package com.listener.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.listener.app.R
import com.listener.app.context.OpenRouterModel
import com.listener.app.topRemoteModelOptions
import com.listener.app.data.DEFAULT_REMOTE_MODEL_ID
import com.listener.app.data.GROQ_GPT_OSS_20B_REMOTE_MODEL_ID
import com.listener.app.data.OPENROUTER_FREE_ROUTER_MODEL_ID
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.minimumSummaryCadenceMillis
import com.listener.app.models.ConservativeModels
import com.listener.app.models.SherpaOnnxModels
import com.listener.app.speech.InferenceBackend

@Composable fun PrivacyOnboarding(onConsent: (remoteEnabled: Boolean, retentionDays: Int) -> Unit) {
    var retention by remember { mutableIntStateOf(30) }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenTitle("Private listening, on your phone")
        Text("Listener transcribes Chinese speech locally. Audio never leaves this device and is not saved.", style = MaterialTheme.typography.bodyLarge)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("English context", style = MaterialTheme.typography.titleMedium)
                }
                Text("Finalized Chinese text, recent transcript text, and the previous English context are sent to your selected OpenRouter or Groq model. Audio stays on this device.")
            }
        }
        Text("Keep saved sessions for $retention days")
        Slider(retention.toFloat(), { retention = it.toInt() }, valueRange = 0f..90f, steps = 89)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onConsent(true, retention) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) { Text("Continue and download Base model") }
    }
}

@Composable fun RemoteSettings(
    apiKeyPresent: Boolean,
    groqApiKeyPresent: Boolean,
    remoteEnabled: Boolean,
    retentionDays: Int,
    selectedModel: String?,
    catalog: List<OpenRouterModel>,
    catalogLoading: Boolean,
    message: String?,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onRemoteEnabled: (Boolean) -> Unit,
    onRetentionDays: (Int) -> Unit,
    onRefreshCatalog: () -> Unit,
    onSelectRemoteModel: (String) -> Unit,
    summaryCadenceMillis: Int = 5_000,
    onSummaryCadenceChange: (Int) -> Unit = {},
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var modelInfoOpen by remember { mutableStateOf(false) }
    var retentionSelection by remember(retentionDays) { mutableFloatStateOf(retentionDays.toFloat()) }
    val currentModel = selectedModel ?: DEFAULT_REMOTE_MODEL_ID
    val modelOptions = remember(catalog, currentModel, groqApiKeyPresent) {
        topRemoteModelOptions(catalog, currentModel, groqApiKeyPresent)
    }
    val currentModelDetails = modelOptions.firstOrNull { it.id == currentModel }
        ?: OpenRouterModel(currentModel, currentModel)
    LaunchedEffect(apiKeyPresent) {
        if (apiKeyPresent) onRefreshCatalog()
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .blur(if (modelInfoOpen) 10.dp else 0.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle("Settings")
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SummaryModelCard(
                apiKeyPresent = apiKeyPresent,
                remoteEnabled = remoteEnabled,
                catalogLoading = catalogLoading,
                currentModel = currentModel,
                currentModelDetails = currentModelDetails,
                modelOptions = modelOptions,
                modelMenuExpanded = modelMenuExpanded,
                onModelMenuExpandedChange = { modelMenuExpanded = it },
                onRemoteEnabled = onRemoteEnabled,
                onRefreshCatalog = onRefreshCatalog,
                onSelectRemoteModel = onSelectRemoteModel,
                onOpenInfo = { modelInfoOpen = true },
                summaryCadenceMillis = summaryCadenceMillis,
                onSummaryCadenceChange = onSummaryCadenceChange,
            )
            LocalPrivacyCard(
                retentionSelection = retentionSelection,
                onRetentionSelectionChange = { retentionSelection = it },
                onRetentionDays = onRetentionDays,
            )
        }
        if (modelInfoOpen) {
            ModelInfoDialog(
                groqApiKeyPresent = groqApiKeyPresent,
                onDismiss = { modelInfoOpen = false },
            )
        }
    }
}

