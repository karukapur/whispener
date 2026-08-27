package com.listener.app

import android.app.Application
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.listener.app.audio.ListeningRuntime
import com.listener.app.audio.ListeningService
import com.listener.app.audio.ListenerRuntimeState
import com.listener.app.audio.PlatformSpeechService
import com.listener.app.context.*
import com.listener.app.data.DEFAULT_OPENROUTER_MODEL_ID
import com.listener.app.data.ListenerPreferences
import com.listener.app.data.OPENROUTER_FREE_ROUTER_MODEL_ID
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.toSummaryIntervalSeconds
import com.listener.app.data.session.ModelMetadataEntity
import com.listener.app.data.session.SessionEntity
import com.listener.app.speech.InferenceBackend
import com.listener.app.models.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ListenerUiState(
    val runtime: ListenerRuntimeState = ListenerRuntimeState(),
    val preferences: ListenerPreferences = ListenerPreferences(),
    val sessions: List<SessionEntity> = emptyList(),
    val installedModels: List<ModelMetadataEntity> = emptyList(),
    val englishContext: ListeningContext? = null,
    val streamingContext: StreamingContextState = StreamingContextState(),
    val summaryDiagnostics: SummaryDiagnostics = SummaryDiagnostics(),
    val remoteStatus: RemoteStatus = RemoteStatus.Ready,
    val remoteMessage: String? = null,
    val catalog: List<OpenRouterModel> = emptyList(),
    val catalogLoading: Boolean = false,
    val download: ModelDownloadState = ModelDownloadState(),
    val apiKeyPresent: Boolean = false,
)

data class ContextHistoryEntry(val context: ListeningContext, val createdAtMillis: Long)

data class StreamingContextState(
    val current: ListeningContext? = null,
    val history: List<ContextHistoryEntry> = emptyList(),
    val draft: ListeningContext? = null,
    val isStreaming: Boolean = false,
    val lastUpdatedAtMillis: Long? = null,
)

data class SummaryDiagnostics(
    val phase: String = "Idle",
    val modelId: String? = null,
    val cadenceMillis: Int? = null,
    val transcriptChars: Int = 0,
    val deltaChars: Int = 0,
    val transcriptReadyAtMillis: Long? = null,
    val requestStartedAtMillis: Long? = null,
    val firstTokenAtMillis: Long? = null,
    val finalAtMillis: Long? = null,
    val error: String? = null,
    val events: List<SummaryTraceEvent> = emptyList(),
)

data class SummaryTraceEvent(val timeMillis: Long, val label: String)

private data class SummaryRemoteAttempt(
    val result: RemoteResult<ListeningContext>,
    val effectiveModel: String,
    val fallbackAttempted: Boolean,
    val fallbackResult: String,
)

private data class LocalState(
    val runtime: ListenerRuntimeState,
    val preferences: ListenerPreferences,
    val sessions: List<SessionEntity>,
    val installed: List<ModelMetadataEntity>,
)

private data class RemoteUiState(
    val context: StreamingContextState,
    val diagnostics: SummaryDiagnostics,
    val status: RemoteStatus,
    val message: String?,
    val catalog: List<OpenRouterModel>,
    val loading: Boolean,
    val download: ModelDownloadState,
)

class ListenerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ListenerApplication
    private val streamingContext = MutableStateFlow(StreamingContextState())
    private val summaryDiagnostics = MutableStateFlow(SummaryDiagnostics())
    private val remoteStatus = MutableStateFlow<RemoteStatus>(RemoteStatus.Ready)
    private val remoteMessage = MutableStateFlow<String?>(null)
    private val catalog = MutableStateFlow<List<OpenRouterModel>>(emptyList())
    private val catalogLoading = MutableStateFlow(false)
    private val download = MutableStateFlow(ModelDownloadState())
    private var summaryJob: Job? = null
    private var lastSentTranscript = ""
    private val summaryGate = SummaryRequestGate()
    private val summaryDebugTrace = SummaryDebugTrace(File(app.filesDir, "summary-debug-traces"))

    private val local = combine(
        ListeningRuntime.state,
        app.preferences.values,
        app.sessions.history(),
        app.models.installedModels(),
        ::LocalState,
    )
    private val remoteProgress = combine(catalogLoading, download, ::Pair)
    private val remoteContext = combine(streamingContext, summaryDiagnostics, ::Pair)
    private val remote = combine(
        remoteContext,
        remoteStatus,
        remoteMessage,
        catalog,
        remoteProgress,
    ) { context, status, message, models, progress -> RemoteUiState(context.first, context.second, status, message, models, progress.first, progress.second) }
    val uiState: StateFlow<ListenerUiState> = combine(local, remote) { localState, remoteState ->
        ListenerUiState(
            runtime = localState.runtime,
            preferences = localState.preferences,
            sessions = localState.sessions,
            installedModels = localState.installed,
            englishContext = remoteState.context.current,
            streamingContext = remoteState.context,
            summaryDiagnostics = remoteState.diagnostics,
            remoteStatus = remoteState.status,
            remoteMessage = remoteState.message,
            catalog = remoteState.catalog,
            catalogLoading = remoteState.loading,
            download = remoteState.download,
            apiKeyPresent = app.keyStore.read() != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenerUiState(apiKeyPresent = app.keyStore.read() != null))

    init {
        DownloadableModels.forEach { model ->
            viewModelScope.launch {
                app.models.observe(model).collect { state ->
                    when {
                        state.running -> download.value = state
                        state.error != null -> download.value = state.copy(modelId = null)
                        download.value.modelId == model.id -> download.value = ModelDownloadState()
                    }
                }
            }
        }
        summaryJob = viewModelScope.launch {
            ListeningRuntime.state.map { it.recording }.distinctUntilChanged()
                .runningFold(false to false) { previous, recording -> previous.second to recording }
                .collectLatest { (wasRecording, recording) ->
                    if (!recording) {
                        if (wasRecording) {
                            delay(500)
                            sendSummaryIfNeeded(app.preferences.values.first())
                        }
                        return@collectLatest
                    }
                    lastSentTranscript = ""
                    streamingContext.value = StreamingContextState()
                    val preferences = app.preferences.values.first()
                    summaryDiagnostics.value = SummaryDiagnostics(phase = "Waiting for finalized transcript", cadenceMillis = preferences.summaryCadenceMillis)
                    summaryDebugTrace.append(
                        sessionId = ListeningRuntime.state.value.sessionId,
                        label = "summary_scheduler_started",
                        fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf(
                            "firstSummaryDelayMs" to FIRST_SUMMARY_DELAY_MS.toString(),
                            "apiKeyPresent" to (app.keyStore.read() != null).yesNo(),
                        ),
                    )
                    delay(FIRST_SUMMARY_DELAY_MS)
                    launch { sendSummaryIfNeeded(app.preferences.values.first()) }
                    while (currentCoroutineContext().isActive) {
                        val preferences = app.preferences.values.first()
                        delay(preferences.summaryCadenceMillis.toLong())
                        launch { sendSummaryIfNeeded(preferences) }
                    }
                }
        }
        viewModelScope.launch {
            combine(app.preferences.values, app.models.installedModels(), ::Pair).collect { (preferences, installed) ->
                if (!preferences.onboardingComplete || preferences.selectedLocalModelId != null) return@collect
                val resolved = resolveLocalModelSelection(null, installed.map { it.modelId }.toSet(), legacyInstall = true)
                if (resolved != null) app.preferences.setSelectedLocalModel(resolved)
            }
        }
    }

    fun completeOnboarding(remoteEnabled: Boolean, retentionDays: Int) {
        viewModelScope.launch {
            app.preferences.completeOnboarding(remoteEnabled, retentionDays)
            if (app.models.installed("base") == null) downloadModel("base")
        }
    }

    fun startRecording(context: Context) {
        val preferences = uiState.value.preferences
        summaryDebugTrace.startNewRecording(
            preferences = preferences,
            apiKeyPresent = app.keyStore.read() != null,
            runtime = ListeningRuntime.state.value,
        )
        summaryDebugTrace.append(
            sessionId = ListeningRuntime.state.value.sessionId,
            label = "start_recording_requested",
            fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf(
                "apiKeyPresent" to (app.keyStore.read() != null).yesNo(),
                "installedLocalModels" to uiState.value.installedModels.joinToString(",") { it.modelId }.ifBlank { "none" },
            ),
        )
        when (preferences.transcriptionEngine) {
            TranscriptionEngine.ANDROID_ON_DEVICE -> {
                ContextCompat.startForegroundService(context, Intent(context, PlatformSpeechService::class.java).apply {
                    action = PlatformSpeechService.ACTION_START
                    putExtra(PlatformSpeechService.EXTRA_CADENCE_SECONDS, preferences.summaryCadenceMillis.toSummaryIntervalSeconds())
                })
                return
            }
            TranscriptionEngine.SHERPA_ONNX -> {
                val sherpaModel = app.models.manager.installedSherpaOnnx()
                if (sherpaModel == null) {
                    summaryDebugTrace.append(
                        sessionId = ListeningRuntime.state.value.sessionId,
                        label = "start_recording_blocked",
                        fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf("reason" to "sherpa_model_missing"),
                    )
                    ListeningRuntime.update {
                        it.copy(
                            backend = InferenceBackend.SHERPA_ONNX,
                            recoverableError = "Install Paraformer at ${app.models.manager.paraformerDir().path}, or SenseVoice at ${app.models.manager.senseVoiceDir().path}, before recording.",
                        )
                    }
                    return
                }
                val (modelId, model) = sherpaModel
                ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).apply {
                    action = ListeningService.ACTION_START
                    putExtra(ListeningService.EXTRA_MODEL_PATH, model.path)
                    putExtra(ListeningService.EXTRA_MODEL_ID, modelId)
                    putExtra(ListeningService.EXTRA_CADENCE_SECONDS, preferences.summaryCadenceMillis.toSummaryIntervalSeconds())
                    putExtra(ListeningService.EXTRA_WORK_PROFILE, preferences.whisperWorkProfile.id)
                    putExtra(ListeningService.EXTRA_BACKEND, InferenceBackend.SHERPA_ONNX.name)
                })
                return
            }
            TranscriptionEngine.WHISPER_CPP -> Unit
        }
        val installedIds = uiState.value.installedModels.map { it.modelId }.toSet()
        val selectedId = resolveLocalModelSelection(preferences.selectedLocalModelId, installedIds)
        val model = selectedId?.let(app.models::installed)
        if (model == null) {
            summaryDebugTrace.append(
                sessionId = ListeningRuntime.state.value.sessionId,
                label = "start_recording_blocked",
                fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf("reason" to "local_model_missing"),
            )
            ListeningRuntime.update { it.copy(recoverableError = "Download and select a local model before recording.") }
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).apply {
            action = ListeningService.ACTION_START
            putExtra(ListeningService.EXTRA_MODEL_PATH, model.path)
            putExtra(ListeningService.EXTRA_MODEL_ID, model.nameWithoutExtension.removePrefix("ggml-"))
            putExtra(ListeningService.EXTRA_CADENCE_SECONDS, uiState.value.preferences.summaryCadenceMillis.toSummaryIntervalSeconds())
            putExtra(ListeningService.EXTRA_WORK_PROFILE, preferences.whisperWorkProfile.id)
            putExtra(ListeningService.EXTRA_BACKEND, InferenceBackend.CPU_FALLBACK.name)
        })
    }

    fun stopRecording(context: Context) {
        val service = if (ListeningRuntime.state.value.backend == InferenceBackend.ANDROID_ON_DEVICE) {
            PlatformSpeechService::class.java
        } else {
            ListeningService::class.java
        }
        val action = if (service == PlatformSpeechService::class.java) PlatformSpeechService.ACTION_STOP else ListeningService.ACTION_STOP
        context.startService(Intent(context, service).setAction(action))
    }

    fun setCadenceMillis(millis: Int) { viewModelScope.launch { app.preferences.setCadenceMillis(millis) } }
    fun setTranscriptionEngine(engine: TranscriptionEngine) {
        if (ListeningRuntime.state.value.recording) return
        viewModelScope.launch { app.preferences.setTranscriptionEngine(engine) }
    }
    fun setWhisperWorkProfile(profile: WhisperWorkProfile) {
        if (ListeningRuntime.state.value.recording) return
        viewModelScope.launch { app.preferences.setWhisperWorkProfile(profile) }
    }
    fun setRemoteEnabled(enabled: Boolean) { viewModelScope.launch { app.preferences.setRemoteEnabled(enabled) } }
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            app.preferences.setRetentionDays(days)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<RetentionWorker>().build())
        }
    }

    fun saveApiKey(key: String) {
        app.keyStore.save(key)
        remoteStatus.value = RemoteStatus.Ready
        remoteMessage.value = null
        viewModelScope.launch { app.preferences.setRemoteEnabled(true) }
    }

    fun clearApiKey() {
        app.keyStore.clear()
        catalog.value = listOf(openRouterFreeRouterModel())
        remoteMessage.value = null
        viewModelScope.launch { app.preferences.setRemoteEnabled(false) }
    }

    fun refreshCatalog() {
        val key = app.keyStore.read() ?: run { remoteMessage.value = "Enter an OpenRouter API key first."; return }
        viewModelScope.launch {
            catalogLoading.value = true
            when (val result = app.openRouter.fetchFreeModels(key)) {
                is RemoteResult.Success -> {
                    val models = result.value.withOpenRouterFreeRouter()
                    catalog.value = models
                    val selected = app.preferences.values.first().selectedModel
                    if (selected != null && selected != DEFAULT_OPENROUTER_MODEL_ID && models.none { it.id == selected }) app.preferences.clearSelectedModel()
                    remoteStatus.value = RemoteStatus.Ready
                    remoteMessage.value = if (result.value.isEmpty()) "Using OpenRouter free router for English context." else null
                }
                is RemoteResult.Failure -> { remoteStatus.value = result.status; remoteMessage.value = result.message }
            }
            catalogLoading.value = false
        }
    }

    fun selectRemoteModel(id: String) { viewModelScope.launch { app.preferences.setSelectedModel(id) } }

    fun selectLocalModel(id: String) {
        if (ListeningRuntime.state.value.recording || app.models.installed(id) == null) return
        viewModelScope.launch { app.preferences.setSelectedLocalModel(id) }
    }

    fun downloadModel(id: String) {
        val model = DownloadableModels.firstOrNull { it.id == id } ?: return
        val previous = download.value
        if (previous.running && previous.modelId != id) previous.workId?.let(app.models::cancel)
        val workId = app.models.download(model)
        download.value = ModelDownloadState(workId = workId, modelId = id, running = true)
    }

    fun cancelDownload() {
        download.value.workId?.let(app.models::cancel)
        download.value = ModelDownloadState(error = "Download cancelled")
    }

    fun deleteModel(id: String) {
        val model = DownloadableModels.firstOrNull { it.id == id } ?: return
        if (ListeningRuntime.state.value.activeModelId == id) return
        viewModelScope.launch {
            runCatching { app.models.delete(model) }
                .onSuccess {
                    val preferences = app.preferences.values.first()
                    if (preferences.selectedLocalModelId == id) {
                        val remaining = app.models.installedModels().first().map { it.modelId }.toSet()
                        resolveLocalModelSelection(null, remaining)?.let { app.preferences.setSelectedLocalModel(it) }
                            ?: app.preferences.clearSelectedLocalModel()
                    }
                }
                .onFailure { download.value = ModelDownloadState(error = it.message ?: "Unable to delete model") }
        }
    }

    fun deleteSession(id: Long) { viewModelScope.launch { app.sessions.deleteConfirmed(id, true) } }
    fun editSession(id: Long, title: String, displayText: String?) { viewModelScope.launch { app.sessions.edit(id, title.trim().ifBlank { "Untitled session" }, displayText) } }

    suspend fun exportSession(id: Long): String = app.sessions.export(id)

    suspend fun saveSessionSummaryTraceToDownloads(id: Long): String = withContext(Dispatchers.IO) {
        val fileName = "listener-summary-trace-$id-${System.currentTimeMillis()}.txt"
        val text = detailedSummaryTraceText(id)
        saveTextToDownloads(app, fileName, text)
    }

    suspend fun createSessionSummaryTraceShareIntent(id: Long): Intent = withContext(Dispatchers.IO) {
        val fileName = "listener-summary-trace-$id-${System.currentTimeMillis()}.txt"
        val file = writeTextToTraceShareCache(app, fileName, detailedSummaryTraceText(id))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        buildTraceShareIntent(uri, fileName)
    }

    private suspend fun detailedSummaryTraceText(id: Long): String =
        buildDetailedSummaryTrace(
            persistedSummaryTrace = app.sessions.summaryTrace(id),
            runtimeTrace = summaryDebugTrace.readForSession(id),
            diagnostics = summaryDiagnostics.value,
            sessionId = id,
        )

    private suspend fun sendSummaryIfNeeded(preferences: ListenerPreferences) {
        val runtime = ListeningRuntime.state.value
        if (!summaryGate.tryStart()) {
            summaryDiagnostics.updateTrace("Summary in flight", preferences.summaryCadenceMillis)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_tick_coalesced_in_flight",
                fields = summaryTraceFields(preferences, runtime),
            )
            return
        }
        try {
            sendSummaryIfNeededLocked(preferences)
        } finally {
            summaryGate.finish()
        }
    }

    private suspend fun sendSummaryIfNeededLocked(preferences: ListenerPreferences) {
        val runtime = ListeningRuntime.state.value
        val apiKeyPresent = app.keyStore.read() != null
        summaryDebugTrace.append(
            sessionId = runtime.sessionId,
            label = "summary_attempt_started",
            fields = summaryTraceFields(preferences, runtime) + mapOf(
                "apiKeyPresent" to apiKeyPresent.yesNo(),
                "selectedModel" to (preferences.selectedModel ?: "none"),
                "lastSentTranscriptChars" to lastSentTranscript.length.toString(),
                "currentCommittedContextPresent" to (streamingContext.value.current != null).yesNo(),
                "currentHistoryCount" to streamingContext.value.history.size.toString(),
            ),
        )
        if (!preferences.remoteEnabled) {
            summaryDiagnostics.updateTrace("Remote summaries disabled", preferences.summaryCadenceMillis)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_attempt_skipped",
                fields = summaryTraceFields(preferences, runtime) + mapOf("reason" to "remote_summaries_disabled"),
            )
            return
        }
        val key = app.keyStore.read() ?: run {
            summaryDiagnostics.updateTrace("Waiting for OpenRouter key", preferences.summaryCadenceMillis)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_attempt_skipped",
                fields = summaryTraceFields(preferences, runtime) + mapOf("reason" to "missing_openrouter_key"),
            )
            return
        }
        val requestedModel = preferences.selectedModel ?: run {
            summaryDiagnostics.updateTrace("Waiting for selected OpenRouter model", preferences.summaryCadenceMillis)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_attempt_skipped",
                fields = summaryTraceFields(preferences, runtime) + mapOf("reason" to "missing_openrouter_model"),
            )
            return
        }
        val transcript = runtime.finalizedTranscriptForSummary()
        if (transcript.isBlank()) {
            summaryDiagnostics.updateTrace("Waiting for finalized transcript", preferences.summaryCadenceMillis, requestedModel, transcriptChars = 0, deltaChars = 0)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_attempt_skipped",
                fields = summaryTraceFields(preferences, runtime) + mapOf("reason" to "stable_transcript_empty"),
            )
            return
        }
        if (transcript == lastSentTranscript) {
            summaryDiagnostics.updateTrace("No new finalized transcript", preferences.summaryCadenceMillis, requestedModel, transcriptChars = transcript.length, deltaChars = 0)
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "summary_attempt_skipped",
                fields = summaryTraceFields(preferences, runtime) + mapOf(
                    "reason" to "stable_transcript_unchanged_since_last_sent",
                    "lastSentTranscriptChars" to lastSentTranscript.length.toString(),
                ),
            )
            return
        }
        val promptInputs = summaryPromptInputs(transcript, lastSentTranscript, streamingContext.value.current)
        val newText = promptInputs.fullDelta
        summaryDebugTrace.append(
            sessionId = runtime.sessionId,
            label = "summary_prompt_prepared",
            fields = summaryTraceFields(preferences, runtime) + mapOf(
                "requestedModel" to requestedModel,
                "fullDeltaChars" to newText.length.toString(),
                "sentNewChineseDeltaChars" to promptInputs.newChineseDelta.length.toString(),
                "sentChineseContinuityTailChars" to promptInputs.chineseContinuityTail.length.toString(),
                "sentPreviousEnglishSummaryChars" to promptInputs.previousEnglishSummary.length.toString(),
                "deltaWasTrimmedToCap" to (promptInputs.newChineseDelta.length < newText.length).yesNo(),
                "lastSentTranscriptChars" to lastSentTranscript.length.toString(),
            ),
        )
        val transcriptReadyAt = System.currentTimeMillis()
        summaryDiagnostics.value = SummaryDiagnostics(
            phase = "Transcript ready",
            modelId = requestedModel,
            cadenceMillis = preferences.summaryCadenceMillis,
            transcriptChars = transcript.length,
            deltaChars = newText.length,
            transcriptReadyAtMillis = transcriptReadyAt,
            events = summaryDiagnostics.value.events.plus(SummaryTraceEvent(transcriptReadyAt, "Transcript ready: +${newText.length} chars")).takeLast(MAX_SUMMARY_EVENTS),
        )
        Log.d(SUMMARY_LOG_TAG, "transcript_ready=$transcriptReadyAt chars=${transcript.length} delta=${newText.length}")
        var firstTokenLogged = false
        streamingContext.update { it.copy(draft = null, isStreaming = true) }
        val requestStartAt = System.currentTimeMillis()
        summaryDiagnostics.update { current ->
            current.copy(
                phase = "OpenRouter request started",
                requestStartedAtMillis = requestStartAt,
                error = null,
                events = current.events.plus(SummaryTraceEvent(requestStartAt, "Request started: ${requestStartAt - transcriptReadyAt}ms after trigger")).takeLast(MAX_SUMMARY_EVENTS),
            )
        }
        Log.d(SUMMARY_LOG_TAG, "request_start=$requestStartAt after_transcript_ready_ms=${requestStartAt - transcriptReadyAt}")
        summaryDebugTrace.append(
            sessionId = runtime.sessionId,
            label = "openrouter_request_started",
            fields = summaryTraceFields(preferences, runtime) + mapOf(
                "requestedModel" to requestedModel,
                "effectiveModel" to requestedModel,
                "fallbackAttempted" to false.yesNo(),
                "requestDelayAfterTranscriptReadyMs" to (requestStartAt - transcriptReadyAt).toString(),
            ),
        )
        var activeRemoteModel = requestedModel
        suspend fun requestSummary(model: String): RemoteResult<ListeningContext> =
            app.openRouter.summarize(key, model, promptInputs.previousEnglishSummary, promptInputs.chineseContinuityTail, promptInputs.newChineseDelta) { draft ->
                val now = System.currentTimeMillis()
                if (!firstTokenLogged) {
                    firstTokenLogged = true
                    summaryDebugTrace.append(
                        sessionId = runtime.sessionId,
                        label = "openrouter_first_streaming_draft",
                        fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf(
                            "requestedModel" to requestedModel,
                            "effectiveModel" to activeRemoteModel,
                            "msAfterRequestStart" to (now - requestStartAt).toString(),
                            "draftGlobalContextChars" to draft.globalContext.length.toString(),
                            "draftDetailsCount" to draft.details.size.toString(),
                        ),
                    )
                    summaryDiagnostics.update { current ->
                        current.copy(
                            phase = "Streaming first draft",
                            firstTokenAtMillis = now,
                            events = current.events.plus(SummaryTraceEvent(now, "First token: ${now - requestStartAt}ms after request")).takeLast(MAX_SUMMARY_EVENTS),
                        )
                    }
                    Log.d(SUMMARY_LOG_TAG, "first_stream_token=$now after_request_start_ms=${now - requestStartAt}")
                }
                streamingContext.update { it.copy(draft = draft, isStreaming = true, lastUpdatedAtMillis = now) }
            }
        val firstResult = requestSummary(requestedModel)
        val remoteAttempt = if (firstResult is RemoteResult.Failure && shouldRetrySummaryWithFreeRouter(requestedModel, firstResult)) {
            val fallbackStartAt = System.currentTimeMillis()
            summaryDiagnostics.update { current ->
                current.copy(
                    phase = "English summary model unavailable; retrying free router",
                    modelId = OPENROUTER_FREE_ROUTER_MODEL_ID,
                    error = null,
                    events = current.events.plus(SummaryTraceEvent(fallbackStartAt, "Model unavailable; retrying free router")).takeLast(MAX_SUMMARY_EVENTS),
                )
            }
            summaryDebugTrace.append(
                sessionId = runtime.sessionId,
                label = "openrouter_fallback_retry_started",
                fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf(
                    "requestedModel" to requestedModel,
                    "effectiveModel" to OPENROUTER_FREE_ROUTER_MODEL_ID,
                    "fallbackAttempted" to true.yesNo(),
                    "firstFailureStatus" to firstResult.status.toString(),
                    "firstFailureMessage" to firstResult.message.safeTraceValue(),
                    "msAfterRequestStart" to (fallbackStartAt - requestStartAt).toString(),
                ),
            )
            activeRemoteModel = OPENROUTER_FREE_ROUTER_MODEL_ID
            val fallbackResult = requestSummary(OPENROUTER_FREE_ROUTER_MODEL_ID)
            SummaryRemoteAttempt(
                result = fallbackResult,
                effectiveModel = OPENROUTER_FREE_ROUTER_MODEL_ID,
                fallbackAttempted = true,
                fallbackResult = fallbackResult.summaryTraceResult(),
            )
        } else {
            SummaryRemoteAttempt(
                result = firstResult,
                effectiveModel = requestedModel,
                fallbackAttempted = false,
                fallbackResult = "not_attempted",
            )
        }
        when (val result = remoteAttempt.result) {
            is RemoteResult.Success -> {
                val finalAt = System.currentTimeMillis()
                val commit = commitSummaryResult(streamingContext.value, result.value, finalAt)
                summaryDiagnostics.update { current ->
                    current.copy(
                        phase = if (commit.changed) "Summary committed" else "Summary unchanged",
                        modelId = remoteAttempt.effectiveModel,
                        finalAtMillis = finalAt,
                        error = null,
                        events = current.events.plus(
                            SummaryTraceEvent(
                                finalAt,
                                if (commit.changed) {
                                    "Final JSON: ${finalAt - requestStartAt}ms after request"
                                } else {
                                    "Summary unchanged: ${finalAt - requestStartAt}ms after request"
                                },
                            )
                        ).takeLast(MAX_SUMMARY_EVENTS),
                    )
                }
                Log.d(SUMMARY_LOG_TAG, "final_summary=$finalAt after_request_start_ms=${finalAt - requestStartAt}")
                streamingContext.value = commit.state
                remoteStatus.value = RemoteStatus.Ready
                remoteMessage.value = null
                lastSentTranscript = transcript
                if (remoteAttempt.fallbackAttempted) app.preferences.setSelectedModel(OPENROUTER_FREE_ROUTER_MODEL_ID)
                if (commit.changed) runtime.sessionId?.let { app.sessions.appendSummary(it, result.value) }
                summaryDebugTrace.append(
                    sessionId = runtime.sessionId,
                    label = if (commit.changed) "summary_response_committed" else "summary_response_valid_unchanged",
                    fields = summaryTraceFields(preferences, ListeningRuntime.state.value) + mapOf(
                        "requestedModel" to requestedModel,
                        "effectiveModel" to remoteAttempt.effectiveModel,
                        "fallbackAttempted" to remoteAttempt.fallbackAttempted.yesNo(),
                        "fallbackResult" to remoteAttempt.fallbackResult,
                        "msAfterRequestStart" to (finalAt - requestStartAt).toString(),
                        "firstTokenSeen" to firstTokenLogged.yesNo(),
                        "responseGlobalContext" to result.value.globalContext.safeTraceValue(),
                        "responseDetailsCount" to result.value.details.size.toString(),
                        "historyCountAfterCommit" to commit.state.history.size.toString(),
                        "sessionSummaryPersisted" to commit.changed.yesNo(),
                        "lastSentTranscriptAdvanced" to true.yesNo(),
                        "lastSentTranscriptCharsAfterCommit" to lastSentTranscript.length.toString(),
                    ),
                )
            }
            is RemoteResult.Failure -> {
                val failedAt = System.currentTimeMillis()
                summaryDiagnostics.update { current ->
                    current.copy(
                        phase = "Summary failed",
                        modelId = remoteAttempt.effectiveModel,
                        finalAtMillis = failedAt,
                        error = result.message,
                        events = current.events.plus(SummaryTraceEvent(failedAt, "Failed: ${result.message}")).takeLast(MAX_SUMMARY_EVENTS),
                    )
                }
                streamingContext.update { it.copy(draft = null, isStreaming = false) }
                summaryDebugTrace.append(
                    sessionId = runtime.sessionId,
                    label = "summary_response_failed",
                    fields = summaryTraceFields(preferences, ListeningRuntime.state.value) +
                        mapOf(
                            "requestedModel" to requestedModel,
                            "effectiveModel" to remoteAttempt.effectiveModel,
                            "fallbackAttempted" to remoteAttempt.fallbackAttempted.yesNo(),
                            "fallbackResult" to remoteAttempt.fallbackResult,
                            "remoteStatus" to result.status.toString(),
                            "message" to result.message.safeTraceValue(),
                            "msAfterRequestStart" to (failedAt - requestStartAt).toString(),
                            "firstTokenSeen" to firstTokenLogged.yesNo(),
                            "invalidResponseKeepsLastSentTranscript" to (result.status == RemoteStatus.InvalidResponse).yesNo(),
                            "lastSentTranscriptAdvanced" to false.yesNo(),
                        ) +
                        result.diagnostics.toSummaryTraceFields(),
                )
                if (result.status == RemoteStatus.InvalidResponse) {
                    remoteStatus.value = RemoteStatus.Ready
                    remoteMessage.value = null
                } else {
                    remoteStatus.value = result.status
                    remoteMessage.value = result.message
                }
            }
        }
    }
}

