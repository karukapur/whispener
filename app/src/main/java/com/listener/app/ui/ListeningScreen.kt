package com.listener.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.session.SessionEntity
import com.listener.app.speech.InferenceBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ListenerBlue = Color(0xFF315F8C)
private val ListenerNavy = Color(0xFF102A43)
private val ListenerPaper = Color(0xFFF7F9FC)
private val ListenerError = Color(0xFFBA1A1A)

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
    val colors = lightColorScheme(primary = ListenerBlue, background = ListenerPaper, surface = Color.White, onSurface = ListenerNavy, error = ListenerError)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
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
                    state.preferences.remoteEnabled && !state.apiKeyPresent -> "Local transcription is ready. Add an OpenRouter key in Settings for English context."
                    state.preferences.remoteEnabled && state.preferences.selectedModel == null -> "Local transcription is ready. Select a free OpenRouter model in Settings for English context."
                    else -> null
                }
                ListeningScreen(
                    recording = state.runtime.recording,
                    elapsedSeconds = state.runtime.elapsedSeconds,
                    intervalSeconds = state.preferences.summaryCadenceSeconds,
                    onIntervalChange = viewModel::setCadence,
                    onToggle = toggleRecording,
                    contextState = state.streamingContext,
                    summaryDiagnostics = state.summaryDiagnostics,
                    emptyContextMessage = "English context will appear after remote summaries are enabled.",
                    stableTranscript = state.runtime.stableTranscript,
                    provisionalTranscript = state.runtime.provisionalTranscript,
                    statusMessage = state.runtime.recoverableError ?: state.runtime.transcriptionStatus ?: state.remoteMessage ?: setupMessage,
                    statusIsError = state.runtime.recoverableError != null || (state.remoteMessage != null && state.remoteStatus != RemoteStatus.Ready),
                    remoteStatus = state.remoteStatus,
                    recordingAvailable = state.preferences.transcriptionEngine != TranscriptionEngine.WHISPER_CPP || state.installedModels.isNotEmpty(),
                    modelLoading = state.runtime.modelLoading,
                    stopping = state.runtime.stopping,
                    audioLevel = state.runtime.audioLevel,
                    activeModelId = state.runtime.activeModelId,
                    backend = state.runtime.backend,
                )
            }
            composable(Destination.Sessions.route) { SessionsScreen(state.sessions, viewModel) }
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
                    remoteEnabled = state.preferences.remoteEnabled,
                    retentionDays = state.preferences.retentionDays,
                    message = state.remoteMessage,
                    onSaveKey = viewModel::saveApiKey,
                    onClearKey = viewModel::clearApiKey,
                    onRemoteEnabled = viewModel::setRemoteEnabled,
                    onRetentionDays = viewModel::setRetentionDays,
                )
            }
        }
    }
}

@Composable fun ListeningScreen(
    recording: Boolean,
    elapsedSeconds: Long,
    intervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    onToggle: () -> Unit,
    globalContext: String = "English context will appear here.",
    details: List<String> = listOf("Audio remains on this device", "Remote summaries are optional"),
    contextState: StreamingContextState? = null,
    summaryDiagnostics: SummaryDiagnostics = SummaryDiagnostics(),
    emptyContextMessage: String = "English context will appear here.",
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
) {
    val resolvedContextState = contextState ?: legacyContextState(globalContext, details)
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp).testTag("adaptive-root")) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val density = LocalDensity.current
        var contextRatio by rememberSaveable { mutableFloatStateOf(0.75f) }
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        Column {
            if (wide) {
                Row(Modifier.weight(1f)) {
                    ContextCard(resolvedContextState, summaryDiagnostics, emptyContextMessage, Modifier.weight(contextRatio).fillMaxHeight())
                    Splitter(
                        Modifier.fillMaxHeight().width(18.dp),
                        vertical = true,
                        onDrag = { delta -> contextRatio = (contextRatio + delta / maxWidthPx).coerceIn(0.35f, 0.9f) },
                    )
                    TranscriptCard(stableTranscript, provisionalTranscript, Modifier.weight(1f - contextRatio).fillMaxHeight())
                }
            } else {
                ContextCard(resolvedContextState, summaryDiagnostics, emptyContextMessage, Modifier.weight(contextRatio).fillMaxWidth())
                Splitter(
                    Modifier.fillMaxWidth().height(18.dp),
                    vertical = false,
                    onDrag = { delta -> contextRatio = (contextRatio + delta / maxHeightPx).coerceIn(0.35f, 0.9f) },
                )
                TranscriptCard(stableTranscript, provisionalTranscript, Modifier.weight(1f - contextRatio).fillMaxWidth())
            }
            if (statusMessage != null) {
                Text(statusMessage, color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Controls(recording, recordingAvailable, modelLoading, stopping, elapsedSeconds, intervalSeconds, audioLevel, activeModelId, backend, onIntervalChange, onToggle)
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

@Composable private fun ContextCard(contextState: StreamingContextState, diagnostics: SummaryDiagnostics, emptyMessage: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    var followUpdates by remember { mutableStateOf(true) }
    val current = contextState.current
    val draft = contextState.draft
    val heading = current?.globalContext
        ?: draft?.globalContext?.takeIf(String::isNotBlank)
        ?: emptyMessage
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
    OutlinedCard(modifier, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("context-card")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("English context", style = MaterialTheme.typography.titleMedium)
                if (contextState.isStreaming) {
                    StreamingIndicator()
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(heading, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("context-heading"))
            SummaryDiagnosticsPanel(diagnostics, contextState.isStreaming)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(scroll).testTag("context-history")) {
                if (contextState.history.isEmpty() && draft == null) {
                    Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                } else {
                    contextState.history.forEach { entry -> ContextHistoryItem(entry.context) }
                    draft?.let { ContextDraftItem(it) }
                }
            }
        }
    }
}

@Composable private fun ContextHistoryItem(context: ListeningContext) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(context.globalContext, style = MaterialTheme.typography.labelLarge)
        context.details.forEach { Text("• $it", Modifier.padding(top = 6.dp)) }
    }
}

