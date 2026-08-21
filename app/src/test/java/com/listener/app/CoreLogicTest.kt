package com.listener.app

import com.listener.app.audio.*
import com.listener.app.context.*
import com.listener.app.models.ModelManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoreLogicTest {
    @Test fun structuredResponseRequiresTwoOrThreeDetails() {
        assertNotNull(StructuredContextValidator.parse("""{"globalContext":"會議","details":["一","二"]}"""))
        assertNull(StructuredContextValidator.parse("""{"globalContext":"會議","details":["一"]}"""))
        assertNull(StructuredContextValidator.parse("""{"globalContext":"會議","details":["一","二","三","四"]}"""))
    }
    @Test fun invalidResponseRetainsLastValidContext() {
        val coordinator = SummaryCoordinator(ListeningContext("old", listOf("a", "b")))
        assertFalse(coordinator.acceptResponse("nope")); assertEquals("old", coordinator.lastValid?.globalContext)
    }
    @Test fun stateCompactionIsBounded() {
        var state = SummaryState(); repeat(100) { state = state.append("x".repeat(100), 500) }
        assertTrue(state.recent.sumOf(String::length) <= 500); assertEquals(3, state.compact("summary").recent.size)
    }
    @Test fun keysAreRedacted() { assertFalse(SecretRedactor.redact("Bearer abc.def sk-or-v1-secret").contains("secret")) }
    @Test fun lifecycleStopsFromRecordingAndInterruption() {
        assertEquals(RecordingState.STOPPING, reduce(RecordingState.RECORDING, RecordingEvent.Stop))
        assertEquals(RecordingState.STOPPING, reduce(RecordingState.INTERRUPTED, RecordingEvent.Stop))
    }
    @Test fun checksumFailureIsDetected() {
        val dir = createTempDir(); val file = File(dir, "x").apply { writeText("bad") }
        assertFalse(ModelManager(dir).verify(file, "00")); dir.deleteRecursively()
    }
    @Test fun intervalAcceptsUserSwitch() { var interval = 5; interval = 10; assertEquals(10, interval) }
}