internal fun ListeningContext?.toPromptContext(): String {
    val context = this ?: return ""
    return buildString {
        appendLine(context.globalContext)
        context.details.forEach { detail -> appendLine("- $detail") }
    }.trim()
}

internal fun ListenerRuntimeState.finalizedTranscriptForSummary(): String = stableTranscript

internal fun ListeningContext?.toPromptJson(): String {
    val context = this ?: return ""
    return JsonObject(
        mapOf(
            "globalContext" to JsonPrimitive(context.globalContext),
            "details" to JsonArray(context.details.map(::JsonPrimitive)),
        )
    ).toString()
}

internal data class SummaryCommitResult(val state: StreamingContextState, val changed: Boolean)

internal fun commitSummaryResult(
    currentState: StreamingContextState,
    response: ListeningContext,
    finalAtMillis: Long,
): SummaryCommitResult {
    if (currentState.current == response) {
        return SummaryCommitResult(
            currentState.copy(draft = null, isStreaming = false, lastUpdatedAtMillis = finalAtMillis),
            changed = false,
        )
    }
    return SummaryCommitResult(
        currentState.copy(
            current = response,
            history = (currentState.history + ContextHistoryEntry(response, finalAtMillis)).takeLast(MAX_CONTEXT_HISTORY),
            draft = null,
            isStreaming = false,
            lastUpdatedAtMillis = finalAtMillis,
        ),
        changed = true,
    )
}

