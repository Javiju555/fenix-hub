# FenixHub — Plan de trabajo

> Generado el 2026-04-11. Para paralelizar entre modelos.  
> Cada tarea es independiente salvo que se indique dependencia.

---

## Contexto del proyecto

FenixHub es una app de portapapeles/transferencia de archivos entre dispositivos en red local.  
Tiene dos superficies de UI:

| Superficie | Stack |
|---|---|
| **Desktop** (Windows/Linux) | Tauri 2 + HTML/CSS/JS vanilla (TypeScript) |
| **Android overlay** | Android Service con WebView (carga el mismo HTML/CSS/JS) |

### Archivos clave de frontend

```
frontend/
  src/
    main.ts      ← Hub desktop (TypeScript, fuente)
    main.js      ← Hub desktop (JavaScript, copia manual — SIEMPRE actualizar junto a main.ts)
    overlay.ts   ← Overlay Android (TypeScript, fuente)
    overlay.js   ← Overlay Android (JavaScript, copia manual — SIEMPRE junto a overlay.ts)
    overlay.css  ← Estilos del overlay
    android.ts   ← App principal Android (WebView de la actividad principal)
    android.js   ← Copia manual de android.ts
```

> **IMPORTANTE**: Los archivos `.ts` y `.js` deben mantenerse en sincronía manual.  
> Tras editar el `.ts`, refleja los mismos cambios en el `.js` correspondiente.  
> Build: `cd frontend && bun run build`

### Android overlay — cómo funciona

- `OverlayController.kt` crea un `WindowManager.LayoutParams TYPE_APPLICATION_OVERLAY` con un `WebView`.
- El WebView carga `https://appassets.androidplatform.net/assets/overlay.html` (generado del build).
- `OverlayWebBridge.kt` expone un JS bridge (`FenixHubBridge`) para que el JS llame comandos nativos.
- Tamaño del panel: **360dp ancho, 540dp alto**, máx 60% ancho / 74% alto en portrait. Esquina top-end (derecha).
- Minimizado: círculo de **58dp** en esquina top-end.

---

## TAREA 1 — Drag-to-scroll horizontal en Desktop

**Prioridad**: Alta  
**Dificultad**: Fácil (~30 min)  
**Archivos**: `frontend/src/main.ts`, `frontend/src/main.js`, CSS de app (buscar en `frontend/src/`)

### Problema

El hub desktop tiene un scroll horizontal con cards de contenido local y de peers.  
La barra de scroll nativa del sistema es imposible de agarrar en una ventana de 34px de altura.

### Objetivo

- Scroll horizontal arrastrando el ratón en cualquier zona vacía entre/fuera de las cards.
- Ocultar la scrollbar nativa (scroll invisible).
- El contenedor scrollable en "Red" es `#panel-red` (contiene `.card-grid`).
- El contenedor scrollable en "Local" es `#panel-local` (contiene `.card-grid`).

### Implementación

**JS** — añadir drag-scroll a ambos paneles tras renderizar las cards.  
Llama a esta función después de `renderLocalContent()` y `renderPeerContent()`:

```typescript
function attachDragScroll(el: HTMLElement) {
  let isDown = false;
  let startX = 0;
  let scrollLeft = 0;

  el.addEventListener('mousedown', (e) => {
    // Solo drag en zona sin botones (el target debe ser el contenedor o .card-grid, no un botón)
    if ((e.target as HTMLElement).closest('button, a, input')) return;
    isDown = true;
    el.style.cursor = 'grabbing';
    startX = e.pageX - el.offsetLeft;
    scrollLeft = el.scrollLeft;
    e.preventDefault();
  });

  el.addEventListener('mouseleave', () => { isDown = false; el.style.cursor = ''; });
  el.addEventListener('mouseup',    () => { isDown = false; el.style.cursor = ''; });

  el.addEventListener('mousemove', (e) => {
    if (!isDown) return;
    const x    = e.pageX - el.offsetLeft;
    const walk = (x - startX) * 1.4;
    el.scrollLeft = scrollLeft - walk;
  });
}
```

