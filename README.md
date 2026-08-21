# Listener

Listener is a privacy-forward Android prototype for live **Traditional Chinese** transcription and optional, ChatGPT-style context updates. It targets API 35, supports API 26+, and is designed around the Samsung Galaxy Z Flip6's portrait, split-screen, folded cover, and unfolded/landscape window sizes.

## Setup

1. Install Android Studio with Android SDK 35 and JDK 17.
2. Run `./gradlew :app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.
3. On first use, review privacy onboarding and grant microphone (and Android 13+ notification) permission. Recording **never starts with permission grant**: press **開始** explicitly. The persistent notification provides an immediate Stop action.
4. In Settings → Remote context, enter an OpenRouter API key. It is encrypted using an Android Keystore-backed master key and is never logged. Remote summaries may be disabled while local transcription continues.

No Whisper API key or cloud speech service is required. For production, build the maintained `whisper.cpp` Android example/JNI library as an AAR and implement the small `WhisperEngine` boundary. This repository deliberately does not link the desktop Open Super Whisper application. Its separation of model discovery, manifests, downloads, and inference sessions is useful architectural inspiration; its desktop runtime is not Android-compatible.

## Architecture and privacy boundary

* `audio/` owns the explicitly initiated foreground microphone service and lifecycle state machine. PCM is 16 kHz mono. The service is `START_NOT_STICKY`, stops synchronously on request, and will not silently resume after process death.
* `speech/` is the Android inference boundary for whisper.cpp, fixed to language `zh`, streaming PCM chunks through VAD and emitting partial/final timestamped results. Implementations should attempt NNAPI/GPU acceleration and fall back to CPU after a device capability check.
* `data/session/` stores sessions, immutable original timestamped segments, separately edited display text, summaries, cadence, and installed model metadata in Room. Cascade deletion occurs only after UI confirmation. Export uses edited display text without destroying original segments.
* `models/` keeps JSON manifests separate from binaries. WorkManager downloads are resumable via HTTP Range, cancellable, checksum-gated, and report progress. An actively loaded model cannot be deleted. Version selection must expose upgrades and explicit downgrades.
* `context/` fetches OpenRouter's live `/api/v1/models` catalog rather than hard-coding “DeepSeek V4.” It submits **only** prior global context, a bounded recent transcript window, and newly finalized transcript text at the selected 5/10-second cadence. It requires `globalContext` plus exactly 2–3 `details`, retains the last valid result, and treats offline, HTTP 401, 429, timeouts, and malformed output as non-fatal UI states.

### Exactly what leaves the phone

When (and only when) the user enables remote summarization, finalized **text excerpts**, the previous global summary, and the recent bounded text window leave the phone for the selected OpenRouter model. Raw audio, partial hypotheses, model files, session titles, database contents, and the API key do not form prompt content. OpenRouter and the chosen provider have their own retention policies; review those before use. A retention slider governs local session deletion.

## Model choices and device benchmark

| Quantized multilingual model | Approx. download | Relative speed | Approx. working memory | Guidance |
|---|---:|---|---:|---|
| Tiny | 75 MB | Fastest | 250 MB | Lowest accuracy; conservative default |
| Base | 142 MB | Balanced | 400 MB | Recommended candidate after measurement |
| Small | 466 MB | Slower | 1 GB | Do not default before sustained tests |

Sizes and memory are planning estimates and vary by quantization/build. **No physical Flip6 measurements have been recorded in this repository yet**, so none is represented as proven real-time. Before shipping, run at least 30 minutes of representative zh-TW speech on a physical Flip6 for every variant and record: real-time factor and p50/p95 chunk latency, word/character error rate, peak RSS, dropped audio, battery delta, surface/battery temperature, and thermal-throttling status in the table below.

| Device/build/model | p50/p95 latency | Real-time factor | Peak RSS | Thermal/battery | Result |
|---|---|---|---|---|---|
| Galaxy Z Flip6 / pending | Not measured | Not measured | Not measured | Not measured | Pending physical hardware |

Suggested device command: `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.listener.app.WhisperLatencyBenchmark`. Capture Perfetto and `adb shell dumpsys thermalservice` before and after each sustained run. Larger models must remain unavailable as defaults until latency, memory pressure, and thermal load pass agreed thresholds.

## OpenRouter model selection

“Free” pricing and model identifiers change. The app should refresh the live catalog, present currently zero-priced candidates, and require user confirmation. A production release **must revalidate the OpenRouter free catalog, provider availability, context limits, privacy terms, and structured-output support**; there is intentionally no compiled-in free default.

## Quality checks

* `./gradlew test` — validators, compaction, redaction, checksums, cadence, and recording transitions.
* `./gradlew connectedAndroidTest` — Room persistence/edit/delete and Compose adaptive UI on configured window-size emulators.
* Foldable QA should cover 376×480 cover, narrow split-screen, portrait main display, landscape/two-pane, font scaling, interruption by calls/audio focus, backgrounding, Stop notification, and process recreation.

## Known integration work

This scaffold contains the production boundaries and data/security rules, but the JNI whisper.cpp AAR, navigation/settings wiring, real HTTP summary request body, call/audio-focus callback, scheduled retention worker, and physical-device measurements remain integration tasks. They are intentionally not simulated or claimed complete.
