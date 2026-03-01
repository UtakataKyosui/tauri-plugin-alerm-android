import { invoke } from '@tauri-apps/api/core'

/** スケジュール済みアラームの情報 */
export interface AlarmInfo {
  /** アラームの一意な識別子 */
  id: number
  /** 通知タイトル */
  title: string
  /** 通知本文 */
  message?: string
  /** 発火時刻（Unix タイムスタンプ、ミリ秒） */
  triggerAtMs: number
  /** アラームタイプ */
  alarmType: AlarmType
  /** 正確なアラームかどうか */
  exact: boolean
  /** 繰り返し間隔（ミリ秒）。undefined の場合は一度だけ発火 */
  repeatIntervalMs?: number
  /** アラーム音声ファイルのパス（assets 内）。未指定時はデフォルトアラーム音 */
  soundUri?: string
}

/** Android AlarmManager のアラームタイプ */
export type AlarmType =
  | 'RTC_WAKEUP'
  | 'RTC'
  | 'ELAPSED_REALTIME_WAKEUP'
  | 'ELAPSED_REALTIME'

/** setAlarm() のオプション */
export interface SetAlarmOptions {
  /** アラームの一意な識別子（同じ ID でセットすると上書き） */
  id: number
  /** 通知タイトル */
  title: string
  /** 通知本文 */
  message?: string
  /**
   * 発火時刻（Unix タイムスタンプ、ミリ秒）
   * 例: Date.now() + 60_000  // 1 分後
   */
  triggerAtMs: number
  /**
   * アラームタイプ（デフォルト: "RTC_WAKEUP"）
   * - RTC_WAKEUP: 指定した時刻にデバイスをウェイクアップして発火
   * - RTC: 指定した時刻に発火（デバイスをウェイクアップしない）
   * - ELAPSED_REALTIME_WAKEUP: 起動からの経過時間でウェイクアップして発火
   * - ELAPSED_REALTIME: 起動からの経過時間で発火（ウェイクアップしない）
   */
  alarmType?: AlarmType
  /**
   * 正確なアラームを使用する（デフォルト: true）
   * Android 12+ では SCHEDULE_EXACT_ALARM 権限が必要。
   * 権限がない場合は不正確なアラームにフォールバックする。
   */
  exact?: boolean
  /**
   * Doze モード中でも発火させる（デフォルト: true）
   * setExactAndAllowWhileIdle / setAndAllowWhileIdle を使用する。
   */
  allowWhileIdle?: boolean
  /**
   * 繰り返し間隔（ミリ秒）
   * 省略すると一度だけ発火する。
   * 繰り返しアラームは Android 4.4+ では不正確なアラームになる。
   */
  repeatIntervalMs?: number
  /**
   * アラーム音声ファイルのパス（アプリ assets 内）
   * 例: "sounds/alarm.mp3"
   * 省略時はシステムのデフォルトアラーム音を使用する。
   */
  soundUri?: string
}

/** checkExactAlarmPermission() のレスポンス */
export interface CheckPermissionResult {
  /** 正確なアラームをスケジュール可能かどうか。Android 11 以下では常に true */
  canScheduleExactAlarms: boolean
}

/**
 * アラームをスケジュールする。
 *
 * Android では AlarmManager を使用してアラームを設定し、
 * 発火時に通知を表示する。
 *
 * @example
 * ```ts
 * await setAlarm({
 *   id: 1,
 *   title: '起床時間です',
 *   message: 'おはようございます！',
 *   triggerAtMs: Date.now() + 8 * 60 * 60 * 1000,  // 8 時間後
 * })
 * ```
 */
export async function setAlarm(options: SetAlarmOptions): Promise<AlarmInfo> {
  return await invoke<AlarmInfo>('plugin:alerm|set_alarm', {
    payload: options,
  })
}

/**
 * スケジュール済みのアラームをキャンセルする。
 *
 * @param id キャンセルするアラームの ID
 */
export async function cancelAlarm(id: number): Promise<void> {
  await invoke<void>('plugin:alerm|cancel_alarm', {
    payload: { id },
  })
}

/**
 * 現在スケジュールされているアラームの一覧を取得する。
 */
export async function listAlarms(): Promise<AlarmInfo[]> {
  const result = await invoke<{ alarms: AlarmInfo[] }>('plugin:alerm|list_alarms', {
    payload: {},
  })
  return result.alarms
}

/**
 * 正確なアラームをスケジュールする権限があるか確認する。
 *
 * Android 12 (API 31) 以上では SCHEDULE_EXACT_ALARM 権限が必要。
 * Android 11 以下では常に true を返す。
 */
export async function checkExactAlarmPermission(): Promise<CheckPermissionResult> {
  return await invoke<CheckPermissionResult>(
    'plugin:alerm|check_exact_alarm_permission',
    { payload: {} }
  )
}

/**
 * 正確なアラームの権限設定画面を開く（Android 12+ のみ有効）。
 *
 * @example
 * ```ts
 * const { canScheduleExactAlarms } = await checkExactAlarmPermission()
 * if (!canScheduleExactAlarms) {
 *   await openExactAlarmSettings()
 * }
 * ```
 */
export async function openExactAlarmSettings(): Promise<void> {
  await invoke<void>('plugin:alerm|open_exact_alarm_settings', {
    payload: {},
  })
}
