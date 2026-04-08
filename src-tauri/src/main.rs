// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // Force XWayland on Linux so set_position / set_always_on_top work.
    // GNOME Wayland sessions set GDK_BACKEND=wayland in the environment, so we
    // must override unconditionally — the .is_err() guard would miss that case.
    #[cfg(target_os = "linux")]
    unsafe {
        std::env::set_var("GDK_BACKEND", "x11");
    }

    fenix_hub_app_lib::run();
}
