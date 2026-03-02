package com.plugin.alerm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Calendar

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
 * 指定した曜日リストのうち、fromMs 以降で最も近い発火時刻を返す。
 * 時・分・秒・ミリ秒は triggerAtMs の値を引き継ぐ。
 *
 * @param triggerAtMs 発火時刻の時・分・秒を取得するベース時刻（Unix ms）
 * @param days アプリ曜日値 (0=日, 1=月, ..., 6=土)
 * @param fromMs この時刻より後の発火時刻を探す（デフォルト: 現在時刻）
 * @throws IllegalArgumentException days が空、または 0..6 外の値が含まれる場合
 */
internal fun nextTriggerForDaysOfWeek(
    triggerAtMs: Long,
    days: List<Int>,
    fromMs: Long = System.currentTimeMillis(),
): Long {
    require(days.isNotEmpty()) { "days must not be empty" }
    require(days.all { it in 0..6 }) { "days must be in range 0..6, got $days" }
    val sortedDays = days.distinct()

    val base = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
    val baseHour = base.get(Calendar.HOUR_OF_DAY)
    val baseMinute = base.get(Calendar.MINUTE)
    val baseSecond = base.get(Calendar.SECOND)
    val baseMs = base.get(Calendar.MILLISECOND)

    // 0〜7日先を順に試して、候補曜日が sortedDays に含まれかつ fromMs より未来であれば返す
    // offset=7 まで見ることで、同一曜日のみ指定かつ当日の時刻が過ぎているケースも自然にカバー
    for (offset in 0..7) {
        val candidate = Calendar.getInstance().apply {
            timeInMillis = fromMs
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, baseHour)
            set(Calendar.MINUTE, baseMinute)
            set(Calendar.SECOND, baseSecond)
            set(Calendar.MILLISECOND, baseMs)
        }
        // Calendar.DAY_OF_WEEK は 1=日〜7=土、アプリ曜日 = DAY_OF_WEEK - 1
        val appDayOfWeek = candidate.get(Calendar.DAY_OF_WEEK) - 1
        if (appDayOfWeek in sortedDays && candidate.timeInMillis > fromMs) {
            return candidate.timeInMillis
        }
    }

    // ここには days が空でない限り到達しないはず
    throw IllegalStateException("No matching day found for days=$days from fromMs=$fromMs")
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
