package com.ritchad.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class Attachment(val name: String, val mime: String, val bytes: ByteArray)

object Session { var image: Bitmap? = null }

object Mem {
    val items = mutableStateListOf<String>()
    private var prefs: SharedPreferences? = null
    fun init(p: SharedPreferences) {
        if (prefs != null) return
        prefs = p
        val a = JSONArray(p.getString("mem", "[]"))
        for (i in 0 until a.length()) items.add(a.getString(i))
    }
    private fun save() { prefs?.edit()?.putString("mem", JSONArray(items.toList()).toString())?.apply() }
    fun add(t: String): String {
        val s = t.trim()
        if (s.isEmpty()) return "nothing to save"
        if (items.any { it.equals(s, true) }) return "already remembered"
        items.add(s); save(); return "remembered"
    }
    fun forget(q: String): String {
        val n = items.count { it.contains(q, true) }
        items.removeAll { it.contains(q, true) }; save(); return "forgot $n"
    }
    fun removeAt(i: Int) { if (i in items.indices) { items.removeAt(i); save() } }
    fun clear() { items.clear(); save() }
    fun prompt(): String = if (items.isEmpty()) "" else "\n\nThings you remember about the user:\n" + items.joinToString("\n") { "- $it" }
}

object ImageOps {
    private fun filtered(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }
    private fun mat(src: Bitmap, m: Matrix) = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

    fun apply(src: Bitmap, op: String, amt: Double): Bitmap? {
        val a = if (amt.isNaN()) null else amt
        return when (op) {
            "rotate" -> mat(src, Matrix().apply { postRotate((a ?: 90.0).toFloat()) })
            "flip_horizontal" -> mat(src, Matrix().apply { postScale(-1f, 1f) })
            "flip_vertical" -> mat(src, Matrix().apply { postScale(1f, -1f) })
            "grayscale" -> filtered(src, ColorMatrix().apply { setSaturation(0f) })
            "saturation" -> filtered(src, ColorMatrix().apply { setSaturation((1 + (a ?: 30.0) / 100).toFloat().coerceAtLeast(0f)) })
            "sepia" -> filtered(src, ColorMatrix(floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            "invert" -> filtered(src, ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
            "brightness" -> { val d = ((a ?: 20.0) * 2.55).toFloat(); filtered(src, ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, d, 0f, 1f, 0f, 0f, d, 0f, 0f, 1f, 0f, d, 0f, 0f, 0f, 1f, 0f))) }
            "contrast" -> { val s = (1 + (a ?: 20.0) / 100).toFloat().coerceAtLeast(0f); val t = 128f * (1 - s); filtered(src, ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, t, 0f, s, 0f, 0f, t, 0f, 0f, s, 0f, t, 0f, 0f, 0f, 1f, 0f))) }
            "crop_square" -> { val n = minOf(src.width, src.height); Bitmap.createBitmap(src, (src.width - n) / 2, (src.height - n) / 2, n, n) }
            "resize" -> { val m = (a ?: 1024.0).toInt().coerceIn(32, 4096); val sc = m.toFloat() / maxOf(src.width, src.height); Bitmap.createScaledBitmap(src, (src.width * sc).toInt().coerceAtLeast(1), (src.height * sc).toInt().coerceAtLeast(1), true) }
            else -> null
        }
    }
}