@Composable
private fun SummaryModelCard(
    apiKeyPresent: Boolean,
    remoteEnabled: Boolean,
    catalogLoading: Boolean,
    currentModel: String,
    currentModelDetails: OpenRouterModel,
    modelOptions: List<OpenRouterModel>,
    modelMenuExpanded: Boolean,
    onModelMenuExpandedChange: (Boolean) -> Unit,
    onRemoteEnabled: (Boolean) -> Unit,
    onRefreshCatalog: () -> Unit,
    onSelectRemoteModel: (String) -> Unit,
    onOpenInfo: () -> Unit,
    summaryCadenceMillis: Int,
    onSummaryCadenceChange: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Summary model", style = MaterialTheme.typography.titleLarge)
                        IconButton(
                            onClick = onOpenInfo,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info_outline),
                                contentDescription = "About summary models",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Text(
                        "Compatible free models are ranked by OpenRouter latency.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("English context", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (remoteEnabled) "Automatic summaries are on." else "Automatic summaries are off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f),
                    )
                }
                Switch(
                    checked = remoteEnabled,
                    onCheckedChange = onRemoteEnabled,
                    modifier = Modifier.semantics { contentDescription = "English context summaries" },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Selected model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                TextButton(enabled = apiKeyPresent && !catalogLoading, onClick = onRefreshCatalog) {
                    Text(if (catalogLoading) "Refreshing" else "Refresh")
                }
            }
            RemoteModelSelector(
                expanded = modelMenuExpanded,
                selectedModelId = currentModel,
                selectedModel = currentModelDetails,
                modelOptions = modelOptions,
                onExpandedChange = onModelMenuExpandedChange,
                onSelect = {
                    onSelectRemoteModel(it)
                    onModelMenuExpandedChange(false)
                },
            )
            CadenceSlider(
                intervalMillis = summaryCadenceMillis,
                minimumIntervalMillis = minimumSummaryCadenceMillis(currentModel),
                change = onSummaryCadenceChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RemoteModelSelector(
    expanded: Boolean,
    selectedModelId: String,
    selectedModel: OpenRouterModel,
    modelOptions: List<OpenRouterModel>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .semantics { contentDescription = "Selected summary model ${remoteModelDisplayName(selectedModel)}" },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (expanded) 0.95f else 0.5f)),
            tonalElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteModelCompanyMark(selectedModel)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        remoteModelDisplayName(selectedModel),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RemoteModelMetaLine(selectedModel)
                }
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down),
                    contentDescription = if (expanded) "Collapse model menu" else "Expand model menu",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                .width(maxWidth)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            modelOptions.forEach { model ->
                RemoteModelMenuRow(
                    model = model,
                    selected = selectedModelId == model.id,
                    onSelect = { onSelect(model.id) },
                )
            }
        }
    }
}

@Composable
private fun RemoteModelMenuRow(
    model: OpenRouterModel,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                Modifier.fillMaxWidth().heightIn(min = RemoteModelMenuItemMinHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteModelCompanyMark(model)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        remoteModelDisplayName(model),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RemoteModelMetaLine(model)
                }
                if (selected) RemoteModelBadge("Selected")
            }
        },
        onClick = onSelect,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun LocalPrivacyCard(
    retentionSelection: Float,
    onRetentionSelectionChange: (Float) -> Unit,
    onRetentionDays: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Local privacy", style = MaterialTheme.typography.titleLarge)
            Text(
                "Audio stays in memory and is never saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Saved sessions", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        "${retentionSelection.toInt()} days",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Slider(
                retentionSelection,
                { onRetentionSelectionChange(it) },
                onValueChangeFinished = { onRetentionDays(retentionSelection.toInt()) },
                valueRange = 0f..90f,
                steps = 89,
                modifier = Modifier.semantics { contentDescription = "Retain saved sessions for ${retentionSelection.toInt()} days" },
            )
        }
    }
}

@Composable
private fun ModelInfoDialog(
    groqApiKeyPresent: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info_outline),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Summary model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Close",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ModelInfoBullet("Summaries are generated automatically when new finalized Chinese text is available.")
                ModelInfoBullet("OpenRouter models are compatible free text models ranked by OpenRouter latency.")
                ModelInfoBullet("OpenRouter free router automatically selects a compatible free model.")
                ModelInfoBullet("Remote summaries currently use an adaptive 5, 8, then 10 second cadence based on Groq experiment evidence.")
                if (groqApiKeyPresent) {
                    ModelInfoBullet("Groq GPT-OSS 20B is available from this debug build's local properties.")
                }
            }
        }
    }
}

