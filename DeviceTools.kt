package com.ritchad.app

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class DeviceTools(private val ctx: Context) {
    private fun launch(i: Intent): String = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
    } catch (e: Exception) { "failed: ${e.message}" }

    fun run(name: String, a: JSONObject): String = when (name) {
        "set_timer" -> launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Timer"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        "set_alarm" -> launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Alarm"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        "open_app" -> {
            val want = a.optString("name").lowercase()
            val pm = ctx.packageManager
            val hit = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            if (hit == null) "no installed app matches '$want'"
            else launch(pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: Intent())
        }
        "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url").let { if (it.startsWith("http")) it else "https://$it" })))
        "dial_number" -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.optString("number"))))
        "flashlight" -> try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList.first(), a.optBoolean("on")); "ok"
        } catch (e: Exception) { "failed: ${e.message}" }
        "get_status" -> {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            "time=${DateFormat.getDateTimeInstance().format(Date())}, battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        }
        else -> "unknown tool"
    }
}
