package rabuchanan2077.video;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.beans.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.channels.FileChannel.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.logging.*;

import javax.swing.*;
import javax.imageio.*;

import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;

/**
This class integrates multiple Gstreamer video feeds into a single composite view,
with projection and viewpoint remapping on each feed.
It was designed with the aim of reproducing the parking camera video systems now available in many cars,
in particular "bird's eye" views created by stitching together video from fisheye cameras on each side of the vehicle.
*/
class VideoMapper {

	private static final Logger logger_ = Logger.getLogger(VideoMapper.class.getName());

	protected final Properties properties_;
		
	protected final BufferedImage outputLayout_;
	protected final Dimension outputResolution_;
	protected final ByteOrder byteOrder_;
	protected final java.util.List<CameraConfiguration> cameraConfiguration_ = new LinkedList<>();
	
	protected BufferedImage outputImage_ = null;
	protected FileChannel outputFileChannel_ = null;
	protected ByteBuffer outputMappedBuffer_ = null;
	protected OutputJComponent outputJComponent_ = null;
	protected final Lock outputLock_ = new ReentrantLock(true);
	
	protected AtomicInteger frames_ = new AtomicInteger();

	public static void main(String[] args) {
	
		args = Gst.init("VideoMapper", args);
		
		Properties properties = new Properties();
		try {
			properties.load(new FileInputStream(System.getProperty("properties", "VideoMapper.properties")));
		}
		catch (Exception ex) {
			System.err.println("No configuration properties loaded, exiting."); // TODO: usage hints
			System.exit(1);
		}
		VideoMapper videoMapper = new VideoMapper(properties);
			
		EventQueue.invokeLater(new Runnable() {

			@Override
			public void run() {

				JFrame frame = new JFrame("VideoMapper");
				frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				frame.add(videoMapper.getOutputJComponent());
				frame.pack();
				frame.setVisible(true);
			}
		});
	}

	public VideoMapper(Properties properties) {
	
		properties_ = properties;
		
		// layout and component size
		BufferedImage layout = null;
		Dimension resolution = null;
		try {
			// layout assigns pixels to output mappers by color
			layout = ImageIO.read(new FileInputStream(properties_.getProperty("layout")));
			resolution = new Dimension(layout.getWidth(), layout.getHeight());
		}
		catch (Exception ex) {
			System.out.println(ex); 
			try {
				String[] s = properties_.getProperty("resolution").split("x");
				resolution = new Dimension(Integer.parseInt(s[0]),Integer.parseInt(s[1]));
			}
			catch (Exception exx) {
				resolution = new Dimension(1280, 720);
			}
		}
		outputLayout_ = layout;
		outputResolution_ = resolution;
		String bo = properties_.getProperty("byte-order");
		byteOrder_ = "BE".equalsIgnoreCase(bo) ? ByteOrder.BIG_ENDIAN
		           : "LE".equalsIgnoreCase(bo) ? ByteOrder.LITTLE_ENDIAN
			   : ByteOrder.nativeOrder(); // TODO: tested only for LE
			   
		outputImage_ = new BufferedImage(outputResolution_.width, outputResolution_.height, BufferedImage.TYPE_INT_RGB); // TODO: check byte order
		outputImage_.setAccelerationPriority(0.0f);
		if (outputLayout_ != null) {
			outputImage_.createGraphics().drawImage(outputLayout_, 0, 0, null);
		}
		
		try {
			outputFileChannel_ = new RandomAccessFile("/tmp/VideoMapper.videoFrame", "rw").getChannel();
			outputMappedBuffer_ = outputFileChannel_.map(MapMode.READ_WRITE, 0, outputResolution_.width * outputResolution_.height * 4).order(byteOrder_);
		}
		catch(Exception ex) {
			ex.printStackTrace();
		}

		// input video streams
		for (int i = 0; ; i++) {
			String cameraName = properties_.getProperty("camera"+i);
			if (cameraName == null) {
				break;
			}
			try {
				cameraConfiguration_.add(new CameraConfiguration(cameraName));
			}
			catch (Exception ex) {
				logger_.log(Level.SEVERE, "Camera configuration failed.", ex);
			}
		}
		if (cameraConfiguration_.isEmpty()) {
			System.err.println("No valid camera configurations loaded, exiting.");
			System.exit(1);
		}
		
		// start video streams
		for (CameraConfiguration cc : cameraConfiguration_) {
			cc.start();
		}
		
		(new java.util.Timer()).schedule(new TimerTask() {
			public void run() {
				System.out.println("FPS:" + frames_.getAndSet(0));
			}
		}, 1000, 1000);
	}
	
