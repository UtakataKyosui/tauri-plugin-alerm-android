package com.plugin.alerm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * AlarmManager からのブロードキャストを受信し、通知を表示する。
 * ファイル名は旧スキャフォールドのまま (Example.kt) だが、クラス名は AlarmReceiver。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarmId", 0)
        val title = intent.getStringExtra("title") ?: "Alarm"
        val message = intent.getStringExtra("message") ?: ""
        val soundUri = intent.getStringExtra("soundUri")

        // Android 8+ では通知チャンネルが必要
        // 音声は MediaPlayer で管理するため、チャンネルの通知音はサイレントにして二重鳴動を防ぐ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AlermPlugin.CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled alarm notifications"
                enableVibration(true)
                setSound(null, null)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        // 音声再生
        if (soundUri != null) {
            playAssetSound(context, soundUri)
        } else {
            playDefaultAlarmSound(context)
        }

        // タップ時にアプリを起動する PendingIntent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPendingIntent = launchIntent?.let {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(context, alarmId, it, flags)
        }

        val notification = NotificationCompat.Builder(context, AlermPlugin.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { if (contentPendingIntent != null) setContentIntent(contentPendingIntent) }
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alarmId, notification)
    }

    /**
     * MediaPlayer のセットアップと再生を行う共通ヘルパー。
     * - AudioAttributes / リスナーの設定を一元管理
     * - 初期化失敗（catch）・再生中エラー（onErrorListener）いずれも onErrorFallback を呼び出す
     * - リスナーは prepare()/start() より前に設定し、短音声でも release() を取りこぼさない
     *
     * @param dataSourceProvider MediaPlayer にデータソースを設定するラムダ
     * @param onErrorFallback エラー発生時のフォールバック処理（省略可）
     */
    private fun playSound(
        dataSourceProvider: (MediaPlayer) -> Unit,
        onErrorFallback: (() -> Unit)? = null,
    ) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    onErrorFallback?.invoke()
                    true
                }
                dataSourceProvider(this)
                prepare()
                start()
            }
        } catch (e: Exception) {
            mediaPlayer.release()
            onErrorFallback?.invoke()
        }
    }

    /**
     * assets フォルダ内の音声ファイルを再生する。
     * 初期化失敗・再生中デコードエラーともにデフォルト音にフォールバック。
     */
    private fun playAssetSound(context: Context, soundUri: String) {
        playSound(
            dataSourceProvider = { mp ->
                context.assets.openFd(soundUri).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            },
            onErrorFallback = { playDefaultAlarmSound(context) },
        )
    }

    /**
     * システムのデフォルトアラーム音を再生する。
     * getActualDefaultRingtoneUri() で端末に実際に設定されている URI を取得する。
     * アラーム音が未設定（null）の場合は無音で戻る。
     */
    private fun playDefaultAlarmSound(context: Context) {
        val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(
            context, RingtoneManager.TYPE_ALARM
        ) ?: return
        playSound(dataSourceProvider = { mp -> mp.setDataSource(context, uri) })
    }
}
