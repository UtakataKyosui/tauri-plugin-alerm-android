package com.plugin.alerm

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
}

@InvokeArg
class CancelAlarmArgs {
    var id: Int = 0
}

@TauriPlugin
class AlermPlugin(private val activity: Activity) : Plugin(activity) {

    companion object {
        const val PREFS_NAME = "tauri_alerm_alarms"
        // v2: setSound(null, null) を反映するためチャンネル ID を変更（Android は既存チャンネルの設定変更を無視するため）
        const val CHANNEL_ID = "tauri_alerm_channel_v2"
        const val DEFAULT_SNOOZE_DURATION_MS = 300_000L
        const val DEFAULT_SNOOZE_LABEL = "スヌーズ"
        /** スヌーズ再スケジュール用 PendingIntent の requestCode オフセット。元のアラームと衝突しないようにする */
        const val SNOOZE_REQUEST_CODE_OFFSET = 100_000
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
        val triggerAtMs = args.triggerAtMs
        val repeatIntervalMs = args.repeatIntervalMs

        val snoozeEnabled = args.snoozeEnabled ?: false
        val snoozeDurationMs = args.snoozeDurationMs ?: DEFAULT_SNOOZE_DURATION_MS
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
            exact -> {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (alarmManager.canScheduleExactAlarms()) {
                            if (allowWhileIdle) {
                                alarmManager.setExactAndAllowWhileIdle(alarmType, triggerAtMs, pendingIntent)
                            } else {
                                alarmManager.setExact(alarmType, triggerAtMs, pendingIntent)
                            }
                        } else {
                            // パーミッション未付与 → 不正確なアラームにフォールバック
                            if (allowWhileIdle) {
                                alarmManager.setAndAllowWhileIdle(alarmType, triggerAtMs, pendingIntent)
                            } else {
                                alarmManager.set(alarmType, triggerAtMs, pendingIntent)
                            }
                        }
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                        if (allowWhileIdle) {
                            alarmManager.setExactAndAllowWhileIdle(alarmType, triggerAtMs, pendingIntent)
                        } else {
                            alarmManager.setExact(alarmType, triggerAtMs, pendingIntent)
                        }
                    }
                    else -> alarmManager.setExact(alarmType, triggerAtMs, pendingIntent)
                }
            }
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
            put("repeatIntervalMs", repeatIntervalMs)
            put("soundUri", args.soundUri)
            put("snoozeEnabled", snoozeEnabled)
            put("snoozeDurationMs", snoozeDurationMs)
            put("snoozeLabel", snoozeLabel)
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
        invoke.resolve(ret)
    }

    @Command
    fun cancelAlarm(invoke: Invoke) {
        val args = invoke.parseArgs(CancelAlarmArgs::class.java)

        val intent = Intent(activity, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            activity, args.id, intent,
            buildPendingIntentFlags(PendingIntent.FLAG_NO_CREATE)
        )
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
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
