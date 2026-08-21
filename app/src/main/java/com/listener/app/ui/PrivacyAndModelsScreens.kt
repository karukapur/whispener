package com.listener.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.listener.app.models.ConservativeModels

@Composable fun RemoteSettings(apiKeyPresent: Boolean, selectedModel: String?, catalog: List<String>, onSaveKey: (String) -> Unit, onSelectModel: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("OpenRouter 摘要", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(key, { key = it }, label = { Text(if (apiKeyPresent) "API 金鑰（已儲存）" else "API 金鑰") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(enabled = key.isNotBlank(), onClick = { onSaveKey(key); key = "" }) { Text("安全儲存") }
        Text("從即時目錄選擇模型（免費狀態可能改變）")
        catalog.forEach { id -> FilterChip(selected = selectedModel == id, onClick = { onSelectModel(id) }, label = { Text(id) }) }
    }
}

@Composable fun PrivacyOnboarding(onConsent: (remoteEnabled: Boolean, retentionDays: Int) -> Unit) {
    var remote by remember { mutableStateOf(false) }; var retention by remember { mutableIntStateOf(30) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("隱私與資料", style = MaterialTheme.typography.headlineMedium)
        Text("音訊只在手機上處理。啟用遠端摘要後，最近的逐字稿片段、先前脈絡與新片段會傳送至你選擇的 OpenRouter 模型；音訊不會上傳。")
        Row { Switch(remote, { remote = it }); Text("允許遠端摘要（關閉仍可本機轉錄）", Modifier.padding(8.dp)) }
        Text("保留 $retention 天"); Slider(retention.toFloat(), { retention = it.toInt() }, valueRange = 0f..90f)
        Button(onClick = { onConsent(remote, retention) }) { Text("同意並繼續") }
    }
}

@Composable fun ModelManagementScreen(activeId: String?, progress: Float?, onDownload: (String) -> Unit, onCancel: () -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("離線語音模型", style = MaterialTheme.typography.headlineSmall)
        ConservativeModels.forEach { model -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${model.id.replaceFirstChar(Char::uppercase)} · 約 ${model.approximateMb} MB")
            Text("${model.speed} · 記憶體 ${model.memory} · 準確度 ${model.accuracy}")
            Row { TextButton(onClick = { onDownload(model.id) }) { Text("下載／升級／降級") }; TextButton(enabled = activeId != model.id, onClick = { onDelete(model.id) }) { Text("刪除") } }
        } }
        if (progress != null) { LinearProgressIndicator(progress = { progress }); TextButton(onClick = onCancel) { Text("取消下載") } }
    }
}
