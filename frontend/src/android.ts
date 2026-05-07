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
      broadcastAll(): Promise<void>;
      openMeshSheet(): Promise<void>;
    };
  }
}

interface IdentityInfo {
  device_name: string;
  group_id: string;
  configured: boolean;
  device_type: string;
}

interface UpdateIdentityResult {
  identity: IdentityInfo;
  group_changed: boolean;
  requires_restart: boolean;
}

interface IdentityProfileInfo {
  name: string;
  device_name: string;
  group_id: string;
  device_type: string;
  active: boolean;
}

interface ProfilesPayload {
  profiles: IdentityProfileInfo[];
}

interface TransportRadioDetails {
  supported: boolean;
  enabled: boolean;
  permissions_ready: boolean;
  adapters?: string[];
}

interface TransportCapabilities {
  lan: boolean;
  lan_ip?: string | null;
  airdrop_ready: boolean;
  flow: string;
  ble?: TransportRadioDetails;
  wifi_direct?: TransportRadioDetails;
  ble_peers?: unknown[];
  wifi_direct_peers?: unknown[];
  handoff_candidates?: unknown[];
}

const DEVICE_TYPES = [
  { id: 'desktop', label: 'Desktop' },
  { id: 'laptop', label: 'Laptop' },
  { id: 'phone', label: 'Phone' },
  { id: 'tablet', label: 'Tablet' },
  { id: 'server', label: 'Server' },
] as const;

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
const POLL_INTERVAL_MS = 10_000;
const LOGO_SRC = './logo-mark.png';

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
let mockIdentity: IdentityInfo = {
  device_name: 'Mi Movil',
  group_id: 'demo',
  configured: false,
  device_type: 'phone',
};
let mockProfiles: IdentityProfileInfo[] = [];

let identity: IdentityInfo | null = null;
let localContent: ContentItem[] = [];
let peerContent: PeerAnnouncement[] = [];
let publishedIds = new Set<string>();
let onlineDevices: string[] = [];
let activeTab: 'local' | 'red' = 'local';
let receiverModeInterval: ReturnType<typeof setInterval> | null = null;
let currentIncomingInvite: IncomingInvite | null = null;

interface IncomingInvite {
  deviceName: string;
  ephemeralGroupId: string;
  senderIp: string;
  senderPort: number;
  contentId: string;
}

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

