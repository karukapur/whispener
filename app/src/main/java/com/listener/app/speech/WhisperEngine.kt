package com.listener.app.speech

enum class InferenceBackend(val label: String) {
    VULKAN("Vulkan"),
    CPU_FALLBACK("CPU fallback"),
    ANDROID_ON_DEVICE("Android on-device"),
    SHERPA_ONNX("sherpa-onnx"),
}

data class WhisperSegment(val text: String, val startMs: Long, val endMs: Long)

data class TranscriptResult(
    val segments: List<WhisperSegment>,
    val isFinal: Boolean = false,
    val hasPreciseTimestamps: Boolean = true,
) {
    val text: String get() = segments.joinToString("") { it.text }.trim()
    val startMs: Long get() = segments.minOfOrNull { it.startMs } ?: 0L
    val endMs: Long get() = segments.maxOfOrNull { it.endMs } ?: startMs
}

/** Mobile boundary implemented by whisper.cpp through JNI. */
interface WhisperEngine {
    suspend fun load(modelPath: String, preferGpu: Boolean = true): InferenceBackend
    suspend fun transcribe(
        samples16Khz: ShortArray,
        windowStartMs: Long,
        prompt: String,
        language: String = "zh",
        final: Boolean = false,
    ): TranscriptResult
    fun cancel()
    suspend fun close()
}

class VoiceActivityDetector(private val threshold: Int = 450) {
    fun containsSpeech(samples: ShortArray): Boolean = samples.any { kotlin.math.abs(it.toInt()) >= threshold }
}

data class TranscriptMergeUpdate(
    val newlyCommitted: List<WhisperSegment>,
    val stableText: String,
    val provisionalText: String,
    val committedThroughMs: Long,
)

/** Stabilizes timestamped results from overlapping windows without assuming word boundaries. */
class OverlapTranscriptMerger(private val stabilityMs: Long = 1_200L) {
    private var committedText = ""
    private var committedThrough = 0L

    fun update(result: TranscriptResult, capturedThroughMs: Long, final: Boolean = false): TranscriptMergeUpdate {
        val cutoff = if (final) Long.MAX_VALUE else (capturedThroughMs - stabilityMs).coerceAtLeast(0L)
        if (!result.hasPreciseTimestamps) return updateCoarse(result, capturedThroughMs, final)
        val newlyCommitted = mutableListOf<WhisperSegment>()
        result.segments.sortedBy { it.startMs }.forEach { segment ->
            if (segment.endMs <= cutoff && segment.endMs > committedThrough) {
                val novel = appendNovel(committedText, segment.text)
                committedThrough = maxOf(committedThrough, segment.endMs)
                if (novel.isNotBlank()) {
                    val accepted = segment.copy(text = novel)
                    newlyCommitted += accepted
                    committedText = joinTranscript(committedText, novel)
                }
            }
        }
        val provisionalRaw = result.segments
            .filter { it.endMs > committedThrough }
            .joinToString("") { it.text }
            .trim()
        return TranscriptMergeUpdate(
            newlyCommitted = newlyCommitted,
            stableText = committedText,
            provisionalText = appendNovel(committedText, provisionalRaw),
            committedThroughMs = committedThrough,
        )
    }

    private fun updateCoarse(result: TranscriptResult, capturedThroughMs: Long, final: Boolean): TranscriptMergeUpdate {
        val commitThrough = if (final) capturedThroughMs else (capturedThroughMs - stabilityMs).coerceAtLeast(0L)
        val rawText = result.text
        val novel = appendNovel(committedText, rawText)
        val newlyCommitted = if (novel.isNotBlank()) {
            val segment = WhisperSegment(novel, committedThrough, commitThrough)
            committedText = joinTranscript(committedText, novel)
            listOf(segment)
        } else {
            emptyList()
        }
        committedThrough = maxOf(committedThrough, commitThrough)
        val provisionalText = if (final) "" else appendNovel(committedText, rawText)
        return TranscriptMergeUpdate(
            newlyCommitted = newlyCommitted,
            stableText = committedText,
            provisionalText = provisionalText,
            committedThroughMs = committedThrough,
        )
    }

    private fun joinTranscript(existing: String, addition: String): String = when {
        existing.isBlank() -> addition.trim()
        addition.isBlank() -> existing
        needsSpace(existing.last(), addition.first()) -> "$existing ${addition.trim()}"
        else -> existing + addition.trim()
    }

    companion object {
        fun appendNovel(existing: String, candidate: String): String {
            val clean = candidate.trim()
            if (existing.isBlank() || clean.isBlank()) return clean
            val normalizedExisting = normalize(existing)
            val normalizedCandidate = normalize(clean)
            val limit = minOf(normalizedExisting.size, normalizedCandidate.size, 160)
            for (length in limit downTo 1) {
                val suffixStart = normalizedExisting.size - length
                val matches = (0 until length).all { offset ->
                    normalizedExisting[suffixStart + offset].first == normalizedCandidate[offset].first
                }
                if (matches) {
                    val consumedThrough = normalizedCandidate[length - 1].second
                    return clean.drop(consumedThrough + 1).trimStart()
                }
            }
            return clean
        }

        private fun normalize(text: String): List<Pair<Char, Int>> = buildList {
            text.forEachIndexed { index, char ->
                if (char.isLetterOrDigit()) add(char.lowercaseChar() to index)
            }
        }

        private fun needsSpace(left: Char, right: Char): Boolean =
            left.isLetterOrDigit() && left.code < 128 && right.isLetterOrDigit() && right.code < 128
    }
}
