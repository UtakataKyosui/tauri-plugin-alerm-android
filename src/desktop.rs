use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::*;

pub fn init<R: Runtime, C: DeserializeOwned>(
    app: &AppHandle<R>,
    _api: PluginApi<R, C>,
) -> crate::Result<Alerm<R>> {
    Ok(Alerm(app.clone()))
}

/// Access to the alerm APIs.
pub struct Alerm<R: Runtime>(AppHandle<R>);

impl<R: Runtime> Alerm<R> {
    pub fn set_alarm(&self, _payload: SetAlarmRequest) -> crate::Result<AlarmInfo> {
        Err(crate::Error::NotSupported)
    }

    pub fn cancel_alarm(&self, _payload: CancelAlarmRequest) -> crate::Result<()> {
        Err(crate::Error::NotSupported)
    }

    pub fn list_alarms(&self) -> crate::Result<ListAlarmsResponse> {
        Err(crate::Error::NotSupported)
    }

    pub fn check_exact_alarm_permission(&self) -> crate::Result<CheckPermissionResponse> {
        Err(crate::Error::NotSupported)
    }

    pub fn open_exact_alarm_settings(&self) -> crate::Result<()> {
        Err(crate::Error::NotSupported)
    }
}
