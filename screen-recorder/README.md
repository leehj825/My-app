# Screen Recorder

A small Android app that records the whole screen to an MP4 video.

- Press **Start recording**, then accept the system "Start recording?" dialog.
- A notification shows while recording; its **Stop recording** button, the app's button, or stopping capture from the system bar all end it.
- Videos are saved to **Movies/ScreenRecordings** and appear in the Gallery and Files apps.

## Details

- Android 10 (API 29) or newer. Target SDK 34.
- Video only (H.264 in an MP4 container, 30 fps). The bitrate is about 4 bits per pixel; the screen is scaled down so its long side is at most 1920 px.
- No microphone or internal audio yet.
- Storage uses MediaStore, so the app needs no storage permission. The file is hidden from other apps until the recording is finished.
- Rotating the phone during a recording is not handled: the capture keeps the size it started with.

## Layout

```
app/src/main/java/io/github/leehj825/screenrecorder/
  MainActivity.kt        the button and status; asks for the capture permission
  RecordingService.kt    foreground service: MediaProjection -> VirtualDisplay -> MediaRecorder -> MediaStore
```

## Build

You need JDK 17, the Android SDK (platform 34) and Gradle 8.9:

```sh
cd screen-recorder && gradle assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. Install it with `adb install`, or open the file on the phone.

The repository's workflow `.github/workflows/screen-recorder-debug.yml` builds the same APK on changes to this folder; download it from the run's **screen-recorder-debug-apk** artifact.

## Not tested on a device yet

This was written without running on a phone or emulator. Check on Android 13 and 14 or newer: the recording starts, the file plays, and Stop saves it.
