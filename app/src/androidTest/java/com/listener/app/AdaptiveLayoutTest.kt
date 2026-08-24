package com.listener.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.MaterialTheme
import com.listener.app.context.ListeningContext
import com.listener.app.speech.InferenceBackend
import com.listener.app.ui.ListeningScreen
import com.listener.app.ui.ModelManagementScreen
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<TestComposeActivity>()

    private fun setTestContent(content: @Composable () -> Unit) {
        compose.setContent(content)
    }

    @Test fun foldedLayoutKeepsTranscriptAndControlVisible() {
        setTestContent { ListeningScreen(false, 0, 5, {}, {}) }
        compose.onNodeWithTag("transcript").assertExists(); compose.onNodeWithTag("record-toggle").assertIsDisplayed()
    }
    @Test fun activeRecordingIsAnnounced() {
        setTestContent { ListeningScreen(true, 65, 10, {}, {}) }
        compose.onNodeWithContentDescription("Recording active").assertExists()
    }
    @Test fun recordingRequiresInstalledModel() {
        setTestContent { ListeningScreen(false, 0, 10, {}, {}, recordingAvailable = false) }
        compose.onNodeWithTag("record-toggle").assertIsNotEnabled()
        compose.onNodeWithText("Model required").assertExists()
    }

    @Test fun provisionalTextAndBackendHaveAccessibleState() {
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalSeconds = 10,
                onIntervalChange = {},
                onToggle = {},
                stableTranscript = "穩定",
                provisionalTranscript = "暫定",
                backend = InferenceBackend.VULKAN,
            )
        }
        compose.onNodeWithContentDescription("Provisional transcript: 暫定").assertExists()
        compose.onNodeWithContentDescription("Inference backend Vulkan").assertExists()
    }

    @Test fun contextHeadingStaysPinnedWhileHistoryExists() {
        val history = (1..12).map {
            ContextHistoryEntry(ListeningContext("Earlier topic $it", listOf("Earlier detail $it")), it.toLong())
        }
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalSeconds = 10,
                onIntervalChange = {},
                onToggle = {},
                contextState = StreamingContextState(
                    current = ListeningContext("Current topic", listOf("Current detail")),
                    history = history,
                ),
            )
        }
        compose.onNodeWithTag("context-heading").assertIsDisplayed()
        compose.onNodeWithText("Current topic").assertExists()
        compose.onNodeWithText("Earlier detail 1").assertExists()
    }

    @Test fun contextHistoryRetainsPriorDetailsAndShowsStreamingDraft() {
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalSeconds = 10,
                onIntervalChange = {},
                onToggle = {},
                contextState = StreamingContextState(
                    current = ListeningContext("Newest committed topic", listOf("Newest committed detail")),
                    history = listOf(
                        ContextHistoryEntry(ListeningContext("First topic", listOf("First retained detail")), 1L),
                        ContextHistoryEntry(ListeningContext("Newest committed topic", listOf("Newest committed detail")), 2L),
                    ),
                    draft = ListeningContext("Draft topic", listOf("Draft detail")),
                    isStreaming = true,
                ),
            )
        }
        compose.onNodeWithText("First retained detail").assertExists()
        compose.onNodeWithText("Newest committed detail").assertExists()
        compose.onNodeWithContentDescription("Streaming English context update").assertExists()
        compose.onNodeWithText("Draft detail").assertExists()
    }

    @Test fun modelCardsAreSingleChoiceAndLockedDuringRecording() {
        setTestContent {
            MaterialTheme {
                ModelManagementScreen(
                    activeId = "base",
                    selectedId = "base",
                    selectedEngine = com.listener.app.data.TranscriptionEngine.WHISPER_CPP,
                    workProfile = com.listener.app.data.WhisperWorkProfile.RESPONSIVE,
                    backend = InferenceBackend.VULKAN,
                    recording = true,
                    installedIds = setOf("base"),
                    progress = null,
                    downloadModelId = null,
                    error = null,
                    onDownload = {},
                    onCancel = {},
                    onDelete = {},
                    onSelect = {},
                    onSelectEngine = {},
                    onWorkProfile = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Base model, installed, selected, active").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Inference · Vulkan").assertExists()
    }
}