Aplicar en `renderLocalContent()` y `renderPeerContent()` justo tras settear `innerHTML`:

```typescript
// al final de renderLocalContent():
attachDragScroll(document.getElementById('panel-local')!);

// al final de renderPeerContent():
attachDragScroll(document.getElementById('panel-red')!);
```

**CSS** — ocultar scrollbar en los paneles (buscar el selector `.tab-panel` o añadir a `#panel-local, #panel-red`):

```css
#panel-local,
#panel-red {
  scrollbar-width: none;          /* Firefox */
  -ms-overflow-style: none;       /* IE/Edge legacy */
}
#panel-local::-webkit-scrollbar,
#panel-red::-webkit-scrollbar {
  display: none;                  /* Chrome/Safari/WebView2 */
}
```

> Verificar que el CSS de app esté en `frontend/src/app.css` o similar. Si no existe ese selector, búscalo en los estilos inline del `main.ts`.

---

## TAREA 2 — Rediseño del overlay Android

**Prioridad**: Alta  
**Dificultad**: Media (~2-3h)  
**Archivos**: `frontend/src/overlay.ts`, `frontend/src/overlay.js`, `frontend/src/overlay.css`  
**Sin cambios en Kotlin** (a menos que se cambie el tamaño del panel).

### Problema actual

El overlay tiene un footer siempre visible con una grid de 5 botones grandes (WiFi Direct, Mandar a, Publicar, Borrar, Pegar). Esto ocupa demasiado espacio, se ve saturado y no es funcional en un panel pequeño.

### Referencia visual

Huawei SuperHub en Android: panel vertical estrecho, cards apiladas, **sin botones permanentes**. Las acciones aparecen solo al tocar una card (bottom sheet).

### Diseño objetivo

```
┌──────────────────────────────┐
│ 🔥 FenixHub    [−] [×]      │  ← header compacto
├──────────────────────────────┤
│ [Local 2] [Red 0]  [Abrir→] │  ← tabs + botón abrir
├──────────────────────────────┤
│ ┌──────────────────────────┐ │
│ │ 📝 TEXT                  │ │  ← card local
│ │ Texto reciente desde...  │ │
│ │ 28 B                     │ │
│ └──────────────────────────┘ │
│ ┌──────────────────────────┐ │
│ │ 🖼 IMAGE  • LIVE         │ │  ← card published
│ │ foto-urgente.jpg         │ │
│ │ 640 KB                   │ │
│ └──────────────────────────┘ │
│          (scroll)            │
├──────────────────────────────┤
│ [📋 Pegar]      [✕ Cerrar]  │  ← footer mínimo (2 pills)
└──────────────────────────────┘

Al tocar una card → bottom sheet slide-up:
┌──────────────────────────────┐
│ foto-urgente.jpg             │
│ ┌──────────┐ ┌────────────┐ │
│ │ 📡 Todos │ │ 🛑 Parar   │ │
│ └──────────┘ └────────────┘ │
│ ┌──────────┐ ┌────────────┐ │
│ │ 🗑 Borrar │ │ ↗ Directo │ │
│ └──────────┘ └────────────┘ │
│ [Cancelar]                   │
└──────────────────────────────┘
```

### Cambios en `overlay.ts`

#### 1. Estructura HTML del shell (función `render()`)

Cambiar `grid-template-rows` de `auto auto 1fr auto` a `auto auto 1fr auto`.  
El `<footer class="overlay-actions">` se reemplaza por un footer mínimo:

```typescript
// Reemplazar la sección <footer> en render() por:
`<footer class="overlay-footer-bar">
  <button class="overlay-footer-btn" id="act-paste">${iconClipboard(16)} Pegar</button>
  <button class="overlay-footer-btn subtle" id="act-close-overlay">${iconX(16)} Cerrar</button>
</footer>`
```

Eventos del nuevo footer:
```typescript
document.getElementById('act-paste')!.addEventListener('click', () => void copyOrPasteLocal());
document.getElementById('act-close-overlay')!.addEventListener('click', () => void invoke('close_overlay'));
```

#### 2. Eliminar `renderActions()` del flujo principal

