# FenixHub — pending real vs done real

## Estado verificado (2026-04-10)

### Implementado

#### v0.2.0
- [x] Hub en tamano fijo (pill/expanded), sin maximizar ni zoom.
- [x] Ajustes redisenados (identidad, perfiles, cache, zona de peligro).
- [x] Perfiles base (guardar, listar, activar, borrar).
- [x] Compresion para archivos grandes y decode en receptor.

#### v0.2.1
- [x] Firma canonica de requests (`method + path + group_id + timestamp + nonce + body_sha256`).
- [x] Anti-replay (ventana temporal + cache de nonce) en desktop y Android.
- [x] `group_id` derivado con HKDF dedicado.
- [x] Politica minima de passphrase en desktop y Android.
- [x] Guard de publicacion efimera (TTL + cambio de red).

#### v0.2.2
- [x] Android decode de FNX2 v2 (`X-FenixHub-Encrypted: 2`) con AES-GCM por chunk.
- [x] Soporte de compresion zstd en decode FNX2 Android.
- [x] Paridad Rust/Kotlin con vector canonico de firma (tests en ambos lados).
- [x] Base AirDrop-like:
  - [x] Android BLE identity discovery + Wi-Fi Direct discovery.
  - [x] Android comando de hardware y peers con snapshot por llamada.
  - [x] Tauri comando de hardware (LAN/BLE/Wi-Fi Direct) con inventario por llamada.

## Pendiente real

### Transporte cercano (prioridad alta)
- [ ] Transferencia de payload real sobre Wi-Fi Direct (ahora mismo hay discovery/handoff, no canal de datos final completo).
- [ ] Handshake de sesion cercana extremo a extremo para modo publico (clave efimera por sesion y confirmacion receptor).
- [ ] Normalizar contrato de `get_transport_hardware` entre desktop y Android para exponer exactamente las mismas claves.

### Rendimiento / memoria
- [ ] Servidor FNX2 realmente streaming end-to-end sin ensamblar todo el body cifrado en RAM antes de responder.
- [ ] Benchmarks de transferencias grandes (desktop-desktop, desktop-android, android-android).
- [ ] Ajuste fino de chunk size, compresion y latencia en redes lentas.

### Android parity
- [ ] Paridad completa de perfiles/identidad v2 en capa Android nativa (hoy la base fuerte esta en desktop).

### Preparacion release OSS
- [ ] CI minima: build desktop + tests Rust + tests Android unitarios en cada PR.
- [ ] Politica de versionado y changelog de release estable.
- [ ] Reforzar README de instalacion por plataforma.

## Clean public repo / clon limpio desde cero

### Objetivo
Generar un repo publico con historial limpio, sin arrastrar rutas privadas/historicas.

### Runbook recomendado
1. Crear mirror local del repo actual:

```bash
git clone --mirror <origen_privado> fenix-hub-public.git
cd fenix-hub-public.git
```

2. Limpiar historial sensible (ajusta rutas segun aplique):

```bash
git filter-repo --path src-tauri/src/personal --invert-paths
```

3. Verificar que la ruta no existe en historial:

```bash
git log --all -- src-tauri/src/personal
```

4. Crear repo remoto publico vacio y empujar mirror limpio:

```bash
git remote add public <url_repo_publico>
git push --mirror public
```

5. Clonar de nuevo el repo publico para validar desde cero:

```bash
cd ..
git clone <url_repo_publico> fenix-hub-public-check
cd fenix-hub-public-check
```

6. Smoke checks en clon limpio:

```bash
bun run --cwd frontend build
cargo test -p fenix-hub-core
cd android
./gradlew :app:testDebugUnitTest
```
