package com.plugin.alerm

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * ローカルユニットテスト（開発マシン上で実行）。
 * Android デバイスや emulator は不要。
 * AlarmManager の定数は compile-time constant (int) なので JVM 上でも評価可能。
 */
class AlermPluginUnitTest {

    // =========================================================================
    // parseAlarmType
    // =========================================================================

    @Test
    fun parseAlarmType_rtcWakeup_returnsRtcWakeup() {
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType("RTC_WAKEUP"))
    }

    @Test
    fun parseAlarmType_rtc_returnsRtc() {
        assertEquals(AlarmManager.RTC, parseAlarmType("RTC"))
    }

    @Test
    fun parseAlarmType_elapsedRealtimeWakeup_returnsElapsedRealtimeWakeup() {
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, parseAlarmType("ELAPSED_REALTIME_WAKEUP"))
    }

    @Test
    fun parseAlarmType_elapsedRealtime_returnsElapsedRealtime() {
        assertEquals(AlarmManager.ELAPSED_REALTIME, parseAlarmType("ELAPSED_REALTIME"))
    }

    @Test
    fun parseAlarmType_emptyString_defaultsToRtcWakeup() {
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType(""))
    }

    @Test
    fun parseAlarmType_unknownString_defaultsToRtcWakeup() {
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType("UNKNOWN_TYPE"))
    }

    @Test
    fun parseAlarmType_caseSensitive_lowercaseDefaultsToRtcWakeup() {
        // 小文字は認識しない → デフォルト
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType("rtc_wakeup"))
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType("rtc"))
        assertEquals(AlarmManager.RTC_WAKEUP, parseAlarmType("elapsed_realtime"))
    }

    @Test
    fun parseAlarmType_allValidTypes_returnDistinctValues() {
        val rtcWakeup   = parseAlarmType("RTC_WAKEUP")
        val rtc         = parseAlarmType("RTC")
        val erWakeup    = parseAlarmType("ELAPSED_REALTIME_WAKEUP")
        val er          = parseAlarmType("ELAPSED_REALTIME")

        // 4 種類がすべて異なる値であることを確認
        val set = setOf(rtcWakeup, rtc, erWakeup, er)
        assertEquals(4, set.size)
    }

    // AlarmManager 定数の実際の値を固定して、Android SDK のバージョン変更に気付けるようにする
    @Test
    fun alarmManagerConstants_haveExpectedValues() {
        assertEquals(0, AlarmManager.RTC_WAKEUP)
        assertEquals(1, AlarmManager.RTC)
        assertEquals(2, AlarmManager.ELAPSED_REALTIME_WAKEUP)
        assertEquals(3, AlarmManager.ELAPSED_REALTIME)
    }

    // =========================================================================
    // calculateEffectiveTriggerTime
    // =========================================================================

    /** 未来のアラームはそのまま返す（繰り返しなし） */
    @Test
    fun calculateEffectiveTriggerTime_futureAlarm_oneshotReturnsOriginal() {
        val now = 1_000_000L
        val trigger = 2_000_000L
        val result = calculateEffectiveTriggerTime(trigger, null, now)
        assertEquals(trigger, result)
    }

    /** 未来のアラームはそのまま返す（繰り返しあり） */
    @Test
    fun calculateEffectiveTriggerTime_futureAlarm_repeatingReturnsOriginal() {
        val now = 1_000L
        val trigger = 5_000L
        val interval = 60_000L
        val result = calculateEffectiveTriggerTime(trigger, interval, now)
        assertEquals(trigger, result)
    }

    /** 過去の一度きりアラームもそのまま返す（呼び出し側でスキップ判断） */
    @Test
    fun calculateEffectiveTriggerTime_pastAlarm_oneshotReturnsOriginal() {
        val now = 5_000L
        val trigger = 1_000L
        val result = calculateEffectiveTriggerTime(trigger, null, now)
        assertEquals(trigger, result)
    }

    /** 過去の繰り返しアラーム: interval ちょうど経過 → 次の interval を返す */
    @Test
    fun calculateEffectiveTriggerTime_pastRepeating_exactlyOneInterval() {
        val interval = 60_000L
        val trigger = 0L
        val now = interval          // 1 interval 経過
        // intervals = (60000 - 0) / 60000 + 1 = 2
        val expected = trigger + 2 * interval
        assertEquals(expected, calculateEffectiveTriggerTime(trigger, interval, now))
    }

    /** 過去の繰り返しアラーム: 2.5 interval 経過 → 3 回目に繰り上げ */
    @Test
    fun calculateEffectiveTriggerTime_pastRepeating_twoAndHalfIntervals() {
        val interval = 60_000L
        val trigger = 0L
        val now = (2.5 * interval).toLong()  // 150_000
        // intervals = 150000 / 60000 + 1 = 3
        val expected = trigger + 3 * interval
        assertEquals(expected, calculateEffectiveTriggerTime(trigger, interval, now))
    }

    /** 次回の発火時刻が now より未来であることを保証 */
    @Test
    fun calculateEffectiveTriggerTime_result_isAlwaysInFuture() {
        val interval = 10_000L
        val now = 100_000L

        // trigger が now より前の様々なケース
        listOf(0L, 5_000L, 50_000L, 99_999L).forEach { trigger ->
            val result = calculateEffectiveTriggerTime(trigger, interval, now)
            assertTrue(
                "trigger=$trigger, now=$now, result=$result は now より未来のはず",
                result > now
            )
        }
    }

    /** trigger == now の場合は "過去" として扱い、次の interval に繰り上げ */
    @Test
    fun calculateEffectiveTriggerTime_triggerEqualsNow_repeating_advancesToNextInterval() {
        val interval = 5_000L
        val trigger = 1_000L
        val now = 1_000L          // trigger == now
        // intervals = (1000 - 1000) / 5000 + 1 = 1
        val expected = trigger + 1 * interval
        assertEquals(expected, calculateEffectiveTriggerTime(trigger, interval, now))
    }

    /** interval が非常に小さい（1ms）エッジケース */
    @Test
    fun calculateEffectiveTriggerTime_tinyInterval_returnsCorrectNext() {
        val interval = 1L
        val trigger = 0L
        val now = 100L
        // intervals = (100 - 0) / 1 + 1 = 101
        val expected = trigger + 101 * interval
        assertEquals(expected, calculateEffectiveTriggerTime(trigger, interval, now))
    }

    /** trigger がゼロ (epoch) でも動作する */
    @Test
    fun calculateEffectiveTriggerTime_epochTrigger_repeating() {
        val interval = 86_400_000L  // 1 day
        val trigger = 0L
        val now = 1_000L            // 1 秒後
        // intervals = (1000 - 0) / 86400000 + 1 = 1
        val expected = trigger + 1 * interval
        assertEquals(expected, calculateEffectiveTriggerTime(trigger, interval, now))
    }

    // =========================================================================
    // nextTriggerForDaysOfWeek
    // =========================================================================

    /**
     * テスト用ヘルパー: 指定した曜日・時刻の Calendar を作成する。
     * @param dayOfWeek Calendar.SUNDAY～Calendar.SATURDAY
     */
    private fun calendarAt(dayOfWeek: Int, hour: Int, minute: Int, second: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

    /**
     * 次の月曜日の発火時刻が正しく計算されること。
     * from が火曜日なら "来週月曜" が返る。
     */
    @Test
    fun nextTriggerForDaysOfWeek_nextMonday_returnsCorrectTimestamp() {
        // 火曜日の正午を "from" にセット（アプリ曜日: 2）
        val from = calendarAt(Calendar.TUESDAY, 12, 0)
        // triggerAtMs: 水曜 08:00 の時刻情報（時刻のベース）
        val trigger = calendarAt(Calendar.WEDNESDAY, 8, 0)

        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(1), // 月曜のみ
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        // 結果の曜日が月曜（Calendar.MONDAY = 2）であること
        assertEquals(Calendar.MONDAY, resultCal.get(Calendar.DAY_OF_WEEK))
        // 結果の時刻が triggerAtMs の時・分と一致すること
        assertEquals(8, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        // 結果が from より未来であること
        assertTrue(result > from.timeInMillis)
    }

    /**
     * 複数の曜日が指定されたとき、最も近い曜日の発火時刻が返ること。
     */
    @Test
    fun nextTriggerForDaysOfWeek_multipleDays_returnsNearest() {
        // 月曜日 10:00 を "from" にセット
        val from = calendarAt(Calendar.MONDAY, 10, 0)
        // triggerAtMs: 月曜 07:00（今日の発火時刻より前 → 過去）
        val trigger = calendarAt(Calendar.MONDAY, 7, 0)

        // 月(1)・水(3)・金(5) → 次は水曜のはず
        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(1, 3, 5),
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.WEDNESDAY, resultCal.get(Calendar.DAY_OF_WEEK))
        assertTrue(result > from.timeInMillis)
    }

    /**
     * 毎日繰り返す場合、翌日が返ること。
     */
    @Test
    fun nextTriggerForDaysOfWeek_allDays_returnsTomorrow() {
        val from = calendarAt(Calendar.WEDNESDAY, 20, 0)
        // 今日の 07:00 が triggerAtMs → 過去
        val trigger = calendarAt(Calendar.WEDNESDAY, 7, 0)

        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(0, 1, 2, 3, 4, 5, 6),
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        // 翌日（木曜）の 07:00 が返るはず
        assertEquals(Calendar.THURSDAY, resultCal.get(Calendar.DAY_OF_WEEK))
        assertTrue(result > from.timeInMillis)
    }

    /**
     * days が空のとき IllegalArgumentException がスローされること。
     */
    @Test(expected = IllegalArgumentException::class)
    fun nextTriggerForDaysOfWeek_emptyDays_throwsException() {
        nextTriggerForDaysOfWeek(
            triggerAtMs = System.currentTimeMillis(),
            days = emptyList(),
            fromMs = System.currentTimeMillis(),
        )
    }

    /**
     * 返る時刻は常に fromMs より未来であること。
     */
    @Test
    fun nextTriggerForDaysOfWeek_result_isAlwaysFuture() {
        val from = calendarAt(Calendar.FRIDAY, 23, 59)
        val trigger = calendarAt(Calendar.FRIDAY, 6, 0)

        // 金(5)のみ → 来週金曜が返るはず
        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(5),
            fromMs = from.timeInMillis,
        )

        assertTrue("result ($result) > from (${from.timeInMillis}) のはず", result > from.timeInMillis)
    }

    /**
     * 返る時刻の時・分・秒が triggerAtMs の値と一致すること。
     */
    @Test
    fun nextTriggerForDaysOfWeek_preservesTimeOfDay() {
        val from = calendarAt(Calendar.SUNDAY, 0, 0)
        val trigger = calendarAt(Calendar.MONDAY, 6, 30, 15)

        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(1), // 月曜
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(6, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, resultCal.get(Calendar.MINUTE))
        assertEquals(15, resultCal.get(Calendar.SECOND))
    }

    /**
     * 0..6 外の曜日値（例: -1, 7）を渡したとき IllegalArgumentException がスローされること。
     */
    @Test(expected = IllegalArgumentException::class)
    fun nextTriggerForDaysOfWeek_invalidDayValue_throwsIllegalArgument() {
        nextTriggerForDaysOfWeek(
            triggerAtMs = System.currentTimeMillis(),
            days = listOf(1, 7), // 7 は無効
            fromMs = System.currentTimeMillis(),
        )
    }

    /**
     * 重複した曜日値を渡しても正しく動作すること（重複排除後に通常処理される）。
     */
    @Test
    fun nextTriggerForDaysOfWeek_duplicateDays_deduplicates() {
        // 月曜 10:00 を from にセット
        val from = calendarAt(Calendar.MONDAY, 10, 0)
        val trigger = calendarAt(Calendar.MONDAY, 7, 0)

        // 月(1)・月(1) → 重複しているが次の水曜(3)が返るべき
        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(1, 1, 3), // 1 が重複
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.WEDNESDAY, resultCal.get(Calendar.DAY_OF_WEEK))
        assertTrue(result > from.timeInMillis)
    }

    /**
     * 同じ曜日のみ指定し、当日の時刻が既に過ぎている場合、offset=7（来週）が返ること。
     */
    @Test
    fun nextTriggerForDaysOfWeek_singleDayAlreadyPassed_returnsNextWeek() {
        // 金曜 23:59 を from にセット（金(5)のみ指定、今日の時刻は過ぎている）
        val from = calendarAt(Calendar.FRIDAY, 23, 59)
        val trigger = calendarAt(Calendar.FRIDAY, 6, 0)

        val result = nextTriggerForDaysOfWeek(
            triggerAtMs = trigger.timeInMillis,
            days = listOf(5), // 金曜のみ
            fromMs = from.timeInMillis,
        )

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        // 来週金曜が返るはず
        assertEquals(Calendar.FRIDAY, resultCal.get(Calendar.DAY_OF_WEEK))
        assertTrue(result > from.timeInMillis)
        // 7日後以上
        assertTrue(result >= from.timeInMillis + 6 * 24 * 60 * 60 * 1000L)
    }
}
