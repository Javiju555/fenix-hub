//! Custom Windows `IDropTarget` that intercepts drag-and-drop **before** WebView2
//! consumes the `IDataObject`.

use std::cell::RefCell;
use std::ffi::c_void;
use std::sync::atomic::{AtomicIsize, Ordering};
use std::time::SystemTime;

use serde::Serialize;
use tauri::{AppHandle, Emitter};

use windows::core::w;
use windows::Win32::Foundation::{BOOL, HWND, LPARAM, POINTL};
use windows::Win32::System::Com::{
    DVASPECT_CONTENT, FORMATETC, IDataObject, IStream, STGMEDIUM, TYMED_HGLOBAL, TYMED_ISTREAM,
};
use windows::Win32::System::DataExchange::RegisterClipboardFormatW;
use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
use windows::Win32::System::Ole::{
    DROPEFFECT, DROPEFFECT_COPY, DROPEFFECT_NONE, IDropTarget, IDropTarget_Vtbl,
    RegisterDragDrop, ReleaseStgMedium, RevokeDragDrop, CF_HDROP,
};
use windows::Win32::System::SystemServices::MODIFIERKEYS_FLAGS;
use windows::Win32::UI::Shell::{DragQueryFileW, FILEDESCRIPTORW, FILEGROUPDESCRIPTORW, HDROP};
use windows::Win32::UI::WindowsAndMessaging::{EnumChildWindows, GetClassNameW};

// IID_IUnknown
const IID_IUNKNOWN: windows::core::GUID =
    windows::core::GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
// IID_IUnknown alias (same)
const IID_IUNKNOWN_ALT: windows::core::GUID =
    windows::core::GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
// IID_IDropTarget
const IID_IDROPTARGET: windows::core::GUID =
    windows::core::GUID::from_u128(0x00000122_0000_0000_c000_000000000046);

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

// ── Manual COM implementation of IDropTarget ────────────────────────────────

/// Raw COM object that implements IDropTarget.
/// Stored on the heap; lifetime managed by refcount.
#[repr(C)]
struct FenixDropTargetRaw {
    vtable: &'static IDropTarget_Vtbl,
    refcount: AtomicIsize,
    app: AppHandle,
}

impl FenixDropTargetRaw {
    fn new(app: AppHandle) -> *mut Self {
        Box::into_raw(Box::new(Self {
            vtable: &VTABLE,
            refcount: AtomicIsize::new(1),
            app,
        }))
    }

    fn add_ref(this: *mut Self) {
        unsafe {
            let old = (*this).refcount.fetch_add(1, Ordering::Relaxed);
            // Refcount goes 1→2, 2→3, etc.
            if old == 0 {
                // Object was being destroyed; restore
                (*this).refcount.fetch_sub(1, Ordering::Relaxed);
            }
        }
    }

    fn release(this: *mut Self) -> u32 {
        unsafe {
            let old = (*this).refcount.fetch_sub(1, Ordering::AcqRel);
            if old == 1 {
                let _ = Box::from_raw(this);
                return 0;
            }
            (old - 1) as u32
        }
    }
}

static VTABLE: IDropTarget_Vtbl = IDropTarget_Vtbl {
    base__: windows::core::IUnknown_Vtbl {
        QueryInterface: fenix_droptarget_query_interface,
        AddRef: fenix_droptarget_addref,
        Release: fenix_droptarget_release,
    },
    DragEnter: fenix_droptarget_dragenter,
    DragOver: fenix_droptarget_dragover,
    DragLeave: fenix_droptarget_dragleave,
    Drop: fenix_droptarget_drop,
};

unsafe extern "system" fn fenix_droptarget_query_interface(
    this: *mut c_void,
    iid: *const windows::core::GUID,
    interface: *mut *mut c_void,
) -> windows::core::HRESULT {
    if iid.is_null() || interface.is_null() {
        return windows::core::HRESULT(-2147467261); // E_POINTER
    }
    let iid = &*iid;
    let this = this as *mut FenixDropTargetRaw;

    if *iid == IID_IUNKNOWN || *iid == IID_IDROPTARGET {
        *interface = this as *mut c_void;
        FenixDropTargetRaw::add_ref(this);
        return windows::core::HRESULT(0); // S_OK
    }

    *interface = std::ptr::null_mut();
    windows::core::HRESULT(-2147467262) // E_NOINTERFACE
}

unsafe extern "system" fn fenix_droptarget_addref(this: *mut c_void) -> u32 {
    FenixDropTargetRaw::add_ref(this as *mut FenixDropTargetRaw);
    1 // We don't track exact count for simplicity; just ensure > 0
}

unsafe extern "system" fn fenix_droptarget_release(this: *mut c_void) -> u32 {
    FenixDropTargetRaw::release(this as *mut FenixDropTargetRaw);
    0
}

