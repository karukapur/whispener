package com.listener.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.session.ModelMetadataEntity
import com.listener.app.data.session.SessionEntity
import com.listener.app.speech.InferenceBackend
import com.listener.app.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    val cadenceSeconds: Int? = null,
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
                    summaryDiagnostics.value = SummaryDiagnostics(phase = "Waiting for finalized transcript", cadenceSeconds = app.preferences.values.first().summaryCadenceSeconds)
                    delay(FIRST_SUMMARY_DELAY_MS)
                    sendSummaryIfNeeded(app.preferences.values.first())
                    while (currentCoroutineContext().isActive) {
                        val preferences = app.preferences.values.first()
                        delay(preferences.summaryCadenceSeconds * 1_000L)
                        sendSummaryIfNeeded(preferences)
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
        when (preferences.transcriptionEngine) {
            TranscriptionEngine.ANDROID_ON_DEVICE -> {
                ContextCompat.startForegroundService(context, Intent(context, PlatformSpeechService::class.java).apply {
                    action = PlatformSpeechService.ACTION_START
                    putExtra(PlatformSpeechService.EXTRA_CADENCE_SECONDS, preferences.summaryCadenceSeconds)
                })
                return
            }
            TranscriptionEngine.SHERPA_ONNX -> {
                val sherpaModel = app.models.manager.installedSherpaOnnx()
                if (sherpaModel == null) {
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
                    putExtra(ListeningService.EXTRA_CADENCE_SECONDS, preferences.summaryCadenceSeconds)
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
            ListeningRuntime.update { it.copy(recoverableError = "Download and select a local model before recording.") }
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).apply {
            action = ListeningService.ACTION_START
            putExtra(ListeningService.EXTRA_MODEL_PATH, model.path)
            putExtra(ListeningService.EXTRA_MODEL_ID, model.nameWithoutExtension.removePrefix("ggml-"))
            putExtra(ListeningService.EXTRA_CADENCE_SECONDS, uiState.value.preferences.summaryCadenceSeconds)
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

    fun setCadence(seconds: Int) { viewModelScope.launch { app.preferences.setCadence(seconds) } }
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
    }

    fun clearApiKey() {
        app.keyStore.clear(); catalog.value = emptyList(); remoteMessage.value = null
    }

    fun refreshCatalog() {
        val key = app.keyStore.read() ?: run { remoteMessage.value = "Enter an OpenRouter API key first."; return }
        viewModelScope.launch {
            catalogLoading.value = true
            when (val result = app.openRouter.fetchFreeModels(key)) {
                is RemoteResult.Success -> {
                    catalog.value = result.value
                    val selected = app.preferences.values.first().selectedModel
                    if (selected != null && selected != DEFAULT_OPENROUTER_MODEL_ID && result.value.none { it.id == selected }) app.preferences.clearSelectedModel()
                    remoteStatus.value = RemoteStatus.Ready
                    remoteMessage.value = if (result.value.isEmpty()) "No free structured-output models are currently available." else null
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

    private suspend fun sendSummaryIfNeeded(preferences: ListenerPreferences) {
        if (!preferences.remoteEnabled) {
            summaryDiagnostics.updateTrace("Remote summaries disabled", preferences.summaryCadenceSeconds)
            return
        }
        val key = app.keyStore.read() ?: run {
            summaryDiagnostics.updateTrace("Waiting for OpenRouter key", preferences.summaryCadenceSeconds)
            return
        }
        val model = preferences.selectedModel ?: run {
            summaryDiagnostics.updateTrace("Waiting for selected OpenRouter model", preferences.summaryCadenceSeconds)
            return
        }
        val runtime = ListeningRuntime.state.value
        val transcript = runtime.finalizedTranscriptForSummary()
        if (transcript.isBlank()) {
            summaryDiagnostics.updateTrace("Waiting for finalized transcript", preferences.summaryCadenceSeconds, model, transcriptChars = 0, deltaChars = 0)
            return
        }
        if (transcript == lastSentTranscript) {
            summaryDiagnostics.updateTrace("No new finalized transcript", preferences.summaryCadenceSeconds, model, transcriptChars = transcript.length, deltaChars = 0)
            return
        }
        val newText = if (transcript.startsWith(lastSentTranscript)) transcript.removePrefix(lastSentTranscript).trim() else transcript
        val priorContext = streamingContext.value.current.toPromptContext().takeLast(2_000)
        val recentTranscript = transcript.takeLast(6_000)
        val finalizedText = newText.takeLast(2_000)
        val transcriptReadyAt = System.currentTimeMillis()
        summaryDiagnostics.value = SummaryDiagnostics(
            phase = "Transcript ready",
            modelId = model,
            cadenceSeconds = preferences.summaryCadenceSeconds,
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
        val result = app.openRouter.summarize(key, model, priorContext, recentTranscript, finalizedText) { draft ->
            val now = System.currentTimeMillis()
            if (!firstTokenLogged) {
                firstTokenLogged = true
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
        when (result) {
            is RemoteResult.Success -> {
                val finalAt = System.currentTimeMillis()
                summaryDiagnostics.update { current ->
                    current.copy(
                        phase = "Summary committed",
                        finalAtMillis = finalAt,
                        error = null,
                        events = current.events.plus(SummaryTraceEvent(finalAt, "Final JSON: ${finalAt - requestStartAt}ms after request")).takeLast(MAX_SUMMARY_EVENTS),
                    )
                }
                Log.d(SUMMARY_LOG_TAG, "final_summary=$finalAt after_request_start_ms=${finalAt - requestStartAt}")
                streamingContext.update {
                    it.copy(
                        current = result.value,
                        history = (it.history + ContextHistoryEntry(result.value, finalAt)).takeLast(MAX_CONTEXT_HISTORY),
                        draft = null,
                        isStreaming = false,
                        lastUpdatedAtMillis = finalAt,
                    )
                }
                remoteStatus.value = RemoteStatus.Ready
                remoteMessage.value = null
                lastSentTranscript = transcript
                runtime.sessionId?.let { app.sessions.appendSummary(it, result.value) }
            }
            is RemoteResult.Failure -> {
                val failedAt = System.currentTimeMillis()
                summaryDiagnostics.update { current ->
                    current.copy(
                        phase = "Summary failed",
                        finalAtMillis = failedAt,
                        error = result.message,
                        events = current.events.plus(SummaryTraceEvent(failedAt, "Failed: ${result.message}")).takeLast(MAX_SUMMARY_EVENTS),
                    )
                }
                streamingContext.update { it.copy(draft = null, isStreaming = false) }
                if (result.status == RemoteStatus.InvalidResponse) {
                    remoteStatus.value = RemoteStatus.Ready
                    remoteMessage.value = null
                } else {
                    remoteStatus.value = result.status
                    remoteMessage.value = result.message
                    lastSentTranscript = transcript
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

private fun MutableStateFlow<SummaryDiagnostics>.updateTrace(
    phase: String,
    cadenceSeconds: Int,
    modelId: String? = value.modelId,
    transcriptChars: Int = value.transcriptChars,
    deltaChars: Int = value.deltaChars,
) {
    val now = System.currentTimeMillis()
    update { current ->
        current.copy(
            phase = phase,
            modelId = modelId,
            cadenceSeconds = cadenceSeconds,
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
private const val SUMMARY_LOG_TAG = "ListenerSummary"
