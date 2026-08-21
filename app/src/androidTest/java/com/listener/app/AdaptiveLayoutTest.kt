package com.listener.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.listener.app.ui.ListeningScreen
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun foldedLayoutKeepsTranscriptAndControlVisible() {
        compose.setContent { ListeningScreen(false, 0, 5, {}, {}) }
        compose.onNodeWithTag("transcript").assertExists(); compose.onNodeWithTag("record-toggle").assertIsDisplayed()
    }
    @Test fun activeRecordingIsAnnounced() {
        compose.setContent { ListeningScreen(true, 65, 10, {}, {}) }
        compose.onNodeWithContentDescription("Recording active").assertExists()
    }
}
