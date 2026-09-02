package com.listener.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.annotation.DrawableRes
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.listener.app.R
import com.listener.app.ContextHistoryEntry
import com.listener.app.ListenerUiState
import com.listener.app.ListenerViewModel
import com.listener.app.SummaryDiagnostics
import com.listener.app.StreamingContextState
import com.listener.app.context.ListeningContext
import com.listener.app.context.RemoteStatus
import com.listener.app.data.MIN_SUMMARY_CADENCE_MILLIS
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.minimumSummaryCadenceMillis
import com.listener.app.data.snapSummaryCadenceMillis
import com.listener.app.data.session.SessionEntity
import com.listener.app.models.ModelManager
import com.listener.app.speech.InferenceBackend
import com.listener.app.ui.theme.ListenerMotion
import com.listener.app.ui.theme.ListenerSpacing
import com.listener.app.ui.theme.ListenerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String, @DrawableRes val iconRes: Int) {
    Listen("listen", "Listen", R.drawable.ic_nav_listen),
    Sessions("sessions", "Sessions", R.drawable.ic_nav_sessions),
    Models("models", "Models", R.drawable.ic_nav_models),
    Settings("settings", "Settings", R.drawable.ic_nav_settings),
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
                            if (state.isMissingRequiredLocalModel()) {
                                viewModel.startRecording(context)
                                return@ListenerNavigation
                            }
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

private fun ListenerUiState.isMissingRequiredLocalModel(): Boolean {
    if (preferences.transcriptionEngine == TranscriptionEngine.ANDROID_ON_DEVICE) return false
    val installedIds = installedModels.map { it.modelId }.toSet()
    return when (preferences.transcriptionEngine) {
        TranscriptionEngine.WHISPER_CPP -> installedIds.isEmpty()
        TranscriptionEngine.SHERPA_ONNX ->
            ModelManager.STREAMING_PARAFORMER_BILINGUAL_ID !in installedIds &&
                ModelManager.SENSE_VOICE_ID !in installedIds
        TranscriptionEngine.ANDROID_ON_DEVICE -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ListenerNavigation(state: ListenerUiState, viewModel: ListenerViewModel, toggleRecording: () -> Unit) {
    val navController = rememberNavController()
    var selected by rememberSaveable { mutableStateOf(Destination.Listen) }
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination; navController.navigate(destination.route) { launchSingleTop = true; popUpTo(Destination.Listen.route) { saveState = true }; restoreState = true } },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = destination.label,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Destination.Listen.route, Modifier.padding(padding)) {
            composable(
                Destination.Listen.route,
                enterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                exitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
            ) {
                val setupMessage = when {
                    state.preferences.transcriptionEngine == TranscriptionEngine.WHISPER_CPP && state.installedModels.isEmpty() -> "A local Whisper model is required before recording. Open Models to view the Base download."
                    state.isMissingRequiredLocalModel() -> "Install a local model from Models before listening."
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
                    recordingAvailable = !state.isMissingRequiredLocalModel(),
                    modelLoading = state.runtime.modelLoading,
                    stopping = state.runtime.stopping,
                    audioLevel = state.runtime.audioLevel,
                    activeModelId = state.runtime.activeModelId,
                    backend = state.runtime.backend,
                    downloadModelId = state.download.modelId.takeIf { state.download.running },
                    downloadProgress = state.download.progress,
                    onCancelDownload = viewModel::cancelDownload,
                    minimumIntervalMillis = minimumSummaryCadenceMillis(state.preferences.selectedModel),
                )
            }
            composable(
                Destination.Sessions.route,
                enterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                exitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
            ) {
                SessionsScreen(state.sessions, viewModel, state.summaryDiagnostics, state.streamingContext.isStreaming)
            }
            composable(
                Destination.Models.route,
                enterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                exitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
            ) {
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
            composable(
                Destination.Settings.route,
                enterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                exitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { tabEnterTransition(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { tabExitTransition(initialState.destination.route, targetState.destination.route) },
            ) {
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
                    summaryCadenceMillis = state.preferences.summaryCadenceMillis,
                    onSummaryCadenceChange = viewModel::setCadenceMillis,
                )
            }
        }
    }
}

private fun tabEnterTransition(fromRoute: String?, toRoute: String?): EnterTransition {
    val direction = tabDirection(fromRoute, toRoute)
    return fadeIn(animationSpec = tween(TabFadeInMillis)) +
        slideInHorizontally(
            animationSpec = tween(TabSlideMillis, easing = ListenerMotion.EmphasisEasing),
            initialOffsetX = { fullWidth -> direction * (fullWidth / TabSlideDistanceDivisor) },
        )
}

private fun tabExitTransition(fromRoute: String?, toRoute: String?): ExitTransition {
    val direction = tabDirection(fromRoute, toRoute)
    return fadeOut(animationSpec = tween(TabFadeOutMillis)) +
        slideOutHorizontally(
            animationSpec = tween(TabSlideMillis, easing = ListenerMotion.EmphasisEasing),
            targetOffsetX = { fullWidth -> -direction * (fullWidth / TabSlideDistanceDivisor) },
        )
}

private fun tabDirection(fromRoute: String?, toRoute: String?): Int {
    val from = Destination.entries.indexOfFirst { it.route == fromRoute }
    val to = Destination.entries.indexOfFirst { it.route == toRoute }
    return when {
        from == -1 || to == -1 || from == to -> 0
        to > from -> 1
        else -> -1
    }
}

private const val TabFadeInMillis = 150
private const val TabFadeOutMillis = 90
private const val TabSlideMillis = 180
private const val TabSlideDistanceDivisor = 18

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
    animateIdleSphere: Boolean = true,
    audioLevel: Float = 0f,
    activeModelId: String? = null,
    backend: InferenceBackend? = null,
    downloadModelId: String? = null,
    downloadProgress: Float? = null,
    onCancelDownload: () -> Unit = {},
    minimumIntervalMillis: Int = MIN_SUMMARY_CADENCE_MILLIS,
) {
    val resolvedContextState = contextState ?: legacyContextState(globalContext, details)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag("adaptive-root"),
    ) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val density = LocalDensity.current
        var contextRatio by rememberSaveable { mutableFloatStateOf(0.6f) }
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ListenerSpacing.Small)) {
            ScreenTitle("Listen")
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ContextCard(
                        contextState = resolvedContextState,
                        diagnostics = summaryDiagnostics,
                        remoteStatus = remoteStatus,
                        remoteMessage = remoteMessage,
                        emptyMessage = emptyContextMessage,
                        orbActive = recording || resolvedContextState.isStreaming || summaryDiagnostics.isEnglishContextInFlight(),
                        showIdleSphere = !recording && !modelLoading && !stopping,
                        animateIdleSphere = animateIdleSphere,
                        modifier = Modifier.weight(contextRatio).fillMaxHeight(),
                    )
                    Splitter(
                        Modifier.fillMaxHeight().width(4.dp),
                        vertical = true,
                        onDrag = { delta -> contextRatio = (contextRatio + delta / maxWidthPx).coerceIn(0.35f, 0.9f) },
                    )
                    TranscriptCard(stableTranscript, provisionalTranscript, Modifier.weight(1f - contextRatio).fillMaxHeight())
                }
            } else {
                ContextCard(
                    contextState = resolvedContextState,
                    diagnostics = summaryDiagnostics,
                    remoteStatus = remoteStatus,
                    remoteMessage = remoteMessage,
                    emptyMessage = emptyContextMessage,
                    orbActive = recording || resolvedContextState.isStreaming || summaryDiagnostics.isEnglishContextInFlight(),
                    showIdleSphere = !recording && !modelLoading && !stopping,
                    animateIdleSphere = animateIdleSphere,
                    modifier = Modifier.weight(contextRatio).fillMaxWidth(),
                )
                Splitter(
                    Modifier.fillMaxWidth().height(6.dp),
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
                toggle = onToggle,
            )
        }
        CompactListenStatusOverlay(
            recording = recording,
            modelLoading = modelLoading,
            stopping = stopping,
            elapsed = elapsedSeconds,
            audioLevel = audioLevel,
            activeModelId = activeModelId,
            backend = backend,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable private fun CompactListenStatusOverlay(
    recording: Boolean,
    modelLoading: Boolean,
    stopping: Boolean,
    elapsed: Long,
    audioLevel: Float,
    activeModelId: String?,
    backend: InferenceBackend?,
    modifier: Modifier = Modifier,
) {
    val showStatus = recording || modelLoading || stopping
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (recording) 0.55f + audioLevel * 0.45f else 0.28f,
        animationSpec = tween(ListenerMotion.FastDurationMillis),
        label = "recording indicator",
    )
    AnimatedVisibility(visible = showStatus, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
            modifier = Modifier.widthIn(max = 180.dp).testTag("listen-header-status"),
        ) {
            Column(
                Modifier.padding(horizontal = ListenerSpacing.Small, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CompactRecordingRow(
                    recording = recording,
                    modelLoading = modelLoading,
                    stopping = stopping,
                    elapsed = elapsed,
                    audioLevel = audioLevel,
                    activeModelId = activeModelId,
                    backend = backend,
                    indicatorAlpha = indicatorAlpha,
                )
            }
        }
    }
}

