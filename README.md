# parking-lot-video

## Purpose
This project attempts to emulate the parking camera video systems now available in many cars,
in particular "bird's eye" views created by stitching together video from fisheye cameras on each side of the vehicle.

## Contents
It consists of one java class, VideoMapper, which takes multiple camera feeds, applies projection or viewpoint remapping,
and assembles them into a single video stream displayed in a JComponent. The video streams and remapping functions are
configurable via a properties file and pluggable classes.

## Status
This is a more or less working prototype, not finished code.

## Dependencies
It has a direct dependency on gst1-java-core (<https://github.com/gstreamer-java/gst1-java-core>), and indirect dependencies
on Gstreamer (<https://gstreamer.freedesktop.org/>) and JNA (<https://github.com/java-native-access/jna>).


