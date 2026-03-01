package com.plugin.alerm

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

            // 一度きりのアラームで既に過去のものはスキップ
            if (triggerAtMs <= now && repeatIntervalMs == null) continue

            val title = alarm.optString("title", "Alarm")
            val message = alarm.optString("message", "")
            val alarmType = parseAlarmType(alarm.optString("alarmType", "RTC_WAKEUP"))
            val exact = alarm.optBoolean("exact", true)
            val soundUri = if (alarm.isNull("soundUri")) null else alarm.optString("soundUri")

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarmId", id)
                putExtra("title", title)
                putExtra("message", message)
                if (soundUri != null) putExtra("soundUri", soundUri)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )

            // 繰り返しアラームは次回発火時刻を再計算する
            val effectiveTrigger = calculateEffectiveTriggerTime(triggerAtMs, repeatIntervalMs, now)

            when {
                repeatIntervalMs != null -> {
                    alarmManager.setInexactRepeating(alarmType, effectiveTrigger, repeatIntervalMs, pendingIntent)
                }
                exact -> {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                            alarmManager.setExactAndAllowWhileIdle(alarmType, effectiveTrigger, pendingIntent)
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                            alarmManager.setExactAndAllowWhileIdle(alarmType, effectiveTrigger, pendingIntent)
                        }
                        else -> alarmManager.setExact(alarmType, effectiveTrigger, pendingIntent)
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(alarmType, effectiveTrigger, pendingIntent)
                    } else {
                        alarmManager.set(alarmType, effectiveTrigger, pendingIntent)
                    }
                }
            }
        }
    }
}
