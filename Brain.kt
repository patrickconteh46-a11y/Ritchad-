package com.ritchad.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Brain(private val tools: DeviceTools, private val prefs: android.content.SharedPreferences) {
    companion object {
        const val MODEL = "gemini-2.5-flash"
        const val SYSTEM = "You are Ritchad, a personal assistant in the style of Jarvis: composed, polite, quietly witty, efficient. " +
            "Replies are spoken aloud, so keep them to 1-3 short sentences, plain text. Use your tools when asked to act on the phone. " +
            "Never claim an action succeeded unless the tool result says so."
    }

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
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

    suspend fun ask(text: String): String = withContext(Dispatchers.IO) {
        val key = prefs.getString("key", "")?.trim().orEmpty()
        val model = prefs.getString("model", MODEL)?.trim().orEmpty().ifBlank { MODEL }
        if (key.isBlank()) return@withContext "I need your Gemini key first. Tap Settings."
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        repeat(5) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val raw = try { http.newCall(req).execute().use { r -> if (!r.isSuccessful) return@withContext "Request failed (${r.code}). Check your API key and model name."; r.body!!.string() } }
                      catch (e: Exception) { return@withContext "I can't reach my brain: ${e.message}" }
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return@withContext "I got an empty answer. Try again."
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val calls = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.has("functionCall") }
            if (calls.isEmpty()) return@withContext (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            val results = JSONArray()
            for (c in calls) {
                val f = c.getJSONObject("functionCall")
                val out = tools.run(f.getString("name"), f.optJSONObject("args") ?: JSONObject())
                results.put(JSONObject().put("functionResponse", JSONObject().put("name", f.getString("name")).put("response", JSONObject().put("result", out))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", results))
        }
        "I got tangled up in my own tools. Please rephrase."
    }
}
