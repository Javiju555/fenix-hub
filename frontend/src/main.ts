import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { listen as tauriListen } from '@tauri-apps/api/event';
import './style.css';

// ── Browser/Tauri detection ───────────────────────────────────────────────────

const IS_TAURI = '__TAURI_INTERNALS__' in window;
const IS_ANDROID = navigator.userAgent.toLowerCase().includes('android');

async function invoke<T>(cmd: string, args?: unknown): Promise<T> {
  if (IS_TAURI) return tauriInvoke<T>(cmd, args as Record<string, unknown>);
  return mockInvoke<T>(cmd, args);
}

function listen<T>(event: string, cb: (e: { payload: T }) => void) {
  if (IS_TAURI) return tauriListen<T>(event, cb);
  return Promise.resolve(() => {});
}

function peerCommandArgs(contentId: string) {
  return {
    contentId,
    content_id: contentId,
  };
}

async function setWindowSize(w: number, h: number) {
  if (!IS_TAURI) return;
  // Use Rust command — bypasses `resizable: false` JS restriction on Windows
  await invoke('resize_hub', { width: w, height: h });
}

async function closeApp() {
  if (!IS_TAURI) return;
  // Use Rust command for reliable cleanup + destroy
  await invoke('close_hub_window');
}

// ── Mock backend ──────────────────────────────────────────────────────────────

const MOCK_LOCAL: ContentItem[] = [
  { id: 'a1', content_type: 'text',  preview: 'npm install @tauri-apps/api@2 --save', size_bytes: 38,        created_at: Date.now()/1000, file_name: null, mime_type: 'text/plain; charset=utf-8' },
  { id: 'a2', content_type: 'file',  preview: 'diseño-fenix-hub.fig',                 size_bytes: 4_400_000, created_at: Date.now()/1000 - 60, file_name: 'diseño-fenix-hub.fig', mime_type: 'application/octet-stream' },
  { id: 'a3', content_type: 'image', preview: 'screenshot-2026.png',                  size_bytes: 1_100_000, created_at: Date.now()/1000 - 120, file_name: 'screenshot-2026.png', mime_type: 'image/png' },
];

