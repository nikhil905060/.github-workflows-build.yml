package com.example.voiceterminal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvTerminalOutput: TextView
    private lateinit var etCommandInput: EditText
    private lateinit var scrollView: ScrollView

    private val defaultApiKey = "AIzaSyDYtFapNjj5jqiXs2wtx0KRQB3dJodS0BQ"
    private var isTorchOn = false

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                etCommandInput.setText(spokenText)
                processNaturalLanguageWithAI(spokenText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTerminalOutput = findViewById(R.id.tvTerminalOutput)
        etCommandInput = findViewById(R.id.etCommandInput)
        scrollView = findViewById(R.id.scrollView)

        val btnSend: Button = findViewById(R.id.btnSend)
        val btnMic: Button = findViewById(R.id.btnMic)
        val btnMenu: Button = findViewById(R.id.btnMenu)

        checkAllPermissions()

        btnSend.setOnClickListener {
            val text = etCommandInput.text.toString().trim()
            if (text.isNotEmpty()) {
                processNaturalLanguageWithAI(text)
                etCommandInput.setText("")
            }
        }

        btnMic.setOnClickListener {
            startListening()
        }

        btnMenu.setOnClickListener {
            showTerminalMenu()
        }
    }

    private fun getActiveApiKey(): String {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        return prefs.getString("custom_api_key", defaultApiKey) ?: defaultApiKey
    }

    private fun showTerminalMenu() {
        val options = arrayOf(
            "🔑 Set Gemini API Key",
            "🔔 Grant Notification Access (Required)",
            "🧹 Clear Terminal Output",
            "📱 Show Device Specs",
            "📋 List Installed Apps"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("[ CONTROL MENU ]")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showApiKeyDialog()
                    1 -> startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    2 -> clearScreen()
                    3 -> showDeviceInfo()
                    4 -> listInstalledApps()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearScreen() {
        tvTerminalOutput.text = "[Terminal Cleared]\n$ "
        scrollToBottom()
    }

    private fun showDeviceInfo() {
        val info = """
            [DEVICE INFORMATION]
            Model: ${Build.MANUFACTURER.uppercase(Locale.ROOT)} ${Build.MODEL}
            Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Hardware: ${Build.HARDWARE}
            Board: ${Build.BOARD}
        """.trimIndent()
        tvTerminalOutput.append("\n$info\n\n$ ")
        scrollToBottom()
    }

    private fun listInstalledApps() {
        tvTerminalOutput.append("\n[FETCHING INSTALLED APPS...]\n")
        thread {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val builder = StringBuilder()
            for (app in packages) {
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    val label = pm.getApplicationLabel(app).toString()
                    builder.append("- ").append(label).append(" (").append(app.packageName).append(")\n")
                }
            }
            runOnUiThread {
                tvTerminalOutput.append(builder.toString() + "\n$ ")
                scrollToBottom()
            }
        }
    }

    private fun showApiKeyDialog() {
        val currentKey = getActiveApiKey()
        val input = EditText(this).apply {
            hint = "Paste custom Gemini API Key"
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xFF161B22.toInt())
            setPadding(24, 24, 24, 24)
            setText(if (currentKey == defaultApiKey) "" else currentKey)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Config: Gemini API Key")
            .setMessage("Leave empty to use system default key.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                if (newKey.isNotEmpty()) {
                    prefs.edit().putString("custom_api_key", newKey).apply()
                    Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show()
                    tvTerminalOutput.append("\n[System]: Custom API key set successfully.\n$ ")
                } else {
                    prefs.edit().remove("custom_api_key").apply()
                    Toast.makeText(this, "Reset to Default Key", Toast.LENGTH_SHORT).show()
                    tvTerminalOutput.append("\n[System]: Reset to default API key.\n$ ")
                }
                scrollToBottom()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processNaturalLanguageWithAI(promptText: String) {
        tvTerminalOutput.append("\n[You]: $promptText\n[AI Routing...]\n")
        scrollToBottom()

        val activeKey = getActiveApiKey()

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$activeKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val systemInstruction = """
                    You are an Android OS Master Controller router. 
                    Analyze the user prompt (Hindi, English, Hinglish) and map it to EXACTLY ONE prefix command:

                    1. Flashlight on/off -> ACTION:TORCH_ON or ACTION:TORCH_OFF
                    2. Volume increase/decrease/mute -> ACTION:VOL_UP or ACTION:VOL_DOWN or ACTION:VOL_MUTE
                    3. Notifications check -> ACTION:READ_NOTIFICATIONS
                    4. Open device settings/wifi/bluetooth -> ACTION:OPEN_SETTINGS or ACTION:OPEN_WIFI or ACTION:OPEN_BLUETOOTH
                    5. Go to Home screen -> ACTION:HOME
                    6. Open any application -> OPEN:<AppName> (e.g. 'OPEN:YouTube', 'OPEN:WhatsApp', 'OPEN:Camera')
                    7. Linux shell / files task -> CMD:<shell command> (e.g. 'CMD:ls /sdcard/Download')

                    Output ONLY the command string. No markdown, no backticks, no explanations.
                """.trimIndent()

                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "$systemInstruction\nUser: $promptText")
                        }))
                    }))
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    val jsonResponse = JSONObject(response)
                    val generatedAction = jsonResponse
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                        .replace("`", "")

                    runOnUiThread {
                        handleAiAction(generatedAction)
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    runOnUiThread {
                        tvTerminalOutput.append("[AI Error]: $err\n$ ")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvTerminalOutput.append("[Network/AI Failed]: ${e.message}\n$ ")
                }
            }
            scrollToBottom()
        }
    }

    private fun handleAiAction(action: String) {
        when {
            action.startsWith("ACTION:") -> {
                val act = action.removePrefix("ACTION:").trim()
                executeDeviceAction(act)
            }
            action.startsWith("OPEN:") -> {
                val target = action.removePrefix("OPEN:").trim()
                tvTerminalOutput.append("[Action]: Opening $target...\n")
                launchApp(target)
            }
            action.startsWith("CMD:") -> {
                val cmd = action.removePrefix("CMD:").trim()
                executeCommand(cmd)
            }
            else -> {
                executeCommand(action)
            }
        }
    }

    private fun executeDeviceAction(actionType: String) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            when (actionType) {
                "TORCH_ON" -> {
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, true)
                    isTorchOn = true
                    tvTerminalOutput.append("[Success]: Flashlight Turned ON\n$ ")
                }
                "TORCH_OFF" -> {
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, false)
                    isTorchOn = false
                    tvTerminalOutput.append("[Success]: Flashlight Turned OFF\n$ ")
                }
                "VOL_UP" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    tvTerminalOutput.append("[Success]: Volume Increased\n$ ")
                }
                "VOL_DOWN" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    tvTerminalOutput.append("[Success]: Volume Decreased\n$ ")
                }
                "VOL_MUTE" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    tvTerminalOutput.append("[Success]: Volume Muted\n$ ")
                }
                "OPEN_SETTINGS" -> {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    tvTerminalOutput.append("[Success]: Settings opened\n$ ")
                }
                "OPEN_WIFI" -> {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    tvTerminalOutput.append("[Success]: WiFi Settings opened\n$ ")
                }
                "OPEN_BLUETOOTH" -> {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    tvTerminalOutput.append("[Success]: Bluetooth Settings opened\n$ ")
                }
                "HOME" -> {
                    val startMain = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(startMain)
                    tvTerminalOutput.append("[Success]: Sent to Home Screen\n$ ")
                }
                "READ_NOTIFICATIONS" -> {
                    synchronized(NotificationMonitor.notificationLogs) {
                        if (NotificationMonitor.notificationLogs.isEmpty()) {
                            tvTerminalOutput.append("[Notifications]: No new notifications captured. (Make sure Notification Access is granted in MENU)\n$ ")
                        } else {
                            tvTerminalOutput.append("\n--- RECENT NOTIFICATIONS ---\n")
                            for (log in NotificationMonitor.notificationLogs) {
                                tvTerminalOutput.append(log + "\n")
                            }
                            tvTerminalOutput.append("----------------------------\n$ ")
                        }
                    }
                }
                else -> {
                    tvTerminalOutput.append("[Unknown Action]: $actionType\n$ ")
                }
            }
        } catch (e: Exception) {
            tvTerminalOutput.append("[Action Failed]: ${e.message}\n$ ")
        }
        scrollToBottom()
    }

    private fun launchApp(target: String) {
        val pm = packageManager
        var intent: Intent? = null
        val lowerTarget = target.lowercase(Locale.ROOT)

        when {
            lowerTarget.contains("setting") -> intent = Intent(Settings.ACTION_SETTINGS)
            lowerTarget.contains("youtube") -> intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
            lowerTarget.contains("whatsapp") -> intent = pm.getLaunchIntentForPackage("com.whatsapp")
            lowerTarget.contains("chrome") -> intent = pm.getLaunchIntentForPackage("com.android.chrome")
            lowerTarget.contains("camera") -> intent = Intent("android.media.action.IMAGE_CAPTURE")
        }

        if (intent == null) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
                if (label.contains(lowerTarget) || app.packageName.lowercase(Locale.ROOT).contains(lowerTarget)) {
                    intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) break
                }
            }
        }

        if (intent != null) {
            try {
                startActivity(intent)
                tvTerminalOutput.append("[Success]: $target launched.\n$ ")
            } catch (e: Exception) {
                tvTerminalOutput.append("[Failed to launch]: ${e.message}\n$ ")
            }
        } else {
            tvTerminalOutput.append("[Error]: App '$target' not found on this device.\n$ ")
        }
        scrollToBottom()
    }

    private fun executeCommand(command: String) {
        tvTerminalOutput.append("$ $command\n")
        scrollToBottom()

        thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))

                var line: String?
                var hasOutput = false

                while (reader.readLine().also { line = it } != null) {
                    hasOutput = true
                    val out = line
                    runOnUiThread { tvTerminalOutput.append(out + "\n") }
                }
                while (errReader.readLine().also { line = it } != null) {
                    hasOutput = true
                    val err = line
                    runOnUiThread { tvTerminalOutput.append("[ERR] " + err + "\n") }
                }

                val exitCode = process.waitFor()
                if (!hasOutput) {
                    runOnUiThread {
                        tvTerminalOutput.append("[Exit code: $exitCode]\n$ ")
                    }
                } else {
                    runOnUiThread {
                        tvTerminalOutput.append("$ ")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvTerminalOutput.append("[Failed: ${e.message}]\n$ ")
                }
            }
            scrollToBottom()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo command...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            tvTerminalOutput.append("[Voice Input Not Supported]\n$ ")
        }
    }

    private fun checkAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun scrollToBottom() {
        runOnUiThread {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
