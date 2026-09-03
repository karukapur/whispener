# AGENTS.md

## Mission

Listener is an Android beta for helping an English-speaking listener understand live Chinese speech. Audio is captured on device, transcribed locally, and never written to disk or uploaded. The English side is a live context summary produced from finalized Chinese transcript text through the selected OpenRouter or Groq provider; it is not a full line-by-line translation transcript.

Treat privacy, traceability, and device-realistic latency as first-class requirements. When the Chinese transcript works but English context is missing, debug the remote summary decision path before touching speech capture.

## Repository Shape

- Android app module: `app/`
- Main ViewModel and summary orchestration: `app/src/main/java/com/listener/app/ListenerViewModel.kt`
- Compose UI: `app/src/main/java/com/listener/app/ui/`
- Local speech services: `app/src/main/java/com/listener/app/audio/`
- Speech engines and overlap merging: `app/src/main/java/com/listener/app/speech/`
- OpenRouter client and context parsing: `app/src/main/java/com/listener/app/context/`
- Preferences: `app/src/main/java/com/listener/app/data/UserPreferences.kt`
- Room session storage: `app/src/main/java/com/listener/app/data/session/`
- Exported user/debug traces: `traces/`

The local model selection and remote summary selection are intentionally separate. Sherpa/Whisper/Android speech engines decide how Chinese text is produced locally. OpenRouter model ids or the fixed Groq GPT-OSS option decide how finalized Chinese text becomes English context.

## Core Runtime Flow

1. `ListenerViewModel.startRecording` records the current preferences and starts the selected local speech service.
2. `ListeningService` or `PlatformSpeechService` updates `ListeningRuntime.state` with stable/provisional Chinese transcript text.
3. The summary scheduler wakes on the configured cadence and calls `sendSummaryIfNeeded`.
4. Summary requests require all of these: remote summaries enabled, the selected provider's key present, a selected remote model, and at least three new finalized Chinese characters.
5. Successful remote output is parsed into `ListeningContext`, committed to `StreamingContextState`, and persisted to the current session.
6. Failed remote output must not stop recording and must not mark Chinese text as successfully summarized.

Pause is session-local: `recording` remains true while `paused` is true, microphone capture stops, audio focus is released, elapsed active-listening time is frozen, and no new Groq/OpenRouter initial or fallback request may begin. Already queued local transcription or an already issued remote request may finish and update UI. Resume continues the same service, engine/recognizer, transcript, English context, session, and adaptive summary phase; any unsummarized finalized Chinese is attempted after Resume.

`lastSentTranscript` should advance only after a valid remote summary response, including a valid unchanged response. Advancing it after a failed remote request causes English context gaps because later ticks think the Chinese text was already processed.

## OpenRouter Policy

- Default remote model: `openrouter/free`.
- Keep `openrouter/free` available even when the fetched catalog is empty or stale.
- In Settings, show up to five free text models in OpenRouter's latency order after filtering for structured-output compatibility; do not hard-code provider model ids.
- Preserve the user's selected compatible model when it falls outside the displayed top five, and use that exact id for English-context requests.
- If a selected remote model returns `ModelUnavailable` or a message containing `No endpoints found`, retry once with `openrouter/free`.
- If the fallback succeeds, commit the context and persist `openrouter/free` as the selected remote model.
- If the fallback fails, keep the previous selected model and surface the error as an English-context status, not as a local recording/start failure.
- `remoteMessage` is about remote English context. Local recording status should be driven by microphone/model/service state.

OpenRouter free-model availability changes over time. Do not hard-code a specific free provider model as the availability guarantee.

## Groq Policy

- Groq is a separate provider option backed by `openai/gpt-oss-20b`, not an OpenRouter model alias.
- Debug builds may read `GROQ_API_KEY` from Gradle, the environment, or `local.properties`; release builds keep the field empty.
- Use strict non-streaming structured output with low reasoning effort because Groq does not combine streaming with structured output.
- Remote summaries currently use the same adaptive cadence trial for every remote model: 5 seconds during the first recording minute, 8 seconds during the second minute, and 10 seconds afterward. This schedule is based on Groq cadence experiments; do not treat it as OpenRouter-specific evidence until OpenRouter has been remeasured.
- Groq and OpenRouter `RateLimited` failures should start a header-driven cooldown before retrying the same remote model. Keep local transcription running and leave `lastSentTranscript` unchanged during cooldown.
- Groq requests should also pass through a local rolling token-budget guard. If the next estimated request would exceed the Groq token-per-minute budget, skip the remote call with `groq_token_budget_cooldown`; keep local transcription running and leave unsent finalized Chinese pending.
- If Groq reports model unavailability and an OpenRouter key exists, the existing one-time `openrouter/free` fallback may run. Other Groq failures keep local transcription active and leave `lastSentTranscript` unchanged.

## Trace Debugging

Use the newest file in `traces/` first:

