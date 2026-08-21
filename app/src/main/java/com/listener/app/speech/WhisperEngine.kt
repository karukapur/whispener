package com.listener.app.speech

/** Mobile boundary implemented by a whisper.cpp Android AAR; no desktop/JVM runtime is linked. */
interface WhisperEngine {
    suspend fun load(modelPath: String, preferGpu: Boolean = true)
    suspend fun transcribe(samples16Khz: ShortArray, language: String = "zh"): TranscriptResult
    suspend fun close()
}
data class TranscriptResult(val text: String, val isFinal: Boolean, val startMs: Long, val endMs: Long)

class StreamingTranscriber(private val engine: WhisperEngine, private val vad: VoiceActivityDetector) {
    suspend fun accept(chunk: ShortArray): TranscriptResult? = if (vad.containsSpeech(chunk)) engine.transcribe(chunk, "zh") else null
}
class VoiceActivityDetector(private val threshold: Int = 450) {
    fun containsSpeech(samples: ShortArray): Boolean = samples.any { kotlin.math.abs(it.toInt()) >= threshold }
}