class Brain(private val tools: DeviceTools, private val prefs: SharedPreferences, private val appCtx: Context) {
    companion object {
        const val SYSTEM_LOCAL = "You are Ritchad, a polite, witty personal assistant running offline on the user's phone. " +
            "Answer in 1-3 short sentences, plain text. Use your tools for timers, alarms, opening apps, the flashlight, calls, battery and memory."
        const val MODEL = "gemini-2.5-flash"
        const val IMG_MODEL = "gemini-3.1-flash-image"
        const val SYSTEM = "You are Ritchad, a personal assistant in the style of Jarvis: composed, polite, quietly witty, efficient. " +
            "Replies are spoken aloud, so keep them to 1-3 short sentences, plain text. Use your tools when asked to act on the phone. " +
            "Never claim an action succeeded unless the tool result says so. " +
            "When the user shares something worth keeping (name, preferences, dates, people), call remember. Call forget when asked. " +
            "Attached images and PDFs are visible to you. For simple image changes use edit_image_local; for creative changes use edit_image_ai. " +
            "After an edit, say briefly what you did; the picture is shown to the user automatically."
    }

    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val contents = JSONArray()

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()): JSONObject {
        val params = JSONObject().put("type", "object").put("properties", props)
        if (props.length() > 0) params.put("required", JSONArray(req))
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.length() > 0) o.put("parameters", params)
        return o
    }
    private fun p(type: String, d: String) = JSONObject().put("type", type).put("description", d)

    private val declarations = JSONArray()
        .put(fn("set_timer", "Start a countdown timer in the clock app.", JSONObject().put("seconds", p("integer", "Duration in seconds")).put("label", p("string", "Label")), listOf("seconds")))
        .put(fn("set_alarm", "Set an alarm at a clock time (24h).", JSONObject().put("hour", p("integer", "0-23")).put("minute", p("integer", "0-59")).put("label", p("string", "Label")), listOf("hour", "minute")))
        .put(fn("open_app", "Open an installed app by its name.", JSONObject().put("name", p("string", "App name, e.g. Spotify")), listOf("name")))
        .put(fn("open_url", "Open a web address in the browser.", JSONObject().put("url", p("string", "URL")), listOf("url")))
        .put(fn("dial_number", "Open the dialer with a number ready to call.", JSONObject().put("number", p("string", "Phone number")), listOf("number")))
        .put(fn("flashlight", "Turn the flashlight on or off.", JSONObject().put("on", p("boolean", "true for on")), listOf("on")))
        .put(fn("get_status", "Get current time, date and battery level."))
        .put(fn("remember", "Save a short fact about the user for future conversations.", JSONObject().put("fact", p("string", "The fact")), listOf("fact")))
        .put(fn("forget", "Delete saved memories containing a keyword.", JSONObject().put("keyword", p("string", "Keyword")), listOf("keyword")))
        .put(fn("list_memories", "List everything remembered about the user."))
        .put(fn("edit_image_local", "Simple edit of the attached image. operation is one of: rotate, flip_horizontal, flip_vertical, grayscale, sepia, invert, brightness, contrast, saturation, crop_square, resize. amount: degrees for rotate (default 90), -100 to 100 for brightness/contrast/saturation, max side in pixels for resize.", JSONObject().put("operation", p("string", "Operation name")).put("amount", p("number", "Amount")), listOf("operation")))
        .put(fn("edit_image_ai", "Creative AI edit of the attached image from a text instruction, such as changing the background, adding objects or restyling.", JSONObject().put("prompt", p("string", "What to change")), listOf("prompt")))

    private fun inline(mime: String, bytes: ByteArray) =
        JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()

    private fun post(req: Request): Pair<String?, String?> {
        var last = ""
        for (i in 0..2) {
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body!!.string() to null
                    last = when (r.code) {
                        401, 403 -> "Google rejected the request (check the key, or this model may need billing enabled)."
                        404 -> "That model name wasn't found. Check it in Settings."
                        429 -> "I've hit the usage limit. Try again in a while."
                        500, 502, 503, 504 -> "Google's servers are busy. Please try again shortly."
                        else -> "Request failed (${r.code})."
                    }
                    if (r.code !in listOf(429, 500, 502, 503, 504)) return null to last
                }
            } catch (e: Exception) { last = "I can't reach my brain. Check your connection." }
            Thread.sleep(1500L * (i + 1))
        }
        return null to last
    }

    private fun local(name: String, a: JSONObject): String = when (name) {
        "remember" -> Mem.add(a.optString("fact"))
        "forget" -> Mem.forget(a.optString("keyword"))
        "list_memories" -> if (Mem.items.isEmpty()) "no memories yet" else Mem.items.joinToString("\n")
        "edit_image_local" -> {
            val src = Session.image
            if (src == null) "no image is attached; ask the user to attach one"
            else {
                val out = try { ImageOps.apply(src, a.optString("operation"), a.optDouble("amount", Double.NaN)) } catch (e: Throwable) { null }
                if (out == null) "unsupported operation or it failed"
                else { Session.image = out; Chat.addImage(out, "Edited: " + a.optString("operation")); "done; the edited image is shown to the user" }
            }
        }
        else -> tools.run(name, a)
    }

    private suspend fun aiEdit(prompt: String): String {
        val src = Session.image ?: return "no image is attached; ask the user to attach one"
        val key = prefs.getString("key", "")?.trim().orEmpty()
        val model = prefs.getString("imgmodel", IMG_MODEL)?.trim().orEmpty().ifBlank { IMG_MODEL }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(inline("image/jpeg", jpeg(src))))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val (raw, err) = post(req)
        if (raw == null) return "image edit failed: $err"
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return "the image model returned nothing"
        var bmp: Bitmap? = null
        for (i in 0 until parts.length()) {
            val pt = parts.getJSONObject(i)
            val d = (pt.optJSONObject("inlineData") ?: pt.optJSONObject("inline_data"))?.optString("data")
            if (!d.isNullOrEmpty()) { val bytes = Base64.decode(d, Base64.DEFAULT); bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); break }
        }
        val result: Bitmap = bmp ?: return "the image model returned no picture"
        withContext(Dispatchers.Main) { Session.image = result; Chat.addImage(result, "Edited: $prompt") }
        return "done; the edited image is shown to the user"
    }

    private fun online(): Boolean {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private suspend fun offlineAsk(text: String, att: Attachment?): String {
        val quick = withContext(Dispatchers.Main) { OfflineCommands.handle(text, tools) }
        if (quick != null) return quick
        if (!Local.installed(appCtx)) return "I'm offline and have no on-device model yet. Open Settings, then Offline brain, and download one while you have internet. Meanwhile I can still do timers, alarms, calls, apps and the flashlight."
        var q = text
        if (att != null) {
            if (att.mime.startsWith("image/") || att.mime == "application/pdf") return "I can't read images or PDFs while offline."
            q = "[File: ${att.name}]\n" + String(att.bytes, Charsets.UTF_8).take(5000) + "\n\n" + text
        }
        return try { Local.ask(appCtx, q, tools) } catch (t: Throwable) { "My on-device brain couldn't start: ${t.message}" }
    }

    suspend fun ask(text: String, att: Attachment? = null): String = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("forceoffline", false) || !online()) return@withContext offlineAsk(text, att)
        val key = prefs.getString("key", "")?.trim().orEmpty()
        val model = prefs.getString("model", MODEL)?.trim().orEmpty().ifBlank { MODEL }
        if (key.isBlank()) return@withContext "I need your Gemini key first. Tap Settings."
        if (contents.length() > 24) repeat(contents.length()) { contents.remove(0) }
        val parts0 = JSONArray()
        var t = text
        if (att != null) {
            if (att.mime.startsWith("image/") || att.mime == "application/pdf") parts0.put(inline(att.mime, att.bytes))
            else t = "[Attached file: ${att.name}]\n" + String(att.bytes, Charsets.UTF_8).take(100_000) + "\n\n" + text
        }
        parts0.put(JSONObject().put("text", t))
        contents.put(JSONObject().put("role", "user").put("parts", parts0))
        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM + Mem.prompt()))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val (raw, err) = post(req)
            if (raw == null) return@withContext if (err?.startsWith("I can't reach") == true) offlineAsk(text, att) else (err ?: "Request failed.")
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return@withContext "I got an empty answer. Try again."
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val calls = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.has("functionCall") }
            if (calls.isEmpty()) return@withContext (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim().ifBlank { "Done." }
            val results = JSONArray()
            for (c in calls) {
                val f = c.getJSONObject("functionCall")
                val nm = f.getString("name")
                val args = f.optJSONObject("args") ?: JSONObject()
                val out = if (nm == "edit_image_ai") aiEdit(args.optString("prompt")) else withContext(Dispatchers.Main) { local(nm, args) }
                results.put(JSONObject().put("functionResponse", JSONObject().put("name", nm).put("response", JSONObject().put("result", out))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", results))
        }
        "I got tangled up in my own tools. Please rephrase."
    }
}


object Local {
    const val DEFAULT_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    private var engine: Engine? = null
    private var conv: Conversation? = null
    private var turns = 0
    private var memSig = ""

    fun file(ctx: Context) = File(ctx.filesDir, "model.litertlm")
    fun installed(ctx: Context) = file(ctx).exists()

    @Synchronized
    fun ask(ctx: Context, text: String, d: DeviceTools): String {
        val e = engine ?: Engine(EngineConfig(modelPath = file(ctx).path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path)).also { it.initialize(); engine = it }
        val sig = Mem.items.joinToString("|")
        var c = conv
        if (c == null || turns >= 6 || sig != memSig) {
            c?.close()
            c = e.createConversation(ConversationConfig(
                systemInstruction = Contents.of(Brain.SYSTEM_LOCAL + Mem.prompt()),
                tools = listOf(tool(PhoneTools(d))),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            ))
            conv = c; memSig = sig; turns = 0
        }
        turns++
        return try {
            c.sendMessage(text).toString().trim().ifBlank { "Done." }
        } catch (t: Throwable) {
            try { c.close() } catch (_: Throwable) {}
            conv = null
            "My on-device brain stumbled: ${t.message}"
        }
    }

    @Synchronized
    fun release() {
        try { conv?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conv = null; engine = null
    }

    suspend fun download(ctx: Context, url: String, progress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val part = File(ctx.filesDir, "model.part")
        val have = if (part.exists()) part.length() else 0L
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val rb = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }
        try {
            client.newCall(rb.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) return@withContext "That download needs a Hugging Face login. Try another model URL."
                if (r.code == 416) { part.delete(); return@withContext "Partial file was invalid. Tap Download again." }
                if (!r.isSuccessful) return@withContext "Download failed (${r.code})."
                val resumed = r.code == 206
                val total = r.body!!.contentLength() + (if (resumed) have else 0L)
                if (!resumed && part.exists()) part.delete()
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = if (resumed) have else 0L
                    r.body!!.byteStream().use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n; progress(got, total)
                        }
                    }
                }
            }
            release()
            val done = file(ctx)
            if (done.exists()) done.delete()
            part.renameTo(done)
            ""
        } catch (e: Exception) { "Download interrupted (${e.message}). Tap Download to resume." }
    }
}

