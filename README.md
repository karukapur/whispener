# Listener

Listener is a privacy-forward Android beta for understanding Chinese speech. Whisper runs on the phone with overlapping live transcription and preserves the Chinese transcript; an optional user-selected OpenRouter or Groq model maintains a concise English context summary. The first hardware target is the Samsung Galaxy Z Flip6. The app targets API 35 and supports API 26+.

## Build and install

Prerequisites are Android Studio/JDK 17, Android SDK 35, NDK `28.0.13004108`, and CMake `3.22.1`. Gradle is configured to download missing SDK components when Android licenses have been accepted. The native build fetches whisper.cpp tag `v1.9.2` and pinned Khronos Vulkan/SPIR-V headers into the build directory; it never loads an unpinned branch.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

OR

/Users/karankapur/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For local debug builds that should start with remote-provider keys already available, copy the relevant placeholders from `local.properties.example` into your git-ignored `local.properties` file:

```properties
OPENROUTER_API_KEY=sk-or-v1-your-key
GROQ_API_KEY=gsk_your-key
```

The OpenRouter key saved in Settings uses encrypted preferences and takes priority over the OpenRouter build key. The Groq key is currently build-only. Local build keys are embedded in the debug APK, so use development keys you are comfortable rotating.

On first launch:

1. Review privacy onboarding and continue. The app schedules the pinned 142 MB multilingual Base model download.
2. Wait for Tiny to show as installed under Models.
3. Tap Start and grant microphone permission. Permission approval never starts recording by itself; tap Start again if permission was previously denied.
4. For English context, configure at least one remote key and enable summaries. The default is Groq GPT-OSS 20B when `GROQ_API_KEY` is present in a debug build; Settings also offers `openrouter/free` and five compatible low-latency OpenRouter models when an OpenRouter key is configured.

## Data and runtime flow

- Audio is captured as 16 kHz mono PCM inside a microphone foreground service. It is buffered only in memory, never written to disk, and never uploaded.
- Energy-based voice activity detection creates bounded utterances. A six-utterance inference queue stops with an explicit error instead of silently dropping audio if the device cannot keep up.
- whisper.cpp transcribes locally with language `zh`, Traditional Chinese prompting, rolling overlap, and automatic Vulkan-to-CPU fallback. The app stores immutable finalized timestamped segments in Room; provisional text and audio remain memory-only.
- When remote summaries are enabled, each selected interval containing at least three new finalized Chinese characters sends the prior English context, a small Chinese continuity tail, and the new finalized Chinese delta to the selected provider. Smaller deltas remain pending until more finalized text arrives. The requested response is an English `globalContext` with concise English `details`.
- If a selected OpenRouter model has no structured-output endpoint, the app retries once with `openrouter/free`. Failed remote summaries do not mark Chinese text as summarized.
- Groq uses the fixed `openai/gpt-oss-20b` model with low reasoning effort and strict non-streaming structured output. Remote summaries currently use a shared adaptive cadence trial across the remote model suite: 5 seconds for the first minute, 8 seconds for the second minute, and 10 seconds afterward. The schedule is based on Groq cadence experiments and should be revalidated before treating it as OpenRouter-specific evidence.
- Streaming summaries may show parseable draft context as fields arrive, but final commits require validated JSON. Malformed streamed or non-streamed output clears only the draft/streaming indicator, keeps the last valid English context, and records bounded diagnostics such as response length, parse stage, finish reason, `[DONE]` state, a hash, and a short redacted excerpt.
- Rate-limited Groq/OpenRouter responses record safe provider headers such as retry-after, remaining request/token counts, and reset windows when returned. The app then pauses retries for that model during the cooldown while preserving unsummarized finalized Chinese text.
- Offline, timeout, invalid-key, rate-limit, and malformed-output failures keep the last valid English context. They never stop local transcription.
- The OpenRouter Settings key is stored with Android Keystore-backed encrypted preferences. Debug build keys are compiled into the APK. Backups and cleartext network traffic are disabled.
- A daily WorkManager task enforces the chosen local retention period. Session deletion remains explicitly confirmed in the UI.
- Each saved-session tile keeps Edit, Export transcript, Share trace, and Delete in one accessible icon row. Traces are shared from temporary app cache rather than saved directly to Downloads.

## Design system

Listener uses stable Material 3 with dynamic light and dark color on Android 12+, complete Listener-blue fallback palettes, shared typography/shape/spacing tokens, and purposeful state motion. It retains Roboto platform typography, 48 dp minimum touch targets, bottom navigation for four primary destinations, scrollable content for font scaling, and TalkBack labels for navigation and recording state. The Listen screen treats English context as the primary tonal surface while keeping the Traditional Chinese transcript visible; wide landscape windows use two resizable content panes, while portrait, cover-size, and split-screen windows stack them.

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
- OpenRouter and Groq free-model availability and quotas can change. The app does not invent a remaining-quota value; it reports actual API failures, logs safe rate-limit headers when available, and retains the last valid context.
- No physical Flip6 measurements have been recorded in this repository yet.