const MOCK_PEERS: PeerAnnouncement[] = [
  { group_id: 'demo', content_id: 'p1', device_name: 'Windows Laptop', preview: 'Reunión viernes 10h sala B', content_type: 'text', size_bytes: 34,     send_mode: { Broadcast: null }, created_at: Date.now()/1000, port: 0, file_name: null, mime_type: 'text/plain; charset=utf-8' },
  { group_id: 'demo', content_id: 'p2', device_name: 'Windows Laptop', preview: 'presupuesto-q2.xlsx',        content_type: 'file', size_bytes: 90_000, send_mode: { Broadcast: null }, created_at: Date.now()/1000, port: 0, file_name: 'presupuesto-q2.xlsx', mime_type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
];

let mockLocal = [...MOCK_LOCAL];
let mockPeers = [...MOCK_PEERS];
let mockPublished = new Set<string>();
let mockId = 100;

async function mockInvoke<T>(cmd: string, args?: unknown): Promise<T> {
  const a = args as Record<string, unknown> | undefined;
  switch (cmd) {
    case 'get_identity': return { device_name: 'Arch Desktop', group_id: 'demo', configured: true, device_type: 'desktop' } as T;
    case 'get_local_content': return [...mockLocal] as T;
    case 'get_peers': return [...mockPeers] as T;
    case 'add_text_content': {
      const text = a?.text as string;
      const item: ContentItem = { id: String(mockId++), content_type: 'text', preview: text.slice(0, 80), size_bytes: text.length, created_at: Date.now()/1000, file_name: null, mime_type: 'text/plain; charset=utf-8' };
      mockLocal = [item, ...mockLocal]; return item as T;
    }
    case 'add_binary_content': {
      const args = a?.args as { file_name: string; mime_type?: string | null; preview?: string | null } | undefined;
      const item: ContentItem = {
        id: String(mockId++),
        content_type: args?.mime_type?.startsWith('image/') ? 'image' : 'file',
        preview: args?.preview || args?.file_name || 'archivo',
        size_bytes: 0,
        created_at: Date.now()/1000,
        file_name: args?.file_name ?? 'archivo',
        mime_type: args?.mime_type ?? 'application/octet-stream',
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'remove_content':
      mockLocal = mockLocal.filter(i => i.id !== (a?.id as string));
      mockPublished.delete(a?.id as string); return undefined as T;
    case 'publish_content': {
      const id = (a?.args as { content_id: string })?.content_id;
      if (id) mockPublished.add(id); return undefined as T;
    }
    case 'stop_server': mockPublished.clear(); return undefined as T;
    case 'pull_peer_content': {
      const peer = mockPeers.find(p => p.content_id === (a?.content_id as string));
      const item: ContentItem = {
        id: String(mockId++),
        content_type: peer?.content_type ?? 'text',
        preview: peer?.preview ?? '',
        size_bytes: peer?.size_bytes ?? 0,
        created_at: Date.now()/1000,
        file_name: peer?.file_name ?? null,
        mime_type: peer?.mime_type ?? null,
      };
      mockLocal = [item, ...mockLocal];
      return item as T;
    }
    case 'copy_peer_content': return undefined as T;
    case 'save_peer_content_as': return { saved: true, path: 'C:\\temp\\fenixhub-mock.bin' } as T;
    case 'save_local_content_as': return { saved: true, path: 'C:\\temp\\fenixhub-local-mock.bin' } as T;
    case 'setup_identity':
      return { device_name: (a?.args as { device_name: string })?.device_name ?? 'Device', group_id: 'demo', configured: true, device_type: (a?.args as { device_type?: string })?.device_type ?? 'desktop' } as T;
    default: return undefined as T;
  }
}

// ── Types ─────────────────────────────────────────────────────────────────────

interface IdentityInfo   { device_name: string; group_id: string; configured: boolean; device_type: string; }
interface ContentItem    { id: string; content_type: 'text'|'image'|'file'; preview: string; size_bytes: number; created_at: number; file_name?: string | null; mime_type?: string | null; transfer_path?: string | null; data_text?: string | null; }
interface PeerAnnouncement { group_id: string; content_id: string; device_name: string; preview: string; content_type: 'text'|'image'|'file'; size_bytes: number; send_mode: { Broadcast: null }|{ Direct: { target_device: string } }; created_at: number; port: number; file_name?: string | null; mime_type?: string | null; }
interface PeerContentPayload { announcement: PeerAnnouncement; peer_ip: string; }
interface DragPayload { text?: string | null; uri_list?: string | null; }
interface SaveContentResult { saved: boolean; path?: string | null; }

// ── State ─────────────────────────────────────────────────────────────────────

let identity: IdentityInfo | null = null;
let localContent: ContentItem[] = [];
let peerContent:  PeerAnnouncement[] = [];
let publishedIds  = new Set<string>();
let onlineDevices: string[] = [];
let activeTab: 'local' | 'red' = 'local';
let collapsed = false;
let selectedDeviceType = 'desktop';
const dragPayloadCache = new Map<string, DragPayload>();

const DEVICE_TYPES: { id: string; label: string; icon: () => string }[] = [
  { id: 'desktop', label: 'Desktop',  icon: () => svg(18,'0 0 20 18','<rect x="1" y="1" width="18" height="13" rx="2" stroke-width="1.5"/><line x1="6" y1="17" x2="14" y2="17" stroke-width="1.5"/><line x1="10" y1="14" x2="10" y2="17" stroke-width="1.5"/>') },
  { id: 'laptop',  label: 'Laptop',   icon: () => svg(18,'0 0 20 18','<rect x="2" y="2" width="16" height="11" rx="1.5" stroke-width="1.5"/><path d="M0,16 Q10,14 20,16" stroke-width="1.5" fill="none"/>') },
  { id: 'phone',   label: 'Android',  icon: () => svg(18,'0 0 14 20','<rect x="1" y="1" width="12" height="18" rx="3" stroke-width="1.5"/><line x1="5.5" y1="16.5" x2="8.5" y2="16.5" stroke-width="1.5"/>') },
  { id: 'tablet',  label: 'Tablet',   icon: () => svg(18,'0 0 16 20','<rect x="1" y="1" width="14" height="18" rx="2.5" stroke-width="1.5"/><line x1="6" y1="16.5" x2="10" y2="16.5" stroke-width="1.5"/>') },
  { id: 'server',  label: 'Servidor', icon: () => svg(18,'0 0 20 18','<rect x="1" y="1" width="18" height="7" rx="1.5" stroke-width="1.5"/><rect x="1" y="10" width="18" height="7" rx="1.5" stroke-width="1.5"/><circle cx="4.5" cy="4.5" r="1" fill="currentColor" stroke="none"/><circle cx="4.5" cy="13.5" r="1" fill="currentColor" stroke="none"/>') },
];

const W = 820, H = 185;
const W_PILL = 280, H_PILL = 34;

// ── Init ──────────────────────────────────────────────────────────────────────

async function init() {
  // Block browser context menu — this is a native-style app, not a webpage
  document.addEventListener('contextmenu', e => e.preventDefault());

  identity = await invoke<IdentityInfo>('get_identity');
  if (!identity.configured) { renderSetup(); return; }
  await loadContent();
  renderHub();
  setupEventListeners();
}

async function loadContent() {
  [localContent, peerContent] = await Promise.all([
    invoke<ContentItem[]>('get_local_content'),
    invoke<PeerAnnouncement[]>('get_peers'),
  ]);
  onlineDevices = [...new Set(peerContent.map(p => p.device_name))];
}

// ── Tauri events ──────────────────────────────────────────────────────────────

function setupEventListeners() {
  listen('hub-activate', () => {
    if (collapsed) expand();
  });
  listen<PeerContentPayload>('peer-content-available', ({ payload }) => {
    const ann = payload.announcement;
    peerContent = [...peerContent.filter(p => p.content_id !== ann.content_id), ann];
    if (!onlineDevices.includes(ann.device_name)) onlineDevices = [...onlineDevices, ann.device_name];
    updateHeader();
    if (activeTab === 'red') renderPeerContent();
  });
  listen<{ content_id: string; device_name: string }>('peer-content-gone', ({ payload }) => {
    peerContent = peerContent.filter(p => p.content_id !== payload.content_id);
    if (!peerContent.some(p => p.device_name === payload.device_name))
      onlineDevices = onlineDevices.filter(d => d !== payload.device_name);
    updateHeader();
    if (activeTab === 'red') renderPeerContent();
  });
  listen<PeerContentPayload>('direct-notify-received', ({ payload }) => {
    const ann = payload.announcement;
    peerContent = [ann, ...peerContent.filter(p => p.content_id !== ann.content_id)];
    updateHeader();
    if (collapsed) expand();
    switchTab('red');
  });
}

// ── Setup screen ──────────────────────────────────────────────────────────────

function renderSetup() {
  document.getElementById('app')!.innerHTML = `
    <div class="hub-setup">
      <div class="setup-row">
        <div class="setup-logo">${iconHub(24)}</div>
        <h1>FenixHub</h1>
        <p>Portapapeles efímero · sin cuenta · sin nube</p>
      </div>
      <div class="device-type-row">
        ${DEVICE_TYPES.map(dt => `
          <button class="device-type-btn${dt.id === selectedDeviceType ? ' active' : ''}" data-dtype="${dt.id}" title="${dt.label}">
            ${dt.icon()}<span>${dt.label}</span>
          </button>`).join('')}
      </div>
      <div class="setup-fields">
        <input type="text"     id="device-name" placeholder="Nombre de este dispositivo" style="max-width:190px" autocomplete="off" />
        <input type="password" id="passphrase"   placeholder="Nombre del grupo (igual en todos)" autocomplete="off" />
        <button id="setup-btn">${iconCheckmark(11)} Activar</button>
      </div>
    </div>`;

  // Device type picker
  document.querySelectorAll('.device-type-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      selectedDeviceType = (btn as HTMLElement).dataset.dtype!;
      document.querySelectorAll('.device-type-btn').forEach(b =>
        b.classList.toggle('active', (b as HTMLElement).dataset.dtype === selectedDeviceType));
    });
  });

  const submit = async () => {
    const passphrase = (document.getElementById('passphrase') as HTMLInputElement).value.trim();
    const deviceName = (document.getElementById('device-name') as HTMLInputElement).value.trim();
    if (!passphrase || !deviceName) return;
    const btn = document.getElementById('setup-btn') as HTMLButtonElement;
    btn.disabled = true; btn.textContent = 'Activando…';
    identity = await invoke<IdentityInfo>('setup_identity', {
      args: { passphrase, device_name: deviceName, device_type: selectedDeviceType },
    });
    await loadContent(); renderHub(); setupEventListeners();
  };
  document.getElementById('setup-btn')!.addEventListener('click', submit);
  document.addEventListener('keydown', function h(e) {
    if (e.key === 'Enter') { submit(); document.removeEventListener('keydown', h); }
  });
}

