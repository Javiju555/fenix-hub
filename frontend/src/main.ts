import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import './style.css';

// ── Types ────────────────────────────────────────────────────────────────────

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
}

// ── State ────────────────────────────────────────────────────────────────────

let identity: IdentityInfo | null = null;
let localContent: ContentItem[] = [];
let peerContent: PeerAnnouncement[] = [];

// ── Init ─────────────────────────────────────────────────────────────────────

async function init() {
  identity = await invoke<IdentityInfo>('get_identity');

  if (!identity.configured) {
    renderSetup();
    return;
  }

  await loadContent();
  renderHub();
  setupEventListeners();
}

async function loadContent() {
  [localContent, peerContent] = await Promise.all([
    invoke<ContentItem[]>('get_local_content'),
    invoke<PeerAnnouncement[]>('get_peers'),
  ]);
}

// ── Event listeners (Tauri events from daemon) ────────────────────────────────

function setupEventListeners() {
  listen<PeerAnnouncement>('peer-content-available', ({ payload }) => {
    peerContent = [...peerContent.filter(p => p.content_id !== payload.content_id), payload];
    renderPeerContent();
  });

  listen<{ content_id: string }>('peer-content-gone', ({ payload }) => {
    peerContent = peerContent.filter(p => p.content_id !== payload.content_id);
    renderPeerContent();
  });

  listen<PeerAnnouncement>('direct-notify-received', ({ payload }) => {
    peerContent = [payload, ...peerContent];
    renderPeerContent();
    // Hub window is already open (daemon called open_hub_window before emitting this)
  });
}

// ── Render ────────────────────────────────────────────────────────────────────

function renderSetup() {
  document.getElementById('app')!.innerHTML = `
    <div class="hub-setup">
      <div class="setup-logo">⬡</div>
      <h1>FenixHub</h1>
      <p class="setup-subtitle">Portapapeles efímero en tu red local</p>
      <form id="setup-form">
        <label>
          <span>Frase de acceso</span>
          <input type="password" id="passphrase" placeholder="Escribe una frase — la misma en todos tus dispositivos" autocomplete="off" />
          <small>Nunca sale de este dispositivo. Actúa como semilla para identificar tu grupo.</small>
        </label>
        <label>
          <span>Nombre de este dispositivo</span>
          <input type="text" id="device-name" placeholder="ej. Arch Desktop, Windows Laptop..." />
        </label>
        <button type="submit">Activar FenixHub</button>
      </form>
    </div>
  `;

  document.getElementById('setup-form')!.addEventListener('submit', async (e) => {
    e.preventDefault();
    const passphrase = (document.getElementById('passphrase') as HTMLInputElement).value.trim();
    const deviceName = (document.getElementById('device-name') as HTMLInputElement).value.trim();
    if (!passphrase || !deviceName) return;

    identity = await invoke<IdentityInfo>('setup_identity', { args: { passphrase, device_name: deviceName } });
    await loadContent();
    renderHub();
    setupEventListeners();
  });
}

function renderHub() {
  document.getElementById('app')!.innerHTML = `
    <div class="hub">
      <header class="hub-header">
        <span class="hub-title">⬡ FenixHub</span>
        <span class="hub-device">${identity!.device_name}</span>
      </header>

      <div class="hub-add">
        <textarea id="text-input" placeholder="Pega texto aquí o arrastra un archivo..." rows="2"></textarea>
        <button id="add-btn">Añadir al hub</button>
      </div>

      <section class="hub-section">
        <h2>Local <span class="count" id="local-count">0</span></h2>
        <div id="local-content" class="content-list"></div>
      </section>

      <section class="hub-section">
        <h2>En la red <span class="count" id="peer-count">0</span></h2>
        <div id="peer-content" class="content-list"></div>
      </section>
    </div>
  `;

  document.getElementById('add-btn')!.addEventListener('click', async () => {
    const input = document.getElementById('text-input') as HTMLTextAreaElement;
    const text = input.value.trim();
    if (!text) return;
    const item = await invoke<ContentItem>('add_text_content', { text });
    localContent = [item, ...localContent];
    input.value = '';
    renderLocalContent();
  });

  // Drag-to-hub: drop text or files on the hub panel
  const hubEl = document.querySelector('.hub')!;
  hubEl.addEventListener('dragover', (e) => {
    e.preventDefault();
    hubEl.classList.add('drag-over');
  });
  hubEl.addEventListener('dragleave', () => hubEl.classList.remove('drag-over'));
  hubEl.addEventListener('drop', async (e) => {
    e.preventDefault();
    hubEl.classList.remove('drag-over');
    const dt = (e as DragEvent).dataTransfer;
    if (!dt) return;
    if (dt.files.length > 0) {
      // TODO: invoke add_file_content when implemented
    } else {
      const text = dt.getData('text/plain');
      if (text) {
        const item = await invoke<ContentItem>('add_text_content', { text });
        localContent = [item, ...localContent];
        renderLocalContent();
      }
    }
  });

  renderLocalContent();
  renderPeerContent();
}

function renderLocalContent() {
  const container = document.getElementById('local-content')!;
  const count = document.getElementById('local-count')!;
  count.textContent = String(localContent.length);

  if (localContent.length === 0) {
    container.innerHTML = '<p class="empty">Sin contenido local</p>';
    return;
  }

  container.innerHTML = localContent.map(item => `
    <div class="content-card" data-id="${item.id}">
      <span class="content-icon">${contentIcon(item.content_type)}</span>
      <span class="content-preview">${escapeHtml(item.preview)}</span>
      <div class="content-actions">
        <button class="btn-broadcast" data-id="${item.id}">Broadcast</button>
        <button class="btn-remove" data-id="${item.id}">✕</button>
      </div>
    </div>
  `).join('');

  container.querySelectorAll('.btn-broadcast').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = (btn as HTMLElement).dataset.id!;
      await invoke('publish_content', { args: { content_id: id, target_device: null } });
    });
  });

  container.querySelectorAll('.btn-remove').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = (btn as HTMLElement).dataset.id!;
      await invoke('remove_content', { id });
      localContent = localContent.filter(i => i.id !== id);
      renderLocalContent();
    });
  });
}

function renderPeerContent() {
  const container = document.getElementById('peer-content')!;
  const count = document.getElementById('peer-count')!;
  count.textContent = String(peerContent.length);

  if (peerContent.length === 0) {
    container.innerHTML = '<p class="empty">Sin contenido de otros dispositivos</p>';
    return;
  }

  container.innerHTML = peerContent.map(item => `
    <div class="content-card peer" data-id="${item.content_id}">
      <span class="content-icon">${contentIcon(item.content_type)}</span>
      <div class="content-meta">
        <span class="content-preview">${escapeHtml(item.preview)}</span>
        <span class="content-device">${escapeHtml(item.device_name)}</span>
      </div>
      <button class="btn-pull" data-id="${item.content_id}">Tomar</button>
    </div>
  `).join('');

  container.querySelectorAll('.btn-pull').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = (btn as HTMLElement).dataset.id!;
      await invoke('pull_peer_content', { content_id: id });
      peerContent = peerContent.filter(i => i.content_id !== id);
      renderPeerContent();
    });
  });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function contentIcon(type: string): string {
  return type === 'text' ? '📝' : type === 'image' ? '🖼' : '📁';
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── Boot ──────────────────────────────────────────────────────────────────────

init();
