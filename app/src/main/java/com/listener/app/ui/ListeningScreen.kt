package com.listener.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.listener.app.ContextHistoryEntry
import com.listener.app.ListenerUiState
import com.listener.app.ListenerViewModel
import com.listener.app.SummaryDiagnostics
import com.listener.app.StreamingContextState
import com.listener.app.context.ListeningContext
import com.listener.app.context.RemoteStatus
import com.listener.app.data.MAX_SUMMARY_CADENCE_MILLIS
import com.listener.app.data.MIN_SUMMARY_CADENCE_MILLIS
import com.listener.app.data.SUMMARY_CADENCE_STEP_MILLIS
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.minimumSummaryCadenceMillis
import com.listener.app.data.snapSummaryCadenceMillis
import com.listener.app.data.session.SessionEntity
import com.listener.app.speech.InferenceBackend
import com.listener.app.ui.theme.ListenerMotion
import com.listener.app.ui.theme.ListenerSpacing
import com.listener.app.ui.theme.ListenerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String, val marker: String) {
    Listen("listen", "Listen", "●"), Sessions("sessions", "Sessions", "≡"), Models("models", "Models", "↓"), Settings("settings", "Settings", "⚙")
}

@Composable fun ListenerApp(viewModel: ListenerViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    KeepScreenAwake(state.runtime.recording)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) viewModel.startRecording(context)
        else com.listener.app.audio.ListeningRuntime.update { it.copy(recoverableError = "Microphone permission is required to transcribe speech.") }
    }
    ListenerTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (!state.preferences.onboardingComplete) {
                    PrivacyOnboarding { remote, retention -> viewModel.completeOnboarding(remote, retention) }
                } else {
                    ListenerNavigation(state, viewModel) {
                        if (state.runtime.recording) viewModel.stopRecording(context) else {
                            val required = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.toTypedArray()
                            val missing = required.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            if (missing) permissionLauncher.launch(required) else viewModel.startRecording(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun KeepScreenAwake(recording: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, recording) {
        if (recording) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (recording) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ListenerNavigation(state: ListenerUiState, viewModel: ListenerViewModel, toggleRecording: () -> Unit) {
    val navController = rememberNavController()
    var selected by rememberSaveable { mutableStateOf(Destination.Listen) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(selected.label) }) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination; navController.navigate(destination.route) { launchSingleTop = true; popUpTo(Destination.Listen.route) { saveState = true }; restoreState = true } },
                        icon = { Text(destination.marker, modifier = Modifier.semantics { contentDescription = destination.label }) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Destination.Listen.route, Modifier.padding(padding)) {
            composable(Destination.Listen.route) {
                val setupMessage = when {
                    state.preferences.transcriptionEngine == TranscriptionEngine.WHISPER_CPP && state.installedModels.isEmpty() -> "A local Whisper model is required before recording. Open Models to view the Base download."
                    else -> null
                }
                ListeningScreen(
                    recording = state.runtime.recording,
                    elapsedSeconds = state.runtime.elapsedSeconds,
                    intervalMillis = state.preferences.summaryCadenceMillis,
                    onIntervalChange = viewModel::setCadenceMillis,
                    onToggle = toggleRecording,
                    contextState = state.streamingContext,
                    summaryDiagnostics = state.summaryDiagnostics,
                    remoteMessage = state.remoteMessage,
                    stableTranscript = state.runtime.stableTranscript,
                    provisionalTranscript = state.runtime.provisionalTranscript,
                    statusMessage = state.runtime.recoverableError ?: state.runtime.transcriptionStatus ?: setupMessage,
                    statusIsError = state.runtime.recoverableError != null,
                    remoteStatus = state.remoteStatus,
                    recordingAvailable = state.preferences.transcriptionEngine != TranscriptionEngine.WHISPER_CPP || state.installedModels.isNotEmpty(),
                    modelLoading = state.runtime.modelLoading,
                    stopping = state.runtime.stopping,
                    audioLevel = state.runtime.audioLevel,
                    activeModelId = state.runtime.activeModelId,
                    backend = state.runtime.backend,
                    minimumIntervalMillis = minimumSummaryCadenceMillis(state.preferences.selectedModel),
                )
            }
            composable(Destination.Sessions.route) {
                SessionsScreen(state.sessions, viewModel, state.summaryDiagnostics, state.streamingContext.isStreaming)
            }
            composable(Destination.Models.route) {
                ModelManagementScreen(
                    activeId = state.runtime.activeModelId,
                    selectedId = state.preferences.selectedLocalModelId,
                    selectedEngine = state.preferences.transcriptionEngine,
                    workProfile = state.preferences.whisperWorkProfile,
                    backend = state.runtime.backend,
                    recording = state.runtime.recording,
                    installedIds = state.installedModels.map { it.modelId }.toSet(),
                    progress = state.download.progress,
                    downloadModelId = state.download.modelId.takeIf { state.download.running },
                    error = state.download.error,
                    onDownload = viewModel::downloadModel,
                    onCancel = viewModel::cancelDownload,
                    onDelete = viewModel::deleteModel,
                    onSelect = viewModel::selectLocalModel,
                    onSelectEngine = viewModel::setTranscriptionEngine,
                    onWorkProfile = viewModel::setWhisperWorkProfile,
                )
            }
            composable(Destination.Settings.route) {
                RemoteSettings(
                    apiKeyPresent = state.apiKeyPresent,
                    groqApiKeyPresent = state.groqApiKeyPresent,
                    remoteEnabled = state.preferences.remoteEnabled,
                    retentionDays = state.preferences.retentionDays,
                    selectedModel = state.preferences.selectedModel,
                    catalog = state.catalog,
                    catalogLoading = state.catalogLoading,
                    message = state.remoteMessage,
                    onSaveKey = viewModel::saveApiKey,
                    onClearKey = viewModel::clearApiKey,
                    onRemoteEnabled = viewModel::setRemoteEnabled,
                    onRetentionDays = viewModel::setRetentionDays,
                    onRefreshCatalog = viewModel::refreshCatalog,
                    onSelectRemoteModel = viewModel::selectRemoteModel,
                )
            }
        }
    }
}

@Composable fun ListeningScreen(
    recording: Boolean,
    elapsedSeconds: Long,
    intervalMillis: Int,
    onIntervalChange: (Int) -> Unit,
    onToggle: () -> Unit,
    globalContext: String = "",
    details: List<String> = emptyList(),
    contextState: StreamingContextState? = null,
    summaryDiagnostics: SummaryDiagnostics = SummaryDiagnostics(),
    remoteMessage: String? = null,
    emptyContextMessage: String = "",
    stableTranscript: String = "",
    provisionalTranscript: String = "",
    statusMessage: String? = null,
    statusIsError: Boolean = false,
    remoteStatus: RemoteStatus = RemoteStatus.Ready,
    recordingAvailable: Boolean = true,
    modelLoading: Boolean = false,
    stopping: Boolean = false,
    audioLevel: Float = 0f,
    activeModelId: String? = null,
    backend: InferenceBackend? = null,
    minimumIntervalMillis: Int = MIN_SUMMARY_CADENCE_MILLIS,
) {
    val resolvedContextState = contextState ?: legacyContextState(globalContext, details)
    BoxWithConstraints(Modifier.fillMaxSize().padding(ListenerSpacing.Large).testTag("adaptive-root")) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val density = LocalDensity.current
        var contextRatio by rememberSaveable { mutableFloatStateOf(0.75f) }
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        Column {
            if (wide) {
                Row(Modifier.weight(1f)) {
                    ContextCard(resolvedContextState, summaryDiagnostics, remoteStatus, remoteMessage, emptyContextMessage, Modifier.weight(contextRatio).fillMaxHeight())
                    Splitter(
                        Modifier.fillMaxHeight().width(18.dp),
                        vertical = true,
                        onDrag = { delta -> contextRatio = (contextRatio + delta / maxWidthPx).coerceIn(0.35f, 0.9f) },
                    )
                    TranscriptCard(stableTranscript, provisionalTranscript, Modifier.weight(1f - contextRatio).fillMaxHeight())
                }
            } else {
                ContextCard(resolvedContextState, summaryDiagnostics, remoteStatus, remoteMessage, emptyContextMessage, Modifier.weight(contextRatio).fillMaxWidth())
                Splitter(
                    Modifier.fillMaxWidth().height(18.dp),
                    vertical = false,
                    onDrag = { delta -> contextRatio = (contextRatio + delta / maxHeightPx).coerceIn(0.35f, 0.9f) },
                )
                TranscriptCard(stableTranscript, provisionalTranscript, Modifier.weight(1f - contextRatio).fillMaxWidth())
            }
            Controls(
                recording = recording,
                recordingAvailable = recordingAvailable,
                modelLoading = modelLoading,
                stopping = stopping,
                elapsed = elapsedSeconds,
                intervalMillis = intervalMillis,
                minimumIntervalMillis = minimumIntervalMillis,
                audioLevel = audioLevel,
                activeModelId = activeModelId,
                backend = backend,
                statusMessage = statusMessage,
                statusIsError = statusIsError,
                change = onIntervalChange,
                toggle = onToggle,
            )
        }
    }
}

@Composable private fun Splitter(modifier: Modifier, vertical: Boolean, onDrag: (Float) -> Unit) {
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onDrag(if (vertical) dragAmount.x else dragAmount.y)
            }
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (vertical) {
            VerticalDivider(Modifier.fillMaxHeight(0.72f), color = MaterialTheme.colorScheme.outlineVariant)
        } else {
            HorizontalDivider(Modifier.fillMaxWidth(0.72f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private fun legacyContextState(global: String, details: List<String>): StreamingContextState =
    StreamingContextState(
        current = ListeningContext(global, details),
        history = if (details.isEmpty()) emptyList() else listOf(ContextHistoryEntry(ListeningContext(global, details), 0L)),
    )

@Composable private fun ContextCard(
    contextState: StreamingContextState,
    diagnostics: SummaryDiagnostics,
    remoteStatus: RemoteStatus,
    remoteMessage: String?,
    emptyMessage: String,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    var followUpdates by remember { mutableStateOf(true) }
    val current = contextState.current
    val draft = contextState.draft
    val heading = current?.globalContext
        ?: draft?.globalContext?.takeIf(String::isNotBlank).orEmpty()
    LaunchedEffect(scroll.value, scroll.maxValue, scroll.isScrollInProgress) {
        if (scroll.value >= scroll.maxValue - 48) followUpdates = true
        else if (scroll.isScrollInProgress) followUpdates = false
    }
    LaunchedEffect(contextState.history.size, draft, contextState.isStreaming) {
        if (followUpdates) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(ListenerSpacing.Large).testTag("context-card")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("English context", style = MaterialTheme.typography.titleLarge)
                if (contextState.isStreaming) {
                    StreamingIndicator()
                }
            }
            Spacer(Modifier.height(ListenerSpacing.Small))
            if (heading.isNotBlank()) Text(heading, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("context-heading"))
            englishContextStatus(diagnostics, contextState.isStreaming, remoteStatus, remoteMessage)?.let { status ->
                val isError = remoteStatus != RemoteStatus.Ready
                Surface(
                    color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = ListenerSpacing.Small).fillMaxWidth().testTag("english-context-status"),
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = ListenerSpacing.Medium, vertical = ListenerSpacing.Small))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = ListenerSpacing.Medium), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f))
            Column(Modifier.weight(1f).verticalScroll(scroll).testTag("context-history")) {
                if (contextState.history.isEmpty() && draft == null) {
                    Text(
                        emptyMessage.ifBlank { "English context will appear here once finalized Chinese speech is summarized." },
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("english-context-empty"),
                    )
                } else {
                    contextState.history.forEach { entry -> ContextHistoryItem(entry.context) }
                    draft?.let { ContextDraftItem(it) }
                }
            }
        }
    }
}