internal fun String.deltaSince(previous: String): String =
    if (startsWith(previous)) removePrefix(previous).trim() else this

internal data class SummaryPromptInputs(
    val previousEnglishSummary: String,
    val chineseContinuityTail: String,
    val newChineseDelta: String,
    val fullDelta: String,
)

internal fun summaryPromptInputs(
    transcript: String,
    lastSentTranscript: String,
    currentContext: ListeningContext?,
): SummaryPromptInputs {
    val fullDelta = transcript.deltaSince(lastSentTranscript)
    return SummaryPromptInputs(
        previousEnglishSummary = currentContext.toPromptJson().takeLast(PREVIOUS_SUMMARY_PROMPT_CHARS),
        chineseContinuityTail = if (transcript.startsWith(lastSentTranscript)) {
            lastSentTranscript.takeLast(CHINESE_CONTINUITY_TAIL_CHARS)
        } else {
            ""
        },
        newChineseDelta = fullDelta.takeLast(CHINESE_DELTA_PROMPT_CHARS),
        fullDelta = fullDelta,
    )
}

internal fun shouldRetrySummaryWithFreeRouter(model: String, failure: RemoteResult.Failure): Boolean =
    model != OPENROUTER_FREE_ROUTER_MODEL_ID &&
        (failure.status == RemoteStatus.ModelUnavailable || failure.message.contains("No endpoints found", ignoreCase = true))

