# FenixHub — roadmap

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
- [x] Base de intercambio cercano:
  - [x] Android BLE identity discovery + Wi-Fi Direct discovery.
  - [x] Android comando de hardware y peers con snapshot por llamada.
  - [x] Tauri comando de hardware (LAN/BLE/Wi-Fi Direct) con inventario por llamada.
- [x] Servidor FNX2 streaming end-to-end sin ensamblar payload en RAM (`Body::from_stream`, archivos grandes por chunks).
- [x] Cliente streaming a disco (`pull_content_to_file`) con fallback graceful a pull bufferizado.
- [x] Contrato `get_transport_hardware` normalizado entre desktop y Android (`ble`/`wifi_direct` anidados, `permissions_ready`, `ble_peers`, `handoff_candidates`).

## Pendiente real

### Transporte cercano (prioridad alta)
- [ ] Transferencia de payload real sobre Wi-Fi Direct (ahora mismo hay discovery/handoff, no canal de datos final completo).
- [ ] Handshake de sesion cercana extremo a extremo para modo publico (clave efimera por sesion y confirmacion receptor).

### Rendimiento / memoria
- [ ] Benchmarks de transferencias grandes (desktop-desktop, desktop-android, android-android).
- [ ] Ajuste fino de chunk size, compresion y latencia en redes lentas.

### Android parity
- [ ] Paridad completa de perfiles/identidad v2 en capa Android nativa (hoy la base fuerte esta en desktop).

### Preparacion release OSS
- [ ] CI minima: build desktop + tests Rust + tests Android unitarios en cada PR.
- [ ] Politica de versionado y changelog de release estable.
- [x] Historial limpio para repo publico.
