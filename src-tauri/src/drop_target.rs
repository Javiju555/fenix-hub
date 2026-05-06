//! Custom Windows `IDropTarget` that intercepts drag-and-drop **before** WebView2
//! consumes the `IDataObject`.

use std::cell::RefCell;
use std::time::SystemTime;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use windows::core::{implement, w, BOOL, IUnknownImpl, Result as WinResult};
use windows::Win32::Foundation::{HWND, LPARAM, POINTL};
use windows::Win32::System::Com::{
    DVASPECT_CONTENT, FORMATETC, IDataObject, IStream, TYMED_HGLOBAL, TYMED_ISTREAM,
};
use windows::Win32::System::DataExchange::RegisterClipboardFormatW;
use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
use windows::Win32::System::Ole::{
    DROPEFFECT, DROPEFFECT_COPY, DROPEFFECT_NONE, IDropTarget, IDropTarget_Impl,
    RegisterDragDrop, ReleaseStgMedium, RevokeDragDrop, CF_HDROP,
};
use windows::Win32::System::SystemServices::MODIFIERKEYS_FLAGS;
use windows::Win32::UI::Shell::{DragQueryFileW, FILEDESCRIPTORW, FILEGROUPDESCRIPTORW, HDROP};
use windows::Win32::UI::WindowsAndMessaging::{EnumChildWindows, GetClassNameW};

// ── Drag-received payload sent to the frontend ──────────────────────────────

#[derive(Clone, Serialize)]
pub struct DragReceivedPayload {
    pub paths: Vec<String>,
    pub source: &'static str,
}

// ── Cached clipboard format IDs ─────────────────────────────────────────────

static mut CF_FILE_DESCRIPTOR: u32 = 0;
static mut CF_FILE_CONTENTS: u32 = 0;

fn ensure_virtual_formats_registered() {
    unsafe {
        if CF_FILE_DESCRIPTOR == 0 {
            CF_FILE_DESCRIPTOR = RegisterClipboardFormatW(w!("FileGroupDescriptorW"));
        }
        if CF_FILE_CONTENTS == 0 {
            CF_FILE_CONTENTS = RegisterClipboardFormatW(w!("FileContents"));
        }
    }
}

// ── FenixDropTarget ─────────────────────────────────────────────────────────

#[implement(IDropTarget)]
pub struct FenixDropTarget {
    app: RefCell<AppHandle>,
}

impl FenixDropTarget {
    pub fn new(app: AppHandle) -> Self {
        Self {
            app: RefCell::new(app),
        }
    }
}

impl IDropTarget_Impl for FenixDropTarget_Impl {
    fn DragEnter(
        &self,
        pdataobj: windows::core::Ref<'_, IDataObject>,
        _grfkeystate: MODIFIERKEYS_FLAGS,
        pt: &POINTL,
        pdweffect: *mut DROPEFFECT,
    ) -> WinResult<()> {
        let data_obj = match pdataobj.as_ref() {
            Some(obj) => obj,
            None => {
                unsafe { *pdweffect = DROPEFFECT_NONE };
                return Ok(());
            }
        };
        if can_accept_drop(data_obj) {
            unsafe { *pdweffect = DROPEFFECT_COPY };
            if let Ok(app) = self.get_impl().app.try_borrow() {
                let _ = app.emit("fenix://drag-enter", ());
            }
        } else {
            unsafe { *pdweffect = DROPEFFECT_NONE };
        }
        let _ = pt;
        Ok(())
    }

    fn DragOver(
        &self,
        _grfkeystate: MODIFIERKEYS_FLAGS,
        _pt: &POINTL,
        pdweffect: *mut DROPEFFECT,
    ) -> WinResult<()> {
        unsafe { *pdweffect = DROPEFFECT_COPY };
        Ok(())
    }

    fn DragLeave(&self) -> WinResult<()> {
        if let Ok(app) = self.get_impl().app.try_borrow() {
            let _ = app.emit("fenix://drag-leave", ());
        }
        Ok(())
    }

    fn Drop(
        &self,
        pdataobj: windows::core::Ref<'_, IDataObject>,
        _grfkeystate: MODIFIERKEYS_FLAGS,
        _pt: &POINTL,
        pdweffect: *mut DROPEFFECT,
    ) -> WinResult<()> {
        let data_obj = match pdataobj.as_ref() {
            Some(obj) => obj,
            None => {
                unsafe { *pdweffect = DROPEFFECT_NONE };
                return Ok(());
            }
        };
        let payload = unsafe { extract_dropped_data(data_obj) };
        let inner = self.get_impl();
        if let Ok(app) = inner.app.try_borrow() {
            let _ = app.emit("fenix://drag-received", &payload);
        }
        unsafe { *pdweffect = DROPEFFECT_COPY };
        Ok(())
    }
}

// ── Acceptance check ────────────────────────────────────────────────────────