@Composable private fun StreamingIndicator() {
    val rotation by rememberInfiniteTransition(label = "summary fetch").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "summary fetch rotation",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("↻", color = MaterialTheme.colorScheme.primary, modifier = Modifier.graphicsLayer { rotationZ = rotation }, style = MaterialTheme.typography.labelLarge)
        Text("Fetching", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
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
                "Cadence ${diagnostics.cadenceSeconds ?: "-"}s · transcript ${diagnostics.transcriptChars} chars · delta ${diagnostics.deltaChars} chars",
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
        Text(heading, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        context.details.forEach { Text("• $it", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    OutlinedCard(modifier.testTag("transcript"), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Traditional Chinese transcript", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 8.dp).weight(1f).verticalScroll(scroll)) {
                if (stableText.isBlank() && provisionalText.isBlank()) {
                    Text("Traditional Chinese speech transcribed on this phone will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(stableText, style = MaterialTheme.typography.bodyLarge)
                    AnimatedVisibility(visible = provisionalText.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            provisionalText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { contentDescription = "Provisional transcript: $provisionalText" },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Controls(
    recording: Boolean,
    recordingAvailable: Boolean,
    modelLoading: Boolean,
    stopping: Boolean,
    elapsed: Long,
    interval: Int,
    audioLevel: Float,
    activeModelId: String?,
    backend: InferenceBackend?,
    change: (Int) -> Unit,
    toggle: () -> Unit,
) {
    val indicatorAlpha by animateFloatAsState(if (recording) 0.55f + audioLevel * 0.45f else 0f, label = "recording indicator")
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = indicatorAlpha }.background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.extraLarge))
            AnimatedContent(targetState = Triple(recording, modelLoading, stopping), label = "recording status") { state ->
                val text = when {
                    state.third -> "Finishing transcript…"
                    state.second -> "Loading ${activeModelId.orEmpty()}…"
                    state.first -> "Recording  %02d:%02d".format(elapsed / 60, elapsed % 60)
                    else -> "Ready to listen"
                }
                Text(text, modifier = Modifier.semantics { contentDescription = if (recording) "Recording active" else "Recording stopped" })
            }
            if (recording && !modelLoading) AudioLevelMeter(audioLevel)
            backend?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                    Text(it.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = "Inference backend ${it.label}" }, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(5, 10).forEachIndexed { index, seconds ->
                    SegmentedButton(selected = interval == seconds, onClick = { change(seconds) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text("${seconds}s") }
                }
            }
            Button(enabled = !stopping && (recording || recordingAvailable), onClick = toggle, modifier = Modifier.heightIn(min = 48.dp).testTag("record-toggle"), colors = ButtonDefaults.buttonColors(containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                AnimatedContent(targetState = when { stopping -> "Finishing…"; modelLoading -> "Cancel"; recording -> "Stop"; recordingAvailable -> "Start"; else -> "Model required" }, label = "record action") { Text(it) }
            }
        }
    }
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

@Composable private fun SessionsScreen(sessions: List<SessionEntity>, viewModel: ListenerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SessionEntity?>(null) }
    var editingTranscriptSeed by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Your saved listening sessions will appear here.") }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sessions.forEach { session ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (session.endedAt == null) "In progress" else "Saved", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { editingTranscriptSeed = viewModel.exportSession(session.id); editing = session } }) { Text("Edit") }
                            TextButton(onClick = {
                                scope.launch {
                                    val text = viewModel.exportSession(session.id)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Export transcript"))
                                }
                            }) { Text("Export") }
                            TextButton(onClick = { deleting = session }) { Text("Delete") }
                        }
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
            text = { Text("The saved transcript and English context will be permanently removed.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSession(session.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
