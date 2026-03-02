package com.plugin.alerm

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    // clampSnoozeDuration
    // =========================================================================

    /** 正の値はそのまま返す */
    @Test
    fun clampSnoozeDuration_positive_returnsGivenValue() {
        assertEquals(60_000L, clampSnoozeDuration(60_000L))
    }

    /** 1ms の最小値も正常に返す */
    @Test
    fun clampSnoozeDuration_oneMs_returnsOneMs() {
        assertEquals(1L, clampSnoozeDuration(1L))
    }

    /** 0 はデフォルト値にフォールバック */
    @Test
    fun clampSnoozeDuration_zero_returnsDefault() {
        assertEquals(AlermPlugin.DEFAULT_SNOOZE_DURATION_MS, clampSnoozeDuration(0L))
    }

    /** 負の値はデフォルト値にフォールバック */
    @Test
    fun clampSnoozeDuration_negative_returnsDefault() {
        assertEquals(AlermPlugin.DEFAULT_SNOOZE_DURATION_MS, clampSnoozeDuration(-1L))
        assertEquals(AlermPlugin.DEFAULT_SNOOZE_DURATION_MS, clampSnoozeDuration(-300_000L))
        assertEquals(AlermPlugin.DEFAULT_SNOOZE_DURATION_MS, clampSnoozeDuration(Long.MIN_VALUE))
    }

    /** null はデフォルト値を返す */
    @Test
    fun clampSnoozeDuration_null_returnsDefault() {
        assertEquals(AlermPlugin.DEFAULT_SNOOZE_DURATION_MS, clampSnoozeDuration(null))
    }
}