fn can_accept_drop(data_obj: &IDataObject) -> bool {
    let fmt_hdrop = make_hdrop_fmtetc();
    if unsafe { data_obj.QueryGetData(&fmt_hdrop) }.is_ok() {
        return true;
    }
    ensure_virtual_formats_registered();
    let cf_desc = unsafe { CF_FILE_DESCRIPTOR };
    if cf_desc != 0 {
        let fmt_desc = FORMATETC {
            cfFormat: cf_desc as u16,
            ptd: std::ptr::null_mut(),
            dwAspect: DVASPECT_CONTENT.0,
            lindex: -1,
            tymed: TYMED_HGLOBAL.0 as u32,
        };
        if unsafe { data_obj.QueryGetData(&fmt_desc) }.is_ok() {
            return true;
        }
    }
    false
}

// ── Extraction ──────────────────────────────────────────────────────────────

unsafe fn extract_dropped_data(data_obj: &IDataObject) -> DragReceivedPayload {
    let fmt_hdrop = make_hdrop_fmtetc();
    if data_obj.QueryGetData(&fmt_hdrop).is_ok() {
        if let Ok(paths) = extract_hdrop_paths(data_obj) {
            if !paths.is_empty() {
                return DragReceivedPayload {
                    paths,
                    source: "cf_hdrop",
                };
            }
        }
    }
    if let Some(virtual_files) = extract_virtual_files(data_obj) {
        if !virtual_files.is_empty() {
            return DragReceivedPayload {
                paths: virtual_files,
                source: "virtual_files",
            };
        }
    }
    DragReceivedPayload {
        paths: vec![],
        source: "cf_hdrop",
    }
}

fn make_hdrop_fmtetc() -> FORMATETC {
    FORMATETC {
        cfFormat: CF_HDROP.0,
        ptd: std::ptr::null_mut(),
        dwAspect: DVASPECT_CONTENT.0,
        lindex: -1,
        tymed: TYMED_HGLOBAL.0 as u32,
    }
}

unsafe fn extract_hdrop_paths(data_obj: &IDataObject) -> WinResult<Vec<String>> {
    let fmt = make_hdrop_fmtetc();
    let mut medium = data_obj.GetData(&fmt)?;
    let hdrop = HDROP(medium.u.hGlobal.0 as _);
    let count = DragQueryFileW(hdrop, 0xFFFFFFFF, None);
    let mut paths = Vec::with_capacity(count as usize);
    for i in 0..count {
        let len = DragQueryFileW(hdrop, i, None);
        if len == 0 {
            continue;
        }
        let mut buf = vec![0u16; (len + 1) as usize];
        DragQueryFileW(hdrop, i, Some(&mut buf));
        if let Some(nul_pos) = buf.iter().position(|&c| c == 0) {
            buf.truncate(nul_pos);
        }
        if let Ok(path) = String::from_utf16(&buf) {
            paths.push(path);
        }
    }
    ReleaseStgMedium(&mut medium);
    Ok(paths)
}

unsafe fn extract_virtual_files(data_obj: &IDataObject) -> Option<Vec<String>> {
    ensure_virtual_formats_registered();
    let cf_desc = CF_FILE_DESCRIPTOR;
    let cf_content = CF_FILE_CONTENTS;
    if cf_desc == 0 || cf_content == 0 {
        return None;
    }

    let fmt_desc = FORMATETC {
        cfFormat: cf_desc as u16,
        ptd: std::ptr::null_mut(),
        dwAspect: DVASPECT_CONTENT.0,
        lindex: -1,
        tymed: TYMED_HGLOBAL.0 as u32,
    };
    let mut medium = data_obj.GetData(&fmt_desc).ok()?;

    let hglobal = medium.u.hGlobal;
    let lock_ptr = GlobalLock(hglobal);
    if lock_ptr.is_null() {
        ReleaseStgMedium(&mut medium);
        return None;
    }

    let group = &*(lock_ptr as *const FILEGROUPDESCRIPTORW);
    let count = group.cItems;
    let descriptors_start = (lock_ptr as *const FILEDESCRIPTORW).add(1);

    let temp_dir = std::env::temp_dir().join("fenix-hub-drag");
    let _ = std::fs::create_dir_all(&temp_dir);
    let mut saved_paths = Vec::with_capacity(count as usize);

    for index in 0..count as usize {
        // FILEDESCRIPTORW is packed — use raw pointers for field access
        let desc_ptr = unsafe { descriptors_start.add(index) };
        let c_file_name: [u16; 260] = unsafe {
            std::ptr::read_unaligned(std::ptr::addr_of!((*desc_ptr).cFileName))
        };
        let name_len = c_file_name.iter().position(|&c| c == 0).unwrap_or(c_file_name.len());
        let name = String::from_utf16_lossy(&c_file_name[..name_len]);
        if name.is_empty() {
            continue;
        }

        let fmt_content = FORMATETC {
            cfFormat: cf_content as u16,
            ptd: std::ptr::null_mut(),
            dwAspect: DVASPECT_CONTENT.0,
            lindex: index as i32,
            tymed: TYMED_ISTREAM.0 as u32,
        };
        if let Ok(mut medium_content) = data_obj.GetData(&fmt_content) {
            if medium_content.tymed == TYMED_ISTREAM.0 as u32 {
                let pstm_ptr = &medium_content.u.pstm as *const _ as *const Option<IStream>;
                if let Some(stream) = (*pstm_ptr).as_ref() {
                    let data = read_istream_to_vec(stream).unwrap_or_default();
                    let unique_name = dedup_file_name(&name, &temp_dir);
                    let save_path = temp_dir.join(&unique_name);
                    if std::fs::write(&save_path, &data).is_ok() {
                        saved_paths.push(save_path.to_string_lossy().to_string());
                    }
                }
                ReleaseStgMedium(&mut medium_content);
            }
        }
    }

    let _ = GlobalUnlock(hglobal);
    ReleaseStgMedium(&mut medium);

    if saved_paths.is_empty() {
        None
    } else {
        Some(saved_paths)
    }
}

