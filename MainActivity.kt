package com.ritchad.app

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

fun applyVoice(t: TextToSpeech, prefs: android.content.SharedPreferences) {
    t.language = Locale.UK
    val en = t.voices?.filter { it.locale.language == "en" } ?: emptyList()
    val saved = prefs.getString("voice", null)
    val male = listOf("gbb", "gbd", "rjs", "iol", "iom", "tpd")
    fun isMale(n: String) = male.any { n.contains("-x-$it") } || (n.contains("male", true) && !n.contains("female", true))
    val pick = en.firstOrNull { it.name == saved }
        ?: en.firstOrNull { it.locale.country == "GB" && isMale(it.name) }
        ?: en.firstOrNull { isMale(it.name) }
    if (pick != null) t.voice = pick
    t.setPitch(if (pick == null) 0.75f else 0.9f)
}

data class Msg(val fromUser: Boolean, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val msgs get() = Chat.msgs
    private var thinking by mutableStateOf(false)
    private var hands by mutableStateOf(false)
    private var speakOn by mutableStateOf(true)
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var brain: Brain
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { hands = true; listen() } }

    private val askWake = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true)
            ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
    }

    private fun toggleWake() {
        if (Shared.wakeOn) { stopService(Intent(this, WakeService::class.java)); return }
        hands = false; recognizer?.destroy()
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        askWake.launch(need.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brain = Shared.brain(applicationContext)
        tts = TextToSpeech(this, this)
        setContent { Ui() }
    }

    override fun onInit(status: Int) {
        applyVoice(tts, getSharedPreferences("r", MODE_PRIVATE))
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

    private fun cycleVoice() {
        val prefs = getSharedPreferences("r", MODE_PRIVATE)
        val en = tts.voices?.filter { it.locale.language == "en" }?.sortedBy { it.name } ?: return
        if (en.isEmpty()) return
        val i = en.indexOfFirst { it.name == prefs.getString("voice", "") }
        prefs.edit().putString("voice", en[(i + 1) % en.size].name).apply()
        applyVoice(tts, prefs)
        tts.speak("Good day. This is my voice.", TextToSpeech.QUEUE_FLUSH, null, "v")
    }

    private fun toggleMic() {
        if (Shared.wakeOn) stopService(Intent(this, WakeService::class.java))
        if (hands) { hands = false; recognizer?.destroy(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { hands = true; listen() }
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() { recognizer?.destroy(); tts.shutdown(); super.onDestroy() }

    @Composable
    private fun Ui() {
        val ivory = Color(0xFFF7F1E3); val navy = Color(0xFF1F2A44); val gold = Color(0xFFB08D3C); val card = Color(0xFFFFFBF2)
        val serif = FontFamily.Serif
        val base = Typography()
        val typo = base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = serif), bodyMedium = base.bodyMedium.copy(fontFamily = serif),
            bodySmall = base.bodySmall.copy(fontFamily = serif), labelLarge = base.labelLarge.copy(fontFamily = serif),
            titleLarge = base.titleLarge.copy(fontFamily = serif))
        val list = rememberLazyListState()
        var input by remember { mutableStateOf("") }
        val prefs = remember { getSharedPreferences("r", MODE_PRIVATE) }
        var showSettings by remember { mutableStateOf(prefs.getString("key", "").isNullOrBlank()) }
        var keyText by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
        var modelText by remember { mutableStateOf(prefs.getString("model", Brain.MODEL) ?: Brain.MODEL) }
        LaunchedEffect(msgs.size) { list.animateScrollToItem(maxOf(0, msgs.size - 1)) }
        MaterialTheme(colorScheme = lightColorScheme(primary = navy, onPrimary = ivory, background = ivory, surface = card, onSurface = navy, secondary = gold), typography = typo) {
            Column(Modifier.fillMaxSize().background(ivory).systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { toggleWake() }) { Text(if (Shared.wakeOn) "Wake word: ON" else "Wake word: off", color = gold) }
                    TextButton(onClick = { showSettings = true }) { Text("Settings", color = gold) }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Emblem(thinking || hands || Shared.wakeOn, gold, navy)
                    Text("Ritchad", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = navy)
                    Text("YOUR PERSONAL ASSISTANT", fontSize = 11.sp, letterSpacing = 3.sp, color = gold)
                    Text(if (thinking) "Thinking..." else if (hands) "Listening..." else if (Shared.wakeOn) "Say: Hey Ritchad" else "At your service", fontSize = 13.sp, color = navy.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = gold.copy(alpha = 0.6f))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(msgs) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                            if (m.fromUser) Surface(color = navy, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)) {
                                Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = ivory, fontSize = 16.sp)
                            } else Surface(color = card, shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), border = BorderStroke(1.dp, gold.copy(alpha = 0.55f))) {
                                Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = navy, fontSize = 16.sp)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("Ask Ritchad") })
                    Button(onClick = { send(input); input = "" }, colors = ButtonDefaults.buttonColors(containerColor = navy, contentColor = ivory)) { Text("Send") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { toggleMic() }, border = BorderStroke(1.dp, gold)) { Text(if (hands) "Stop listening" else "Speak", color = navy) }
                    TextButton(onClick = { speakOn = !speakOn }) { Text(if (speakOn) "Voice: on" else "Voice: off", color = gold) }
                }
                if (showSettings) AlertDialog(
                    onDismissRequest = { showSettings = false },
                    containerColor = card,
                    title = { Text("Setup") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste a free Gemini key from aistudio.google.com/apikey. It stays on this phone.")
                        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") }, singleLine = true)
                        OutlinedTextField(modelText, { modelText = it }, label = { Text("Model") }, singleLine = true)
                        TextButton(onClick = { cycleVoice() }) { Text("Try next voice", color = gold) }
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
    private fun Emblem(active: Boolean, gold: Color, navy: Color) {
        val t = rememberInfiniteTransition(label = "e")
        val pulse by t.animateFloat(1f, 1.15f, infiniteRepeatable(tween(if (active) 700 else 2400), RepeatMode.Reverse), label = "p")
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(gold.copy(alpha = 0.22f), size.minDimension / 2 * pulse * 0.85f)
                drawCircle(gold, size.minDimension / 2 * 0.62f, style = Stroke(width = 3.dp.toPx()))
            }
            Text("R", color = navy, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}


object Chat {
    val msgs = mutableStateListOf(Msg(false, "Systems online. Tap Mic to talk, turn on the wake word, or type."))
}

object Shared {
    var wakeOn by mutableStateOf(false)
    private var b: Brain? = null
    fun brain(ctx: Context): Brain = b ?: Brain(
        DeviceTools(ctx.applicationContext),
        ctx.applicationContext.getSharedPreferences("r", Context.MODE_PRIVATE)
    ).also { b = it }
}

class WakeService : Service(), TextToSpeech.OnInitListener {
    private val h = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awaiting = false
    private var busy = false
    private var alive = true
    private val wake = Regex("(hey |hi |ok |okay )?(ritchad|richad|richard|ritchard|rishad|rich ad)")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("wake", "Ritchad listening", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "wake")
            .setContentTitle("Ritchad is listening")
            .setContentText("Say Hey Ritchad. Tap to open the app and turn it off.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        Shared.wakeOn = true
        if (tts == null) tts = TextToSpeech(this, this) else listen()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        tts?.apply {
            applyVoice(this, this@WakeService.getSharedPreferences("r", Context.MODE_PRIVATE))
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { h.post { listen() } }
                override fun onError(id: String?) { h.post { listen() } }
            })
        }
        ttsReady = true
        listen()
    }

    private fun again(delay: Long = 500) { h.postDelayed({ listen() }, delay) }

    private fun listen() {
        if (!alive || busy || tts?.isSpeaking == true) return
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) again() else handle(t)
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) stopSelf()
                    else { awaiting = false; again(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500) }
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    private fun handle(t: String) {
        if (awaiting) { awaiting = false; run(t); return }
        val s = t.lowercase()
        val m = wake.find(s)
        if (m == null) { again(); return }
        val rest = s.substring(m.range.last + 1).trim(' ', ',', '.', '!')
        if (rest.length > 2) run(rest) else { awaiting = true; speak("Yes?") }
    }

    private fun run(cmd: String) {
        busy = true
        Chat.msgs.add(Msg(true, cmd))
        scope.launch {
            val reply = Shared.brain(this@WakeService).ask(cmd)
            Chat.msgs.add(Msg(false, reply))
            busy = false
            speak(reply)
        }
    }

    private fun speak(t: String) {
        if (ttsReady) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "w") else again()
    }

    override fun onDestroy() {
        alive = false; Shared.wakeOn = false
        h.removeCallbacksAndMessages(null)
        rec?.destroy(); tts?.shutdown(); scope.cancel()
        super.onDestroy()
    }
}
