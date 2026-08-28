package com.listener.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.listener.app.context.OpenRouterModel
import com.listener.app.topRemoteModelOptions
import com.listener.app.data.DEFAULT_OPENROUTER_MODEL_ID
import com.listener.app.data.GROQ_GPT_OSS_20B_REMOTE_MODEL_ID
import com.listener.app.data.OPENROUTER_FREE_ROUTER_MODEL_ID
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.models.ConservativeModels
import com.listener.app.models.SherpaOnnxModels
import com.listener.app.speech.InferenceBackend

@Composable fun PrivacyOnboarding(onConsent: (remoteEnabled: Boolean, retentionDays: Int) -> Unit) {
    var remote by remember { mutableStateOf(false) }
    var retention by remember { mutableIntStateOf(30) }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Private listening, on your phone", style = MaterialTheme.typography.headlineMedium)
        Text("Listener transcribes Chinese speech locally. Audio never leaves this device and is not saved.", style = MaterialTheme.typography.bodyLarge)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Optional English context", style = MaterialTheme.typography.titleMedium)
                Text("If enabled, finalized Chinese text, recent transcript text, and the previous English context are sent to your selected OpenRouter or Groq model. Audio stays on this device.")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(remote, { remote = it }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
            Text("Enable remote English summaries", Modifier.padding(top = 12.dp))
        }
        Text("Keep saved sessions for $retention days")
        Slider(retention.toFloat(), { retention = it.toInt() }, valueRange = 0f..90f, steps = 89)
        Spacer(Modifier.weight(1f))
        Button(onClick = { onConsent(remote, retention) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Continue and download Base model") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    var key by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var retentionSelection by remember(retentionDays) { mutableFloatStateOf(retentionDays.toFloat()) }
    val currentModel = selectedModel ?: DEFAULT_OPENROUTER_MODEL_ID
    val modelOptions = remember(catalog, currentModel, groqApiKeyPresent) {
        topRemoteModelOptions(catalog, currentModel, groqApiKeyPresent)
    }
    val currentModelDetails = modelOptions.firstOrNull { it.id == currentModel }
        ?: OpenRouterModel(currentModel, currentModel)
    LaunchedEffect(apiKeyPresent) {
        if (apiKeyPresent) onRefreshCatalog()
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Remote English context", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("Enable summaries"); Text("Local transcription always remains available.", style = MaterialTheme.typography.bodySmall) }
            Switch(remoteEnabled, onRemoteEnabled)
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (apiKeyPresent) "Replace API key" else "OpenRouter API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = key.isNotBlank(), onClick = { onSaveKey(key); key = "" }) { Text("Save securely") }
            if (apiKeyPresent) TextButton(onClick = onClearKey) { Text("Remove key") }
        }
        HorizontalDivider()
        Text("Choose Groq GPT-OSS 20B, a specific low-latency OpenRouter model, or OpenRouter's automatic free router.", style = MaterialTheme.typography.bodySmall)
        if (groqApiKeyPresent) {
            Text("Groq GPT-OSS 20B is available from this debug build's local properties.", style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Summary model", style = MaterialTheme.typography.titleMedium)
                Text("Five compatible free models are ranked by OpenRouter latency.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = apiKeyPresent && !catalogLoading, onClick = onRefreshCatalog) {
                Text(if (catalogLoading) "Refreshing" else "Refresh")
            }
        }
        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = remoteModelFieldName(currentModelDetails),
                onValueChange = {},
                readOnly = true,
                label = { Text("Selected model") },
                supportingText = { Text(currentModelDetails.id) },
                leadingIcon = {
                    RemoteModelBrandMarks(
                        model = currentModelDetails,
                        openAiContentDescription = "OpenAI",
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                modelOptions.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                RemoteModelLabel(model)
                                Text(
                                    if (model.id == OPENROUTER_FREE_ROUTER_MODEL_ID) {
                                        "${model.id} · randomly selects a compatible free model"
                                    } else if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) {
                                        "Groq · fixed model · 2s minimum cadence"
                                    } else {
                                        model.id
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelectRemoteModel(model.id)
                            modelMenuExpanded = false
                        },
                        leadingIcon = {
                            RadioButton(selected = currentModel == model.id, onClick = null)
                        },
                    )
                }
            }
        }
        Text("Every interval containing new finalized text creates one request to the selected remote provider. HTTP 429 keeps the last valid context and never stops local recording.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Local privacy", style = MaterialTheme.typography.titleMedium)
        Text("Audio stays in memory and is never saved. Completed sessions are retained for ${retentionSelection.toInt()} days.")
        Slider(retentionSelection, { retentionSelection = it }, onValueChangeFinished = { onRetentionDays(retentionSelection.toInt()) }, valueRange = 0f..90f, steps = 89)
    }
}

@Composable
private fun RemoteModelLabel(model: OpenRouterModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteModelLogoSpacing),
    ) {
        RemoteModelBrandMarks(model)
        Text(remoteModelDisplayName(model), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoteModelBrandMarks(
    model: OpenRouterModel,
    openAiContentDescription: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteModelLogoSpacing),
    ) {
        if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) {
            Image(
                painter = painterResource(com.listener.app.R.drawable.ic_brand_groq_wordmark),
                contentDescription = "Groq",
                modifier = Modifier.size(width = GroqWordmarkWidth, height = RemoteModelLogoSize),
            )
            Text(
                text = "|",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                painter = painterResource(com.listener.app.R.drawable.ic_brand_openai),
                contentDescription = openAiContentDescription,
                modifier = Modifier.size(RemoteModelLogoSize),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(
                painter = painterResource(com.listener.app.R.drawable.ic_brand_openrouter),
                contentDescription = "OpenRouter",
                modifier = Modifier.size(RemoteModelLogoSize),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun remoteModelDisplayName(model: OpenRouterModel): String =
    if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "OpenAI GPT-OSS 20B" else model.name

private fun remoteModelFieldName(model: OpenRouterModel): String =
    if (model.id == GROQ_GPT_OSS_20B_REMOTE_MODEL_ID) "GPT-OSS 20B" else model.name

private val RemoteModelLogoSize = 18.dp
private val RemoteModelLogoSpacing = 6.dp
private val GroqWordmarkWidth = 48.dp

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
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("On-device speech", style = MaterialTheme.typography.headlineSmall)
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
                OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
            OutlinedCard(
                Modifier.fillMaxWidth().selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = { onSelect(model.id) })
                    .semantics { contentDescription = "${modelName(model.id)} model, ${if (installed) "installed" else "not installed"}${if (selected) ", selected" else ""}${if (active) ", active" else ""}" },
                border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
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
    OutlinedCard(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(engine) })
            .semantics { contentDescription = "$title transcription engine${if (selected) ", selected" else ""}" },
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
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
