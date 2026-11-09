# My App

A collection of useful Android tools in one app. The home screen lists them; tapping one opens it.

## Tools

| Tool | What it does |
| --- | --- |
| **Screen Recorder** | Records the whole screen to an MP4 video in `Movies/ScreenRecordings` (visible in Gallery). |

### Screen Recorder

- **Start:** the app's button, the floating REC button in any app, or the Quick Settings tile (pull down from the top of the screen).
- **Stop:** the app's button, the Stop action in the recording notification, or the Quick Settings tile. The floating button is hidden while recording, so it is not in the video.
- **Floating button:** turn it on with "Show floating button" in the app. It needs "Display over other apps" permission, which the app asks for once.
- Each recording asks Android's "Start recording?" permission first. Android requires this every time.
- Video only (H.264 in MP4, 30 fps). The screen is scaled so its long side is at most 1920 px.
- Microphone audio: the app asks once for the microphone permission. If you allow it, the video has your voice; if not, the video has no sound. App and system sounds are not recorded.
- Needs Android 10 (API 29) or newer. Storage uses MediaStore, so no storage permission is needed.
- Rotating the phone during a recording is not handled: the capture keeps the size it started with.

## Layout

```
app/src/main/java/io/github/leehj825/myapp/
  HomeActivity.kt              the home screen; lists the tools
  Tools.kt                     the list of tools (name, description, activity)
  recorder/
    ScreenRecorderActivity.kt  app screen: start/stop and the floating button switch
    CaptureRequestActivity.kt  invisible screen that asks for capture permission (used by the floating button and tile)
    RecordingService.kt        foreground service: MediaProjection -> VirtualDisplay -> MediaRecorder -> MediaStore
    FloatingButtonService.kt   the REC button drawn over other apps
    ScreenRecorderTileService.kt  Quick Settings tile
```

## Adding a tool

1. Put its code in its own package, e.g. `myapp/notes/`, with an activity for its screen.
2. Declare the activity in `app/src/main/AndroidManifest.xml` (not exported).
3. Add its name and description to `app/src/main/res/values/strings.xml`.
4. Add a `Tool(...)` entry to `Tools.all` in `Tools.kt`.

## Build

You need JDK 17, the Android SDK (platform 34) and Gradle 8.9:

```sh
gradle assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. Install it with `adb install`, or open the file on the phone.

The workflow `.github/workflows/android-debug.yml` builds the same APK on every branch push and attaches it to the run as **my-app-debug-apk**.

Not tested on a device yet: check on Android 13 and 14 or newer that a recording starts from the app, the floating button and the tile, the file plays, and Stop saves it.