@Composable private fun CompactRecordingRow(
    recording: Boolean,
    modelLoading: Boolean,
    stopping: Boolean,
    elapsed: Long,
    audioLevel: Float,
    activeModelId: String?,
    backend: InferenceBackend?,
    indicatorAlpha: Float,
) {
    if (!recording && !modelLoading && !stopping) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.ExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = indicatorAlpha }
                .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = if (recording) "Recording active" else "Recording stopped" },
        )
        Text(
            compactRecordingLabel(recording, modelLoading, stopping, elapsed, activeModelId, backend),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (recording && !modelLoading) AudioLevelMeter(audioLevel)
    }
}

private fun compactRecordingLabel(
    recording: Boolean,
    modelLoading: Boolean,
    stopping: Boolean,
    elapsed: Long,
    activeModelId: String?,
    backend: InferenceBackend?,
): String = when {
    stopping -> "Finishing"
    modelLoading -> "Loading ${localModelDisplayName(activeModelId)}"
    recording -> "Recording %02d:%02d".format(elapsed / 60, elapsed % 60)
    activeModelId != null -> localModelDisplayName(activeModelId)
    backend != null -> backend.label
    else -> ""
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
        val handleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f)
        if (vertical) {
            Box(Modifier.width(4.dp).fillMaxHeight(0.24f).clip(CircleShape).background(handleColor))
        } else {
            Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(handleColor))
        }
    }
}