// ── Hub ───────────────────────────────────────────────────────────────────────

function renderHub() {
  document.getElementById('app')!.innerHTML = `
    <div class="hub" id="hub-root">
      <header class="hub-header" id="hub-header">
        <div class="hub-logo">${iconHub(18)}</div>
        <span class="hub-title">FenixHub</span>
        <span class="hub-device-label" title="Dispositivo: ${escapeHtml(identity?.device_name ?? '')}">
          ${deviceTypeIcon(identity?.device_type ?? 'desktop', 13)}
        </span>

        <div class="hub-tabs" id="hub-tabs">
          <button class="tab active" data-tab="local">${iconInbox(10)} Local <span class="badge" id="count-local">0</span></button>
          <button class="tab"        data-tab="red"  >${iconWifi(10)}  Red   <span class="badge" id="count-red">0</span></button>
        </div>

        <div class="hub-status">
          <div class="status-dot scanning" id="status-dot"></div>
          <span id="status-text">Buscando…</span>
          <span class="enc-badge" title="Cifrado AES-256-GCM extremo a extremo activo">${iconLock(9)}</span>
        </div>

        <div class="hub-collapsed-bar" id="hub-collapsed-bar" title="Mostrar FenixHub">
          <span class="hub-pull-grip" aria-hidden="true"></span>
          <span class="hub-collapsed-copy">
            <span class="hub-collapsed-label">${iconChevronDown(10)} Abrir</span>
            <span class="hub-collapsed-counts">
              <span class="hub-collapsed-pill">${iconInbox(9)} <span id="count-local-mini">0</span></span>
              <span class="hub-collapsed-pill">${iconWifi(9)} <span id="count-red-mini">0</span></span>
            </span>
          </span>
        </div>

        <div class="hub-actions">
          <button class="btn-icon" id="btn-share-all" title="Compartir todo con todos">${iconBroadcast(13)}</button>
          <button class="btn-icon" id="btn-collapse"  title="Minimizar a notch">${iconMinus(13)}</button>
          <button class="btn-icon danger" id="btn-close" title="Ocultar al tray">${iconX(12)}</button>
        </div>
      </header>

      <div class="tab-panel active" id="panel-local"></div>
      <div class="tab-panel"        id="panel-red"></div>
    </div>`;

  // Tabs
  document.getElementById('hub-tabs')!.addEventListener('click', (e) => {
    const btn = (e.target as HTMLElement).closest('.tab') as HTMLElement;
    if (btn?.dataset.tab) switchTab(btn.dataset.tab as 'local' | 'red');
  });

  // Share all
  document.getElementById('btn-share-all')!.addEventListener('click', async () => {
    for (const item of localContent) {
      if (!publishedIds.has(item.id)) {
        await invoke('publish_content', { args: { content_id: item.id, target_device: null } });
        publishedIds.add(item.id);
      }
    }
    renderLocalContent();
  });

  // Collapse → pill
  document.getElementById('btn-collapse')!.addEventListener('click', () => collapsed ? expand() : collapse());

  // Click header when collapsed → expand
  document.getElementById('hub-header')!.addEventListener('click', (e) => {
    if (collapsed && !(e.target as HTMLElement).closest('.hub-actions')) expand();
  });

  // Close app
  document.getElementById('btn-close')!.addEventListener('click', async () => {
    await closeApp();
  });

  // Drag-to-hub
  const hub = document.getElementById('hub-root')!;
  hub.addEventListener('dragover', (e) => { e.preventDefault(); e.dataTransfer!.dropEffect = 'copy'; hub.classList.add('drag-over'); });
  hub.addEventListener('dragleave', (e) => {
    if (!(e.relatedTarget as HTMLElement)?.closest('#hub-root')) hub.classList.remove('drag-over');
  });
  hub.addEventListener('drop', async (e) => {
    e.preventDefault(); hub.classList.remove('drag-over');
    const files = Array.from((e as DragEvent).dataTransfer?.files ?? []);
    if (files.length > 0) {
      const items = await Promise.all(files.map(addBrowserFileToHub));
      localContent = [...items, ...localContent];
      updateHeader();
      if (activeTab !== 'local') switchTab('local'); else renderLocalContent();
      return;
    }
    const text = (e as DragEvent).dataTransfer?.getData('text/plain');
    if (text) {
      const item = await invoke<ContentItem>('add_text_content', { text });
      localContent = [item, ...localContent];
      updateHeader();
      if (activeTab !== 'local') switchTab('local'); else renderLocalContent();
    }
  });

  // Ctrl+V → add to hub
  document.addEventListener('paste', async (e) => {
    const cd = e.clipboardData;
    if (!cd) return;

    // Image from clipboard (screenshot, copied image)
    const imgItem = Array.from(cd.items).find(i => i.type.startsWith('image/'));
    if (imgItem) {
      const blob = imgItem.getAsFile();
      if (!blob) return;
      const file = new File([blob], `clipboard-${Date.now()}.png`, { type: blob.type || 'image/png' });
      const ci = await addBrowserFileToHub(file);
      localContent = [ci, ...localContent];
      updateHeader();
      if (activeTab !== 'local') switchTab('local'); else renderLocalContent();
      return;
    }

    // Plain text
    const text = cd.getData('text/plain').trim();
    if (text) {
      const ci = await invoke<ContentItem>('add_text_content', { text });
      localContent = [ci, ...localContent];
      updateHeader();
      if (activeTab !== 'local') switchTab('local'); else renderLocalContent();
    }
  });

  updateHeader();
  renderLocalContent();
  renderPeerContent();
}

