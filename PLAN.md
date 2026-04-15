# FenixHub — Plan de trabajo

> Actualizado 2026-04-11. Para paralelizar entre modelos.  
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
```

> **CRÍTICO**: Los archivos `.ts` y `.js` son copias manuales — NO hay transpilador automático.  
> Tras editar el `.ts`, refleja los mismos cambios en el `.js` correspondiente (mismo orden de funciones, mismo contenido, sin tipos TypeScript).  
> Build: `cd frontend && bun run build`  
> Verificar tipos: `cd frontend && npx tsc --noEmit` (debe terminar sin errores — tsconfig está en modo strict)

### Estado del tsconfig

`frontend/tsconfig.json` tiene `"noUnusedLocals": true` y `"noUnusedParameters": true`.  
**No desactivar estas flags**. Si el build falla por código no usado, eliminar el código, no deshabilitar la comprobación.

---

## TAREAS COMPLETADAS (referencia)

| # | Tarea | Estado |
|---|---|---|
| 1 | Drag-to-scroll horizontal en Desktop (`main.ts`/`main.js`) | ✅ DONE |
| 2 | Rediseño overlay Android — bottom sheet en lugar de footer de botones | ✅ DONE |
| 3 | Eliminar base64 preview de imágenes en `NsdController.kt` | ✅ DONE |
| 4 | Settings CSS — fondo más opaco | ✅ DONE |
| 5 | Limpieza código muerto overlay.ts/overlay.js | ✅ DONE |
| 6 | Android edge-drag trigger + slide-in desde derecha | ✅ DONE |
| 7 | Android FIFO 25-item limit en historial local | ✅ DONE |

---

## TAREA 5 — Limpieza de código muerto en overlay.ts / overlay.js

**Prioridad**: URGENTE (el código no compila limpio hasta que esto esté hecho)  
**Dificultad**: Fácil — solo borrar bloques  
**Archivos**: `frontend/src/overlay.ts`, `frontend/src/overlay.js`

### Contexto

El rediseño del overlay (Tarea 2) eliminó `renderActions()` y el sistema de selección, dejando funciones huérfanas que ya no se llaman desde ningún sitio. Además, en un paso previo ya se eliminaron `sendToDevice`, `sendToDeviceSingle` y `deleteLocalTargets` del `.ts`, pero `openDeviceSheet` y `openDeviceSheetSingle` aún las referencian — el `.ts` no compila limpio ahora mismo.

### Qué eliminar de `overlay.ts`

Eliminar **completas** las siguientes funciones (búscalas por nombre, no por número de línea):

1. **`downloadPeerTargets()`** — itera todos los peers, no tiene caller
2. **`ignorePeerTargets()`** — itera todos los peers, no tiene caller
3. **`copyPeerTargets()`** — itera todos los peers, no tiene caller
4. **`openDeviceSheet()`** — abría un sheet para elegir dispositivo, no tiene caller; además referencia `sendToDevice` que ya no existe
5. **`openDeviceSheetSingle(id)`** — igual, referencia `sendToDeviceSingle` que ya no existe
6. **`iconSend(size)`** — SVG helper, no tiene caller
7. **`iconBroadcast(size)`** — SVG helper, no tiene caller
8. **`iconWifi(size)`** — SVG helper, no tiene caller

### Cómo verificar

Tras borrar, ejecutar:

```bash
cd frontend && npx tsc --noEmit
```

Debe terminar **sin errores ni warnings**. Si aparece algún "declared but never read" adicional, borrarlo también.

### Sincronizar overlay.js

Después de limpiar el `.ts`, aplicar los mismos borrados en `overlay.js`:
- Las mismas funciones existen en el `.js` (sin tipos, sin `async`/`function` keyword differences)
- Busca `function downloadPeerTargets`, `function ignorePeerTargets`, etc. y borra cada bloque completo
- El `.js` no necesita compilar con tsc, pero debe ser estructuralmente idéntico al `.ts` sin tipos

---

## TAREA 6 — Android: disparador de overlay por arrastre al borde derecho

**Prioridad**: Media  
**Dificultad**: Media (~2h)  
**Archivos**: Android — buscar en `android/app/src/main/java/com/fenixhub/mobile/`

### Comportamiento deseado

Cuando el usuario tiene un archivo seleccionado (long-press activo sobre un archivo en cualquier app) y lo arrastra hacia el **borde derecho** de la pantalla, el overlay de FenixHub debe aparecer deslizándose desde la derecha.

No hace falta que el archivo "caiga" sobre el overlay — solo que se acerque al borde (~80px del borde derecho) para que el overlay aparezca. El usuario puede entonces soltar el archivo sobre el overlay para transferirlo, o ignorar el overlay si lo arrastra de vuelta.

### Referencia

Huawei SuperHub hace exactamente esto: el panel emerge desde la derecha cuando detecta un drag cerca del borde. Es más predecible que agitar el dispositivo.

### Implementación sugerida

#### Opción A — Accessibility Service (recomendada)

Android puede registrar un `AccessibilityService` con `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` o usar `WindowManager` con `FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH` para detectar eventos de toque globales.

Sin embargo, la opción más limpia es añadir un **trigger strip** transparente: una vista de ~20dp de ancho pegada al borde derecho, siempre visible, que al recibir un `DragEvent.ACTION_DRAG_ENTERED` o `ACTION_DRAG_LOCATION` lanza el overlay.

#### Pasos

1. **Crear `EdgeTriggerView`** — `View` o `FrameLayout` de 20dp × MATCH_PARENT, transparente, con `alpha=0` (invisible pero clickable).  
   Registrarla en `WindowManager` con:
   ```kotlin
   val params = WindowManager.LayoutParams(
       dpToPx(20), WindowManager.LayoutParams.MATCH_PARENT,
       WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
       WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
       WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
       PixelFormat.TRANSLUCENT
   )
   params.gravity = Gravity.END or Gravity.TOP
   ```

2. **Añadir listener de drag**:
   ```kotlin
   edgeTriggerView.setOnDragListener { _, event ->
       when (event.action) {
           DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
               if (!overlayVisible) showOverlay()
           }
           DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
               // Overlay sigue visible hasta que el usuario lo cierre
           }
       }
       true
   }
   ```

3. **Animación de entrada desde la derecha** — en `OverlayController.kt`, al llamar `showOverlay()`, animar con:
   ```kotlin
   overlayView.translationX = overlayView.width.toFloat()
   overlayView.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
   ```

4. **Arrancar `EdgeTriggerView` desde el mismo servicio que lanza el overlay** — probablemente `OverlayService.kt` o similar. Buscar dónde se crea el `WindowManager` para el overlay y añadir la vista trigger en el mismo `onCreate()`.

### Notas

- La `EdgeTriggerView` solo debe estar activa cuando el servicio overlay esté corriendo (el usuario tiene FenixHub activo).
- No requiere cambios en frontend JS.
- Permiso necesario ya existe: `SYSTEM_ALERT_WINDOW` (ya usado por el overlay).

---

## TAREA 7 — Android: límite FIFO de 25 items en historial local

**Prioridad**: Media  
**Dificultad**: Fácil  
**Archivos**: Buscar `TempClipboardStore`, `LocalContentStore`, o similar en `android/app/src/main/java/com/fenixhub/mobile/`

### Problema

El historial de contenido local en Android no tiene límite. Con el tiempo puede acumular decenas o cientos de items, consumiendo almacenamiento y haciendo el overlay inutilizable.

### Objetivo

Limitar el historial a **25 items**. Cuando se añade el item 26, el más antiguo (por `created_at` ascendente) se elimina automáticamente — FIFO.

### Implementación

1. Localizar la clase que persiste el historial local (busca `insert`, `add`, `save` junto a `LocalContent` o `ContentItem`).

2. Añadir constante:
   ```kotlin
   private const val MAX_HISTORY_ITEMS = 25
   ```

3. Tras insertar un nuevo item, comprobar y purgar:
   ```kotlin
   fun addItem(item: LocalContent) {
       store.add(item)
       // Purgar si supera el límite
       if (store.size > MAX_HISTORY_ITEMS) {
           val sorted = store.sortedBy { it.createdAt }
           val toRemove = sorted.take(store.size - MAX_HISTORY_ITEMS)
           toRemove.forEach { store.remove(it) }
       }
   }
   ```

   Si el store usa una base de datos (Room/SQLite), hacer la purga con una query:
   ```kotlin
   // Borrar los más antiguos que excedan el límite
   dao.deleteOldestBeyondLimit(MAX_HISTORY_ITEMS)
   ```
   
   Query Room equivalente:
   ```kotlin
   @Query("DELETE FROM local_content WHERE id NOT IN (SELECT id FROM local_content ORDER BY created_at DESC LIMIT :limit)")
   fun deleteOldestBeyondLimit(limit: Int)
   ```

4. Verificar que la purga también elimina los archivos en caché asociados (si `LocalContent` tiene un `cachePath` o similar).

---

## Dependencias entre tareas nuevas

```
TAREA 5 (dead code)     → independiente, HACER PRIMERO (desbloquea build limpio)
TAREA 6 (edge-drag)     → independiente de 5 y 7
TAREA 7 (FIFO limit)    → independiente de 5 y 6
```

Todas las tareas nuevas son paralelizables entre sí, salvo que conviene hacer la 5 primero para tener el build limpio.

---

## Notas para el modelo ejecutor

1. **SIEMPRE** editar `.ts` Y `.js` en paralelo — son copias manuales sin transpilador.
2. **NO** desactivar flags de tsconfig (`noUnusedLocals`, `noUnusedParameters`) para ocultar errores — borrar el código muerto en su lugar.
3. El mock data en `overlay.ts` (`mockLocal`, `mockPeers`) sirve para probar en browser sin Android — **no borrarlo**.
4. Para verificar sin Android: abrir `dist/overlay.html` en Chrome DevTools con dimensiones 360×540.
5. Build frontend: `cd frontend && bun run build`. Verificar tipos: `cd frontend && npx tsc --noEmit`.