internal fun List<OpenRouterModel>.withOpenRouterFreeRouter(): List<OpenRouterModel> =
    if (any { it.id == OPENROUTER_FREE_ROUTER_MODEL_ID }) this else listOf(openRouterFreeRouterModel()) + this

internal fun openRouterFreeRouterModel(): OpenRouterModel =
    OpenRouterModel(OPENROUTER_FREE_ROUTER_MODEL_ID, "OpenRouter free router")

private fun RemoteResult<ListeningContext>.summaryTraceResult(): String = when (this) {
    is RemoteResult.Success -> "success"
    is RemoteResult.Failure -> "failure_${status}"
}

internal fun RemoteFailureDiagnostics?.toSummaryTraceFields(): Map<String, String> {
    val diagnostics = this
    return mapOf(
        "responseChars" to (diagnostics?.responseChars?.toString() ?: "none"),
        "streamDeltaChars" to (diagnostics?.streamDeltaChars?.toString() ?: "none"),
        "doneSeen" to diagnostics?.doneSeen?.yesNo().orNone(),
        "parseStage" to (diagnostics?.parseStage ?: "none"),
        "finishReason" to (diagnostics?.finishReason ?: "none"),
        "sseErrorSeen" to ((diagnostics?.sseErrorType != null || diagnostics?.sseErrorMessage != null).yesNo()),
        "sseErrorType" to (diagnostics?.sseErrorType ?: "none"),
        "sseErrorMessage" to (diagnostics?.sseErrorMessage ?: "none"),
        "responseHash" to (diagnostics?.responseHash ?: "none"),
        "safeResponseExcerpt" to (diagnostics?.safeResponseExcerpt ?: "none"),
    )
}

