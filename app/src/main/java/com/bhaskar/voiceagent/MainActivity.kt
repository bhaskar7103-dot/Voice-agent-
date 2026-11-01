package com.bhaskar.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var resultView: TextView
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        resultView = TextView(this).apply {
            text = "बटन दबाकर बोलिए: वेदा, यूट्यूब खोलो"
            textSize = 22f
        }
        val button = Button(this).apply { text = "बोलिए" }
        layout.addView(button)
        layout.addView(resultView)
        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val said = list?.firstOrNull() ?: ""
                resultView.text = "आपने कहा: $said"
                handleCommand(said)
            }
            override fun onError(error: Int) {
                resultView.text = "गड़बड़ हुई, कोड: $error"
            }
            override fun onReadyForSpeech(params: Bundle?) { resultView.text = "सुन रहा हूँ..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        button.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            recognizer.startListening(intent)
        }
    }

    private fun handleCommand(said: String) {
        val text = said.lowercase()

        val wakeWords = listOf("वेदा", "veda", "वेद", "वीडा")
        if (wakeWords.none { text.contains(it) }) {
            resultView.text = "पहले वेदा बोलिए। आपने कहा: $said"
            return
        }

        when {
            text.contains("यूट्यूब") || text.contains("youtube") -> {
                if (text.contains("गाना") || text.contains("बजाओ")) {
                    playSong(text)
                } else {
                    openApp("com.google.android.youtube", "यूट्यूब")
                }
            }
            text.contains("गाना") || text.contains("बजाओ") || text.contains("सॉन्ग") -> playSong(text)
            text.contains("व्हाट्सएप") || text.contains("whatsapp") || text.contains("वाट्सएप") ->
                openApp("com.whatsapp", "व्हाट्सएप")
            text.contains("कैमरा") || text.contains("camera") -> {
                startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                resultView.text = "कैमरा खोल रहा हूँ"
            }
            text.contains("टॉर्च") || text.contains("torch") || text.contains("फ्लैशलाइट") -> {
                val on = !(text.contains("बंद") || text.contains("off"))
                setTorch(on)
            }
            text.contains("कॉल") || text.contains("call") -> callNumber(text)
            text.contains("खोजो") || text.contains("सर्च") || text.contains("गूगल") -> googleSearch(text)
            else -> resultView.text = "यह कमांड मुझे नहीं आता: $said"
        }
    }

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
            resultView.text = "$label खोल रहा हूँ"
        } else {
            resultView.text = "$label फ़ोन में नहीं मिला"
        }
    }

    private fun playSong(text: String) {
        var query = text
        for (w in listOf("वेदा", "veda", "यूट्यूब", "youtube", "पर", "गाना", "बजाओ", "सॉन्ग", "चलाओ", "खोलो")) {
            query = query.replace(w, " ")
        }
        query = query.trim()
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        resultView.text = "यूट्यूब पर खोज रहा हूँ: $query"
    }

    private fun googleSearch(text: String) {
        var query = text
        for (w in listOf("वेदा", "veda", "गूगल", "पर", "खोजो", "सर्च", "करो")) {
            query = query.replace(w, " ")
        }
        query = query.trim()
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        resultView.text = "गूगल पर खोज रहा हूँ: $query"
    }

    private fun callNumber(text: String) {
        val digits = text.filter { it.isDigit() }
        if (digits.length >= 6) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
            resultView.text = "डायलर खोल रहा हूँ: $digits"
        } else {
            startActivity(Intent(Intent.ACTION_DIAL))
            resultView.text = "डायलर खोल रहा हूँ। नंबर बोलकर कहिए, जैसे: वेदा, 9 8 7 6 5 4 3 2 1 0 को कॉल करो"
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first()
            cm.setTorchMode(id, on)
            torchOn = on
            resultView.text = if (on) "टॉर्च चालू" else "टॉर्च बंद"
        } catch (e: Exception) {
            resultView.text = "टॉर्च में गड़बड़: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.destroy()
    }
}
