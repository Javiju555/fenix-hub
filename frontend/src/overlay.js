import './overlay.css';
const IS_TAURI = '__TAURI_INTERNALS__' in window;
const IS_NATIVE_ANDROID = 'FenixHubBridge' in window;
const POLL_INTERVAL_MS = 8000;
const LOGO_SRC = './logo-mark.png';
let nativeBridgeReady = false;
let nativeRequestId = 0;
let pollHandle = null;
let listenersBound = false;
const nativePending = new Map();
let localContent = [];
let peerContent = [];
let onlineDevices = [];
let activeTab = 'local';
let selectedLocalId = null;
let selectedPeerId = null;
let overlayMinimized = false;
function localFingerprint(item) {
    return [
        item.id,
        item.content_type,
        item.preview,
        item.size_bytes,
        item.created_at,
        item.file_name ?? '',
        item.mime_type ?? '',
        item.is_published ? '1' : '0',
    ].join('|');
}
function peerFingerprint(item) {
    return [
        item.group_id,
        item.content_id,
        item.device_name,
        item.preview,
        item.content_type,
        item.size_bytes,
        item.created_at,
        item.port,
        item.file_name ?? '',
        item.mime_type ?? '',
        JSON.stringify(item.send_mode),
    ].join('|');
}
function stateFingerprint(local, peers) {
    const localPart = local.map(localFingerprint).join('||');
    const peerPart = peers.map(peerFingerprint).join('||');
    return `${localPart}###${peerPart}`;
}
function upsertPeerAnnouncement(announcement) {
    const existing = peerContent.find(item => item.content_id === announcement.content_id);
    if (existing && peerFingerprint(existing) === peerFingerprint(announcement)) {
        return false;
    }
    peerContent = [announcement, ...peerContent.filter(item => item.content_id !== announcement.content_id)];
    return true;
}
let mockLocal = [
    {
        id: 'local-1',
        content_type: 'text',
        preview: 'Texto reciente desde overlay',
        size_bytes: 28,
        created_at: Date.now(),
        mime_type: 'text/plain; charset=utf-8',
        is_published: false,
    },
    {
        id: 'local-2',
        content_type: 'image',
        preview: 'foto-urgente.jpg',
        size_bytes: 640000,
        created_at: Date.now() - 1000,
        file_name: 'foto-urgente.jpg',
        mime_type: 'image/jpeg',
        is_published: true,
    },
];
let mockPeers = [
    {
        group_id: 'demo',
        content_id: 'peer-1',
        device_name: 'Portátil',
        preview: 'factura.pdf',
        content_type: 'file',
        size_bytes: 84000,
        send_mode: { Broadcast: null },
        created_at: Date.now(),
        port: 0,
        file_name: 'factura.pdf',
        mime_type: 'application/pdf',
    },
];
export async function initOverlay() {
    document.body.classList.add('overlay-mode');
    document.addEventListener('contextmenu', event => event.preventDefault());
    ensureNativeBridge();
    window.__fenixOverlayRefresh = async () => {
        await refreshState();
    };
    window.__fenixOverlaySetMinimized = (minimized) => {
        overlayMinimized = minimized;
        render();
    };
    await loadState();
    render();
    setupListeners();
}
async function invoke(cmd, args) {
    if (IS_TAURI) {
        const { invoke: tauriInvoke } = await import('@tauri-apps/api/core');
        return tauriInvoke(cmd, args);
    }
    if (IS_NATIVE_ANDROID) {
        return invokeNative(cmd, args);
    }
    return invokeMock(cmd, args);
}
function ensureNativeBridge() {
    if (!IS_NATIVE_ANDROID || nativeBridgeReady)
        return;
    window.__fenixResolve = (id, payload) => {
        const pending = nativePending.get(id);
        if (!pending)
            return;
        nativePending.delete(id);
        pending.resolve(payload);
    };
    window.__fenixReject = (id, message) => {
        const pending = nativePending.get(id);
        if (!pending)
            return;
        nativePending.delete(id);
        pending.reject(new Error(message));
    };
    nativeBridgeReady = true;
}
function invokeNative(cmd, args) {
    ensureNativeBridge();
    const bridge = window.FenixHubBridge;
    if (!bridge) {
        return Promise.reject(new Error('Puente Android no disponible'));
    }
    const id = `overlay-${nativeRequestId++}`;
    const request = JSON.stringify({
        id,
        cmd,
        args: args ?? null,
    });
    return new Promise((resolve, reject) => {
        nativePending.set(id, {
            resolve: payload => resolve(payload),
            reject,
        });
        bridge.postMessage(request);
    });
}
async function invokeMock(cmd, args) {
    const a = args;
    await new Promise(resolve => setTimeout(resolve, 80));
    switch (cmd) {
        case 'get_local_content':
            return [...mockLocal];
        case 'get_peers':
            return [...mockPeers];
        case 'paste_clipboard_text': {
            const item = {
                id: `local-${Date.now()}`,
                content_type: 'text',
                preview: 'Texto pegado del sistema',
                size_bytes: 24,
                created_at: Date.now(),
                mime_type: 'text/plain; charset=utf-8',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'publish_content': {
            const contentId = a?.args?.content_id;
            mockLocal = mockLocal.map(item => item.id === contentId ? { ...item, is_published: true } : item);
            return undefined;
        }
        case 'unpublish_content': {
            const contentId = a?.content_id;
            mockLocal = mockLocal.map(item => item.id === contentId ? { ...item, is_published: false } : item);
            return undefined;
        }
        case 'remove_content':
            mockLocal = mockLocal.filter(item => item.id !== a?.id);
            return undefined;
        case 'copy_local_content':
            return 'Contenido copiado al portapapeles';
        case 'pull_peer_content': {
            const contentId = a?.content_id;
            const peer = mockPeers.find(item => item.content_id === contentId);
            if (peer) {
                mockLocal = [
                    {
                        id: `local-${Date.now()}`,
                        content_type: peer.content_type,
                        preview: peer.preview,
                        size_bytes: peer.size_bytes,
                        created_at: Date.now(),
                        file_name: peer.file_name,
                        mime_type: peer.mime_type,
                        is_published: false,
                    },
                    ...mockLocal,
                ];
            }
            mockPeers = mockPeers.filter(item => item.content_id !== contentId);
            return undefined;
        }
        case 'copy_peer_content':
            return 'Contenido remoto copiado';
        case 'ignore_peer_content':
            mockPeers = mockPeers.filter(item => item.content_id !== a?.content_id);
            return undefined;
        case 'open_full_app':
            return undefined;
        case 'minimize_overlay':
            overlayMinimized = true;
            return true;
        case 'expand_overlay':
            overlayMinimized = false;
            return true;
        case 'close_overlay':
            return true;
        default:
            return undefined;
    }
}
function listen(event, cb) {
    if (IS_TAURI) {
        import('@tauri-apps/api/event').then(({ listen: tauriListen }) => tauriListen(event, cb));
        return;
    }
    return Promise.resolve(() => { });
}
function setupListeners() {
    if (listenersBound)
        return;
    listenersBound = true;
    listen('peer-content-available', ({ payload }) => {
        const announcement = payload.announcement;
        if (!upsertPeerAnnouncement(announcement))
            return;
        syncOnlineDevices();
        update();
    });
    listen('peer-content-gone', ({ payload }) => {
        if (!peerContent.some(item => item.content_id === payload.content_id))
            return;
        peerContent = peerContent.filter(item => item.content_id !== payload.content_id);
        if (selectedPeerId === payload.content_id) {
            selectedPeerId = null;
        }
        syncOnlineDevices();
        update();
    });
    if (IS_NATIVE_ANDROID && pollHandle === null) {
        pollHandle = window.setInterval(() => {
            if (!document.hidden) {
                void refreshState();
            }
        }, POLL_INTERVAL_MS);
    }
}
async function loadState() {
    [localContent, peerContent] = await Promise.all([
        invoke('get_local_content'),
        invoke('get_peers'),
    ]);
    if (selectedLocalId && !localContent.some(item => item.id === selectedLocalId)) {
        selectedLocalId = null;
    }
    if (selectedPeerId && !peerContent.some(item => item.content_id === selectedPeerId)) {
        selectedPeerId = null;
    }
    syncOnlineDevices();
}
async function refreshState() {
    try {
        const before = stateFingerprint(localContent, peerContent);
        await loadState();
        const after = stateFingerprint(localContent, peerContent);
        if (before !== after) {
            update();
        }
    }
    catch (error) {
        console.error(error);
    }
}
function syncOnlineDevices() {
    onlineDevices = [...new Set(peerContent.map(item => item.device_name))];
}
function render() {
    if (overlayMinimized) {
        document.getElementById('app').innerHTML = `
      <div class="overlay-mini-shell">
        <button class="overlay-mini-main" id="overlay-mini-main" title="Restaurar FenixHub">
          <img class="overlay-mini-logo" src="${LOGO_SRC}" alt="FenixHub" />
        </button>
        <button class="overlay-mini-close" id="overlay-mini-close" title="Cerrar overlay">${iconX(11)}</button>
      </div>
    `;
        document.getElementById('overlay-mini-main').addEventListener('click', async () => {
            await invoke('expand_overlay');
            overlayMinimized = false;
            render();
        });
        document.getElementById('overlay-mini-close').addEventListener('click', async () => {
            await invoke('close_overlay');
        });
        return;
    }
    document.getElementById('app').innerHTML = `
    <div class="overlay-shell">
      <header class="overlay-header">
        <div class="overlay-brand">
          <img class="overlay-logo" src="${LOGO_SRC}" alt="FenixHub" />
          <span>FenixHub</span>
        </div>
        <div class="overlay-window-actions">
          <button class="overlay-control" id="overlay-minimize" title="Minimizar">${iconMinus(14)}</button>
          <button class="overlay-control danger" id="overlay-close" title="Cerrar">${iconX(14)}</button>
        </div>
      </header>
      <div class="overlay-tabs-row">
        <div class="overlay-tab-group">
          <button class="overlay-tab ${activeTab === 'local' ? 'active' : ''}" data-tab="local">Local</button>
          <button class="overlay-tab ${activeTab === 'red' ? 'active' : ''}" data-tab="red">Red</button>
        </div>
        <button class="overlay-expand" id="overlay-expand">${iconExpand(16)} Abrir</button>
      </div>
      <section class="overlay-stack" id="overlay-stack"></section>
      <footer class="overlay-actions" id="overlay-actions"></footer>
    </div>
  `;
    document.querySelectorAll('.overlay-tab').forEach(button => {
        button.addEventListener('click', () => {
            const nextTab = button.dataset.tab;
            activeTab = nextTab;
            selectedLocalId = null;
            selectedPeerId = null;
            update();
        });
    });
    document.getElementById('overlay-expand').addEventListener('click', async () => {
        await invoke('open_full_app');
    });
    document.getElementById('overlay-minimize').addEventListener('click', async () => {
        await invoke('minimize_overlay');
        overlayMinimized = true;
        render();
    });
    document.getElementById('overlay-close').addEventListener('click', async () => {
        await invoke('close_overlay');
    });
    update();
}
function update() {
    if (overlayMinimized)
        return;
    document.querySelectorAll('.overlay-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.tab === activeTab);
    });
    renderCards();
    renderActions();
}
function renderCards() {
    const stack = document.getElementById('overlay-stack');
    if (!stack)
        return;
    if (activeTab === 'local') {
        if (localContent.length === 0) {
            stack.innerHTML = emptyState('Nada local', 'Pulsa Pegar para capturar el portapapeles.');
            return;
        }
        stack.innerHTML = localContent.map(item => {
            const selected = selectedLocalId === item.id;
            return `
        <button class="overlay-card ${selected ? 'selected' : ''}" data-local-id="${item.id}">
          <div class="overlay-card-top">
            <span class="overlay-type ${item.content_type}">${typeLabel(item.content_type)}</span>
            ${item.is_published ? '<span class="overlay-live">LIVE</span>' : ''}
          </div>
          <div class="overlay-title">${escapeHtml(item.file_name || item.preview)}</div>
          <div class="overlay-meta">${humanSize(item.size_bytes)}</div>
        </button>
      `;
        }).join('');
        stack.querySelectorAll('[data-local-id]').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.localId;
                selectedLocalId = selectedLocalId === id ? null : id;
                update();
            });
        });
    }
    else {
        if (peerContent.length === 0) {
            stack.innerHTML = emptyState('Sin peers', 'Los anuncios de red aparecerán aquí.');
            return;
        }
        stack.innerHTML = peerContent.map(item => {
            const selected = selectedPeerId === item.content_id;
            return `
        <button class="overlay-card peer ${selected ? 'selected' : ''}" data-peer-id="${item.content_id}">
          <div class="overlay-card-top">
            <span class="overlay-type ${item.content_type}">${typeLabel(item.content_type)}</span>
            <span class="overlay-device">${escapeHtml(item.device_name)}</span>
          </div>
          <div class="overlay-title">${escapeHtml(item.file_name || item.preview)}</div>
          <div class="overlay-meta">${humanSize(item.size_bytes)}</div>
        </button>
      `;
        }).join('');
        stack.querySelectorAll('[data-peer-id]').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.peerId;
                selectedPeerId = selectedPeerId === id ? null : id;
                update();
            });
        });
    }
}
function renderActions() {
    const actions = document.getElementById('overlay-actions');
    if (!actions)
        return;
    if (activeTab === 'local') {
        const targets = getLocalTargets();
        const anyPublished = targets.some(item => item.is_published);
        const allPublished = targets.length > 0 && targets.every(item => item.is_published);
        actions.innerHTML = `
      <div class="overlay-hint">${localHint(targets.length)}</div>
      <div class="overlay-grid">
        <button class="overlay-action" id="act-send">${iconSend(18)} Mandar a</button>
        <button class="overlay-action" id="act-publish">${iconBroadcast(18)} ${allPublished ? 'Parar' : anyPublished ? 'Publicar resto' : 'Publicar'}</button>
        <button class="overlay-action danger" id="act-delete">${iconTrash(18)} Borrar</button>
        <button class="overlay-action" id="act-paste">${iconClipboard(18)} Pegar</button>
      </div>
      <div class="overlay-close-row">
        <button class="overlay-action subtle" id="act-close-overlay">${iconX(18)} Cerrar overlay</button>
      </div>
    `;
        document.getElementById('act-send').addEventListener('click', () => {
            void sendLocalTargets();
        });
        document.getElementById('act-publish').addEventListener('click', () => {
            void togglePublishTargets();
        });
        document.getElementById('act-delete').addEventListener('click', () => {
            void deleteLocalTargets();
        });
        document.getElementById('act-paste').addEventListener('click', () => {
            void copyOrPasteLocal();
        });
    }
    else {
        const targets = getPeerTargets();
        actions.innerHTML = `
      <div class="overlay-hint">${peerHint(targets.length)}</div>
      <div class="overlay-grid">
        <button class="overlay-action success" id="act-download">${iconDownload(18)} Descargar</button>
        <button class="overlay-action danger" id="act-ignore">${iconMute(18)} Ignorar</button>
        <button class="overlay-action" id="act-copy">${iconCopy(18)} Copiar</button>
        <button class="overlay-action" id="act-open">${iconExpand(18)} Abrir</button>
      </div>
      <div class="overlay-close-row">
        <button class="overlay-action subtle" id="act-close-overlay">${iconX(18)} Cerrar overlay</button>
      </div>
    `;
        document.getElementById('act-download').addEventListener('click', () => {
            void downloadPeerTargets();
        });
        document.getElementById('act-ignore').addEventListener('click', () => {
            void ignorePeerTargets();
        });
        document.getElementById('act-copy').addEventListener('click', () => {
            void copyPeerTargets();
        });
        document.getElementById('act-open').addEventListener('click', async () => {
            await invoke('open_full_app');
        });
    }
    document.getElementById('act-close-overlay')?.addEventListener('click', async () => {
        await invoke('close_overlay');
    });
}
function getLocalTargets() {
    if (selectedLocalId) {
        return localContent.filter(item => item.id === selectedLocalId);
    }
    return localContent;
}
function getPeerTargets() {
    if (selectedPeerId) {
        return peerContent.filter(item => item.content_id === selectedPeerId);
    }
    return peerContent;
}
async function sendLocalTargets() {
    const targets = getLocalTargets();
    if (targets.length === 0) {
        showToast('No hay contenido local');
        return;
    }
    if (onlineDevices.length === 0) {
        showToast('No hay peers conectados');
        return;
    }
    if (onlineDevices.length === 1) {
        await sendToDevice(onlineDevices[0]);
        return;
    }
    openDeviceSheet();
}
async function sendToDevice(device) {
    try {
        for (const item of getLocalTargets()) {
            await invoke('publish_content', { args: { content_id: item.id, target_device: device } });
        }
        showToast(`Enviado a ${device}`);
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function togglePublishTargets() {
    const targets = getLocalTargets();
    if (targets.length === 0) {
        showToast('No hay contenido local');
        return;
    }
    const allPublished = targets.every(item => item.is_published);
    try {
        if (allPublished) {
            for (const item of targets) {
                await invoke('unpublish_content', { content_id: item.id });
            }
            showToast(targets.length === 1 ? 'Emisión detenida' : 'Emisiones detenidas');
        }
        else {
            for (const item of targets.filter(entry => !entry.is_published)) {
                await invoke('publish_content', { args: { content_id: item.id, target_device: null } });
            }
            showToast(targets.length === 1 ? 'Contenido publicado' : 'Contenido publicado en lote');
        }
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function deleteLocalTargets() {
    const targets = getLocalTargets();
    if (targets.length === 0) {
        showToast('No hay contenido local');
        return;
    }
    try {
        for (const item of targets) {
            await invoke('remove_content', { id: item.id });
        }
        selectedLocalId = null;
        showToast(targets.length === 1 ? 'Contenido borrado' : 'Lote borrado');
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function copyOrPasteLocal() {
    if (selectedLocalId) {
        try {
            const message = await invoke('copy_local_content', { id: selectedLocalId });
            showToast(message || 'Copiado al portapapeles');
        }
        catch (error) {
            showToast(errorMessage(error));
        }
        return;
    }
    try {
        await invoke('paste_clipboard_text');
        showToast('Portapapeles añadido al hub');
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function downloadPeerTargets() {
    const targets = getPeerTargets();
    if (targets.length === 0) {
        showToast('No hay peers visibles');
        return;
    }
    try {
        for (const item of targets) {
            await invoke('pull_peer_content', { content_id: item.content_id });
        }
        selectedPeerId = null;
        showToast(targets.length === 1 ? 'Descarga completada' : 'Descargas completadas');
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function ignorePeerTargets() {
    const targets = getPeerTargets();
    if (targets.length === 0) {
        showToast('No hay peers visibles');
        return;
    }
    try {
        for (const item of targets) {
            await invoke('ignore_peer_content', { content_id: item.content_id });
        }
        selectedPeerId = null;
        showToast(targets.length === 1 ? 'Peer ignorado' : 'Peers ignorados');
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function copyPeerTargets() {
    const targets = getPeerTargets();
    if (targets.length === 0) {
        showToast('No hay peers visibles');
        return;
    }
    try {
        for (const item of targets) {
            await invoke('copy_peer_content', { content_id: item.content_id });
        }
        selectedPeerId = null;
        showToast(targets.length === 1 ? 'Peer copiado al sistema' : 'Peers copiados');
        await refreshState();
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
function openDeviceSheet() {
    const backdrop = document.createElement('div');
    backdrop.className = 'overlay-sheet-backdrop';
    backdrop.innerHTML = `
    <div class="overlay-sheet">
      <div class="overlay-sheet-title">Mandar a</div>
      ${onlineDevices.map(device => `<button class="overlay-sheet-item" data-device="${escapeAttribute(device)}">${escapeHtml(device)}</button>`).join('')}
      <button class="overlay-sheet-item ghost" data-close="1">Cancelar</button>
    </div>
  `;
    backdrop.addEventListener('click', event => {
        if (event.target === backdrop) {
            backdrop.remove();
        }
    });
    backdrop.querySelectorAll('.overlay-sheet-item').forEach(button => {
        button.addEventListener('click', () => {
            const device = button.dataset.device;
            backdrop.remove();
            if (device) {
                void sendToDevice(device);
            }
        });
    });
    document.body.appendChild(backdrop);
}
function localHint(count) {
    if (selectedLocalId) {
        return 'Card seleccionada: acciones individuales.';
    }
    if (count === 0) {
        return 'Sin cards locales.';
    }
    return `Sin selección: acciones masivas sobre ${count} cards.`;
}
function peerHint(count) {
    if (selectedPeerId) {
        return 'Peer seleccionado: acciones individuales.';
    }
    if (count === 0) {
        return 'Sin peers visibles.';
    }
    return `Sin selección: acciones masivas sobre ${count} peers.`;
}
function emptyState(title, description) {
    return `
    <div class="overlay-empty">
      <div class="overlay-empty-title">${escapeHtml(title)}</div>
      <div class="overlay-empty-copy">${escapeHtml(description)}</div>
    </div>
  `;
}
function showToast(message) {
    const toast = document.createElement('div');
    toast.className = 'overlay-toast';
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 240);
    }, 1800);
}
function errorMessage(error) {
    if (error instanceof Error && error.message) {
        return error.message;
    }
    return 'Operación no completada';
}
function humanSize(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1048576)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1048576).toFixed(1)} MB`;
}
function typeLabel(type) {
    if (type === 'text')
        return 'Texto';
    if (type === 'image')
        return 'Imagen';
    return 'Archivo';
}
function escapeHtml(value) {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
function escapeAttribute(value) {
    return escapeHtml(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
const icon = (paths, size = 18) => `<svg width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
function iconSend(size = 18) {
    return icon('<path d="M3 10h10"/><path d="M9 4l6 6-6 6"/><path d="M15 10h2"/>', size);
}
function iconBroadcast(size = 18) {
    return icon('<circle cx="5" cy="10" r="1.5"/><circle cx="15" cy="10" r="1.5"/><path d="M7 10c2-4 4-4 6 0"/><path d="M7 10c2 4 4 4 6 0"/>', size);
}
function iconTrash(size = 18) {
    return icon('<path d="M4 6h12"/><path d="M7 6V4h6v2"/><path d="M6.5 6l.8 10h5.4l.8-10"/>', size);
}
function iconClipboard(size = 18) {
    return icon('<rect x="5" y="4" width="10" height="12" rx="2"/><path d="M8 4V3.5A1.5 1.5 0 0 1 9.5 2h1A1.5 1.5 0 0 1 12 3.5V4"/>', size);
}
function iconDownload(size = 18) {
    return icon('<path d="M10 3v9"/><path d="M6 9l4 4 4-4"/><path d="M4 17h12"/>', size);
}
function iconMute(size = 18) {
    return icon('<path d="M4 8h3l4-3v10l-4-3H4z"/><path d="M14 7l3 6"/><path d="M17 7l-3 6"/>', size);
}
function iconCopy(size = 18) {
    return icon('<rect x="7" y="5" width="9" height="11" rx="2"/><path d="M5 13H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v1"/>', size);
}
function iconExpand(size = 18) {
    return icon('<path d="M7 5H5v2"/><path d="M13 5h2v2"/><path d="M5 13v2h2"/><path d="M15 13v2h-2"/><path d="M5 5l4 4"/><path d="M15 5l-4 4"/><path d="M5 15l4-4"/><path d="M15 15l-4-4"/>', size);
}
function iconMinus(size = 18) {
    return icon('<path d="M4 10h12"/>', size);
}
function iconX(size = 18) {
    return icon('<path d="M5 5l10 10"/><path d="M15 5L5 15"/>', size);
}
initOverlay();
