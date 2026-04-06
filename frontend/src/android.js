import './android.css';
const IS_TAURI = '__TAURI_INTERNALS__' in window;
const IS_NATIVE_ANDROID = 'FenixHubBridge' in window;
const POLL_INTERVAL_MS = 10000;
const LOGO_SRC = './logo-mark.png';
let nativeBridgeReady = false;
let nativeRequestId = 0;
let pollHandle = null;
let listenersRegistered = false;
const nativePending = new Map();
let mockLocal = [
    {
        id: 'a1',
        content_type: 'text',
        preview: 'Prueba desde el navegador',
        size_bytes: 25,
        created_at: Date.now(),
        file_name: null,
        mime_type: 'text/plain',
    },
    {
        id: 'a3',
        content_type: 'image',
        preview: 'foto.png',
        size_bytes: 1100000,
        created_at: Date.now() - 120000,
        file_name: 'foto.png',
        mime_type: 'image/png',
    },
];
let mockPeers = [
    {
        group_id: 'demo',
        content_id: 'p1',
        device_name: 'Laptop Trabajo',
        preview: 'Recibo.pdf',
        content_type: 'file',
        size_bytes: 145000,
        send_mode: { Broadcast: null },
        created_at: Date.now(),
        port: 0,
        file_name: 'Recibo.pdf',
        mime_type: 'application/pdf',
    },
];
let mockPublished = new Set();
let mockId = 100;
let configured = false;
let identity = null;
let localContent = [];
let peerContent = [];
let publishedIds = new Set();
let onlineDevices = [];
let activeTab = 'local';
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
export async function initAndroid() {
    document.body.classList.add('android-mode');
    ensureNativeBridge();
    window.__fenixExternalRefresh = async () => {
        await refreshState();
    };
    identity = await invoke('get_identity');
    if (!identity?.configured) {
        renderSetup();
        setupEventListeners();
        return;
    }
    await loadContent();
    renderApp();
    setupEventListeners();
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
    const id = `android-${nativeRequestId++}`;
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
    await new Promise(resolve => setTimeout(resolve, 120));
    switch (cmd) {
        case 'get_identity':
            return { device_name: 'Mi Móvil', group_id: 'demo', configured };
        case 'get_local_content':
            return mockLocal.map(item => ({
                ...item,
                is_published: mockPublished.has(item.id),
            }));
        case 'get_peers':
            return [...mockPeers];
        case 'add_text_content': {
            const text = a?.text;
            const item = {
                id: String(mockId++),
                content_type: 'text',
                preview: text.slice(0, 80),
                size_bytes: text.length,
                created_at: Date.now(),
                file_name: null,
                mime_type: 'text/plain; charset=utf-8',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'paste_clipboard_text': {
            const item = {
                id: String(mockId++),
                content_type: 'text',
                preview: 'Texto pegado del portapapeles',
                size_bytes: 28,
                created_at: Date.now(),
                file_name: null,
                mime_type: 'text/plain; charset=utf-8',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'pick_file': {
            const item = {
                id: String(mockId++),
                content_type: 'file',
                preview: 'mock-file.bin',
                size_bytes: 120000,
                created_at: Date.now(),
                file_name: 'mock-file.bin',
                mime_type: 'application/octet-stream',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'add_binary_content': {
            const payload = a?.args ?? {
                file_name: 'archivo',
            };
            const item = {
                id: String(mockId++),
                content_type: payload.mime_type?.startsWith('image/') ? 'image' : 'file',
                preview: payload.preview || payload.file_name || 'archivo',
                size_bytes: 0,
                created_at: Date.now(),
                file_name: payload.file_name,
                mime_type: payload.mime_type ?? 'application/octet-stream',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'copy_local_content':
            return 'Contenido copiado al portapapeles';
        case 'remove_content':
            mockLocal = mockLocal.filter(item => item.id !== a?.id);
            mockPublished.delete(a?.id);
            return undefined;
        case 'publish_content': {
            const contentId = a?.args?.content_id;
            if (contentId)
                mockPublished.add(contentId);
            return undefined;
        }
        case 'unpublish_content': {
            const contentId = a?.content_id;
            mockPublished.delete(contentId);
            return undefined;
        }
        case 'stop_server':
            mockPublished.clear();
            return undefined;
        case 'pull_peer_content': {
            const contentId = a?.content_id;
            const peer = mockPeers.find(item => item.content_id === contentId);
            mockPeers = mockPeers.filter(item => item.content_id !== contentId);
            const item = {
                id: String(mockId++),
                content_type: peer?.content_type || 'file',
                preview: peer?.preview || '',
                size_bytes: peer?.size_bytes || 0,
                created_at: Date.now(),
                file_name: peer?.file_name,
                mime_type: peer?.mime_type,
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'open_overlay':
            return true;
        case 'setup_identity':
            configured = true;
            return {
                device_name: a?.args?.device_name ?? 'Device',
                group_id: 'demo',
                configured: true,
            };
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
async function loadContent() {
    [localContent, peerContent] = await Promise.all([
        invoke('get_local_content'),
        invoke('get_peers'),
    ]);
    syncDerivedState();
}
function setupEventListeners() {
    if (listenersRegistered)
        return;
    listenersRegistered = true;
    listen('peer-content-available', ({ payload }) => {
        const announcement = payload.announcement;
        if (!upsertPeerAnnouncement(announcement))
            return;
        syncDerivedState();
        updateUI();
    });
    listen('peer-content-gone', ({ payload }) => {
        if (!peerContent.some(item => item.content_id === payload.content_id))
            return;
        peerContent = peerContent.filter(item => item.content_id !== payload.content_id);
        syncDerivedState();
        updateUI();
    });
    listen('direct-notify-received', ({ payload }) => {
        const announcement = payload.announcement;
        if (!upsertPeerAnnouncement(announcement) && activeTab === 'red')
            return;
        activeTab = 'red';
        syncDerivedState();
        updateUI();
    });
    if (IS_NATIVE_ANDROID && pollHandle === null) {
        pollHandle = window.setInterval(() => {
            if (!document.hidden) {
                void refreshState();
            }
        }, POLL_INTERVAL_MS);
    }
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            void refreshState();
        }
    });
    window.addEventListener('focus', () => {
        void refreshState();
    });
}
async function refreshState() {
    try {
        const before = stateFingerprint(localContent, peerContent);
        await loadContent();
        const after = stateFingerprint(localContent, peerContent);
        if (before !== after) {
            updateUI();
        }
    }
    catch (error) {
        console.error(error);
    }
}
function syncDerivedState() {
    publishedIds = new Set(localContent.filter(item => item.is_published).map(item => item.id));
    onlineDevices = [...new Set(peerContent.map(item => item.device_name))];
}
function renderSetup() {
    document.getElementById('app').innerHTML = `
    <div class="android-setup">
      <div class="a-setup-brand">
        <div class="logo-wrapper">${iconHub(56)}</div>
        <span class="a-kicker">LOCAL TRANSFER</span>
        <h1>FenixHub</h1>
        <p>Comparte texto, imagenes y archivos del movil al resto de tus equipos sin cuenta y sin nube.</p>
      </div>
      <div class="a-setup-panel">
        <input type="password" id="passphrase" placeholder="Frase de acceso de red" autocomplete="off" />
        <input type="text" id="device-name" placeholder="Nombre de este movil" value="${escapeAttribute(identity?.device_name || '')}" />
        <button id="setup-btn">Activar Hub</button>
        <div class="a-setup-note">Usa la misma frase en todos tus dispositivos para entrar en la misma red efimera.</div>
      </div>
    </div>`;
    document.getElementById('setup-btn').addEventListener('click', async () => {
        const passphrase = document.getElementById('passphrase').value.trim();
        const deviceName = document.getElementById('device-name').value.trim();
        if (!passphrase || !deviceName) {
            showToast('Necesitas frase y nombre de dispositivo');
            return;
        }
        const button = document.getElementById('setup-btn');
        button.disabled = true;
        button.textContent = 'Activando...';
        try {
            identity = await invoke('setup_identity', {
                args: { passphrase, device_name: deviceName },
            });
            await loadContent();
            renderApp();
            showToast('Hub activado');
        }
        catch (error) {
            button.disabled = false;
            button.textContent = 'Activar Hub';
            showToast(errorMessage(error));
        }
    });
}
function renderApp() {
    const deviceName = escapeHtml(identity?.device_name || 'Mi movil');
    const groupLabel = escapeHtml(shortGroupLabel(identity?.group_id));
    document.getElementById('app').innerHTML = `
    <div class="android-layout">
      <header class="a-hero">
        <div class="a-brand-card">
          <div class="a-brand-mark">${iconHub(52)}</div>
          <div class="a-brand-copy">
            <span class="a-kicker">LOCAL TRANSFER</span>
            <div class="a-brand-row">
              <h1>FenixHub</h1>
              <div class="a-status-pill">
                <div class="a-status-dot scanning" id="status-dot"></div>
                <span id="status-text">Buscando</span>
              </div>
            </div>
            <p>${deviceName} · ${groupLabel}</p>
          </div>
        </div>
        <div class="a-hero-actions">
          <div class="a-header-actions">
            <button class="a-chip-btn accent" id="btn-clipboard">${iconClipboard(16)} Capturar</button>
            <button class="a-chip-btn" id="btn-overlay">${iconOverlay(16)} Overlay</button>
          </div>
        </div>
        <div class="a-stat-strip">
          <div class="a-stat-card">
            <span>Local</span>
            <strong id="stat-local">0</strong>
          </div>
          <div class="a-stat-card">
            <span>Peers</span>
            <strong id="stat-peers">0</strong>
          </div>
          <div class="a-stat-card">
            <span>Live</span>
            <strong id="stat-live">0</strong>
          </div>
        </div>
      </header>
      <main class="a-content" id="a-content-area"></main>
      <input id="android-file-picker" class="a-hidden-input" type="file" accept="*/*" />
      <button class="a-fab" id="fab-add" style="display:${activeTab === 'local' ? 'flex' : 'none'};">
        ${iconPlus(24)}
      </button>
      <nav class="a-bottom-nav">
        <button class="a-nav-btn ${activeTab === 'local' ? 'active' : ''}" data-tab="local">
          ${iconInbox(18)} Mi Hub <span class="a-nav-badge" id="badge-local">0</span>
        </button>
        <button class="a-nav-btn ${activeTab === 'red' ? 'active' : ''}" data-tab="red">
          ${iconWifi(18)} Red <span class="a-nav-badge" id="badge-red">0</span>
        </button>
      </nav>
    </div>`;
    document.querySelectorAll('.a-nav-btn').forEach(button => {
        button.addEventListener('click', () => {
            const tab = button.dataset.tab;
            switchTab(tab);
        });
    });
    document.getElementById('fab-add').addEventListener('click', openAddSheet);
    document.getElementById('btn-clipboard').addEventListener('click', () => {
        void window.androidActions?.pasteClipboard();
    });
    document.getElementById('btn-overlay').addEventListener('click', () => {
        void window.androidActions?.openOverlay();
    });
    document.getElementById('android-file-picker').addEventListener('change', event => {
        const file = event.currentTarget.files?.[0];
        if (!file)
            return;
        void addBrowserFile(file);
        event.currentTarget.value = '';
    });
    updateUI();
}
function switchTab(tab) {
    activeTab = tab;
    document.querySelectorAll('.a-nav-btn').forEach(button => {
        button.classList.toggle('active', button.dataset.tab === tab);
    });
    const fab = document.getElementById('fab-add');
    if (fab) {
        fab.style.display = tab === 'local' ? 'flex' : 'none';
    }
    updateUI();
}
function updateUI() {
    const localBadge = document.getElementById('badge-local');
    const peerBadge = document.getElementById('badge-red');
    const localStat = document.getElementById('stat-local');
    const peerStat = document.getElementById('stat-peers');
    const liveStat = document.getElementById('stat-live');
    if (localBadge)
        localBadge.textContent = String(localContent.length);
    if (peerBadge)
        peerBadge.textContent = String(peerContent.length);
    if (localStat)
        localStat.textContent = String(localContent.length);
    if (peerStat)
        peerStat.textContent = String(onlineDevices.length);
    if (liveStat)
        liveStat.textContent = String(publishedIds.size);
    const statusDot = document.getElementById('status-dot');
    const statusText = document.getElementById('status-text');
    if (statusDot && statusText) {
        if (onlineDevices.length > 0) {
            statusDot.className = 'a-status-dot online';
            statusText.textContent = `${onlineDevices.length} disp`;
        }
        else {
            statusDot.className = 'a-status-dot scanning';
            statusText.textContent = 'Buscando';
        }
    }
    const contentArea = document.getElementById('a-content-area');
    if (!contentArea)
        return;
    if (activeTab === 'local') {
        renderLocalContent(contentArea);
    }
    else {
        renderPeerContent(contentArea);
    }
}
function renderLocalContent(area) {
    const header = `
    <section class="a-pane-head">
      <div>
        <span class="a-pane-kicker">Hub local</span>
        <h2>Contenido listo para salir</h2>
      </div>
      <p>${publishedIds.size > 0 ? `${publishedIds.size} emisiones activas ahora mismo.` : 'Pega, importa o comparte desde cualquier app para llenar tu buffer local.'}</p>
    </section>
  `;
    if (localContent.length === 0) {
        area.innerHTML = `${header}<div class="a-empty">${iconInbox(48)}<p>Tu hub local esta vacio.</p></div>`;
        return;
    }
    area.innerHTML = `${header}<div class="a-card-stack">${localContent
        .map(item => {
        const isLive = publishedIds.has(item.id);
        const media = renderMediaPreview(item);
        const directAction = onlineDevices.length > 0
            ? `<button class="a-btn a-btn-secondary" onclick="window.androidActions.chooseDirect('${item.id}')">Mandar a...</button>`
            : '';
        const actions = isLive
            ? `
            <button class="a-btn a-btn-danger" onclick="window.androidActions.stop('${item.id}')">Parar live</button>
            <button class="a-btn a-btn-secondary" onclick="window.androidActions.copy('${item.id}')">Copiar</button>
          `
            : `
            <button class="a-btn a-btn-primary" onclick="window.androidActions.broadcast('${item.id}')">Lanzar</button>
            ${directAction}
            <button class="a-btn a-btn-secondary" onclick="window.androidActions.copy('${item.id}')">Copiar</button>
          `;
        return `
        <div class="a-card ${isLive ? 'broadcasting' : ''}" data-id="${item.id}">
          ${media}
          <button class="a-btn-delete-float" onclick="window.androidActions.remove('${item.id}')">${iconX(16)}</button>
          <div class="a-card-body">
            <div class="a-card-eyebrow">
              <span class="a-type-pill ${item.content_type}">${escapeHtml(contentTypeLabel(item.content_type))}</span>
              ${isLive ? '<span class="a-live-pill">LIVE</span>' : '<span class="a-meta-pill">READY</span>'}
            </div>
            <div class="a-card-preview">${escapeHtml(item.file_name || item.preview)}</div>
            <div class="a-card-meta">
              <span>${humanSize(item.size_bytes)}</span>
              <span>${formatRelativeTime(item.created_at)}</span>
            </div>
            <div class="a-card-actions">${actions}</div>
          </div>
        </div>`;
    })
        .join('')}</div>`;
}
function renderPeerContent(area) {
    const header = `
    <section class="a-pane-head">
      <div>
        <span class="a-pane-kicker">Red</span>
        <h2>Transmisiones detectadas</h2>
      </div>
      <p>${onlineDevices.length > 0 ? `${onlineDevices.length} dispositivos anunciando contenido cerca de ti.` : 'En cuanto otro equipo publique algo, aparecera aqui.'}</p>
    </section>
  `;
    if (peerContent.length === 0) {
        area.innerHTML = `${header}<div class="a-empty">${iconWifi(48)}<p>Buscando dispositivos cercanos...</p></div>`;
        return;
    }
    area.innerHTML = `${header}<div class="a-card-stack">${peerContent
        .map(item => {
        const media = renderPeerMediaPreview(item);
        return `
        <div class="a-card peer" data-id="${item.content_id}">
          ${media}
          <div class="a-card-body">
            <div class="a-card-eyebrow">
              <span class="a-type-pill ${item.content_type}">${escapeHtml(contentTypeLabel(item.content_type))}</span>
              <span class="a-peer-pill">${escapeHtml(item.device_name)}</span>
            </div>
            <div class="a-card-preview">${escapeHtml(item.file_name || item.preview)}</div>
            <div class="a-card-meta">
              <span>${humanSize(item.size_bytes)}</span>
              <span>${formatRelativeTime(item.created_at)}</span>
            </div>
            <div class="a-card-actions">
              <button class="a-btn a-btn-success" id="btn-pull-${item.content_id}" onclick="window.androidActions.receive('${item.content_id}')">Guardar en local</button>
            </div>
          </div>
        </div>`;
    })
        .join('')}</div>`;
}
function renderMediaPreview(item) {
    if (item.content_type === 'image' && item.preview.startsWith('data:image')) {
        return `<div class="a-media a-media-image"><img class="a-card-img" src="${item.preview}" alt="" /></div>`;
    }
    return renderMediaShell(item.content_type, item.file_name || item.preview);
}
function renderPeerMediaPreview(item) {
    if (item.content_type === 'image' && item.preview.startsWith('data:image')) {
        return `<div class="a-media a-media-image"><img class="a-card-img" src="${item.preview}" alt="" /></div>`;
    }
    return renderMediaShell(item.content_type, item.file_name || item.preview);
}
function renderMediaShell(type, label) {
    return `
    <div class="a-media a-media-${type}">
      <div class="a-media-icon">${typeIcon(type)}</div>
      <div class="a-media-copy">${escapeHtml(contentTypeLabel(type))}</div>
      <div class="a-media-subcopy">${escapeHtml(compactLabel(label))}</div>
    </div>
  `;
}
function openAddSheet() {
    if (document.getElementById('a-sheet-backdrop'))
        return;
    const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-sheet-title">Añadir al hub</div>
    <p class="a-sheet-copy">Usa texto, portapapeles o un archivo local sin salir de la app.</p>
    <button class="a-sheet-btn" data-action="text">${iconText(20)} Texto rápido</button>
    <button class="a-sheet-btn" data-action="clipboard">${iconClipboard(20)} Portapapeles actual</button>
    <button class="a-sheet-btn" data-action="file">${iconFile(20)} Archivo o imagen</button>
    <button class="a-sheet-btn danger" data-action="cancel">Cancelar</button>
  `);
    const close = () => backdrop.remove();
    backdrop.querySelectorAll('.a-sheet-btn').forEach(button => {
        button.addEventListener('click', () => {
            const action = button.dataset.action;
            close();
            if (action === 'text') {
                openTextComposer();
            }
            else if (action === 'clipboard') {
                void window.androidActions?.pasteClipboard();
            }
            else if (action === 'file') {
                if (IS_NATIVE_ANDROID) {
                    void pickNativeFile();
                }
                else {
                    document.getElementById('android-file-picker')?.click();
                }
            }
        });
    });
}
function openTextComposer() {
    const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-sheet-title">Texto rápido</div>
    <p class="a-sheet-copy">Escribe, pega y lánzalo al hub local.</p>
    <textarea class="a-sheet-textarea" id="a-quick-text" placeholder="Escribe o pega aquí"></textarea>
    <div class="a-sheet-row">
      <button class="a-sheet-btn danger" data-action="cancel">Cancelar</button>
      <button class="a-sheet-btn" data-action="save">${iconCheck(18)} Guardar</button>
    </div>
  `);
    const close = () => backdrop.remove();
    const textarea = backdrop.querySelector('#a-quick-text');
    textarea?.focus();
    backdrop.querySelectorAll('.a-sheet-btn').forEach(button => {
        button.addEventListener('click', async () => {
            const action = button.dataset.action;
            if (action === 'cancel') {
                close();
                return;
            }
            const text = textarea?.value.trim() || '';
            if (!text) {
                showToast('No hay texto para añadir');
                return;
            }
            button.disabled = true;
            try {
                const item = await invoke('add_text_content', { text });
                localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
                syncDerivedState();
                updateUI();
                showToast('Texto añadido al hub');
                close();
            }
            catch (error) {
                button.disabled = false;
                showToast(errorMessage(error));
            }
        });
    });
}
function openDirectSheet(contentId) {
    if (onlineDevices.length === 0) {
        showToast('No hay peers activos');
        return;
    }
    if (onlineDevices.length === 1) {
        void window.androidActions?.direct(contentId, onlineDevices[0]);
        return;
    }
    const deviceButtons = onlineDevices
        .map(device => `<button class="a-sheet-btn" data-device="${escapeAttribute(device)}">${iconWifi(18)} ${escapeHtml(device)}</button>`)
        .join('');
    const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-sheet-title">Enviar directamente</div>
    <p class="a-sheet-copy">Selecciona el dispositivo destino para este contenido.</p>
    ${deviceButtons}
    <button class="a-sheet-btn danger" data-action="cancel">Cancelar</button>
  `);
    const close = () => backdrop.remove();
    backdrop.querySelectorAll('.a-sheet-btn').forEach(button => {
        button.addEventListener('click', () => {
            const device = button.dataset.device;
            close();
            if (device) {
                void window.androidActions?.direct(contentId, device);
            }
        });
    });
}
async function pickNativeFile() {
    try {
        const item = await invoke('pick_file');
        if (!item)
            return;
        localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
        syncDerivedState();
        updateUI();
        showToast(item.content_type === 'image' ? 'Imagen añadida al hub' : 'Archivo añadido al hub');
    }
    catch (error) {
        showToast(errorMessage(error));
    }
}
async function addBrowserFile(file) {
    const bytesBase64 = await fileToBase64(file);
    const preview = file.type.startsWith('image/') ? await imageFileToPreview(file) : undefined;
    const item = await invoke('add_binary_content', {
        args: {
            file_name: file.name || `clipboard-${Date.now()}`,
            mime_type: file.type || null,
            bytes_base64: bytesBase64,
            preview: preview || null,
        },
    });
    localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
    syncDerivedState();
    updateUI();
    showToast(file.type.startsWith('image/') ? 'Imagen añadida al hub' : 'Archivo añadido al hub');
}
async function fileToBase64(file) {
    const buffer = await file.arrayBuffer();
    let binary = '';
    const chunk = 0x8000;
    const bytes = new Uint8Array(buffer);
    for (let index = 0; index < bytes.length; index += chunk) {
        binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
    }
    return btoa(binary);
}
async function imageFileToPreview(file) {
    const bitmap = await createImageBitmap(file).catch(() => null);
    if (!bitmap)
        return undefined;
    const canvas = document.createElement('canvas');
    canvas.width = 72;
    canvas.height = 72;
    const ctx = canvas.getContext('2d');
    if (!ctx)
        return undefined;
    const scale = Math.max(canvas.width / bitmap.width, canvas.height / bitmap.height);
    const width = bitmap.width * scale;
    const height = bitmap.height * scale;
    ctx.drawImage(bitmap, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height);
    return canvas.toDataURL('image/jpeg', 0.58);
}
function createSheet(innerHtml) {
    const backdrop = document.createElement('div');
    backdrop.id = 'a-sheet-backdrop';
    backdrop.className = 'a-sheet-backdrop';
    backdrop.innerHTML = `<div class="a-sheet">${innerHtml}</div>`;
    backdrop.addEventListener('click', event => {
        if (event.target === backdrop) {
            backdrop.remove();
        }
    });
    document.body.appendChild(backdrop);
    return backdrop;
}
window.androidActions = {
    async broadcast(id) {
        try {
            await invoke('publish_content', { args: { content_id: id, target_device: null } });
            publishedIds.add(id);
            updateLocalItem(id, item => ({ ...item, is_published: true }));
            updateUI();
            showToast('Emitiendo a la red');
            await refreshStateIfNative();
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async stop(id) {
        try {
            await invoke('unpublish_content', { content_id: id });
            publishedIds.delete(id);
            updateLocalItem(id, item => ({ ...item, is_published: false }));
            updateUI();
            showToast('Emisión detenida');
            await refreshStateIfNative();
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async chooseDirect(id) {
        openDirectSheet(id);
    },
    async direct(id, device) {
        try {
            await invoke('publish_content', { args: { content_id: id, target_device: device } });
            publishedIds.add(id);
            updateLocalItem(id, item => ({ ...item, is_published: true }));
            updateUI();
            showToast(`Enviando a ${device}`);
            await refreshStateIfNative();
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async copy(id) {
        try {
            const message = await invoke('copy_local_content', { id });
            showToast(message || 'Contenido copiado');
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async remove(id) {
        try {
            await invoke('remove_content', { id });
            publishedIds.delete(id);
            localContent = localContent.filter(item => item.id !== id);
            syncDerivedState();
            updateUI();
            await refreshStateIfNative();
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async receive(id) {
        const button = document.getElementById(`btn-pull-${id}`);
        if (button) {
            button.disabled = true;
            button.textContent = 'Obteniendo...';
        }
        try {
            const received = await invoke('pull_peer_content', { content_id: id });
            localContent = [received, ...localContent.filter(item => item.id !== received.id)];
            peerContent = peerContent.filter(item => item.content_id !== id);
            syncDerivedState();
            updateUI();
            showToast('Descarga completada');
            await refreshStateIfNative();
        }
        catch (error) {
            if (button) {
                button.disabled = false;
                button.textContent = 'Guardar en local';
            }
            showToast(errorMessage(error));
        }
    },
    async openOverlay() {
        try {
            const opened = await invoke('open_overlay');
            showToast(opened ? 'Overlay abierto' : 'Permiso de overlay requerido');
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
    async pasteClipboard() {
        try {
            const item = await invoke('paste_clipboard_text');
            localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
            syncDerivedState();
            updateUI();
            showToast('Portapapeles añadido al hub');
        }
        catch (error) {
            showToast(errorMessage(error));
        }
    },
};
function updateLocalItem(id, mutate) {
    localContent = localContent.map(item => (item.id === id ? mutate(item) : item));
    syncDerivedState();
}
async function refreshStateIfNative() {
    if (IS_NATIVE_ANDROID) {
        await refreshState();
    }
}
function showToast(message) {
    const toast = document.createElement('div');
    toast.className = 'a-toast';
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity 0.3s';
        setTimeout(() => toast.remove(), 300);
    }, 2200);
}
function errorMessage(error) {
    if (error instanceof Error && error.message) {
        return error.message;
    }
    return 'Operación no completada';
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
function humanSize(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1048576)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1048576).toFixed(1)} MB`;
}
function formatRelativeTime(timestamp) {
    const normalized = timestamp < 1000000000000 ? timestamp * 1000 : timestamp;
    const diffMs = Math.max(0, Date.now() - normalized);
    const diffMinutes = Math.floor(diffMs / 60000);
    if (diffMinutes < 1)
        return 'ahora';
    if (diffMinutes < 60)
        return `hace ${diffMinutes} min`;
    const diffHours = Math.floor(diffMinutes / 60);
    if (diffHours < 24)
        return `hace ${diffHours} h`;
    const diffDays = Math.floor(diffHours / 24);
    return `hace ${diffDays} d`;
}
function contentTypeLabel(type) {
    if (type === 'text')
        return 'Texto';
    if (type === 'image')
        return 'Imagen';
    return 'Archivo';
}
function compactLabel(value) {
    const sanitized = value.trim();
    if (sanitized.length <= 34)
        return sanitized;
    return `${sanitized.slice(0, 31)}...`;
}
function shortGroupLabel(groupId) {
    if (!groupId)
        return 'red privada';
    return `grupo ${groupId.slice(0, 8)}`;
}
const svg = (size, viewBox, path, extra = 'stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"') => `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="none" ${extra}>${path}</svg>`;
function iconHub(size) {
    return `<img src="${LOGO_SRC}" alt="FenixHub" width="${size}" height="${size}" style="display:block;object-fit:contain;" />`;
}
function iconInbox(size) {
    return svg(size, '0 0 16 16', '<rect x="1.5" y="1.5" width="13" height="13" rx="2" stroke-width="1.7"/><polyline points="1.5,10 4.5,10 5.5,12.5 10.5,12.5 11.5,10 14.5,10" stroke-width="1.7"/>');
}
function iconWifi(size) {
    return svg(size, '0 0 16 16', '<path d="M1.5,6 Q8,1 14.5,6" stroke-width="1.7"/><path d="M3.5,9 Q8,5.5 12.5,9" stroke-width="1.7"/><path d="M5.5,12 Q8,10 10.5,12" stroke-width="1.7"/><circle cx="8" cy="14" r="0.8" fill="currentColor" stroke="none"/>');
}
function iconPlus(size) {
    return svg(size, '0 0 24 24', '<line x1="12" y1="5" x2="12" y2="19" stroke-width="2.5"/><line x1="5" y1="12" x2="19" y2="12" stroke-width="2.5"/>');
}
function iconX(size) {
    return svg(size, '0 0 14 14', '<line x1="2" y1="2" x2="12" y2="12" stroke-width="2"/><line x1="12" y1="2" x2="2" y2="12" stroke-width="2"/>');
}
function iconText(size) {
    return svg(size, '0 0 20 20', '<line x1="4" y1="6" x2="16" y2="6" stroke-width="1.8"/><line x1="4" y1="10" x2="16" y2="10" stroke-width="1.8"/><line x1="4" y1="14" x2="11" y2="14" stroke-width="1.8"/>');
}
function iconFile(size) {
    return svg(size, '0 0 20 20', '<path d="M12,2 H6 a2,2 0 0 0 -2,2 v12 a2,2 0 0 0 2,2 h8 a2,2 0 0 0 2,-2 V8 Z" stroke-width="1.8"/><polyline points="12,2 12,8 18,8" stroke-width="1.8"/>');
}
function iconClipboard(size) {
    return svg(size, '0 0 20 20', '<rect x="5" y="4" width="10" height="13" rx="2" stroke-width="1.7"/><path d="M8,4.5 V3.5 C8,2.7 8.7,2 9.5,2 h1 C11.3,2 12,2.7 12,3.5 v1" stroke-width="1.7"/>');
}
function iconOverlay(size) {
    return svg(size, '0 0 20 20', '<rect x="3" y="4" width="11" height="11" rx="2" stroke-width="1.7"/><path d="M8,15 h7 a2,2 0 0 0 2,-2 V6" stroke-width="1.7"/>');
}
function iconCheck(size) {
    return svg(size, '0 0 20 20', '<polyline points="4.5,10.5 8.2,14.2 15.5,6.8" stroke-width="2"/>');
}
function typeIcon(type) {
    if (type === 'text') {
        return svg(24, '0 0 24 24', '<line x1="5" y1="8" x2="19" y2="8" stroke-width="2.5"/><line x1="5" y1="12" x2="19" y2="12" stroke-width="2.5"/><line x1="5" y1="16" x2="13" y2="16" stroke-width="2.5"/>');
    }
    if (type === 'image') {
        return svg(24, '0 0 24 24', '<rect x="3" y="4" width="18" height="16" rx="3" stroke-width="2"/><circle cx="9" cy="10" r="2" stroke-width="2"/><polyline points="3,16 8,11 11,14 15,10 21,16" stroke-width="2"/>');
    }
    return svg(24, '0 0 24 24', '<path d="M14,3 H6 a2,2 0 0 0 -2,2 v14 a2,2 0 0 0 2,2 h12 a2,2 0 0 0 2,-2 V9 Z" stroke-width="2"/><polyline points="14,3 14,9 20,9" stroke-width="2"/>');
}