	public JComponent getOutputJComponent() {
		
		if (outputJComponent_ == null) {
			outputJComponent_ = new OutputJComponent();
		}
		return outputJComponent_;
	}

	private class OutputJComponent extends JComponent {
	
		@Override
		protected void paintComponent(Graphics g) {

			double scale = Math.min(((double)getWidth())/outputResolution_.width, ((double)getHeight())/outputResolution_.height);
			int w = (int)Math.round(outputResolution_.width * scale);
			int h = (int)Math.round(outputResolution_.height * scale);
			int x = (getWidth()-w)/2;
			int y = (getHeight()-h)/2;

			outputLock_.lock();
			g.drawImage(outputImage_, x, y, w, h, null); // scales from rendered size to renderComponent size
			outputLock_.unlock();
		}
	
		@Override
		public Dimension getPreferredSize() {
			return outputResolution_;
		}

		@Override
		public boolean isOpaque() {
			return true;
		}

		@Override
		public boolean isLightweight() {
			return true;
		}
		
	} // OutputJComponent

	private final class CameraConfiguration {
	
		// configured properties set at initilization time
		public final String name_;
		public final String pipelineString_;
		
		// camera placement
		private final char cameraOrientation_;
		private final double cameraX_;
		private final double cameraY_;
		private final double cameraZ_;
		
		// camera lens properties
		private final double cameraFOVAngle_;
		private final double cameraFOVDiameter_;
		private final double cameraFOVCenterX_;
		private final double cameraFOVCenterY_;
		
		private Dimension cameraResolution_ = null;
		private final Collection<Mapper> mapper_ = new LinkedList<>();
		private int[] map_ = null;
		
		// keep a reference to running pipeline to keep it from getting GCed and crashing
		private Pipeline pipeline_;
		
		public CameraConfiguration(String name) throws Exception {
			
			name_ = name;
			pipelineString_ = properties_.getProperty(name_ + ".pipeline");

			// camera location
			cameraOrientation_ = properties_.getProperty(name_ + ".camera-orientation", "N").toUpperCase().charAt(0); // N|S|E|W
			double cameraNSPosition = Double.parseDouble(properties_.getProperty(name_ + ".camera-NS-position", "0.0")); // relative to origin
			double cameraEWPosition_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-EW-position", "0.0")); // relative to origin
			AffineTransform worldToCamera = new AffineTransform();
			worldToCamera.rotate(Math.PI/180.*(cameraOrientation_=='E' ? 90. : cameraOrientation_=='S' ? 180. : cameraOrientation_=='W' ? 270. : 0.));
			double[] cameraPosition = { Double.parseDouble(properties_.getProperty(name_ + ".camera-EW-position", "0.0")), // E/W relative to origin
			                            Double.parseDouble(properties_.getProperty(name_ + ".camera-NS-position", "0.0"))}; // N/S relative to origin
			worldToCamera.transform(cameraPosition, 0, cameraPosition, 0, 1);
			cameraX_ = cameraPosition[0];
			cameraY_ = cameraPosition[1];
			cameraZ_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-height", "24.0")); // height above origin
			
			// camera properties
			cameraFOVAngle_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-fov-angle", "180.0")); // view in degrees			
			cameraFOVDiameter_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-fov-diameter", "1.0")); // fraction of image width at fov angle
			cameraFOVCenterX_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-fov-center-X", "0.5")); // fraction of image width
			cameraFOVCenterY_ = Double.parseDouble(properties_.getProperty(name_ + ".camera-fov-center-Y", "0.5")); // fraction of image height

			// pixel mappers
			for (int i = 0; ; i++) {
				String mapperName = properties_.getProperty(name_ + ".mapper"+i);
				if (mapperName == null) {
					break;
				}
				try {
					Class<?> mapperClass = Class.forName(properties_.getProperty(mapperName + ".class"));
					mapper_.add((Mapper)mapperClass.getDeclaredConstructor(CameraConfiguration.class, String.class).newInstance(this, mapperName));
				}
				catch (Exception ex) {
					logger_.log(Level.SEVERE, "Mapper initialization failed.", ex);
				}
			}
			if (mapper_.isEmpty()) {
				throw new RuntimeException("No mappers configured.");
			}
			
		}
		
