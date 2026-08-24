package com.listener.app.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JniWhisperEngine : WhisperEngine {
    private var handle: Long = 0

    override suspend fun load(modelPath: String, preferGpu: Boolean): InferenceBackend = withContext(Dispatchers.IO) {
        require(File(modelPath).isFile) { "Whisper model is not installed" }
        close()
        if (preferGpu) handle = nativeCreate(modelPath, true)
        val backend = if (handle != 0L && nativeUsesGpu(handle)) {
            InferenceBackend.VULKAN
        } else {
            if (handle != 0L) nativeDestroy(handle)
            handle = nativeCreate(modelPath, false)
            check(handle != 0L) { "Unable to load Whisper model" }
            InferenceBackend.CPU_FALLBACK
        }
        backend
    }

    override suspend fun transcribe(
        samples16Khz: ShortArray,
        windowStartMs: Long,
        prompt: String,
        language: String,
        final: Boolean,
    ): TranscriptResult = withContext(Dispatchers.Default) {
        check(handle != 0L) { "Whisper model is not loaded" }
        val payload = nativeTranscribe(handle, samples16Khz, language, prompt).orEmpty()
        val segments = payload.split(RECORD_SEPARATOR).mapNotNull { record ->
            val parts = record.split(FIELD_SEPARATOR, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            WhisperSegment(parts[2].trim(), windowStartMs + start, windowStartMs + end)
                .takeIf { it.text.isNotBlank() }
        }
        TranscriptResult(segments)
    }

    override fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(path: String, preferGpu: Boolean): Long
    private external fun nativeUsesGpu(handle: Long): Boolean
    private external fun nativeTranscribe(handle: Long, pcm: ShortArray, language: String, prompt: String): String?
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val RECORD_SEPARATOR = '\u001e'
        private const val FIELD_SEPARATOR = '\u001f'
        init { System.loadLibrary("listener_whisper") }
    }
}