async function collapse() {
  collapsed = true;
  document.getElementById('hub-root')!.classList.add('collapsed');
  document.getElementById('btn-collapse')!.innerHTML = iconChevronDown(13);
  document.getElementById('btn-collapse')!.title = 'Mostrar FenixHub';
  // Resize window FIRST, then let CSS transition finish
  await setWindowSize(W_PILL, H_PILL);
}

async function expand() {
  collapsed = false;
  // Grow the window before revealing content so layout doesn't flash
  await setWindowSize(W, H);
  document.getElementById('hub-root')!.classList.remove('collapsed');
  document.getElementById('btn-collapse')!.innerHTML = iconMinus(13);
  document.getElementById('btn-collapse')!.title = 'Minimizar a notch';
  switchTab(activeTab);
}

function renderTab(tab: 'local' | 'red') {
  if (tab === 'local') {
    renderLocalContent();
  } else {
    renderPeerContent();
  }
}

function switchTab(tab: 'local' | 'red') {
  activeTab = tab;
  document.querySelectorAll('.tab').forEach(t =>
    t.classList.toggle('active', (t as HTMLElement).dataset.tab === tab));
  document.querySelectorAll('.tab-panel').forEach(p =>
    p.classList.toggle('active', p.id === `panel-${tab}`));
  renderTab(tab);
}

