package com.listener.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.MaterialTheme
import com.listener.app.context.ListeningContext
import com.listener.app.context.OpenRouterModel
import com.listener.app.data.GROQ_GPT_OSS_20B_REMOTE_MODEL_ID
import com.listener.app.speech.InferenceBackend
import com.listener.app.ui.ListeningScreen
import com.listener.app.ui.ModelManagementScreen
import com.listener.app.ui.RemoteSettings
import com.listener.app.ui.SessionActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<TestComposeActivity>()

    private fun setTestContent(content: @Composable () -> Unit) {
        compose.setContent(content)
    }

    @Test fun foldedLayoutKeepsTranscriptAndControlVisible() {
        setTestContent { ListeningScreen(false, 0, 5_000, {}, {}) }
        compose.onNodeWithTag("transcript").assertExists(); compose.onNodeWithTag("record-toggle").assertIsDisplayed()
        compose.onNodeWithText("English context").assertExists()
        compose.onNodeWithText("English context will appear after remote summaries are enabled.").assertDoesNotExist()
        compose.onNodeWithText("Summary trace").assertDoesNotExist()
    }
    @Test fun activeRecordingIsAnnounced() {
        setTestContent { ListeningScreen(true, 65, 10_000, {}, {}) }
        compose.onNodeWithContentDescription("Recording active").assertExists()
    }
    @Test fun recordingRequiresInstalledModel() {
        setTestContent { ListeningScreen(false, 0, 10_000, {}, {}, recordingAvailable = false) }
        compose.onNodeWithTag("record-toggle").assertIsNotEnabled()
        compose.onNodeWithText("Model required").assertExists()
    }

    @Test fun provisionalTextAndBackendHaveAccessibleState() {
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalMillis = 10_000,
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
                intervalMillis = 10_000,
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
                intervalMillis = 10_000,
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

    @Test fun cadenceSliderShowsDecimalValuesAndReportsSnappedMillis() {
        var selected = 0
        setTestContent {
            ListeningScreen(
                recording = false,
                elapsedSeconds = 0,
                intervalMillis = 2_500,
                onIntervalChange = { millis: Int -> selected = millis },
                onToggle = {},
            )
        }
        compose.onNodeWithText("Summary every 2.5s").assertExists()
        compose.onNodeWithContentDescription("Summary cadence 2.5s").assertExists()
        compose.onNodeWithTag("cadence-slider").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertTrue(selected in 500..10_000)
            assertEquals(0, selected % 500)
        }
    }

    @Test fun groqCadenceSliderCannotGoBelowTwoSeconds() {
        var selected = 0
        setTestContent {
            ListeningScreen(
                recording = false,
                elapsedSeconds = 0,
                intervalMillis = 500,
                onIntervalChange = { selected = it },
                onToggle = {},
                minimumIntervalMillis = 2_000,
            )
        }

        compose.onNodeWithText("Summary every 2s").assertExists()
        compose.onNodeWithContentDescription("Summary cadence 2s").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(selected >= 2_000) }
    }

    @Test fun remoteErrorsStayInsideEnglishContextPanel() {
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalMillis = 500,
                onIntervalChange = {},
                onToggle = {},
                stableTranscript = "你好",
                remoteStatus = com.listener.app.context.RemoteStatus.ModelUnavailable,
                remoteMessage = "No endpoints found for nvidia/nemotron-nano-9b-v2:free.",
            )
        }
        compose.onNodeWithTag("english-context-status").assertExists()
        compose.onNodeWithText("No endpoints found for nvidia/nemotron-nano-9b-v2:free.").assertExists()
        compose.onNodeWithText("Recording  00:02").assertExists()
    }

    @Test fun remoteSettingsDropdownShowsFiveFastestCompatibleModelsAndSelectsOne() {
        val selected = mutableStateOf("openrouter/free")
        val catalog = (1..7).map { OpenRouterModel("free/model-$it", "Fast model $it") }
        setTestContent {
            MaterialTheme {
                RemoteSettings(
                    apiKeyPresent = false,
                    groqApiKeyPresent = true,
                    remoteEnabled = true,
                    retentionDays = 30,
                    selectedModel = selected.value,
                    catalog = catalog,
                    catalogLoading = false,
                    message = null,
                    onSaveKey = {},
                    onClearKey = {},
                    onRemoteEnabled = {},
                    onRetentionDays = {},
                    onRefreshCatalog = {},
                    onSelectRemoteModel = { selected.value = it },
                )
            }
        }

        compose.onNodeWithText("OpenRouter free router").performClick()
        compose.onNodeWithText("Fast model 5").assertExists()
        compose.onNodeWithText("Fast model 6").assertDoesNotExist()
        compose.onNodeWithText("Groq · GPT-OSS 20B").assertExists().performClick()
        compose.runOnIdle { assertEquals(GROQ_GPT_OSS_20B_REMOTE_MODEL_ID, selected.value) }
    }

    @Test fun sessionActionsAreOneAccessibleIconRowWithoutSaveTrace() {
        setTestContent {
            MaterialTheme {
                SessionActions({}, {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Edit session").assertIsDisplayed()
        compose.onNodeWithContentDescription("Export transcript").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share trace").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete session").assertIsDisplayed()
        compose.onNodeWithText("Save trace").assertDoesNotExist()
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
