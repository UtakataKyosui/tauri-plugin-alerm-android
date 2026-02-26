use tauri::{command, AppHandle, Runtime};

use crate::models::*;
use crate::AlermExt;
use crate::Result;

#[command]
pub(crate) async fn set_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: SetAlarmRequest,
) -> Result<AlarmInfo> {
    app.alerm().set_alarm(payload)
}

#[command]
pub(crate) async fn cancel_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: CancelAlarmRequest,
) -> Result<()> {
    app.alerm().cancel_alarm(payload)
}

#[command]
pub(crate) async fn list_alarms<R: Runtime>(
    app: AppHandle<R>,
) -> Result<ListAlarmsResponse> {
    app.alerm().list_alarms()
}

#[command]
pub(crate) async fn check_exact_alarm_permission<R: Runtime>(
    app: AppHandle<R>,
) -> Result<CheckPermissionResponse> {
    app.alerm().check_exact_alarm_permission()
}

#[command]
pub(crate) async fn open_exact_alarm_settings<R: Runtime>(
    app: AppHandle<R>,
) -> Result<()> {
    app.alerm().open_exact_alarm_settings()
}
