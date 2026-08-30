package com.listener.app.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.listener.app.ListenerApplication
import com.listener.app.MainActivity
import com.listener.app.R
import com.listener.app.data.session.DatabaseProvider
import com.listener.app.data.session.SessionRepository
import com.listener.app.speech.InferenceBackend
import com.listener.app.speech.OverlapTranscriptMerger
import com.listener.app.speech.TranscriptResult
import com.listener.app.speech.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class PlatformSpeechService : LifecycleService(), RecognitionListener {
    private val running = AtomicBoolean(false)
    private val repository by lazy { SessionRepository(DatabaseProvider.get(this).sessions()) }
    private var recognizer: SpeechRecognizer? = null
    private var sessionId: Long? = null
    private var startedAtMs = 0L
    private var lastCommitMs = 0L
    private var restarting = false
    private var languageIndex = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (running.compareAndSet(false, true)) startRecognizer(
                intent.getIntExtra(EXTRA_CADENCE_SECONDS, 10),
            )
            ACTION_STOP -> stopRecognizer()
        }
        return START_NOT_STICKY
    }

    private fun startRecognizer(cadenceSeconds: Int) {
        promoteToForeground("Android on-device recognizer")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required to transcribe speech.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail("Android speech recognition is not available on this device.")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            fail("Android on-device speech recognition is not available on this device.")
            return
        }
        lifecycleScope.launch {
            sessionId = repository.create(
                "Recording · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}",
                cadenceSeconds,
            )
            startedAtMs = System.currentTimeMillis()
            languageIndex = 0
            (application as ListenerApplication).models.manager.markLoaded(ENGINE_ID)
            ListeningRuntime.update {
                ListenerRuntimeState(
                    recording = true,
                    activeModelId = ENGINE_ID,
                    backend = InferenceBackend.ANDROID_ON_DEVICE,
                    sessionId = sessionId,
                )
            }
            recognizer = if (Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this@PlatformSpeechService)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this@PlatformSpeechService)
            }.also { it.setRecognitionListener(this@PlatformSpeechService) }
            listen()
        }
    }

    private fun listen() {
        if (!running.get()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val language = AndroidSpeechLanguages.candidates[languageIndex]
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (language != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        restarting = false
        recognizer?.startListening(intent)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = bestText(partialResults) ?: return
        ListeningRuntime.update { it.copy(provisionalTranscript = text) }
    }

    override fun onResults(results: Bundle?) {
        commit(bestText(results))
        restartIfNeeded()
    }

    override fun onSegmentResults(segmentResults: Bundle) {
        commit(bestText(segmentResults))
    }

    override fun onEndOfSegmentedSession() {
        restartIfNeeded()
    }

    override fun onError(error: Int) {
        if (!running.get()) return
        val recoverable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
        val languageRejected = error == ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT || error == ERROR_LANGUAGE_UNAVAILABLE_COMPAT
        when {
            languageRejected && tryNextLanguage() -> Unit
            recoverable -> restartIfNeeded()
            else -> fail("Android speech recognition stopped: ${errorLabel(error)}.")
        }
    }

    private fun commit(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isBlank()) return
        val currentStable = ListeningRuntime.state.value.stableTranscript
        val novel = OverlapTranscriptMerger.appendNovel(currentStable, clean)
        if (novel.isBlank()) return
        val now = System.currentTimeMillis()
        val start = lastCommitMs
        val end = (now - startedAtMs).coerceAtLeast(start + 1)
        lastCommitMs = end
        val segment = WhisperSegment(novel, start, end)
        lifecycleScope.launch(Dispatchers.IO) {
            sessionId?.let { repository.appendSegment(it, TranscriptResult(listOf(segment), isFinal = true)) }
        }
        ListeningRuntime.update {
            val stable = if (it.stableTranscript.isBlank()) novel else it.stableTranscript + novel
            it.copy(stableTranscript = stable, provisionalTranscript = "", elapsedSeconds = end / 1_000, processingLagMs = 0)
        }
    }

    private fun bestText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun restartIfNeeded() {
        if (!running.get() || restarting) return
        restarting = true
        lifecycleScope.launch {
            recognizer?.cancel()
            listen()
        }
    }

    private fun tryNextLanguage(): Boolean {
        if (languageIndex >= AndroidSpeechLanguages.candidates.lastIndex) return false
        languageIndex += 1
        val label = AndroidSpeechLanguages.candidates[languageIndex] ?: "device default language"
        ListeningRuntime.update { it.copy(transcriptionStatus = "Trying Android recognizer language: $label") }
        restartIfNeeded()
        return true
    }

    private fun stopRecognizer() {
        if (!running.getAndSet(false)) {
            stopSelf()
            return
        }
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        lifecycleScope.launch {
            sessionId?.let { repository.finish(it) }
            (application as ListenerApplication).models.manager.markLoaded(null)
            ListeningRuntime.update {
                it.copy(recording = false, stopping = false, activeModelId = null, backend = null, provisionalTranscript = "", audioLevel = 0f)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        ListeningRuntime.update { ListenerRuntimeState(recoverableError = message) }
        running.set(false)
        recognizer?.destroy()
        recognizer = null
        (application as ListenerApplication).models.manager.markLoaded(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        recognizer?.destroy()
        (application as ListenerApplication).models.manager.markLoaded(null)
        super.onDestroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        ListeningRuntime.update { it.copy(audioLevel = level) }
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

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
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, notification)
    }

    private fun errorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio capture error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing permission"
        ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT -> "language not supported"
        ERROR_LANGUAGE_UNAVAILABLE_COMPAT -> "language unavailable"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "recognizer service error"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recognizer service disconnected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        else -> "error $error"
    }

    companion object {
        const val ACTION_START = "com.listener.PLATFORM_START"
        const val ACTION_STOP = "com.listener.PLATFORM_STOP"
        const val EXTRA_CADENCE_SECONDS = "cadence_seconds"
        const val ENGINE_ID = "android"
        private const val CHANNEL = "recording"
        private const val ID = 8
        private const val ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE_COMPAT = 13
    }
}

internal object AndroidSpeechLanguages {
    val candidates = listOf(
        "zh-TW",
        "zh-Hant-TW",
        "cmn-Hant-TW",
        "zh",
        "cmn",
        "zh-CN",
        null,
    )
}
