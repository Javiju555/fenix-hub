# FenixHub

**Un portapapeles compartido. Sin cuenta. Sin nube. Sin dramas.**

Copias algo en el móvil y aparece en el PC. Arrastras un archivo desde el PC y llega al portátil. Sin cables, sin pasos raros, sin mandar cosas por WhatsApp a ti mismo. Eso es FenixHub.

---

## ¿Qué es?

FenixHub es una app open source que convierte todos tus dispositivos en un portapapeles compartido en tiempo real. Texto, imágenes, archivos — lo que sea. Se comparte al instante con cualquier dispositivo que tenga FenixHub instalado y esté en tu red.

Sin servidores intermedios. Sin cuenta de ningún tipo. Todo viaja cifrado de extremo a extremo directamente entre tus dispositivos.

---

## ¿Cómo funciona?

Tus dispositivos se descubren solos en la red local usando mDNS (el mismo protocolo que usa AirDrop para encontrarse). Cuando compartes algo, se monta un servidor efímero en tu dispositivo que solo existe mientras dura el intercambio. El contenido viaja cifrado con AES-256-GCM — ni el router lo puede leer.

Cada grupo de dispositivos comparte una passphrase común que sirve como llave de cifrado. Sin ella, nadie puede ver lo que compartes, aunque esté en la misma red.

---

## ¿Qué puedo compartir?

- **Texto plano** — se copia directamente al portapapeles del otro dispositivo
- **Imágenes** — aparecen en la interfaz con preview, listas para copiar o guardar
- **Archivos** — cualquier tipo, con nombre original, listos para guardar donde quieras

---

## Flujo de uso

**Compartir:**
1. Pegas algo en el portapapeles o arrastras un archivo al hub
2. Se anuncia automáticamente a tus otros dispositivos
3. Aparece en su interfaz con un botón de "Copiar" y "Guardar"

**Recibir:**
- **Copiar** → va directo al portapapeles. Si es texto, listo para pegar. Si es un archivo, se guarda en una carpeta temporal FIFO (30 archivos, los más viejos se borran solos) y se copia la ruta.
- **Guardar** → diálogo nativo del sistema operativo, eliges dónde va, sin pasos extra.

---

## Modo público (próximamente)

Además del modo de grupo (solo entre tus dispositivos con la misma passphrase), FenixHub tendrá un modo de transferencia pública entre dispositivos cercanos — como AirDrop pero sin Apple.

Funciona por **Bluetooth Low Energy y WiFi Direct**, sin necesidad de estar en la misma red. Ambos dispositivos activan el modo público, se ven mutuamente, y el receptor acepta o rechaza la transferencia. Sin passphrase compartida — la clave se negocia al momento mediante criptografía asimétrica (X25519). El discovery es cifrado, diseñado para ser difícil de interceptar o suplantar.

---

## ¿En qué plataformas funciona?

| Plataforma | Estado |
|---|---|
| Windows | ✅ Disponible |
| Linux | ✅ Disponible |
| Android | ✅ Disponible (app nativa) |
| macOS | ❌ Sin planes (requiere licencia de Apple) |

---

## ¿Por qué no X alternativa?

- **KDE Connect**: solo entre Linux/Android con KDE, requiere configuración
- **Snapdrop / PairDrop**: web, sin cifrado E2E real, sin app nativa
- **AirDrop**: solo Apple
- **Nearby Share**: requiere cuenta Google, depende de servidores de Google

FenixHub no depende de ninguna cuenta, ninguna empresa y ningún servidor. El código es abierto. Los datos no salen de tu red.

---

## Stack técnico (para los curiosos)

- **Desktop**: Tauri v2 (Rust + TypeScript/Vite) — app nativa con webview
- **Android**: app nativa con webview integrada
- **Transporte**: HTTP efímero sobre red local, TLS en modo público
- **Cifrado**: AES-256-GCM (simétrico, grupo), X25519 ECDH (modo público)
- **Discovery**: mDNS (`_fenixhub._tcp`) en modo grupo, BLE + WiFi Direct en modo público
- **Sin dependencias externas**: cero servidores, cero cuentas, cero telemetría