package com.plugin.alarm

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import org.json.JSONObject
import java.util.Calendar

@InvokeArg
class SetAlarmArgs {
    var id: Int = 0
    var title: String = ""
    var message: String? = null
    var triggerAtMs: Long = 0
    var alarmType: String? = null
    var exact: Boolean? = null
    var allowWhileIdle: Boolean? = null
    var repeatIntervalMs: Long? = null
    var soundUri: String? = null
    var snoozeEnabled: Boolean? = null
    var snoozeDurationMs: Long? = null
    var snoozeLabel: String? = null
    var repeatDaysOfWeek: ArrayList<Int>? = null
}

@InvokeArg
class CancelAlarmArgs {
    var id: Int = 0
}

@TauriPlugin
class AlarmPlugin(private val activity: Activity) : Plugin(activity) {

    companion object {
        const val PREFS_NAME = "tauri_alarm_alarms"
        // v2: setSound(null, null) を反映するためチャンネル ID を変更（Android は既存チャンネルの設定変更を無視するため）
        const val CHANNEL_ID = "tauri_alarm_channel_v2"
        const val DEFAULT_SNOOZE_DURATION_MS = 300_000L
        const val DEFAULT_SNOOZE_LABEL = "スヌーズ"
    }

    override fun load(webView: WebView) {
        super.load(webView)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled alarm notifications"
                enableVibration(true)
                // 音声は AlarmReceiver の MediaPlayer で管理するため、チャンネル通知音はサイレントにして二重鳴動を防ぐ
                setSound(null, null)
            }
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @Command
    fun setAlarm(invoke: Invoke) {
        val args = invoke.parseArgs(SetAlarmArgs::class.java)
        val exact = args.exact ?: true
        val allowWhileIdle = args.allowWhileIdle ?: true
        val alarmTypeName = args.alarmType ?: "RTC_WAKEUP"
        val alarmType = parseAlarmType(alarmTypeName)
        val repeatIntervalMs = args.repeatIntervalMs
        // 空配列は null と同等に扱う（null 正規化）
        val normalizedDays: List<Int>? = args.repeatDaysOfWeek?.takeIf { it.isNotEmpty() }

        // repeatIntervalMs と repeatDaysOfWeek の両立は禁止
        if (repeatIntervalMs != null && normalizedDays != null) {
            invoke.reject("repeatIntervalMs と repeatDaysOfWeek は同時に指定できません")
            return
        }

        // 曜日繰り返しの場合、発火時刻を次の指定曜日に必ず補正する
        val now = System.currentTimeMillis()
        val triggerAtMs = if (normalizedDays != null) {
            val dayOfWeekAtTrigger = Calendar.getInstance().apply { timeInMillis = args.triggerAtMs }
                .get(Calendar.DAY_OF_WEEK) - 1
            if (dayOfWeekAtTrigger in normalizedDays && args.triggerAtMs > now) {
                args.triggerAtMs
            } else {
                try {
                    nextTriggerForDaysOfWeek(args.triggerAtMs, normalizedDays, now)
                } catch (e: IllegalArgumentException) {
                    invoke.reject("無効な repeatDaysOfWeek パラメータ: ${e.message}")
                    return
                }
            }
        } else {
            args.triggerAtMs
        }

        val snoozeEnabled = args.snoozeEnabled ?: false
        val snoozeDurationMs = clampSnoozeDuration(args.snoozeDurationMs)
        val snoozeLabel = args.snoozeLabel ?: DEFAULT_SNOOZE_LABEL
        val intent = Intent(activity, AlarmReceiver::class.java).apply {
            putExtra("alarmId", args.id)
            putExtra("title", args.title)
            putExtra("message", args.message ?: "")
            if (args.soundUri != null) putExtra("soundUri", args.soundUri)
            putExtra("snoozeEnabled", snoozeEnabled)
            putExtra("snoozeDurationMs", snoozeDurationMs)
            putExtra("snoozeLabel", snoozeLabel)
            putExtra("alarmType", alarmTypeName)
            putExtra("exact", exact)
            putExtra("allowWhileIdle", allowWhileIdle)
            if (normalizedDays != null) {
                putExtra("repeatDaysOfWeek", normalizedDays.toIntArray())
                putExtra("originalTriggerAtMs", args.triggerAtMs)
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            activity, args.id, intent,
            buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        when {
            repeatIntervalMs != null -> {
                // 繰り返しアラーム（Android 4.4+ では不正確）
                alarmManager.setInexactRepeating(alarmType, triggerAtMs, repeatIntervalMs, pendingIntent)
            }
            exact -> scheduleExactAlarm(alarmManager, alarmType, triggerAtMs, pendingIntent, allowWhileIdle)
            else -> {
                if (allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(alarmType, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.set(alarmType, triggerAtMs, pendingIntent)
                }
            }
        }

        // 端末再起動後の復元用に SharedPreferences へ保存
        val alarmInfo = JSONObject().apply {
            put("id", args.id)
            put("title", args.title)
            put("message", args.message)
            put("triggerAtMs", triggerAtMs)
            put("alarmType", alarmTypeName)
            put("exact", exact)
            put("allowWhileIdle", allowWhileIdle)
            put("repeatIntervalMs", repeatIntervalMs)
            put("soundUri", args.soundUri)
            put("snoozeEnabled", snoozeEnabled)
            put("snoozeDurationMs", snoozeDurationMs)
            put("snoozeLabel", snoozeLabel)
            if (normalizedDays != null) {
                put("repeatDaysOfWeek", org.json.JSONArray(normalizedDays))
                put("originalTriggerAtMs", args.triggerAtMs)
            }
        }
        saveAlarm(activity, args.id, alarmInfo)

        val ret = JSObject()
        ret.put("id", args.id)
        ret.put("title", args.title)
        ret.put("message", args.message)
        ret.put("triggerAtMs", triggerAtMs)
        ret.put("alarmType", alarmTypeName)
        ret.put("exact", exact)
        if (repeatIntervalMs != null) ret.put("repeatIntervalMs", repeatIntervalMs)
        if (args.soundUri != null) ret.put("soundUri", args.soundUri)
        ret.put("snoozeEnabled", snoozeEnabled)
        ret.put("snoozeDurationMs", snoozeDurationMs)
        ret.put("snoozeLabel", snoozeLabel)
        if (normalizedDays != null) {
            ret.put("repeatDaysOfWeek", JSArray.from(normalizedDays))
        }
        invoke.resolve(ret)
    }

    @Command
    fun cancelAlarm(invoke: Invoke) {
        val args = invoke.parseArgs(CancelAlarmArgs::class.java)
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 通常アラームの PendingIntent をキャンセル
        val intent = Intent(activity, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            activity, args.id, intent,
            buildPendingIntentFlags(PendingIntent.FLAG_NO_CREATE)
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // スヌーズで再スケジュールされた PendingIntent も併せてキャンセル
        val snoozeIntent = Intent(activity, AlarmReceiver::class.java).apply {
            action = SNOOZE_ALARM_ACTION
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            activity, args.id, snoozeIntent,
            buildPendingIntentFlags(PendingIntent.FLAG_NO_CREATE)
        )
        if (snoozePendingIntent != null) {
            alarmManager.cancel(snoozePendingIntent)
            snoozePendingIntent.cancel()
        }

        removeAlarm(activity, args.id)
        invoke.resolve()
    }

    @Command
    fun listAlarms(invoke: Invoke) {
        val alarms = getStoredAlarms(activity)
        val arr = JSArray()
        for (alarm in alarms) {
            val obj = JSObject()
            obj.put("id", alarm.getInt("id"))
            obj.put("title", alarm.getString("title"))
            if (!alarm.isNull("message")) obj.put("message", alarm.getString("message"))
            obj.put("triggerAtMs", alarm.getLong("triggerAtMs"))
            obj.put("alarmType", alarm.getString("alarmType"))
            obj.put("exact", alarm.getBoolean("exact"))
            if (!alarm.isNull("repeatIntervalMs")) obj.put("repeatIntervalMs", alarm.getLong("repeatIntervalMs"))
            if (!alarm.isNull("soundUri")) obj.put("soundUri", alarm.getString("soundUri"))
            if (!alarm.isNull("snoozeEnabled")) obj.put("snoozeEnabled", alarm.getBoolean("snoozeEnabled"))
            if (!alarm.isNull("snoozeDurationMs")) obj.put("snoozeDurationMs", alarm.getLong("snoozeDurationMs"))
            if (!alarm.isNull("snoozeLabel")) obj.put("snoozeLabel", alarm.getString("snoozeLabel"))
            if (alarm.has("repeatDaysOfWeek") && !alarm.isNull("repeatDaysOfWeek")) {
                obj.put("repeatDaysOfWeek", JSArray.from(alarm.getJSONArray("repeatDaysOfWeek")))
            }
            arr.put(obj)
        }
        val ret = JSObject()
        ret.put("alarms", arr)
        invoke.resolve(ret)
    }

    @Command
    fun checkExactAlarmPermission(invoke: Invoke) {
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        val ret = JSObject()
        ret.put("canScheduleExactAlarms", canSchedule)
        invoke.resolve(ret)
    }

    @Command
    fun openExactAlarmSettings(invoke: Invoke) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
        invoke.resolve()
    }
}