function updateHeader() {
  const lc = document.getElementById('count-local');
  const rc = document.getElementById('count-red');
  const lcMini = document.getElementById('count-local-mini');
  const rcMini = document.getElementById('count-red-mini');
  if (lc) lc.textContent = String(localContent.length);
  if (rc) rc.textContent = String(peerContent.length);
  if (lcMini) lcMini.textContent = String(localContent.length);
  if (rcMini) rcMini.textContent = String(peerContent.length);

  const dot = document.getElementById('status-dot');
  const txt = document.getElementById('status-text');
  if (!dot || !txt) return;
  if (onlineDevices.length > 0) {
    dot.className = 'status-dot online';
    txt.textContent = `${onlineDevices.length} disp.`;
  } else {
    dot.className = 'status-dot scanning';
    txt.textContent = 'Buscando…';
  }
}

// ── Local panel ───────────────────────────────────────────────────────────────

function renderLocalContent() {
  const container = document.getElementById('panel-local')!;
  updateHeader();
  const validIds = new Set(localContent.map(item => item.id));
  for (const id of dragPayloadCache.keys()) {
    if (!validIds.has(id)) {
      dragPayloadCache.delete(id);
    }
  }

  if (localContent.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        ${iconInboxLarge()}
        <p>Arrastra contenido aquí o cópialo al portapapeles para añadirlo al hub</p>
      </div>`;
    return;
  }

  container.innerHTML = `<div class="card-grid">${localContent.map(item => {
    const pub = publishedIds.has(item.id);
    const actionBtns = pub
      ? `<button class="btn-stop" data-id="${item.id}" data-action="stop">■ Parar</button>`
      : [
          `<button class="btn-broadcast" data-id="${item.id}" data-action="broadcast">${iconBroadcast(9)} Todos</button>`,
          ...onlineDevices.map(d =>
            `<button class="btn-direct" data-id="${item.id}" data-action="direct" data-device="${escapeHtml(d)}">${iconDevice(9)} ${escapeHtml(d)}</button>`
          )
        ].join('');

    const isImg = item.content_type === 'image' && item.preview.startsWith('data:image');
    const topContent = isImg
      ? `<img class="card-thumb" src="${item.preview}" />
         <div class="card-image-caption">${escapeHtml(item.file_name || 'imagen recibida')}</div>`
      : `<div class="card-top">
           <div class="type-icon ${item.content_type}">${typeIcon(item.content_type)}</div>
           <div class="card-body">
             <div class="card-preview">${escapeHtml(item.file_name || item.preview)}</div>
           </div>
         </div>`;

    const liveTag = pub ? ' · <span style="color:var(--accent)">live</span>' : '';
    // For image cards: merge size + actions into one compact row (no separate meta row)
    const metaRow = isImg ? '' : `<div class="card-meta">${humanSize(item.size_bytes)}${liveTag}</div>`;
    const actionsRow = isImg
      ? `<div class="card-actions card-actions-img">
           <span class="card-size-inline">${humanSize(item.size_bytes)}${liveTag}</span>
           ${actionBtns}
         </div>`
      : `<div class="card-actions">${actionBtns}</div>`;

    return `
    <div class="card-wrap">
      <div class="content-card${pub ? ' broadcasting' : ''}${isImg ? ' image-card' : ''}" data-id="${item.id}" draggable="true">
        ${topContent}
        ${metaRow}
        ${actionsRow}
      </div>
      <button class="btn-delete" data-id="${item.id}" title="Quitar del hub">${iconX(8)}</button>
    </div>`;
  }).join('')}</div>`;

  container.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const el = btn as HTMLElement;
      const id = el.dataset.id!;
      if (el.dataset.action === 'stop') {
        await invoke('stop_server'); publishedIds.clear();
      } else if (el.dataset.action === 'broadcast') {
        await invoke('publish_content', { args: { content_id: id, target_device: null } }); publishedIds.add(id);
      } else if (el.dataset.action === 'direct') {
        await invoke('publish_content', { args: { content_id: id, target_device: el.dataset.device! } }); publishedIds.add(id);
      }
      renderLocalContent();
    });
  });

  container.querySelectorAll('.btn-delete').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = (btn as HTMLElement).dataset.id!;
      await invoke('remove_content', { id });
      publishedIds.delete(id);
      localContent = localContent.filter(i => i.id !== id);
      updateHeader(); renderLocalContent();
    });
  });

  // Click on card body/thumb/caption → copy to local clipboard
  container.querySelectorAll('.card-body, .card-thumb, .card-image-caption').forEach(el => {
    (el as HTMLElement).style.cursor = 'pointer';
    el.addEventListener('click', async () => {
      const card = (el as HTMLElement).closest('.content-card') as HTMLElement;
      const item = localContent.find(i => i.id === card?.dataset.id);
      if (!item) return;
      if (IS_TAURI) {
        await invoke('write_local_to_clipboard', { id: item.id }).catch(() => {});
      } else {
        await navigator.clipboard.writeText(item.preview).catch(() => {});
      }
      // Brief visual feedback
      card.style.outline = '1px solid var(--accent)';
      setTimeout(() => card.style.outline = '', 600);
    });
  });

  container.querySelectorAll('.content-card').forEach(card => {
    const id = (card as HTMLElement).dataset.id;
    if (id) {
      (card as HTMLElement).addEventListener('pointerdown', () => {
        void warmupDragPayload(id);
      }, { passive: true });
      (card as HTMLElement).addEventListener('mouseenter', () => {
        void warmupDragPayload(id);
      });
    }

    // dragstart MUST be synchronous — any await empties dataTransfer before the OS reads it
    (card as HTMLElement).addEventListener('dragstart', (event) => {
      const id = (card as HTMLElement).dataset.id;
      const item = localContent.find(entry => entry.id === id);
      if (!id || !item) return;

      const dataTransfer = (event as DragEvent).dataTransfer;
      if (!dataTransfer) return;
      dataTransfer.effectAllowed = 'copy';

      if (item.content_type === 'text') {
        const text = item.data_text || item.preview;
        dataTransfer.setData('text/plain', text);
        // Silently copy to clipboard so Ctrl+V always works as fallback
        if (IS_TAURI) invoke('write_local_to_clipboard', { id }).catch(() => {});
      } else if (item.transfer_path) {
        const fwdPath = item.transfer_path.replace(/\\/g, '/');
        const uri = fwdPath.startsWith('/') ? `file://${fwdPath}` : `file:///${fwdPath}`;
        dataTransfer.setData('text/plain', item.transfer_path);
        dataTransfer.setData('text/uri-list', uri);
      } else {
        dataTransfer.setData('text/plain', item.file_name || item.preview);
      }
    });

    // If WebView2 couldn't complete the native drop (forbidden cursor), show hint
    (card as HTMLElement).addEventListener('dragend', (event) => {
      if ((event as DragEvent).dataTransfer?.dropEffect === 'none') {
        showDragFallbackHint();
      }
    });
  });
}

