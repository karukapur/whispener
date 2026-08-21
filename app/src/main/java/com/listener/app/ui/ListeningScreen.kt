package com.listener.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF3567A8)
private val Ink = Color(0xFF17202A)
private val Paper = Color(0xFFF8F9FA)

@Composable fun ListenerApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Paper, surface = Color.White, onSurface = Ink)) {
        var recording by remember { mutableStateOf(false) }
        var interval by remember { mutableIntStateOf(5) }
        ListeningScreen(recording, 0, interval, { interval = it }, { recording = !recording })
    }
}

/** Width and height drive a two-pane layout on unfolded/landscape windows and stacked panes otherwise. */
@Composable fun ListeningScreen(
    recording: Boolean,
    elapsedSeconds: Long,
    intervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    onToggle: () -> Unit,
    globalContext: String = "等待足夠的內容以建立整體脈絡。",
    details: List<String> = listOf("音訊只在裝置上轉錄", "摘要可隨時停用"),
    transcript: String = "即時繁體中文逐字稿會顯示在這裡。",
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp).testTag("adaptive-root")) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val panes: @Composable RowScope.() -> Unit = {
            ContextCard(globalContext, details, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            TranscriptCard(transcript, Modifier.weight(1f).fillMaxHeight())
        }
        Column {
            if (wide) Row(Modifier.weight(1f), content = panes) else {
                ContextCard(globalContext, details, Modifier.weight(0.42f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TranscriptCard(transcript, Modifier.weight(0.58f).fillMaxWidth())
            }
            Controls(recording, elapsedSeconds, intervalSeconds, onIntervalChange, onToggle)
        }
    }
}

@Composable private fun ContextCard(global: String, details: List<String>, modifier: Modifier) {
    OutlinedCard(modifier, border = BorderStroke(1.dp, Blue)) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("目前脈絡", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp)); Text(global)
            details.take(3).forEach { Text("• $it", Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable private fun TranscriptCard(text: String, modifier: Modifier) {
    OutlinedCard(modifier.testTag("transcript"), border = BorderStroke(1.dp, Blue)) {
        Column(Modifier.padding(16.dp)) {
            Text("即時逐字稿", style = MaterialTheme.typography.titleMedium)
            Text(text, Modifier.padding(top = 8.dp).weight(1f).verticalScroll(rememberScrollState()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Controls(recording: Boolean, elapsed: Long, interval: Int, change: (Int) -> Unit, toggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(if (recording) "● 正在錄音  %02d:%02d".format(elapsed / 60, elapsed % 60) else "未在錄音", color = if (recording) Color(0xFFB3261E) else Ink,
            modifier = Modifier.semantics { contentDescription = if (recording) "Recording active" else "Recording stopped" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SingleChoiceSegmentedButtonRow {
                listOf(5, 10).forEachIndexed { index, seconds ->
                    SegmentedButton(selected = interval == seconds, onClick = { change(seconds) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text("${seconds}秒") }
                }
            }
            Button(onClick = toggle, colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFFB3261E) else Blue), modifier = Modifier.testTag("record-toggle")) { Text(if (recording) "停止" else "開始") }
        }
    }
}