private fun englishContextStatus(
    diagnostics: SummaryDiagnostics,
    streaming: Boolean,
    remoteStatus: RemoteStatus,
    remoteMessage: String?,
): String? {
    if (!remoteMessage.isNullOrBlank()) return remoteMessage
    if (streaming) return "Streaming English context"
    return when (diagnostics.phase) {
        "Idle" -> null
        "Waiting for finalized transcript" -> "Waiting for finalized Chinese"
        "Transcript ready", "OpenRouter request started", "Groq request started" -> "Sending summary"
        "No new finalized transcript" -> "No new finalized Chinese"
        "Remote summaries disabled" -> "Remote summaries disabled"
        else -> diagnostics.phase.takeIf { it.isNotBlank() }
    } ?: if (remoteStatus == RemoteStatus.Ready) null else "English summary remote status: $remoteStatus"
}

@Composable private fun ContextHistoryItem(context: ListeningContext) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(context.globalContext, style = MaterialTheme.typography.labelLarge)
        context.details.forEach { Text("• $it", Modifier.padding(top = 6.dp)) }
    }
}

@Composable private fun StreamingIndicator() {
    Row(
        modifier = Modifier.semantics { contentDescription = "Loading English context" },
        horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Small),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text("Updating", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun SummaryDiagnosticsPanel(diagnostics: SummaryDiagnostics, streaming: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(streaming, diagnostics.requestStartedAtMillis, diagnostics.firstTokenAtMillis) {
        while (streaming) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val requestStarted = diagnostics.requestStartedAtMillis
    val firstToken = diagnostics.firstTokenAtMillis
    val finalAt = diagnostics.finalAtMillis
    val firstTokenPendingMs = if (streaming && requestStarted != null && firstToken == null) now - requestStarted else null
    val warning = firstTokenPendingMs?.takeIf { it >= FIRST_TOKEN_WARNING_MS }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("summary-diagnostics"),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Summary trace", style = MaterialTheme.typography.labelLarge)
            Text("Phase: ${diagnostics.phase}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Cadence ${diagnostics.cadenceMillis?.formatCadenceMillis() ?: "-"} · transcript ${diagnostics.transcriptChars} chars · delta ${diagnostics.deltaChars} chars",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Request ${requestStarted.relativeTo(diagnostics.transcriptReadyAtMillis)} · first token ${firstToken.relativeTo(requestStarted, firstTokenPendingMs)} · final ${finalAt.relativeTo(requestStarted)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (warning != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            diagnostics.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            diagnostics.events.takeLast(4).forEach { event ->
                Text("${event.timeMillis.clockTime()}  ${event.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ContextDraftItem(context: ListeningContext) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = "Streaming English context update" }) {
        val heading = context.globalContext.ifBlank { "Updating…" }
        Text(heading, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        context.details.forEach {
            Text("• $it", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f))
        }
    }
}

private fun Long?.relativeTo(start: Long?, pendingMs: Long? = null): String = when {
    this != null && start != null -> formatDuration(this - start)
    pendingMs != null -> "pending ${formatDuration(pendingMs)}"
    this != null -> "seen"
    else -> "-"
}

private fun formatDuration(ms: Long): String = if (ms < 1_000) "${ms.coerceAtLeast(0)}ms" else "%.1fs".format(ms / 1_000f)

private fun Long.clockTime(): String {
    val totalSeconds = this / 1_000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = (totalSeconds / 3_600) % 24
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private const val FIRST_TOKEN_WARNING_MS = 10_000L

@Composable private fun TranscriptCard(stableText: String, provisionalText: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    val shouldFollow = scroll.value >= scroll.maxValue - 48
    LaunchedEffect(stableText, provisionalText) {
        if (shouldFollow) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Card(
        modifier = modifier.testTag("transcript"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(ListenerSpacing.Large)) {
            Text("Traditional Chinese transcript", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = ListenerSpacing.Small).weight(1f).verticalScroll(scroll)) {
                if (stableText.isNotBlank() || provisionalText.isNotBlank()) {
                    Text(stableText, style = MaterialTheme.typography.bodyLarge)
                    AnimatedVisibility(visible = provisionalText.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            provisionalText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { contentDescription = "Provisional transcript: $provisionalText" },
                        )
                    }
                } else {
                    Text(
                        "Chinese speech will appear here while listening.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("transcript-empty"),
                    )
                }
            }
        }
    }
}

@Composable private fun Controls(
    recording: Boolean,
    recordingAvailable: Boolean,
    modelLoading: Boolean,
    stopping: Boolean,
    elapsed: Long,
    intervalMillis: Int,
    minimumIntervalMillis: Int,
    audioLevel: Float,
    activeModelId: String?,
    backend: InferenceBackend?,
    statusMessage: String?,
    statusIsError: Boolean,
    change: (Int) -> Unit,
    toggle: () -> Unit,
) {
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (recording) 0.55f + audioLevel * 0.45f else 0.28f,
        animationSpec = tween(ListenerMotion.FastDurationMillis),
        label = "recording indicator",
    )
    val actionCorner by animateDpAsState(
        targetValue = if (recording) 18.dp else 28.dp,
        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
        label = "record action shape",
    )
    val actionColor by animateColorAsState(
        targetValue = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
        label = "record action color",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = ListenerSpacing.Medium).testTag("listening-controls"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(ListenerSpacing.Large)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Small),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge),
                )
                AnimatedContent(
                    targetState = Triple(recording, modelLoading, stopping),
                    modifier = Modifier.weight(1f),
                    label = "recording status",
                ) { state ->
                    val text = when {
                        state.third -> "Finishing transcript…"
                        state.second -> "Loading ${activeModelId.orEmpty()}…"
                        state.first -> "Recording  %02d:%02d".format(elapsed / 60, elapsed % 60)
                        else -> "Ready to listen"
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = if (recording) "Recording active" else "Recording stopped" },
                    )
                }
                if (recording && !modelLoading) AudioLevelMeter(audioLevel)
                backend?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Text(
                            it.label,
                            Modifier
                                .padding(horizontal = ListenerSpacing.Medium, vertical = ListenerSpacing.Small)
                                .semantics { contentDescription = "Inference backend ${it.label}" },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            statusMessage?.let { message ->
                Surface(
                    color = if (statusIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (statusIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = ListenerSpacing.Small).testTag("local-status"),
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(ListenerSpacing.Medium))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = ListenerSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Medium),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                CadenceSlider(intervalMillis, minimumIntervalMillis, change, Modifier.weight(1f))
                Button(
                    enabled = !stopping && (recording || recordingAvailable),
                    onClick = toggle,
                    modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 104.dp).testTag("record-toggle"),
                    shape = RoundedCornerShape(actionCorner),
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                ) {
                    val action = when {
                        stopping -> "Finishing…"
                        modelLoading -> "Cancel"
                        recording -> "Stop"
                        recordingAvailable -> "Start"
                        else -> "Model required"
                    }
                    AnimatedContent(targetState = action, label = "record action") { label ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Small),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            if (modelLoading || stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp).testTag("model-loading-indicator"),
                                    color = LocalContentColor.current,
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CadenceSlider(intervalMillis: Int, minimumIntervalMillis: Int, change: (Int) -> Unit, modifier: Modifier = Modifier) {
    val minimum = minimumIntervalMillis.snapSummaryCadenceMillis()
    val snapped = intervalMillis.coerceAtLeast(minimum).snapSummaryCadenceMillis()
    Column(modifier) {
        Text("Summary every ${snapped.formatCadenceMillis()}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = snapped.toFloat(),
            onValueChange = { change(it.toInt().coerceAtLeast(minimum).snapSummaryCadenceMillis()) },
            valueRange = minimum.toFloat()..MAX_SUMMARY_CADENCE_MILLIS.toFloat(),
            steps = ((MAX_SUMMARY_CADENCE_MILLIS - minimum) / SUMMARY_CADENCE_STEP_MILLIS) - 1,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cadence-slider")
                .semantics { contentDescription = "Summary cadence ${snapped.formatCadenceMillis()}" },
        )
    }
}

private fun Int.formatCadenceMillis(): String {
    val snapped = snapSummaryCadenceMillis()
    return if (snapped % 1_000 == 0) "${snapped / 1_000}s" else "%.1fs".format(snapped / 1_000f)
}

@Composable private fun AudioLevelMeter(level: Float) {
    Row(
        Modifier.height(24.dp).semantics { contentDescription = "Microphone level ${(level * 100).toInt()} percent" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        listOf(0.55f, 1f, 0.72f).forEachIndexed { index, multiplier ->
            val height by animateDpAsState((6 + 16 * (level * multiplier)).dp, label = "audio level $index")
            Box(Modifier.width(3.dp).height(height).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
        }
    }
}

@Composable private fun SessionsScreen(
    sessions: List<SessionEntity>,
    viewModel: ListenerViewModel,
    summaryDiagnostics: SummaryDiagnostics,
    summaryStreaming: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var editingTranscriptSeed by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryDiagnosticsPanel(summaryDiagnostics, summaryStreaming)
        saveMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (sessions.isEmpty()) {
            Text("Your saved listening sessions will appear here.", Modifier.padding(top = 12.dp))
        } else {
            sessions.forEach { session ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (session.endedAt == null) "In progress" else "Saved", style = MaterialTheme.typography.bodySmall)
                        SessionActions(
                            onEdit = { scope.launch { editingTranscriptSeed = viewModel.exportSession(session.id); editing = session } },
                            onExport = {
                                scope.launch {
                                    val text = viewModel.exportSession(session.id)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Export transcript"))
                                }
                            },
                            onShareTrace = {
                                scope.launch {
                                    saveMessage = runCatching {
                                        val intent = viewModel.createSessionSummaryTraceShareIntent(session.id)
                                        context.startActivity(Intent.createChooser(intent, "Share summary trace"))
                                        "Opening share sheet."
                                    }.getOrElse { error ->
                                        error.message ?: "Unable to share summary trace."
                                    }
                                }
                            },
                            onDelete = { deleting = session },
                        )
                    }
                }
            }
        }
    }
    editing?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        var transcript by remember(session.id) { mutableStateOf(editingTranscriptSeed) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit session") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Title") }); OutlinedTextField(transcript, { transcript = it }, label = { Text("Edited transcript (optional)") }, minLines = 4) } },
            confirmButton = { TextButton(onClick = { viewModel.editSession(session.id, title, transcript.ifBlank { null }); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this session?") },
            text = { Text("The saved transcript and English summaries will be permanently removed.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSession(session.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable internal fun SessionActions(
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onShareTrace: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(onClick = onEdit, modifier = Modifier.testTag("session-action-edit")) {
            Icon(painterResource(android.R.drawable.ic_menu_edit), contentDescription = "Edit session")
        }
        IconButton(onClick = onExport, modifier = Modifier.testTag("session-action-export")) {
            Icon(painterResource(android.R.drawable.ic_menu_send), contentDescription = "Export transcript")
        }
        IconButton(onClick = onShareTrace, modifier = Modifier.testTag("session-action-share-trace")) {
            Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = "Share trace")
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag("session-action-delete")) {
            Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = "Delete session")
        }
    }
}

@Preview(name = "Listen · Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ListeningLightPreview() {
    ListeningPreviewFrame {
        ListeningScreen(
            recording = false,
            elapsedSeconds = 0,
            intervalMillis = 2_500,
            onIntervalChange = {},
            onToggle = {},
            contextState = previewContextState(),
            stableTranscript = "我們先確認明天早上九點在台北車站見面。",
            provisionalTranscript = "然後一起去",
            backend = InferenceBackend.VULKAN,
        )
    }
}

@Preview(
    name = "Listen · Dark recording",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ListeningDarkPreview() {
    ListeningPreviewFrame(darkTheme = true) {
        ListeningScreen(
            recording = true,
            elapsedSeconds = 65,
            intervalMillis = 5_000,
            onIntervalChange = {},
            onToggle = {},
            contextState = previewContextState(),
            stableTranscript = "我們先確認明天早上九點在台北車站見面。",
            provisionalTranscript = "然後一起去吃早餐。",
            audioLevel = 0.64f,
            activeModelId = "sherpa-paraformer-zh-en",
            backend = InferenceBackend.CPU,
        )
    }
}

@Preview(name = "Listen · Empty", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ListeningEmptyPreview() {
    ListeningPreviewFrame {
        ListeningScreen(false, 0, 5_000, {}, {})
    }
}

@Preview(name = "Listen · Loading", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ListeningLoadingPreview() {
    ListeningPreviewFrame {
        ListeningScreen(
            recording = false,
            elapsedSeconds = 0,
            intervalMillis = 2_000,
            onIntervalChange = {},
            onToggle = {},
            contextState = previewContextState().copy(
                draft = ListeningContext("Adding travel details", listOf("Checking the meeting point")),
                isStreaming = true,
            ),
            stableTranscript = "明天早上九點在台北車站見面。",
            modelLoading = true,
            activeModelId = "paraformer",
        )
    }
}

@Preview(name = "Listen · Remote error", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ListeningRemoteErrorPreview() {
    ListeningPreviewFrame {
        ListeningScreen(
            recording = true,
            elapsedSeconds = 18,
            intervalMillis = 2_500,
            onIntervalChange = {},
            onToggle = {},
            contextState = previewContextState(),
            stableTranscript = "明天早上九點在台北車站見面。",
            remoteStatus = RemoteStatus.ModelUnavailable,
            remoteMessage = "No endpoints found. Local transcription is still active.",
            audioLevel = 0.32f,
            backend = InferenceBackend.CPU,
        )
    }
}

@Composable
private fun ListeningPreviewFrame(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    ListenerTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

private fun previewContextState(): StreamingContextState = StreamingContextState(
    current = ListeningContext(
        globalContext = "Planning tomorrow's meetup",
        details = listOf("Meet at Taipei Main Station at 9:00 AM", "Breakfast is the next likely stop"),
    ),
    history = listOf(
        ContextHistoryEntry(
            ListeningContext(
                globalContext = "Planning tomorrow's meetup",
                details = listOf("Meet at Taipei Main Station at 9:00 AM", "Breakfast is the next likely stop"),
            ),
            createdAtMillis = 0L,
        ),
    ),
)