- Eliminar `<section id="overlay-actions">` del HTML del shell.
- Eliminar la llamada `renderActions()` en `update()`.
- Mantener `renderActions` solo como función privada que genera el contenido del sheet (ver paso 3).

#### 3. Acción al tocar una card → bottom sheet

Crear función `showActionSheet(item, tab)`:

```typescript
function showActionSheet(item: ContentItem | PeerAnnouncement, tab: 'local' | 'red') {
  // Crear backdrop + sheet con las acciones del item
  // Usar las clases CSS ya existentes: overlay-sheet-backdrop, overlay-sheet, overlay-sheet-item
  const backdrop = document.createElement('div');
  backdrop.className = 'overlay-sheet-backdrop';

  const isLocal = tab === 'local';
  const localItem = item as ContentItem;
  const peerItem = item as PeerAnnouncement;

  let buttonsHtml = '';
  if (isLocal) {
    const pub = localItem.is_published;
    buttonsHtml = `
      <button class="overlay-sheet-item" data-sheet-action="publish">
        ${pub ? '⏹ Parar difusión' : '📡 Publicar para todos'}
      </button>
      ${pub ? `<button class="overlay-sheet-item" data-sheet-action="direct">↗ Mandar directo</button>` : ''}
      <button class="overlay-sheet-item" data-sheet-action="copy">${iconCopy(16)} Copiar</button>
      <button class="overlay-sheet-item danger" data-sheet-action="delete">${iconTrash(16)} Borrar</button>
      <button class="overlay-sheet-item ghost" data-sheet-action="cancel">Cancelar</button>
    `;
  } else {
    buttonsHtml = `
      <button class="overlay-sheet-item success" data-sheet-action="download">${iconDownload(16)} Descargar</button>
      <button class="overlay-sheet-item" data-sheet-action="copy">${iconCopy(16)} Copiar directo</button>
      <button class="overlay-sheet-item danger" data-sheet-action="ignore">${iconMute(16)} Ignorar</button>
      <button class="overlay-sheet-item ghost" data-sheet-action="cancel">Cancelar</button>
    `;
  }

  backdrop.innerHTML = `
    <div class="overlay-sheet">
      <div class="overlay-sheet-title">${escapeHtml(
        isLocal ? (localItem.file_name || localItem.preview.slice(0, 40)) : (peerItem.file_name || peerItem.preview.slice(0, 40))
      )}</div>
      ${buttonsHtml}
    </div>
  `;

  // Cerrar al tocar fuera del sheet
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) backdrop.remove();
  });

  // Manejar acciones
  backdrop.querySelectorAll<HTMLButtonElement>('[data-sheet-action]').forEach(btn => {
    btn.addEventListener('click', async () => {
      backdrop.remove();
      const action = btn.dataset.sheetAction!;
      if (action === 'cancel') return;

      if (isLocal) {
        const id = localItem.id;
        if (action === 'publish')  await togglePublishSingle(localItem);
        if (action === 'copy')     await copySingleLocal(id);
        if (action === 'delete')   await deleteSingleLocal(id);
        if (action === 'direct')   await startDirectModeFromOverlay();
      } else {
        const id = peerItem.content_id;
        if (action === 'download') await downloadSinglePeer(id);
        if (action === 'copy')     await copySinglePeer(id);
        if (action === 'ignore')   await ignoreSinglePeer(id);
      }
    });
  });

  document.getElementById('app')!.appendChild(backdrop);
}
```

Adaptar los helpers de acción existentes (`getLocalTargets`, `togglePublishTargets`, etc.) para versiones "single-item" si no existen.

#### 4. Click en card → llamar al sheet (en `renderCards()`)

```typescript
// En el listener de cards locales:
button.addEventListener('click', () => {
  const id = button.dataset.localId!;
  const item = localContent.find(i => i.id === id);
  if (item) showActionSheet(item, 'local');
});

// En el listener de cards peer:
button.addEventListener('click', () => {
  const id = button.dataset.peerId!;
  const item = peerContent.find(i => i.content_id === id);
  if (item) showActionSheet(item, 'red');
});
```

