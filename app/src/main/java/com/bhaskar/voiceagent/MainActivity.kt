package com.bhaskar.voiceagent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
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
    private val handler = Handler(Looper.getMainLooper())

    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")

    private val nameWords = listOf("एलेक्सा", "अलेक्सा", "alexa", "एलेक्स")

    private val systemPrompt = """
        तुम एलेक्सा हो, एक निजी वॉइस असिस्टेंट। यूज़र को हमेशा "बॉस" या "सर" कहकर बुलाओ, कभी उनका नाम मत लो।
        हमेशा सरल हिंदी में, छोटे वाक्यों में जवाब दो।
        तुम्हें हमेशा सिर्फ़ एक JSON ऑब्जेक्ट लौटाना है, और कुछ नहीं, इस रूप में:
        {"action":"...","query":"...","reply":"..."}
        action इनमें से एक हो सकता है: none, open_youtube, play_song, open_whatsapp, open_camera, torch_on, torch_off, google_search, dial, wifi_on, wifi_off, bluetooth, lock, silent, vibrate, ring.
        query में गाने का नाम, खोज का विषय या फ़ोन नंबर लिखो, नहीं तो खाली छोड़ो।
        मौसम, ख़बर या किसी ताज़ा जानकारी के लिए action google_search रखो और query में खोज का विषय लिखो।
        reply में वह छोटा वाक्य लिखो जो तुम बोलकर कहोगी।
        अगर बातचीत करनी है तो action none रखो और reply में जवाब दो।
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("alexa", Context.MODE_PRIVATE)

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
        val talkButton = Button(this).apply { text = "एलेक्सा से बोलिए" }
        val accButton = Button(this).apply { text = "Accessibility चालू करें" }
        val dndButton = Button(this).apply { text = "साइलेंट की अनुमति दें" }
        resultView = TextView(this).apply {
            text = "बटन दबाकर बोलिए। आसान कमांड बिना Key के भी चलेंगे।"
            textSize = 20f
            setPadding(0, 32, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(resultView) }

        layout.addView(keyBox)
        layout.addView(saveButton)
        layout.addView(talkButton)
        layout.addView(accButton)
        layout.addView(dndButton)
        layout.addView(scroll)
        setContentView(layout)

        saveButton.setOnClickListener {
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            resultView.text = "Key सेव हो गई"
        }

        accButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        dndButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
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
                resultView.text = "आपने कहा: $said"
                if (!tryLocalCommand(said)) {
                    resultView.append("\n\nएलेक्सा सोच रही है...")
                    askGemini(said)
                }
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

    private fun cleanQuery(text: String, extra: List<String>): String {
        var q = text
        for (w in nameWords + extra) {
            q = q.replace(w, " ", ignoreCase = true)
        }
        return q.replace(Regex("\\s+"), " ").trim()
    }

    private fun tryLocalCommand(said: String): Boolean {
        val text = said.lowercase()

        val wantsSong = text.contains("गाना") || text.contains("गाने") || text.contains("सॉन्ग") || text.contains("song")
        val wantsPlay = text.contains("बजाओ") || text.contains("चलाओ") || text.contains("सुनाओ") || text.contains("सुनना")
        val weather = text.contains("मौसम") || text.contains("वेदर") || text.contains("weather") || text.contains("तापमान")
        val turnOff = text.contains("बंद") || text.contains("ऑफ") || text.contains("off")

        when {
            text.contains("साइलेंट") || text.contains("silent") || text.contains("म्यूट") || text.contains("mute") -> {
                setRinger(AudioManager.RINGER_MODE_SILENT, "साइलेंट")
                return true
            }
            text.contains("वाइब्रेशन") || text.contains("वाइब्रेट") || text.contains("vibrat") -> {
                setRinger(AudioManager.RINGER_MODE_VIBRATE, "वाइब्रेशन")
                return true
            }
            text.contains("रिंग") || text.contains("ring") || text.contains("नॉर्मल मोड") || text.contains("आवाज़ चालू") -> {
                setRinger(AudioManager.RINGER_MODE_NORMAL, "रिंग")
                return true
            }
            text.contains("लॉक") || text.contains("lock") -> {
                lockPhone()
                return true
            }
            text.contains("वाईफाई") || text.contains("वाई-फाई") || text.contains("वाईफ़ाई") || text.contains("वाई फाई") || text.contains("wifi") || text.contains("wi-fi") -> {
                toggleWifi(!turnOff)
                return true
            }
            text.contains("ब्लूटूथ") || text.contains("bluetooth") -> {
                openBluetooth()
                return true
            }
            weather -> {
                googleSearch("आज का मौसम")
                return true
            }
            wantsSong || (wantsPlay && text.contains("यूट्यूब")) -> {
                val q = cleanQuery(text, listOf("यूट्यूब", "youtube", "पर", "गाना", "गाने", "सॉन्ग", "song", "बजाओ", "चलाओ", "सुनाओ", "सुनना", "है", "मुझे", "का", "की", "खोलो", "करो"))
                playSong(q)
                return true
            }
            text.contains("यूट्यूब") || text.contains("youtube") -> {
                openApp("com.google.android.youtube", "यूट्यूब")
                return true
            }
            text.contains("बिज़नेस") || text.contains("बिजनेस") || text.contains("business") -> {
                openApp("com.whatsapp.w4b", "व्हाट्सएप बिज़नेस")
                return true
            }
            text.contains("व्हाट्सएप") || text.contains("whatsapp") || text.contains("वाट्सएप") || text.contains("व्हाट्सऐप") -> {
                openWhatsApp()
                return true
            }
            text.contains("कैमरा") || text.contains("camera") -> {
                startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                reply("ठीक है बॉस, कैमरा खोल रही हूँ")
                return true
            }
            text.contains("टॉर्च") || text.contains("torch") || text.contains("फ्लैशलाइट") || text.contains("टार्च") -> {
                setTorch(!turnOff)
                return true
            }
            text.contains("कॉल") || text.contains("call") || text.contains("फोन लगाओ") -> {
                val digits = said.filter { it.isDigit() }
                if (digits.length >= 6) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                    reply("ठीक है सर, डायलर खोल रही हूँ")
                    return true
                }
                return false
            }
            text.contains("खोजो") || text.contains("सर्च") || text.contains("गूगल") -> {
                val q = cleanQuery(text, listOf("गूगल", "google", "पर", "खोजो", "सर्च", "करो", "में", "के बारे में"))
                googleSearch(q)
                return true
            }
        }
        return false
    }

    private fun reply(msg: String) {
        resultView.append("\n\nएलेक्सा: $msg")
        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "alexa")
    }

    private fun setRinger(mode: Int, label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            resultView.append("\n\nपहले \"साइलेंट की अनुमति दें\" बटन दबाकर एलेक्सा को चालू कीजिए")
            tts.speak("बॉस, पहले साइलेंट की अनुमति दीजिए", TextToSpeech.QUEUE_FLUSH, null, "alexa")
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
        reply("ठीक है बॉस, फ़ोन $label मोड पर है")
    }

    private fun toggleWifi(on: Boolean) {
        startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        reply(if (on) "ठीक है बॉस, वाईफ़ाई चालू कर रही हूँ" else "ठीक है बॉस, वाईफ़ाई बंद कर रही हूँ")
        handler.postDelayed({
            val service = AlexaService.instance
            if (service != null) {
                val ok = service.toggleSwitchWanted(on)
                if (!ok) {
                    resultView.append("\n\nस्विच नहीं मिला, आप खुद दबा दीजिए")
                }
            } else {
                resultView.append("\n\nस्विच खुद दबाने के लिए Accessibility चालू कीजिए")
            }
        }, 2000)
    }

    private fun googleSearch(q: String) {
        val url = "https://www.google.com/search?q=" + Uri.encode(q)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        reply("ठीक है बॉस, गूगल पर खोज रही हूँ")
    }

    private fun playSong(query: String) {
        val youtubeUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl))
        viewIntent.setPackage("com.google.android.youtube")
        try {
            startActivity(viewIntent)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)))
        }
        reply("ठीक है सर, $query यूट्यूब पर चला रही हूँ")

        handler.postDelayed({
            val service = AlexaService.instance
            if (service != null) {
                val ok = service.clickFirstVideoResult()
                if (!ok) {
                    resultView.append("\n\nपहला गाना नहीं मिला, आप खुद दबा दीजिए")
                }
            } else {
                resultView.append("\n\nगाना खुद चलाने के लिए Accessibility चालू कीजिए")
            }
        }, 4500)
    }

    private fun openWhatsApp() {
        val normal = packageManager.getLaunchIntentForPackage("com.whatsapp")
        val business = packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        when {
            normal != null -> {
                startActivity(normal)
                reply("ठीक है बॉस, व्हाट्सएप खोल रही हूँ")
            }
            business != null -> {
                startActivity(business)
                reply("ठीक है बॉस, व्हाट्सएप बिज़नेस खोल रही हूँ")
            }
            else -> resultView.append("\n\nव्हाट्सएप फ़ोन में नहीं मिला")
        }
    }

    private fun lockPhone() {
        val service = AlexaService.instance
        if (service != null) {
            reply("ठीक है सर, फ़ोन लॉक कर रही हूँ")
            handler.postDelayed({ service.lockScreen() }, 1500)
        } else {
            resultView.append("\n\nलॉक करने के लिए Accessibility चालू कीजिए")
        }
    }

    private fun openBluetooth() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        reply("ठीक है बॉस, ब्लूटूथ की सेटिंग खोल रही हूँ")
    }

    private fun askGemini(said: String) {
        val key = getSharedPreferences("alexa", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isEmpty()) {
            resultView.append("\n\nबातचीत के लिए ऊपर Key चिपकाकर सेव कीजिए")
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

                var code = 0
                var response = ""
                for ((index, model) in models.withIndex()) {
                    runOnUiThread {
                        resultView.text = "आपने कहा: $said\n\nएलेक्सा सोच रही है... (कोशिश ${index + 1})"
                    }
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.doOutput = true
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }

                    code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    response = stream.bufferedReader().readText()

                    if (code in 200..299) break
                    if (code != 503 && code != 429 && code != 500) break
                    Thread.sleep(2000)
                }

                if (code !in 200..299) {
                    history.remove(history.length() - 1)
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
                val replyText = answer.optString("reply", "")

                runOnUiThread {
                    resultView.text = "आपने कहा: $said"
                    if (replyText.isNotEmpty()) {
                        reply(replyText)
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
                "open_youtube" -> openApp("com.google.android.youtube", "यूट्यूब")
                "play_song" -> playSong(query)
                "open_whatsapp" -> openWhatsApp()
                "open_camera" -> startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                "torch_on" -> setTorch(true)
                "torch_off" -> setTorch(false)
                "google_search" -> googleSearch(query)
                "wifi_on" -> toggleWifi(true)
                "wifi_off" -> toggleWifi(false)
                "bluetooth" -> openBluetooth()
                "lock" -> lockPhone()
                "silent" -> setRinger(AudioManager.RINGER_MODE_SILENT, "साइलेंट")
                "vibrate" -> setRinger(AudioManager.RINGER_MODE_VIBRATE, "वाइब्रेशन")
                "ring" -> setRinger(AudioManager.RINGER_MODE_NORMAL, "रिंग")
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

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
            reply("ठीक है बॉस, $label खोल रही हूँ")
        } else {
            resultView.append("\n\n$label फ़ोन में नहीं मिला")
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList.first(), on)
            reply(if (on) "ठीक है सर, टॉर्च चालू" else "ठीक है सर, टॉर्च बंद")
        } catch (e: Exception) {
            resultView.append("\n\nटॉर्च में गड़बड़: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.destroy()
        tts.s
