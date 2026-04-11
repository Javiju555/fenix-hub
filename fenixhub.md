# FenixHub

## Una frase

FenixHub convierte todos tus dispositivos en un portapapeles compartido, cifrado y sin nube.

## Que problema resuelve

Si trabajas entre movil, laptop y desktop, acabas copiandote texto por chat, enviandote archivos a ti mismo o usando servicios externos para algo que deberia ser inmediato.

FenixHub elimina eso:
- Copias en un dispositivo.
- Aparece en los demas.
- Lo pegas o lo guardas en segundos.

Sin cuenta. Sin servidor central. Sin dependencia de terceros.

## Que hace hoy (estado real)

### Grupo privado en red local
- Comparticion de texto, imagenes y archivos entre dispositivos del mismo grupo.
- Discovery automatico con mDNS.
- Transferencia de contenido cifrado punto a punto.

### Seguridad activa
- Cifrado de contenido con AES-256-GCM.
- Firma canonica de requests (`method/path/group_id/timestamp/nonce/body-hash`).
- Anti-replay (ventana temporal + nonce unico).
- `group_id` derivado con HKDF dedicado.
- Politica minima de passphrase para evitar claves debiles.

### Robustez de sesion
- Publicacion efimera con TTL.
- Auto-stop al detectar cambio de red durante sesion activa.

### Cercania tipo AirDrop (base ya integrada)
- Inventario de hardware por llamada para LAN/BLE/Wi-Fi Direct.
- Android: discovery por BLE + discovery de peers Wi-Fi Direct + candidatos de handoff.
- Desktop (Tauri): snapshot de capacidades/adaptadores por llamada.

Importante: el canal final de transferencia completa sobre Wi-Fi Direct aun esta en roadmap.

## Arquitectura resumida

- Desktop: Tauri v2 (Rust + frontend web).
- Android: app nativa con bridge web.
- Discovery privado: mDNS.
- Cifrado y protocolo: core Rust + paridad Kotlin.
- Sin servicios cloud obligatorios.

## Por que importa

- Privacidad: tus datos no pasan por un servidor de terceros.
- Velocidad: transferencia local directa.
- Control: codigo abierto, stack auditable.
- Friccion cero: menos pasos, menos contexto roto.

## Roadmap corto (para release OSS fuerte)

1. Canal de transferencia cercano completo sobre Wi-Fi Direct.
2. Benchmarks y tuning de velocidad por plataforma.
3. Repo publico limpio con historial filtrado.

## Estado de plataforma

| Plataforma | Estado |
|---|---|
| Windows | operativo |
| Linux | operativo |
| Android | operativo |
| macOS | no priorizado |

## Copys listos para publicar

### Instagram (corto)
"Copiar en el movil y pegar en el PC, sin nube y sin cuenta. Eso es FenixHub: portapapeles compartido entre tus dispositivos, cifrado y open source."

### LinkedIn (producto)
"Estoy construyendo FenixHub: un portapapeles compartido entre dispositivos, sin servidor central y con cifrado end-to-end. Ya funciona en Windows, Linux y Android para texto, imagenes y archivos en red local. Estamos cerrando seguridad, rendimiento y modo de cercania tipo AirDrop para llevarlo a release open source."

### LinkedIn (tecnico)
"FenixHub ya integra firma canonica de requests, anti-replay, cifrado AES-GCM y base BLE + Wi-Fi Direct para discovery cercano. Siguiente paso: canal final de transferencia Wi-Fi Direct y optimizacion de streaming para reducir RAM en archivos grandes."

### X / micro-post
"FenixHub = portapapeles compartido entre tus dispositivos, sin nube, sin cuenta, cifrado y open source."

## Mensaje clave de marca

Tu contenido, en tus dispositivos, bajo tus reglas.
