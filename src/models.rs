use serde::{Deserialize, Serialize};

/// アラームをセットするリクエスト
#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetAlarmRequest {
    /// アラームの一意な識別子（同じ ID でセットすると上書き）
    pub id: i32,
    /// 通知タイトル
    pub title: String,
    /// 通知本文（省略可）
    pub message: Option<String>,
    /// 発火時刻（Unix タイムスタンプ、ミリ秒）
    pub trigger_at_ms: i64,
    /// アラームタイプ: "RTC_WAKEUP"（デフォルト）| "RTC" | "ELAPSED_REALTIME_WAKEUP" | "ELAPSED_REALTIME"
    pub alarm_type: Option<String>,
    /// 正確なアラームを使用する（Android 12+ では SCHEDULE_EXACT_ALARM 権限が必要）。デフォルト: true
    pub exact: Option<bool>,
    /// Doze モード中でも発火させる（setExactAndAllowWhileIdle）。デフォルト: true
    pub allow_while_idle: Option<bool>,
    /// 繰り返し間隔（ミリ秒）。省略時は一度だけ発火
    pub repeat_interval_ms: Option<i64>,
}

/// スケジュール済みアラームの情報
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmInfo {
    pub id: i32,
    pub title: String,
    pub message: Option<String>,
    pub trigger_at_ms: i64,
    pub alarm_type: String,
    pub exact: bool,
    pub repeat_interval_ms: Option<i64>,
}

/// アラームをキャンセルするリクエスト
#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelAlarmRequest {
    pub id: i32,
}

/// アラーム一覧レスポンス
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ListAlarmsResponse {
    pub alarms: Vec<AlarmInfo>,
}