class PhoneTools(private val d: DeviceTools) : ToolSet {
    private val main = Handler(Looper.getMainLooper())

    @Tool(description = "Start a countdown timer on the phone.")
    fun setTimer(@ToolParam(description = "Duration in seconds") seconds: Int): String = d.run("set_timer", JSONObject().put("seconds", seconds))

    @Tool(description = "Set an alarm at a clock time.")
    fun setAlarm(@ToolParam(description = "Hour 0-23") hour: Int, @ToolParam(description = "Minute 0-59") minute: Int): String =
        d.run("set_alarm", JSONObject().put("hour", hour).put("minute", minute))

    @Tool(description = "Open an installed app by name.")
    fun openApp(@ToolParam(description = "App name") name: String): String = d.run("open_app", JSONObject().put("name", name))

    @Tool(description = "Turn the flashlight on or off.")
    fun flashlight(@ToolParam(description = "true for on, false for off") on: Boolean): String = d.run("flashlight", JSONObject().put("on", on))

    @Tool(description = "Open the dialer with a phone number.")
    fun dialNumber(@ToolParam(description = "Phone number") number: String): String = d.run("dial_number", JSONObject().put("number", number))

    @Tool(description = "Get the current time, date and battery level.")
    fun getStatus(): String = d.run("get_status", JSONObject())

