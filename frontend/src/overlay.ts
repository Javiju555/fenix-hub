import './overlay.css';

declare global {
  interface Window {
    FenixHubBridge?: {
      postMessage(message: string): void;
    };
    __fenixResolve?: (id: string, payload: unknown) => void;
    __fenixReject?: (id: string, message: string) => void;
    __fenixOverlayRefresh?: () => Promise<void>;
    __fenixOverlaySetMinimized?: (minimized: boolean) => void;
  }
}

interface ContentItem {
  id: string;
  content_type: 'text' | 'image' | 'file';
  preview: string;
  size_bytes: number;
  created_at: number;
  file_name?: string | null;
  mime_type?: string | null;
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
const POLL_INTERVAL_MS = 8_000;
const LOGO_SRC = './logo-mark.png';

let nativeBridgeReady = false;
let nativeRequestId = 0;
let pollHandle: number | null = null;
let listenersBound = false;
const nativePending = new Map<string, NativePendingRequest>();

let localContent: ContentItem[] = [];
let peerContent: PeerAnnouncement[] = [];
let activeTab: 'local' | 'red' = 'local';
let overlayMinimized = false;

function localFingerprint(item: ContentItem): string {
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

function peerFingerprint(item: PeerAnnouncement): string {
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

function stateFingerprint(local: ContentItem[], peers: PeerAnnouncement[]): string {
  const localPart = local.map(localFingerprint).join('||');
  const peerPart = peers.map(peerFingerprint).join('||');
  return `${localPart}###${peerPart}`;
}

function upsertPeerAnnouncement(announcement: PeerAnnouncement): boolean {
  const existing = peerContent.find(item => item.content_id === announcement.content_id);
  if (existing && peerFingerprint(existing) === peerFingerprint(announcement)) {
    return false;
  }
  peerContent = [announcement, ...peerContent.filter(item => item.content_id !== announcement.content_id)];
  return true;
}

let mockLocal: ContentItem[] = [
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
    size_bytes: 640_000,
    created_at: Date.now() - 1000,
    file_name: 'foto-urgente.jpg',
    mime_type: 'image/jpeg',
    is_published: true,
  },
];

let mockPeers: PeerAnnouncement[] = [
  {
    group_id: 'demo',
    content_id: 'peer-1',
    device_name: 'Portátil',
    preview: 'factura.pdf',
    content_type: 'file',
    size_bytes: 84_000,
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
  window.__fenixOverlaySetMinimized = (minimized: boolean) => {
    overlayMinimized = minimized;
    render();
  };
  await loadState();
  render();
  setupListeners();
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

  const id = `overlay-${nativeRequestId++}`;
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
  await new Promise(resolve => setTimeout(resolve, 80));

  switch (cmd) {
    case 'get_local_content':
      return [...mockLocal] as T;
    case 'get_peers':
      return [...mockPeers] as T;
    case 'paste_clipboard_text': {
      const item: ContentItem = {
        id: `local-${Date.now()}`,
        content_type: 'text',
        preview: 'Texto pegado del sistema',
        size_bytes: 24,
        created_at: Date.now(),
        mime_type: 'text/plain; charset=utf-8',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'publish_content': {
      const contentId = (a?.args as { content_id: string } | undefined)?.content_id;
      mockLocal = mockLocal.map(item =>
        item.id === contentId ? { ...item, is_published: true } : item,
      );
      return undefined as T;
    }
    case 'unpublish_content': {
      const contentId = a?.content_id as string;
      mockLocal = mockLocal.map(item =>
        item.id === contentId ? { ...item, is_published: false } : item,
      );
      return undefined as T;
    }
    case 'publish_all_local':
      mockLocal = mockLocal.map(item => ({ ...item, is_published: true }));
      return undefined as T;
    case 'remove_content':
      mockLocal = mockLocal.filter(item => item.id !== (a?.id as string));
      return undefined as T;
    case 'copy_local_content':
      return 'Contenido copiado al portapapeles' as T;
    case 'pull_peer_content': {
      const contentId = a?.content_id as string;
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
      return undefined as T;
    }
    case 'copy_peer_content':
      return 'Contenido remoto copiado' as T;
    case 'ignore_peer_content':
      mockPeers = mockPeers.filter(item => item.content_id !== (a?.content_id as string));
      return undefined as T;
    case 'open_full_app':
      return undefined as T;
    case 'minimize_overlay':
      overlayMinimized = true;
      return true as T;
    case 'expand_overlay':
      overlayMinimized = false;
      return true as T;
    case 'close_overlay':
      return true as T;
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

function setupListeners() {
  if (listenersBound) return;
  listenersBound = true;

  listen<{ announcement: PeerAnnouncement }>('peer-content-available', ({ payload }) => {
    const announcement = payload.announcement;
    if (!upsertPeerAnnouncement(announcement)) return;
    update();
  });

  listen<{ content_id: string }>('peer-content-gone', ({ payload }) => {
    if (!peerContent.some(item => item.content_id === payload.content_id)) return;
    peerContent = peerContent.filter(item => item.content_id !== payload.content_id);
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
    invoke<ContentItem[]>('get_local_content'),
    invoke<PeerAnnouncement[]>('get_peers'),
  ]);
}

async function refreshState() {
  try {
    const before = stateFingerprint(localContent, peerContent);
    await loadState();
    const after = stateFingerprint(localContent, peerContent);
    if (before !== after) {
      update();
    }
  } catch (error) {
    console.error(error);
  }
}

function render() {
  if (overlayMinimized) {
    document.getElementById('app')!.innerHTML = `
      <div class="overlay-mini-shell">
        <button class="overlay-mini-main" id="overlay-mini-main" title="Restaurar FenixHub">
          <img class="overlay-mini-logo" src="${LOGO_SRC}" alt="FenixHub" />
        </button>
        <button class="overlay-mini-close" id="overlay-mini-close" title="Cerrar overlay">${iconX(11)}</button>
      </div>
    `;

    document.getElementById('overlay-mini-main')!.addEventListener('click', async () => {
      await invoke('expand_overlay');
      overlayMinimized = false;
      render();
    });
    document.getElementById('overlay-mini-close')!.addEventListener('click', async () => {
      await invoke('close_overlay');
    });
    return;
  }

  document.getElementById('app')!.innerHTML = `
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
      <footer class="overlay-footer-bar">
        <button class="overlay-footer-btn" id="act-paste">${iconClipboard(16)} Pegar</button>
        <button class="overlay-footer-btn accent" id="act-share-all">${iconBroadcast(16)} Todo</button>
        <button class="overlay-footer-btn subtle" id="act-close-overlay">${iconX(16)} Cerrar</button>
      </footer>
    </div>
  `;

  document.querySelectorAll<HTMLButtonElement>('.overlay-tab').forEach(button => {
    button.addEventListener('click', () => {
      const nextTab = button.dataset.tab as 'local' | 'red';
      activeTab = nextTab;
      update();
    });
  });

  document.getElementById('overlay-expand')!.addEventListener('click', async () => {
    await invoke('open_full_app');
  });

  document.getElementById('overlay-minimize')!.addEventListener('click', async () => {
    await invoke('minimize_overlay');
    overlayMinimized = true;
    render();
  });

  document.getElementById('overlay-close')!.addEventListener('click', async () => {
    await invoke('close_overlay');
  });

  document.getElementById('act-paste')!.addEventListener('click', () => void copyOrPasteLocal());
  document.getElementById('act-share-all')!.addEventListener('click', () => void publishAllLocal());
  document.getElementById('act-close-overlay')!.addEventListener('click', async () => {
    await invoke('close_overlay');
  });

  update();
}

function update() {
  if (overlayMinimized) return;

  document.querySelectorAll('.overlay-tab').forEach(tab => {
    tab.classList.toggle('active', (tab as HTMLElement).dataset.tab === activeTab);
  });
  renderCards();
}

function renderCards() {
  const stack = document.getElementById('overlay-stack');
  if (!stack) return;

  if (activeTab === 'local') {
    if (localContent.length === 0) {
      stack.innerHTML = emptyState('Nada local', 'Pulsa Pegar para capturar el portapapeles.');
      return;
    }

    stack.innerHTML = localContent.map(item => {
      return `
        <button class="overlay-card ${item.is_published ? 'published' : ''}" data-local-id="${item.id}">
          <div class="overlay-card-top">
            <span class="overlay-type ${item.content_type}">${typeLabel(item.content_type)}</span>
            ${item.is_published ? '<span class="overlay-live">LIVE</span>' : ''}
          </div>
          <div class="overlay-title">${escapeHtml(item.file_name || item.preview)}</div>
          <div class="overlay-meta">${humanSize(item.size_bytes)}</div>
        </button>
      `;
    }).join('');

    stack.querySelectorAll<HTMLButtonElement>('[data-local-id]').forEach(button => {
      button.addEventListener('click', () => {
        const id = button.dataset.localId!;
        const item = localContent.find(i => i.id === id);
        if (item) showActionSheet(item, 'local');
      });
    });
  } else {
    if (peerContent.length === 0) {
      stack.innerHTML = emptyState('Sin peers', 'Los anuncios de red aparecerán aquí.');
      return;
    }

    stack.innerHTML = peerContent.map(item => {
      return `
        <button class="overlay-card peer" data-peer-id="${item.content_id}">
          <div class="overlay-card-top">
            <span class="overlay-type ${item.content_type}">${typeLabel(item.content_type)}</span>
            <span class="overlay-device">${escapeHtml(item.device_name)}</span>
          </div>
          <div class="overlay-title">${escapeHtml(item.file_name || item.preview)}</div>
          <div class="overlay-meta">${humanSize(item.size_bytes)}</div>
        </button>
      `;
    }).join('');

    stack.querySelectorAll<HTMLButtonElement>('[data-peer-id]').forEach(button => {
      button.addEventListener('click', () => {
        const id = button.dataset.peerId!;
        const item = peerContent.find(i => i.content_id === id);
        if (item) showActionSheet(item, 'red');
      });
    });
  }
}

async function togglePublishSingle(id: string) {
  const item = localContent.find(i => i.id === id);
  if (!item) return;
  try {
    if (item.is_published) {
      await invoke('unpublish_content', { content_id: id });
      showToast('Emisión detenida');
    } else {
      await invoke('publish_content', { args: { content_id: id, target_device: null } });
      showToast('Publicado');
    }
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}


async function deleteLocalSingle(id: string) {
  try {
    await invoke('remove_content', { id });
    showToast('Contenido borrado');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function startDirectModeFromOverlay(contentId?: string) {
  const id = contentId || localContent[0]?.id;
  if (!id) {
    showToast('Añade contenido antes de enviar');
    return;
  }
  try {
    await invoke('start_direct_mode_sender');
    showToast('Modo directo activo - buscando dispositivos...');
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function copySingleLocal(id: string) {
  try {
    const message = await invoke<string>('copy_local_content', { id });
    showToast(message || 'Copiado al portapapeles');
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function copyOrPasteLocal() {
  try {
    await invoke('paste_clipboard_text');
    showToast('Portapapeles añadido al hub');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function publishAllLocal() {
  try {
    await invoke('publish_all_local');
    showToast('Todo publicado en la red');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function downloadSinglePeer(id: string) {
  try {
    await invoke('pull_peer_content', { content_id: id });
    showToast('Descarga completada');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

function showActionSheet(item: ContentItem | PeerAnnouncement, tab: 'local' | 'red') {
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

  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) backdrop.remove();
  });

  backdrop.querySelectorAll<HTMLButtonElement>('[data-sheet-action]').forEach(btn => {
    btn.addEventListener('click', async () => {
      backdrop.remove();
      const action = btn.dataset.sheetAction!;
      if (action === 'cancel') return;

      if (isLocal) {
        const id = localItem.id;
        if (action === 'publish')  await togglePublishSingle(id);
        if (action === 'copy')     await copySingleLocal(id);
        if (action === 'delete')   await deleteLocalSingle(id);
        if (action === 'direct')   await startDirectModeFromOverlay(id);
      } else {
        const id = peerItem.content_id;
        if (action === 'download') await downloadSinglePeer(id);
        if (action === 'copy')     await copySinglePeer(id);
        if (action === 'ignore')   await ignoreSinglePeer(id);
      }
    });
  });

  document.body.appendChild(backdrop);
}

async function copySinglePeer(id: string) {
  try {
    await invoke('copy_peer_content', { content_id: id });
    showToast('Peer copiado al sistema');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function ignoreSinglePeer(id: string) {
  try {
    await invoke('ignore_peer_content', { content_id: id });
    showToast('Peer ignorado');
    await refreshState();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

function emptyState(title: string, description: string) {
  return `
    <div class="overlay-empty">
      <div class="overlay-empty-title">${escapeHtml(title)}</div>
      <div class="overlay-empty-copy">${escapeHtml(description)}</div>
    </div>
  `;
}

function showToast(message: string) {
  const toast = document.createElement('div');
  toast.className = 'overlay-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 240);
  }, 1800);
}

function errorMessage(error: unknown) {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Operación no completada';
}

function humanSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}

function typeLabel(type: ContentItem['content_type']) {
  if (type === 'text') return 'Texto';
  if (type === 'image') return 'Imagen';
  return 'Archivo';
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

const icon = (paths: string, size = 18) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;

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

function iconBroadcast(size = 18) {
  return icon('<circle cx="9" cy="5" r="1.5"/><circle cx="16" cy="5" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="16" cy="12" r="1.5"/><path d="M9 5 L16 12"/><path d="M16 5 L9 12"/>', size);
}

initOverlay();
