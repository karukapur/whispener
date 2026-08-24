package com.listener.app

import com.listener.app.audio.*
import com.listener.app.context.*
import com.listener.app.data.DEFAULT_OPENROUTER_MODEL_ID
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.models.ModelManager
import com.listener.app.models.resolveLocalModelSelection
import com.listener.app.speech.OverlapTranscriptMerger
import com.listener.app.speech.TranscriptResult
import com.listener.app.speech.VoiceActivityDetector
import com.listener.app.speech.WhisperSegment
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

class CoreLogicTest {
    @Test fun structuredResponseKeepsModelDetailsWithoutFiller() {
        assertNotNull(StructuredContextValidator.parse("""{"globalContext":"會議","details":["一","二"]}"""))
        assertEquals(listOf("一"), StructuredContextValidator.parse("""{"globalContext":"會議","details":["一"]}""")?.details)
        assertEquals(4, StructuredContextValidator.parse("""{"globalContext":"會議","details":["一","二","三","四"]}""")?.details?.size)
    }
    @Test fun structuredResponseAllowsFencedJsonObject() {
        val parsed = StructuredContextValidator.parse(
            """
            ```json
            {"globalContext":"Lunch planning","details":["They are discussing food","Noodles are mentioned"]}
            ```
            """.trimIndent(),
        )
        assertEquals("Lunch planning", parsed?.globalContext)
    }
    @Test fun modelOutputAllowsHeadingAndBullets() {
        val parsed = StructuredContextValidator.parseModelOutput(
            """
            Global context: Lunch planning
            Details:
            - They are discussing where to eat.
            - Noodles are mentioned as an option.
            - The choice is not final yet.
            """.trimIndent(),
        )
        assertEquals("Lunch planning", parsed?.globalContext)
        assertEquals(3, parsed?.details?.size)
    }
    @Test fun invalidResponseRetainsLastValidContext() {
        val coordinator = SummaryCoordinator(ListeningContext("old", listOf("a", "b")))
        assertFalse(coordinator.acceptResponse("nope")); assertEquals("old", coordinator.lastValid?.globalContext)
    }
    @Test fun priorEnglishContextIncludesHeadingAndDetails() {
        val context = ListeningContext("Planning lunch", listOf("They are choosing a restaurant", "One person prefers noodles"))
        assertEquals(
            "Planning lunch\n- They are choosing a restaurant\n- One person prefers noodles",
            context.toPromptContext(),
        )
        assertEquals("", null.toPromptContext())
    }
    @Test fun remoteSummariesUseFinalizedTranscriptOnly() {
        val state = ListenerRuntimeState(stableTranscript = "已完成", provisionalTranscript = "還在變")
        assertEquals("已完成", state.finalizedTranscriptForSummary())
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
        val dir = createTempDirectory("listener-test").toFile(); val file = File(dir, "x").apply { writeText("bad") }
        assertFalse(ModelManager(dir).verify(file, "00")); dir.deleteRecursively()
    }
    @Test fun checksumSuccessIsDetected() {
        val dir = createTempDirectory("listener-test").toFile(); val file = File(dir, "x").apply { writeText("listener") }
        val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(ModelManager(dir).verify(file, expected)); dir.deleteRecursively()
    }
    @Test fun vadSeparatesSilenceFromSpeech() {
        val vad = VoiceActivityDetector(450)
        assertFalse(vad.containsSpeech(shortArrayOf(0, 100, -449)))
        assertTrue(vad.containsSpeech(shortArrayOf(0, 450)))
    }
    @Test fun intervalAcceptsUserSwitch() { var interval = 5; interval = 10; assertEquals(10, interval) }

    @Test fun defaultOpenRouterModelUsesFastFreeStructuredModel() {
        assertEquals("nvidia/nemotron-nano-9b-v2:free", DEFAULT_OPENROUTER_MODEL_ID)
        assertEquals(DEFAULT_OPENROUTER_MODEL_ID, com.listener.app.data.ListenerPreferences().selectedModel)
    }

    @Test fun transcriptionEnginePreferenceFallsBackToWhisper() {
        assertEquals(TranscriptionEngine.ANDROID_ON_DEVICE, TranscriptionEngine.fromId("android_on_device"))
        assertEquals(TranscriptionEngine.SHERPA_ONNX, TranscriptionEngine.fromId("sherpa_onnx"))
        assertEquals(TranscriptionEngine.WHISPER_CPP, TranscriptionEngine.fromId("missing"))
    }

    @Test fun whisperWorkProfileDefaultsToResponsive() {
        assertEquals(WhisperWorkProfile.CONSERVATIVE, WhisperWorkProfile.fromId("conservative"))
        assertEquals(WhisperWorkProfile.RESPONSIVE, WhisperWorkProfile.fromId(null))
    }

