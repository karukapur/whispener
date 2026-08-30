package com.listener.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<TestComposeActivity>()

    private fun setTestContent(content: @Composable () -> Unit) {
        compose.setContent(content)
    }

    @After fun resetOrientation() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun requestOrientation(orientation: Int, expected: Int) {
        compose.activity.requestedOrientation = orientation
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.activity.resources.configuration.orientation == expected
        }
    }

    @Test fun foldedLayoutKeepsTranscriptAndControlVisible() {
        setTestContent { ListeningScreen(false, 0, 5_000, {}, {}) }
        compose.onNodeWithTag("transcript").assertExists(); compose.onNodeWithTag("record-toggle").assertIsDisplayed()
        compose.onNodeWithText("English context").assertExists()
        compose.onNodeWithText("Listen").assertExists()
        compose.onNodeWithText("Summary interval").assertDoesNotExist()
        compose.onNodeWithText("English context will appear after remote summaries are enabled.").assertDoesNotExist()
        compose.onNodeWithText("Summary trace").assertDoesNotExist()
    }

    @Test fun emptyListenStateExplainsBothPanels() {
        setTestContent { ListeningScreen(false, 0, 5_000, {}, {}) }

        compose.onNodeWithTag("listen-header-status").assertDoesNotExist()
        compose.onNodeWithTag("english-context-empty").assertIsDisplayed()
        compose.onNodeWithText("Follow the conversation").assertExists()
        compose.onNodeWithText("Your English context will appear here once you start listening.").assertExists()
        compose.onNodeWithTag("transcript-empty").assertExists()
        compose.onNodeWithText("Traditional Chinese will appear here.").assertExists()
    }

    @Test fun loadingStateKeepsCommittedContextVisibleAndOffersCancel() {
        setTestContent {
            ListeningScreen(
                recording = false,
                elapsedSeconds = 0,
                intervalMillis = 2_000,
                onIntervalChange = {},
                onToggle = {},
                contextState = StreamingContextState(
                    current = ListeningContext("Committed topic", listOf("Committed detail")),
                    history = listOf(ContextHistoryEntry(ListeningContext("Committed topic", listOf("Committed detail")), 1L)),
                    draft = ListeningContext("Draft topic", listOf("Draft detail")),
                    isStreaming = true,
                ),
                modelLoading = true,
                activeModelId = "paraformer",
            )
        }

        compose.onNodeWithTag("listen-header-status").assertExists()
        compose.onNodeWithTag("context-current-detail").assertExists()
        compose.onNodeWithContentDescription("Loading English context").assertExists()
        compose.onNodeWithTag("model-loading-indicator", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Cancel").assertExists()
    }

    @Test fun localErrorsDoNotReserveListenHeaderSpaceWhenIdle() {
        setTestContent {
            ListeningScreen(
                recording = false,
                elapsedSeconds = 0,
                intervalMillis = 5_000,
                onIntervalChange = {},
                onToggle = {},
                statusMessage = "Microphone permission is required to transcribe speech.",
                statusIsError = true,
            )
        }

        compose.onNodeWithTag("listen-header-status").assertDoesNotExist()
        compose.onNodeWithTag("local-status").assertDoesNotExist()
        compose.onNodeWithTag("english-context-status").assertDoesNotExist()
    }

    @Test fun listenPanelsRemainSideBySideInWideLandscape() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        setTestContent {
            ListeningScreen(false, 0, 5_000, {}, {})
        }

        val context = compose.onNodeWithTag("context-card").getUnclippedBoundsInRoot()
        val transcript = compose.onNodeWithTag("transcript").getUnclippedBoundsInRoot()
        assertTrue(context.left < transcript.left)
        assertTrue(context.top < transcript.bottom && transcript.top < context.bottom)
    }

    @Test fun listenPanelsRemainStackedInNarrowPortrait() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        setTestContent {
            Box(Modifier.size(width = 360.dp, height = 720.dp)) {
                ListeningScreen(false, 0, 5_000, {}, {})
            }
        }

        val context = compose.onNodeWithTag("context-card").getUnclippedBoundsInRoot()
        val transcript = compose.onNodeWithTag("transcript").getUnclippedBoundsInRoot()
        assertTrue(context.bottom <= transcript.top)
    }
    @Test fun activeRecordingIsAnnounced() {
        setTestContent { ListeningScreen(true, 65, 10_000, {}, {}) }
        compose.onNodeWithContentDescription("Recording active").assertExists()
    }
    @Test fun recordingRequiresInstalledModel() {
        setTestContent { ListeningScreen(false, 0, 10_000, {}, {}, recordingAvailable = false) }
        compose.onNodeWithTag("record-toggle").assertIsEnabled()
        compose.onNodeWithText("Install model").assertExists()
    }

    @Test fun modelInstallProgressDoesNotAppearOnListenScreen() {
        setTestContent {
            ListeningScreen(
                recording = false,
                elapsedSeconds = 0,
                intervalMillis = 10_000,
                onIntervalChange = {},
                onToggle = {},
                statusMessage = "Install Paraformer before recording.",
                statusIsError = true,
                downloadModelId = "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
                downloadProgress = 0.42f,
            )
        }

        compose.onNodeWithTag("listen-header-status").assertDoesNotExist()
        compose.onNodeWithTag("local-status").assertDoesNotExist()
        compose.onNodeWithText("42% Sherpa Paraformer").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
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
        compose.onNodeWithTag("context-current-detail").assertExists()
        compose.onNodeWithTag("context-heading").assertIsDisplayed()
    }

    @Test fun contextCardShowsOnlyCurrentContextWhileStreaming() {
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
        compose.onNodeWithTag("context-current-detail").assertExists()
        compose.onNodeWithContentDescription("Loading English context").assertExists()
        compose.onNodeWithTag("context-draft-detail").assertDoesNotExist()
        compose.onNodeWithTag("context-history-item").assertDoesNotExist()
    }

    @Test fun currentEnglishContextIsNotRepeatedBelowStatus() {
        val current = ListeningContext("Newest committed topic", listOf("Newest committed detail"))
        setTestContent {
            ListeningScreen(
                recording = true,
                elapsedSeconds = 2,
                intervalMillis = 10_000,
                onIntervalChange = {},
                onToggle = {},
                contextState = StreamingContextState(
                    current = current,
                    history = listOf(
                        ContextHistoryEntry(ListeningContext("Earlier topic", listOf("Earlier detail")), 1L),
                        ContextHistoryEntry(current, 2L),
                    ),
                ),
            )
        }

        compose.onNodeWithTag("context-heading").assertIsDisplayed()
        compose.onNodeWithTag("context-current-detail").assertExists()
        compose.onNodeWithTag("context-history-item").assertDoesNotExist()
    }

    @Test fun settingsCadenceSliderShowsDecimalValuesAndReportsSnappedMillis() {
        var selected = 2_000
        setTestContent {
            MaterialTheme {
                RemoteSettings(
                    apiKeyPresent = false,
                    groqApiKeyPresent = false,
                    remoteEnabled = true,
                    retentionDays = 30,
                    selectedModel = "openrouter/free",
                    catalog = emptyList(),
                    catalogLoading = false,
                    message = null,
                    onSaveKey = {},
                    onClearKey = {},
                    onRemoteEnabled = {},
                    onRetentionDays = {},
                    onRefreshCatalog = {},
                    onSelectRemoteModel = {},
                    summaryCadenceMillis = 2_500,
                    onSummaryCadenceChange = { millis: Int -> selected = millis },
                )
            }
        }
        compose.onNodeWithText("Summary interval").assertExists()
        compose.onNodeWithText("Every 2.5s").assertExists()
        compose.onNodeWithContentDescription("Summary cadence 2.5s").assertExists()
        compose.onNodeWithTag("cadence-slider").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertTrue(selected in 500..10_000)
            assertEquals(0, selected % 500)
        }
    }

    @Test fun remoteSettingsShowsEnglishContextSwitch() {
        var enabled = false
        setTestContent {
            MaterialTheme {
                RemoteSettings(
                    apiKeyPresent = false,
                    groqApiKeyPresent = false,
                    remoteEnabled = enabled,
                    retentionDays = 30,
                    selectedModel = "openrouter/free",
                    catalog = emptyList(),
                    catalogLoading = false,
                    message = null,
                    onSaveKey = {},
                    onClearKey = {},
                    onRemoteEnabled = { enabled = it },
                    onRetentionDays = {},
                    onRefreshCatalog = {},
                    onSelectRemoteModel = {},
                )
            }
        }

        compose.onNodeWithText("English context").assertExists()
        compose.onNodeWithText("Automatic summaries are off.").assertExists()
        compose.onNodeWithContentDescription("English context summaries").assertIsOff().performClick()
        compose.runOnIdle { assertTrue(enabled) }
    }

    @Test fun summaryModelInfoDialogCloseButtonDismissesPopup() {
        setTestContent {
            MaterialTheme {
                RemoteSettings(
                    apiKeyPresent = false,
                    groqApiKeyPresent = false,
                    remoteEnabled = true,
                    retentionDays = 30,
                    selectedModel = "openrouter/free",
                    catalog = emptyList(),
                    catalogLoading = false,
                    message = null,
                    onSaveKey = {},
                    onClearKey = {},
                    onRemoteEnabled = {},
                    onRetentionDays = {},
                    onRefreshCatalog = {},
                    onSelectRemoteModel = {},
                )
            }
        }

        compose.onNodeWithContentDescription("About summary models").performClick()
        compose.onAllNodesWithText("Summary model").assertCountEquals(2)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Summary model").assertCountEquals(1)
    }

    @Test fun groqCadenceSliderCannotGoBelowTwoSeconds() {
        var selected = 0
        setTestContent {
            MaterialTheme {
                RemoteSettings(
                    apiKeyPresent = false,
                    groqApiKeyPresent = true,
                    remoteEnabled = true,
                    retentionDays = 30,
                    selectedModel = GROQ_GPT_OSS_20B_REMOTE_MODEL_ID,
                    catalog = emptyList(),
                    catalogLoading = false,
                    message = null,
                    onSaveKey = {},
                    onClearKey = {},
                    onRemoteEnabled = {},
                    onRetentionDays = {},
                    onRefreshCatalog = {},
                    onSelectRemoteModel = {},
                    summaryCadenceMillis = 500,
                    onSummaryCadenceChange = { selected = it },
                )
            }
        }

        compose.onNodeWithText("Summary interval").assertExists()
        compose.onNodeWithText("Every 2s").assertExists()
        compose.onNodeWithText("Groq uses this cadence with a 2s minimum.").assertExists()
        compose.onNodeWithContentDescription("Summary cadence 2s").performTouchInput { swipeLeft() }
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