internal class SummaryRequestGate {
    private val inFlight = AtomicBoolean(false)

    fun tryStart(): Boolean = inFlight.compareAndSet(false, true)

    fun finish() {
        inFlight.set(false)
    }
}

internal class SummaryDebugTrace(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableListOf<String>()
    private var activeSessionId: Long? = null

    @Synchronized
    fun startNewRecording(
        preferences: ListenerPreferences,
        apiKeyPresent: Boolean,
        runtime: ListenerRuntimeState,
    ) {
        activeSessionId = runtime.sessionId
        pending.clear()
        appendLocked(
            sessionId = runtime.sessionId,
            label = "trace_started_for_recording",
            fields = summaryTraceFields(preferences, runtime) + mapOf(
                "apiKeyPresent" to apiKeyPresent.yesNo(),
                "note" to "This file records summary/debug decisions only. It does not include API keys.",
            ),
        )
    }

    @Synchronized
    fun append(sessionId: Long?, label: String, fields: Map<String, String>) {
        appendLocked(sessionId, label, fields)
    }

    @Synchronized
    fun readForSession(sessionId: Long): String {
        val persisted = traceFile(sessionId).takeIf(File::exists)?.readText().orEmpty()
        val pendingForActive = if (activeSessionId == sessionId && pending.isNotEmpty()) {
            pending.joinToString(separator = "\n")
        } else {
            ""
        }
        return listOf(persisted, pendingForActive)
            .filter(String::isNotBlank)
            .joinToString(separator = "\n")
            .ifBlank { "No runtime summary diagnostics were recorded for this session." }
    }

    private fun appendLocked(sessionId: Long?, label: String, fields: Map<String, String>) {
        val line = buildTraceLine(clock(), label, fields)
        if (sessionId == null) {
            pending += line
            return
        }
        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
        }
        directory.mkdirs()
        val file = traceFile(sessionId)
        if (!file.exists()) {
            file.appendText("Runtime summary diagnostics for session $sessionId\n")
            if (pending.isNotEmpty()) {
                pending.forEach { file.appendText("$it\n") }
                pending.clear()
            }
        }
        file.appendText("$line\n")
    }

    private fun traceFile(sessionId: Long): File = File(directory, "session-$sessionId.txt")
}