		public void start() {
		
			Bin bin = Bin.launch(pipelineString_, true);
			pipeline_ = new Pipeline();
			AppSink appSink = new AppSink(name_);
			appSink.set("drop", true);
			appSink.set("max-buffers", 1);
			appSink.setCaps(new Caps("video/x-raw," + (byteOrder_ == ByteOrder.LITTLE_ENDIAN ? "format=BGRx" : "format=xRGB")));
			pipeline_.addMany(bin, appSink);
			pipeline_.linkMany(bin, appSink);
			pipeline_.play();
			(new Thread() {
				public void run() {
					while (true) {
						frames_.getAndIncrement();
						handleSample(appSink.pullSample());
					}
				}
			}).start();
		}
		
		public void handleSample(Sample sample) {

			Structure capsStruct = sample.getCaps().getStructure(0);
			int w = capsStruct.getInteger("width");
			int h = capsStruct.getInteger("height");
			if (cameraResolution_ == null || cameraResolution_.width != w || cameraResolution_.height != h) {
				cameraResolution_ = new Dimension(w, h);
				map_ = null;
			}
			if(map_ == null) { // initialize pixel mapping table
				map_ = getMap(); 
			}
			
			// update the output image with pixels from this camera frame
			Buffer buffer = sample.getBuffer();
			ByteBuffer bb = buffer.map(false);
			if (bb != null) { // not sure why null would ever be encountered
				processFrame(bb.order(byteOrder_).asIntBuffer());
				buffer.unmap();
			}
			sample.dispose();
			
			if (outputJComponent_ != null) {
				outputJComponent_.repaint();
			}
		}

		public void processFrame(IntBuffer cameraFramePixels) {

			outputLock_.lock();
			// copy pixels from the current frame to the output
			int[] outputPixels = ((DataBufferInt)outputImage_.getRaster().getDataBuffer()).getData();
			for(int i = 0; i < map_.length; i+=2) {
				int outputPixelIndex = map_[i+0];
				int cameraPixelIndex = map_[i+1];
				outputPixels[outputPixelIndex] = cameraFramePixels.get(cameraPixelIndex);
			}
			
			try {
				outputMappedBuffer_.rewind();
				FileLock lock = outputFileChannel_.tryLock();
				if (lock != null) {
					outputMappedBuffer_.asIntBuffer().put(outputPixels);
					lock.release();
				}
			}
			catch(Exception ex) {
				ex.printStackTrace();
			}
			outputLock_.unlock();
		}
	
		public int[] getMap() {
		
			int[] m = new int[outputResolution_.width*outputResolution_.height];
			for (int i = 0; i < m.length; i++) {
				m[i] = -1;
			}
			for (Mapper mapper : mapper_) {
				int[] mm = mapper.getMap();
				for (int i = 0; i < mm.length; i += 2) {
					m[mm[i]] = mm[i+1];
				}
			}
			int n = 0;
			for (int i = 0; i < m.length; i++) {
				if (m[i] > 0 && m[i] < cameraResolution_.width*cameraResolution_.height) {
					n++;
				}
			}
			int[] map = new int[2*n];
			n = 0;
			for (int i = 0; i < m.length; i++) {
				if (m[i] > 0 && m[i] < cameraResolution_.width*cameraResolution_.height) {
					map[n++] = i;
					map[n++] = m[i];
				}
			}
			return map;
		}
		
	
		public abstract class Mapper {
			
