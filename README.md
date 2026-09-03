# Cam0

Cam0 is a lightweight camera application using minimum system resources.
Cam0 aims to bridge the gap between heavy OEM camera bloatware and overly
stripped down open source alternatives, while needing as little
permissions as possible.

A from scratch, fully open source Android camera app. GuiLite renders
the UI and CameraX drives the actual camera. Long term goal of the project is to
get close to OEM camera quality, the project is still MVP do not expect OEM quality as of now, this is simply as of now a camera.

Licensed under the GPLv3 (see `LICENSE`).

Confirmed building and running with no changes needed on:
- Google Pixel 9a running GrapheneOS
- Moto G62 5G running stock Android
- These are the only devices i own and can confidently test on i have 0 reason to suspect why it wouldnt work on other devices

## Status

MVP with: live preview, pinch to zoom, tap to focus, capture to JPEG, a folder you pick once, and an in app
gallery to review shots without leaving the app.

Missing as of MVP: flash
control, front/back camera switch, exposure control, RAW, video.

## Setup / first run

First launch asks for the Camera permission, then a dialog explains
that Cam0 needs a folder to save/load photos from before the system
folder picker opens (a subfolder like Pictures/Cam0 works well). Two
prompts total and thats it.

## Architecture

```
cam0/
├── core/                       GuiLite UI code (C++)
│   ├── camera_overlay.h/.cpp   Draws shutter/gallery buttons, zoom readout, focus reticle, hit-tests touches
│   └── CMakeLists.txt
├── third_party/GuiLite/       Vendored copy of github.com/idea4good/GuiLite (plain files, tracked directly)
└── android/                  Android Studio project
    └── app/src/main/
        ├── java/org/cam0/app/
        │   ├── MainActivity.kt        CameraX setup, capture, zoom, focus, save orchestration
        │   ├── CameraOverlayView.kt   Transparent SurfaceView hosting GuiLite + gesture routing
        │   ├── NativeBridge.kt        The only class touching JNI
        │   ├── PhotoStore.kt          SAF folder grant + photo enumeration (DocumentsContract)
        │   ├── GalleryActivity.kt     Thumbnail grid (GridView/BaseAdapter)
        │   ├── PhotoViewerActivity.kt Full screen viewer host
        │   └── ZoomableImageView.kt   Pinch/pan image view (Matrix + gesture detectors)
        └── cpp/
            ├── jni_bridge.cpp         start/touch/updateBitmap/etc JNI glue
            ├── jni_callback.cpp       lets C++ call back into Kotlin (shutter + gallery taps)
            └── CMakeLists.txt
```

- **`PreviewView` (CameraX)** live preview, hardware path,
  CameraX/Camera2 owns it entirely.
- **`CameraOverlayView`** a transparent `SurfaceView`
  (`setZOrderOnTop(true)`, `PixelFormat.TRANSLUCENT`) that shows
  whatever GuiLite draws the shutter button, gallery
  button, zoom readou and a brief focus reticle wherever you tap to focus.

## Building

GuiLite is vendored directly in `third_party/GuiLite`

You'll need Android Studio obviously.

Open the `android/` folder in Android Studio. If it
asks you to pick a Gradle JVM, use JDK 21, then let it sync.

## Planned: an actual settings screen

Not built yet, settings will expose basically
every toggle a user could want, following my personal belief of "the person using the app should decide how they use the app.", a FOSS camera apps settings screen doesn't need to be fancy a plain list of every option in a key/value config style, plus a
"reset to shipped defaults" button, covers it more than enough.

## License

GPLv3. GuiLite itself is Apache-2.0 (see `third_party/GuiLite`), which
is compatible for inclusion here.