```sh
ls -lt traces
sed -n '1,140p' traces/<latest-trace>.txt
rg -n "summary_attempt|openrouter|groq|remoteEnabled|selectedRemoteModel|summary_response" traces/<latest-trace>.txt
```

Important trace fields:

- `engine`, `backend`, `activeModelId`: local speech path.
- `paused`: whether the session is open but microphone capture and new summary requests are suspended.
- `remoteEnabled`, `apiKeyPresent`, `selectedRemoteModel`: remote summary setup.
- `stableTranscriptChars`, `provisionalTranscriptChars`: finalized versus provisional Chinese text.
- `summary_attempt_skipped reason=...`: local decision gate prevented a remote call.
- `pause_recording_requested` / `resume_recording_requested`: UI requested session-local pause or resume.
- `openrouter_request_started`: the app sent a remote request.
- `groq_request_started`: the app sent a strict non-streaming GPT-OSS 20B request directly to Groq.
- `openrouter_first_streaming_draft`: first parseable streamed English context.
- `summary_response_committed`: English context was accepted and persisted.
- `summary_response_failed`: remote/API/parsing failure; local transcription may still be healthy.
- `summary_rate_limit_cooldown_started`: a `RateLimited` response paused retries for the same remote model.
- `groq_token_budget_cooldown`: the app skipped a Groq request locally because the estimated request would exceed the rolling token budget.
- `responseChars`, `streamDeltaChars`, `doneSeen`, `parseStage`, `finishReason`, `sseErrorSeen`, `responseHash`, `safeResponseExcerpt`: bounded failure diagnostics only; excerpts are redacted/truncated and full model output is not logged.
- `retryAfterSeconds`, `rateLimitRemainingRequests`, `rateLimitResetRequests`, `rateLimitRemainingTokens`, `rateLimitResetTokens`: safe provider rate-limit headers when returned.

For the common "Chinese works, English missing" report, classify the trace before coding:

- `remote_summaries_disabled`: preference/setup issue.
- `missing_openrouter_key`: credential issue.
- `missing_groq_key`: the Groq option is selected but the debug build has no Groq key.
- `missing_remote_model`: remote model selection issue.
- `stable_transcript_empty`: local transcript has not finalized any text.
- `stable_transcript_unchanged_since_last_sent`: no new finalized Chinese since the last valid summary.
- `stable_transcript_delta_below_minimum`: fewer than three new finalized Chinese characters are pending; they should accumulate and be sent once the guard is met.
- `remote_rate_limit_cooldown`: previous `RateLimited` response is still cooling down; local transcript capture should continue and the unsent finalized Chinese should remain pending.
- `groq_token_budget_cooldown`: a new Groq request would exceed the local token-per-minute budget estimate; finalized Chinese remains pending for a later bounded chunk.
- `listening_paused`: Pause is active; no new microphone audio or Groq/OpenRouter request should begin, and unsent finalized Chinese should remain pending until Resume.
- `ModelUnavailable` / `No endpoints found`: remote model endpoint issue; prefer router fallback.
- `InvalidResponse`: model responded but parser rejected it; use `parseStage`, `[DONE]` state, finish reason, and the safe excerpt/hash to distinguish truncated streams from malformed model content.

## Coding Standards

- Prefer existing architecture and helpers. Keep changes scoped to the failing behavior.
- Use Kotlin coroutines/flows consistently with the current ViewModel and service patterns.
- Keep privacy language exact: audio remains in memory, Chinese finalized text is sent only when remote summaries are enabled and a key exists.
- Keep UI copy clear about "English context" versus "Chinese transcript."
- Do not conflate local model failures with OpenRouter failures.
- Preserve user work in a dirty tree. Never revert unrelated modifications.
- Use `rg` for search and `apply_patch` for manual edits.
- Avoid logging secrets. Route all trace strings through existing redaction helpers when adding diagnostics.

## Validation

Primary gates:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintDebug assembleDebug
```

Device/emulator gate when available:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest
```

For UI verification with ADB, first inspect screen size and UI hierarchy, then capture screenshots into `artifacts/`:

```sh
adb shell wm size
adb shell uiautomator dump /sdcard/view.xml
adb pull /sdcard/view.xml artifacts/view.xml
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png artifacts/screen.png
```

Acceptance for English context reliability:

- Starting recording with Sherpa Paraformer installed shows the local backend as Sherpa, independent of remote model state.
- An unavailable selected OpenRouter model retries once with `openrouter/free`.
- Successful fallback produces visible English context and persisted summary rows.
- Failed fallback leaves local transcription running and leaves `lastSentTranscript` unchanged.
- Rate-limited remote summaries log provider limit headers when available, pause retries for the cooldown window, and leave `lastSentTranscript` unchanged.
- The main recording status does not show OpenRouter endpoint errors.

## Documentation Hygiene

Keep README and this file aligned when behavior changes. Update trace interpretation notes whenever new trace labels or skip reasons are added. If OpenRouter behavior changes, cite current official OpenRouter docs in commit or PR notes rather than relying on old model availability assumptions.
