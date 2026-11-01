package com.bhaskar.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var resultView: TextView
    private lateinit var keyBox: EditText
    private val history = JSONArray()

    private val systemPrompt = """
        तुम वेदा हो, भास्कर की निजी वॉइस असिस्टेंट। हमेशा सरल हिंदी में, छोटे वाक्यों में जवाब दो।
        तुम्हें हमेशा सिर्फ़ एक JSON ऑब्जेक्ट लौटाना है, और कुछ नहीं, इस रूप में:
        {"action":"...","query":"...","reply":"..."}
        action इनमें से एक हो सकता है: none, open_youtube, play_song, open_whatsapp, open_camera, torch_on, torch_off, google_search, dial.
        query में गाने का नाम, खोज का विषय या फ़ोन नंबर लिखो, नहीं तो खाली छोड़ो।
        reply में वह छोटा वाक्य लिखो जो तुम बोलकर कहोगी।
        अगर बातचीत करनी है तो action none रखो और reply में जवाब दो।
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("veda", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        keyBox = EditText(this).apply {
            hint = "यहाँ Gemini API Key चिपकाइए"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val saveButton = Button(this).apply { text = "Key सेव करें" }
        val talkButton = Button(this).apply { text = "वेदा से बोलिए" }
        resultView = TextView(this).apply {
            text = "पहले Key सेव कीजिए, फिर बटन दबाकर बोलिए"
            textSize = 20f
            setPadding(0, 32, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(resultView) }

        layout.addView(keyBox)
        layout.addView(saveButton)
        layout.addView(talkButton)
        layout.addView(scroll)
        setContentView(layout)

        saveButton.setOnClickListener {
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            resultView.text = "Key सेव हो गई"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("hi", "IN")
            }
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val said = list?.firstOrNull() ?: ""
                resultView.text = "आपने कहा: $said\n\nवेदा सोच रही है..."
                askGemini(said)
            }
            override fun onError(error: Int) {
                resultView.text = "गड़बड़ हुई, कोड: $error"
            }
            override fun onReadyForSpeech(params: Bundle?) { resultView.text = "सुन रही हूँ..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        talkButton.setOnClickListener {
            tts.stop()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            recognizer.startListening(intent)
        }
    }

    private fun askGemini(said: String) {
        val key = getSharedPreferences("veda", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isEmpty()) {
            resultView.text = "पहले ऊपर Key चिपकाकर सेव कीजिए"
            return
        }
        thread {
            try {
                val userMsg = JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", said)))
                history.put(userMsg)

                val body = JSONObject()
                    .put("system_instruction", JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", history)
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().readText()

                if (code !in 200..299) {
                    runOnUiThread { resultView.text = "एआई से गड़बड़ ($code): $response" }
                    return@thread
                }

                val text = JSONObject(response)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")

                val modelMsg = JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
                history.put(modelMsg)

                val answer = JSONObject(text)
                val action = answer.optString("action", "none")
                val query = answer.optString("query", "")
                val reply = answer.optString("reply", "")

                runOnUiThread {
                    resultView.text = "आपने कहा: $said\n\nवेदा: $reply"
                    if (reply.isNotEmpty()) {
                        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "veda")
                    }
                    doAction(action, query)
                }
            } catch (e: Exception) {
                runOnUiThread { resultView.text = "गड़बड़: ${e.message}" }
            }
        }
    }

    private fun doAction(action: String, query: String) {
        try {
            when (action) {
                "open_youtube" -> openApp("com.google.android.youtube")
                "play_song" -> startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
                "open_whatsapp" -> openApp("com.whatsapp")
                "open_camera" -> startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                "torch_on" -> setTorch(true)
                "torch_off" -> setTorch(false)
                "google_search" -> startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
                "dial" -> {
                    val digits = query.filter { it.isDigit() }
                    if (digits.isNotEmpty()) {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                    } else {
                        startActivity(Intent(Intent.ACTION_DIAL))
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            resultView.append("\n\nकाम में गड़बड़: ${e.message}")
        }
    }

    private fun openApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        } else {
            resultView.append("\n\nयह ऐप फ़ोन में नहीं मिला")
        }
    }

    private fun setTorch(on: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.setTorchMode(cm.cameraIdList.first(), on)
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.destroy()
        tts.shutdown()
    }
}
