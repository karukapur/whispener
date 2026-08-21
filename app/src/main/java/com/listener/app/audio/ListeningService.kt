package com.listener.app.audio

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.listener.app.MainActivity
import com.listener.app.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ListeningService : LifecycleService() {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running.get()) startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY // process recreation never starts the microphone without a new user action
    }

    @SuppressLint("MissingPermission") // MainActivity obtains RECORD_AUDIO before dispatching ACTION_START.
    private fun startCapture() {
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name)).setContentText("Microphone active — tap Stop to end immediately")
            .setOngoing(true).setContentIntent(openIntent).addAction(0, "Stop", stopIntent).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, notification)
        val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size * 2).build()
        running.set(true); recorder?.startRecording()
        thread(name = "listener-audio") {
            val samples = ShortArray(size / 2)
            while (running.get()) recorder?.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            releaseRecorder()
        }
    }

    private fun stopCapture() { running.set(false); recorder?.stop(); releaseRecorder(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    @Synchronized private fun releaseRecorder() { recorder?.release(); recorder = null }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) (getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL, "Active listening", NotificationManager.IMPORTANCE_HIGH)) }
    companion object { const val ACTION_START = "com.listener.START"; const val ACTION_STOP = "com.listener.STOP"; private const val CHANNEL = "recording"; private const val ID = 7; private const val RATE = 16_000 }
}