			protected final String name_;
			
			// configured properties set at initilization time
			protected final String[] bounds_;
			protected final String flip_;
			protected final String rotate_;
			protected final boolean[][] mask_;
			
			public Mapper(String name) {
			
				name_ = name;
				bounds_ = properties_.getProperty(name_ + ".bounds", "0,0,1,1").split(",");
				flip_ = properties_.getProperty(name_ + ".flip", "");
				rotate_ = properties_.getProperty(name_ + ".rotate", "0");
				Color maskColor = null;
				boolean[][] mask = null;
				try {
					int mc = Integer.parseInt(properties_.getProperty(name_ + ".maskColor"), 16) & 0x00FFFFFF;
					mask = new boolean[outputResolution_.width][outputResolution_.height];
					if (outputLayout_ != null) {
						for (int x = 0; x < mask.length; x++) {
							for (int y = 0; y < mask[x].length; y++) {
								mask[x][y] = mc == (outputLayout_.getRGB(x, y)&0x00FFFFFF);
							}
						}
					}
				}
				catch (Exception ex) {
				}
				mask_ = mask;
			}
			
			public abstract int[] getMap();
		}
		
		public abstract class FisheyeMapper extends Mapper {

			// input dependent values initialized or updated in getMap()
			protected int boundsX_;
			protected int boundsY_;
			protected int boundsWidth_;
			protected int boundsHeight_;
			protected final AffineTransform outputToRendering_;
					
			public FisheyeMapper(String name) {
			
				super(name);

				try {
					boundsX_ = (int)Math.round(Double.parseDouble(bounds_[0])*outputResolution_.width);
					boundsY_ = (int)Math.round(Double.parseDouble(bounds_[1])*outputResolution_.height);
					boundsWidth_ = (int)Math.round(Double.parseDouble(bounds_[2])*outputResolution_.width);
					boundsHeight_ = (int)Math.round(Double.parseDouble(bounds_[3])*outputResolution_.height);
				}
				catch (Exception ex) {
					ex.printStackTrace();
					boundsX_ = 0;
					boundsY_ = 0;
					boundsWidth_ = outputResolution_.width;
					boundsHeight_ = outputResolution_.height;
				}
				
				outputToRendering_ = new AffineTransform();
				outputToRendering_.translate(boundsWidth_/2., boundsHeight_/2.);
				try {
					outputToRendering_.rotate(-Math.PI/180.*Double.parseDouble(rotate_)); // degrees positive CW
					outputToRendering_.scale("H".equalsIgnoreCase(flip_) ? -1 : 1, "V".equalsIgnoreCase(flip_) ? -1 : 1 );
				}
				catch (Exception ex) {
					ex.printStackTrace();
				}
				outputToRendering_.translate(-boundsWidth_/2., -boundsHeight_/2.);
				outputToRendering_.translate(-boundsX_, -boundsY_);
				
			}
			
