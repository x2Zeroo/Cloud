package com.cloud.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cloud.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceService? = null
    private var bound = false
    private var typingMode = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = (service as VoiceService.LocalBinder).service()
            voiceService?.listener = uiListener
            bound = true
            binding.micBtn.isSelected = voiceService?.listening == true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null; bound = false
        }
    }

    private val uiListener = object : VoiceService.Listener {
        override fun onStateChanged(listening: Boolean, statusText: String) = runOnUiThread {
            binding.orbView.listening = listening
            binding.capText.text = statusText
            if (!listening) binding.transcriptText.text = ""
        }
        override fun onTranscript(text: String) = runOnUiThread { binding.transcriptText.text = text }
        override fun onReply(text: String) = runOnUiThread {
            binding.transcriptText.text = text
            binding.orbView.pulse()
        }
        override fun onRmsLevel(level: Float) = runOnUiThread { binding.orbView.setTargetLevel(level) }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) startVoiceService()
        else binding.capText.text = "ต้องอนุญาตไมโครโฟนก่อนใช้งาน"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (SecurePrefs.getKey(this).isBlank()) promptForApiKey()

        binding.micBtn.setOnClickListener { onMicToggle() }
        binding.typeToggleBtn.setOnClickListener { setTypingMode(true) }
        binding.backToVoiceBtn.setOnClickListener { setTypingMode(false) }
        binding.nextBtn.setOnClickListener {
            // "Next": interpreted as skip current TTS utterance and resume listening.
            Intent(this, VoiceService::class.java).also { startService(it) }
        }
        binding.typeSendBtn.setOnClickListener { submitTypedText() }
        binding.typeInput.setOnEditorActionListener { _, _, _ -> submitTypedText(); true }

        bindService(Intent(this, VoiceService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun onMicToggle() {
        val currentlyListening = voiceService?.listening == true
        if (currentlyListening) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            binding.micBtn.isSelected = false
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (hasPermission) startVoiceService() else requestPermissions.launch(perms.toTypedArray())
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        binding.micBtn.isSelected = true
    }

    private fun setTypingMode(on: Boolean) {
        typingMode = on
        binding.voiceControls.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        binding.typeForm.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        binding.capText.text = if (on) "พิมพ์แล้วกด Enter" else "แตะ \"แตะ\" เพื่อเปิดใช้งาน"
        if (on) binding.typeInput.requestFocus()
    }

    private fun submitTypedText() {
        val text = binding.typeInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.transcriptText.text = text
        binding.orbView.pulse()
        binding.typeInput.setText("")
        startService(Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_SEND_TEXT
            putExtra(VoiceService.EXTRA_TEXT, text)
        })
    }

    private fun promptForApiKey() {
        val input = EditText(this).apply {
            hint = "วาง Gemini API key..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("บันทึก") { _, _ -> SecurePrefs.setKey(this, input.text.toString().trim()) }
            .show()
    }

    override fun onDestroy() {
        if (bound) { voiceService?.listener = null; unbindService(connection); bound = false }
        super.onDestroy()
    }
}
