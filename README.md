# FenixHub

Portapapeles compartido entre tus dispositivos, cifrado y sin nube.

## Que es

FenixHub te permite compartir texto, imagenes y archivos entre Windows, Linux y Android sin cuenta, sin servidor central y sin depender de servicios externos.

## Estado actual

- Grupo privado local funcional (mDNS + transferencia cifrada).
- Seguridad endurecida: firma canonica, anti-replay, passphrase policy.
- Base AirDrop-like integrada:
  - Inventario de hardware LAN/BLE/Wi-Fi Direct por llamada.
  - Discovery BLE + Wi-Fi Direct en Android (handoff base).
- Android ya soporta decode FNX2 v2 con zstd.

## Quick start (desktop)

### Requisitos
- Rust toolchain
- Bun
- Dependencias de Tauri segun plataforma

### Desarrollo

```bash
bun tauri dev
```

### Build frontend

```bash
bun run --cwd frontend build
```

### Check/test Rust

```bash
cargo check -p fenix-hub-app
cargo test -p fenix-hub-core
```

### Tests Android (unit)

```bash
cd android
./gradlew :app:testDebugUnitTest
```

## Documentacion

- Vision producto y copys para redes: [fenixhub.md](fenixhub.md)
- Roadmap real y checklist de release limpio: [PENDING.md](PENDING.md)
- Cambios por release/sesion: [CHANGELOG_LAPTOP.md](CHANGELOG_LAPTOP.md)

## Licencia

Pendiente de definir antes de release publico.
