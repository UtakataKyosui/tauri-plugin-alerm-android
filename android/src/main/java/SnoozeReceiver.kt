package com.plugin.alerm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * スヌーズボタンタップ時に呼ばれる BroadcastReceiver。
 * 現在の通知を消し、snoozeDurationMs 後に AlarmReceiver を再発火させる。
 *
 * 再スケジュールする AlarmReceiver の Intent には [SNOOZE_ALARM_ACTION] を設定し、
 * 通常アラームの PendingIntent（アクションなし、requestCode = alarmId）と区別する。
 * これにより requestCode が同じでも別の PendingIntent として扱われ、
 * 繰り返しアラームのスケジュールを上書きしない。
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

        // alarmType に応じて基準時刻を切り替える（ELAPSED 系は elapsedRealtime() を使用）
        val triggerAtMs = when (alarmType) {
            "ELAPSED_REALTIME", "ELAPSED_REALTIME_WAKEUP" ->
                SystemClock.elapsedRealtime() + snoozeDurationMs
            else ->
                System.currentTimeMillis() + snoozeDurationMs
        }

        // SNOOZE_ALARM_ACTION を設定して通常アラームの PendingIntent と区別する
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = SNOOZE_ALARM_ACTION
            putExtra("alarmId", alarmId)
            putExtra("title", title)
            putExtra("message", message)
            putExtra("alarmType", alarmType)
            putExtra("snoozeEnabled", true)
            putExtra("snoozeDurationMs", snoozeDurationMs)
            putExtra("snoozeLabel", snoozeLabel)
            if (soundUri != null) putExtra("soundUri", soundUri)
        }
        // action が異なるため requestCode = alarmId でも通常アラームと衝突しない
        val pendingIntent = PendingIntent.getBroadcast(
            context, alarmId, alarmIntent,
            buildPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleExactAlarm(alarmManager, parseAlarmType(alarmType), triggerAtMs, pendingIntent)
    }
}