private fun legacyContextState(global: String, details: List<String>): StreamingContextState =
    StreamingContextState(
        current = ListeningContext(global, details),
        history = if (details.isEmpty()) emptyList() else listOf(ContextHistoryEntry(ListeningContext(global, details), 0L)),
    )

@Composable internal fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = ListenerSpacing.Small),
    )
}

@Composable private fun ContextCard(
    contextState: StreamingContextState,
    diagnostics: SummaryDiagnostics,
    remoteStatus: RemoteStatus,
    remoteMessage: String?,
    emptyMessage: String,
    orbActive: Boolean,
    showIdleSphere: Boolean,
    animateIdleSphere: Boolean,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    var followUpdates by remember { mutableStateOf(true) }
    val current = contextState.current
    val draft = contextState.draft
    val displayedContext = current.takeIf { it.hasDisplayableContext() }
        ?: draft.takeIf { it.hasDisplayableContext() }
    val heading = displayedContext?.globalContext.orEmpty()
    val status = englishContextStatus(diagnostics, contextState.isStreaming, remoteStatus, remoteMessage)
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val inlineOrbDiameter = with(LocalDensity.current) { titleStyle.fontSize.toDp() * 1.25f }
    val titleStartPadding by animateDpAsState(
        targetValue = if (orbActive) inlineOrbDiameter + ListenerSpacing.ExtraSmall else 0.dp,
        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
        label = "english context title offset",
    )
    LaunchedEffect(scroll.value, scroll.maxValue, scroll.isScrollInProgress) {
        if (scroll.value >= scroll.maxValue - 48) followUpdates = true
        else if (scroll.isScrollInProgress) followUpdates = false
    }
    LaunchedEffect(displayedContext, contextState.isStreaming) {
        if (followUpdates) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    val idleSphereVisible = displayedContext == null && showIdleSphere
    val sphereSize = 462.dp
    val sphereCutOffset = 64.dp
    Card(
        modifier = modifier.testTag("context-card"),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = idleSphereVisible,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = sphereCutOffset)
                    .zIndex(0f),
                enter = fadeIn(animationSpec = tween(ListenerMotion.DefaultDurationMillis)) +
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
                    ),
                exit = fadeOut(animationSpec = tween(ListenerMotion.DefaultDurationMillis)) +
                    scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
                    ),
            ) {
                Box(Modifier.testTag("idle-particle-sphere")) {
                    IdleParticleSphere(
                        size = sphereSize,
                        particleColor = MaterialTheme.colorScheme.primary,
                        haloColor = MaterialTheme.colorScheme.primary,
                        particleRadiusScale = 0.5f,
                        paused = !animateIdleSphere,
                        modifier = Modifier.graphicsLayer { alpha = 0.82f },
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = ListenerSpacing.Large)
                    .zIndex(1f),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            androidx.compose.animation.AnimatedVisibility(visible = orbActive, enter = fadeIn(), exit = fadeOut()) {
                                ThinkingOrb(
                                    state = ThinkingOrbState.Solving,
                                    size = ThinkingOrbSize.Inline,
                                    diameter = inlineOrbDiameter,
                                    primaryColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    secondaryColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    contentDescription = "English context updating",
                                )
                            }
                            Text(
                                "English context",
                                style = titleStyle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = titleStartPadding),
                            )
                        }
                    }
                }
                if (contextState.isStreaming) StreamingIndicator(Modifier.padding(top = ListenerSpacing.Small))
                if (displayedContext == null) {
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().weight(1f),
                    ) {
                        val textWidthFraction = if (maxWidth < 340.dp) 0.52f else 0.48f
                        Column(
                            Modifier
                                .fillMaxWidth(textWidthFraction)
                                .align(Alignment.CenterStart),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "Follow the\nconversation",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                emptyMessage.ifBlank { "Appears after start." },
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = ListenerSpacing.ExtraSmall)
                                    .testTag("english-context-empty"),
                            )
                        }
                    }
                } else {
                    Column(Modifier.padding(top = ListenerSpacing.Medium).weight(1f).verticalScroll(scroll)) {
                        if (heading.isNotBlank()) AnimatedSequentialText(
                            heading,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("context-heading"),
                        )
                        displayedContext.details.takeIf { it.isNotEmpty() }?.let { details ->
                            Column(Modifier.padding(top = ListenerSpacing.Small), verticalArrangement = Arrangement.spacedBy(ListenerSpacing.ExtraSmall)) {
                                details.forEach { detail ->
                                    AnimatedSequentialText(
                                        "• $detail",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                                        modifier = Modifier.testTag("context-current-detail"),
                                    )
                                }
                            }
                        }
                    }
                }
                status?.let {
                    val isError = remoteStatus != RemoteStatus.Ready
                    Surface(
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(top = ListenerSpacing.Small).fillMaxWidth().testTag("english-context-status"),
                    ) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = ListenerSpacing.Medium, vertical = ListenerSpacing.Small))
                    }
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
        "Remote summaries disabled" -> "English context is off in Settings"
        else -> diagnostics.phase.takeIf { it.isNotBlank() }
    } ?: if (remoteStatus == RemoteStatus.Ready) null else "English summary remote status: $remoteStatus"
}

