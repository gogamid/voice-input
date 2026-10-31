package org.futo.voiceinput.tv

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.settings.ENGLISH_MODEL_INDEX
import org.futo.voiceinput.settings.setSetting
import org.futo.voiceinput.shared.ENGLISH_MODELS
import org.futo.voiceinput.startModelDownloadActivity

class TvSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 28, 56, 24)
            setBackgroundColor(Color.rgb(20, 24, 30))
        }
        root.addView(TextView(this).apply { text = "Voice Input TV"; textSize = 28f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply {
            text = "Unofficial FUTO nightly adaptation · speech stays on this TV\nUSB remote microphone only. No background listening."
            textSize = 17f; setTextColor(Color.LTGRAY)
        })
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12) }
        root.addView(status)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Allow microphone and USB receiver") { requestAccess() }
        button("Use / download small English model") {
            runBlocking { setSetting(ENGLISH_MODEL_INDEX, 3) }
            startModelDownloadActivity(listOf(ENGLISH_MODELS[3]), forUpgrade = false)
            updateStatus()
        }
        editor = EditText(this).apply {
            hint = "Test text appears here"; textSize = 20f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
        }
        root.addView(editor)
        button("Try USB dictation in this text field") {
            editor.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            editor.postDelayed({
                try { TvShortcut.start(this) }
                catch (e: Exception) { status.text = e.message }
            }, 300)
        }
        button("FUTO settings, languages and support") {
            startActivity(Intent(this, org.futo.voiceinput.settings.SettingsActivity::class.java))
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, IntentFilter(permissionAction), RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, IntentFilter(permissionAction))
        root.getChildAt(3).requestFocus()
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun requestAccess() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val device = UsbAudio.receiver(this)
        if (device == null) { status.text = "Plug in the remote's USB receiver first."; return }
        val usb = getSystemService(UsbManager::class.java)
        if (!usb.hasPermission(device)) {
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        } else updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestAccess()
        else updateStatus()
    }

    private fun updateStatus() {
        val device = UsbAudio.receiver(this)
        val usbAllowed = device != null && getSystemService(UsbManager::class.java).hasPermission(device)
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val shortcut = Settings.Secure.getString(contentResolver, "voice_interaction_service")
            ?.startsWith("$packageName/") == true
        status.text = "Receiver: ${if (device != null) "connected" else "missing"} · USB access: ${if (usbAllowed) "allowed" else "needed"}" +
            "\nMicrophone permission: ${if (micAllowed) "allowed" else "needed"} · Native remote shortcut: ${if (shortcut) "configured" else "needs ADB setup"}"
    }

    override fun onDestroy() { unregisterReceiver(permissionReceiver); super.onDestroy() }
}