internal fun buildDetailedSummaryTrace(
    persistedSummaryTrace: String,
    runtimeTrace: String,
    diagnostics: SummaryDiagnostics,
    sessionId: Long,
): String = buildString {
    appendLine("Listener detailed summary trace")
    appendLine("Session ID: $sessionId")
    appendLine("Exported: ${formatTraceTimestamp(System.currentTimeMillis())}")
    appendLine()
    appendLine("How to read this")
    appendLine("- If the Chinese transcript is good but English is missing, look for summary_attempt_skipped or summary_response_failed lines.")
    appendLine("- Common skip reasons: remote_summaries_disabled, missing_openrouter_key, missing_openrouter_model, stable_transcript_empty, stable_transcript_unchanged_since_last_sent.")
    appendLine("- InvalidResponse means OpenRouter replied, but the app could not parse valid JSON, so the previous context was kept and lastSentTranscript was not advanced.")
    appendLine("- RateLimited, Offline, TimedOut, InvalidKey, and ModelUnavailable are remote/API states; transcript capture can still be perfect while summaries fail.")
    appendLine()
    appendLine("Current diagnostics snapshot")
    appendLine(diagnostics.toDetailedTraceSnapshot())
    appendLine()
    appendLine("Persisted English summaries")
    appendLine(persistedSummaryTrace)
    appendLine()
    appendLine("Runtime summary decision log")
    appendLine(runtimeTrace)
}.trimEnd()