// ── Red panel ─────────────────────────────────────────────────────────────────

function renderPeerContent() {
  const container = document.getElementById('panel-red')!;
  updateHeader();

  if (peerContent.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        ${iconWifiLarge()}
        <p>Sin contenido de otros dispositivos en la red</p>
      </div>`;
    return;
  }

  container.innerHTML = `<div class="card-grid">${peerContent.map(item => {
    const isImg = item.preview.startsWith('data:image');
    const topContent = isImg
      ? `<img class="card-thumb" src="${item.preview}" />`
      : `<div class="card-top">
           <div class="type-icon ${item.content_type}">${typeIcon(item.content_type)}</div>
           <div class="card-body">
             <div class="card-preview">${escapeHtml(item.file_name || item.preview)}</div>
           </div>
         </div>`;
    return `
    <div class="content-card peer-card">
      ${topContent}
      <div class="card-meta card-device">
        ${iconDevice(9)} ${escapeHtml(item.device_name)} · ${humanSize(item.size_bytes)}
      </div>
      <div class="card-actions">
        <button class="btn-peer-secondary" data-peer-action="copy" data-id="${item.content_id}">${iconCopy(9)} Copiar</button>
        <button class="btn-peer-secondary" data-peer-action="save" data-id="${item.content_id}">${iconSave(9)} Guardar</button>
      </div>
    </div>`;
  }).join('')}</div>`;

  container.querySelectorAll<HTMLButtonElement>('[data-peer-action]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = btn.dataset.id!;
      const action = btn.dataset.peerAction!;
      const previousMarkup = btn.innerHTML;
      btn.disabled = true;

      try {
        if (action === 'copy') {
          btn.textContent = 'Copiando…';
          await invoke('copy_peer_content', peerCommandArgs(id));
          flashPeerAction(btn, 'Copiado');
          return;
        }

        btn.textContent = 'Guardando…';
        const result = await invoke<SaveContentResult>('save_peer_content_as', peerCommandArgs(id));
        if (result.saved) {
          flashPeerAction(btn, 'Guardado');
        } else {
          btn.disabled = false;
          btn.innerHTML = previousMarkup;
        }
      } catch (e) {
        console.error(`peer action ${action} failed:`, e);
        btn.disabled = false;
        btn.textContent = 'Error';
        window.setTimeout(() => {
          if (!btn.isConnected) return;
          btn.innerHTML = previousMarkup;
          btn.disabled = false;
        }, 1400);
      }
    });
  });
}

function flashPeerAction(button: HTMLButtonElement, label: string) {
  button.textContent = label;
  window.setTimeout(() => {
    if (!button.isConnected) return;
    renderPeerContent();
  }, 900);
}

// ── Icons ─────────────────────────────────────────────────────────────────────

const svg = (s: number, vb: string, path: string, extra = 'stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"') =>
  `<svg width="${s}" height="${s}" viewBox="${vb}" fill="none" ${extra}>${path}</svg>`;

const LOGO_SRC = './logo-mark.png';

function iconHub(s: number) {
  return `<img src="${LOGO_SRC}" alt="FenixHub" width="${s}" height="${s}" style="display:block;object-fit:contain;" />`;
}
function iconCheckmark(s: number) {
  return svg(s,'0 0 16 16','<polyline points="2.5,8 6.5,12 13.5,4" stroke-width="2.2"/>');
}
function iconInbox(s: number) {
  return svg(s,'0 0 16 16','<rect x="1.5" y="1.5" width="13" height="13" rx="2" stroke-width="1.7"/><polyline points="1.5,10 4.5,10 5.5,12.5 10.5,12.5 11.5,10 14.5,10" stroke-width="1.7"/>');
}
function iconWifi(s: number) {
  return svg(s,'0 0 16 16','<path d="M1.5,6 Q8,1 14.5,6" stroke-width="1.7"/><path d="M3.5,9 Q8,5.5 12.5,9" stroke-width="1.7"/><path d="M5.5,12 Q8,10 10.5,12" stroke-width="1.7"/><circle cx="8" cy="14" r="0.8" fill="currentColor" stroke="none"/>');
}
function iconBroadcast(s: number) {
  return svg(s,'0 0 16 16','<circle cx="8" cy="8" r="2" stroke-width="1.8"/><path d="M4,4 Q8,0.5 12,4" stroke-width="1.8"/><path d="M2.5,2.5 Q8,-1.5 13.5,2.5" stroke-width="1.8"/><path d="M4,12 Q8,15.5 12,12" stroke-width="1.8"/><path d="M2.5,13.5 Q8,17.5 13.5,13.5" stroke-width="1.8"/>');
}
function iconX(s: number) {
  return svg(s,'0 0 14 14','<line x1="2" y1="2" x2="12" y2="12" stroke-width="2"/><line x1="12" y1="2" x2="2" y2="12" stroke-width="2"/>');
}
function iconCopy(s: number) {
  return svg(s,'0 0 16 16','<rect x="5" y="3" width="8" height="10" rx="1.6" stroke-width="1.6"/><path d="M3.5,11.5 H3 a1.5,1.5 0 0 1 -1.5,-1.5 V4.5 A1.5,1.5 0 0 1 3,3 h5" stroke-width="1.6"/>');
}
function iconSave(s: number) {
  return svg(s,'0 0 16 16','<path d="M3,2.5 H11.5 L13.5,4.5 V13.5 H2.5 V3 A0.5,0.5 0 0 1 3,2.5 Z" stroke-width="1.6"/><rect x="5" y="9" width="6" height="3.5" rx="0.8" stroke-width="1.4"/><path d="M5,2.8 V6.3 H10.5 V2.8" stroke-width="1.4"/>');
}
function iconDevice(s: number) {
  return svg(s,'0 0 14 14','<rect x="1" y="2" width="12" height="9" rx="1.5" stroke-width="1.6"/><line x1="4" y1="13" x2="10" y2="13" stroke-width="1.6"/>');
}
function iconMinus(s: number) {
  return svg(s,'0 0 14 14','<line x1="2" y1="7" x2="12" y2="7" stroke-width="2"/>');
}
function iconChevronDown(s: number) {
  return svg(s,'0 0 14 14','<polyline points="2,4.5 7,9.5 12,4.5" stroke-width="2"/>');
}
function iconLock(s: number) {
  return svg(s,'0 0 14 14','<rect x="2" y="6" width="10" height="7" rx="1.5" stroke-width="1.6"/><path d="M4,6 V4 a4,4 0 0 1 6,0 V6" stroke-width="1.6" fill="none"/>');
}
function deviceTypeIcon(type: string, s: number): string {
  const dt = DEVICE_TYPES.find(d => d.id === type) ?? DEVICE_TYPES[0];
  // Re-render with custom size
  switch (type) {
    case 'laptop':  return svg(s,'0 0 20 18','<rect x="2" y="2" width="16" height="11" rx="1.5" stroke-width="1.5"/><path d="M0,16 Q10,14 20,16" stroke-width="1.5" fill="none"/>');
    case 'phone':   return svg(s,'0 0 14 20','<rect x="1" y="1" width="12" height="18" rx="3" stroke-width="1.5"/><line x1="5.5" y1="16.5" x2="8.5" y2="16.5" stroke-width="1.5"/>');
    case 'tablet':  return svg(s,'0 0 16 20','<rect x="1" y="1" width="14" height="18" rx="2.5" stroke-width="1.5"/><line x1="6" y1="16.5" x2="10" y2="16.5" stroke-width="1.5"/>');
    case 'server':  return svg(s,'0 0 20 18','<rect x="1" y="1" width="18" height="7" rx="1.5" stroke-width="1.5"/><rect x="1" y="10" width="18" height="7" rx="1.5" stroke-width="1.5"/>');
    default:        return svg(s,'0 0 20 18','<rect x="1" y="1" width="18" height="13" rx="2" stroke-width="1.5"/><line x1="6" y1="17" x2="14" y2="17" stroke-width="1.5"/><line x1="10" y1="14" x2="10" y2="17" stroke-width="1.5"/>');
  }
  return dt.icon();
}
function iconInboxLarge() {
  return svg(36,'0 0 24 24','<rect x="2" y="2" width="20" height="20" rx="3" stroke-width="1.2"/><polyline points="2,15 7,15 8.5,19 15.5,19 17,15 22,15" stroke-width="1.2"/>');
}
function iconWifiLarge() {
  return svg(36,'0 0 24 24','<path d="M1.5,9 Q12,1 22.5,9" stroke-width="1.2"/><path d="M4.5,13 Q12,7.5 19.5,13" stroke-width="1.2"/><path d="M7.5,17 Q12,13.5 16.5,17" stroke-width="1.2"/><circle cx="12" cy="20.5" r="1.2" fill="currentColor" stroke="none"/>');
}
function typeIcon(type: string) {
  if (type === 'text')  return svg(14,'0 0 16 16','<line x1="3" y1="5" x2="13" y2="5" stroke-width="1.8"/><line x1="3" y1="8" x2="13" y2="8" stroke-width="1.8"/><line x1="3" y1="11" x2="9" y2="11" stroke-width="1.8"/>');
  if (type === 'image') return svg(14,'0 0 16 16','<rect x="2" y="3" width="12" height="10" rx="1.5" stroke-width="1.8"/><circle cx="6" cy="6.5" r="1" stroke-width="1.8"/><polyline points="2,11 5.5,8 7.5,10 10,7.5 14,11" stroke-width="1.8"/>');
  return svg(14,'0 0 16 16','<path d="M10,2 H4 a1.5,1.5 0 0 0 -1.5,1.5 v9 a1.5,1.5 0 0 0 1.5,1.5 h8 a1.5,1.5 0 0 0 1.5,-1.5 V5.5 Z" stroke-width="1.8"/><polyline points="10,2 10,5.5 13.5,5.5" stroke-width="1.8"/>');
}

async function warmupDragPayload(id: string): Promise<void> {
  if (!IS_TAURI || dragPayloadCache.has(id)) return;
  try {
    const payload = await invoke<DragPayload>('prepare_local_drag', { id });
    dragPayloadCache.set(id, payload);
  } catch {
    // Drag has a sync fallback path; cache warm-up is best-effort.
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function escapeHtml(s: string) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function humanSize(b: number) {
  if (b < 1024) return `${b} B`;
  if (b < 1048576) return `${(b/1024).toFixed(1)} KB`;
  return `${(b/1048576).toFixed(1)} MB`;
}

const WARN_SIZE_BYTES = 100 * 1024 * 1024;   // 100 MB — mostrar aviso
const MAX_SIZE_BYTES  = 500 * 1024 * 1024;   // 500 MB — límite práctico (cifrado en RAM)

async function addBrowserFileToHub(file: File): Promise<ContentItem> {
  if (file.size > MAX_SIZE_BYTES) {
    throw new Error(`El archivo "${file.name}" supera el límite de 500 MB para transferencia cifrada en RAM.`);
  }
  if (file.size > WARN_SIZE_BYTES) {
    showSizeWarning(file.name, file.size);
  }

  // Tauri exposes the real filesystem path on File objects from drag & drop.
  // Use it to let Rust read the bytes directly — no base64, no RAM double.
  const nativePath = IS_TAURI ? (file as unknown as { path?: string }).path : undefined;
  if (nativePath) {
    return invoke<ContentItem>('add_file_by_path', { path: nativePath });
  }

  // Fallback: clipboard images and browser-only mode use base64
  const bytesBase64 = await fileToBase64(file);
  const preview = file.type.startsWith('image/') ? await imageFileToPreview(file) : undefined;
  return invoke<ContentItem>('add_binary_content', {
    args: {
      file_name: file.name || `clipboard-${Date.now()}`,
      mime_type: file.type || null,
      bytes_base64: bytesBase64,
      preview: preview || null,
    },
  });
}

function showDragFallbackHint() {
  const existing = document.getElementById('drag-hint');
  if (existing) return;
  const el = document.createElement('div');
  el.id = 'drag-hint';
  el.className = 'size-warn';
  el.innerHTML = `Ya en portapapeles — usa <kbd>Ctrl+V</kbd> para pegar &nbsp;<button onclick="this.parentElement.remove()">×</button>`;
  document.getElementById('panel-local')?.prepend(el);
  setTimeout(() => el.remove(), 4000);
}

function showSizeWarning(name: string, size: number) {
  const existing = document.getElementById('size-warn');
  if (existing) existing.remove();
  const el = document.createElement('div');
  el.id = 'size-warn';
  el.className = 'size-warn';
  el.innerHTML = `⚠ <strong>${escapeHtml(name)}</strong> (${humanSize(size)}) — archivo grande, puede tardar. <button onclick="this.parentElement.remove()">×</button>`;
  document.getElementById('panel-local')?.prepend(el);
  setTimeout(() => el.remove(), 8000);
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

// ── Boot ──────────────────────────────────────────────────────────────────────

if (IS_ANDROID) {
  import('./android').then(m => m.initAndroid());
} else {
  init();
}
