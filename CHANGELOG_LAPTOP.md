# Changelog — sesión laptop Linux (2026-04-04)

## Release 0.2.2 — base AirDrop-like + paridad FNX2 Android (2026-04-10)

### Transporte cercano (BLE + Wi-Fi Direct)
- Android:
	- Nuevo `BleIdentityController` para advertising/scanning de identidad cercana.
	- Nuevo `WifiDirectController` para discovery de peers Wi-Fi Direct.
	- Integracion de ambos en ciclo de vida del servicio.
	- Nuevo snapshot de hardware/estado/peers por llamada desde bridge (`get_transport_hardware`).
- Tauri/desktop:
	- `get_transport_capabilities` ampliado con inventario detallado por llamada.
	- Alias `get_transport_hardware` registrado para snapshot completo.

### Seguridad y protocolo
- Android client ya decodifica FNX2 v2 (`X-FenixHub-Encrypted: 2`) con AES-GCM por chunk.
- Soporte de descompresion zstd en decode FNX2 Android.
- Test vector canonico de firma HMAC alineado Rust/Kotlin.
- Tests de paridad y round-trip:
	- Rust: test de vector canonico.
	- Android: test de vector canonico + tests FNX2 v2 (con y sin zstd).

### Versionado
- Bump visible a `0.2.2` (desktop/core/frontend) y Android `1.0.3`.

## Release 0.2.1 — hardening de seguridad cross-platform (2026-04-10)

### Autenticación y anti-replay
- Nuevo modelo de firma canónica para requests HTTP de contenido:
	- mensaje firmado incluye `method + path + group_id + timestamp + nonce + SHA256(body)`
	- nuevos headers: `X-FenixHub-Timestamp`, `X-FenixHub-Nonce`, `X-FenixHub-Body-Sha256`
- Verificación estricta de ventana temporal y nonce único en servidor Rust y Android.
- Caché de nonces para bloquear replay dentro de ventana de aceptación.

### Identidad y claves
- `group_id` derivado con contexto HKDF dedicado (`fenixhub-v2-group-id`) en lugar de truncar `group_key`.
- Política de calidad mínima de passphrase aplicada en desktop y Android.

### Ciclo de vida efímero
- Guard de publicación: auto-stop por TTL (10 min) en sesión activa.
- Guard de red: publicación se detiene automáticamente si cambia la IP/LAN durante sesión activa.

### Android
- Cliente Android actualizado para firmar requests con el nuevo esquema canónico.
- Servidor Android actualizado para verificar firma canónica + anti-replay.
- Detección explícita de payload FNX2 v2 no soportado en Android (error claro, sin fallback inseguro).

### Versionado
- Bump visible a `0.2.1` (desktop/core/frontend) y Android `1.0.2`.

## Release 0.2.0 — ajustes UX + identidad/perfiles + compresión (2026-04-10)

### UX / ventanas
- Hub bloqueado a dos tamaños válidos: `pill (280x34)` y `expanded (820x185)`.
- Bloqueo de maximización/minimización/redimensionado de la ventana hub a nivel config + runtime.
- Zoom desactivado por atajos/gestos (`Ctrl/Cmd +/-/0`, `Ctrl/Cmd+wheel`) para mantener escala fija.
- Ventana de ajustes rediseñada para evitar recortes y con botón `X` explícito y visible.

### Ajustes / identidad
- Nuevo flujo de identidad:
	- actualización en caliente de nombre y tipo de dispositivo
	- cambio de identidad/grupo sin borrar caché
	- eliminación de identidad sin borrar caché/historial
- Nuevo soporte base de perfiles (multicuenta): listar, guardar, activar y eliminar.
- Añadido estado de capacidades de transporte en ajustes: LAN / BLE / Wi-Fi Direct.

### Protocolo / transferencia
- Compresión zstd para archivos grandes (>=100 MB) cuando compensa.
- Heurística para evitar comprimir formatos ya comprimidos (zip/7z/rar/media/imagenes comprimidas).
- Cliente actualizado para decodificar payload FNX2 comprimido.
- `save as` de peers no-texto pasa por pull streaming a archivo para reducir picos de RAM.

### Versionado
- Bump visible a `0.2.0` en Tauri y paquetes relacionados para facilitar detección de actualización en Windows.

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
