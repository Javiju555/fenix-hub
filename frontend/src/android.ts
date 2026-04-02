import './android.css';

declare global {
  interface Window {
    FenixHubBridge?: {
      postMessage(message: string): void;
    };
    __fenixResolve?: (id: string, payload: unknown) => void;
    __fenixReject?: (id: string, message: string) => void;
    __fenixExternalRefresh?: () => Promise<void>;
    androidActions?: {
      broadcast(id: string): Promise<void>;
      stop(id: string): Promise<void>;
      chooseDirect(id: string): Promise<void>;
      direct(id: string, device: string): Promise<void>;
      copy(id: string): Promise<void>;
      remove(id: string): Promise<void>;
      receive(id: string): Promise<void>;
      openOverlay(): Promise<void>;
      pasteClipboard(): Promise<void>;
    };
  }
}

interface IdentityInfo {
  device_name: string;
  group_id: string;
  configured: boolean;
}

interface ContentItem {
  id: string;
  content_type: 'text' | 'image' | 'file';
  preview: string;
  size_bytes: number;
  created_at: number;
  file_name?: string | null;
  mime_type?: string | null;
  transfer_path?: string | null;
  is_published?: boolean;
}

interface PeerAnnouncement {
  group_id: string;
  content_id: string;
  device_name: string;
  preview: string;
  content_type: 'text' | 'image' | 'file';
  size_bytes: number;
  send_mode: { Broadcast: null } | { Direct: { target_device: string } };
  created_at: number;
  port: number;
  file_name?: string | null;
  mime_type?: string | null;
}

interface NativePendingRequest {
  resolve: (payload: unknown) => void;
  reject: (error: Error) => void;
}

const IS_TAURI = '__TAURI_INTERNALS__' in window;
const IS_NATIVE_ANDROID = 'FenixHubBridge' in window;
const POLL_INTERVAL_MS = 2_000;

let nativeBridgeReady = false;
let nativeRequestId = 0;
let pollHandle: number | null = null;
let listenersRegistered = false;

const nativePending = new Map<string, NativePendingRequest>();

let mockLocal: ContentItem[] = [
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
    size_bytes: 1_100_000,
    created_at: Date.now() - 120_000,
    file_name: 'foto.png',
    mime_type: 'image/png',
  },
];

let mockPeers: PeerAnnouncement[] = [
  {
    group_id: 'demo',
    content_id: 'p1',
    device_name: 'Laptop Trabajo',
    preview: 'Recibo.pdf',
    content_type: 'file',
    size_bytes: 145_000,
    send_mode: { Broadcast: null },
    created_at: Date.now(),
    port: 0,
    file_name: 'Recibo.pdf',
    mime_type: 'application/pdf',
  },
];

let mockPublished = new Set<string>();
let mockId = 100;
let configured = false;

let identity: IdentityInfo | null = null;
let localContent: ContentItem[] = [];
let peerContent: PeerAnnouncement[] = [];
let publishedIds = new Set<string>();
let onlineDevices: string[] = [];
let activeTab: 'local' | 'red' = 'local';

export async function initAndroid() {
  document.body.classList.add('android-mode');
  ensureNativeBridge();
  window.__fenixExternalRefresh = async () => {
    await refreshState();
  };

  identity = await invoke<IdentityInfo>('get_identity');
  if (!identity?.configured) {
    renderSetup();
    setupEventListeners();
    return;
  }

  await loadContent();
  renderApp();
  setupEventListeners();
}

async function invoke<T>(cmd: string, args?: unknown): Promise<T> {
  if (IS_TAURI) {
    const { invoke: tauriInvoke } = await import('@tauri-apps/api/core');
    return tauriInvoke<T>(cmd, args as Record<string, unknown>);
  }

  if (IS_NATIVE_ANDROID) {
    return invokeNative<T>(cmd, args);
  }

  return invokeMock<T>(cmd, args);
}