@Composable
private fun ModelInfoBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text("-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteModelCompanyMark(model: OpenRouterModel) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(remoteModelCompanyLogo(model)),
                contentDescription = remoteModelCompany(model),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RemoteModelMetaLine(model: OpenRouterModel) {
    val badge = when (model.id) {
        GROQ_GPT_OSS_20B_REMOTE_MODEL_ID -> "Adaptive"
        OPENROUTER_FREE_ROUTER_MODEL_ID -> "Auto"
        else -> "Free"
    }
    val provider = remoteModelDeliveryProvider(model)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            provider,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RemoteModelBadge(badge)
    }
}

@Composable
private fun RemoteModelBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.74f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

private fun remoteModelDisplayName(model: OpenRouterModel): String =
    if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "GPT-OSS 20B" else model.name

private fun remoteModelDeliveryProvider(model: OpenRouterModel): String =
    if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "Groq" else "OpenRouter"

private fun remoteModelCompany(model: OpenRouterModel): String {
    val provider = if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "openai" else model.id.substringBefore("/")
    return when (provider.lowercase()) {
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "deepseek" -> "DeepSeek"
        "google" -> "Google"
        "meta-llama" -> "Meta"
        "mistralai" -> "Mistral"
        "nvidia" -> "NVIDIA"
        "qwen" -> "Qwen"
        "liquid" -> "Liquid AI"
        "z-ai", "zai", "zhipu" -> "Z.ai"
        "openrouter" -> "OpenRouter"
        else -> provider.replace("-", " ").replaceFirstChar { it.uppercase() }
    }
}

@DrawableRes
private fun remoteModelCompanyLogo(model: OpenRouterModel): Int {
    val provider = if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "openai" else model.id.substringBefore("/")
    return when (provider.lowercase()) {
        "openai" -> R.drawable.ic_brand_openai
        "nvidia" -> R.drawable.ic_brand_nvidia
        "liquid" -> R.drawable.ic_brand_liquid_ai
        "z-ai", "zai", "zhipu" -> R.drawable.ic_brand_z_ai
        "openrouter" -> R.drawable.ic_brand_openrouter
        else -> R.drawable.ic_brand_openrouter
    }
}

private val RemoteModelMenuItemMinHeight = 52.dp

