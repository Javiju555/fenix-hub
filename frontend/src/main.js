import { invoke as tauriInvoke, convertFileSrc } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { listen as tauriListen } from '@tauri-apps/api/event';
import './style.css';
// ── Browser/Tauri detection ───────────────────────────────────────────────────
const IS_TAURI = '__TAURI_INTERNALS__' in window;
const IS_ANDROID = navigator.userAgent.toLowerCase().includes('android');
async function invoke(cmd, args) {
    if (IS_TAURI)
        return tauriInvoke(cmd, args);
    return mockInvoke(cmd, args);
}
function listen(event, cb) {
    if (IS_TAURI)
        return tauriListen(event, cb);
    return Promise.resolve(() => { });
}
function peerCommandArgs(contentId) {
    return {
        contentId,
        content_id: contentId,
    };
}
async function setWindowSize(w, h) {
    if (!IS_TAURI)
        return;
    // Use Rust command — bypasses `resizable: false` JS restriction on Windows
    await invoke('resize_hub', { width: w, height: h });
}
async function closeApp() {
    if (!IS_TAURI)
        return;
    // Use Rust command for reliable cleanup + destroy
    await invoke('close_hub_window');
}
// ── Mock backend ──────────────────────────────────────────────────────────────
const MOCK_LOCAL = [
    { id: 'a1', content_type: 'text', preview: 'npm install @tauri-apps/api@2 --save', size_bytes: 38, created_at: Date.now() / 1000, file_name: null, mime_type: 'text/plain; charset=utf-8' },
    { id: 'a2', content_type: 'file', preview: 'diseño-fenix-hub.fig', size_bytes: 4400000, created_at: Date.now() / 1000 - 60, file_name: 'diseño-fenix-hub.fig', mime_type: 'application/octet-stream' },
    { id: 'a3', content_type: 'image', preview: 'screenshot-2026.png', size_bytes: 1100000, created_at: Date.now() / 1000 - 120, file_name: 'screenshot-2026.png', mime_type: 'image/png' },
];
const MOCK_PEERS = [
    { group_id: 'demo', content_id: 'p1', device_name: 'Windows Laptop', preview: 'Reunión viernes 10h sala B', content_type: 'text', size_bytes: 34, send_mode: { Broadcast: null }, created_at: Date.now() / 1000, port: 0, file_name: null, mime_type: 'text/plain; charset=utf-8' },
    { group_id: 'demo', content_id: 'p2', device_name: 'Windows Laptop', preview: 'presupuesto-q2.xlsx', content_type: 'file', size_bytes: 90000, send_mode: { Broadcast: null }, created_at: Date.now() / 1000, port: 0, file_name: 'presupuesto-q2.xlsx', mime_type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
];
let mockLocal = [...MOCK_LOCAL];
let mockPeers = [...MOCK_PEERS];
let mockPublished = new Set();
let mockId = 100;
let mockIdentity = {
    device_name: 'Arch Desktop',
    group_id: 'demo',
    configured: true,
    device_type: 'desktop',
};
let mockProfiles = [
    {
        name: 'personal',
        device_name: 'Arch Desktop',
        group_id: 'demo',
        device_type: 'desktop',
        active: true,
    },
];
async function mockInvoke(cmd, args) {
    const a = args;
    switch (cmd) {
        case 'get_identity': return mockIdentity;
        case 'get_local_content': return [...mockLocal];
        case 'get_peers': return [...mockPeers];
        case 'add_text_content': {
            const text = a?.text;
            const item = { id: String(mockId++), content_type: 'text', preview: text.slice(0, 80), size_bytes: text.length, created_at: Date.now() / 1000, file_name: null, mime_type: 'text/plain; charset=utf-8' };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'add_binary_content': {
            const args = a?.args;
            const item = {
                id: String(mockId++),
                content_type: args?.mime_type?.startsWith('image/') ? 'image' : 'file',
                preview: args?.preview || args?.file_name || 'archivo',
                size_bytes: 0,
                created_at: Date.now() / 1000,
                file_name: args?.file_name ?? 'archivo',
                mime_type: args?.mime_type ?? 'application/octet-stream',
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'remove_content':
            mockLocal = mockLocal.filter(i => i.id !== a?.id);
            mockPublished.delete(a?.id);
            return undefined;
        case 'publish_content': {
            const id = a?.args?.content_id;
            if (id)
                mockPublished.add(id);
            return undefined;
        }
        case 'stop_server':
            mockPublished.clear();
            return undefined;
        case 'start_direct_mode_sender': return undefined;
        case 'accept_direct_peers': return undefined;
        case 'cancel_direct_mode': return undefined;
        case 'toggle_direct_peer': return undefined;
        case 'get_direct_peers': return { peers: [] };
        case 'get_direct_session_state': return { discovering: false, advertising: false, ephemeral_group_id: '', peer_count: 0 };
        case 'pull_peer_content': {
            const peer = mockPeers.find(p => p.content_id === a?.content_id);
            const item = {
                id: String(mockId++),
                content_type: peer?.content_type ?? 'text',
                preview: peer?.preview ?? '',
                size_bytes: peer?.size_bytes ?? 0,
                created_at: Date.now() / 1000,
                file_name: peer?.file_name ?? null,
                mime_type: peer?.mime_type ?? null,
            };
            mockLocal = [item, ...mockLocal];
            return item;
        }
        case 'copy_peer_content': return undefined;
        case 'save_peer_content_as': return { saved: true, path: 'C:\\temp\\fenixhub-mock.bin' };
        case 'save_local_content_as': return { saved: true, path: 'C:\\temp\\fenixhub-local-mock.bin' };
        case 'setup_identity':
            mockIdentity = {
                device_name: a?.args?.device_name ?? 'Device',
                group_id: (a?.args?.passphrase ? `grp-${mockId++}` : mockIdentity.group_id),
                configured: true,
                device_type: a?.args?.device_type ?? 'desktop',
            };
            return mockIdentity;
        case 'update_identity': {
            const payload = a?.args || { device_name: 'Device' };
            const oldGroup = mockIdentity.group_id;
            const nextGroup = payload.passphrase ? `grp-${mockId++}` : oldGroup;
            mockIdentity = {
                device_name: payload.device_name || mockIdentity.device_name,
                group_id: nextGroup,
                configured: true,
                device_type: payload.device_type ?? mockIdentity.device_type,
            };
            return {
                identity: mockIdentity,
                group_changed: nextGroup !== oldGroup,
                requires_restart: nextGroup !== oldGroup,
            };
        }
        case 'delete_identity_only': {
            mockIdentity = {
                device_name: '',
                group_id: '',
                configured: false,
                device_type: 'desktop',
            };
            return undefined;
        }
        case 'list_identity_profiles':
            return { profiles: mockProfiles };
        case 'save_current_identity_profile': {
            const payload = a?.args || { name: '' };
            const name = payload.name.trim();
            if (name) {
                const existing = mockProfiles.find(p => p.name === name);
                if (existing) {
                    existing.device_name = mockIdentity.device_name;
                    existing.group_id = mockIdentity.group_id;
                    existing.device_type = mockIdentity.device_type;
                }
                else {
                    mockProfiles.push({
                        name,
                        device_name: mockIdentity.device_name,
                        group_id: mockIdentity.group_id,
                        device_type: mockIdentity.device_type,
                        active: false,
                    });
                }
                if (payload.make_active) {
                    mockProfiles = mockProfiles.map(profile => ({ ...profile, active: profile.name === name }));
                }
            }
            return { profiles: mockProfiles };
        }
        case 'activate_identity_profile': {
            const name = (a?.args?.name || '').trim();
            const selected = mockProfiles.find(profile => profile.name === name);
            if (selected) {
                mockProfiles = mockProfiles.map(profile => ({ ...profile, active: profile.name === name }));
                mockIdentity = {
                    device_name: selected.device_name,
                    group_id: selected.group_id,
                    configured: true,
                    device_type: selected.device_type,
                };
            }
            return {
                identity: mockIdentity,
                group_changed: false,
                requires_restart: true,
            };
        }
        case 'delete_identity_profile': {
            const name = (a?.args?.name || '').trim();
            mockProfiles = mockProfiles.filter(profile => profile.name !== name);
            if (!mockProfiles.some(profile => profile.active) && mockProfiles.length > 0) {
                mockProfiles[0].active = true;
            }
            return { profiles: mockProfiles };
        }
        case 'get_transport_hardware':
            return {
                lan: true,
                lan_ip: '192.168.1.50',
                airdrop_ready: true,
                flow: 'ble_discovery_then_wifi_direct_transfer',
                ble: { supported: true, enabled: true, permissions_ready: true, adapters: ['Mock BLE Adapter'] },
                wifi_direct: { supported: true, enabled: true, permissions_ready: true, adapters: ['Mock Wi-Fi Adapter'] },
                ble_peers: [],
                wifi_direct_peers: [],
                handoff_candidates: [],
            };
        case 'get_transport_capabilities':
            return { lan: true, airdrop_ready: false, flow: '', ble: { supported: false, enabled: false, permissions_ready: false, adapters: [] }, wifi_direct: { supported: false, enabled: false, permissions_ready: false, adapters: [] }, ble_peers: [], wifi_direct_peers: [], handoff_candidates: [] };
        case 'confirm_reset': return true;
        case 'close_settings': return undefined;
        default: return undefined;
    }
}
// ── State ─────────────────────────────────────────────────────────────────────
let identity = null;
let localContent = [];
let peerContent = [];
let publishedIds = new Set();
let onlineDevices = []; // devices with active content
let presenceDevices = new Set(); // devices seen via presence beacon
let activeTab = 'local';
let collapsed = false;
let selectedDeviceType = 'desktop';
let transportCapabilities = null;
const dragPayloadCache = new Map();
const DEVICE_TYPES = [
    { id: 'desktop', label: 'Desktop', icon: () => svg(18, '0 0 20 18', '<rect x="1" y="1" width="18" height="13" rx="2" stroke-width="1.5"/><line x1="6" y1="17" x2="14" y2="17" stroke-width="1.5"/><line x1="10" y1="14" x2="10" y2="17" stroke-width="1.5"/>') },
    { id: 'laptop', label: 'Laptop', icon: () => svg(18, '0 0 20 18', '<rect x="2" y="2" width="16" height="11" rx="1.5" stroke-width="1.5"/><path d="M0,16 Q10,14 20,16" stroke-width="1.5" fill="none"/>') },
    { id: 'phone', label: 'Android', icon: () => svg(18, '0 0 14 20', '<rect x="1" y="1" width="12" height="18" rx="3" stroke-width="1.5"/><line x1="5.5" y1="16.5" x2="8.5" y2="16.5" stroke-width="1.5"/>') },
    { id: 'tablet', label: 'Tablet', icon: () => svg(18, '0 0 16 20', '<rect x="1" y="1" width="14" height="18" rx="2.5" stroke-width="1.5"/><line x1="6" y1="16.5" x2="10" y2="16.5" stroke-width="1.5"/>') },
    { id: 'server', label: 'Servidor', icon: () => svg(18, '0 0 20 18', '<rect x="1" y="1" width="18" height="7" rx="1.5" stroke-width="1.5"/><rect x="1" y="10" width="18" height="7" rx="1.5" stroke-width="1.5"/><circle cx="4.5" cy="4.5" r="1" fill="currentColor" stroke="none"/><circle cx="4.5" cy="13.5" r="1" fill="currentColor" stroke="none"/>') },
];
const W = 820, H = 185;
const W_PILL = 280, H_PILL = 34;
// ── Init ──────────────────────────────────────────────────────────────────────
async function init() {
    document.body.classList.remove('settings-mode');
    // Block browser context menu — this is a native-style app, not a webpage
    document.addEventListener('contextmenu', e => e.preventDefault());
    // Keep hub scale fixed: disable browser/webview zoom shortcuts and gestures.
    document.addEventListener('wheel', (event) => {
        if (event.ctrlKey || event.metaKey)
            event.preventDefault();
    }, { passive: false });
    document.addEventListener('keydown', (event) => {
        if (!(event.ctrlKey || event.metaKey))
            return;
        if (event.key === '+' || event.key === '=' || event.key === '-' || event.key === '0') {
            event.preventDefault();
        }
    });
    // Settings window uses the same bundle but a different hash.
    if (window.location.hash === '#settings') {
        document.body.classList.add('settings-mode');
        await initSettings();
        return;
    }
    identity = await invoke('get_identity');
    if (!identity.configured) {
        renderSetup();
        return;
    }
    await loadContent();
    transportCapabilities = await loadTransportCapabilities().catch(() => null);
    renderHub();
    setupEventListeners();
}
function directModeSupported(transport = transportCapabilities) {
    if (!transport)
        return false;
    const bleReady = !!(transport.ble?.supported
        && transport.ble?.enabled
        && transport.ble?.permissions_ready);
    const wifiReady = !!(transport.wifi_direct?.supported
        && transport.wifi_direct?.enabled
        && transport.wifi_direct?.permissions_ready);
    return bleReady && wifiReady && !!transport.airdrop_ready;
}
// ── Settings window ───────────────────────────────────────────────────────────
async function initSettings() {
    await reloadSettingsView();
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            void invoke('close_settings');
        }
    });
}
async function reloadSettingsView(feedback) {
    identity = await invoke('get_identity');
    const [profilesPayload, transport] = await Promise.all([
        invoke('list_identity_profiles').catch(() => ({ profiles: [] })),
        loadTransportCapabilities(),
    ]);
    renderSettings(profilesPayload.profiles, transport, feedback);
}
async function loadTransportCapabilities() {
    return invoke('get_transport_hardware')
        .catch(() => invoke('get_transport_capabilities'))
        .catch(() => ({ lan: false, airdrop_ready: false, flow: '', ble: { supported: false, enabled: false, permissions_ready: false, adapters: [] }, wifi_direct: { supported: false, enabled: false, permissions_ready: false, adapters: [] }, ble_peers: [], wifi_direct_peers: [], handoff_candidates: [] }));
}
function renderSettings(profiles, transport, feedback) {
    const app = document.getElementById('app');
    const selectedType = identity?.device_type || 'desktop';
    const profileOptions = profiles.map(profile => `<option value="${escapeHtml(profile.name)}"${profile.active ? ' selected' : ''}>${escapeHtml(profile.name)}${profile.active ? ' · activo' : ''}</option>`).join('');
    app.innerHTML = `
    <div class="settings-shell">
      <header class="settings-titlebar">
        <div class="settings-titlewrap">
          <div class="hub-logo" style="color:var(--accent)">${iconHub(15)}</div>
          <span class="settings-title">Ajustes de FenixHub</span>
        </div>
        <button class="btn-icon danger settings-close" id="btn-settings-close" title="Cerrar">${iconX(11)}</button>
      </header>

      <div class="settings-body">
        ${feedback ? `<div class="settings-feedback ${feedback.tone}">${escapeHtml(feedback.message)}</div>` : ''}

        <section class="settings-section">
          <h3>Identidad</h3>
          <div class="settings-grid two-col">
            <label class="settings-field">
              <span class="settings-label">Nombre del dispositivo</span>
              <input id="settings-device-name" type="text" value="${escapeHtml(identity?.device_name || '')}" placeholder="Nombre de este dispositivo" />
            </label>
            <label class="settings-field">
              <span class="settings-label">Tipo</span>
              <select id="settings-device-type" class="settings-select">
                ${DEVICE_TYPES.map(dt => `<option value="${dt.id}"${dt.id === selectedType ? ' selected' : ''}>${dt.label}</option>`).join('')}
              </select>
            </label>
          </div>

          <div class="settings-row">
            <span class="settings-label">Grupo (ID)</span>
            <span class="settings-value mono settings-group-id" id="group-id-val">${identity?.group_id ?? '—'}</span>
            <button class="btn-secondary" id="btn-copy-gid">Copiar</button>
          </div>

          <div class="settings-actions">
            <button class="btn-secondary" id="btn-apply-identity">Guardar nombre/tipo</button>
          </div>

          <div class="settings-divider"></div>

          <div class="settings-grid one-col">
            <label class="settings-field">
              <span class="settings-label">Cambiar identidad (nuevo grupo, mantiene caché)</span>
              <input id="settings-passphrase" type="password" placeholder="Nueva passphrase del grupo" />
            </label>
          </div>
          <div class="settings-actions">
            <button class="btn-secondary" id="btn-change-group">Cambiar identidad</button>
          </div>
        </section>

        <section class="settings-section">
          <h3>Perfiles</h3>
          <div class="settings-grid two-col">
            <label class="settings-field">
              <span class="settings-label">Perfil guardado</span>
              <select id="settings-profile-select" class="settings-select" ${profiles.length === 0 ? 'disabled' : ''}>
                ${profileOptions || '<option value="">Sin perfiles</option>'}
              </select>
            </label>
            <label class="settings-field">
              <span class="settings-label">Guardar perfil actual como</span>
              <input id="settings-profile-name" type="text" placeholder="ej. trabajo" />
            </label>
          </div>
          <div class="settings-actions split">
            <button class="btn-secondary" id="btn-save-profile">Guardar perfil</button>
            <button class="btn-secondary" id="btn-activate-profile" ${profiles.length === 0 ? 'disabled' : ''}>Activar perfil</button>
            <button class="btn-danger ghost" id="btn-delete-profile" ${profiles.length === 0 ? 'disabled' : ''}>Eliminar perfil</button>
          </div>
        </section>

        <section class="settings-section">
          <h3>Transporte</h3>
          <div class="transport-grid">
            <div class="transport-item">
              <span>LAN</span>
              <span class="cap-pill ${transport.lan ? 'ok' : 'off'}">${transport.lan ? 'Disponible' : 'No disponible'}</span>
            </div>
            <div class="transport-note">IP actual: ${escapeHtml(transport.lan_ip || 'sin enlace LAN')}</div>
            <div class="transport-item">
              <span>Bluetooth LE</span>
              <span class="cap-pill ${transport.ble?.supported && transport.ble?.enabled ? 'ok' : 'off'}">${transport.ble?.supported && transport.ble?.enabled ? 'Disponible' : 'No disponible'}</span>
            </div>
            <div class="transport-note">Adaptadores BLE: ${escapeHtml((transport.ble?.adapters || []).join(', ') || 'ninguno detectado')}</div>
            <div class="transport-item">
              <span>Wi-Fi Direct</span>
              <span class="cap-pill ${transport.wifi_direct?.supported && transport.wifi_direct?.enabled ? 'ok' : 'off'}">${transport.wifi_direct?.supported && transport.wifi_direct?.enabled ? 'Disponible' : 'No disponible'}</span>
            </div>
            <div class="transport-note">Adaptadores Wi-Fi: ${escapeHtml((transport.wifi_direct?.adapters || []).join(', ') || 'ninguno detectado')}</div>
            <div class="transport-note">Flujo cercano: ${escapeHtml(transport.flow || 'ble_discovery_then_wifi_direct_transfer')}</div>
            <div class="transport-note">Modo AirDrop-like: <strong>${transport.airdrop_ready ? 'listo' : 'parcial'}</strong></div>
          </div>
        </section>

        <section class="settings-section">
          <h3>Caché</h3>
          <div class="settings-row">
            <span class="settings-label">Archivos recibidos</span>
            <span class="settings-value">FIFO 25 archivos en caché local</span>
          </div>
          <div class="settings-actions">
            <button class="btn-secondary" id="btn-clear-cache">Limpiar caché</button>
          </div>
        </section>

        <section class="settings-section danger">
          <h3>Zona de peligro</h3>
          <div class="settings-row danger-row">
            <span class="settings-label">Eliminar solo identidad y configuración (mantiene caché)</span>
          </div>
          <div class="settings-actions split">
            <button class="btn-danger ghost" id="btn-delete-identity">Eliminar identidad</button>
          </div>
          <div class="settings-row danger-row">
            <span class="settings-label">Eliminar identidad, historial y caché de este dispositivo</span>
          </div>
          <div class="settings-actions split">
            <button class="btn-danger" id="btn-reset">Eliminar todos los datos</button>
          </div>
        </section>
      </div>
    </div>
  `;
    document.getElementById('btn-settings-close').addEventListener('click', async () => {
        await invoke('close_settings');
    });
    document.getElementById('btn-copy-gid')?.addEventListener('click', () => {
        if (identity?.group_id)
            navigator.clipboard.writeText(identity.group_id);
    });
    document.getElementById('btn-apply-identity')?.addEventListener('click', async () => {
        if (!identity?.configured) {
            await reloadSettingsView({
                message: 'No hay identidad activa. Usa "Cambiar identidad" para crear una nueva.',
                tone: 'error',
            });
            return;
        }
        const deviceName = document.getElementById('settings-device-name').value.trim();
        const deviceType = document.getElementById('settings-device-type').value;
        if (!deviceName) {
            await reloadSettingsView({ message: 'El nombre del dispositivo no puede estar vacío.', tone: 'error' });
            return;
        }
        const result = await invoke('update_identity', {
            args: {
                device_name: deviceName,
                device_type: deviceType,
                passphrase: null,
            },
        });
        await reloadSettingsView({
            message: result.group_changed
                ? 'Identidad actualizada. Reinicia la app para cambiar completamente de grupo.'
                : 'Nombre y tipo actualizados al instante.',
            tone: result.group_changed ? 'warn' : 'ok',
        });
    });
    document.getElementById('btn-change-group')?.addEventListener('click', async () => {
        const passphrase = document.getElementById('settings-passphrase').value.trim();
        const deviceName = document.getElementById('settings-device-name').value.trim() || identity?.device_name || '';
        const deviceType = document.getElementById('settings-device-type').value;
        if (!passphrase) {
            await reloadSettingsView({ message: 'Introduce una passphrase para cambiar de identidad.', tone: 'error' });
            return;
        }
        // Auto-save current identity as a profile before switching groups,
        // so the user can switch back without re-entering the passphrase.
        if (identity?.configured && identity.device_name) {
            const existingProfiles = await invoke('list_identity_profiles').catch(() => ({ profiles: [] }));
            const alreadySaved = existingProfiles.profiles.some(p => p.active);
            if (!alreadySaved) {
                await invoke('save_current_identity_profile', { args: { name: identity.device_name, make_active: true } }).catch(() => { });
            }
        }
        if (!identity?.configured) {
            await invoke('setup_identity', {
                args: {
                    passphrase,
                    device_name: deviceName || 'Dispositivo',
                    device_type: deviceType,
                },
            });
            await reloadSettingsView({ message: 'Identidad creada correctamente.', tone: 'ok' });
            return;
        }
        const result = await invoke('update_identity', {
            args: {
                passphrase,
                device_name: deviceName,
                device_type: deviceType,
            },
        });
        await reloadSettingsView({
            message: result.requires_restart
                ? 'Identidad cambiada sin borrar caché. Reinicia la app para completar el cambio de grupo.'
                : 'Identidad cambiada.',
            tone: result.requires_restart ? 'warn' : 'ok',
        });
    });
    document.getElementById('btn-save-profile')?.addEventListener('click', async () => {
        const name = document.getElementById('settings-profile-name').value.trim();
        if (!name) {
            await reloadSettingsView({ message: 'Escribe un nombre de perfil.', tone: 'error' });
            return;
        }
        await invoke('save_current_identity_profile', { args: { name, make_active: false } });
        await reloadSettingsView({ message: `Perfil "${name}" guardado.`, tone: 'ok' });
    });
    document.getElementById('btn-activate-profile')?.addEventListener('click', async () => {
        const select = document.getElementById('settings-profile-select');
        const name = select?.value?.trim();
        if (!name)
            return;
        await invoke('activate_identity_profile', { args: { name } });
        await reloadSettingsView({
            message: `Perfil "${name}" activado. Reinicia la app para reinicializar discovery con total limpieza.`,
            tone: 'warn',
        });
    });
    document.getElementById('btn-delete-profile')?.addEventListener('click', async () => {
        const select = document.getElementById('settings-profile-select');
        const name = select?.value?.trim();
        if (!name)
            return;
        await invoke('delete_identity_profile', { args: { name } });
        await reloadSettingsView({ message: `Perfil "${name}" eliminado.`, tone: 'ok' });
    });
    document.getElementById('btn-clear-cache')?.addEventListener('click', async () => {
        await invoke('clear_received_cache');
        await reloadSettingsView({ message: 'Caché local limpiada.', tone: 'ok' });
    });
    document.getElementById('btn-delete-identity')?.addEventListener('click', async () => {
        await invoke('delete_identity_only');
        await reloadSettingsView({
            message: 'Identidad eliminada. La caché local se mantiene intacta.',
            tone: 'warn',
        });
    });
    document.getElementById('btn-reset')?.addEventListener('click', async () => {
        const ok = await invoke('confirm_reset');
        if (!ok)
            return;
        await invoke('reset_all_data');
        await reloadSettingsView({
            message: 'Todos los datos locales han sido eliminados.',
            tone: 'warn',
        });
    });
}
async function loadContent() {
    [localContent, peerContent] = await Promise.all([
        invoke('get_local_content'),
        invoke('get_peers'),
    ]);
    onlineDevices = [...new Set(peerContent.map(p => p.device_name))];
}
// ── Tauri events ──────────────────────────────────────────────────────────────
function setupEventListeners() {
    listen('hub-activate', () => {
        if (collapsed)
            expand();
    });
    listen('peer-content-available', ({ payload }) => {
        const ann = payload.announcement;
        // On duplicate mDNS resolves the new announcement may have a truncated preview
        // (NSD TXT records have size limits). Preserve whatever is better.
        const existing = peerContent.find(p => p.content_id === ann.content_id);
        if (existing) {
            if (existing._localSrc)
                ann._localSrc = existing._localSrc; // keep cached asset URL
            if (existing.preview.startsWith('data:image') && !ann.preview.startsWith('data:image'))
                ann.preview = existing.preview; // keep full-res thumbnail
        }
        peerContent = [...peerContent.filter(p => p.content_id !== ann.content_id), ann];
        if (!onlineDevices.includes(ann.device_name))
            onlineDevices = [...onlineDevices, ann.device_name];
        updateHeader();
        renderPeerContent();
    });
    listen('peer-content-gone', ({ payload }) => {
        const removed = peerContent.find(p => p.content_id === payload.content_id);
        const deviceName = payload.device_name || removed?.device_name || '';
        peerContent = peerContent.filter(p => p.content_id !== payload.content_id);
        if (deviceName && !peerContent.some(p => p.device_name === deviceName))
            onlineDevices = onlineDevices.filter(d => d !== deviceName);
        updateHeader();
        renderPeerContent();
    });
    listen('direct-notify-received', ({ payload }) => {
        const ann = payload.announcement;
        peerContent = [ann, ...peerContent.filter(p => p.content_id !== ann.content_id)];
        updateHeader();
        renderPeerContent();
        if (collapsed)
            expand();
        switchTab('red');
    });
    listen('peer-online', ({ payload: deviceName }) => {
        presenceDevices.add(deviceName);
        // Do NOT add to onlineDevices — a peer is only "visible" when it has active content.
        // This prevents idle/present-but-empty peers from showing in the counter or send buttons.
        updateHeader();
        renderPeerContent();
    });
    listen('peer-offline', ({ payload: deviceName }) => {
        presenceDevices.delete(deviceName);
        // Remove from onlineDevices only if they also have no active content
        if (!peerContent.some(p => p.device_name === deviceName)) {
            onlineDevices = onlineDevices.filter(d => d !== deviceName);
        }
        updateHeader();
        renderPeerContent();
    });
    listen('firewall-blocked', ({ payload }) => {
        showFirewallModal(payload);
    });
}
// ── Setup screen ──────────────────────────────────────────────────────────────
function renderSetup() {
    document.getElementById('app').innerHTML = `
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
      <p class="setup-error" id="setup-error" aria-live="polite"></p>
    </div>`;
    // Device type picker
    document.querySelectorAll('.device-type-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            selectedDeviceType = btn.dataset.dtype;
            document.querySelectorAll('.device-type-btn').forEach(b => b.classList.toggle('active', b.dataset.dtype === selectedDeviceType));
        });
    });
    const submit = async () => {
        const passphrase = document.getElementById('passphrase').value.trim();
        const deviceName = document.getElementById('device-name').value.trim();
        if (!passphrase || !deviceName)
            return;
        const btn = document.getElementById('setup-btn');
        const errorNode = document.getElementById('setup-error');
        errorNode.textContent = '';
        btn.disabled = true;
        btn.textContent = 'Activando…';
        try {
            identity = await invoke('setup_identity', {
                args: { passphrase, device_name: deviceName, device_type: selectedDeviceType },
            });
            await loadContent();
            renderHub();
            setupEventListeners();
        }
        catch (error) {
            btn.disabled = false;
            btn.innerHTML = `${iconCheckmark(11)} Activar`;
            errorNode.textContent = error instanceof Error ? error.message : String(error);
        }
    };
    document.getElementById('setup-btn').addEventListener('click', submit);
    document.addEventListener('keydown', function h(e) {
        if (e.key === 'Enter') {
            submit();
            document.removeEventListener('keydown', h);
        }
    });
}
// ── Hub ───────────────────────────────────────────────────────────────────────
function renderHub() {
    document.getElementById('app').innerHTML = `
    <div class="hub" id="hub-root">

      <!-- ── Expanded header ─────────────────────────────────────────── -->
      <header class="hub-header hub-expanded-header" id="hub-header" data-tauri-drag-region>
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

        <div class="hub-actions">
          <button class="btn-icon" id="btn-share-all" title="Compartir todo con todos">${iconBroadcast(13)}</button>
          <button class="btn-icon" id="btn-settings"  title="Ajustes">${iconGear(13)}</button>
          <button class="btn-icon" id="btn-collapse"  title="Minimizar a notch">${iconMinus(13)}</button>
          <button class="btn-icon danger" id="btn-close" title="Ocultar al tray">${iconX(12)}</button>
        </div>
      </header>

      <!-- ── Collapsed pill ──────────────────────────────────────────── -->
      <header class="hub-header hub-pill-header" id="hub-pill-header" data-tauri-drag-region>
        <div class="hub-logo pill-logo">${iconHub(16)}</div>
        <button class="pill-tab" id="pill-tab-local" data-pill-tab="local">${iconInbox(10)} Local <span class="badge" id="count-local-mini">0</span></button>
        <button class="pill-tab pill-tab-red" id="pill-tab-red" data-pill-tab="red">${iconWifi(10)} Red <span class="badge badge-red" id="count-red-mini">0</span></button>
        <button class="btn-icon danger pill-close" id="btn-pill-close" title="Cerrar al tray">${iconX(11)}</button>
      </header>

      <div class="tab-panel active" id="panel-local"></div>
      <div class="tab-panel"        id="panel-red"></div>
    </div>`;
    // Tabs
    document.getElementById('hub-tabs').addEventListener('click', (e) => {
        const btn = e.target.closest('.tab');
        if (btn?.dataset.tab)
            switchTab(btn.dataset.tab);
    });
    // Share all
    document.getElementById('btn-share-all').addEventListener('click', async () => {
        for (const item of localContent) {
            if (!publishedIds.has(item.id)) {
                await invoke('publish_content', { args: { content_id: item.id, target_device: null } });
                publishedIds.add(item.id);
            }
        }
        renderLocalContent();
    });
    // Settings
    document.getElementById('btn-settings').addEventListener('click', async () => {
        await invoke('open_settings');
    });
    // Collapse → pill
    document.getElementById('btn-collapse').addEventListener('click', () => collapse());
    // Close app (expanded)
    document.getElementById('btn-close').addEventListener('click', async () => {
        await closeApp();
    });
    // Pill tab buttons → expand + switch to tab
    document.querySelectorAll('.pill-tab').forEach(btn => {
        btn.addEventListener('click', async () => {
            const tab = btn.dataset.pillTab;
            await expand();
            switchTab(tab);
        });
    });
    // Pill close → send to tray
    document.getElementById('btn-pill-close').addEventListener('click', async () => {
        await closeApp();
    });
    // Drag-to-hub (HTML5 — browser files and text)
    const hub = document.getElementById('hub-root');
    hub.addEventListener('dragover', (e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; hub.classList.add('drag-over'); });
    hub.addEventListener('dragleave', (e) => {
        if (!e.relatedTarget?.closest('#hub-root'))
            hub.classList.remove('drag-over');
    });
    hub.addEventListener('drop', async (e) => {
        e.preventDefault();
        hub.classList.remove('drag-over');
        const files = Array.from(e.dataTransfer?.files ?? []);
        if (files.length > 0) {
            const items = await Promise.all(files.map(addBrowserFileToHub));
            localContent = [...items, ...localContent];
            updateHeader();
            if (activeTab !== 'local')
                switchTab('local');
            else
                renderLocalContent();
            return;
        }
        const text = e.dataTransfer?.getData('text/plain');
        if (text) {
            const item = await invoke('add_text_content', { text });
            localContent = [item, ...localContent];
            updateHeader();
            if (activeTab !== 'local')
                switchTab('local');
            else
                renderLocalContent();
        }
    });
    // Native drag-drop (Tauri onDragDropEvent — filesystem paths via WebView2).
    // Virtual-file sources (Outlook, OneDrive, ZIP viewer) give empty paths because
    // WebView2 exposes CF_HDROP only. Fall back to Ctrl+V for those.
    let lastNativeDropSig = '';
    let lastNativeDropAt = 0;
    function isDuplicateNativeDrop(paths) {
        const sig = paths.join('\n');
        const now = Date.now();
        if (sig.length > 0 && sig === lastNativeDropSig && (now - lastNativeDropAt) < 250) {
            return true;
        }
        lastNativeDropSig = sig;
        lastNativeDropAt = now;
        return false;
    }
    if (IS_TAURI) {
        (async () => {
            try {
                await getCurrentWindow().onDragDropEvent(async (event) => {
                    switch (event.payload.type) {
                        case 'enter':
                        case 'over':
                            hub.classList.add('drag-over');
                            break;
                        case 'leave':
                            hub.classList.remove('drag-over');
                            break;
                        case 'drop': {
                            hub.classList.remove('drag-over');
                            const paths = (event.payload.paths ?? []).filter((p) => typeof p === 'string' && p.length > 0);
                            if (paths.length > 0) {
                                if (!isDuplicateNativeDrop(paths)) {
                                    await commitDroppedItems(paths.map(p => addFileByPathToHub(p)));
                                }
                            }
                            else {
                                // Outlook attachments, unsynced OneDrive files, ZIP entries, etc.:
                                // WebView2 has no path for them. Suggest Ctrl+V.
                                showDragFallbackHint();
                            }
                            break;
                        }
                    }
                });
            }
            catch {
                // HTML5 drop handler above covers the remaining cases.
            }
            try {
                await listen('fenix://drag-received', async ({ payload }) => {
                    const paths = (payload?.paths ?? []).filter((p) => typeof p === 'string' && p.length > 0);
                    if (paths.length > 0) {
                        // If both WebView2 and native IDropTarget paths arrive, keep one insert.
                        if (!isDuplicateNativeDrop(paths)) {
                            await commitDroppedItems(paths.map(p => addFileByPathToHub(p)));
                        }
                    }
                    else {
                        showDragFallbackHint();
                    }
                });
            }
            catch {
                // onDragDropEvent remains available if custom native listener can't attach.
            }
        })();
    }
    async function addFileByPathToHub(path) {
        return invoke('add_file_by_path', { path });
    }
    async function commitDroppedItems(tasks) {
        if (tasks.length === 0)
            return;
        const settled = await Promise.allSettled(tasks);
        const items = settled
            .filter((r) => r.status === 'fulfilled')
            .map(r => r.value);
        if (items.length > 0) {
            localContent = [...items, ...localContent];
            updateHeader();
            if (activeTab !== 'local')
                switchTab('local');
            else
                renderLocalContent();
        }
        const firstError = settled.find((r) => r.status === 'rejected');
        if (firstError) {
            const msg = firstError.reason instanceof Error ? firstError.reason.message : String(firstError.reason);
            showToast(msg);
        }
    }
    // Ctrl+V → add to hub
    document.addEventListener('paste', async (e) => {
        const cd = e.clipboardData;
        if (!cd)
            return;
        // Image from clipboard (screenshot, copied image)
        const imgItem = Array.from(cd.items).find(i => i.type.startsWith('image/'));
        if (imgItem) {
            const blob = imgItem.getAsFile();
            if (!blob)
                return;
            const file = new File([blob], `clipboard-${Date.now()}.png`, { type: blob.type || 'image/png' });
            const ci = await addBrowserFileToHub(file);
            localContent = [ci, ...localContent];
            updateHeader();
            if (activeTab !== 'local')
                switchTab('local');
            else
                renderLocalContent();
            return;
        }
        // Plain text
        const text = cd.getData('text/plain').trim();
        if (text) {
            const ci = await invoke('add_text_content', { text });
            localContent = [ci, ...localContent];
            updateHeader();
            if (activeTab !== 'local')
                switchTab('local');
            else
                renderLocalContent();
        }
    });
    updateHeader();
    renderLocalContent();
    renderPeerContent();
}
async function collapse() {
    collapsed = true;
    document.getElementById('hub-root').classList.add('collapsed');
    document.getElementById('btn-collapse').innerHTML = iconChevronDown(13);
    document.getElementById('btn-collapse').title = 'Mostrar FenixHub';
    // Resize window FIRST, then let CSS transition finish
    await setWindowSize(W_PILL, H_PILL);
}
async function expand() {
    collapsed = false;
    // Grow the window before revealing content so layout doesn't flash
    await setWindowSize(W, H);
    document.getElementById('hub-root').classList.remove('collapsed');
    document.getElementById('btn-collapse').innerHTML = iconMinus(13);
    document.getElementById('btn-collapse').title = 'Minimizar a notch';
    switchTab(activeTab);
}
function renderTab(tab) {
    if (tab === 'local') {
        renderLocalContent();
    }
    else {
        renderPeerContent();
    }
}
function switchTab(tab) {
    activeTab = tab;
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === `panel-${tab}`));
    renderTab(tab);
}
function updateHeader() {
    const lc = document.getElementById('count-local');
    const rc = document.getElementById('count-red');
    const lcMini = document.getElementById('count-local-mini');
    const rcMini = document.getElementById('count-red-mini');
    if (lc)
        lc.textContent = String(localContent.length);
    if (rc)
        rc.textContent = String(peerContent.length);
    if (lcMini)
        lcMini.textContent = String(localContent.length);
    if (rcMini)
        rcMini.textContent = String(peerContent.length);
    const dot = document.getElementById('status-dot');
    const txt = document.getElementById('status-text');
    if (!dot || !txt)
        return;
    if (onlineDevices.length > 0) {
        dot.className = 'status-dot online';
        txt.textContent = `${onlineDevices.length} disp.`;
    }
    else {
        dot.className = 'status-dot scanning';
        txt.textContent = 'Buscando…';
    }
}
// ── Drag-to-scroll ────────────────────────────────────────────────────────────
function attachDragScroll(el) {
    let isDown = false;
    let startX = 0;
    let scrollLeft = 0;
    el.addEventListener('mousedown', (e) => {
        if (e.target.closest('button, a, input'))
            return;
        isDown = true;
        el.style.cursor = 'grabbing';
        startX = e.pageX - el.offsetLeft;
        scrollLeft = el.scrollLeft;
        e.preventDefault();
    });
    el.addEventListener('mouseleave', () => { isDown = false; el.style.cursor = ''; });
    el.addEventListener('mouseup', () => { isDown = false; el.style.cursor = ''; });
    el.addEventListener('mousemove', (e) => {
        if (!isDown)
            return;
        const x = e.pageX - el.offsetLeft;
        const walk = (x - startX) * 1.4;
        el.scrollLeft = scrollLeft - walk;
    });
}
// ── Local panel ───────────────────────────────────────────────────────────────
function renderLocalContent() {
    const container = document.getElementById('panel-local');
    const canUseDirectMode = directModeSupported();
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
            ? `${canUseDirectMode
                ? `<button class="btn-direct-mode" data-id="${item.id}" data-action="direct-mode" title="Modo directo AirDrop">${iconWifi(9)} Directo</button>`
                : ''}
          <button class="btn-stop" data-id="${item.id}" data-action="stop">■ Parar</button>`
            : [
                `<button class="btn-broadcast" data-id="${item.id}" data-action="broadcast">${iconBroadcast(9)} Todos</button>`,
                ...onlineDevices.map(d => `<button class="btn-direct" data-id="${item.id}" data-action="direct" data-device="${escapeHtml(d)}">${iconDevice(9)} ${escapeHtml(d)}</button>`)
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
            const el = btn;
            const id = el.dataset.id;
            if (el.dataset.action === 'stop') {
                await invoke('stop_server');
                publishedIds.clear();
            }
            else if (el.dataset.action === 'broadcast') {
                await invoke('publish_content', { args: { content_id: id, target_device: null } });
                publishedIds.add(id);
            }
            else if (el.dataset.action === 'direct') {
                await invoke('publish_content', { args: { content_id: id, target_device: el.dataset.device } });
                publishedIds.add(id);
            }
            else if (el.dataset.action === 'direct-mode') {
                if (!directModeSupported()) {
                    showToast('Modo Directo no disponible en este equipo.');
                    return;
                }
                openDirectModeModal(id);
            }
            renderLocalContent();
        });
    });
    container.querySelectorAll('.btn-delete').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.dataset.id;
            await invoke('remove_content', { id });
            publishedIds.delete(id);
            localContent = localContent.filter(i => i.id !== id);
            updateHeader();
            renderLocalContent();
        });
    });
    // Click on card body/thumb/caption → copy to local clipboard
    container.querySelectorAll('.card-body, .card-thumb, .card-image-caption').forEach(el => {
        el.style.cursor = 'pointer';
        el.addEventListener('click', async () => {
            const card = el.closest('.content-card');
            const item = localContent.find(i => i.id === card?.dataset.id);
            if (!item)
                return;
            if (IS_TAURI) {
                await invoke('write_local_to_clipboard', { id: item.id }).catch(() => { });
            }
            else {
                await navigator.clipboard.writeText(item.preview).catch(() => { });
            }
            // Brief visual feedback
            card.style.outline = '1px solid var(--accent)';
            setTimeout(() => card.style.outline = '', 600);
        });
    });
    container.querySelectorAll('.content-card').forEach(card => {
        const id = card.dataset.id;
        if (id) {
            card.addEventListener('pointerdown', () => {
                void warmupDragPayload(id);
            }, { passive: true });
            card.addEventListener('mouseenter', () => {
                void warmupDragPayload(id);
            });
        }
        // dragstart MUST be synchronous — any await empties dataTransfer before the OS reads it
        card.addEventListener('dragstart', (event) => {
            const id = card.dataset.id;
            const item = localContent.find(entry => entry.id === id);
            if (!id || !item)
                return;
            const dataTransfer = event.dataTransfer;
            if (!dataTransfer)
                return;
            dataTransfer.effectAllowed = 'copy';
            if (item.content_type === 'text') {
                const text = item.data_text || item.preview;
                dataTransfer.setData('text/plain', text);
                // Silently copy to clipboard so Ctrl+V always works as fallback
                if (IS_TAURI)
                    invoke('write_local_to_clipboard', { id }).catch(() => { });
            }
            else if (item.transfer_path) {
                const fwdPath = item.transfer_path.replace(/\\/g, '/');
                const uri = fwdPath.startsWith('/') ? `file://${fwdPath}` : `file:///${fwdPath}`;
                dataTransfer.setData('text/plain', item.transfer_path);
                dataTransfer.setData('text/uri-list', uri);
            }
            else {
                dataTransfer.setData('text/plain', item.file_name || item.preview);
            }
        });
        // If WebView2 couldn't complete the native drop (forbidden cursor), show hint
        card.addEventListener('dragend', (event) => {
            if (event.dataTransfer?.dropEffect === 'none') {
                showDragFallbackHint();
            }
        });
    });
    attachDragScroll(document.getElementById('panel-local'));
}
let directModalContentId = null;
let directModalPeerRefresh = null;
async function openDirectModeModal(contentId) {
    if (!directModeSupported()) {
        showToast('Modo Directo no disponible en este equipo.');
        return;
    }
    directModalContentId = contentId;
    // Start sender mode discovery
    await invoke('start_direct_mode_sender');
    // Show modal
    const overlay = createModalOverlay();
    document.body.appendChild(overlay);
    const modal = document.createElement('div');
    modal.className = 'direct-modal';
    modal.innerHTML = `
    <div class="direct-modal-header">
      <span>${iconWifi(18)} Modo Directo</span>
      <button class="modal-close" id="direct-modal-close">${iconX(14)}</button>
    </div>
    <div class="direct-modal-body">
      <p class="direct-hint">Buscando dispositivos cercanos...</p>
      <div class="direct-peers-list" id="direct-peers-list">
        <div class="direct-empty">Iniciando...</div>
      </div>
    </div>
    <div class="direct-modal-footer">
      <button class="btn-cancel" id="direct-cancel">Cancelar</button>
      <button class="btn-accept" id="direct-accept" disabled>Enviar a 0</button>
    </div>`;
    overlay.querySelector('.modal-backdrop').appendChild(modal);
    document.getElementById('direct-modal-close').addEventListener('click', closeDirectModal);
    document.getElementById('direct-cancel').addEventListener('click', closeDirectModal);
    document.getElementById('direct-accept').addEventListener('click', () => {
        void acceptDirectModal();
    });
    // Start refreshing peers list
    directModalPeerRefresh = setInterval(() => {
        void refreshDirectPeers();
    }, 1500);
    void refreshDirectPeers();
}
async function refreshDirectPeers() {
    try {
        const resp = await invoke('get_direct_peers');
        const list = document.getElementById('direct-peers-list');
        if (!list)
            return;
        if (resp.peers.length === 0) {
            list.innerHTML = `<div class="direct-empty">No hay dispositivos cerca.<br> Asegúrate de que el otro dispositivo también tenga FenixHub abierto en modo directo.</div>`;
            return;
        }
        list.innerHTML = resp.peers.map(peer => {
            const rssiBars = signalBars(peer.rssi);
            return `
        <div class="direct-peer-item" data-peer-id="${escapeHtml(peer.device_id)}">
          <div class="direct-peer-info">
            <div class="direct-peer-name">${escapeHtml(peer.device_name)}</div>
            <div class="direct-peer-id">Grupo: ${escapeHtml(peer.ephemeral_group_id)}</div>
          </div>
          <div class="direct-peer-signal">${rssiBars}</div>
          <div class="direct-peer-check">
            <button class="peer-select-btn" data-peer-id="${escapeHtml(peer.device_id)}">
              ${peer.selected ? iconCheckmark(16) : iconCircle(16)}
            </button>
          </div>
        </div>`;
        }).join('');
        list.querySelectorAll('.peer-select-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const peerId = btn.dataset.peerId;
                toggleDirectPeer(peerId);
                void refreshDirectPeers();
            });
        });
        const selectedCount = resp.peers.filter(p => p.selected).length;
        const acceptBtn = document.getElementById('direct-accept');
        acceptBtn.textContent = `Enviar a ${selectedCount}`;
        acceptBtn.disabled = selectedCount === 0;
        // Update header
        const hint = document.querySelector('.direct-hint');
        if (hint) {
            hint.textContent = resp.peers.length > 0
                ? `${resp.peers.length} dispositivo(s) encontrado(s)`
                : 'Buscando dispositivos...';
        }
    }
    catch (e) {
        console.error('Failed to refresh direct peers', e);
    }
}
let selectedDirectPeerIds = new Set();
async function toggleDirectPeer(peerId) {
    if (selectedDirectPeerIds.has(peerId)) {
        selectedDirectPeerIds.delete(peerId);
    }
    else {
        selectedDirectPeerIds.add(peerId);
    }
    await invoke('toggle_direct_peer', { peer_id: peerId });
}
async function acceptDirectModal() {
    if (selectedDirectPeerIds.size === 0 || !directModalContentId)
        return;
    const selected = Array.from(selectedDirectPeerIds);
    await invoke('accept_direct_peers', {
        args: {
            selected_peer_ids: selected,
            content_id: directModalContentId,
        },
    });
    showToast(`Enviando a ${selected.length} dispositivo(s)...`);
    closeDirectModal();
}
function closeDirectModal() {
    if (directModalPeerRefresh) {
        clearInterval(directModalPeerRefresh);
        directModalPeerRefresh = null;
    }
    selectedDirectPeerIds.clear();
    invoke('cancel_direct_mode').catch(() => { });
    const overlay = document.querySelector('.modal-overlay');
    if (overlay)
        overlay.remove();
    renderLocalContent();
}
function createModalOverlay() {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `<div class="modal-backdrop"></div>`;
    overlay.querySelector('.modal-backdrop').addEventListener('click', (e) => {
        if (e.target === overlay.querySelector('.modal-backdrop'))
            closeDirectModal();
    });
    return overlay;
}
// ── Firewall modal (Desktop) ───────────────────────────────────────────────
function showFirewallModal(status) {
    // Only show once per session
    if (document.getElementById('firewall-modal'))
        return;
    const isWindowsFirewall = status.firewall_type === 'windows_defender';
    const manualCmd = isWindowsFirewall
        ? `netsh advfirewall firewall add rule name="FenixHub TCP ${status.port}" dir=in action=allow protocol=TCP localport=${status.port}`
        : status.firewall_type === 'ufw'
            ? `sudo ufw allow ${status.port}/tcp`
            : `sudo iptables -I INPUT -p tcp --dport ${status.port} -j ACCEPT`;
    const autoPromptHint = isWindowsFirewall
        ? 'pedirá confirmación de administrador (UAC)'
        : 'pedirá contraseña de root';
    const terminalHint = isWindowsFirewall
        ? 'PowerShell o CMD como administrador'
        : 'una terminal';
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.id = 'firewall-modal-overlay';
    overlay.innerHTML = `<div class="modal-backdrop"></div>`;
    const modal = document.createElement('div');
    modal.className = 'direct-modal';
    modal.id = 'firewall-modal';
    modal.innerHTML = `
    <div class="direct-modal-header">
      <span>⚠ Firewall bloqueando conexiones</span>
      <button class="modal-close" id="fw-modal-close">${iconX(14)}</button>
    </div>
    <div class="direct-modal-body">
      <p style="margin:0 0 8px">El firewall (<b>${status.firewall_type}</b>) puede bloquear que otros dispositivos se conecten al puerto <b>${status.port}/tcp</b>.</p>
      <p style="margin:0 0 8px">Pulsa <b>Añadir regla</b> para añadirla automáticamente (${autoPromptHint}), o copia el comando y ejecútalo en ${terminalHint}:</p>
      <code class="fw-cmd" id="fw-cmd-text" style="display:block;background:rgba(255,255,255,0.07);padding:8px;border-radius:6px;font-size:12px;word-break:break-all;cursor:pointer" title="Copiar">${manualCmd}</code>
      <p id="fw-result-msg" style="margin:8px 0 0;font-size:12px;min-height:16px"></p>
    </div>
    <div class="direct-modal-footer">
      <button class="btn-cancel" id="fw-dismiss">Ignorar</button>
      <button class="btn-cancel" id="fw-copy-cmd">Copiar comando</button>
      <button class="btn-accept" id="fw-add-rule">Añadir regla</button>
    </div>`;
    overlay.querySelector('.modal-backdrop').appendChild(modal);
    document.body.appendChild(overlay);
    const closeModal = () => overlay.remove();
    document.getElementById('fw-modal-close').addEventListener('click', closeModal);
    document.getElementById('fw-dismiss').addEventListener('click', closeModal);
    document.getElementById('fw-cmd-text').addEventListener('click', () => {
        void navigator.clipboard.writeText(manualCmd).then(() => {
            const msg = document.getElementById('fw-result-msg');
            msg.textContent = 'Comando copiado al portapapeles.';
        });
    });
    document.getElementById('fw-copy-cmd').addEventListener('click', () => {
        void navigator.clipboard.writeText(manualCmd).then(() => {
            const msg = document.getElementById('fw-result-msg');
            msg.textContent = 'Comando copiado al portapapeles.';
        });
    });
    document.getElementById('fw-add-rule').addEventListener('click', () => {
        const btn = document.getElementById('fw-add-rule');
        const msg = document.getElementById('fw-result-msg');
        btn.disabled = true;
        btn.textContent = 'Aplicando...';
        invoke('request_firewall_allow', { port: status.port })
            .then((added) => {
            if (added) {
                msg.style.color = '#69ff47';
                msg.textContent = 'Regla añadida correctamente. Los dispositivos ya pueden conectarse.';
                btn.textContent = 'Listo';
                setTimeout(closeModal, 2500);
            }
            else {
                msg.style.color = '#ffa040';
                msg.textContent = 'Cancelado. Puedes añadir la regla manualmente con el comando de arriba.';
                btn.disabled = false;
                btn.textContent = 'Añadir regla';
            }
        })
            .catch((err) => {
            msg.style.color = '#ff5555';
            msg.textContent = String(err);
            btn.disabled = false;
            btn.textContent = 'Añadir regla';
        });
    });
}
function signalBars(rssi) {
    if (rssi >= -60)
        return '●●●';
    if (rssi >= -75)
        return '●●○';
    if (rssi >= -90)
        return '●○○';
    return '○○○';
}
// ── Red panel ─────────────────────────────────────────────────────────────────
function renderPeerContent() {
    const container = document.getElementById('panel-red');
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
        // _localSrc: full-res asset:// URL set after local cache; preview: mDNS base64 thumbnail
        const thumbSrc = item._localSrc || (item.preview.startsWith('data:image') ? item.preview : null);
        const topContent = thumbSrc
            ? `<img class="card-thumb" src="${thumbSrc}" />`
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
    container.querySelectorAll('[data-peer-action]').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.dataset.id;
            const action = btn.dataset.peerAction;
            const previousMarkup = btn.innerHTML;
            btn.disabled = true;
            try {
                if (action === 'copy') {
                    btn.textContent = 'Copiando…';
                    const result = await invoke('copy_peer_content', peerCommandArgs(id));
                    if (result?.cached_path) {
                        // Persist asset URL in peerContent so re-renders keep the thumbnail
                        const assetSrc = convertFileSrc(result.cached_path);
                        peerContent = peerContent.map(p => p.content_id === id ? { ...p, _localSrc: assetSrc } : p);
                        renderPeerContent();
                    }
                    flashPeerAction(btn, 'Copiado');
                    return;
                }
                btn.textContent = 'Guardando…';
                const result = await invoke('save_peer_content_as', peerCommandArgs(id));
                if (result.saved) {
                    flashPeerAction(btn, 'Guardado');
                }
                else {
                    btn.disabled = false;
                    btn.innerHTML = previousMarkup;
                }
            }
            catch (e) {
                console.error(`peer action ${action} failed:`, e);
                btn.disabled = false;
                btn.textContent = 'Error';
                window.setTimeout(() => {
                    if (!btn.isConnected)
                        return;
                    btn.innerHTML = previousMarkup;
                    btn.disabled = false;
                }, 1400);
            }
        });
    });
    attachDragScroll(document.getElementById('panel-red'));
}
function flashPeerAction(button, label) {
    button.textContent = label;
    window.setTimeout(() => {
        if (!button.isConnected)
            return;
        renderPeerContent();
    }, 900);
}
// ── Icons ─────────────────────────────────────────────────────────────────────
const svg = (s, vb, path, extra = 'stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"') => `<svg width="${s}" height="${s}" viewBox="${vb}" fill="none" ${extra}>${path}</svg>`;
const LOGO_SRC = './logo-mark.png';
function iconHub(s) {
    return `<img src="${LOGO_SRC}" alt="FenixHub" width="${s}" height="${s}" style="display:block;object-fit:contain;" />`;
}
function deviceTypeIcon(type, s) {
    switch (type) {
        case 'desktop':
            return svg(s, '0 0 20 18', '<rect x="1" y="1" width="18" height="13" rx="2" stroke-width="1.5"/><line x1="6" y1="17" x2="14" y2="17" stroke-width="1.5"/><line x1="10" y1="14" x2="10" y2="17" stroke-width="1.5"/>');
        case 'laptop':
            return svg(s, '0 0 20 18', '<rect x="2" y="2" width="16" height="11" rx="1.5" stroke-width="1.5"/><path d="M0,16 Q10,14 20,16" stroke-width="1.5" fill="none"/>');
        case 'phone':
            return svg(s, '0 0 14 20', '<rect x="1" y="1" width="12" height="18" rx="3" stroke-width="1.5"/><line x1="5.5" y1="16.5" x2="8.5" y2="16.5" stroke-width="1.5"/>');
        case 'tablet':
            return svg(s, '0 0 16 20', '<rect x="1" y="1" width="14" height="18" rx="2.5" stroke-width="1.5"/><line x1="6" y1="16.5" x2="10" y2="16.5" stroke-width="1.5"/>');
        case 'server':
            return svg(s, '0 0 20 18', '<rect x="1" y="1" width="18" height="7" rx="1.5" stroke-width="1.5"/><rect x="1" y="10" width="18" height="7" rx="1.5" stroke-width="1.5"/><circle cx="4.5" cy="4.5" r="1" fill="currentColor" stroke="none"/><circle cx="4.5" cy="13.5" r="1" fill="currentColor" stroke="none"/>');
        default:
            return iconDevice(s);
    }
}
function iconCheckmark(s) {
    return svg(s, '0 0 16 16', '<polyline points="2.5,8 6.5,12 13.5,4" stroke-width="2.2"/>');
}
function iconCircle(s) {
    return svg(s, '0 0 16 16', '<circle cx="8" cy="8" r="5.5" stroke-width="1.8"/>');
}
function iconInbox(s) {
    return svg(s, '0 0 16 16', '<rect x="1.5" y="1.5" width="13" height="13" rx="2" stroke-width="1.7"/><polyline points="1.5,10 4.5,10 5.5,12.5 10.5,12.5 11.5,10 14.5,10" stroke-width="1.7"/>');
}
function iconWifi(s) {
    return svg(s, '0 0 16 16', '<path d="M1.5,6 Q8,1 14.5,6" stroke-width="1.7"/><path d="M3.5,9 Q8,5.5 12.5,9" stroke-width="1.7"/><path d="M5.5,12 Q8,10 10.5,12" stroke-width="1.7"/><circle cx="8" cy="14" r="0.8" fill="currentColor" stroke="none"/>');
}
function iconBroadcast(s) {
    return svg(s, '0 0 16 16', '<circle cx="8" cy="8" r="2" stroke-width="1.8"/><path d="M4,4 Q8,0.5 12,4" stroke-width="1.8"/><path d="M2.5,2.5 Q8,-1.5 13.5,2.5" stroke-width="1.8"/><path d="M4,12 Q8,15.5 12,12" stroke-width="1.8"/><path d="M2.5,13.5 Q8,17.5 13.5,13.5" stroke-width="1.8"/>');
}
function iconX(s) {
    return svg(s, '0 0 14 14', '<line x1="2" y1="2" x2="12" y2="12" stroke-width="2"/><line x1="12" y1="2" x2="2" y2="12" stroke-width="2"/>');
}
function iconCopy(s) {
    return svg(s, '0 0 16 16', '<rect x="5" y="3" width="8" height="10" rx="1.6" stroke-width="1.6"/><path d="M3.5,11.5 H3 a1.5,1.5 0 0 1 -1.5,-1.5 V4.5 A1.5,1.5 0 0 1 3,3 h5" stroke-width="1.6"/>');
}
function iconSave(s) {
    return svg(s, '0 0 16 16', '<path d="M3,2.5 H11.5 L13.5,4.5 V13.5 H2.5 V3 A0.5,0.5 0 0 1 3,2.5 Z" stroke-width="1.6"/><rect x="5" y="9" width="6" height="3.5" rx="0.8" stroke-width="1.4"/><path d="M5,2.8 V6.3 H10.5 V2.8" stroke-width="1.4"/>');
}
function iconDevice(s) {
    return svg(s, '0 0 14 14', '<rect x="1" y="2" width="12" height="9" rx="1.5" stroke-width="1.6"/><line x1="4" y1="13" x2="10" y2="13" stroke-width="1.6"/>');
}
function iconMinus(s) {
    return svg(s, '0 0 14 14', '<line x1="2" y1="7" x2="12" y2="7" stroke-width="2"/>');
}
function iconGear(s) {
    return svg(s, '0 0 16 16', '<circle cx="8" cy="8" r="2.5" stroke-width="1.5" fill="none"/><path d="M8 1v2M8 13v2M1 8h2M13 8h2M3.1 3.1l1.4 1.4M11.5 11.5l1.4 1.4M3.1 12.9l1.4-1.4M11.5 4.5l1.4-1.4" stroke-width="1.5"/>');
}
function iconChevronDown(s) {
    return svg(s, '0 0 14 14', '<polyline points="2,4.5 7,9.5 12,4.5" stroke-width="2"/>');
}
function iconLock(s) {
    return svg(s, '0 0 14 14', '<rect x="2" y="6" width="10" height="7" rx="1.5" stroke-width="1.6"/><path d="M4,6 V4 a4,4 0 0 1 6,0 V6" stroke-width="1.6" fill="none"/>');
}
function iconInboxLarge() {
    return svg(36, '0 0 24 24', '<rect x="2" y="2" width="20" height="20" rx="3" stroke-width="1.2"/><polyline points="2,15 7,15 8.5,19 15.5,19 17,15 22,15" stroke-width="1.2"/>');
}
function iconWifiLarge() {
    return svg(36, '0 0 24 24', '<path d="M1.5,9 Q12,1 22.5,9" stroke-width="1.2"/><path d="M4.5,13 Q12,7.5 19.5,13" stroke-width="1.2"/><path d="M7.5,17 Q12,13.5 16.5,17" stroke-width="1.2"/><circle cx="12" cy="20.5" r="1.2" fill="currentColor" stroke="none"/>');
}
function typeIcon(type) {
    if (type === 'text')
        return svg(14, '0 0 16 16', '<line x1="3" y1="5" x2="13" y2="5" stroke-width="1.8"/><line x1="3" y1="8" x2="13" y2="8" stroke-width="1.8"/><line x1="3" y1="11" x2="9" y2="11" stroke-width="1.8"/>');
    if (type === 'image')
        return svg(14, '0 0 16 16', '<rect x="2" y="3" width="12" height="10" rx="1.5" stroke-width="1.8"/><circle cx="6" cy="6.5" r="1" stroke-width="1.8"/><polyline points="2,11 5.5,8 7.5,10 10,7.5 14,11" stroke-width="1.8"/>');
    return svg(14, '0 0 16 16', '<path d="M10,2 H4 a1.5,1.5 0 0 0 -1.5,1.5 v9 a1.5,1.5 0 0 0 1.5,1.5 h8 a1.5,1.5 0 0 0 1.5,-1.5 V5.5 Z" stroke-width="1.8"/><polyline points="10,2 10,5.5 13.5,5.5" stroke-width="1.8"/>');
}
async function warmupDragPayload(id) {
    if (!IS_TAURI || dragPayloadCache.has(id))
        return;
    try {
        const payload = await invoke('prepare_local_drag', { id });
        dragPayloadCache.set(id, payload);
    }
    catch {
        // Drag has a sync fallback path; cache warm-up is best-effort.
    }
}
// ── Helpers ───────────────────────────────────────────────────────────────────
function escapeHtml(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function humanSize(b) {
    if (b < 1024)
        return `${b} B`;
    if (b < 1048576)
        return `${(b / 1024).toFixed(1)} KB`;
    return `${(b / 1048576).toFixed(1)} MB`;
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
const WARN_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB — mostrar aviso
const MAX_SIZE_BYTES = 500 * 1024 * 1024; // 500 MB — límite práctico (cifrado en RAM)
async function addBrowserFileToHub(file) {
    if (file.size > MAX_SIZE_BYTES) {
        throw new Error(`El archivo "${file.name}" supera el límite de 500 MB para transferencia cifrada en RAM.`);
    }
    if (file.size > WARN_SIZE_BYTES) {
        showSizeWarning(file.name, file.size);
    }
    // Tauri exposes the real filesystem path on File objects from drag & drop.
    // Use it to let Rust read the bytes directly — no base64, no RAM double.
    const nativePath = IS_TAURI ? file.path : undefined;
    if (nativePath) {
        return invoke('add_file_by_path', { path: nativePath });
    }
    // Fallback: clipboard images and browser-only mode use base64
    const bytesBase64 = await fileToBase64(file);
    const preview = file.type.startsWith('image/') ? await imageFileToPreview(file) : undefined;
    return invoke('add_binary_content', {
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
    if (existing)
        return;
    const el = document.createElement('div');
    el.id = 'drag-hint';
    el.className = 'size-warn';
    el.innerHTML = `Ya en portapapeles — usa <kbd>Ctrl+V</kbd> para pegar &nbsp;<button onclick="this.parentElement.remove()">×</button>`;
    document.getElementById('panel-local')?.prepend(el);
    setTimeout(() => el.remove(), 4000);
}
function showSizeWarning(name, size) {
    const existing = document.getElementById('size-warn');
    if (existing)
        existing.remove();
    const el = document.createElement('div');
    el.id = 'size-warn';
    el.className = 'size-warn';
    el.innerHTML = `⚠ <strong>${escapeHtml(name)}</strong> (${humanSize(size)}) — archivo grande, puede tardar. <button onclick="this.parentElement.remove()">×</button>`;
    document.getElementById('panel-local')?.prepend(el);
    setTimeout(() => el.remove(), 8000);
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
// ── Boot ──────────────────────────────────────────────────────────────────────
if (IS_ANDROID) {
    import('./android').then(m => m.initAndroid());
}
else {
    init();
}