export async function initAndroid() {
  document.body.classList.add('android-mode');
  document.addEventListener('contextmenu', event => event.preventDefault());
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
  if (IS_NATIVE_ANDROID) {
    startReceiverModePolling();
  }
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
      return mockIdentity as T;
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
    case 'copy_peer_content':
      return 'Contenido remoto copiado al portapapeles' as T;
    case 'save_peer_content_as':
      return 'Contenido guardado en el destino elegido' as T;
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
    case 'publish_all': {
      const ids = (a as unknown as { content_ids: string[] })?.content_ids ?? [];
      ids.forEach((id: string) => mockPublished.add(id));
      return undefined as T;
    }
    case 'stop_server':
      mockPublished.clear();
      return undefined as T;
    case 'start_direct_mode_sender':
      return undefined as T;
    case 'accept_direct_peers':
      return undefined as T;
    case 'cancel_direct_mode':
      return undefined as T;
    case 'toggle_direct_peer':
      return undefined as T;
    case 'get_direct_peers':
      return { peers: [] } as T;
    case 'get_direct_session_state':
      return { discovering: false, advertising: false, ephemeral_group_id: '', peer_count: 0 } as T;
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
    case 'setup_identity': {
      const payload = (a?.args as {
        passphrase?: string;
        device_name: string;
        device_type?: string;
      } | undefined) ?? { device_name: 'Device' };
      mockIdentity = {
        device_name: payload.device_name,
        group_id: payload.passphrase ? `grp-${mockId++}` : (mockIdentity.group_id || 'demo'),
        configured: true,
        device_type: payload.device_type || mockIdentity.device_type || 'phone',
      };
      return mockIdentity as T;
    }
    case 'update_identity': {
      const payload = (a?.args as {
        passphrase?: string | null;
        device_name: string;
        device_type?: string;
      } | undefined) ?? { device_name: mockIdentity.device_name || 'Device' };
      const oldGroup = mockIdentity.group_id;
      const nextGroup = payload.passphrase ? `grp-${mockId++}` : oldGroup;
      mockIdentity = {
        device_name: payload.device_name,
        group_id: nextGroup,
        configured: true,
        device_type: payload.device_type || mockIdentity.device_type || 'phone',
      };
      return {
        identity: mockIdentity,
        group_changed: Boolean(oldGroup) && oldGroup !== nextGroup,
        requires_restart: Boolean(oldGroup) && oldGroup !== nextGroup,
      } as T;
    }
    case 'delete_identity_only': {
      mockIdentity = {
        device_name: '',
        group_id: '',
        configured: false,
        device_type: 'phone',
      };
      return undefined as T;
    }
    case 'list_identity_profiles':
      return { profiles: mockProfiles } as T;
    case 'save_current_identity_profile': {
      const payload = (a?.args as { name: string; make_active?: boolean } | undefined) ?? { name: '' };
      const name = payload.name.trim();
      if (name) {
        const existing = mockProfiles.find(profile => profile.name === name);
        if (existing) {
          existing.device_name = mockIdentity.device_name;
          existing.group_id = mockIdentity.group_id;
          existing.device_type = mockIdentity.device_type;
        } else {
          mockProfiles.push({
            name,
            device_name: mockIdentity.device_name,
            group_id: mockIdentity.group_id,
            device_type: mockIdentity.device_type,
            active: false,
          });
        }
        if (payload.make_active) {
          mockProfiles = mockProfiles.map(profile => ({
            ...profile,
            active: profile.name === name,
          }));
        }
      }
      return { profiles: mockProfiles } as T;
    }
    case 'activate_identity_profile': {
      const name = ((a?.args as { name: string } | undefined)?.name || '').trim();
      const selected = mockProfiles.find(profile => profile.name === name);
      if (!selected) {
        throw new Error('Profile not found');
      }
      const oldGroup = mockIdentity.group_id;
      mockProfiles = mockProfiles.map(profile => ({ ...profile, active: profile.name === name }));
      mockIdentity = {
        device_name: selected.device_name,
        group_id: selected.group_id,
        configured: true,
        device_type: selected.device_type,
      };
      return {
        identity: mockIdentity,
        group_changed: Boolean(oldGroup) && oldGroup !== selected.group_id,
        requires_restart: true,
      } as T;
    }
    case 'delete_identity_profile': {
      const name = ((a?.args as { name: string } | undefined)?.name || '').trim();
      mockProfiles = mockProfiles.filter(profile => profile.name !== name);
      if (!mockProfiles.some(profile => profile.active) && mockProfiles.length > 0) {
        mockProfiles[0].active = true;
      }
      return { profiles: mockProfiles } as T;
    }
    case 'clear_received_cache': {
      mockLocal = [];
      mockPublished.clear();
      return undefined as T;
    }
    case 'confirm_reset':
      return true as T;
    case 'reset_all_data': {
      mockLocal = [];
      mockPeers = [];
      mockPublished.clear();
      mockProfiles = [];
      mockIdentity = {
        device_name: '',
        group_id: '',
        configured: false,
        device_type: 'phone',
      };
      return undefined as T;
    }
    case 'start_direct_mode_receiver':
      return undefined as T;
    case 'get_current_inviter':
      return { has_invite: false } as T;
    case 'accept_direct_invite':
      return undefined as T;
    case 'reject_direct_invite':
      return undefined as T;
    case 'get_transport_hardware':
      return {
        lan: true,
        lan_ip: '192.168.1.10',
        ble: {
          supported: true,
          enabled: true,
          permissions_ready: true,
          adapters: ['Mock BLE'],
        },
        wifi_direct: {
          supported: true,
          enabled: true,
          permissions_ready: true,
          adapters: ['Mock WiFi Direct'],
        },
        airdrop_ready: true,
        flow: 'ble_discovery_then_wifi_direct_transfer',
        ble_peers: [],
        wifi_direct_peers: [],
        handoff_candidates: [],
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
    if (!upsertPeerAnnouncement(announcement)) return;
    syncDerivedState();
    updateUI();
  });

  listen<{ content_id: string; device_name: string }>('peer-content-gone', ({ payload }) => {
    if (!peerContent.some(item => item.content_id === payload.content_id)) return;
    peerContent = peerContent.filter(item => item.content_id !== payload.content_id);
    syncDerivedState();
    updateUI();
  });

  listen<{ announcement: PeerAnnouncement }>('direct-notify-received', ({ payload }) => {
    const announcement = payload.announcement;
    if (!upsertPeerAnnouncement(announcement) && activeTab === 'red') return;
    activeTab = 'red';
    syncDerivedState();
    updateUI();
  });

  document.getElementById('btn-mesh')!.addEventListener('click', () => {
    void window.androidActions?.openMeshSheet();
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
      <div class="a-setup-brand">
        <div class="logo-wrapper">${iconHub(56)}</div>
        <span class="a-kicker">LOCAL TRANSFER</span>
        <h1>FenixHub</h1>
        <p>Comparte texto, imagenes y archivos del movil al resto de tus equipos sin cuenta y sin nube.</p>
      </div>
      <div class="a-setup-panel">
        <div class="a-password-field">
          <input type="password" id="passphrase" placeholder="Frase de acceso de red" autocomplete="off" />
          <button class="a-password-toggle" id="passphrase-toggle" type="button" aria-label="Mostrar passphrase" aria-pressed="false" title="Mostrar passphrase">
            ${iconEye(18)}
          </button>
        </div>
        <input type="text" id="device-name" placeholder="Nombre de este movil" value="${escapeAttribute(identity?.device_name || '')}" />
        <button id="setup-btn">Activar Hub</button>
        <div class="a-setup-note">Usa la misma frase en todos tus dispositivos para entrar en la misma red efimera.</div>
      </div>
    </div>`;

  bindPasswordToggle('passphrase', 'passphrase-toggle');

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
  const deviceName = escapeHtml(identity?.device_name || 'Mi movil');
  const groupLabel = escapeHtml(shortGroupLabel(identity?.group_id));
  document.getElementById('app')!.innerHTML = `
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
            <button class="a-chip-btn" id="btn-share-all">${iconBroadcast(16)} Todo</button>
            <button class="a-chip-btn" id="btn-mesh">${iconMesh(16)} Mesh</button>
            <button class="a-chip-btn" id="btn-overlay">${iconOverlay(16)} Overlay</button>
            <button class="a-chip-btn" id="btn-settings">${iconSettings(16)} Ajustes</button>
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

  document.querySelectorAll<HTMLButtonElement>('.a-nav-btn').forEach(button => {
    button.addEventListener('click', () => {
      const tab = button.dataset.tab as 'local' | 'red';
      switchTab(tab);
    });
  });

  document.getElementById('fab-add')!.addEventListener('click', openAddSheet);
  document.getElementById('btn-share-all')!.addEventListener('click', () => {
    void window.androidActions?.broadcastAll();
  });
  document.getElementById('btn-overlay')!.addEventListener('click', () => {
    void window.androidActions?.openOverlay();
  });
  document.getElementById('btn-settings')!.addEventListener('click', () => {
    void reloadSettingsView();
  });
  document.getElementById('android-file-picker')!.addEventListener('change', event => {
    const file = (event.currentTarget as HTMLInputElement).files?.[0];
    if (!file) return;
    void addBrowserFile(file);
    (event.currentTarget as HTMLInputElement).value = '';
  });

  updateUI();
}

async function loadTransportCapabilities(): Promise<TransportCapabilities> {
  return invoke<TransportCapabilities>('get_transport_hardware')
    .catch(() => ({
      lan: false,
      airdrop_ready: false,
      flow: '',
      ble: {
        supported: false,
        enabled: false,
        permissions_ready: false,
        adapters: [],
      },
      wifi_direct: {
        supported: false,
        enabled: false,
        permissions_ready: false,
        adapters: [],
      },
      ble_peers: [],
      wifi_direct_peers: [],
      handoff_candidates: [],
    }));
}

async function returnToHubView() {
  identity = await invoke<IdentityInfo>('get_identity');
  if (!identity?.configured) {
    renderSetup();
    return;
  }
  await loadContent();
  renderApp();
}

async function reloadSettingsView(feedback?: { message: string; tone: 'ok' | 'warn' | 'error' }) {
  identity = await invoke<IdentityInfo>('get_identity');
  if (!identity?.configured) {
    renderSetup();
    if (feedback) {
      showToast(feedback.message);
    }
    return;
  }

  const [profilesPayload, transport] = await Promise.all([
    invoke<ProfilesPayload>('list_identity_profiles').catch(() => ({ profiles: [] })),
    loadTransportCapabilities(),
  ]);

  renderSettingsView(profilesPayload.profiles, transport, feedback);
}

function renderSettingsView(
  profiles: IdentityProfileInfo[],
  transport: TransportCapabilities,
  feedback?: { message: string; tone: 'ok' | 'warn' | 'error' },
) {
  const app = document.getElementById('app')!;
  const selectedType = identity?.device_type || 'phone';
  const profileOptions = profiles.map(profile =>
    `<option value="${escapeHtml(profile.name)}"${profile.active ? ' selected' : ''}>${escapeHtml(profile.name)}${profile.active ? ' - activo' : ''}</option>`
  ).join('');

  app.innerHTML = `
    <div class="a-settings-shell">
      <header class="a-settings-head">
        <div class="a-settings-title">${iconSettings(18)} Ajustes</div>
        <button class="a-settings-close" id="btn-settings-close" title="Cerrar">${iconX(12)}</button>
      </header>
      <div class="a-settings-body">
        ${feedback ? `<div class="a-settings-feedback ${feedback.tone}">${escapeHtml(feedback.message)}</div>` : ''}

        <section class="a-settings-section">
          <h3>Identidad</h3>
          <div class="a-settings-grid two">
            <label class="a-settings-field">
              <span>Nombre del dispositivo</span>
              <input id="settings-device-name" type="text" value="${escapeAttribute(identity?.device_name || '')}" placeholder="Nombre de este movil" />
            </label>
            <label class="a-settings-field">
              <span>Tipo</span>
              <select id="settings-device-type">
                ${DEVICE_TYPES.map(dt => `<option value="${dt.id}"${dt.id === selectedType ? ' selected' : ''}>${dt.label}</option>`).join('')}
              </select>
            </label>
          </div>

          <div class="a-settings-row">
            <span>Grupo (ID)</span>
            <span class="mono">${escapeHtml(identity?.group_id || '---')}</span>
            <button class="a-settings-btn" id="btn-copy-gid">Copiar</button>
          </div>

          <div class="a-settings-actions">
            <button class="a-settings-btn" id="btn-apply-identity">Guardar nombre/tipo</button>
          </div>

          <label class="a-settings-field">
            <span>Cambiar identidad (nuevo grupo, mantiene cache)</span>
            <div class="a-password-field">
              <input id="settings-passphrase" type="password" placeholder="Nueva passphrase del grupo" autocomplete="off" />
              <button class="a-password-toggle" id="settings-passphrase-toggle" type="button" aria-label="Mostrar passphrase" aria-pressed="false" title="Mostrar passphrase">
                ${iconEye(18)}
              </button>
            </div>
          </label>

          <div class="a-settings-actions">
            <button class="a-settings-btn" id="btn-change-group">Cambiar identidad</button>
          </div>
        </section>

        <section class="a-settings-section">
          <h3>Perfiles</h3>
          <div class="a-settings-grid two">
            <label class="a-settings-field">
              <span>Perfil guardado</span>
              <select id="settings-profile-select" ${profiles.length === 0 ? 'disabled' : ''}>
                ${profileOptions || '<option value="">Sin perfiles</option>'}
              </select>
            </label>
            <label class="a-settings-field">
              <span>Guardar perfil actual como</span>
              <input id="settings-profile-name" type="text" placeholder="ej. trabajo" />
            </label>
          </div>
          <div class="a-settings-actions split">
            <button class="a-settings-btn" id="btn-save-profile">Guardar perfil</button>
            <button class="a-settings-btn" id="btn-activate-profile" ${profiles.length === 0 ? 'disabled' : ''}>Activar perfil</button>
            <button class="a-settings-btn danger" id="btn-delete-profile" ${profiles.length === 0 ? 'disabled' : ''}>Eliminar perfil</button>
          </div>
        </section>

        <section class="a-settings-section">
          <h3>Transporte</h3>
          <div class="a-settings-transport">
            <div>LAN: <strong>${transport.lan ? 'Disponible' : 'No disponible'}</strong></div>
            <div>IP: ${escapeHtml(transport.lan_ip || 'sin enlace LAN')}</div>
            <div>Bluetooth LE: <strong>${transport.ble?.supported && transport.ble?.enabled ? 'Disponible' : 'No disponible'}</strong></div>
            <div>Adaptadores BLE: ${escapeHtml((transport.ble?.adapters || []).join(', ') || 'ninguno')}</div>
            <div>Wi-Fi Direct: <strong>${transport.wifi_direct?.supported && transport.wifi_direct?.enabled ? 'Disponible' : 'No disponible'}</strong></div>
            <div>Adaptadores Wi-Fi: ${escapeHtml((transport.wifi_direct?.adapters || []).join(', ') || 'ninguno')}</div>
            <div>Flujo: ${escapeHtml(transport.flow || 'ble_discovery_then_wifi_direct_transfer')}</div>
            <div>Modo AirDrop-like: <strong>${transport.airdrop_ready ? 'listo' : 'parcial'}</strong></div>
          </div>
        </section>

        <section class="a-settings-section">
          <h3>Cache</h3>
          <div class="a-settings-actions">
            <button class="a-settings-btn" id="btn-clear-cache">Limpiar cache</button>
          </div>
        </section>

        <section class="a-settings-section danger-zone">
          <h3>Zona de peligro</h3>
          <div class="a-settings-actions split">
            <button class="a-settings-btn danger" id="btn-delete-identity">Eliminar identidad</button>
            <button class="a-settings-btn danger solid" id="btn-reset">Eliminar todos los datos</button>
          </div>
        </section>
      </div>
    </div>`;

  document.getElementById('btn-settings-close')!.addEventListener('click', () => {
    void returnToHubView();
  });
  bindPasswordToggle('settings-passphrase', 'settings-passphrase-toggle');

  document.getElementById('btn-copy-gid')?.addEventListener('click', () => {
    if (identity?.group_id) {
      void navigator.clipboard.writeText(identity.group_id);
      showToast('ID de grupo copiado');
    }
  });

  document.getElementById('btn-apply-identity')?.addEventListener('click', async () => {
    if (!identity?.configured) {
      await reloadSettingsView({ message: 'No hay identidad activa.', tone: 'error' });
      return;
    }

    const deviceName = (document.getElementById('settings-device-name') as HTMLInputElement).value.trim();
    const deviceType = (document.getElementById('settings-device-type') as HTMLSelectElement).value;
    if (!deviceName) {
      await reloadSettingsView({ message: 'El nombre del dispositivo no puede estar vacio.', tone: 'error' });
      return;
    }

    const result = await invoke<UpdateIdentityResult>('update_identity', {
      args: {
        device_name: deviceName,
        device_type: deviceType,
        passphrase: null,
      },
    });

    await reloadSettingsView({
      message: result.group_changed
        ? 'Identidad actualizada. Reinicia la app para completar cambio de grupo.'
        : 'Nombre y tipo actualizados al instante.',
      tone: result.group_changed ? 'warn' : 'ok',
    });
  });

  document.getElementById('btn-change-group')?.addEventListener('click', async () => {
    const passphrase = (document.getElementById('settings-passphrase') as HTMLInputElement).value.trim();
    const deviceName = (document.getElementById('settings-device-name') as HTMLInputElement).value.trim() || identity?.device_name || '';
    const deviceType = (document.getElementById('settings-device-type') as HTMLSelectElement).value;
    if (!passphrase) {
      await reloadSettingsView({ message: 'Introduce una passphrase para cambiar de identidad.', tone: 'error' });
      return;
    }

    // Auto-save current identity as a profile before switching groups,
    // so the user can switch back without re-entering the passphrase.
    if (identity?.configured && identity.device_name) {
      const currentProfileName = identity.device_name;
      const existingProfiles = await invoke<ProfilesPayload>('list_identity_profiles').catch(() => ({ profiles: [] as IdentityProfileInfo[] }));
      const alreadySaved = existingProfiles.profiles.some(p => p.active);
      if (!alreadySaved) {
        await invoke('save_current_identity_profile', { args: { name: currentProfileName, make_active: true } }).catch(() => {});
      }
    }

    let result: UpdateIdentityResult;
    if (identity?.configured) {
      result = await invoke<UpdateIdentityResult>('update_identity', {
        args: {
          passphrase,
          device_name: deviceName,
          device_type: deviceType,
        },
      });
    } else {
      const created = await invoke<IdentityInfo>('setup_identity', {
        args: {
          passphrase,
          device_name: deviceName || 'Dispositivo',
          device_type: deviceType,
        },
      });
      result = {
        identity: created,
        group_changed: true,
        requires_restart: false,
      };
    }

    await reloadSettingsView({
      message: result.group_changed
        ? 'Grupo cambiado. La red se reinicia automáticamente.'
        : 'Identidad cambiada.',
      tone: 'ok',
    });
  });

  document.getElementById('btn-save-profile')?.addEventListener('click', async () => {
    const name = (document.getElementById('settings-profile-name') as HTMLInputElement).value.trim();
    if (!name) {
      await reloadSettingsView({ message: 'Escribe un nombre de perfil.', tone: 'error' });
      return;
    }
    await invoke<ProfilesPayload>('save_current_identity_profile', { args: { name, make_active: false } });
    await reloadSettingsView({ message: `Perfil "${name}" guardado.`, tone: 'ok' });
  });

  document.getElementById('btn-activate-profile')?.addEventListener('click', async () => {
    const select = document.getElementById('settings-profile-select') as HTMLSelectElement | null;
    const name = select?.value?.trim();
    if (!name) return;
    await invoke<UpdateIdentityResult>('activate_identity_profile', { args: { name } });
    await reloadSettingsView({
      message: `Perfil "${name}" activado. Reinicia la app para reinicializar discovery con total limpieza.`,
      tone: 'warn',
    });
  });

  document.getElementById('btn-delete-profile')?.addEventListener('click', async () => {
    const select = document.getElementById('settings-profile-select') as HTMLSelectElement | null;
    const name = select?.value?.trim();
    if (!name) return;
    await invoke<ProfilesPayload>('delete_identity_profile', { args: { name } });
    await reloadSettingsView({ message: `Perfil "${name}" eliminado.`, tone: 'ok' });
  });

  document.getElementById('btn-clear-cache')?.addEventListener('click', async () => {
    await invoke('clear_received_cache');
    await loadContent();
    await reloadSettingsView({ message: 'Cache local limpiada.', tone: 'ok' });
  });

  document.getElementById('btn-delete-identity')?.addEventListener('click', async () => {
    await invoke('delete_identity_only');
    await returnToHubView();
    showToast('Identidad eliminada. La cache local se mantiene intacta.');
  });

  document.getElementById('btn-reset')?.addEventListener('click', async () => {
    const ok = await invoke<boolean>('confirm_reset').catch(() =>
      window.confirm('Se eliminaran todos los datos locales. Deseas continuar?')
    );
    if (!ok) return;
    await invoke('reset_all_data');
    await returnToHubView();
    showToast('Todos los datos locales han sido eliminados.');
  });
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
  const localStat = document.getElementById('stat-local');
  const peerStat = document.getElementById('stat-peers');
  const liveStat = document.getElementById('stat-live');
  if (localBadge) localBadge.textContent = String(localContent.length);
  if (peerBadge) peerBadge.textContent = String(peerContent.length);
  if (localStat) localStat.textContent = String(localContent.length);
  if (peerStat) peerStat.textContent = String(onlineDevices.length);
  if (liveStat) liveStat.textContent = String(publishedIds.size);

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
  const header = `
    <section class="a-pane-head">
      <div>
        <span class="a-pane-kicker">Hub local</span>
        <h2>Contenido listo para salir</h2>
      </div>
      <p>${publishedIds.size > 0 ? `${publishedIds.size} emisiones activas ahora mismo.` : 'Pega, importa o comparte desde cualquier app para llenar tu buffer temporal dentro de FenixHub.'}</p>
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

function renderPeerContent(area: HTMLElement) {
  const header = `
    <section class="a-pane-head">
      <div>
        <span class="a-pane-kicker">Red</span>
        <h2>Transmisiones detectadas</h2>
      </div>
      <p>${onlineDevices.length > 0 ? `${onlineDevices.length} dispositivos anunciando contenido cerca de ti.` : 'En cuanto otro equipo publique algo, aparecera aqui.'} El contenido recibido entra primero en el hub temporal.</p>
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
              <button class="a-btn a-btn-success" id="btn-pull-${item.content_id}" onclick="window.androidActions.receive('${item.content_id}')">Recibir...</button>
            </div>
          </div>
        </div>`;
    })
    .join('')}</div>`;
}

function renderMediaPreview(item: ContentItem) {
  if (item.content_type === 'image' && item.preview?.startsWith('data:image')) {
    return `<div class="a-media a-media-image a-media-image-local"><img class="a-card-img a-card-img-local" src="${item.preview}" alt="" /></div>`;
  }
  return renderMediaShell(item.content_type, item.file_name || item.preview);
}

function renderPeerMediaPreview(item: PeerAnnouncement) {
  if (item.content_type === 'image' && item.preview?.startsWith('data:image')) {
    return `<div class="a-media a-media-image a-media-image-peer"><img class="a-card-img a-card-img-peer" src="${item.preview}" alt="" /></div>`;
  }
  return renderMediaShell(item.content_type, item.file_name || item.preview);
}

function renderMediaShell(type: ContentItem['content_type'], label: string) {
  return `
    <div class="a-media a-media-${type}">
      <div class="a-media-icon">${typeIcon(type)}</div>
      <div class="a-media-copy">${escapeHtml(contentTypeLabel(type))}</div>
      <div class="a-media-subcopy">${escapeHtml(compactLabel(label))}</div>
    </div>
  `;
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

function openReceiveSheet(contentId: string) {
  const peer = peerContent.find(item => item.content_id === contentId);
  if (!peer) {
    showToast('Peer no encontrado');
    return;
  }

  const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-sheet-title">Recibir contenido</div>
    <p class="a-sheet-copy">${escapeHtml(peer.file_name || compactLabel(peer.preview))}</p>
    <button class="a-sheet-btn" data-action="import">${iconInbox(20)} Guardar en hub temporal</button>
    <button class="a-sheet-btn" data-action="copy">${iconClipboard(20)} ${copyPeerActionLabel(peer.content_type)}</button>
    <button class="a-sheet-btn" data-action="save-as">${iconFile(20)} ${saveAsPeerActionLabel(peer.content_type)}</button>
    <button class="a-sheet-btn danger" data-action="cancel">Cancelar</button>
  `);

  const close = () => backdrop.remove();

  backdrop.querySelectorAll<HTMLButtonElement>('.a-sheet-btn').forEach(button => {
    button.addEventListener('click', () => {
      const action = button.dataset.action;
      close();
      if (action === 'import') {
        void importPeerToHub(contentId);
      } else if (action === 'copy') {
        void copyPeerToClipboard(contentId);
      } else if (action === 'save-as') {
        void savePeerAs(contentId);
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

async function importPeerToHub(id: string) {
  try {
    const received = await invoke<ContentItem>('pull_peer_content', { content_id: id });
    localContent = [received, ...localContent.filter(item => item.id !== received.id)];
    syncDerivedState();
    updateUI();
    showToast(receiveSuccessMessage(received));
    await refreshStateIfNative();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function copyPeerToClipboard(id: string) {
  try {
    const message = await invoke<string>('copy_peer_content', { content_id: id });
    showToast(message || 'Contenido copiado al portapapeles');
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function savePeerAs(id: string) {
  try {
    const message = await invoke<string | null>('save_peer_content_as', { content_id: id });
    if (message) {
      showToast(message);
    }
  } catch (error) {
    showToast(errorMessage(error));
  }
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
  const maxEdge = 1600;
  const scale = Math.min(maxEdge / bitmap.width, maxEdge / bitmap.height, 1);
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const ctx = canvas.getContext('2d');
  if (!ctx) return undefined;

  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL(file.type === 'image/png' ? 'image/png' : 'image/webp', 0.92);
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

function bindPasswordToggle(inputId: string, buttonId: string) {
  const input = document.getElementById(inputId) as HTMLInputElement | null;
  const button = document.getElementById(buttonId) as HTMLButtonElement | null;
  if (!input || !button) return;

  const setVisible = (visible: boolean) => {
    input.type = visible ? 'text' : 'password';
    button.setAttribute('aria-pressed', String(visible));
    button.setAttribute('aria-label', visible ? 'Ocultar passphrase' : 'Mostrar passphrase');
    button.title = visible ? 'Ocultar passphrase' : 'Mostrar passphrase';
    button.innerHTML = visible ? iconEyeOff(18) : iconEye(18);
  };

  setVisible(false);
  button.addEventListener('click', event => {
    event.preventDefault();
    setVisible(input.type === 'password');
    input.focus();
  });
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
    openReceiveSheet(id);
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

  async broadcastAll() {
    try {
      await invoke('publish_all_local');
      localContent = localContent.map(item => ({ ...item, is_published: true }));
      publishedIds = new Set(localContent.map(item => item.id));
      updateUI();
      showToast('Todo publicado en la red');
      await refreshStateIfNative();
    } catch (error) {
      showToast(errorMessage(error));
    }
  },

  async openMeshSheet() {
    await openMeshSheet();
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

function escapeHtml(value: string | null | undefined) {
  return (value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function humanSize(bytes: number) {
  if (!bytes || isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}

function formatRelativeTime(timestamp: number) {
  const normalized = timestamp < 1_000_000_000_000 ? timestamp * 1000 : timestamp;
  const diffMs = Math.max(0, Date.now() - normalized);
  const diffMinutes = Math.floor(diffMs / 60_000);
  if (diffMinutes < 1) return 'ahora';
  if (diffMinutes < 60) return `hace ${diffMinutes} min`;
  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `hace ${diffHours} h`;
  const diffDays = Math.floor(diffHours / 24);
  return `hace ${diffDays} d`;
}

function contentTypeLabel(type: ContentItem['content_type']) {
  if (type === 'text') return 'Texto';
  if (type === 'image') return 'Imagen';
  return 'Archivo';
}

function receiveSuccessMessage(item: ContentItem) {
  if (item.content_type === 'text') {
    return 'Texto importado al hub temporal';
  }
  if (item.content_type === 'image') {
    return 'Imagen importada al hub temporal';
  }
  return 'Archivo importado al hub temporal';
}

function copyPeerActionLabel(type: ContentItem['content_type']) {
  if (type === 'text') return 'Copiar texto';
  return 'Copiar al portapapeles';
}

function saveAsPeerActionLabel(type: ContentItem['content_type']) {
  if (type === 'text') return 'Guardar como TXT...';
  return 'Guardar como...';
}

function compactLabel(value: string | null | undefined) {
  const sanitized = (value ?? '').trim();
  if (sanitized.length <= 34) return sanitized;
  return `${sanitized.slice(0, 31)}...`;
}

function shortGroupLabel(groupId?: string | null) {
  if (!groupId) return 'red privada';
  return `grupo ${groupId.slice(0, 8)}`;
}

const svg = (
  size: number,
  viewBox: string,
  path: string,
  extra = 'stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"',
) => `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="none" ${extra}>${path}</svg>`;

function iconHub(size: number) {
  return `<img src="${LOGO_SRC}" alt="FenixHub" width="${size}" height="${size}" style="display:block;object-fit:contain;" />`;
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
    '<line x1="12" y1="5.5" x2="12" y2="18.5" stroke-width="2.6"/><line x1="5.5" y1="12" x2="18.5" y2="12" stroke-width="2.6"/>',
  );
}

function iconEye(size: number) {
  return svg(
    size,
    '0 0 18 18',
    '<path d="M1.8,9s2.7-4.5 7.2-4.5S16.2,9 16.2,9 13.5,13.5 9,13.5 1.8,9 1.8,9Z" stroke-width="1.6"/><circle cx="9" cy="9" r="2.2" stroke-width="1.6"/>',
  );
}

function iconEyeOff(size: number) {
  return svg(
    size,
    '0 0 18 18',
    '<path d="M2.2,2.2 15.8,15.8" stroke-width="1.7"/><path d="M6.5,4.9A7.4,7.4 0 0 1 9,4.5C13.5,4.5 16.2,9 16.2,9a13.2,13.2 0 0 1-2.5,2.8" stroke-width="1.6"/><path d="M11.1,13.1A7.2,7.2 0 0 1 9,13.5C4.5,13.5 1.8,9 1.8,9a13.2,13.2 0 0 1 2.4-2.7" stroke-width="1.6"/><path d="M7.4,7.4a2.2,2.2 0 0 0 3.2,3.2" stroke-width="1.6"/>',
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

function iconSettings(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<circle cx="10" cy="10" r="3" stroke-width="1.8"/><path d="M10 2.3v2.1M10 15.6v2.1M2.3 10h2.1M15.6 10h2.1M4.6 4.6l1.5 1.5M13.9 13.9l1.5 1.5M15.4 4.6l-1.5 1.5M6.1 13.9l-1.5 1.5" stroke-width="1.6"/>',
  );
}

function iconCheck(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<polyline points="4.5,10.5 8.2,14.2 15.5,6.8" stroke-width="2"/>',
  );
}

function iconBroadcast(size: number) {
  return svg(
    size,
    '0 0 20 20',
    '<circle cx="9" cy="5" r="2" fill="none" stroke-width="1.6"/><circle cx="16" cy="5" r="2" fill="none" stroke-width="1.6"/><circle cx="9" cy="12" r="2" fill="none" stroke-width="1.6"/><circle cx="16" cy="12" r="2" fill="none" stroke-width="1.6"/><line x1="10.5" y1="5" x2="14.5" y2="11.5" stroke-width="1.6"/><line x1="14.5" y1="5" x2="10.5" y2="11.5" stroke-width="1.6"/>',
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

// ── Direct Mode Receiver (Incoming Invites) ──────────────────────────────────

function startReceiverModePolling() {
  if (receiverModeInterval !== null) return;
  receiverModeInterval = window.setInterval(() => {
    if (document.hidden) return;
    void pollIncomingInvite();
  }, 3000);
  void pollIncomingInvite();
}

async function pollIncomingInvite() {
  if (currentIncomingInvite !== null) return;
  try {
    const resp = await invoke<{ has_invite: boolean; device_name?: string; ephemeral_group_id?: string; sender_ip?: string; sender_port?: number; content_id?: string }>('get_current_inviter');
    if (resp.has_invite && resp.device_name) {
      currentIncomingInvite = {
        deviceName: resp.device_name,
        ephemeralGroupId: resp.ephemeral_group_id || '',
        senderIp: resp.sender_ip || '',
        senderPort: resp.sender_port || 0,
        contentId: resp.content_id || '',
      };
      showIncomingInviteModal(currentIncomingInvite);
    }
  } catch {
    // Silently ignore polling errors
  }
}

function showIncomingInviteModal(invite: IncomingInvite) {
  const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-direct-invite-header">
      <div class="a-direct-invite-icon">${iconDirect(32)}</div>
      <div class="a-direct-invite-title">Transferencia directa</div>
    </div>
    <p class="a-direct-invite-body">
      <strong>${escapeHtml(invite.deviceName)}</strong> quiere<br/>
      enviarte contenido por WiFi Direct.
    </p>
    <p class="a-direct-invite-sub">Grupo: ${escapeHtml(invite.ephemeralGroupId)}</p>
    <div class="a-sheet-row">
      <button class="a-sheet-btn danger" data-action="reject">Rechazar</button>
      <button class="a-sheet-btn" data-action="accept">${iconCheck(16)} Unirse y recibir</button>
    </div>
  `);

  backdrop.querySelectorAll<HTMLButtonElement>('.a-sheet-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      closeInviteBackdrop(backdrop);
      if (action === 'accept') {
        void acceptDirectInviteFromModal();
      } else {
        void rejectDirectInviteFromModal();
      }
    });
  });
}

