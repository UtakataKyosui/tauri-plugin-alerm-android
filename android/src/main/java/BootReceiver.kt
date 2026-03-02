package com.plugin.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
/**
 * 端末再起動後にアラームを復元する BroadcastReceiver。
 * AlarmManager のアラームはデバイス再起動で消えるため、
 * SharedPreferences に保存した情報を使い再スケジュールする。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            restoreAlarms(context)
        }
    }

    private fun restoreAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        for (alarm in getStoredAlarms(context)) {
            val id = alarm.getInt("id")
            val triggerAtMs = alarm.getLong("triggerAtMs")
            val repeatIntervalMs = if (alarm.isNull("repeatIntervalMs")) null
                                   else alarm.getLong("repeatIntervalMs")
            // 空配列は null 扱い（repeatDaysOfWeek が空でもクラッシュしないよう正規化）
            val repeatDaysOfWeek: List<Int>? = if (alarm.has("repeatDaysOfWeek") && !alarm.isNull("repeatDaysOfWeek")) {
                val arr = alarm.getJSONArray("repeatDaysOfWeek")
                if (arr.length() > 0) List(arr.length()) { arr.getInt(it) } else null
            } else null
            val originalTriggerAtMs = alarm.optLong("originalTriggerAtMs", triggerAtMs)

            // 一度きりのアラームで既に過去のものはスキップ（曜日繰り返しは常に復元）
            if (triggerAtMs <= now && repeatIntervalMs == null && repeatDaysOfWeek == null) continue

            val title = alarm.optString("title", "Alarm")
            val message = alarm.optString("message", "")
            val alarmTypeName = alarm.optString("alarmType", "RTC_WAKEUP")
            val alarmType = parseAlarmType(alarmTypeName)
            val exact = alarm.optBoolean("exact", true)
            val allowWhileIdle = alarm.optBoolean("allowWhileIdle", true)
            val soundUri = alarm.optString("soundUri", null)
            val snoozeEnabled = alarm.optBoolean("snoozeEnabled", false)
            val snoozeDurationMs = alarm.optLong("snoozeDurationMs", AlarmPlugin.DEFAULT_SNOOZE_DURATION_MS)
            val snoozeLabel = alarm.optString("snoozeLabel", AlarmPlugin.DEFAULT_SNOOZE_LABEL)

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarmId", id)
                putExtra("title", title)
                putExtra("message", message)
                putExtra("alarmType", alarmTypeName)
                putExtra("allowWhileIdle", allowWhileIdle)
                if (soundUri != null) putExtra("soundUri", soundUri)
                putExtra("snoozeEnabled", snoozeEnabled)
                putExtra("snoozeDurationMs", snoozeDurationMs)
                putExtra("snoozeLabel", snoozeLabel)
                putExtra("exact", exact)
                if (repeatDaysOfWeek != null) {
                    putExtra("repeatDaysOfWeek", repeatDaysOfWeek.toIntArray())
                    putExtra("originalTriggerAtMs", originalTriggerAtMs)
                }
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )

            // 次回発火時刻を計算する
            // repeatDaysOfWeek を優先し、両立時は repeatIntervalMs を無視（setAlarm と同じ制約）
            val effectiveTrigger = when {
                repeatDaysOfWeek != null -> {
                    try {
                        nextTriggerForDaysOfWeek(originalTriggerAtMs, repeatDaysOfWeek, now)
                    } catch (e: IllegalArgumentException) {
                        continue // 不正な repeatDaysOfWeek のアラームは復元スキップ
                    }
                }
                else ->
                    calculateEffectiveTriggerTime(triggerAtMs, repeatIntervalMs, now)
            }

            // SharedPreferences の triggerAtMs を次回発火時刻に同期的更新
            updateAlarmTriggerTime(context, id, effectiveTrigger)

            when {
                repeatIntervalMs != null && repeatDaysOfWeek == null -> {
                    // repeatDaysOfWeek が優先されるため、repeatIntervalMs は repeatDaysOfWeek がない場合のみ
                    alarmManager.setInexactRepeating(alarmType, effectiveTrigger, repeatIntervalMs, pendingIntent)
                }
                exact -> scheduleExactAlarm(alarmManager, alarmType, effectiveTrigger, pendingIntent, allowWhileIdle)
                else -> {
                    if (allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(alarmType, effectiveTrigger, pendingIntent)
                    } else {
                        alarmManager.set(alarmType, effectiveTrigger, pendingIntent)
                    }
                }
            }
        }
    }
}
