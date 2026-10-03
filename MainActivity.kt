package com.ritchad.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

data class Msg(val fromUser: Boolean, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val msgs = mutableStateListOf(Msg(false, "Systems online. Tap Mic to talk, or type."))
    private var thinking by mutableStateOf(false)
    private var hands by mutableStateOf(false)
    private var speakOn by mutableStateOf(true)
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var brain: Brain
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { hands = true; listen() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brain = Brain(DeviceTools(applicationContext), getSharedPreferences("r", MODE_PRIVATE))
        tts = TextToSpeech(this, this)
        setContent { Ui() }
    }

    override fun onInit(status: Int) {
        tts.language = Locale.UK
        tts.setPitch(0.85f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { runOnUiThread { listen() } }
            override fun onError(id: String?) { runOnUiThread { listen() } }
        })
    }

    private fun send(text: String) {
        if (text.isBlank() || thinking) return
        msgs.add(Msg(true, text)); thinking = true
        lifecycleScope.launch {
            val reply = brain.ask(text)
            msgs.add(Msg(false, reply)); thinking = false
            if (speakOn) tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "r") else listen()
        }
    }

    private fun listen() {
        if (!hands || thinking || tts.isSpeaking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: android.os.Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t != null) send(t) else listen()
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) hands = false
                    else window.decorView.postDelayed({ listen() }, 600)
                }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    private fun toggleMic() {
        if (hands) { hands = false; recognizer?.destroy(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { hands = true; listen() }
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() { recognizer?.destroy(); tts.shutdown(); super.onDestroy() }

    @Composable
    private fun Ui() {
        val cyan = Color(0xFF5FD4E0); val bg = Color(0xFF04070A)
        val list = rememberLazyListState()
        var input by remember { mutableStateOf("") }
        val prefs = remember { getSharedPreferences("r", MODE_PRIVATE) }
        var showSettings by remember { mutableStateOf(prefs.getString("key", "").isNullOrBlank()) }
        var keyText by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
        var modelText by remember { mutableStateOf(prefs.getString("model", Brain.MODEL) ?: Brain.MODEL) }
        LaunchedEffect(msgs.size) { list.animateScrollToItem(maxOf(0, msgs.size - 1)) }
        MaterialTheme(colorScheme = darkColorScheme(primary = cyan, background = bg, surface = bg)) {
            Column(Modifier.fillMaxSize().background(bg).systemBarsPadding().imePadding().padding(16.dp)) {
                TextButton(onClick = { showSettings = true }, modifier = Modifier.align(Alignment.End)) { Text("Settings") }
                Core(thinking, hands)
                Text(if (thinking) "Thinking" else if (hands) "Listening" else "Online", color = cyan, modifier = Modifier.align(Alignment.CenterHorizontally))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(msgs) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                            Surface(color = if (m.fromUser) Color(0x22E8A855) else Color(0x1A5FD4E0), shape = MaterialTheme.shapes.small) {
                                Text(m.text, Modifier.padding(12.dp), color = Color(0xFFD9E8EC))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { toggleMic() }) { Text(if (hands) "Stop" else "Mic") }
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask Ritchad") })
                    TextButton(onClick = { speakOn = !speakOn }) { Text(if (speakOn) "Voice on" else "Voice off") }
                    Button(onClick = { send(input); input = "" }) { Text("Send") }
                }
                if (showSettings) AlertDialog(
                    onDismissRequest = { showSettings = false },
                    title = { Text("Setup") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste a free Gemini key from aistudio.google.com/apikey. It stays on this phone.")
                        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") }, singleLine = true)
                        OutlinedTextField(modelText, { modelText = it }, label = { Text("Model") }, singleLine = true)
                    } },
                    confirmButton = { TextButton(onClick = {
                        prefs.edit().putString("key", keyText.trim()).putString("model", modelText.trim()).apply(); showSettings = false
                    }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } }
                )
            }
        }
    }

    @Composable
    private fun Core(thinking: Boolean, listening: Boolean) {
        val t = rememberInfiniteTransition(label = "core")
        val pulse by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(if (thinking) 600 else 1800), RepeatMode.Reverse), label = "p")
        val col = if (thinking) Color(0xFFE8A855) else Color(0xFF5FD4E0)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = .5f), Color.Transparent), c, 55.dp.toPx() * pulse), 55.dp.toPx() * pulse, c)
            drawCircle(col, 22.dp.toPx() * (if (listening) pulse else 1f), c)
        }
    }
}
