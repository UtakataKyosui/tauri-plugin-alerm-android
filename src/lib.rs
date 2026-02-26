use tauri::{
  plugin::{Builder, TauriPlugin},
  Manager, Runtime,
};

pub use models::*;

#[cfg(desktop)]
mod desktop;
#[cfg(mobile)]
mod mobile;

mod commands;
mod error;
mod models;

pub use error::{Error, Result};

#[cfg(desktop)]
use desktop::Alerm;
#[cfg(mobile)]
use mobile::Alerm;

/// Extensions to [`tauri::App`], [`tauri::AppHandle`] and [`tauri::Window`] to access the alerm APIs.
pub trait AlermExt<R: Runtime> {
  fn alerm(&self) -> &Alerm<R>;
}

impl<R: Runtime, T: Manager<R>> crate::AlermExt<R> for T {
  fn alerm(&self) -> &Alerm<R> {
    self.state::<Alerm<R>>().inner()
  }
}

/// Initializes the plugin.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
  Builder::new("alerm")
    .invoke_handler(tauri::generate_handler![commands::ping])
    .setup(|app, api| {
      #[cfg(mobile)]
      let alerm = mobile::init(app, api)?;
      #[cfg(desktop)]
      let alerm = desktop::init(app, api)?;
      app.manage(alerm);
      Ok(())
    })
    .build()
}
