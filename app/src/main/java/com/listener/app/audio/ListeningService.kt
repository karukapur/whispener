package com.listener.app.audio

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.listener.app.ListenerApplication
import com.listener.app.MainActivity
import com.listener.app.R
import com.listener.app.data.WhisperWorkProfile
import com.listener.app.data.session.DatabaseProvider
import com.listener.app.data.session.SessionRepository
import com.listener.app.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class ListenerRuntimeState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val modelLoading: Boolean = false,
    val stopping: Boolean = false,
    val elapsedSeconds: Long = 0,
    val sessionId: Long? = null,
    val activeModelId: String? = null,
    val backend: InferenceBackend? = null,
    val stableTranscript: String = "",
    val provisionalTranscript: String = "",
    val audioLevel: Float = 0f,
    val processingLagMs: Long = 0,
    val transcriptionStatus: String? = null,
    val recoverableError: String? = null,
) {
    val transcript: String
        get() = listOf(stableTranscript, provisionalTranscript).filter(String::isNotBlank).joinToString("")
}

object ListeningRuntime {
    private val mutable = MutableStateFlow(ListenerRuntimeState())
    val state = mutable.asStateFlow()
    fun update(block: (ListenerRuntimeState) -> ListenerRuntimeState) = mutable.update(block)
}

private data class AudioWindow(
    val samples: ShortArray,
    val startMs: Long,
    val endMs: Long,
    val final: Boolean = false,
)

internal class PcmRingBuffer(private val capacity: Int) {
    private val values = ShortArray(capacity)
    private var head = 0
    var size = 0; private set
    var totalSamples = 0L; private set
    val earliestSample: Long get() = totalSamples - size

    fun append(source: ShortArray, count: Int) {
        for (index in 0 until count) {
            val destination = (head + size) % capacity
            values[destination] = source[index]
            if (size == capacity) head = (head + 1) % capacity else size++
            totalSamples++
        }
    }

    fun snapshotFrom(absoluteSample: Long): ShortArray {
        val start = absoluteSample.coerceIn(earliestSample, totalSamples)
        val count = (totalSamples - start).toInt()
        val offset = (start - earliestSample).toInt()
        return ShortArray(count) { index -> values[(head + offset + index) % capacity] }
    }
}