/// 正確なアラーム権限チェックのレスポンス
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckPermissionResponse {
    pub can_schedule_exact_alarms: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------
    // SetAlarmRequest
    // ------------------------------------------------------------------

    #[test]
    fn set_alarm_request_serializes_to_camel_case() {
        let req = SetAlarmRequest {
            id: 1,
            title: "Morning".to_string(),
            message: Some("Wake up!".to_string()),
            trigger_at_ms: 1_700_000_000_000,
            alarm_type: Some("RTC_WAKEUP".to_string()),
            exact: Some(true),
            allow_while_idle: Some(true),
            repeat_interval_ms: None,
        };
        let json = serde_json::to_string(&req).unwrap();

        // フィールド名が camelCase になっていることを確認
        assert!(json.contains("\"triggerAtMs\""), "triggerAtMs が含まれない: {json}");
        assert!(json.contains("\"alarmType\""),   "alarmType が含まれない: {json}");
        assert!(json.contains("\"allowWhileIdle\""), "allowWhileIdle が含まれない: {json}");
        // snake_case が残っていないことを確認
        assert!(!json.contains("trigger_at_ms"),  "snake_case が残っている: {json}");
    }

    #[test]
    fn set_alarm_request_deserializes_from_camel_case() {
        let json = r#"{
            "id": 42,
            "title": "Test Alarm",
            "message": null,
            "triggerAtMs": 9999999,
            "alarmType": "RTC",
            "exact": false,
            "allowWhileIdle": null,
            "repeatIntervalMs": 3600000
        }"#;
        let req: SetAlarmRequest = serde_json::from_str(json).unwrap();

        assert_eq!(req.id, 42);
        assert_eq!(req.title, "Test Alarm");
        assert!(req.message.is_none());
        assert_eq!(req.trigger_at_ms, 9_999_999);
        assert_eq!(req.alarm_type.as_deref(), Some("RTC"));
        assert_eq!(req.exact, Some(false));
        assert_eq!(req.repeat_interval_ms, Some(3_600_000));
    }

    // ------------------------------------------------------------------
    // AlarmInfo
    // ------------------------------------------------------------------

    #[test]
    fn alarm_info_round_trip() {
        let info = AlarmInfo {
            id: 7,
            title: "Lunch".to_string(),
            message: Some("Eat something".to_string()),
            trigger_at_ms: 1_700_000_000_000,
            alarm_type: "RTC_WAKEUP".to_string(),
            exact: true,
            repeat_interval_ms: None,
        };
        let json = serde_json::to_string(&info).unwrap();
        let restored: AlarmInfo = serde_json::from_str(&json).unwrap();

        assert_eq!(restored.id, 7);
        assert_eq!(restored.title, "Lunch");
        assert_eq!(restored.message.as_deref(), Some("Eat something"));
        assert_eq!(restored.alarm_type, "RTC_WAKEUP");
        assert!(restored.exact);
        assert!(restored.repeat_interval_ms.is_none());
    }

    #[test]
    fn alarm_info_with_repeat_interval_round_trip() {
        let info = AlarmInfo {
            id: 3,
            title: "Daily".to_string(),
            message: None,
            trigger_at_ms: 0,
            alarm_type: "RTC".to_string(),
            exact: false,
            repeat_interval_ms: Some(86_400_000),
        };
        let json = serde_json::to_string(&info).unwrap();
        let restored: AlarmInfo = serde_json::from_str(&json).unwrap();

        assert_eq!(restored.repeat_interval_ms, Some(86_400_000));
        assert!(!restored.exact);
    }

    // ------------------------------------------------------------------
    // CancelAlarmRequest
    // ------------------------------------------------------------------

    #[test]
    fn cancel_alarm_request_serializes_correctly() {
        let req = CancelAlarmRequest { id: 99 };
        let json = serde_json::to_string(&req).unwrap();
        let restored: CancelAlarmRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.id, 99);
    }

    // ------------------------------------------------------------------
    // ListAlarmsResponse
    // ------------------------------------------------------------------

    #[test]
    fn list_alarms_response_serializes_alarms_array() {
        let response = ListAlarmsResponse {
            alarms: vec![
                AlarmInfo {
                    id: 1,
                    title: "A".to_string(),
                    message: None,
                    trigger_at_ms: 1000,
                    alarm_type: "RTC_WAKEUP".to_string(),
                    exact: true,
                    repeat_interval_ms: None,
                },
                AlarmInfo {
                    id: 2,
                    title: "B".to_string(),
                    message: Some("msg".to_string()),
                    trigger_at_ms: 2000,
                    alarm_type: "RTC".to_string(),
                    exact: false,
                    repeat_interval_ms: Some(60_000),
                },
            ],
        };
        let json = serde_json::to_string(&response).unwrap();
        assert!(json.contains("\"alarms\""), "alarms キーが含まれない");

        let restored: ListAlarmsResponse = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.alarms.len(), 2);
        assert_eq!(restored.alarms[0].id, 1);
        assert_eq!(restored.alarms[1].id, 2);
    }

    #[test]
    fn list_alarms_response_empty() {
        let response = ListAlarmsResponse { alarms: vec![] };
        let json = serde_json::to_string(&response).unwrap();
        let restored: ListAlarmsResponse = serde_json::from_str(&json).unwrap();
        assert!(restored.alarms.is_empty());
    }

    // ------------------------------------------------------------------
    // CheckPermissionResponse
    // ------------------------------------------------------------------

    #[test]
    fn check_permission_response_true() {
        let resp = CheckPermissionResponse { can_schedule_exact_alarms: true };
        let json = serde_json::to_string(&resp).unwrap();
        assert!(json.contains("\"canScheduleExactAlarms\":true"));
        let restored: CheckPermissionResponse = serde_json::from_str(&json).unwrap();
        assert!(restored.can_schedule_exact_alarms);
    }

    #[test]
    fn check_permission_response_false() {
        let resp = CheckPermissionResponse { can_schedule_exact_alarms: false };
        let json = serde_json::to_string(&resp).unwrap();
        let restored: CheckPermissionResponse = serde_json::from_str(&json).unwrap();
        assert!(!restored.can_schedule_exact_alarms);
    }
}
