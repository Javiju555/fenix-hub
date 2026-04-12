# FenixHub

Tu contenido. Tus dispositivos. Tus reglas.

---

## Qué es

FenixHub es un portapapeles compartido entre dispositivos que funciona en tu red local, sin cuenta, sin servidor externo y con cifrado real.

Copias algo en el móvil. Aparece en el PC. Lo pegas. No hay más pasos.

Funciona con texto, imágenes y archivos. En Windows, Linux y Android.

---

## Cómo funciona

Cada dispositivo corre su propio nodo. Los nodos se descubren por mDNS en la red local y se autentican con un `group_id` compartido. Las transferencias van cifradas con AES-GCM por chunks sobre HTTP autenticado — sin relay, sin cloud, sin intermediarios.

El overlay de Android te permite soltar contenido sobre cualquier app sin cambiar de ventana. En desktop, arrastras archivos directamente al hub.

---

## Seguridad

No es cifrado decorativo. Cada request lleva firma canónica sobre `method + path + group_id + timestamp + nonce + body_sha256`. Hay protección anti-replay con ventana temporal y nonce único por petición. El `group_id` se deriva con HKDF. La passphrase tiene política mínima de complejidad.

Las transferencias a ~30 MB/s porque el cifrado corre por hardware — el overhead de criptografía software se elimina en el lado del dispositivo.

---

## Estado actual

**Operativo**

- Desktop Windows y Linux (Tauri v2 + Rust)
- Android: hub nativo + overlay + drag/drop
- Transferencia LAN cifrada: texto, imagen, archivo
- Descubrimiento por mDNS, pull por HTTP autenticado
- Base de modo cercano: BLE discovery + Wi-Fi Direct discovery

**En progreso**

- Canal de transferencia completo sobre Wi-Fi Direct (modo cercano sin LAN)

---

## iOS

No hay port iOS funcional. No es postura: es que hace falta Mac + Xcode + Apple Developer Program (99 USD/año) + hardware real para testing. Cuando ese setup exista, se hace.

---

## Plataformas

| Plataforma | Stack |
|---|---|
| Windows / Linux | Tauri v2, Rust, TypeScript |
| Android | App nativa Kotlin + WebView bridge |
| iOS | Experimental / pendiente de setup Apple |

---

## Proyecto

Fenix Motion Systems.
