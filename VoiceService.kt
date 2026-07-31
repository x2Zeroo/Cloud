package com.cloud.assistant

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import java.util.Locale

class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.cloud.assistant.action.START"
        const val ACTION_STOP = "com.cloud.assistant.action.STOP"
        const val ACTION_SEND_TEXT = "com.cloud.assistant.action.SEND_TEXT"
        const val EXTRA_TEXT = "extra_text"
        private const val NOTIF_CHANNEL_ID = "cloud_voice_channel"
        private const val NOTIF_ID = 1001
    }

    interface Listener {
        fun onStateChanged(listening: Boolean, statusText: String)
        fun onTranscript(text: String)
        fun onReply(text: String)
        fun onRmsLevel(level: Float) // normalized 0..1, drives orb energy
    }

    inner class LocalBinder : Binder() { fun service(): VoiceService = this@VoiceService }
    private val binder = LocalBinder()
    var listener: Listener? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var listening = false; private set
    private val history = mutableListOf<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) { ttsReady = true; selectThaiVoice() }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (listening) startRecognizer() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (listening) startRecognizer() }
        })
    }

    private fun selectThaiVoice() {
        val voices = tts?.voices ?: return
        val preferred = listOf("kanya", "narisa", "premwadee", "pattara", "female", "women")
        val thVoices = voices.filter { it.locale.language.equals("th", ignoreCase = true) }
        val best = thVoices.firstOrNull { v -> preferred.any { v.name.lowercase().contains(it) } }
            ?: thVoices.firstOrNull()
        best?.let { tts?.voice = it }
        tts?.language = Locale("th", "TH")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginListening()
            ACTION_STOP -> stopListeningAndSelf()
            ACTION_SEND_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { handleUserText(it) }
        }
        return START_STICKY
    }

    private fun beginListening() {
        if (listening) return
        listening = true
        postForegroundNotification("กำลังฟัง...")
        listener?.onStateChanged(true, "กำลังฟัง...")
        startRecognizer()
    }

    private fun stopListeningAndSelf() {
        listening = false
        recognizer?.destroy(); recognizer = null
        listener?.onStateChanged(false, "ปิดการฟังแล้ว")
        listener?.onRmsLevel(0f)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startRecognizer() {
        if (!listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listener?.onStateChanged(false, "อุปกรณ์นี้ไม่รองรับการรู้จำเสียง")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Empirical range for Android SpeechRecognizer rms: ~ -2 (silence) .. 10 (loud).
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    listener?.onRmsLevel(normalized)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listener?.onRmsLevel(0f)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) scope.launch { startRecognizer() }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            listening = false
                            listener?.onStateChanged(false, "ไม่ได้รับสิทธิ์ไมโครโฟน")
                        }
                        else -> if (listening) scope.launch { startRecognizer() }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) { listener?.onTranscript(text); handleUserText(text) }
                    else if (listening) startRecognizer()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { listener?.onTranscript(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        }
    }

    private fun handleUserText(text: String) {
        recognizer?.stopListening()
        val apiKey = SecurePrefs.getKey(this)
        if (apiKey.isBlank()) { listener?.onReply("กรุณาใส่ Gemini API key ก่อน"); return }

        history.add("user" to text)
        listener?.onStateChanged(listening, "กำลังคิด...")
        scope.launch {
            try {
                var reply = GeminiClient.send(apiKey, history)
                Regex("\\[OPEN:(\\w+)]", RegexOption.IGNORE_CASE).find(reply)
                    ?.groupValues?.get(1)?.let { AppLauncher.open(this@VoiceService, it) }
                reply = reply.replace(Regex("\\[OPEN:\\w+]", RegexOption.IGNORE_CASE), "").trim()

                history.add("model" to reply)
                listener?.onReply(reply)
                listener?.onStateChanged(listening, "แตะ \"แตะ\" เพื่อเปิดใช้งาน")
                speak(reply)
            } catch (e: GeminiClient.GeminiException) {
                history.removeLastOrNull()
                listener?.onReply("เกิดข้อผิดพลาด: ${e.message}")
                if (listening) startRecognizer()
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) { if (listening) startRecognizer(); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cloud_utt_${System.currentTimeMillis()}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "Cloud Voice Assistant", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Cloud")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .addAction(0, "หยุด", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun postForegroundNotification(status: String) {
        val notif = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
        }
    }

    /** Call after startForeground to update the status line without re-declaring FGS type. */
    private fun updateNotification(status: String) {
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(status))
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
