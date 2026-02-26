package com.plugin.alerm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * アラームタイプ名を AlarmManager の定数に変換する。
 * 不明な文字列は RTC_WAKEUP (0) をデフォルトとして返す。
 */
internal fun parseAlarmType(typeName: String): Int = when (typeName) {
    "ELAPSED_REALTIME"         -> AlarmManager.ELAPSED_REALTIME
    "ELAPSED_REALTIME_WAKEUP"  -> AlarmManager.ELAPSED_REALTIME_WAKEUP
    "RTC"                      -> AlarmManager.RTC
    else                       -> AlarmManager.RTC_WAKEUP
}

/**
 * Android バージョンに応じた PendingIntent フラグを返す。
 * Android 6 (M) 以上では FLAG_IMMUTABLE を付与する。
 */
internal fun buildPendingIntentFlags(baseFlag: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        baseFlag or PendingIntent.FLAG_IMMUTABLE
    } else {
        baseFlag
    }

/** SharedPreferences にアラーム情報を保存（上書き）する */
internal fun saveAlarm(context: Context, id: Int, alarmInfo: JSONObject) {
    val prefs = context.getSharedPreferences(AlermPlugin.PREFS_NAME, Context.MODE_PRIVATE)
    val all = JSONObject(prefs.getString("alarms", "{}") ?: "{}")
    all.put(id.toString(), alarmInfo)
    prefs.edit().putString("alarms", all.toString()).apply()
}

/** SharedPreferences からアラーム情報を削除する */
internal fun removeAlarm(context: Context, id: Int) {
    val prefs = context.getSharedPreferences(AlermPlugin.PREFS_NAME, Context.MODE_PRIVATE)
    val all = JSONObject(prefs.getString("alarms", "{}") ?: "{}")
    all.remove(id.toString())
    prefs.edit().putString("alarms", all.toString()).apply()
}

/** SharedPreferences に保存されている全アラーム情報を取得する */
internal fun getStoredAlarms(context: Context): List<JSONObject> {
    val prefs = context.getSharedPreferences(AlermPlugin.PREFS_NAME, Context.MODE_PRIVATE)
    val all = JSONObject(prefs.getString("alarms", "{}") ?: "{}")
    val result = mutableListOf<JSONObject>()
    val keys = all.keys()
    while (keys.hasNext()) {
        result.add(all.getJSONObject(keys.next()))
    }
    return result
}

/**
 * 再起動後の実際の発火時刻を計算する。
 *
 * - 一度きりのアラームは [triggerAtMs] をそのまま返す
 * - 繰り返しアラームで [triggerAtMs] が過去の場合は、次回の発火時刻を計算して返す
 *
 * @param triggerAtMs 元の発火時刻（Unix タイムスタンプ ms）
 * @param repeatIntervalMs 繰り返し間隔（ms）。null の場合は一度きり
 * @param now 現在時刻（テスト時に差し替え可能。デフォルトは System.currentTimeMillis()）
 */
internal fun calculateEffectiveTriggerTime(
    triggerAtMs: Long,
    repeatIntervalMs: Long?,
    now: Long = System.currentTimeMillis(),
): Long {
    if (triggerAtMs > now || repeatIntervalMs == null) return triggerAtMs
    val intervals = ((now - triggerAtMs) / repeatIntervalMs) + 1
    return triggerAtMs + intervals * repeatIntervalMs
}