async function acceptDirectInviteFromModal() {
  showToast('Conectando al grupo...');
  try {
    await invoke('accept_direct_invite');
    showToast('Recibiendo contenido...');
    await refreshState();
    activeTab = 'red';
    updateUI();
  } catch (error) {
    showToast(errorMessage(error));
  }
}

async function rejectDirectInviteFromModal() {
  try {
    await invoke('reject_direct_invite');
  } catch {
    // Silently ignore
  }
}

function closeInviteBackdrop(_backdrop: HTMLElement) {
  currentIncomingInvite = null;
  _backdrop.remove();
}

interface MeshState {
  role: 'none' | 'host' | 'device';
  status: 'idle' | 'discovering' | 'pending' | 'forming' | 'active' | 'transferring' | 'destroying';
  mesh_id: string | null;
  passphrase_set: boolean;
  pending_devices: MeshDevice[];
  active_devices: MeshDevice[];
  local_content_pool: string[];
  is_active: boolean;
  can_add_devices: boolean;
  can_leave: boolean;
  pending_count: number;
}

interface MeshDevice {
  id: string;
  name: string;
  mesh_id?: string | null;
  rssi: number;
  status: string;
  joined_at: number | null;
}

let meshSheetOpen = false;
let meshState: MeshState = {
  role: 'none',
  status: 'idle',
  mesh_id: null,
  passphrase_set: false,
  pending_devices: [],
  active_devices: [],
  local_content_pool: [],
  is_active: false,
  can_add_devices: false,
  can_leave: false,
  pending_count: 0,
};
let meshRefreshInterval: number | null = null;