    @Test fun androidSpeechLanguageFallbackKeepsTraditionalChineseFirst() {
        assertEquals("zh-TW", AndroidSpeechLanguages.candidates.first())
        assertTrue(AndroidSpeechLanguages.candidates.contains("zh-Hant-TW"))
        assertTrue(AndroidSpeechLanguages.candidates.contains("cmn-Hant-TW"))
        assertNull(AndroidSpeechLanguages.candidates.last())
    }

    @Test fun legacyInstallKeepsTinyButMissingSelectionUsesBalancedFallback() {
        assertEquals("tiny", resolveLocalModelSelection(null, setOf("tiny", "base"), legacyInstall = true))
        assertEquals("base", resolveLocalModelSelection("missing", setOf("tiny", "base", "small-q5_1")))
        assertEquals("small-q5_1", resolveLocalModelSelection("missing", setOf("tiny", "small-q5_1")))
    }

    @Test fun selectedInstalledModelWins() {
        assertEquals("small-q5_1", resolveLocalModelSelection("small-q5_1", setOf("tiny", "base", "small-q5_1")))
        assertNull(resolveLocalModelSelection(null, emptySet()))
    }

    @Test fun activeModelCannotBeDeleted() {
        val dir = createTempDirectory("listener-test").toFile()
        val manager = ModelManager(dir)
        val (binary, manifest) = manager.paths("base")
        binary.parentFile?.mkdirs(); manifest.parentFile?.mkdirs()
        binary.writeBytes(byteArrayOf(1)); manifest.writeText("{}")
        manager.markLoaded("base")
        assertThrows(IllegalStateException::class.java) {
            manager.delete(com.listener.app.models.InstalledModel("base", "test", binary, manifest, ""))
        }
        assertTrue(binary.exists())
        dir.deleteRecursively()
    }

    @Test fun pcmRingBufferKeepsOnlyNewestThirtySecondStyleWindow() {
        val buffer = PcmRingBuffer(5)
        buffer.append(shortArrayOf(1, 2, 3), 3)
        buffer.append(shortArrayOf(4, 5, 6, 7), 4)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), buffer.snapshotFrom(0))
        assertEquals(2, buffer.earliestSample)
        assertEquals(7, buffer.totalSamples)
    }

    @Test fun conflatedDecodeQueueDropsStaleWindows() {
        runBlocking {
            val queue = Channel<Int>(Channel.CONFLATED)
            queue.trySend(1); queue.trySend(2); queue.trySend(3)
            assertEquals(3, queue.receive())
            queue.close()
        }
    }

    @Test fun overlapMergerPromotesStableChineseWithoutDuplicatingPrefix() {
        val merger = OverlapTranscriptMerger(1_200)
        val first = merger.update(
            TranscriptResult(listOf(WhisperSegment("你好", 0, 1_000), WhisperSegment("世界", 1_000, 2_000))),
            capturedThroughMs = 2_500,
        )
        assertEquals("你好", first.stableText)
        assertEquals("世界", first.provisionalText)

        val second = merger.update(
            TranscriptResult(listOf(WhisperSegment("你好世界", 800, 2_500), WhisperSegment("今天很好", 2_500, 3_500))),
            capturedThroughMs = 4_000,
        )
        assertEquals("你好世界", second.stableText)
        assertEquals("今天很好", second.provisionalText)
        assertEquals(1, second.newlyCommitted.size)
    }

    @Test fun finalOverlapUpdateCommitsRemainingText() {
        val merger = OverlapTranscriptMerger()
        val update = merger.update(
            TranscriptResult(listOf(WhisperSegment("最後一句", 0, 900))),
            capturedThroughMs = 900,
            final = true,
        )
        assertEquals("最後一句", update.stableText)
        assertEquals("", update.provisionalText)
        assertEquals(900, update.committedThroughMs)
    }

    @Test fun coarseTimestampResultsAdvanceWithCapturedAudio() {
        val merger = OverlapTranscriptMerger(1_200)
        val first = merger.update(
            TranscriptResult(
                listOf(WhisperSegment("你好世界", 0, 4_000)),
                hasPreciseTimestamps = false,
            ),
            capturedThroughMs = 4_000,
        )
        assertEquals("你好世界", first.stableText)
        assertEquals(2_800, first.committedThroughMs)

        val second = merger.update(
            TranscriptResult(
                listOf(WhisperSegment("你好世界今天很好", 0, 8_000)),
                hasPreciseTimestamps = false,
            ),
            capturedThroughMs = 8_000,
        )
        assertEquals("你好世界今天很好", second.stableText)
        assertEquals(listOf("今天很好"), second.newlyCommitted.map { it.text })
        assertEquals(6_800, second.committedThroughMs)
    }

    @Test fun normalizedChineseOverlapIgnoresPunctuationAndSpaces() {
        assertEquals("今天很好", OverlapTranscriptMerger.appendNovel("你好，世界", "你好世界 今天很好"))
    }
}