function ensureNativeBridge() {
  if (!IS_NATIVE_ANDROID || nativeBridgeReady) return;

  window.__fenixResolve = (id, payload) => {
    const pending = nativePending.get(id);
    if (!pending) return;
    nativePending.delete(id);
    pending.resolve(payload);
  };

  window.__fenixReject = (id, message) => {
    const pending = nativePending.get(id);
    if (!pending) return;
    nativePending.delete(id);
    pending.reject(new Error(message));
  };

  nativeBridgeReady = true;
}

function invokeNative<T>(cmd: string, args?: unknown): Promise<T> {
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

  return new Promise<T>((resolve, reject) => {
    nativePending.set(id, {
      resolve: payload => resolve(payload as T),
      reject,
    });
    bridge.postMessage(request);
  });
}

async function invokeMock<T>(cmd: string, args?: unknown): Promise<T> {
  const a = args as Record<string, unknown> | undefined;
  await new Promise(resolve => setTimeout(resolve, 120));

  switch (cmd) {
    case 'get_identity':
      return { device_name: 'Mi Móvil', group_id: 'demo', configured } as T;
    case 'get_local_content':
      return mockLocal.map(item => ({
        ...item,
        is_published: mockPublished.has(item.id),
      })) as T;
    case 'get_peers':
      return [...mockPeers] as T;
    case 'add_text_content': {
      const text = a?.text as string;
      const item: ContentItem = {
        id: String(mockId++),
        content_type: 'text',
        preview: text.slice(0, 80),
        size_bytes: text.length,
        created_at: Date.now(),
        file_name: null,
        mime_type: 'text/plain; charset=utf-8',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'paste_clipboard_text': {
      const item: ContentItem = {
        id: String(mockId++),
        content_type: 'text',
        preview: 'Texto pegado del portapapeles',
        size_bytes: 28,
        created_at: Date.now(),
        file_name: null,
        mime_type: 'text/plain; charset=utf-8',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'pick_file': {
      const item: ContentItem = {
        id: String(mockId++),
        content_type: 'file',
        preview: 'mock-file.bin',
        size_bytes: 120_000,
        created_at: Date.now(),
        file_name: 'mock-file.bin',
        mime_type: 'application/octet-stream',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'add_binary_content': {
      const payload = (a?.args as {
        file_name: string;
        mime_type?: string | null;
        preview?: string | null;
      } | undefined) ?? {
        file_name: 'archivo',
      };
      const item: ContentItem = {
        id: String(mockId++),
        content_type: payload.mime_type?.startsWith('image/') ? 'image' : 'file',
        preview: payload.preview || payload.file_name || 'archivo',
        size_bytes: 0,
        created_at: Date.now(),
        file_name: payload.file_name,
        mime_type: payload.mime_type ?? 'application/octet-stream',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'copy_local_content':
      return 'Contenido copiado al portapapeles' as T;
    case 'remove_content':
      mockLocal = mockLocal.filter(item => item.id !== (a?.id as string));
      mockPublished.delete(a?.id as string);
      return undefined as T;
    case 'publish_content': {
      const contentId = (a?.args as { content_id: string } | undefined)?.content_id;
      if (contentId) mockPublished.add(contentId);
      return undefined as T;
    }
    case 'unpublish_content': {
      const contentId = a?.content_id as string;
      mockPublished.delete(contentId);
      return undefined as T;
    }
    case 'stop_server':
      mockPublished.clear();
      return undefined as T;
    case 'pull_peer_content': {
      const contentId = a?.content_id as string;
      const peer = mockPeers.find(item => item.content_id === contentId);
      mockPeers = mockPeers.filter(item => item.content_id !== contentId);
      const item: ContentItem = {
        id: String(mockId++),
        content_type: peer?.content_type || 'file',
        preview: peer?.preview || '',
        size_bytes: peer?.size_bytes || 0,
        created_at: Date.now(),
        file_name: peer?.file_name,
        mime_type: peer?.mime_type,
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'open_overlay':
      return true as T;
    case 'setup_identity':
      configured = true;
      return {
        device_name: (a?.args as { device_name: string } | undefined)?.device_name ?? 'Device',
        group_id: 'demo',
        configured: true,
      } as T;
    default:
      return undefined as T;
  }
}

function listen<T>(event: string, cb: (e: { payload: T }) => void) {
  if (IS_TAURI) {
    import('@tauri-apps/api/event').then(({ listen: tauriListen }) => tauriListen<T>(event, cb));
    return;
  }
  return Promise.resolve(() => {});
}

async function loadContent() {
  [localContent, peerContent] = await Promise.all([
    invoke<ContentItem[]>('get_local_content'),
    invoke<PeerAnnouncement[]>('get_peers'),
  ]);
  syncDerivedState();
}

function setupEventListeners() {
  if (listenersRegistered) return;
  listenersRegistered = true;

  listen<{ announcement: PeerAnnouncement }>('peer-content-available', ({ payload }) => {
    const announcement = payload.announcement;
    peerContent = [announcement, ...peerContent.filter(item => item.content_id !== announcement.content_id)];
    syncDerivedState();
    updateUI();
  });

  listen<{ content_id: string; device_name: string }>('peer-content-gone', ({ payload }) => {
    peerContent = peerContent.filter(item => item.content_id !== payload.content_id);
    syncDerivedState();
    updateUI();
  });

  listen<{ announcement: PeerAnnouncement }>('direct-notify-received', ({ payload }) => {
    const announcement = payload.announcement;
    peerContent = [announcement, ...peerContent.filter(item => item.content_id !== announcement.content_id)];
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
    await loadContent();
    updateUI();
  } catch (error) {
    console.error(error);
  }
}

function syncDerivedState() {
  publishedIds = new Set(localContent.filter(item => item.is_published).map(item => item.id));
  onlineDevices = [...new Set(peerContent.map(item => item.device_name))];
}

function renderSetup() {
  document.getElementById('app')!.innerHTML = `
    <div class="android-setup">
      <div class="logo-wrapper">${iconHub(48)}</div>
      <h1>FenixHub</h1>
      <p>Conecta tus dispositivos Fenix sin cuentas, sin nube y sin fricción.</p>
      <input type="password" id="passphrase" placeholder="Frase de acceso de red" autocomplete="off" />
      <input type="text" id="device-name" placeholder="Nombre de este móvil" value="${escapeAttribute(identity?.device_name || '')}" />
      <button id="setup-btn">Activar Hub</button>
    </div>`;

  document.getElementById('setup-btn')!.addEventListener('click', async () => {
    const passphrase = (document.getElementById('passphrase') as HTMLInputElement).value.trim();
    const deviceName = (document.getElementById('device-name') as HTMLInputElement).value.trim();
    if (!passphrase || !deviceName) {
      showToast('Necesitas frase y nombre de dispositivo');
      return;
    }

    const button = document.getElementById('setup-btn') as HTMLButtonElement;
    button.disabled = true;
    button.textContent = 'Activando...';

    try {
      identity = await invoke<IdentityInfo>('setup_identity', {
        args: { passphrase, device_name: deviceName },
      });
      await loadContent();
      renderApp();
      showToast('Hub activado');
    } catch (error) {
      button.disabled = false;
      button.textContent = 'Activar Hub';
      showToast(errorMessage(error));
    }
  });
}

function renderApp() {
  document.getElementById('app')!.innerHTML = `
    <div class="android-layout">
      <header class="a-header">
        <div class="a-header-title">${iconHub(28)} FenixHub</div>
        <div class="a-header-right">
          <div class="a-header-actions">
            <button class="a-chip-btn" id="btn-clipboard">${iconClipboard(16)} Portapapeles</button>
            <button class="a-chip-btn" id="btn-overlay">${iconOverlay(16)} Overlay</button>
          </div>
          <div class="a-status-pill">
            <div class="a-status-dot scanning" id="status-dot"></div>
            <span id="status-text">Buscando</span>
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

  document.querySelectorAll<HTMLButtonElement>('.a-nav-btn').forEach(button => {
    button.addEventListener('click', () => {
      const tab = button.dataset.tab as 'local' | 'red';
      switchTab(tab);
    });
  });

  document.getElementById('fab-add')!.addEventListener('click', openAddSheet);
  document.getElementById('btn-clipboard')!.addEventListener('click', () => {
    void window.androidActions?.pasteClipboard();
  });
  document.getElementById('btn-overlay')!.addEventListener('click', () => {
    void window.androidActions?.openOverlay();
  });
  document.getElementById('android-file-picker')!.addEventListener('change', event => {
    const file = (event.currentTarget as HTMLInputElement).files?.[0];
    if (!file) return;
    void addBrowserFile(file);
    (event.currentTarget as HTMLInputElement).value = '';
  });

  updateUI();
}

function switchTab(tab: 'local' | 'red') {
  activeTab = tab;
  document.querySelectorAll('.a-nav-btn').forEach(button => {
    button.classList.toggle('active', (button as HTMLElement).dataset.tab === tab);
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
  if (localBadge) localBadge.textContent = String(localContent.length);
  if (peerBadge) peerBadge.textContent = String(peerContent.length);

  const statusDot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');
  if (statusDot && statusText) {
    if (onlineDevices.length > 0) {
      statusDot.className = 'a-status-dot online';
      statusText.textContent = `${onlineDevices.length} disp`;
    } else {
      statusDot.className = 'a-status-dot scanning';
      statusText.textContent = 'Buscando';
    }
  }

  const contentArea = document.getElementById('a-content-area');
  if (!contentArea) return;

  if (activeTab === 'local') {
    renderLocalContent(contentArea);
  } else {
    renderPeerContent(contentArea);
  }
}

function renderLocalContent(area: HTMLElement) {
  if (localContent.length === 0) {
    area.innerHTML = `<div class="a-empty">${iconInbox(48)}<p>Tu hub local está vacío.</p></div>`;
    return;
  }

  area.innerHTML = localContent
    .map(item => {
      const isLive = publishedIds.has(item.id);
      const media = renderMediaPreview(item);
      const directAction = onlineDevices.length > 0
        ? `<button class="a-btn a-btn-secondary" onclick="window.androidActions.chooseDirect('${item.id}')">Enviar...</button>`
        : '';
      const actions = isLive
        ? `
            <button class="a-btn a-btn-danger" onclick="window.androidActions.stop('${item.id}')">Parar emisión</button>
            <button class="a-btn a-btn-secondary" onclick="window.androidActions.copy('${item.id}')">Copiar</button>
          `
        : `
            <button class="a-btn a-btn-primary" onclick="window.androidActions.broadcast('${item.id}')">Emitir</button>
            ${directAction}
            <button class="a-btn a-btn-secondary" onclick="window.androidActions.copy('${item.id}')">Copiar</button>
          `;

      return `
        <div class="a-card ${isLive ? 'broadcasting' : ''}" data-id="${item.id}">
          ${media}
          <button class="a-btn-delete-float" onclick="window.androidActions.remove('${item.id}')">${iconX(16)}</button>
          <div class="a-card-body">
            <div class="a-card-header">
              <div class="a-type-icon ${item.content_type}">${typeIcon(item.content_type)}</div>
              <div class="a-card-text">
                <div class="a-card-preview">${escapeHtml(item.file_name || item.preview)}</div>
                <div class="a-card-meta">${humanSize(item.size_bytes)}${isLive ? ' · <span class="a-live-pill">LIVE</span>' : ''}</div>
              </div>
            </div>
            <div class="a-card-actions">${actions}</div>
          </div>
        </div>`;
    })
    .join('');
}

function renderPeerContent(area: HTMLElement) {
  if (peerContent.length === 0) {
    area.innerHTML = `<div class="a-empty">${iconWifi(48)}<p>Buscando dispositivos cercanos...</p></div>`;
    return;
  }

  area.innerHTML = peerContent
    .map(item => {
      const media = renderPeerMediaPreview(item);
      return `
        <div class="a-card peer" data-id="${item.content_id}">
          ${media}
          <div class="a-card-body">
            <div class="a-card-header">
              <div class="a-type-icon ${item.content_type}">${typeIcon(item.content_type)}</div>
              <div class="a-card-text">
                <div class="a-card-preview">${escapeHtml(item.file_name || item.preview)}</div>
                <div class="a-card-meta"><span class="a-peer-pill">${escapeHtml(item.device_name)}</span> · ${humanSize(item.size_bytes)}</div>
              </div>
            </div>
            <div class="a-card-actions">
              <button class="a-btn a-btn-success" id="btn-pull-${item.content_id}" onclick="window.androidActions.receive('${item.content_id}')">Guardar en local</button>
            </div>
          </div>
        </div>`;
    })
    .join('');
}

function renderMediaPreview(item: ContentItem) {
  if (item.content_type === 'image' && item.preview.startsWith('data:image')) {
    return `<img class="a-card-img" src="${item.preview}" alt="" />`;
  }
  return '';
}

function renderPeerMediaPreview(item: PeerAnnouncement) {
  if (item.content_type === 'image' && item.preview.startsWith('data:image')) {
    return `<img class="a-card-img" src="${item.preview}" alt="" />`;
  }
  return '';
}

function openAddSheet() {
  if (document.getElementById('a-sheet-backdrop')) return;

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

  backdrop.querySelectorAll<HTMLButtonElement>('.a-sheet-btn').forEach(button => {
    button.addEventListener('click', () => {
      const action = button.dataset.action;
      close();
      if (action === 'text') {
        openTextComposer();
      } else if (action === 'clipboard') {
        void window.androidActions?.pasteClipboard();
      } else if (action === 'file') {
        if (IS_NATIVE_ANDROID) {
          void pickNativeFile();
        } else {
          (document.getElementById('android-file-picker') as HTMLInputElement | null)?.click();
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
  const textarea = backdrop.querySelector<HTMLTextAreaElement>('#a-quick-text');
  textarea?.focus();

  backdrop.querySelectorAll<HTMLButtonElement>('.a-sheet-btn').forEach(button => {
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
        const item = await invoke<ContentItem>('add_text_content', { text });
        localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
        syncDerivedState();
        updateUI();
        showToast('Texto añadido al hub');
        close();
      } catch (error) {
        button.disabled = false;
        showToast(errorMessage(error));
      }
    });
  });
}

function openDirectSheet(contentId: string) {
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

  backdrop.querySelectorAll<HTMLButtonElement>('.a-sheet-btn').forEach(button => {
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
    const item = await invoke<ContentItem | null>('pick_file');
    if (!item) return;
    localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
    syncDerivedState();
    updateUI();
    showToast(item.content_type === 'image' ? 'Imagen añadida al hub' : 'Archivo añadido al hub');
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function addBrowserFile(file: File) {
  const bytesBase64 = await fileToBase64(file);
  const preview = file.type.startsWith('image/') ? await imageFileToPreview(file) : undefined;
  const item = await invoke<ContentItem>('add_binary_content', {
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

async function fileToBase64(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  let binary = '';
  const chunk = 0x8000;
  const bytes = new Uint8Array(buffer);
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
  }
  return btoa(binary);
}

async function imageFileToPreview(file: File): Promise<string | undefined> {
  const bitmap = await createImageBitmap(file).catch(() => null);
  if (!bitmap) return undefined;

  const canvas = document.createElement('canvas');
  canvas.width = 72;
  canvas.height = 72;
  const ctx = canvas.getContext('2d');
  if (!ctx) return undefined;

  const scale = Math.max(canvas.width / bitmap.width, canvas.height / bitmap.height);
  const width = bitmap.width * scale;
  const height = bitmap.height * scale;
  ctx.drawImage(bitmap, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height);
  return canvas.toDataURL('image/jpeg', 0.58);
}

function createSheet(innerHtml: string) {
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
  async broadcast(id: string) {
    try {
      await invoke('publish_content', { args: { content_id: id, target_device: null } });
      publishedIds.add(id);
      updateLocalItem(id, item => ({ ...item, is_published: true }));
      updateUI();
      showToast('Emitiendo a la red');
      await refreshStateIfNative();
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async stop(id: string) {
    try {
      await invoke('unpublish_content', { content_id: id });
      publishedIds.delete(id);
      updateLocalItem(id, item => ({ ...item, is_published: false }));
      updateUI();
      showToast('Emisión detenida');
      await refreshStateIfNative();
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async chooseDirect(id: string) {
    openDirectSheet(id);
  },

  async direct(id: string, device: string) {
    try {
      await invoke('publish_content', { args: { content_id: id, target_device: device } });
      publishedIds.add(id);
      updateLocalItem(id, item => ({ ...item, is_published: true }));
      updateUI();
      showToast(`Enviando a ${device}`);
      await refreshStateIfNative();
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async copy(id: string) {
    try {
      const message = await invoke<string>('copy_local_content', { id });
      showToast(message || 'Contenido copiado');
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async remove(id: string) {
    try {
      await invoke('remove_content', { id });
      publishedIds.delete(id);
      localContent = localContent.filter(item => item.id !== id);
      syncDerivedState();
      updateUI();
      await refreshStateIfNative();
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async receive(id: string) {
    const button = document.getElementById(`btn-pull-${id}`) as HTMLButtonElement | null;
    if (button) {
      button.disabled = true;
      button.textContent = 'Obteniendo...';
    }

    try {
      const received = await invoke<ContentItem>('pull_peer_content', { content_id: id });
      localContent = [received, ...localContent.filter(item => item.id !== received.id)];
      peerContent = peerContent.filter(item => item.content_id !== id);
      syncDerivedState();
      updateUI();
      showToast('Descarga completada');
      await refreshStateIfNative();
    } catch (error) {
      if (button) {
        button.disabled = false;
        button.textContent = 'Guardar en local';
      }
      showToast(errorMessage(error));
    }
  },

  async openOverlay() {
    try {
      const opened = await invoke<boolean>('open_overlay');
      showToast(opened ? 'Overlay abierto' : 'Permiso de overlay requerido');
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async pasteClipboard() {
    try {
      const item = await invoke<ContentItem>('paste_clipboard_text');
      localContent = [item, ...localContent.filter(existing => existing.id !== item.id)];
      syncDerivedState();
      updateUI();
      showToast('Portapapeles añadido al hub');
    } catch (error) {
      showToast(errorMessage(error));
    }
  },
};

function updateLocalItem(id: string, mutate: (item: ContentItem) => ContentItem) {
  localContent = localContent.map(item => (item.id === id ? mutate(item) : item));
  syncDerivedState();
}

async function refreshStateIfNative() {
  if (IS_NATIVE_ANDROID) {
    await refreshState();
  }
}

function showToast(message: string) {
  const toast = document.createElement('div');
  toast.className = 'a-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transition = 'opacity 0.3s';
    setTimeout(() => toast.remove(), 300);
  }, 2_200);
}

function errorMessage(error: unknown) {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Operación no completada';
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function humanSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}

const svg = (
  size: number,
  viewBox: string,
  path: string,
  extra = 'stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"',
) => `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="none" ${extra}>${path}</svg>`;

function iconHub(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<polygon points="10,2 18,6.5 18,13.5 10,18 2,13.5 2,6.5" stroke-width="1.6"/><circle cx="10" cy="10" r="2.5" stroke-width="1.6"/>',
  );
}

function iconInbox(size: number) {
  return svg(
    size,
    '0 0 16 16',
    '<rect x="1.5" y="1.5" width="13" height="13" rx="2" stroke-width="1.7"/><polyline points="1.5,10 4.5,10 5.5,12.5 10.5,12.5 11.5,10 14.5,10" stroke-width="1.7"/>',
  );
}

function iconWifi(size: number) {
  return svg(
    size,
    '0 0 16 16',
    '<path d="M1.5,6 Q8,1 14.5,6" stroke-width="1.7"/><path d="M3.5,9 Q8,5.5 12.5,9" stroke-width="1.7"/><path d="M5.5,12 Q8,10 10.5,12" stroke-width="1.7"/><circle cx="8" cy="14" r="0.8" fill="currentColor" stroke="none"/>',
  );
}

function iconPlus(size: number) {
  return svg(
    size,
    '0 0 24 24',
    '<line x1="12" y1="5" x2="12" y2="19" stroke-width="2.5"/><line x1="5" y1="12" x2="19" y2="12" stroke-width="2.5"/>',
  );
}

function iconX(size: number) {
  return svg(
    size,
    '0 0 14 14',
    '<line x1="2" y1="2" x2="12" y2="12" stroke-width="2"/><line x1="12" y1="2" x2="2" y2="12" stroke-width="2"/>',
  );
}

function iconText(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<line x1="4" y1="6" x2="16" y2="6" stroke-width="1.8"/><line x1="4" y1="10" x2="16" y2="10" stroke-width="1.8"/><line x1="4" y1="14" x2="11" y2="14" stroke-width="1.8"/>',
  );
}

function iconFile(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<path d="M12,2 H6 a2,2 0 0 0 -2,2 v12 a2,2 0 0 0 2,2 h8 a2,2 0 0 0 2,-2 V8 Z" stroke-width="1.8"/><polyline points="12,2 12,8 18,8" stroke-width="1.8"/>',
  );
}

function iconClipboard(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<rect x="5" y="4" width="10" height="13" rx="2" stroke-width="1.7"/><path d="M8,4.5 V3.5 C8,2.7 8.7,2 9.5,2 h1 C11.3,2 12,2.7 12,3.5 v1" stroke-width="1.7"/>',
  );
}

function iconOverlay(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<rect x="3" y="4" width="11" height="11" rx="2" stroke-width="1.7"/><path d="M8,15 h7 a2,2 0 0 0 2,-2 V6" stroke-width="1.7"/>',
  );
}

function iconCheck(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<polyline points="4.5,10.5 8.2,14.2 15.5,6.8" stroke-width="2"/>',
  );
}

function typeIcon(type: string) {
  if (type === 'text') {
    return svg(
      24,
      '0 0 24 24',
      '<line x1="5" y1="8" x2="19" y2="8" stroke-width="2.5"/><line x1="5" y1="12" x2="19" y2="12" stroke-width="2.5"/><line x1="5" y1="16" x2="13" y2="16" stroke-width="2.5"/>',
    );
  }
  if (type === 'image') {
    return svg(
      24,
      '0 0 24 24',
      '<rect x="3" y="4" width="18" height="16" rx="3" stroke-width="2"/><circle cx="9" cy="10" r="2" stroke-width="2"/><polyline points="3,16 8,11 11,14 15,10 21,16" stroke-width="2"/>',
    );
  }
  return svg(
    24,
    '0 0 24 24',
    '<path d="M14,3 H6 a2,2 0 0 0 -2,2 v14 a2,2 0 0 0 2,2 h12 a2,2 0 0 0 2,-2 V9 Z" stroke-width="2"/><polyline points="14,3 14,9 20,9" stroke-width="2"/>',
  );
}