async function openMeshSheet() {
  if (meshSheetOpen) return;
  meshSheetOpen = true;

  await refreshMeshState();
  renderMeshSheet();
  await invoke('mesh_modal_open');

  meshRefreshInterval = window.setInterval(async () => {
    await refreshMeshState();
    updateMeshSheetState();
  }, 1500);
}

async function refreshMeshState() {
  try {
    meshState = await invoke<MeshState>('mesh_get_state');
  } catch {
    // ignore
  }
}

function closeMeshSheet() {
  meshSheetOpen = false;
  void invoke('mesh_modal_close');
  if (meshRefreshInterval) {
    clearInterval(meshRefreshInterval);
    meshRefreshInterval = null;
  }
  const backdrop = document.getElementById('a-sheet-backdrop');
  backdrop?.remove();
}

function renderMeshSheet() {
  const backdrop = createSheet(`
    <div class="a-sheet-handle"></div>
    <div class="a-mesh-header">
      <div class="a-mesh-icon">${iconMesh(28)}</div>
      <div class="a-mesh-title">Mesh WiFi Direct</div>
      <button class="a-mesh-close" id="mesh-close">${iconX(16)}</button>
    </div>
    <div class="a-mesh-hint" id="mesh-hint">Elige cómo participar</div>
    <div class="a-mesh-tabs" id="mesh-tabs">
      <button class="a-mesh-tab ${meshState.role === 'none' ? 'active' : ''}" data-mesh-role="host">${iconHost(20)} Host</button>
      <button class="a-mesh-tab ${meshState.role === 'none' ? '' : 'active'}" data-mesh-role="device">${iconDevice(20)} Device</button>
    </div>
    <div class="a-mesh-devices" id="mesh-devices-list">
      <div class="a-mesh-empty" id="mesh-empty">Pulsa Host o Device para empezar a buscar.</div>
    </div>
    <div class="a-mesh-actions" id="mesh-actions"></div>
  `);

  document.getElementById('mesh-close')!.addEventListener('click', closeMeshSheet);

  document.querySelectorAll<HTMLButtonElement>('.a-mesh-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      const role = tab.dataset.meshRole as 'host' | 'device';
      void selectMeshRole(role);
    });
  });

  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) closeMeshSheet();
  });
}