unsafe extern "system" fn fenix_droptarget_dragenter(
    this: *mut c_void,
    pdataobj: *mut c_void,
    _grfkeystate: MODIFIERKEYS_FLAGS,
    _pt: POINTL,
    pdweffect: *mut DROPEFFECT,
) -> windows::core::HRESULT {
    let this = &*(this as *mut FenixDropTargetRaw);
    if pdataobj.is_null() || pdweffect.is_null() {
        return windows::core::HRESULT(-2147467261);
    }
    let data_obj: IDataObject = std::mem::transmute(pdataobj);
    if can_accept_drop(&data_obj) {
        *pdweffect = DROPEFFECT_COPY;
    } else {
        *pdweffect = DROPEFFECT_NONE;
    }
    windows::core::HRESULT(0)
}

unsafe extern "system" fn fenix_droptarget_dragover(
    _this: *mut c_void,
    _grfkeystate: MODIFIERKEYS_FLAGS,
    _pt: POINTL,
    pdweffect: *mut DROPEFFECT,
) -> windows::core::HRESULT {
    if pdweffect.is_null() {
        return windows::core::HRESULT(-2147467261);
    }
    *pdweffect = DROPEFFECT_COPY;
    windows::core::HRESULT(0)
}

unsafe extern "system" fn fenix_droptarget_dragleave(
    _this: *mut c_void,
) -> windows::core::HRESULT {
    windows::core::HRESULT(0)
}