    @Tool(description = "Save a short fact about the user for later.")
    fun remember(@ToolParam(description = "The fact") fact: String): String { main.post { Mem.add(fact) }; return "remembered" }

    @Tool(description = "Delete saved memories containing a keyword.")
    fun forget(@ToolParam(description = "Keyword") keyword: String): String { main.post { Mem.forget(keyword) }; return "forgotten" }

    @Tool(description = "List what is remembered about the user.")
    fun listMemories(): String = if (Mem.items.isEmpty()) "nothing yet" else Mem.items.joinToString("; ")
}

object OfflineCommands {
    private val words = mapOf("a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0, "forty five" to 45.0, "sixty" to 60.0)

    private fun duration(t: String): Int? {
        if (Regex("""half an? hour""").containsMatchIn(t)) return 1800
        val m = Regex("""\b(\d+(?:\.\d+)?|forty five|fifteen|twenty|thirty|forty|sixty|an?|one|two|three|four|five|six|seven|eight|nine|ten)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b""").find(t) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: words[m.groupValues[1]] ?: return null
        val u = m.groupValues[2]
        val mult = if (u.startsWith("h")) 3600 else if (u.startsWith("m")) 60 else 1
        return (n * mult).toInt()
    }

    private fun human(sec: Int): String = when {
        sec % 3600 == 0 -> "${sec / 3600} hour" + (if (sec / 3600 == 1) "" else "s")
        sec % 60 == 0 -> "${sec / 60} minute" + (if (sec / 60 == 1) "" else "s")
        else -> "$sec seconds"
    }

    fun handle(raw: String, d: DeviceTools): String? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        Regex("""^\s*(?:please\s+)?remember(?:\s+that)?\s+(.+)""", RegexOption.IGNORE_CASE).find(raw.trim())?.let {
            return if (Mem.add(it.groupValues[1]) == "remembered") "Noted. I'll remember that." else "I already know that."
        }
        Regex("""^\s*(?:please\s+)?forget(?:\s+about)?\s+(.+)""", RegexOption.IGNORE_CASE).find(raw.trim())?.let {
            val r = Mem.forget(it.groupValues[1]).removePrefix("forgot ").toIntOrNull() ?: 0
            return if (r > 0) "Done. I've forgotten $r item" + (if (r == 1) "." else "s.") else "I had nothing like that."
        }
        if (t.contains("what do you remember") || t.contains("what do you know about me"))
            return if (Mem.items.isEmpty()) "I don't have any memories yet." else "I remember: " + Mem.items.joinToString("; ") + "."
        if (t.contains("timer") || t.contains("remind me in")) {
            val s = duration(t) ?: return "How long should the timer run?"
            d.run("set_timer", JSONObject().put("seconds", s))
            return "Timer set for ${human(s)}."
        }
        if (t.contains("alarm") || t.contains("wake me")) {
            val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?""").find(t) ?: return "What time should I set it for?"
            var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].ifEmpty { "0" }.toInt(); val ap = m.groupValues[3]
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            if (h !in 0..23 || mi !in 0..59) return "That time doesn't look right."
            d.run("set_alarm", JSONObject().put("hour", h).put("minute", mi))
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "Alarm set for %d:%02d %s.".format(h12, mi, if (h < 12) "AM" else "PM")
        }
        if (t.contains("flashlight") || t.contains("torch")) {
            val on = !(t.contains(" off") || t.contains("turn off"))
            val r = d.run("flashlight", JSONObject().put("on", on))
            return if (r == "ok") (if (on) "Flashlight on." else "Flashlight off.") else "I couldn't reach the flashlight."
        }
        Regex("""\b(?:call|dial)\s+(\+?\d[\d\s\-()]{4,})""").find(t)?.let {
            d.run("dial_number", JSONObject().put("number", it.groupValues[1].trim()))
            return "Opening the dialer for ${it.groupValues[1].trim()}."
        }
        Regex("""^(?:please\s+)?(?:open|launch|start)\s+(.+)$""").find(t)?.let {
            val target = it.groupValues[1].removePrefix("the ").removeSuffix(" app").trim()
            val isUrl = Regex("""^[\w.-]+\.[a-z]{2,}(/\S*)?$""").matches(target)
            val r = if (isUrl) d.run("open_url", JSONObject().put("url", target)) else d.run("open_app", JSONObject().put("name", target))
            return when { r.startsWith("no installed app") -> "I couldn't find $target on this phone."; r.startsWith("failed") -> "I couldn't open $target."; else -> "Opening $target." }
        }
        if (Regex("""what(?:'s| is)? the time|what time is it|current time""").containsMatchIn(t))
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        if (Regex("""today's date|what(?:'s| is)? the date|what day is it|what is today""").containsMatchIn(t))
            return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."
        if (t.contains("battery")) {
            val p = Regex("""battery=(\d+%)""").find(d.run("get_status", JSONObject()))?.groupValues?.get(1)
            return if (p != null) "Your battery is at $p." else "I couldn't read the battery."
        }
        return null
    }
}
