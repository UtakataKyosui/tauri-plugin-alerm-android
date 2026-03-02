package com.plugin.alerm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * スヌーズボタンタップ時に呼ばれる BroadcastReceiver。
 * 現在の通知を消し、snoozeDurationMs 後に AlarmReceiver を再発火させる。
 *
 * 再スケジュール時は元のアラームの PendingIntent（requestCode = alarmId）を上書きしないよう、
 * SNOOZE_REQUEST_CODE_OFFSET を加えた別の requestCode を使用する。
 * これにより繰り返しアラームのスケジュールが維持される。
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarmId", -1)
        if (alarmId == -1) return  // 不正な alarmId は処理しない

        val snoozeDurationMs = intent.getLongExtra("snoozeDurationMs", AlermPlugin.DEFAULT_SNOOZE_DURATION_MS)
        val title = intent.getStringExtra("title") ?: "Alarm"
        val message = intent.getStringExtra("message") ?: ""
        val soundUri = intent.getStringExtra("soundUri")
        val alarmType = intent.getStringExtra("alarmType") ?: "RTC_WAKEUP"
        val snoozeLabel = intent.getStringExtra("snoozeLabel") ?: AlermPlugin.DEFAULT_SNOOZE_LABEL

        // 現在表示中の通知を消す
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(alarmId)

        // snoozeDurationMs 後に AlarmReceiver を再発火させる
        val triggerAtMs = System.currentTimeMillis() + snoozeDurationMs
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarmId", alarmId)
            putExtra("title", title)
            putExtra("message", message)
            putExtra("alarmType", alarmType)
            putExtra("snoozeEnabled", true)
            putExtra("snoozeDurationMs", snoozeDurationMs)
            putExtra("snoozeLabel", snoozeLabel)
            if (soundUri != null) putExtra("soundUri", soundUri)
        }
        // 元のアラーム（requestCode = alarmId）を上書きしないよう別の requestCode を使う
        val snoozeRequestCode = alarmId + AlermPlugin.SNOOZE_REQUEST_CODE_OFFSET
        val pendingIntent = PendingIntent.getBroadcast(
            context, snoozeRequestCode, alarmIntent,
            buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val parsedAlarmType = parseAlarmType(alarmType)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                // Android 12+ では SCHEDULE_EXACT_ALARM 権限がない場合にフォールバック
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(parsedAlarmType, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(parsedAlarmType, triggerAtMs, pendingIntent)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(parsedAlarmType, triggerAtMs, pendingIntent)
            }
            else -> alarmManager.setExact(parsedAlarmType, triggerAtMs, pendingIntent)
        }
    }
}