			public int[] getMap() {
		
				int[] map = new int[2*outputResolution_.width*outputResolution_.height];
				int i = 0;
				for (int outputImageY = 0; outputImageY < outputResolution_.height; outputImageY++) {
					for (int outputImageX = 0; outputImageX < outputResolution_.width; outputImageX++) {

						double[] s2r = output_image_2_rendering(outputImageX, outputImageY);
						if (s2r == null) {
							continue;
						}
						double rendering_x = s2r[0];
						double rendering_y = s2r[1];
						
						double[] r2w = rendering_2_world(rendering_x, rendering_y);
						if (r2w == null) {
							continue;
						}
						double world_o_clock = r2w[0];
						double world_from_center = r2w[1];

						double[] w2cv = world_2_camera_view(world_o_clock, world_from_center);
						if (w2cv == null) {
							continue;
						}
						double camera_view_o_clock = w2cv[0];
						double camera_view_radius = w2cv[1];
						
						int[] cv2ci = camera_view_2_camera_image(camera_view_o_clock, camera_view_radius);
						if (cv2ci == null) {
							continue;
						}
						int camera_image_x = cv2ci[0];
						int camera_image_y = cv2ci[1];

						int outputImageIndex = outputImageY*outputResolution_.width + outputImageX;
						int cameraImageIndex = camera_image_y*cameraResolution_.width + camera_image_x;
						map[i++] = outputImageIndex;
						map[i++] = cameraImageIndex;
					}
				}
				return Arrays.copyOf(map, i);
			}

			protected double[] // {camera_view_o_clock, camera_view_radius}
			world_2_camera_view(double world_o_clock, double world_from_center) {

				double camera_view_o_clock = world_o_clock;
				double f = cameraFOVAngle_/180.;
				double camera_view_radius = Math.sin(world_from_center/f) / Math.sin(Math.PI/2/f);
				return new double[] {camera_view_o_clock, camera_view_radius};
			}

			protected int[] // {camera_image_x, camera_image_y}
			camera_view_2_camera_image(double camera_view_o_clock, double camera_view_radius) {

				double x = camera_view_radius * Math.sin(camera_view_o_clock);
				double y = camera_view_radius * Math.cos(camera_view_o_clock);

				double r = cameraFOVDiameter_ * cameraResolution_.width / 2.;
				x *= r;
				y *= r;
				x += cameraFOVCenterX_ * cameraResolution_.width;
				y += cameraFOVCenterY_ * cameraResolution_.height;

  				int camera_image_x = (int)Math.round(x);
				int camera_image_y = (int)Math.round(y);
				
				// optional: drop pixels if out of frame
// 				if (camera_image_x < 0 || camera_image_x >= cameraResolution_.width || camera_image_y < 0 || camera_image_y >= cameraResolution_.height) {
// 					return null;
// 				}
				
				// otherwise use nearest in-frame pixel
				camera_image_x = Math.max(Math.min(camera_image_x, cameraResolution_.width-1), 0);
				camera_image_y = Math.max(Math.min(camera_image_y, cameraResolution_.height-1), 0);
				
				return new int[] {camera_image_x, camera_image_y};
			}
			
			protected double[] // {rendering_x, rendering_y}
			output_image_2_rendering(int outputImageX, int outputImageY) {
			
				if (outputImageX < boundsX_ || outputImageX >= boundsX_+boundsWidth_
				 || outputImageY < boundsY_ || outputImageY >= boundsY_+boundsHeight_) {
				 	return null;
				}
				if (mask_ != null && !mask_[outputImageX][outputImageY]) {
					return null;
				}
				
				double[] rxy = {outputImageX, outputImageY};
				
				outputToRendering_.transform(rxy, 0, rxy, 0, 1);
				
				// TODO: check of between rendering corners
				
				double rendering_x = rxy[0];
				double rendering_y = rxy[1];
				
				return new double[] {rendering_x, rendering_y}; 
			}			
			
			protected abstract double[] // {world_o_clock, world_from_center}
			rendering_2_world(double rendering_x, double rendering_y);

		} // FisheyeMapper
		
		public class BirdseyeMapper extends FisheyeMapper {
		
			private final double projectionWidth_;
			private final double projectionHeight_;
			private final double projectionOriginX_;
			private final double projectionOriginY_;
			private final double renderingWidth_;
			private final double renderingHeight_;
			private final double renderingOriginX_;
			private final double renderingOriginY_;
			private final double scaleX_;
			private final double scaleY_;
		
