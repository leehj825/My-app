# My App

A collection of useful Android tools in one app. The home screen lists them; tapping one opens it.

## Tools

| Tool | What it does |
| --- | --- |
| **Screen Recorder** | Records the whole screen to an MP4 video in `Movies/ScreenRecordings` (visible in Gallery). |

### Screen Recorder

- Press **Start recording**, then accept the system "Start recording?" dialog.
- A notification shows while recording; its **Stop recording** button, the app's button, or stopping capture from the system bar all end it.
- Video only (H.264 in MP4, 30 fps). The screen is scaled so its long side is at most 1920 px.
- No microphone or internal audio yet.
- Needs Android 10 (API 29) or newer. Storage uses MediaStore, so no storage permission is needed.
- Rotating the phone during a recording is not handled: the capture keeps the size it started with.

## Layout

```
app/src/main/java/io/github/leehj825/myapp/
  HomeActivity.kt           the home screen; lists the tools
  Tools.kt                  the list of tools (name, description, activity)
  recorder/
    ScreenRecorderActivity.kt  start/stop screen and permission request
    RecordingService.kt        foreground service: MediaProjection -> VirtualDisplay -> MediaRecorder -> MediaStore
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

The workflow `.github/workflows/android-debug.yml` builds the same APK on changes to the app and attaches it to the run as **my-app-debug-apk**.

Not tested on a device yet: check on Android 13 and 14 or newer that a recording starts, the file plays, and Stop saves it.