internal fun buildTraceLine(timeMillis: Long, label: String, fields: Map<String, String>): String {
    val details = fields.entries.joinToString(separator = " ") { (key, value) -> "$key=${value.safeTraceValue()}" }
    return "${formatTraceTimestamp(timeMillis)} $label $details".trimEnd()
}

internal fun summaryTraceFields(preferences: ListenerPreferences, runtime: ListenerRuntimeState): Map<String, String> =
    mapOf(
        "sessionId" to (runtime.sessionId?.toString() ?: "none"),
        "recording" to runtime.recording.yesNo(),
        "stopping" to runtime.stopping.yesNo(),
        "engine" to preferences.transcriptionEngine.id,
        "backend" to (runtime.backend?.name ?: "none"),
        "activeModelId" to (runtime.activeModelId ?: "none"),
        "remoteEnabled" to preferences.remoteEnabled.yesNo(),
        "selectedRemoteModel" to (preferences.selectedModel ?: "none"),
        "cadenceMillis" to preferences.summaryCadenceMillis.toString(),
        "cadenceSessionSeconds" to preferences.summaryCadenceMillis.toSummaryIntervalSeconds().toString(),
        "stableTranscriptChars" to runtime.stableTranscript.length.toString(),
        "provisionalTranscriptChars" to runtime.provisionalTranscript.length.toString(),
        "elapsedSeconds" to runtime.elapsedSeconds.toString(),
        "processingLagMs" to runtime.processingLagMs.toString(),
        "recoverableError" to (runtime.recoverableError ?: "none"),
        "transcriptionStatus" to (runtime.transcriptionStatus ?: "none"),
    )

internal fun SummaryDiagnostics.toDetailedTraceSnapshot(): String =
    buildString {
        appendLine("phase=${phase.safeTraceValue()}")
        appendLine("modelId=${(modelId ?: "none").safeTraceValue()}")
        appendLine("cadenceMillis=${cadenceMillis ?: "none"}")
        appendLine("transcriptChars=$transcriptChars")
        appendLine("deltaChars=$deltaChars")
        appendLine("transcriptReadyAt=${transcriptReadyAtMillis?.let(::formatTraceTimestamp) ?: "none"}")
        appendLine("requestStartedAt=${requestStartedAtMillis?.let(::formatTraceTimestamp) ?: "none"}")
        appendLine("firstTokenAt=${firstTokenAtMillis?.let(::formatTraceTimestamp) ?: "none"}")
        appendLine("finalAt=${finalAtMillis?.let(::formatTraceTimestamp) ?: "none"}")
        appendLine("error=${(error ?: "none").safeTraceValue()}")
        appendLine("recentEvents=${events.joinToString(separator = " | ") { "${formatTraceTimestamp(it.timeMillis)} ${it.label}" }.ifBlank { "none" }.safeTraceValue()}")
    }.trimEnd()

private fun MutableStateFlow<SummaryDiagnostics>.updateTrace(
    phase: String,
    cadenceMillis: Int,
    modelId: String? = value.modelId,
    transcriptChars: Int = value.transcriptChars,
    deltaChars: Int = value.deltaChars,
) {
    val now = System.currentTimeMillis()
    update { current ->
        current.copy(
            phase = phase,
            modelId = modelId,
            cadenceMillis = cadenceMillis,
            transcriptChars = transcriptChars,
            deltaChars = deltaChars,
            error = null,
            events = current.events.plus(SummaryTraceEvent(now, phase)).takeLast(MAX_SUMMARY_EVENTS),
        )
    }
}

private const val FIRST_SUMMARY_DELAY_MS = 2_000L
private const val MAX_CONTEXT_HISTORY = 30
private const val MAX_SUMMARY_EVENTS = 6
private const val PREVIOUS_SUMMARY_PROMPT_CHARS = 2_000
private const val CHINESE_DELTA_PROMPT_CHARS = 2_000
private const val CHINESE_CONTINUITY_TAIL_CHARS = 800
private const val SUMMARY_LOG_TAG = "ListenerSummary"

private fun Boolean.yesNo(): String = if (this) "yes" else "no"

private fun String?.orNone(): String = this ?: "none"

private fun String.safeTraceValue(): String =
    SecretRedactor.redact(replace("\n", "\\n")).ifBlank { "blank" }

private fun formatTraceTimestamp(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timeMillis))

private fun saveTextToDownloads(context: Context, fileName: String, text: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads file.")
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            ?: error("Unable to write Downloads file.")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return fileName
    }
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!directory.exists() && !directory.mkdirs()) error("Unable to create Downloads directory.")
    val file = File(directory, fileName)
    file.writeText(text)
    return file.absolutePath
}

private fun writeTextToTraceShareCache(context: Context, fileName: String, text: String): File {
    val directory = File(context.cacheDir, "summary-trace-shares")
    if (!directory.exists() && !directory.mkdirs()) error("Unable to prepare summary trace for sharing.")
    val file = File(directory, fileName)
    file.writeText(text)
    return file
}

internal fun buildTraceShareIntent(uri: Uri, fileName: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        putExtra(Intent.EXTRA_TITLE, fileName)
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