@Composable fun ModelManagementScreen(
    activeId: String?,
    selectedId: String?,
    selectedEngine: TranscriptionEngine,
    workProfile: WhisperWorkProfile,
    backend: InferenceBackend?,
    recording: Boolean,
    installedIds: Set<String>,
    progress: Float?,
    downloadModelId: String?,
    error: String?,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectEngine: (TranscriptionEngine) -> Unit,
    onWorkProfile: (WhisperWorkProfile) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("On-device speech")
        Text("Choose the live transcription engine. Whisper remains available; Android on-device avoids the Whisper CPU fallback path.")
        if (recording) Text("Stop recording to change models.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        EngineCard(
            engine = TranscriptionEngine.ANDROID_ON_DEVICE,
            selected = selectedEngine == TranscriptionEngine.ANDROID_ON_DEVICE,
            enabled = !recording,
            title = "Android on-device",
            body = "Uses the phone's offline speech recognizer when available. Fastest path to test live Traditional Chinese latency.",
            status = if (activeId == "android") backend?.label ?: "Active" else "No model download required",
            onSelect = onSelectEngine,
        )
        EngineCard(
            engine = TranscriptionEngine.SHERPA_ONNX,
            selected = selectedEngine == TranscriptionEngine.SHERPA_ONNX,
            enabled = !recording,
            title = "sherpa-onnx streaming",
            body = "Uses Sherpa's streaming bilingual Paraformer for Mandarin and English local decoding.",
            status = "Download Paraformer below",
            onSelect = onSelectEngine,
        )
        if (selectedEngine == TranscriptionEngine.SHERPA_ONNX) {
            Text("Sherpa models", style = MaterialTheme.typography.titleMedium)
            Text("Streaming Paraformer bilingual zh-en is the bundled download option for Mandarin and English testing.")
            SherpaOnnxModels.forEach { model ->
                val installed = model.id in installedIds
                val active = activeId == model.id
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Streaming Paraformer zh-en · about ${model.approximateMb} MB", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            AnimatedVisibility(installed) { Text(if (active) "Active" else "Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
                        }
                        Text("${model.speed} · ${model.memory} memory · ${model.accuracy}")
                        Text("Version ${model.version.take(7)}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDownload(model.id) }) { Text(downloadButtonLabel(installed, downloadModelId, model.id)) }
                            if (installed) TextButton(enabled = activeId != model.id, onClick = { onDelete(model.id) }) { Text("Delete") }
                        }
                        if (downloadModelId == model.id) {
                            val animatedProgress by animateFloatAsState(progress ?: 0f, label = "paraformer download progress")
                            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                            Text(if (progress == null) "Preparing download..." else "Downloading ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) backend?.let { Text("Inference · ${it.label}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        EngineCard(
            engine = TranscriptionEngine.WHISPER_CPP,
            selected = selectedEngine == TranscriptionEngine.WHISPER_CPP,
            enabled = !recording,
            title = "Whisper.cpp",
            body = "Current local Whisper path. Use responsive work pattern to reduce repeated CPU inference.",
            status = backend?.takeIf { selectedEngine == TranscriptionEngine.WHISPER_CPP }?.label ?: "Requires a downloaded model",
            onSelect = onSelectEngine,
        )
        if (selectedEngine == TranscriptionEngine.WHISPER_CPP) {
            Text("Whisper work pattern", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WhisperWorkProfile.entries.forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = workProfile == profile,
                        enabled = !recording,
                        onClick = { onWorkProfile(profile) },
                        shape = SegmentedButtonDefaults.itemShape(index, WhisperWorkProfile.entries.size),
                    ) {
                        Text(if (profile == WhisperWorkProfile.RESPONSIVE) "Responsive" else "Conservative")
                    }
                }
            }
            Text(
                if (workProfile == WhisperWorkProfile.RESPONSIVE) "4-second windows with lower overlap reduce duplicated CPU work." else "Original 8-second overlapping windows preserve more context but can fall behind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Text("Whisper models", style = MaterialTheme.typography.titleMedium)
        Text("Base is the balanced default. Small Q5 improves accuracy at a higher memory and latency cost.")
        ConservativeModels.forEach { model ->
            val installed = model.id in installedIds
            val selected = selectedId == model.id
            val active = activeId == model.id
            val selectable = installed && !recording
            val borderColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "model selection")
            val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, label = "model border")
            Card(
                Modifier.fillMaxWidth().selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = { onSelect(model.id) })
                    .semantics { contentDescription = "${modelName(model.id)} model, ${if (installed) "installed" else "not installed"}${if (selected) ", selected" else ""}${if (active) ", active" else ""}" },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = BorderStroke(borderWidth, borderColor),
                elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selected, enabled = selectable, onClick = null)
                        Text("${modelName(model.id)} · about ${model.approximateMb} MB", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AnimatedVisibility(installed) { Text(if (active) "Active" else "Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
                    }
                    Text("${model.speed} · ${model.memory} memory · ${model.accuracy}")
                    Text("Version ${model.version}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(model.id) }) { Text(downloadButtonLabel(installed, downloadModelId, model.id)) }
                        if (installed) TextButton(enabled = activeId != model.id, onClick = { onDelete(model.id) }) { Text("Delete") }
                    }
                    if (downloadModelId == model.id) {
                        val animatedProgress by animateFloatAsState(progress ?: 0f, label = "download progress")
                        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                        Text(if (progress == null) "Preparing download…" else "Downloading ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    if (active) backend?.let { Text("Inference · ${it.label}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (downloadModelId != null) {
            TextButton(onClick = onCancel) { Text("Cancel download") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun EngineCard(
    engine: TranscriptionEngine,
    selected: Boolean,
    enabled: Boolean,
    title: String,
    body: String,
    status: String,
    onSelect: (TranscriptionEngine) -> Unit,
) {
    val borderColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "$title engine selection")
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, label = "$title engine border")
    Card(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(engine) })
            .semantics { contentDescription = "$title transcription engine${if (selected) ", selected" else ""}" },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(borderWidth, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = selected, enabled = enabled, onClick = null)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) Text("Selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Text(body)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun modelName(id: String): String = when (id) {
    "small-q5_1" -> "Small Q5"
    else -> id.replaceFirstChar(Char::uppercase)
}

private fun downloadButtonLabel(installed: Boolean, downloadModelId: String?, modelId: String): String = when {
    downloadModelId == modelId -> "Restart"
    downloadModelId != null -> "Download instead"
    installed -> "Reinstall"
    else -> "Download"
}