private fun SummaryDiagnostics.isEnglishContextInFlight(): Boolean =
    phase in setOf(
        "Summary in flight",
        "Transcript ready",
        "OpenRouter request started",
        "Groq request started",
        "Streaming first draft",
        "English summary model unavailable; retrying free router",
    )

private data class AnimatedTextToken(
    val value: String,
    val previousValue: String?,
    val stable: Boolean,
    val index: Int,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AnimatedSequentialText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    contentDescription: String = text,
) {
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    var previousTokens by remember { mutableStateOf(tokenizeAnimatedText(text)) }
    val currentTokens = remember(text) { tokenizeAnimatedText(text) }
    val tokens = remember(previousTokens, currentTokens) { diffAnimatedTokens(previousTokens, currentTokens) }

    LaunchedEffect(text) {
        previousTokens = currentTokens
    }

    if (reduceMotion) {
        Text(
            text,
            color = color,
            style = style,
            modifier = modifier,
        )
        return
    }

    FlowRow(
        modifier = Modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
        }.then(modifier),
    ) {
        tokens.forEach { token ->
            key(token.index) {
                AnimatedTokenText(
                    token = token,
                    color = color,
                    style = style,
                )
            }
        }
    }
}

@Composable private fun AnimatedTokenText(
    token: AnimatedTextToken,
    color: Color,
    style: TextStyle,
) {
    val duration = 125
    val riseDistance = with(LocalDensity.current) { 3.dp.toPx() }
    var entered by remember(token.value, token.index) { mutableStateOf(token.stable) }
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = duration, easing = ListenerMotion.EmphasisEasing),
        label = "sequential text alpha",
    )
    val offset by animateFloatAsState(
        targetValue = if (entered) 0f else riseDistance,
        animationSpec = tween(durationMillis = duration, easing = ListenerMotion.EmphasisEasing),
        label = "sequential text rise",
    )

    LaunchedEffect(token.value, token.index) {
        entered = true
    }

    if (token.previousValue != null && token.previousValue != token.value) {
        Crossfade(
            targetState = token.value,
            animationSpec = tween(durationMillis = duration),
            label = "sequential text crossfade",
        ) { value ->
            Text(value, color = color, style = style)
        }
    } else {
        Text(
            token.value,
            color = color,
            style = style,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                translationY = offset
            },
        )
    }
}