Eliminar `selectedLocalId` y `selectedPeerId` del estado (ya no son necesarios para resaltar selección).

### Cambios en `overlay.css`

#### 1. Nuevo footer mínimo

```css
.overlay-footer-bar {
  display: flex;
  gap: 8px;
  padding-top: 4px;
}

.overlay-footer-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--overlay-border);
  color: var(--overlay-text);
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  touch-action: manipulation;
}

.overlay-footer-btn.subtle {
  background: rgba(255, 255, 255, 0.04);
  color: var(--overlay-dim);
}
```

#### 2. Sheet items con variantes de color

```css
.overlay-sheet-item.danger {
  color: var(--overlay-red);
}

.overlay-sheet-item.success {
  color: var(--overlay-green);
}
```

#### 3. Eliminar estilos obsoletos (opcional, no breaking)

Las clases `.overlay-actions`, `.overlay-grid`, `.overlay-hint`, `.overlay-close-row` ya no se usan.  
Pueden dejarse o eliminarse.

---

## TAREA 3 — Eliminar base64 preview de imágenes (limpieza)

**Prioridad**: Baja  
**Dificultad**: Fácil  
**Archivos**: `android/app/src/main/java/com/fenixhub/mobile/network/NsdController.kt`  
**Contexto**: El campo `preview` del anuncio mDNS intenta enviar una miniatura base64 de imágenes, pero el límite del TXT record (~1000 bytes totales) hace que nunca quepa para imágenes reales. Siempre se trunca y muestra el fallback de texto.

### Cambio

En `NsdController.kt`, función `previewForAnnouncement()` (línea ~404):

```kotlin
// ANTES:
private fun previewForAnnouncement(item: LocalContent): String {
    if (item.contentType != HubContentType.IMAGE) {
        return item.preview
    }
    return runCatching {
        PreviewUtils.imageAnnouncementPreviewDataUrl(File(item.cachePath).readBytes())
    }.getOrElse {
        item.fileName?.let { fileName -> "Imagen: ${fileName.take(48)}" } ?: "Imagen lista para descargar"
    }
}

// DESPUÉS (eliminar el intento de base64, ir directo al fallback de texto):
private fun previewForAnnouncement(item: LocalContent): String {
    if (item.contentType != HubContentType.IMAGE) {
        return item.preview
    }
    return item.fileName?.let { "Imagen: ${it.take(48)}" } ?: "Imagen lista para descargar"
}
```

Esto reduce el tamaño del anuncio mDNS, deja más espacio para `fileName` y evita trabajo de CPU innecesario.

---

## TAREA 4 — Settings: fondo más opaco (CSS cosmético)

**Prioridad**: Baja  
**Dificultad**: Trivial  
**Archivos**: `frontend/src/` — buscar estilos de `.settings-panel` o `#settings` en los CSS/TS

La ventana de settings en desktop tiene el fondo demasiado transparente. Aumentar la opacidad del backdrop/fondo del panel de settings a ~0.97-0.99.

---

## Dependencias entre tareas

```
TAREA 1 (drag-scroll)    → independiente
TAREA 2 (overlay)        → independiente
TAREA 3 (base64)         → independiente
TAREA 4 (settings CSS)   → independiente
```

Todas son paralelizables. Tras cada tarea: `cd frontend && bun run build` para verificar que compila.

---

## Notas para el modelo ejecutor

1. **Siempre** editar `.ts` Y `.js` en paralelo — son copias manuales.
2. El mock data en `overlay.ts` (variables `mockLocal`, `mockPeers`) sirve para probar la UI en browser sin Android — no borrarlos.
3. Las funciones de acción ya existen en `overlay.ts` (`sendLocalTargets`, `togglePublishTargets`, `deleteLocalTargets`, `copyOrPasteLocal`, `downloadPeerTargets`, `ignorePeerTargets`, `copyPeerTargets`). Reutilizarlas o crear variantes single-item.
4. El bridge de Android (`invoke()`) funciona igual para Tauri y Android — no hay diferencia a nivel TS.
5. Para verificar sin dispositivo Android, abrir `dist/overlay.html` en Chrome DevTools con dimensiones 360×540.
