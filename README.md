# My App

A collection of useful Android tools in one app. The home screen lists them; tapping one opens it.

## Tools

| Tool | What it does |
| --- | --- |
| **Screen Recorder** | Records the whole screen to an MP4 video in `Movies/ScreenRecordings` (visible in Gallery). |
| **Notes** | Write or dictate a note. Each note is saved with its date and time. |
| **Daily journal** | One entry a day, with a reminder at a time you choose. |
| **Secure notes** | Notes locked with your fingerprint or screen lock, stored encrypted. |
| **Voice settings** | The language and voice for reading aloud and for dictation. |
| **Chat** | Chat with a GGUF language model on the phone (llama.cpp), or with Gemini in the cloud. |
| **Image** | Make pictures from a description, with a GGUF image model on the phone (stable-diffusion.cpp) or with Google's image model. |

Chat and Image are only shown on phones where their on-phone library loads; Image with Gemini and Chat with Gemini
need a build that has a `GEMINI_API_KEY`.

### Screen Recorder

- **Start:** the app's button, the floating REC button in any app, or the Quick Settings tile (pull down from the top of the screen).
- **Stop:** the app's button, the Stop action in the recording notification, or the Quick Settings tile. The floating button is hidden while recording, so it is not in the video.
- **Floating button:** turn it on with "Show floating button" in the app. It needs "Display over other apps" permission, which the app asks for once.
- Each recording asks Android's "Start recording?" permission first. Android requires this every time.
- Video only (H.264 in MP4, 30 fps). The screen is scaled so its long side is at most 1920 px.
- Sound: when the app asks for the audio permission (once), the video has the sound that apps play, mixed with your microphone. If refused, the video has no sound. An app can refuse to let its sound be recorded; then it is silent in the video.
- Needs Android 10 (API 29) or newer. Storage uses MediaStore, so no storage permission is needed.
- Rotating the phone during a recording is not handled: the capture keeps the size it started with.

### Chat

- **Load model** copies a GGUF language model into the app. Chats are saved on the phone; **Memory** sets how many
  earlier messages go with each new one. **Search the web** (Tavily) adds web results; **Use Gemini** sends the chat to Google.

### Image

Pictures are saved to `Pictures/MyApp` and listed in **History** with the prompt and settings, so they can be shared
or made again.

**On the phone** (leave "Use Gemini" unticked):

- **Load model** takes one GGUF image model file that holds the whole model: Stable Diffusion 1.x, SDXL, SDXS, or the
  SDXL variants (Lightning, Hyper-SD, DMD2). Models that need extra text-encoder or VAE files (Flux, SD3, Qwen-Image,
  Z-Image) are not supported.
- **Models are kept in a list.** **Choose model** switches between them, and **Remove model** deletes a copy (a model
  read in place is only taken off the list). The settings are remembered for each model.
- **Without copying:** allow "All files access" with **Allow loading without copying**, and a model picked from the
  phone's own storage is read where it is. Otherwise it is copied into the app first (several GB).
- **Model type** sets the starting steps, size and sampler: Stable Diffusion 1.5 (20 steps, 512 px), SDXL (10 steps,
  768 px), SDXL fast (4 steps) or SDXS (1 step). Each can then be changed with **Size**, **Sampler** and **Steps**.
  A new file starts on the type its name suggests.
- **Negative prompt**, **Seed** (empty is random; the seed used is shown with the timing), **CPU threads** (default 6
  for the Galaxy Z Fold8; changing it reloads the model).
- **Progress and Cancel:** the line under the picture shows the step reached, and Cancel stops the picture.
- **Improve the prompt** rewrites a short idea as a detailed description with the Chat tool's model. The chat model
  is loaded for the request and freed afterwards, so load one in Chat first.
- **Start from a picture** (image to image): pick a photo, or use the last picture. The new picture is made as a
  change of it, by **Strength** (0.05 to 1). Clear the start picture to go back to text prompts.
- **Upscaler:** **Load upscaler** takes an ESRGAN model file; **Upscale the last picture** makes it 2 or 4 times
  larger (up to 3072 px on a side).
- When a model will not load or a picture fails, the library's message is shown on screen and written to Android's
  log (tag `ImageActivity` and `MyAppSd`). Each picture shows how long it took.

**With Gemini:** the description is sent to Google. If Google's picture quota is used up (HTTP 429) and a model is
loaded on the phone, the picture is made on the phone instead.

## Layout

```
app/src/main/java/io/github/leehj825/myapp/
  HomeActivity.kt              the home screen; lists the tools
  Tools.kt                     the list of tools (name, description, activity)
  recorder/                    Screen Recorder: activity, capture request, service, floating button, tile
  notes/  journal/  secure/  voice/    Notes, Daily journal, Secure notes, Voice settings
  chat/
    ChatActivity.kt            the chat screen
    LlmEngine.kt               llama.cpp through JNI
    GeminiClient.kt            Gemini in the cloud
    ChatStore.kt  WebSearch.kt saved chats; Tavily web search
  image/
    ImageActivity.kt           the Image screen: settings, progress, history, upscaler
    LocalImage.kt              stable-diffusion.cpp through JNI
    GoogleImage.kt             Google's image model
    ModelStore.kt              the list of image models
    ImageHistory.kt            the list of pictures made
    PromptWriter.kt            improves a prompt with the chat model
app/src/main/cpp/
  llm_jni.cpp                  the llama.cpp bridge
  sd_jni.cpp                   the stable-diffusion.cpp bridge (pictures, progress, cancel, upscaler)
  CMakeLists.txt               builds both libraries; stable-diffusion.cpp as its own CMake project
app/src/test/                  unit tests
```

## Adding a tool

1. Put its code in its own package, e.g. `myapp/notes/`, with an activity for its screen.
2. Declare the activity in `app/src/main/AndroidManifest.xml` (not exported).
3. Add its name and description to `app/src/main/res/values/strings.xml`.
4. Add a `Tool(...)` entry to `Tools.all` in `Tools.kt`.

## Build

You need JDK 17, the Android SDK (platform 34), the NDK 27.0.12077973 and Gradle 8.9 or newer:

```sh
gradle assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. Install it with `adb install`, or open the file on the phone.
The first build downloads llama.cpp and stable-diffusion.cpp and takes several minutes.

Keys come from the build environment and are built into the APK: `GEMINI_API_KEY` (Chat and Image with Gemini) and
`TAVILY_API_KEY` (web search). Anyone who gets the APK can read them out of it.

**Unit tests:** `gradle testDebugUnitTest`.

**GPU (Vulkan) build:** `gradle assembleDebug -PvulkanImage=true` builds the image library with the GPU backend. The
Image tool then shows **Use the GPU**, and says on each picture whether the GPU or the CPU made it. The normal build
is unchanged.

**GitHub workflows:**

- `.github/workflows/android-debug.yml` builds the APK on every push, runs the unit tests, and attaches the APK to the
  run as **my-app-debug-apk**.
- `.github/workflows/android-vulkan.yml` builds the Vulkan APK on pushes to `claude/` branches and on demand, as
  **my-app-vulkan-debug-apk**. It is separate, so a problem with the GPU build never stops the normal APK.

The native code is built for 64-bit ARM only. The image library uses the 8-bit matrix instructions (i8mm), which the
Galaxy Z Fold8 has; a phone without them would crash in it.

Not tested on a device yet: check on the phone that a recording starts and saves, a model loads, a picture is made,
Cancel stops it, History and Share work, and (in the Vulkan APK) the GPU makes a picture.