private fun diffAnimatedTokens(previous: List<String>, current: List<String>): List<AnimatedTextToken> {
    if (current.isEmpty()) return emptyList()
    if (previous.isEmpty()) {
        return current.mapIndexed { index, value ->
            AnimatedTextToken(value = value, previousValue = null, stable = false, index = index)
        }
    }
    if (current.size >= previous.size && current.subList(0, previous.size) == previous) {
        return current.mapIndexed { index, value ->
            AnimatedTextToken(value = value, previousValue = null, stable = index < previous.size, index = index)
        }
    }
    if (previous.size * current.size > MAX_ANIMATED_TEXT_DIFF_CELLS) {
        val matchedPreviousIndexes = commonEdgeMatchedPreviousIndexes(previous, current)
        return current.mapIndexed { index, value ->
            val stable = matchedPreviousIndexes[index] != -1
            val previousValue = previous.getOrNull(index)?.takeIf { !stable && it != value }
            AnimatedTextToken(value = value, previousValue = previousValue, stable = stable, index = index)
        }
    }
    val matchedPreviousIndexes = lcsMatchedPreviousIndexes(previous, current)
    return current.mapIndexed { index, value ->
        val stable = matchedPreviousIndexes[index] != -1
        val previousValue = previous.getOrNull(index)?.takeIf { !stable && it != value }
        AnimatedTextToken(value = value, previousValue = previousValue, stable = stable, index = index)
    }
}

private fun commonEdgeMatchedPreviousIndexes(previous: List<String>, current: List<String>): IntArray {
    val matched = IntArray(current.size) { -1 }
    var prefix = 0
    while (prefix < previous.size && prefix < current.size && previous[prefix] == current[prefix]) {
        matched[prefix] = prefix
        prefix++
    }
    var previousSuffix = previous.lastIndex
    var currentSuffix = current.lastIndex
    while (previousSuffix >= prefix && currentSuffix >= prefix && previous[previousSuffix] == current[currentSuffix]) {
        matched[currentSuffix] = previousSuffix
        previousSuffix--
        currentSuffix--
    }
    return matched
}

private fun lcsMatchedPreviousIndexes(previous: List<String>, current: List<String>): IntArray {
    val lengths = Array(previous.size + 1) { IntArray(current.size + 1) }
    for (previousIndex in previous.indices.reversed()) {
        for (currentIndex in current.indices.reversed()) {
            lengths[previousIndex][currentIndex] = if (previous[previousIndex] == current[currentIndex]) {
                lengths[previousIndex + 1][currentIndex + 1] + 1
            } else {
                maxOf(lengths[previousIndex + 1][currentIndex], lengths[previousIndex][currentIndex + 1])
            }
        }
    }
    val matched = IntArray(current.size) { -1 }
    var previousIndex = 0
    var currentIndex = 0
    while (previousIndex < previous.size && currentIndex < current.size) {
        when {
            previous[previousIndex] == current[currentIndex] -> {
                matched[currentIndex] = previousIndex
                previousIndex++
                currentIndex++
            }
            lengths[previousIndex + 1][currentIndex] >= lengths[previousIndex][currentIndex + 1] -> previousIndex++
            else -> currentIndex++
        }
    }
    return matched
}

private fun tokenizeAnimatedText(text: String): List<String> {
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char.isWhitespace() -> {
                if (tokens.isEmpty()) tokens += char.toString() else tokens[tokens.lastIndex] += char
                index++
            }
            char.isCjkToken() -> {
                tokens += char.toString()
                index++
            }
            else -> {
                val start = index
                while (index < text.length && !text[index].isWhitespace() && !text[index].isCjkToken()) {
                    index++
                }
                tokens += text.substring(start, index)
            }
        }
    }
    return tokens
}

