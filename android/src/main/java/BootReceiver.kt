package com.plugin.alerm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

            // 一度きりのアラームで既に過去のものはスキップ
            if (triggerAtMs <= now && repeatIntervalMs == null) continue

            val title = alarm.optString("title", "Alarm")
            val message = alarm.optString("message", "")
            val alarmTypeName = alarm.optString("alarmType", "RTC_WAKEUP")
            val alarmType = parseAlarmType(alarmTypeName)
            val exact = alarm.optBoolean("exact", true)
            val allowWhileIdle = alarm.optBoolean("allowWhileIdle", true)
            val soundUri = alarm.optString("soundUri", null)
            val snoozeEnabled = alarm.optBoolean("snoozeEnabled", false)
            val snoozeDurationMs = alarm.optLong("snoozeDurationMs", AlermPlugin.DEFAULT_SNOOZE_DURATION_MS)
            val snoozeLabel = alarm.optString("snoozeLabel", AlermPlugin.DEFAULT_SNOOZE_LABEL)

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
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )

            // 繰り返しアラームは次回発火時刻を再計算する
            val effectiveTrigger = calculateEffectiveTriggerTime(triggerAtMs, repeatIntervalMs, now)

            when {
                repeatIntervalMs != null ->
                    alarmManager.setInexactRepeating(alarmType, effectiveTrigger, repeatIntervalMs, pendingIntent)
                exact ->
                    scheduleExactAlarm(alarmManager, alarmType, effectiveTrigger, pendingIntent, allowWhileIdle)
                else -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(alarmType, effectiveTrigger, pendingIntent)
                    } else {
                        alarmManager.set(alarmType, effectiveTrigger, pendingIntent)
                    }
                }
            }
        }
    }
}
