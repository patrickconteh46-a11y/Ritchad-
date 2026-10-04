package com.ritchad.app

import android.Manifest
import java.io.File
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import java.io.ByteArrayOutputStream
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

data class Msg(val fromUser: Boolean, val text: String, val image: Bitmap? = null)

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
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

    private var pending by mutableStateOf<Attachment?>(null)
    private var pendingThumb by mutableStateOf<Bitmap?>(null)
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) loadAttachment(uri) }

    private var dl by mutableStateOf("")

    private fun startDownload(url: String) {
        if (dl.startsWith("Downloading") || dl == "Starting...") return
        if (filesDir.usableSpace < 3_000_000_000L && !File(filesDir, "model.part").exists()) { toast("You need about 3 GB of free space"); return }
        dl = "Starting..."
        lifecycleScope.launch {
            var last = -1
            val err = Local.download(applicationContext, url) { g, t ->
                if (t > 0) { val p = (g * 100 / t).toInt(); if (p != last) { last = p; dl = "Downloading $p%" } }
            }
            dl = err
            if (err.isEmpty()) toast("Offline model ready")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun decodeScaled(uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sc = 1
        while (maxOf(o.outWidth, o.outHeight) / sc > 2048) sc *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sc }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun loadAttachment(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        try {
            if (mime.startsWith("image/")) {
                val bmp = decodeScaled(uri)
                if (bmp == null) { toast("Couldn't read that image"); return }
                Session.image = bmp; pendingThumb = bmp
                pending = Attachment(name, "image/jpeg", ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray())
            } else {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) { toast("Couldn't open that file"); return }
                if (bytes.size > 15_000_000) { toast("File too large (15 MB max)"); return }
                val ok = mime == "application/pdf" || mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"
                if (!ok) { toast("Supported: images, PDF and text files"); return }
                pendingThumb = null; pending = Attachment(name, mime, bytes)
            }
        } catch (e: Exception) { toast("Couldn't open that file") }
    }

    private fun saveImage(b: Bitmap) {
        if (Build.VERSION.SDK_INT < 29) { toast("Saving needs Android 10 or newer"); return }
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Ritchad_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ritchad")
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(u)!!.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Saved to Pictures/Ritchad")
        } catch (e: Exception) { toast("Couldn't save the image") }
    }

    private fun send(text: String) {
        val att = pending
        if ((text.isBlank() && att == null) || thinking) return
        msgs.add(Msg(true, if (text.isBlank()) "Attached: ${att!!.name}" else text, pendingThumb)); thinking = true
        val q = if (text.isBlank()) "Please look at the attached file and tell me what it is." else text
        pending = null; pendingThumb = null
        lifecycleScope.launch {
            val reply = brain.ask(q, att)
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
        val ivory = Color(0xFFF6F1E7); val navy = Color(0xFF1B2540); val gold = Color(0xFFC2A15B); val line = Color(0xFFE4DAC4)
        val serif = FontFamily.Serif
        val typo = Typography().let { it.copy(titleLarge = it.titleLarge.copy(fontFamily = serif)) }
        val list = rememberLazyListState()
        var input by remember { mutableStateOf("") }
        val prefs = remember { getSharedPreferences("r", MODE_PRIVATE) }
        var showSettings by remember { mutableStateOf(prefs.getString("key", "").isNullOrBlank()) }
        var showMem by remember { mutableStateOf(false) }
        var keyText by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
        var modelText by remember { mutableStateOf(prefs.getString("model", Brain.MODEL) ?: Brain.MODEL) }
        var forceOffline by remember { mutableStateOf(prefs.getBoolean("forceoffline", false)) }
        var urlText by remember { mutableStateOf(prefs.getString("modelurl", Local.DEFAULT_URL) ?: Local.DEFAULT_URL) }
        val installed = remember(dl, showSettings) { Local.installed(this@MainActivity) }
        var imgText by remember { mutableStateOf(prefs.getString("imgmodel", Brain.IMG_MODEL) ?: Brain.IMG_MODEL) }
        LaunchedEffect(msgs.size) { list.animateScrollToItem(maxOf(0, msgs.size - 1)) }
        val canSend = input.isNotBlank() || pending != null
        val pulse by rememberInfiniteTransition(label = "m").animateFloat(1f, 1.14f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "mp")
        MaterialTheme(colorScheme = lightColorScheme(primary = navy, onPrimary = ivory, background = ivory, surface = Color.White, onSurface = navy, secondary = gold), typography = typo) {
            Column(Modifier.fillMaxSize().background(ivory).imePadding()) {
                Row(Modifier.fillMaxWidth().background(navy).statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Emblem(thinking || hands || Shared.wakeOn, gold, ivory, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Ritchad", fontFamily = serif, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ivory)
                        Text(if (thinking) "Thinking..." else if (hands) "Listening..." else if (Shared.wakeOn) "Say: Hey Ritchad" else "At your service", fontSize = 12.sp, color = gold)
                    }
                    TextButton(onClick = { showMem = true }) { Text("Memory", color = ivory) }
                    TextButton(onClick = { showSettings = true }) { Text("Settings", color = ivory) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = Shared.wakeOn, onClick = { toggleWake() }, label = { Text("Wake word") })
                    FilterChip(selected = speakOn, onClick = { speakOn = !speakOn }, label = { Text("Voice replies") })
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(msgs) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                            Surface(
                                color = if (m.fromUser) navy else Color.White,
                                shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                                border = if (m.fromUser) null else BorderStroke(1.dp, line),
                                shadowElevation = if (m.fromUser) 0.dp else 1.dp,
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    m.image?.let { bmp ->
                                        Image(bmp.asImageBitmap(), null, Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    Text(m.text, color = if (m.fromUser) ivory else navy, fontSize = 16.sp, lineHeight = 22.sp)
                                    if (!m.fromUser) m.image?.let { bmp -> TextButton(onClick = { saveImage(bmp) }) { Text("Save to Photos", color = gold) } }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    pending?.let { a ->
                        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            pendingThumb?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(8.dp)) }
                            Text(a.name, Modifier.weight(1f), maxLines = 1, fontSize = 14.sp)
                            TextButton(onClick = { pending = null; pendingThumb = null }) { Text("Remove", color = gold) }
                        }
                    }
                    Surface(shape = RoundedCornerShape(32.dp), color = Color.White, border = BorderStroke(1.dp, line), shadowElevation = 6.dp) {
                        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { pickFile.launch("*/*") }, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 28.sp, color = gold) }
                            TextField(input, { input = it }, Modifier.weight(1f), maxLines = 4, placeholder = { Text("Message Ritchad") },
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            Box(Modifier.size(52.dp).scale(if (hands && !canSend) pulse else 1f).clip(CircleShape)
                                .background(if (canSend) navy else if (hands) Color(0xFFB3402F) else gold)
                                .clickable { if (canSend) { send(input); input = "" } else toggleMic() }, contentAlignment = Alignment.Center) {
                                Text(if (canSend) "➤" else "🎤", fontSize = 22.sp, color = Color.White)
                            }
                        }
                    }
                }
                if (showMem) AlertDialog(
                    onDismissRequest = { showMem = false }, containerColor = Color.White,
                    title = { Text("Memories") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Mem.items.isEmpty()) Text("Nothing yet. Say \"remember that...\" and it will appear here.")
                        Mem.items.toList().forEachIndexed { i, t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t, Modifier.weight(1f))
                                TextButton(onClick = { Mem.removeAt(i) }) { Text("Delete", color = gold) }
                            }
                        }
                    } },
                    confirmButton = { TextButton(onClick = { showMem = false }) { Text("Done") } },
                    dismissButton = { if (Mem.items.isNotEmpty()) TextButton(onClick = { Mem.clear() }) { Text("Clear all") } }
                )
                if (showSettings) AlertDialog(
                    onDismissRequest = { showSettings = false }, containerColor = Color.White,
                    title = { Text("Settings") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste a free Gemini key from aistudio.google.com/apikey. It stays on this phone.")
                        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") }, singleLine = true)
                        OutlinedTextField(modelText, { modelText = it }, label = { Text("Chat model") }, singleLine = true)
                        OutlinedTextField(imgText, { imgText = it }, label = { Text("Image-edit model") }, singleLine = true)
                        TextButton(onClick = { cycleVoice() }) { Text("Try next voice", color = gold) }
                        HorizontalDivider()
                        Text("Offline brain", fontWeight = FontWeight.Bold)
                        Text(if (installed) "Offline model installed." else "Not installed. Needs about 3 GB free. Use Wi-Fi.")
                        if (dl.isNotEmpty()) Text(dl, color = gold)
                        OutlinedTextField(urlText, { urlText = it }, label = { Text("Model URL (.litertlm)") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startDownload(urlText) }, enabled = !dl.startsWith("Downloading") && dl != "Starting...") { Text(if (installed) "Re-download" else "Download") }
                            if (installed) TextButton(onClick = { File(filesDir, "model.litertlm").delete(); Local.release(); toast("Model deleted"); dl = "" }) { Text("Delete", color = gold) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(forceOffline, { forceOffline = it }); Spacer(Modifier.width(8.dp)); Text("Always use offline brain") }
                    } },
                    confirmButton = { TextButton(onClick = {
                        prefs.edit().putString("key", keyText.trim()).putString("model", modelText.trim()).putString("imgmodel", imgText.trim()).putBoolean("forceoffline", forceOffline).putString("modelurl", urlText.trim()).apply(); showSettings = false
                    }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } }
                )
            }
        }
    }

    @Composable
    private fun Emblem(active: Boolean, ring: Color, letter: Color, size: Dp) {
        val t = rememberInfiniteTransition(label = "e")
        val pulse by t.animateFloat(1f, 1.15f, infiniteRepeatable(tween(if (active) 700 else 2400), RepeatMode.Reverse), label = "p")
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(ring.copy(alpha = 0.22f), this.size.minDimension / 2 * pulse * 0.85f)
                drawCircle(ring, this.size.minDimension / 2 * 0.62f, style = Stroke(width = 2.dp.toPx()))
            }
            Text("R", color = letter, fontFamily = FontFamily.Serif, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
        }
    }
}


object Chat {
    fun addImage(b: Bitmap, caption: String) { msgs.add(Msg(false, caption, b)) }
    val msgs = mutableStateListOf(Msg(false, "Systems online. Tap Mic to talk, turn on the wake word, or type."))
}

object Shared {
    var wakeOn by mutableStateOf(false)
    private var b: Brain? = null
    fun brain(ctx: Context): Brain {
        val p = ctx.applicationContext.getSharedPreferences("r", Context.MODE_PRIVATE)
        Mem.init(p)
        return b ?: Brain(DeviceTools(ctx.applicationContext), p, ctx.applicationContext).also { b = it }
    }
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
