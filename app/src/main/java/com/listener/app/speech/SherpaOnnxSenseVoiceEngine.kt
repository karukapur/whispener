package com.listener.app.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SherpaOnnxSenseVoiceEngine : WhisperEngine {
    private var offlineRecognizer: OfflineRecognizer? = null
    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private var onlineAcceptedSamples: Long = 0

    override suspend fun load(modelPath: String, preferGpu: Boolean): InferenceBackend = withContext(Dispatchers.IO) {
        val dir = File(modelPath)
        val tokens = dir.resolve("tokens.txt")
        close()
        if (dir.resolve("encoder.int8.onnx").isFile && dir.resolve("decoder.int8.onnx").isFile && tokens.isFile) {
            onlineRecognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = dir.resolve("encoder.int8.onnx").path,
                            decoder = dir.resolve("decoder.int8.onnx").path,
                        ),
                        tokens = tokens.path,
                        modelType = "paraformer",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                ),
            )
        } else {
            val model = dir.resolve("model.int8.onnx")
            require(model.isFile && tokens.isFile) { "Install a sherpa-onnx model at ${dir.path} with the required ONNX files and tokens.txt." }
            offlineRecognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = model.path).takeIf { dir.name.contains("paraformer") }
                            ?: OfflineParaformerModelConfig(),
                        senseVoice = if (dir.name.contains("sense-voice")) {
                            OfflineSenseVoiceModelConfig(
                                model = model.path,
                                language = "zh",
                                useInverseTextNormalization = true,
                            )
                        } else {
                            OfflineSenseVoiceModelConfig()
                        },
                        tokens = tokens.path,
                        modelType = if (dir.name.contains("paraformer")) "paraformer" else "",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                ),
            )
        }
        InferenceBackend.SHERPA_ONNX
    }

    override suspend fun transcribe(
        samples16Khz: ShortArray,
        windowStartMs: Long,
        prompt: String,
        language: String,
        final: Boolean,
    ): TranscriptResult = withContext(Dispatchers.Default) {
        val text = when {
            onlineRecognizer != null -> transcribeOnline(samples16Khz, windowStartMs, final)
            offlineRecognizer != null -> transcribeOffline(samples16Khz)
            else -> error("sherpa-onnx model is not loaded")
        }
        TranscriptResult(
            segments = if (text.isBlank()) emptyList() else listOf(
                WhisperSegment(text, windowStartMs, windowStartMs + samples16Khz.size * 1_000L / 16_000L),
            ),
            hasPreciseTimestamps = false,
        )
    }

    override fun cancel() = Unit

    override suspend fun close() = withContext(Dispatchers.IO) {
        onlineStream?.release()
        offlineRecognizer?.release()
        onlineRecognizer?.release()
        onlineStream = null
        offlineRecognizer = null
        onlineRecognizer = null
        onlineAcceptedSamples = 0
    }

    private fun transcribeOnline(samples16Khz: ShortArray, windowStartMs: Long, final: Boolean): String {
        val activeRecognizer = checkNotNull(onlineRecognizer)
        val absoluteStartSample = windowStartMs * 16
        val absoluteEndSample = absoluteStartSample + samples16Khz.size
        if (onlineStream == null || absoluteEndSample < onlineAcceptedSamples) {
            onlineStream?.release()
            onlineStream = activeRecognizer.createStream()
            onlineAcceptedSamples = absoluteStartSample
        }
        val stream = checkNotNull(onlineStream)
        val suffixOffset = (onlineAcceptedSamples - absoluteStartSample).coerceIn(0, samples16Khz.size.toLong()).toInt()
        if (suffixOffset < samples16Khz.size) {
            stream.acceptWaveform(samples16Khz.copyOfRange(suffixOffset, samples16Khz.size).toFloatSamples(), sampleRate = 16_000)
            onlineAcceptedSamples = absoluteEndSample
        }
        if (final) {
            stream.inputFinished()
        }
        while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)
        return activeRecognizer.getResult(stream).text.trim()
    }

    private fun transcribeOffline(samples16Khz: ShortArray): String {
        val activeRecognizer = checkNotNull(offlineRecognizer)
        val stream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(samples16Khz.toFloatSamples(), sampleRate = 16_000)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun ShortArray.toFloatSamples(): FloatArray =
        FloatArray(size) { index -> this[index] / 32768.0f }
}