async function selectMeshRole(role: 'host' | 'device') {
  document.querySelectorAll('.a-mesh-tab').forEach(t => t.classList.remove('active'));
  document.querySelector(`[data-mesh-role="${role}"]`)?.classList.add('active');

  if (role === 'host') {
    if (localContent.length === 0) {
      showToast('Añade contenido al hub antes de crear mesh');
      return;
    }
    const contentPool = localContent.map(i => i.id);
    await invoke('mesh_start_host', { content_pool: contentPool });
    showToast('Creando mesh como host...');
  } else {
    await invoke('mesh_start_device');
    showToast('Buscando meshes...');
  }

  await refreshMeshState();
  updateMeshSheetState();
}

function updateMeshSheetState() {
  const hint = document.getElementById('mesh-hint');
  const deviceList = document.getElementById('mesh-devices-list');
  const actions = document.getElementById('mesh-actions');
  if (!hint || !deviceList || !actions) return;

  if (meshState.role === 'none') {
    hint.textContent = 'Elige cómo participar';
    deviceList.innerHTML = '<div class="a-mesh-empty">Pulsa Host o Device para empezar a buscar.</div>';
    actions.innerHTML = '';
    return;
  }

  if (meshState.status === 'idle') {
    hint.textContent = meshState.role === 'host' ? 'Iniciando host...' : 'Iniciando...';
    deviceList.innerHTML = '<div class="a-mesh-empty">Buscando...</div>';
    actions.innerHTML = '';
    return;
  }

  if (meshState.status === 'discovering') {
    hint.textContent = meshState.role === 'host'
      ? `${meshState.pending_count} dispositivo(s) encontrado(s)`
      : 'Buscando meshes...';

    if (meshState.pending_devices.length > 0) {
      deviceList.innerHTML = meshState.pending_devices.map(d => {
        if (meshState.role === 'host') {
          return `
        <div class="a-mesh-device">
          <div class="a-mesh-device-info">
            <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
            <span class="a-mesh-device-signal">${signalBars(d.rssi)}</span>
          </div>
          <div class="a-mesh-device-actions">
            <button class="a-mesh-btn-accept" data-device-id="${d.id}">${iconCheck(16)}</button>
            <button class="a-mesh-btn-reject" data-device-id="${d.id}">${iconX(16)}</button>
          </div>
        </div>`;
        } else {
          const hostMeshId = d.mesh_id || '';
          return `
        <div class="a-mesh-device a-mesh-device-pending">
          <div class="a-mesh-device-info">
            <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
            <span class="a-mesh-device-signal">${signalBars(d.rssi)}</span>
          </div>
          <div class="a-mesh-device-actions">
            <button class="a-mesh-btn-accept" data-host-mesh-id="${escapeAttribute(hostMeshId)}" data-host-name="${escapeAttribute(d.name)}" ${hostMeshId ? '' : 'disabled'}>
              Solicitar acceso
            </button>
          </div>
        </div>`;
        }
      }).join('');

      if (meshState.role === 'host') {
        deviceList.querySelectorAll('.a-mesh-btn-accept').forEach(btn => {
          (btn as HTMLElement).addEventListener('click', () => {
            const id = (btn as HTMLElement).dataset.deviceId!;
            void invoke('mesh_accept_device', { device_id: id });
            void refreshMeshState().then(updateMeshSheetState);
          });
        });
        deviceList.querySelectorAll('.a-mesh-btn-reject').forEach(btn => {
          (btn as HTMLElement).addEventListener('click', () => {
            const id = (btn as HTMLElement).dataset.deviceId!;
            void invoke('mesh_reject_device', { device_id: id });
            void refreshMeshState().then(updateMeshSheetState);
          });
        });
      } else {
        deviceList.querySelectorAll('.a-mesh-btn-accept').forEach(btn => {
          (btn as HTMLElement).addEventListener('click', () => {
            const meshId = (btn as HTMLElement).dataset.hostMeshId || '';
            const hostName = (btn as HTMLElement).dataset.hostName || 'Host';
            if (!meshId) {
              showToast('Host sin mesh id válido');
              return;
            }
            void invoke('mesh_request_join', { host_mesh_id: meshId, host_name: hostName });
            void refreshMeshState().then(updateMeshSheetState);
          });
        });
      }
    } else {
      deviceList.innerHTML = `<div class="a-mesh-empty">${meshState.role === 'host' ? 'Esperando dispositivos...' : 'Buscando meshes...'}</div>`;
    }

    if (meshState.role === 'host' && meshState.pending_count > 0) {
      const allAccepted = meshState.pending_devices.every(d => d.status === 'connected');
      if (allAccepted) {
        actions.innerHTML = `<button class="a-btn a-btn-primary" id="mesh-close-btn">Iniciar mesh</button>`;
        document.getElementById('mesh-close-btn')?.addEventListener('click', () => {
          void invoke('mesh_close_modal').then(closeMeshSheet);
        });
      } else {
        actions.innerHTML = `<div class="a-mesh-hint" style="text-align:center;color:var(--yellow);">Acepta o rechaza los dispositivos primero</div>`;
      }
    } else if (meshState.role === 'host') {
      actions.innerHTML = '';
    } else {
      actions.innerHTML = `<button class="a-btn a-btn-secondary" id="mesh-close-btn">Cancelar</button>`;
      document.getElementById('mesh-close-btn')?.addEventListener('click', () => {
        void invoke('mesh_cancel_discovery').then(closeMeshSheet);
      });
    }
    return;
  }

  if (meshState.status === 'pending') {
    hint.textContent = meshState.role === 'device'
      ? 'Solicitud enviada. Esperando aprobación del host y passphrase BLE...'
      : 'Pendiente';
    deviceList.innerHTML = meshState.pending_devices.length > 0
      ? meshState.pending_devices.map(d => `
        <div class="a-mesh-device a-mesh-device-pending">
          <div class="a-mesh-device-info">
            <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
          </div>
          <span class="a-mesh-device-badge pending">Esperando...</span>
        </div>`).join('')
      : '<div class="a-mesh-empty">Esperando respuesta del host...</div>';
    actions.innerHTML = `<button class="a-btn a-btn-secondary" id="mesh-close-btn">Cancelar</button>`;
    document.getElementById('mesh-close-btn')?.addEventListener('click', () => {
      void invoke('mesh_cancel_discovery').then(closeMeshSheet);
    });
    return;
  }

  if (meshState.status === 'forming') {
    hint.textContent = meshState.role === 'host' ? 'Formando grupo WiFi Direct...' : 'Conectando al grupo...';
    deviceList.innerHTML = meshState.active_devices.length > 0
      ? meshState.active_devices.map(d => `
        <div class="a-mesh-device a-mesh-device-active">
          <div class="a-mesh-device-info">
            <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
          </div>
          <span class="a-mesh-device-badge">Conectando...</span>
        </div>`).join('')
      : '<div class="a-mesh-empty">Estableciendo conexión...</div>';
    actions.innerHTML = '';
    return;
  }

  if (meshState.status === 'active' || meshState.status === 'transferring') {
    hint.textContent = meshState.role === 'host'
      ? `Mesh activo · ${meshState.active_devices.length} dispositivo(s)`
      : `Conectado al mesh de ${meshState.mesh_id || 'host'}`;

    if (meshState.role === 'host') {
      const inviteHtml = meshState.pending_devices.length > 0
        ? meshState.pending_devices.map(d => `
          <div class="a-mesh-device">
            <div class="a-mesh-device-info">
              <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
              <span class="a-mesh-device-signal">${signalBars(d.rssi)}</span>
            </div>
            <div class="a-mesh-device-actions">
              <button class="a-mesh-btn-accept" data-device-id="${d.id}">${iconCheck(16)}</button>
              <button class="a-mesh-btn-reject" data-device-id="${d.id}">${iconX(16)}</button>
            </div>
          </div>`).join('')
        : '<div class="a-mesh-empty">Sin nuevos dispositivos detectados</div>';

      const connectedHtml = meshState.active_devices.length > 0
        ? meshState.active_devices.map(d => `
          <div class="a-mesh-device a-mesh-device-active">
            <div class="a-mesh-device-info">
              <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
            </div>
            <div class="a-mesh-device-actions">
              <button class="a-mesh-btn-reject" data-expel-device-id="${d.id}">Expulsar</button>
            </div>
          </div>`).join('')
        : '<div class="a-mesh-empty">Aún no hay dispositivos conectados</div>';

      deviceList.innerHTML = `
        <div class="a-mesh-hint" style="text-align:left; margin-bottom:8px;">Disponibles para invitar</div>
        ${inviteHtml}
        <div class="a-mesh-hint" style="text-align:left; margin:12px 0 8px;">Integrantes de la mesh</div>
        ${connectedHtml}
      `;

      deviceList.querySelectorAll('.a-mesh-btn-accept').forEach(btn => {
        (btn as HTMLElement).addEventListener('click', () => {
          const id = (btn as HTMLElement).dataset.deviceId!;
          void invoke('mesh_accept_device', { device_id: id });
          void refreshMeshState().then(updateMeshSheetState);
        });
      });
      deviceList.querySelectorAll('.a-mesh-btn-reject').forEach(btn => {
        const expelId = (btn as HTMLElement).dataset.expelDeviceId;
        if (expelId) {
          (btn as HTMLElement).addEventListener('click', () => {
            void invoke('mesh_expel_device', { device_id: expelId });
            void refreshMeshState().then(updateMeshSheetState);
          });
        } else {
          (btn as HTMLElement).addEventListener('click', () => {
            const id = (btn as HTMLElement).dataset.deviceId!;
            void invoke('mesh_reject_device', { device_id: id });
            void refreshMeshState().then(updateMeshSheetState);
          });
        }
      });
    } else {
      deviceList.innerHTML = meshState.active_devices.length > 0
        ? meshState.active_devices.map(d => `
          <div class="a-mesh-device a-mesh-device-active">
            <div class="a-mesh-device-info">
              <span class="a-mesh-device-name">${escapeHtml(d.name)}</span>
            </div>
            <span class="a-mesh-device-badge">Conectado</span>
          </div>`).join('')
        : '<div class="a-mesh-empty">Mesh establecido</div>';
    }

    if (meshState.role === 'host') {
      actions.innerHTML = `
        <button class="a-btn a-btn-primary" id="mesh-finalize-btn">Finalizar envío</button>
        <button class="a-btn a-btn-secondary" id="mesh-leave-btn">Disolver mesh</button>`;
    } else {
      actions.innerHTML = `<button class="a-btn a-btn-danger" id="mesh-leave-btn">Salir del mesh</button>`;
    }

    const leaveBtn = document.getElementById('mesh-leave-btn');
    leaveBtn?.addEventListener('click', async () => {
      if (confirm('¿Seguro que quieres salir del mesh?')) {
        await invoke('mesh_leave');
        closeMeshSheet();
      }
    });

    const finalizeBtn = document.getElementById('mesh-finalize-btn');
    finalizeBtn?.addEventListener('click', async () => {
      await invoke('mesh_finalize');
      closeMeshSheet();
    });
    return;
  }

  if (meshState.status === 'destroying') {
    hint.textContent = 'Cerrando mesh...';
    deviceList.innerHTML = '<div class="a-mesh-empty">Dissolviendo grupo...</div>';
    actions.innerHTML = '';
    return;
  }

  hint.textContent = `Estado: ${meshState.status}`;
  deviceList.innerHTML = '<div class="a-mesh-empty">—</div>';
  actions.innerHTML = '';
}

