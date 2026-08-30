package com.listener.app

import com.listener.app.audio.*
import com.listener.app.context.*
import com.listener.app.data.DEFAULT_OPENROUTER_MODEL_ID
import com.listener.app.data.DEFAULT_REMOTE_MODEL_ID
import com.listener.app.data.DEFAULT_SUMMARY_CADENCE_MILLIS
import com.listener.app.data.GROQ_GPT_OSS_20B_REMOTE_MODEL_ID
import com.listener.app.data.GROQ_MIN_SUMMARY_CADENCE_MILLIS
import com.listener.app.data.ListenerPreferences
import com.listener.app.data.OPENROUTER_FREE_ROUTER_MODEL_ID
import com.listener.app.data.TranscriptionEngine
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.cadenceMillisPreference
import com.listener.app.data.minimumSummaryCadenceMillis
import com.listener.app.data.snapSummaryCadenceMillis
import com.listener.app.data.toSummaryIntervalSeconds
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
    @Test fun priorEnglishSummaryCanBeSentAsJson() {
        val context = ListeningContext("Planning lunch", listOf("They are choosing a restaurant", "One person prefers noodles"))
        assertEquals(
            """{"globalContext":"Planning lunch","details":["They are choosing a restaurant","One person prefers noodles"]}""",
            context.toPromptJson(),
        )
        assertEquals("", null.toPromptJson())
    }
    @Test fun remoteSummariesUseFinalizedTranscriptOnly() {
        val state = ListenerRuntimeState(stableTranscript = "已完成", provisionalTranscript = "還在變")
        assertEquals("已完成", state.finalizedTranscriptForSummary())
    }
    @Test fun stateCompactionIsBounded() {
        var state = SummaryState(); repeat(100) { state = state.append("x".repeat(100), 500) }
        assertTrue(state.recent.sumOf(String::length) <= 500); assertEquals(3, state.compact("summary").recent.size)
    }
    @Test fun keysAreRedacted() {
        val redacted = SecretRedactor.redact("Bearer abc.def sk-or-v1-secret gsk_groq-secret")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("gsk_"))
    }
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
    @Test fun cadenceFallbackMapsLegacySecondsToMillis() {
        assertEquals(5_000, cadenceMillisPreference(storedMillis = null, legacySeconds = 5))
        assertEquals(10_000, cadenceMillisPreference(storedMillis = null, legacySeconds = 10))
        assertEquals(DEFAULT_SUMMARY_CADENCE_MILLIS, cadenceMillisPreference(storedMillis = null, legacySeconds = null))
        assertEquals(2_500, cadenceMillisPreference(storedMillis = 2_500, legacySeconds = 10))
    }

    @Test fun cadenceMillisClampsSnapsAndRoundsSessionSecondsUp() {
        assertEquals(500, 100.snapSummaryCadenceMillis())
        assertEquals(500, 749.snapSummaryCadenceMillis())
        assertEquals(1_000, 750.snapSummaryCadenceMillis())
        assertEquals(2_500, 2_510.snapSummaryCadenceMillis())
        assertEquals(10_000, 12_000.snapSummaryCadenceMillis())
        assertEquals(1, 500.toSummaryIntervalSeconds())
        assertEquals(3, 2_500.toSummaryIntervalSeconds())
        assertEquals(10, 10_000.toSummaryIntervalSeconds())
    }

    @Test fun unchangedSummaryCommitDoesNotAppendHistory() {
        val previous = ListeningContext("Planning lunch", listOf("They prefer noodles"))
        val state = StreamingContextState(
            current = previous,
            history = listOf(ContextHistoryEntry(previous, 1L)),
            draft = ListeningContext("Draft", listOf("Noise")),
            isStreaming = true,
        )
        val commit = commitSummaryResult(state, previous, 2L)
        assertFalse(commit.changed)
        assertEquals(listOf(ContextHistoryEntry(previous, 1L)), commit.state.history)
        assertEquals(previous, commit.state.current)
        assertNull(commit.state.draft)
        assertFalse(commit.state.isStreaming)
        assertEquals(2L, commit.state.lastUpdatedAtMillis)
    }

    @Test fun changedSummaryCommitAppendsHistory() {
        val previous = ListeningContext("Planning lunch", listOf("They prefer noodles"))
        val next = ListeningContext("Planning dinner", listOf("They mention a later meal"))
        val commit = commitSummaryResult(
            StreamingContextState(current = previous, history = listOf(ContextHistoryEntry(previous, 1L)), isStreaming = true),
            next,
            2L,
        )
        assertTrue(commit.changed)
        assertEquals(next, commit.state.current)
        assertEquals(2, commit.state.history.size)
        assertEquals(next, commit.state.history.last().context)
    }

    @Test fun summaryPromptInputsUseCompactPreviousSummaryTailAndDelta() {
        val stableBefore = "舊".repeat(6_000)
        val fullDelta = "新".repeat(2_500)
        val inputs = summaryPromptInputs(
            transcript = stableBefore + fullDelta,
            lastSentTranscript = stableBefore,
            currentContext = ListeningContext("Previous heading", List(6) { "Previous detail $it with extra text" }),
        )
        assertTrue(inputs.previousEnglishSummary.contains("Previous heading"))
        assertTrue(inputs.previousEnglishSummary.length <= 2_000)
        assertEquals("舊".repeat(800), inputs.chineseContinuityTail)
        assertEquals("新".repeat(2_000), inputs.newChineseDelta)
        assertEquals(fullDelta, inputs.fullDelta)
    }

    @Test fun summaryGateCoalescesOverlappingRequests() {
        val gate = SummaryRequestGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test fun summaryDebugTraceFlushesPendingLinesToSessionAndRedactsSecrets() {
        val dir = createTempDirectory("listener-summary-trace").toFile()
        val trace = SummaryDebugTrace(dir) { 1_000L }
        trace.startNewRecording(
            preferences = ListenerPreferences(remoteEnabled = true),
            apiKeyPresent = true,
            runtime = ListenerRuntimeState(recording = false),
        )
        trace.append(
            sessionId = 42,
            label = "summary_response_failed",
            fields = mapOf("message" to "Bearer abc.def sk-or-v1-secret", "reason" to "InvalidKey"),
        )

        val text = trace.readForSession(42)

        assertTrue(text.contains("trace_started_for_recording"))
        assertTrue(text.contains("summary_response_failed"))
        assertTrue(text.contains("[REDACTED]"))
        assertFalse(text.contains("sk-or-v1-secret"))
        dir.deleteRecursively()
    }

    @Test fun failedSummaryTraceIncludesSafeRemoteDiagnosticsAndNoTranscriptAdvance() {
        val diagnostics = RemoteFailureDiagnostics(
            responseChars = 48,
            streamDeltaChars = 48,
            doneSeen = true,
            parseStage = "stream_final_model_output",
            finishReason = "stop",
            responseHash = "abc123",
            safeResponseExcerpt = """{"globalContext":"Draft"}""",
        )

        val line = buildTraceLine(
            timeMillis = 1_000L,
            label = "summary_response_failed",
            fields = mapOf(
                "remoteStatus" to RemoteStatus.InvalidResponse.toString(),
                "lastSentTranscriptAdvanced" to false.yesNoForTest(),
            ) + diagnostics.toSummaryTraceFields(),
        )

        assertTrue(line.contains("summary_response_failed"))
        assertTrue(line.contains("lastSentTranscriptAdvanced=no"))
        assertTrue(line.contains("responseChars=48"))
        assertTrue(line.contains("streamDeltaChars=48"))
        assertTrue(line.contains("doneSeen=yes"))
        assertTrue(line.contains("parseStage=stream_final_model_output"))
        assertTrue(line.contains("finishReason=stop"))
        assertTrue(line.contains("sseErrorSeen=no"))
        assertTrue(line.contains("responseHash=abc123"))
        assertTrue(line.contains("""safeResponseExcerpt={"globalContext":"Draft"}"""))
    }

    @Test fun detailedSummaryTraceIncludesDebugGuideSnapshotAndRuntimeLog() {
        val text = buildDetailedSummaryTrace(
            persistedSummaryTrace = "Persisted summary\n- Useful detail",
            runtimeTrace = "summary_attempt_skipped reason=missing_openrouter_key",
            diagnostics = SummaryDiagnostics(phase = "Waiting for OpenRouter key", transcriptChars = 500, deltaChars = 0),
            sessionId = 7,
        )

        assertTrue(text.contains("Listener detailed summary trace"))
        assertTrue(text.contains("Session ID: 7"))
        assertTrue(text.contains("If the Chinese transcript is good but English is missing"))
        assertTrue(text.contains("missing_openrouter_key"))
        assertTrue(text.contains("phase=Waiting for OpenRouter key"))
        assertTrue(text.contains("Persisted summary"))
        assertTrue(text.contains("summary_attempt_skipped"))
    }

    @Test fun defaultRemoteModelUsesGroqGptOss20b() {
        assertEquals("openrouter/free", DEFAULT_OPENROUTER_MODEL_ID)
        assertEquals(GROQ_GPT_OSS_20B_REMOTE_MODEL_ID, DEFAULT_REMOTE_MODEL_ID)
        assertEquals(DEFAULT_REMOTE_MODEL_ID, com.listener.app.data.ListenerPreferences().selectedModel)
    }

    @Test fun openRouterCatalogAlwaysIncludesFreeRouterFallback() {
        assertEquals(listOf(OPENROUTER_FREE_ROUTER_MODEL_ID), emptyList<OpenRouterModel>().withOpenRouterFreeRouter().map { it.id })
        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID, "free/model"),
            listOf(OpenRouterModel("free/model", "Free model")).withOpenRouterFreeRouter().map { it.id },
        )
        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID),
            listOf(OpenRouterModel(OPENROUTER_FREE_ROUTER_MODEL_ID, "Router")).withOpenRouterFreeRouter().map { it.id },
        )
    }

    @Test fun remoteModelOptionsKeepRouterFiveFastestAndCurrentSelection() {
        val catalog = (1..7).map { OpenRouterModel("free/model-$it", "Model $it") }

        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID, "free/model-1", "free/model-2", "free/model-3", "free/model-4", "free/model-5"),
            topRemoteModelOptions(catalog, OPENROUTER_FREE_ROUTER_MODEL_ID).map { it.id },
        )
        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID, "free/model-1", "free/model-2", "free/model-3", "free/model-4", "free/model-5", "free/model-7"),
            topRemoteModelOptions(catalog, "free/model-7").map { it.id },
        )
    }

    @Test fun remoteModelOptionsPreserveStoredSelectionWhileCatalogIsUnavailable() {
        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID, "free/stored-model"),
            topRemoteModelOptions(emptyList(), "free/stored-model").map { it.id },
        )
    }

    @Test fun groqOptionAndCadenceMinimumAreProviderSpecific() {
        assertEquals(
            listOf(OPENROUTER_FREE_ROUTER_MODEL_ID, GROQ_GPT_OSS_20B_REMOTE_MODEL_ID, "free/model"),
            topRemoteModelOptions(
                catalog = listOf(OpenRouterModel("free/model", "Free model")),
                selectedModel = OPENROUTER_FREE_ROUTER_MODEL_ID,
                groqAvailable = true,
            ).map { it.id },
        )
        assertEquals(GROQ_MIN_SUMMARY_CADENCE_MILLIS, minimumSummaryCadenceMillis(GROQ_GPT_OSS_20B_REMOTE_MODEL_ID))
        assertEquals(com.listener.app.data.MIN_SUMMARY_CADENCE_MILLIS, minimumSummaryCadenceMillis("free/model"))
    }

    @Test fun modelUnavailableRetriesWithFreeRouterOnlyWhenUseful() {
        val unavailable = RemoteResult.Failure(RemoteStatus.ModelUnavailable, "No endpoints found for nvidia/nemotron-nano-9b-v2:free.")
        val invalid = RemoteResult.Failure(RemoteStatus.InvalidResponse, "Malformed JSON")

        assertTrue(shouldRetrySummaryWithFreeRouter("nvidia/nemotron-nano-9b-v2:free", unavailable))
        assertTrue(shouldRetrySummaryWithFreeRouter(GROQ_GPT_OSS_20B_REMOTE_MODEL_ID, unavailable))
        assertFalse(shouldRetrySummaryWithFreeRouter(OPENROUTER_FREE_ROUTER_MODEL_ID, unavailable))
        assertFalse(shouldRetrySummaryWithFreeRouter("free/model", invalid))
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

    private fun Boolean.yesNoForTest(): String = if (this) "yes" else "no"
}
