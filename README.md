# Listener

Listener is a privacy-forward Android beta for understanding Chinese speech. Whisper runs on the phone with overlapping live transcription and preserves the Chinese transcript; an optional user-selected free OpenRouter model maintains a concise English context summary. The first hardware target is the Samsung Galaxy Z Flip6. The app targets API 35 and supports API 26+.

## Build and install

Prerequisites are Android Studio/JDK 17, Android SDK 35, NDK `28.0.13004108`, and CMake `3.22.1`. Gradle is configured to download missing SDK components when Android licenses have been accepted. The native build fetches whisper.cpp tag `v1.9.2` and pinned Khronos Vulkan/SPIR-V headers into the build directory; it never loads an unpinned branch.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

OR

/Users/karankapur/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For local debug builds that should start with an OpenRouter key already available, copy the placeholder from `local.properties.example` into your git-ignored `local.properties` file:

```properties
OPENROUTER_API_KEY=sk-or-v1-your-key
```

The Settings key stored in encrypted preferences still takes priority. The local build key is only a convenience fallback and is embedded in the debug APK, so use a development key you are comfortable rotating.

On first launch:

1. Review privacy onboarding and continue. The app schedules the pinned 142 MB multilingual Base model download.
2. Wait for Tiny to show as installed under Models.
3. Tap Start and grant microphone permission. Permission approval never starts recording by itself; tap Start again if permission was previously denied.
4. For English context, save an OpenRouter key under Settings and enable summaries. The default remote model is `openrouter/free`, which routes to an available free model that supports the request shape.

## Data and runtime flow

- Audio is captured as 16 kHz mono PCM inside a microphone foreground service. It is buffered only in memory, never written to disk, and never uploaded.
- Energy-based voice activity detection creates bounded utterances. A six-utterance inference queue stops with an explicit error instead of silently dropping audio if the device cannot keep up.
- whisper.cpp transcribes locally with language `zh`, Traditional Chinese prompting, rolling overlap, and automatic Vulkan-to-CPU fallback. The app stores immutable finalized timestamped segments in Room; provisional text and audio remain memory-only.
- When remote summaries are enabled, each selected 500 ms to 10 s interval containing new finalized text sends the prior English context, a small Chinese continuity tail, and the new finalized Chinese delta. The requested response is an English `globalContext` with concise English `details`.
- If a selected OpenRouter model has no structured-output endpoint, the app retries once with `openrouter/free`. Failed remote summaries do not mark Chinese text as summarized.
- Streaming summaries may show parseable draft context as fields arrive, but final commits require validated JSON. Malformed streamed or non-streamed output clears only the draft/streaming indicator, keeps the last valid English context, and records bounded diagnostics such as response length, parse stage, finish reason, `[DONE]` state, a hash, and a short redacted excerpt.
- Offline, timeout, invalid-key, rate-limit, and malformed-output failures keep the last valid English context. They never stop local transcription.
- The OpenRouter key is stored with Android Keystore-backed encrypted preferences. Backups and cleartext network traffic are disabled.
- A daily WorkManager task enforces the chosen local retention period. Session deletion remains explicitly confirmed in the UI.

## Design system

Listener uses Material 3 with semantic primary, surface, outline, and error colors; Roboto platform typography; a 4/8/12/16/24/32/48 dp spacing scale; 48 dp minimum touch targets; bottom navigation for four primary destinations; scrollable content for font scaling; and TalkBack labels for navigation and recording state. Wide landscape windows use two content panes, while portrait, cover-size, and split-screen windows stack them.

## Quality gates

```sh
./gradlew test
./gradlew lintDebug assembleDebug
./gradlew connectedAndroidTest
```

Flip6 acceptance covers portrait, landscape, narrow split-screen, cover-window behavior where Samsung permits it, large fonts, microphone denial/retry, background recording, notification Stop, audio-focus interruption, process recreation, offline transcription, OpenRouter HTTP 401/429 behavior, and a 30-minute Tiny-model soak. Device screenshots, UI dumps, logs, memory, battery, and thermal evidence belong in `artifacts/`.

## Beta limitations

- This is a USB-installed debug beta, not a release-signed or Play Store build.
- Native inference is initially CPU-only and packaged for `arm64-v8a`; Flip6 latency and thermals must be measured before expanding model defaults.
- OpenRouter free-model availability and quotas can change. The app does not invent a remaining-quota value; it reports actual API failures and retains the last valid context.
- No physical Flip6 measurements have been recorded in this repository yet.