function iconMesh(size: number) {
  return svg(
    size,
    '0 0 24 24',
    '<circle cx="5" cy="5" r="2.5" stroke-width="1.8" fill="none"/><circle cx="19" cy="5" r="2.5" stroke-width="1.8" fill="none"/><circle cx="5" cy="19" r="2.5" stroke-width="1.8" fill="none"/><circle cx="19" cy="19" r="2.5" stroke-width="1.8" fill="none"/><line x1="7" y1="5" x2="17" y2="19" stroke-width="1.8"/><line x1="17" y1="5" x2="7" y2="19" stroke-width="1.8"/>',
  );
}

function iconHost(size: number) {
  return svg(
    size,
    '0 0 24 24',
    '<circle cx="12" cy="8" r="4" stroke-width="1.8" fill="none"/><path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" stroke-width="1.8" fill="none"/><circle cx="12" cy="12" r="9" stroke-width="1.5" fill="none" stroke-dasharray="2 2"/>',
  );
}

function iconDevice(size: number) {
  return svg(
    size,
    '0 0 24 24',
    '<rect x="5" y="2" width="14" height="20" rx="3" stroke-width="1.8" fill="none"/><line x1="9" y1="18" x2="15" y2="18" stroke-width="1.8"/>',
  );
}

function signalBars(rssi: number): string {
  if (rssi > -60) return '●●●';
  if (rssi > -75) return '●●○';
  return '●○○';
}

function iconDirect(size: number) {
  return svg(
    size,
    '0 0 16 16',
    '<circle cx="4" cy="8" r="1.5" stroke-width="1.6"/><circle cx="12" cy="8" r="1.5" stroke-width="1.6"/><path d="M5.5,8 Q10,3 10.5,8" stroke-width="1.6"/>',
  );
}
