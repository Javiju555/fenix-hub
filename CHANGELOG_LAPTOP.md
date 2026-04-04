# Changelog — sesión laptop Linux (2026-04-04)

## Fixes aplicados en esta sesión (Linux, rama main)

### 1. `src-tauri/Cargo.toml` — feature `tray-icon`
- Añadido `features = ["tray-icon"]` a la dependencia de tauri.
- **Por qué**: `tauri.conf.json` define `trayIcon` pero el feature no estaba habilitado → build fallaba.

### 2. `src-tauri/tauri.conf.json` — Vite dev server
- Añadidos `devUrl` y `beforeDevCommand` para que `bun run dev` arranque Vite en `:1435`.
- **Por qué**: sin esto el frontend se servía desde `dist/` estático y requería rebuild+reinicio manual para ver cambios.

### 3. `crates/fenix-hub-daemon/src/mdns.rs` — fixes de discovery Linux

#### a) Error logging en parser avahi
- `serde_json::from_str` fallaba silenciosamente. Ahora loguea `ERROR` con snippet del JSON.

#### b) stdbuf para line-buffering de avahi-browse
- Cambiado `Command::new("avahi-browse")` → `Command::new("stdbuf").args(["-oL", "avahi-browse", ...])`
- **Por qué**: cuando avahi-browse escribe a un pipe, el runtime C usa full-buffering (~4 KB). Los servicios nuevos no llegaban en tiempo real hasta que el buffer se llenaba. Con `-oL` se fuerza line-buffering → actualizaciones instantáneas.
- `which_avahi_browse()` ahora verifica también que `stdbuf` esté disponible (paquete `coreutils`).

#### c) Parser `\DDD` decimal byte escapes (UTF-8)
- **Bug**: avahi-browse -p escapa caracteres no-ASCII como `\DDD` (3 dígitos decimales = valor del byte). El parser anterior los pasaba literalmente → serde_json fallaba con "invalid escape".
- **Fix**: el parser ahora acumula bytes crudos (`Vec<u8>`) y decodifica secuencias `\DDD` como bytes; convierte el resultado a UTF-8 con `String::from_utf8_lossy`. Esto permite texto con acentos, ñ, emojis, etc. en el preview.

### 4. `frontend/src/main.ts` — fixes UI

#### a) Error handling en pull
- Pull de contenido peer no tenía `try/catch` → al fallar, el botón quedaba en "Recibiendo…" para siempre sin mostrar el error.
- **Fix**: añadido `try/catch` que restaura el botón y muestra el mensaje de error.

#### b) Argumento camelCase en `pull_peer_content`
- Tauri v2 convierte parámetros top-level de comandos Rust a camelCase en JS.
- `content_id` → debe pasarse como `contentId` en `invoke(...)`.
- **Fix**: `invoke('pull_peer_content', { contentId: id })`.

#### c) Pull no elimina de Red
- Tras pullear un item de un peer, se eliminaba de la lista Red aunque el peer lo sigue teniendo publicado.
- **Fix**: eliminada la línea `peerContent = peerContent.filter(...)` post-pull.

---

## Notas de sincronización para Windows

> **⚠️ Antes de merge**: revisar conflictos en estos archivos con los cambios de Windows:
> - `frontend/src/main.ts` — puede haber cambios en UI/lógica desde Windows
> - `crates/fenix-hub-daemon/src/mdns.rs` — solo afecta código `#[cfg(target_os = "linux")]`, no debería conflictuar
> - `src-tauri/tauri.conf.json` — `devUrl`/`beforeDevCommand` son solo para dev, no afectan build de producción Windows

## Estado de la primera prueba real
- ✅ Discovery Windows → Linux funcionando (avahi-browse + stdbuf)
- ✅ Discovery Linux → Windows funcionando (mdns-sd nativo)
- ✅ Pull de texto con UTF-8 de Windows a Linux
- ✅ Cifrado AES-256-GCM end-to-end verificado
- ⏳ Live updates en tiempo real (stdbuf fix aplicado, pendiente de verificar en sesión larga)
- ⏳ Ventana ocupa demasiado espacio en GNOME (pendiente fix de layout)
- ⏳ Android CryptoUtils pendiente de actualizar a protocolo v2 (Argon2id 64MB + HKDF + AES-GCM)
