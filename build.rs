const COMMANDS: &[&str] = &[
    "set_alarm",
    "cancel_alarm",
    "list_alarms",
    "check_exact_alarm_permission",
    "open_exact_alarm_settings",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .ios_path("ios")
        .build();
}