			public BirdseyeMapper(String name) {
				super(name);

				Shape renderingBounds = outputToRendering_.createTransformedShape(new Rectangle(boundsX_, boundsY_, boundsWidth_, boundsHeight_));
				renderingWidth_ = renderingBounds.getBounds().getWidth();
				renderingHeight_ = renderingBounds.getBounds().getHeight();
				
				renderingOriginX_ = renderingBounds.getBounds().getX() + renderingWidth_/2;
				renderingOriginY_ = renderingBounds.getBounds().getY() + renderingHeight_;

				projectionWidth_ = Double.parseDouble(properties_.getProperty(name_ + ".projection-width", "240.0")); // width of ground area in view
				scaleX_ = projectionWidth_ / renderingWidth_;
				scaleY_ = -scaleX_;
				projectionHeight_ = scaleY_ * renderingHeight_;
				projectionOriginX_ = -cameraX_; // TODO: untested, check sign
				projectionOriginY_ = -cameraY_;
			}
			
			protected double[] // {world_o_clock, world_from_center}
			rendering_2_world(double rendering_x, double rendering_y) {
			
				double projection_z = -cameraZ_;
				double projection_x = (rendering_x - renderingOriginX_) * scaleX_ + projectionOriginX_;
				double projection_y = (rendering_y - renderingOriginY_) * scaleY_ + projectionOriginY_;
				
				if (projection_y < 0) {
					return null;
				}
				
				double xz = Math.sqrt(projection_x*projection_x + projection_z*projection_z);

				double world_o_clock = Math.PI - Math.atan2(projection_x, projection_z);
				double world_from_center = Math.atan2(xz, projection_y);
				
				return new double[] {world_o_clock, world_from_center};
			}

		} // BirdseyeMapper
		
		public class PanoramaMapper extends FisheyeMapper {
		
			private final double horizontalFOV_;
			private final double horizontalFOVleft_;
			private final double verticalFOV_;
			private final double verticalFOVtop_;
		
			public PanoramaMapper(String name) {
				super(name);
				horizontalFOV_ = Double.parseDouble(properties_.getProperty(name_ + ".horizontal-fov", "180.0"));
				horizontalFOVleft_ = Double.parseDouble(properties_.getProperty(name_ + ".horizontal-fov-left", ""+(-.5*horizontalFOV_)));
				verticalFOV_ = Double.parseDouble(properties_.getProperty(name_ + ".vertical-fov", "120.0"));
				verticalFOVtop_ = Double.parseDouble(properties_.getProperty(name_ + ".vertical-fov-top", ""+(-.5*verticalFOV_)));
			}
			
			protected double[] // {world_o_clock, world_from_center}
			rendering_2_world(double rendering_x, double rendering_y) {

				double vertical_fov_angle = verticalFOV_ * Math.PI / 180.;
				double vertical_fov_angle_top = verticalFOVtop_ * Math.PI / 180.;
				double horizontal_fov_angle = horizontalFOV_ * Math.PI/180.;
				double horizontal_fov_angle_left = horizontalFOVleft_ * Math.PI/180.;
  
				double vertical_angle = (vertical_fov_angle_top+vertical_fov_angle/2) + (rendering_y-boundsHeight_/2.) * vertical_fov_angle / boundsHeight_;
				double horizontal_angle = (horizontal_fov_angle_left+horizontal_fov_angle/2) + (rendering_x-boundsWidth_/2.) * horizontal_fov_angle / boundsWidth_;
  
				double projection_z = -Math.sin(vertical_angle);
				double xy = Math.cos(vertical_angle);
				double projection_x = xy * Math.sin(horizontal_angle);
				double projection_y = xy * Math.cos(horizontal_angle);
 
				double xz = Math.sqrt(projection_x*projection_x + projection_z*projection_z);

				double world_o_clock = Math.PI - Math.atan2(projection_x, projection_z);
				double world_from_center = Math.atan2(xz, projection_y);
				return new double[] {world_o_clock, world_from_center};
			}

		} // PanoramaMapper

	} // CameraConfiguration
}
