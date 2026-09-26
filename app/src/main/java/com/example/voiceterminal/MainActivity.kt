package com.example.voiceterminal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    private val apiKey = "AIzaSyDYtFapNjj5jqiXs2wtx0KRQB3dJodS0BQ"

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

        checkPermissions()

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
    }

    private fun processNaturalLanguageWithAI(promptText: String) {
        tvTerminalOutput.append("\n[You]: $promptText\n[AI Thinking...]\n")
        scrollToBottom()

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val systemInstruction = "Convert the user request (which might be in Hindi, Hinglish, or broken English) into a single executable Android shell / Linux command. Return ONLY the raw command, no backticks, no markdown, no explanation. Example: 'download folder dikhao' -> 'ls /sdcard/Download'."

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
                    val generatedCommand = jsonResponse
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                        .replace("`", "")

                    runOnUiThread {
                        executeCommand(generatedCommand)
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    runOnUiThread {
                        tvTerminalOutput.append("[AI Error]: $err\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvTerminalOutput.append("[Network/AI Failed]: ${e.message}\n")
                }
            }
            scrollToBottom()
        }
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
                        tvTerminalOutput.append("[Exit code: $exitCode (Done)]\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvTerminalOutput.append("[Failed: ${e.message}]\n")
                }
            }
            scrollToBottom()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Kuch bhi bolo...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            tvTerminalOutput.append("[Voice Input Not Supported]\n")
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun scrollToBottom() {
        runOnUiThread {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
