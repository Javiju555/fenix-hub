/// GTK4 authentication dialog — Fenix visual style.
use gtk4::glib;
use gtk4::prelude::*;
use gtk4::{Application, ApplicationWindow, Box as GtkBox, Button, Image, Label,
           Orientation, PasswordEntry};
use std::sync::{Arc, Mutex};
use tokio::sync::oneshot;

use crate::agent::AuthRequest;

pub fn show(app: &Application, req: AuthRequest) {
    let window = ApplicationWindow::builder()
        .application(app)
        .title("Authentication Required")
        .default_width(380)
        .resizable(false)
        .build();

    let root = GtkBox::new(Orientation::Vertical, 16);
    root.set_margin_top(28);
    root.set_margin_bottom(24);
    root.set_margin_start(28);
    root.set_margin_end(28);

    let icon = Image::from_icon_name("dialog-password-symbolic");
    icon.set_pixel_size(52);

    let title_lbl = Label::new(Some("Authentication Required"));
    title_lbl.add_css_class("title-3");

    let msg_lbl = Label::new(Some(&req.message));
    msg_lbl.set_wrap(true);
    msg_lbl.set_justify(gtk4::Justification::Center);

    let user_lbl = Label::new(Some(&format!("Authenticating as: {}", req.user_name)));
    user_lbl.add_css_class("caption");

    let entry = PasswordEntry::new();
    entry.set_show_peek_icon(true);
    entry.set_placeholder_text(Some("Password"));

    let btn_row = GtkBox::new(Orientation::Horizontal, 8);
    btn_row.set_halign(gtk4::Align::End);
    let cancel_btn = Button::with_label("Cancel");
    let auth_btn = Button::with_label("Authenticate");
    auth_btn.add_css_class("suggested-action");
    btn_row.append(&cancel_btn);
    btn_row.append(&auth_btn);

    root.append(&icon);
    root.append(&title_lbl);
    root.append(&msg_lbl);
    root.append(&user_lbl);
    root.append(&entry);
    root.append(&btn_row);

    apply_css();
    window.set_child(Some(&root));

    // Shared one-shot sender — only fires once (OK, Cancel, or close)
    let tx: Arc<Mutex<Option<oneshot::Sender<Option<String>>>>> =
        Arc::new(Mutex::new(Some(req.response_tx)));

    let tx_c = tx.clone();
    let win_c = window.clone();
    cancel_btn.connect_clicked(move |_| {
        if let Some(s) = tx_c.lock().unwrap().take() { let _ = s.send(None); }
        win_c.close();
    });

    let tx_a = tx.clone();
    let entry_a = entry.clone();
    let win_a = window.clone();
    auth_btn.connect_clicked(move |_| {
        let pw = entry_a.text().to_string();
        if let Some(s) = tx_a.lock().unwrap().take() { let _ = s.send(Some(pw)); }
        win_a.close();
    });

    let tx_e = tx.clone();
    let win_e = window.clone();
    entry.connect_activate(move |e| {
        let pw = e.text().to_string();
        if let Some(s) = tx_e.lock().unwrap().take() { let _ = s.send(Some(pw)); }
        win_e.close();
    });

    let tx_x = tx.clone();
    window.connect_close_request(move |_| {
        if let Some(s) = tx_x.lock().unwrap().take() { let _ = s.send(None); }
        glib::Propagation::Proceed
    });

    window.present();
}

fn apply_css() {
    use gtk4::CssProvider;
    let css = CssProvider::new();
    css.load_from_data(
        r#"
        window {
            background-color: alpha(@window_bg_color, 0.92);
        }
        "#,
    );
    gtk4::style_context_add_provider_for_display(
        &gtk4::gdk::Display::default().expect("display"),
        &css,
        gtk4::STYLE_PROVIDER_PRIORITY_APPLICATION,
    );
}