private fun Char.isCjkToken(): Boolean = this in '\u4E00'..'\u9FFF' ||
    this in '\u3400'..'\u4DBF' ||
    this in '\uF900'..'\uFAFF'

private const val MAX_ANIMATED_TEXT_DIFF_CELLS = 40_000

private fun ListeningContext?.hasDisplayableContext(): Boolean =
    this != null && (globalContext.isNotBlank() || details.any(String::isNotBlank))

@Composable private fun StreamingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "Loading English context" },
        horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
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
    var followUpdates by remember { mutableStateOf(true) }
    LaunchedEffect(scroll.value, scroll.maxValue, scroll.isScrollInProgress) {
        if (scroll.value >= scroll.maxValue - 48) followUpdates = true
        else if (scroll.isScrollInProgress) followUpdates = false
    }
    LaunchedEffect(stableText, provisionalText) {
        if (followUpdates) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Card(
        modifier = modifier.testTag("transcript"),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = ListenerSpacing.Large)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_transcript),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        "Traditional Chinese",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                Modifier.padding(top = ListenerSpacing.Small).weight(1f).verticalScroll(scroll),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                if (stableText.isNotBlank() || provisionalText.isNotBlank()) {
                    AnimatedSequentialText(stableText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    AnimatedVisibility(visible = provisionalText.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        AnimatedSequentialText(
                            provisionalText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                            contentDescription = "Provisional transcript: $provisionalText",
                        )
                    }
                } else {
                    Text(
                        "Traditional Chinese will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = ListenerSpacing.ExtraSmall).testTag("transcript-empty"),
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
    toggle: () -> Unit,
) {
    val actionCorner by animateDpAsState(
        targetValue = if (recording) 18.dp else 30.dp,
        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
        label = "record action shape",
    )
    val actionColor by animateColorAsState(
        targetValue = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(ListenerMotion.DefaultDurationMillis, easing = ListenerMotion.EmphasisEasing),
        label = "record action color",
    )
    val buttonEnabled = !stopping
    Column(Modifier.fillMaxWidth().padding(top = ListenerSpacing.ExtraSmall).testTag("listening-controls")) {
        Button(
            enabled = buttonEnabled,
            onClick = toggle,
            modifier = Modifier.fillMaxWidth().padding(top = ListenerSpacing.ExtraSmall).heightIn(min = 58.dp).testTag("record-toggle"),
            shape = RoundedCornerShape(actionCorner),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recordingAvailable || recording || modelLoading) actionColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (recordingAvailable || recording || modelLoading) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            val action = when {
                stopping -> "Finishing..."
                modelLoading -> "Cancel"
                recording -> "Stop listening"
                recordingAvailable -> "Start listening"
                else -> "Install model"
            }
            AnimatedContent(targetState = action, label = "record action") { label ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ListenerSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (modelLoading || stopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).testTag("model-loading-indicator"),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable internal fun CadenceSlider(intervalMillis: Int, minimumIntervalMillis: Int, change: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Summary interval", style = MaterialTheme.typography.titleMedium)
            LanguageChip("Adaptive 5-10s")
        }
    }
}

private fun localModelDisplayName(id: String?): String = when (id) {
    null, "" -> "model"
    ModelManager.STREAMING_PARAFORMER_BILINGUAL_ID -> "Sherpa Paraformer"
    ModelManager.SENSE_VOICE_ID -> "Sherpa SenseVoice"
    "android" -> "Android on-device"
    else -> id.replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase)
}

@Composable private fun LanguageChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = ListenerSpacing.Medium, vertical = ListenerSpacing.Small),
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
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .blur(if (editing != null || deleting != null) 10.dp else 0.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle("Sessions")
            SummaryDiagnosticsPanel(summaryDiagnostics, summaryStreaming)
            saveMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (sessions.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_sessions),
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text("Your saved listening sessions will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                sessions.forEach { session ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
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
            Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit session", tint = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onExport, modifier = Modifier.testTag("session-action-export")) {
            Icon(painterResource(R.drawable.ic_send), contentDescription = "Export transcript", tint = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onShareTrace, modifier = Modifier.testTag("session-action-share-trace")) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = "Share trace", tint = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag("session-action-delete")) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete session", tint = MaterialTheme.colorScheme.error)
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
            backend = InferenceBackend.CPU_FALLBACK,
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
            backend = InferenceBackend.CPU_FALLBACK,
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