unsafe extern "system" fn fenix_droptarget_drop(
    this: *mut c_void,
    pdataobj: *mut c_void,
    _grfkeystate: MODIFIERKEYS_FLAGS,
    _pt: POINTL,
    pdweffect: *mut DROPEFFECT,
) -> windows::core::HRESULT {
    if pdweffect.is_null() {
        return windows::core::HRESULT(-2147467261);
    }
    *pdweffect = DROPEFFECT_COPY;

    if pdataobj.is_null() {
        return windows::core::HRESULT(0);
    }

    let this = &*(this as *mut FenixDropTargetRaw);
    let data_obj: IDataObject = std::mem::transmute(pdataobj);
    let payload = extract_dropped_data(&data_obj);

    let _ = this.app.emit("fenix://drag-received", &payload);

    windows::core::HRESULT(0)
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
            cfFormat: cf_desc,
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

fn extract_dropped_data(data_obj: &IDataObject) -> DragReceivedPayload {
    let fmt_hdrop = make_hdrop_fmtetc();
    if unsafe { data_obj.QueryGetData(&fmt_hdrop) }.is_ok() {
        if let Ok(paths) = unsafe { extract_hdrop_paths(data_obj) } {
            if !paths.is_empty() {
                return DragReceivedPayload {
                    paths,
                    source: "cf_hdrop",
                };
            }
        }
    }
    if let Some(virtual_files) = unsafe { extract_virtual_files(data_obj) } {
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

unsafe fn extract_hdrop_paths(data_obj: &IDataObject) -> windows::core::Result<Vec<String>> {
    let mut medium = STGMEDIUM::default();
    data_obj.GetData(&make_hdrop_fmtetc(), &mut medium)?;
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
        cfFormat: cf_desc,
        ptd: std::ptr::null_mut(),
        dwAspect: DVASPECT_CONTENT.0,
        lindex: -1,
        tymed: TYMED_HGLOBAL.0 as u32,
    };
    let mut medium = STGMEDIUM::default();
    if data_obj.GetData(&fmt_desc, &mut medium).is_err() {
        return None;
    }

    let hglobal = medium.u.hGlobal;
    let lock_ptr = GlobalLock(hglobal);
    if lock_ptr.is_null() {
        ReleaseStgMedium(&mut medium);
        return None;
    }

    let group = &*(lock_ptr as *const FILEGROUPDESCRIPTORW);
    let count = group.cItems;
    let descriptors: &[FILEDESCRIPTORW] = std::slice::from_raw_parts(
        (lock_ptr as *const FILEDESCRIPTORW).add(1),
        count as usize,
    );

    let temp_dir = std::env::temp_dir().join("fenix-hub-drag");
    let _ = std::fs::create_dir_all(&temp_dir);
    let mut saved_paths = Vec::with_capacity(count as usize);

    for (index, desc) in descriptors.iter().enumerate() {
        let name_len = desc
            .cFileName
            .iter()
            .position(|&c| c == 0)
            .unwrap_or(desc.cFileName.len());
        let name = String::from_utf16_lossy(&desc.cFileName[..name_len]);
        if name.is_empty() {
            continue;
        }

        let fmt_content = FORMATETC {
            cfFormat: cf_content,
            ptd: std::ptr::null_mut(),
            dwAspect: DVASPECT_CONTENT.0,
            lindex: index as i32,
            tymed: TYMED_ISTREAM.0 as u32,
        };
        let mut medium_content = STGMEDIUM::default();
        if data_obj.GetData(&fmt_content, &mut medium_content).is_ok()
            && medium_content.tymed == TYMED_ISTREAM.0 as u32
        {
            let stream: IStream = (*medium_content.u.pstm).clone();
            let data = read_istream_to_vec(&stream).unwrap_or_default();
            let unique_name = dedup_file_name(&name, &temp_dir);
            let save_path = temp_dir.join(&unique_name);
            if std::fs::write(&save_path, &data).is_ok() {
                saved_paths.push(save_path.to_string_lossy().to_string());
            }
            ReleaseStgMedium(&mut medium_content);
        }
    }

    GlobalUnlock(hglobal);
    ReleaseStgMedium(&mut medium);

    if saved_paths.is_empty() {
        None
    } else {
        Some(saved_paths)
    }
}

fn read_istream_to_vec(stream: &IStream) -> windows::core::Result<Vec<u8>> {
    let mut data = Vec::new();
    let mut buf = [0u8; 8192];
    loop {
        let mut read = 0u32;
        stream.Read(&mut buf, Some(&mut read))?;
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
    unsafe {
        EnumChildWindows(parent, Some(enum_child_proc), LPARAM(0));
    }
    FOUND_HWND.with(|r| *r.borrow())
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
        // Step 1: Disable WebView2's built-in external drop handler via raw COM.
        let _ = window.with_webview(|webview| {
            let controller_raw = webview.controller();
            if controller_raw.is_null() {
                tracing::warn!("WebView2 controller is null — external drop may not be intercepted");
                return;
            }

            // IID_ICoreWebView2Controller4
            let iid = windows::core::GUID::from_u128(0x3191c66b_9f7b_4d7b_9d35_93cb9d2d766d);
            let mut ppv: *mut std::ffi::c_void = std::ptr::null_mut();

            // QI for ICoreWebView2Controller4 from the raw IUnknown pointer
            let hr = unsafe {
                let iunknown = &*(controller_raw as *const *const *const std::ffi::c_void);
                let qi: unsafe extern "system" fn(
                    *mut std::ffi::c_void,
                    *const windows::core::GUID,
                    *mut *mut std::ffi::c_void,
                ) -> windows::core::HRESULT = std::mem::transmute((*iunknown)[0]);
                qi(controller_raw as *mut std::ffi::c_void, &iid, &mut ppv)
            };

            if hr.is_err() || ppv.is_null() {
                tracing::debug!(
                    "QI for ICoreWebView2Controller4 failed (hr={hr:?}) — proceeding without SetAllowExternalDrop"
                );
                return;
            }

            // SetAllowExternalDrop(BOOL) is vtable slot 8 on ICoreWebView2Controller4
            let set_hr = unsafe {
                type SetFn =
                    unsafe extern "system" fn(*mut std::ffi::c_void, i32) -> windows::core::HRESULT;
                let vtable = &*(ppv as *const *const *const std::ffi::c_void);
                let func: SetFn = std::mem::transmute((*vtable)[8]);
                func(ppv, 0) // FALSE = disable
            };

            // Release the QI'd pointer
            unsafe {
                let vtable = &*(ppv as *const *const *const std::ffi::c_void);
                let release: unsafe extern "system" fn(*mut std::ffi::c_void) -> u32 =
                    std::mem::transmute((*vtable)[2]);
                release(ppv);
            }

            if set_hr.is_ok() {
                tracing::debug!("SetAllowExternalDrop(false) succeeded");
            } else {
                tracing::warn!("SetAllowExternalDrop returned: {set_hr:?}");
            }
        });

        // Step 2: Find the WebView2 child HWND
        let parent_hwnd = match window.hwnd() {
            Ok(h) => HWND(h as *mut _),
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

        // Step 3: Revoke any existing drop target, then register ours
        let _ = unsafe { RevokeDragDrop(child_hwnd) };

        let drop_target_ptr = FenixDropTargetRaw::new(window.app_handle().clone());

        // RegisterDragDrop takes ownership of an AddRef'd IDropTarget pointer
        if let Err(e) = unsafe {
            RegisterDragDrop(child_hwnd, &IDropTarget::from_raw_borrowed(&drop_target_ptr))
        } {
            // Registration failed — release our reference
            FenixDropTargetRaw::release(drop_target_ptr);
            tracing::warn!("Failed to register custom drop target: {e:?}");
        } else {
            tracing::info!("FenixDropTarget registered on WebView2 child HWND");
        }
    }
}