fn read_istream_to_vec(stream: &IStream) -> WinResult<Vec<u8>> {
    let mut data = Vec::new();
    let mut buf = [0u8; 8192];
    loop {
        let mut read = 0u32;
        let hr = unsafe {
            stream.Read(
                buf.as_mut_ptr() as *mut std::ffi::c_void,
                buf.len() as u32,
                Some(&mut read),
            )
        };
        hr.ok()?;
        if read == 0 {
            break;
        }
        data.extend_from_slice(&buf[..read as usize]);
    }
    Ok(data)
}

fn dedup_file_name(name: &str, dir: &std::path::Path) -> String {
    let path = dir.join(name);
    if !path.exists() {
        return name.to_string();
    }
    let stem = std::path::Path::new(name)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("file");
    let ext = std::path::Path::new(name)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("");
    let ts = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    if ext.is_empty() {
        format!("{}_{}", stem, ts)
    } else {
        format!("{}_{}.{}", stem, ts, ext)
    }
}

// ── HWND enumeration ───────────────────────────────────────────────────────

thread_local! {
    static FOUND_HWND: RefCell<Option<HWND>> = const { RefCell::new(None) };
}

pub fn find_webview2_hwnd(parent: HWND) -> Option<HWND> {
    FOUND_HWND.with(|r| *r.borrow_mut() = None);
    let _ = unsafe {
        EnumChildWindows(Some(parent), Some(enum_child_proc), LPARAM(0))
    };
    FOUND_HWND.with(|r: &RefCell<Option<HWND>>| *r.borrow())
}

unsafe extern "system" fn enum_child_proc(hwnd: HWND, _lparam: LPARAM) -> BOOL {
    let mut class_buf = [0u16; 256];
    let len = GetClassNameW(hwnd, &mut class_buf);
    if len > 0 {
        if let Ok(class_name) = String::from_utf16(&class_buf[..len as usize]) {
            if class_name.contains("Chrome_RenderWidgetHostHWND") {
                FOUND_HWND.with(|r| *r.borrow_mut() = Some(hwnd));
                return BOOL(0);
            }
        }
    }
    BOOL(1)
}

// ── Public setup helper ────────────────────────────────────────────────────

pub fn register_fenix_drop_target(window: &tauri::WebviewWindow) {
    #[cfg(not(target_os = "windows"))]
    {
        let _ = window;
    }

    #[cfg(target_os = "windows")]
    {
        // Run inside with_webview so the callback fires only after WebView2 is
        // fully initialized (Chrome_RenderWidgetHostHWND exists) and on the main
        // UI thread (STA), which is required by RegisterDragDrop.
        let window_clone = window.clone();
        let _ = window.with_webview(move |_webview| {
            let parent_hwnd = match window_clone.hwnd() {
                Ok(h) => h,
                Err(_) => {
                    tracing::warn!("Cannot get window HWND — drop interception disabled");
                    return;
                }
            };

            let child_hwnd = match find_webview2_hwnd(parent_hwnd) {
                Some(h) => h,
                None => {
                    tracing::warn!("Cannot find WebView2 child HWND — drop interception disabled");
                    return;
                }
            };

            let _ = unsafe { RevokeDragDrop(child_hwnd) };

            let drop_target: IDropTarget = FenixDropTarget::new(window_clone.app_handle().clone()).into();
            if let Err(e) = unsafe { RegisterDragDrop(child_hwnd, &drop_target) } {
                tracing::warn!("Failed to register custom drop target: {e:?}");
            } else {
                tracing::info!("FenixDropTarget registered on WebView2 child HWND");
            }
        });
    }
}