class ListeningService : LifecycleService() {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val committedThroughMs = AtomicLong(0)
    private val capturedSampleOffset = AtomicLong(0)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var processingJob: Job? = null
    private var sessionId: Long? = null
    private var focusRequest: AudioFocusRequest? = null
    private var workProfile = WhisperWorkProfile.RESPONSIVE
    private var foregroundStatus = "Preparing local model"
    private var windows = Channel<AudioWindow>(Channel.CONFLATED)
    private var engine: WhisperEngine? = null
    private val repository by lazy { SessionRepository(DatabaseProvider.get(this).sessions()) }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!stopping.get() && running.compareAndSet(false, true)) startCapture(
                intent.getStringExtra(EXTRA_MODEL_PATH),
                intent.getStringExtra(EXTRA_MODEL_ID),
                intent.getIntExtra(EXTRA_CADENCE_SECONDS, 10),
                WhisperWorkProfile.fromId(intent.getStringExtra(EXTRA_WORK_PROFILE)),
                InferenceBackend.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_BACKEND) },
            )
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_STOP -> lifecycleScope.launch { stopAndFlush() }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(modelPath: String?, modelId: String?, cadenceSeconds: Int, profile: WhisperWorkProfile, requestedBackend: InferenceBackend?) {
        promoteToForeground("Preparing local model")
        if (modelPath.isNullOrBlank() || !File(modelPath).exists()) {
            ListeningRuntime.update { ListenerRuntimeState(recoverableError = "Download and select a local model before recording.") }
            running.set(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (!requestAudioFocus()) {
            ListeningRuntime.update { ListenerRuntimeState(recoverableError = "Another app is using audio. Try again when it finishes.") }
            running.set(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        windows = Channel(Channel.CONFLATED)
        workProfile = profile
        committedThroughMs.set(0)
        capturedSampleOffset.set(0)
        paused.set(false)
        val activeModel = modelId ?: File(modelPath).nameWithoutExtension.removePrefix("ggml-")
        (application as ListenerApplication).models.manager.markLoaded(activeModel)
        ListeningRuntime.update { ListenerRuntimeState(recording = true, paused = false, modelLoading = true, activeModelId = activeModel) }
        processingJob = lifecycleScope.launch(Dispatchers.Default) {
            var failed = false
            try {
                val activeEngine = when (requestedBackend) {
                    InferenceBackend.SHERPA_ONNX -> SherpaOnnxSenseVoiceEngine()
                    else -> JniWhisperEngine()
                }
                engine = activeEngine
                val backend = activeEngine.load(modelPath, preferGpu = requestedBackend != InferenceBackend.SHERPA_ONNX)
                if (!running.get()) return@launch
                sessionId = repository.create(
                    "Recording · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}",
                    cadenceSeconds,
                )
                ListeningRuntime.update { it.copy(modelLoading = false, backend = backend, sessionId = sessionId) }
                foregroundStatus = "$activeModel · ${backend.label}"
                if (paused.get()) {
                    promoteToForeground("Paused")
                } else {
                    promoteToForeground(foregroundStatus)
                    captureJob = lifecycleScope.launch(Dispatchers.IO) { captureLoop() }
                }
                processWindows()
            } catch (error: Throwable) {
                failed = error !is CancellationException
                if (failed) ListeningRuntime.update { it.copy(recoverableError = error.message ?: "Transcription failed") }
                running.set(false)
                paused.set(false)
                recorder?.runCatching { stop() }
            } finally {
                engine?.close()
                engine = null
                if (failed) withContext(Dispatchers.Main.immediate) { finishService() }
            }
        }
    }

    private suspend fun processWindows() {
        val merger = OverlapTranscriptMerger(STABILITY_MS)
        for (window in windows) {
            val prompt = buildString {
                append(TRADITIONAL_CHINESE_PROMPT)
                val context = ListeningRuntime.state.value.stableTranscript.takeLast(PROMPT_CHARS)
                if (context.isNotBlank()) append("\n最近內容：").append(context)
            }
            val result = checkNotNull(engine).transcribe(window.samples, window.startMs, prompt, "zh", final = window.final)
            val update = merger.update(result, window.endMs, final = window.final)
            committedThroughMs.accumulateAndGet(update.committedThroughMs, ::maxOf)
            val currentSession = sessionId
            if (currentSession != null) {
                update.newlyCommitted.forEach { segment ->
                    repository.appendSegment(currentSession, TranscriptResult(listOf(segment), isFinal = true))
                }
            }
            val lag = (window.endMs - committedThroughMs.get()).coerceAtLeast(0)
            ListeningRuntime.update {
                it.copy(
                    stableTranscript = update.stableText,
                    provisionalTranscript = if (window.final) "" else update.provisionalText,
                    processingLagMs = lag,
                    transcriptionStatus = if (lag >= CATCHING_UP_MS) "Transcription is catching up · ${lag / 1_000}s behind" else null,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureLoop() {
        val minSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) return failCapture("This device does not support 16 kHz microphone capture.")
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minSize * 2)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return failCapture("Unable to initialize the microphone.")
        recorder = audioRecord
        val baseSamples = capturedSampleOffset.get()
        val block = ShortArray(maxOf(minSize / 2, RATE / 10))
        val audio = PcmRingBuffer(RATE * MAX_BUFFER_SECONDS)
        var nextDecodeSample = RATE.toLong() * decodeStepSeconds()
        var lastSpeechMs = -RECENT_SPEECH_MS - 1
        var speechSinceDecode = false
        try {
            audioRecord.startRecording()
            while (running.get() && !paused.get()) {
                val count = audioRecord.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                audio.append(block, count)
                val nowMs = samplesToMs(baseSamples + audio.totalSamples)
                val level = audioLevel(block, count)
                val hasSpeech = containsSpeech(block, count)
                if (hasSpeech) { lastSpeechMs = nowMs; speechSinceDecode = true }
                ListeningRuntime.update { it.copy(elapsedSeconds = nowMs / 1_000, audioLevel = level) }

                if (!speechSinceDecode && nowMs - lastSpeechMs > RECENT_SPEECH_MS && committedThroughMs.get() >= lastSpeechMs) {
                    committedThroughMs.set(nowMs)
                }
                val lag = nowMs - committedThroughMs.get()
                if (lag >= MAX_BUFFER_SECONDS * 1_000L - decodeStepSeconds() * 1_000L) {
                    failCapture("The selected model cannot keep up. The completed transcript was saved.")
                    break
                }
                val silenceFinalize = speechSinceDecode && !hasSpeech && nowMs - lastSpeechMs >= SILENCE_FINALIZE_MS
                if (audio.totalSamples >= nextDecodeSample || silenceFinalize) {
                    if (speechSinceDecode || nowMs - lastSpeechMs <= RECENT_SPEECH_MS) {
                        windows.trySend(makeWindow(audio, nowMs, baseSamples, final = false))
                    }
                    speechSinceDecode = false
                    nextDecodeSample = audio.totalSamples + RATE.toLong() * decodeStepSeconds()
                }
            }
        } catch (error: Throwable) {
            if (running.get() && !paused.get()) failCapture(error.message ?: "Microphone capture failed")
        } finally {
            val endMs = samplesToMs(baseSamples + audio.totalSamples)
            capturedSampleOffset.accumulateAndGet(baseSamples + audio.totalSamples, ::maxOf)
            if (!paused.get()) {
                if (audio.size > 0) windows.trySend(makeWindow(audio, endMs, baseSamples, final = true))
                windows.close()
            }
            audioRecord.runCatching { stop() }
            audioRecord.release()
            recorder = null
            ListeningRuntime.update { it.copy(audioLevel = 0f) }
        }
    }

    private fun makeWindow(audio: PcmRingBuffer, endMs: Long, baseSamples: Long, final: Boolean): AudioWindow {
        val overlapStart = (committedThroughMs.get() - overlapMs()).coerceAtLeast(0)
        val normalStart = (endMs - windowMs()).coerceAtLeast(0)
        val desiredStartMs = minOf(normalStart, overlapStart)
        val desiredSample = desiredStartMs * RATE / 1_000
        val startSample = maxOf(baseSamples + audio.earliestSample, desiredSample)
        return AudioWindow(audio.snapshotFrom(startSample - baseSamples), samplesToMs(startSample), endMs, final)
    }

    private fun decodeStepSeconds(): Int = when (workProfile) {
        WhisperWorkProfile.RESPONSIVE -> 4
        WhisperWorkProfile.CONSERVATIVE -> 2
    }

    private fun windowMs(): Long = when (workProfile) {
        WhisperWorkProfile.RESPONSIVE -> 4_000L
        WhisperWorkProfile.CONSERVATIVE -> 8_000L
    }

    private fun overlapMs(): Long = when (workProfile) {
        WhisperWorkProfile.RESPONSIVE -> 400L
        WhisperWorkProfile.CONSERVATIVE -> 800L
    }

    private fun failCapture(message: String) {
        ListeningRuntime.update { it.copy(recoverableError = message) }
        running.set(false)
        paused.set(false)
        recorder?.runCatching { stop() }
        lifecycleScope.launch { stopAndFlush() }
    }

    private fun pauseCapture() {
        if (!running.get() || paused.get() || stopping.get()) return
        paused.set(true)
        recorder?.runCatching { stop() }
        abandonAudioFocus()
        ListeningRuntime.update { it.copy(paused = true, audioLevel = 0f, recoverableError = null) }
        promoteToForeground("Paused")
    }

    private fun resumeCapture() {
        if (!running.get() || !paused.get() || stopping.get()) return
        if (!requestAudioFocus()) {
            ListeningRuntime.update {
                it.copy(paused = true, audioLevel = 0f, recoverableError = "Microphone could not be reacquired. Tap Resume to try again.")
            }
            promoteToForeground("Paused")
            return
        }
        lifecycleScope.launch {
            captureJob?.join()
            if (running.get() && paused.get() && !stopping.get()) {
                paused.set(false)
                ListeningRuntime.update { it.copy(paused = false, recoverableError = null) }
                promoteToForeground(foregroundStatus)
                if (engine != null && sessionId != null) {
                    captureJob = lifecycleScope.launch(Dispatchers.IO) { captureLoop() }
                }
            }
        }
    }

    private fun containsSpeech(samples: ShortArray, count: Int): Boolean {
        for (index in 0 until count) if (kotlin.math.abs(samples[index].toInt()) >= VAD_THRESHOLD) return true
        return false
    }

    private fun audioLevel(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (index in 0 until count) {
            val normalized = samples[index] / 32768.0
            sum += normalized * normalized
        }
        return (sqrt(sum / count) * 5.5).toFloat().coerceIn(0f, 1f)
    }

    private fun samplesToMs(samples: Long): Long = samples * 1_000L / RATE

    private suspend fun stopAndFlush() {
        if (!stopping.compareAndSet(false, true)) return
        paused.set(false)
        ListeningRuntime.update { it.copy(paused = false, stopping = true) }
        if (!running.getAndSet(false) && captureJob == null) { stopSelf(); return }
        recorder?.runCatching { stop() }
        captureJob?.join()
        windows.close()
        processingJob?.join()
        finishService()
    }

    private suspend fun finishService() {
        sessionId?.let { repository.finish(it) }
        (application as ListenerApplication).models.manager.markLoaded(null)
        abandonAudioFocus()
        ListeningRuntime.update {
            it.copy(recording = false, paused = false, modelLoading = false, stopping = false, activeModelId = null, audioLevel = 0f, provisionalTranscript = "")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        paused.set(false)
        engine?.cancel()
        recorder?.runCatching { stop() }
        (application as ListenerApplication).models.manager.markLoaded(null)
        abandonAudioFocus()
        super.onDestroy()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    ListeningRuntime.update { it.copy(recoverableError = "Recording stopped because audio was interrupted.") }
                    lifecycleScope.launch { stopAndFlush() }
                }
            }.build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private fun promoteToForeground(status: String) {
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, notification)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active listening", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.listener.START"
        const val ACTION_PAUSE = "com.listener.PAUSE"
        const val ACTION_RESUME = "com.listener.RESUME"
        const val ACTION_STOP = "com.listener.STOP"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_CADENCE_SECONDS = "cadence_seconds"
        const val EXTRA_WORK_PROFILE = "work_profile"
        const val EXTRA_BACKEND = "backend"
        private const val CHANNEL = "recording"
        private const val ID = 7
        internal const val RATE = 16_000
        private const val VAD_THRESHOLD = 450
        private const val STABILITY_MS = 1_200L
        private const val SILENCE_FINALIZE_MS = 700L
        private const val RECENT_SPEECH_MS = 2_500L
        private const val CATCHING_UP_MS = 6_000L
        private const val MAX_BUFFER_SECONDS = 30
        private const val PROMPT_CHARS = 500
        private const val TRADITIONAL_CHINESE_PROMPT = "以下是臺灣繁體中文對話逐字稿。請使用繁體中文、保留人名與原意，不要翻譯。"
    }
}
