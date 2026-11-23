window._aiCallbacks = {};
window._apiTestCallbacks = {};

window.AppState = {
  currentPage: 'home',
  historyStack: [],
  catalog: [],
  permissions: [],
  telemetry: {},
  aiSnapshot: {},
  synced: {
    isAdmin: false,
    profile: { displayName: 'User', email: '', planTier: 'free', themeMode: 'dark', accentColor: 'cyan', totalCommands: 0, totalAiQueries: 0 },
    logs: [],
    automations: [],
    myPayments: [],
    adminUsers: [],
    adminPayments: []
  },
  installedApps: [],
  safFiles: [],
  safTreeUri: '',
  chatMessages: [
    { role: 'ai', text: 'স্বাগতম! বাংলা বা ইংরেজিতে যেকোনো নির্দেশ দিন (যেমন: "স্ক্রিনের আলো ৫০% করো", "Wi-Fi Settings খোলো", "YouTube চালু করো")।' }
  ],
  voiceHistory: [],
  voiceLang: 'bn-BD',
  voiceEnabled: true,
  voiceConfirm: true,
  fontScale: 1,
  controlFilter: 'All',
  controlSearch: '',
  adminUnlocked: false,
  selectedPlan: { id: 'pro_monthly', name: 'Pro Monthly', price: 299 },
  selectedPayMethod: 'bkash'
};

const PAGES = [
  { id: 'welcome', label: '1. Welcome Page' },
  { id: 'home', label: '2. Home Dashboard' },
  { id: 'ai_assistant', label: '3. AI Assistant' },
  { id: 'ai_manager', label: '4. AI Manager (4 AIs)' },
  { id: 'api_manager', label: '5. API Manager Vault' },
  { id: 'phone_control', label: '6. Phone Control (100)' },
  { id: 'display_control', label: '7. Display Control' },
  { id: 'keyboard_control', label: '8. Keyboard Control' },
  { id: 'app_control', label: '9. App Control' },
  { id: 'sound_control', label: '10. Sound Control' },
  { id: 'network_control', label: '11. Network Control' },
  { id: 'security_center', label: '12. Security Center' },
  { id: 'permission_center', label: '13. Permission Center' },
  { id: 'file_manager', label: '14. File Manager' },
  { id: 'battery_manager', label: '15. Battery & Performance' },
  { id: 'system_manager', label: '16. System Manager' },
  { id: 'automation_manager', label: '17. Automation Manager' },
  { id: 'floating_settings', label: '18. Floating Controller' },
  { id: 'voice_assistant', label: '19. Voice Assistant' },
  { id: 'activity_history', label: '20. Activity History' },
  { id: 'app_settings', label: '21. App Settings & Theme' },
  { id: 'about', label: '22. About & Limitations' },
  { id: 'analytics', label: '★ User Analytics' },
  { id: 'subscription', label: '👑 Premium & Play Store' },
  { id: 'code_studio', label: '⚡ AI Code & Web Studio' },
  { id: 'custom_assistants', label: '🤖 Custom AI & Files' },
  { id: 'admin_panel', label: '👑 Admin Panel', adminOnly: true }
];

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function showToast(msg) {
  const el = document.getElementById('toastBanner');
  if (!el) return;
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(() => el.classList.add('hidden'), 3000);
}

function toggleDrawer(forceOpen) {
  const drawer = document.getElementById('navDrawer');
  const backdrop = document.getElementById('drawerBackdrop');
  const open = forceOpen !== undefined ? forceOpen : !drawer.classList.contains('open');
  drawer.classList.toggle('open', open);
  backdrop.classList.toggle('hidden', !open);
}

function navigateTo(pageId, pushHistory = true) {
  if (pageId === 'admin_panel' && !window.AppState.synced.isAdmin) {
    showToast('Admin access is restricted to verified admin account.');
    return;
  }
  if (pushHistory && window.AppState.currentPage && window.AppState.currentPage !== pageId) {
    window.AppState.historyStack.push(window.AppState.currentPage);
  }
  window.AppState.currentPage = pageId;
  toggleDrawer(false);
  refreshNativeSnapshot();
  renderNavigation();
  renderCurrentPage();
  window.scrollTo(0, 0);
}

window.handleNativeBack = function () {
  const drawer = document.getElementById('navDrawer');
  if (drawer && drawer.classList.contains('open')) {
    toggleDrawer(false);
    return true;
  }
  if (window.AppState.historyStack.length > 0) {
    const prev = window.AppState.historyStack.pop();
    navigateTo(prev, false);
    return true;
  }
  if (window.AppState.currentPage !== 'home') {
    navigateTo('home', false);
    return true;
  }
  return false;
};

function applyThemeToDom(themeMode, accentColor) {
  document.documentElement.setAttribute('data-theme', themeMode || 'dark');
  document.documentElement.setAttribute('data-accent', accentColor || 'cyan');
  document.documentElement.style.setProperty('--font-scale', String(window.AppState.fontScale || 1));
}

function refreshNativeSnapshot() {
  if (!window.AndroidBridge) return;
  try {
    if (!window.AppState.catalog.length) {
      window.AppState.catalog = JSON.parse(window.AndroidBridge.getCatalogJson());
    }
    window.AppState.permissions = JSON.parse(window.AndroidBridge.getPermissionsJson());
    window.AppState.telemetry = JSON.parse(window.AndroidBridge.getTelemetryJson());
    window.AppState.aiSnapshot = JSON.parse(window.AndroidBridge.getAiProvidersJson());
    const syncedRaw = window.AndroidBridge.getSyncedStateJson();
    if (syncedRaw) window.AppState.synced = JSON.parse(syncedRaw);
  } catch (e) {
    console.error(e);
  }
}

window.onNativeStateSync = function (jsonStr) {
  try {
    window.AppState.synced = JSON.parse(jsonStr);
    const p = window.AppState.synced.profile || {};
    const effectiveTheme = (p.themeMode === 'gold_royal' && !window.AppState.synced.isEffectivePremium && !window.AppState.synced.isAdmin) ? 'dark' : p.themeMode;
    applyThemeToDom(effectiveTheme, p.accentColor);
    renderNavigation();
    renderCurrentPage();
  } catch (e) {}
};

window.onNativeRefresh = function (permsJson, telJson, aiJson) {
  try {
    if (permsJson) window.AppState.permissions = JSON.parse(permsJson);
    if (telJson) window.AppState.telemetry = JSON.parse(telJson);
    if (aiJson) window.AppState.aiSnapshot = JSON.parse(aiJson);
    renderNavigation();
    renderCurrentPage();
  } catch (e) {}
};

window.onNativeToast = function (msg) { showToast(msg); };

window.onNativeSafFiles = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    window.AppState.safTreeUri = data.treeUri || '';
    window.AppState.safFiles = data.files || [];
    showToast('Loaded ' + window.AppState.safFiles.length + ' items from folder.');
    renderCurrentPage();
  } catch (e) {}
};

window.onNativeFilePicked = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    showToast('Selected ' + data.type + ': ' + data.uri.slice(0, 40) + '...');
  } catch (e) {}
};

window.onNativeVoiceResult = function (text) {
  window.AppState.voiceHistory.unshift({ text: text, time: new Date().toLocaleTimeString() });
  if (window.AppState.currentPage !== 'ai_assistant' && window.AppState.currentPage !== 'voice_assistant') {
    navigateTo('ai_assistant');
  }
  sendAiPrompt(text);
};

window.onNativeAiResponse = function (cbId, jsonStr) {
  const cb = window._aiCallbacks[cbId];
  delete window._aiCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

window.onNativeApiTest = function (cbId, jsonStr) {
  const cb = window._apiTestCallbacks[cbId];
  delete window._apiTestCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

// --- Confirmation & Permission Modals ---
let _confirmCallback = null;
function openConfirmModal(title, desc, source, permLabel, permStatus, isDangerous, onConfirm) {
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmDesc').textContent = desc;
  document.getElementById('confirmSource').textContent = source;
  document.getElementById('confirmPerm').textContent = permLabel || 'None required';
  document.getElementById('confirmPermStatus').textContent = permStatus || 'Ready';
  const badge = document.getElementById('confirmRiskBadge');
  badge.textContent = isDangerous ? 'HIGH-SENSITIVITY CONFIRMATION' : 'ACTION CONFIRMATION';
  badge.style.color = isDangerous ? 'var(--danger)' : 'var(--accent)';
  _confirmCallback = onConfirm;
  document.getElementById('confirmModal').classList.remove('hidden');
}

function closeConfirmModal(accepted) {
  document.getElementById('confirmModal').classList.add('hidden');
  const cb = _confirmCallback;
  _confirmCallback = null;
  if (accepted && cb) cb();
}

let _permCallback = null;
function openPermModal(title, rationale, onProceed) {
  document.getElementById('permModalTitle').textContent = title;
  document.getElementById('permModalRationale').textContent = rationale;
  _permCallback = onProceed;
  document.getElementById('permModal').classList.remove('hidden');
}

function closePermModal(accepted) {
  document.getElementById('permModal').classList.add('hidden');
  const cb = _permCallback;
  _permCallback = null;
  if (accepted && cb) cb();
}

// --- Native Control Execution with Permission + Confirmation Gate ---
function runControl(controlId, paramsObj, sourceProvider) {
  const ctrl = window.AppState.catalog.find(c => c.id === controlId);
  if (!ctrl) return;
  const paramsJson = JSON.stringify(paramsObj || {});
  const reqPerm = ctrl.requiredPermission;
  let permStatusText = 'Granted';
  if (reqPerm) {
    const pObj = window.AppState.permissions.find(p => p.id === reqPerm);
    if (pObj && !pObj.granted) {
      permStatusText = 'OFF (Needs Permission)';
      openPermModal(
        pObj.title,
        pObj.rationale + ' (Required for ' + ctrl.name + ')',
        function () {
          window.AndroidBridge && window.AndroidBridge.requestPermission(reqPerm);
        }
      );
      return;
    }
  }

  const executeNow = function () {
    if (!window.AndroidBridge) return;
    const res = JSON.parse(window.AndroidBridge.executeControl(controlId, paramsJson, sourceProvider || 'Manual', true));
    if (res.needsPermission) {
      window.PermissionManagerModule.handleToggle(res.needsPermission);
      return;
    }
    if (res.data && res.data.apps) {
      window.AppState.installedApps = res.data.apps;
    }
    if (res.uiAction === 'open_permission_center') navigateTo('permission_center');
    else if (res.uiAction === 'open_ai_assistant') navigateTo('ai_assistant');
    else if (res.uiAction === 'open_automation') navigateTo('automation_manager');
    else if (res.uiAction === 'open_file_manager') navigateTo('file_manager');
    else if (res.uiAction === 'toggle_floating') navigateTo('floating_settings');

    showToast(res.message);
    refreshNativeSnapshot();
    renderCurrentPage();
  };

  if (ctrl.requiresConfirmation || ctrl.isDangerous) {
    const note = ctrl.limitationNote ? (' Note: ' + ctrl.limitationNote) : '';
    openConfirmModal(
      '#' + ctrl.id + ' ' + ctrl.name,
      'Execute "' + ctrl.nameBn + '" with parameters ' + paramsJson + '.' + note,
      sourceProvider || 'Manual',
      reqPerm || 'Standard',
      permStatusText,
      ctrl.isDangerous,
      executeNow
    );
  } else {
    executeNow();
  }
}

function emergencyStop() {
  if (window.AndroidBridge) {
    window.AndroidBridge.controlFloating('stop', '#06B6D4', 56);
    window.AndroidBridge.stopSpeaking();
  }
  window._aiCallbacks = {};
  showToast('Emergency Stop: Active AI & Floating services halted.');
  refreshNativeSnapshot();
  renderCurrentPage();
}

function nativeSignOut() {
  window.AndroidBridge && window.AndroidBridge.signOut();
}

// --- AI Command Flow ---
function sendAiPrompt(overrideText) {
  const input = document.getElementById('aiPromptInput');
  const text = (overrideText !== undefined ? overrideText : (input ? input.value : '')).trim();
  if (!text) return;
  if (input && overrideText === undefined) input.value = '';

  const online = window.AppState.telemetry && window.AppState.telemetry.network && window.AppState.telemetry.network.online;
  if (online === false) {
    window.AppState.chatMessages.push({ role: 'user', text: text });
    window.AppState.chatMessages.push({ role: 'ai', text: 'Offline Mode: Internet is unavailable. You can still use all Local Phone Controls & Settings.' });
    renderCurrentPage();
    return;
  }

  window.AppState.chatMessages.push({ role: 'user', text: text });
  window.AppState.chatMessages.push({ role: 'ai', text: 'Thinking...', pending: true });
  renderCurrentPage();

  if (!window.AndroidBridge) return;
  const cbId = 'ai_' + Date.now();
  const provSel = document.getElementById('aiChatProvider');
  const requestedProvider = provSel ? provSel.value : '';

  window._aiCallbacks[cbId] = function (res) {
    window.AppState.chatMessages = window.AppState.chatMessages.filter(m => !m.pending);
    const primary = res.primary || {};
    const proposals = res.allProposals || [];
    window.AppState.chatMessages.push({
      role: 'ai',
      text: primary.replyText || primary.explanation || 'Ready.',
      providerName: primary.providerName || 'AI',
      proposal: primary.controlId > 0 ? primary : null,
      allProposals: proposals
    });
    if (window.AppState.voiceEnabled && primary.replyText) {
      window.AndroidBridge.speakText(primary.replyText, window.AppState.voiceLang);
    }
    renderCurrentPage();
  };

  window.AndroidBridge.sendAiCommand(text, requestedProvider, cbId);
}

// --- Navigation & Header Render ---
function renderNavigation() {
  const p = window.AppState.synced.profile || {};
  document.getElementById('drawerUserName').textContent = p.displayName || 'User';
  document.getElementById('drawerUserEmail').textContent = p.email || '';
  document.getElementById('drawerPlanBadge').textContent = (p.planTier || 'free').toUpperCase();

  const isOnline = !(window.AppState.telemetry && window.AppState.telemetry.network && window.AppState.telemetry.network.online === false);
  const netBadge = document.getElementById('netBadge');
  netBadge.textContent = isOnline ? 'Online' : 'Offline';
  netBadge.className = 'status-pill ' + (isOnline ? 'online' : 'offline');

  const pageMeta = PAGES.find(x => x.id === window.AppState.currentPage);
  document.getElementById('topbarSubtitle').textContent = pageMeta ? pageMeta.label : 'Goriber AI';

  const linksHtml = PAGES
    .filter(item => !item.adminOnly || window.AppState.synced.isAdmin)
    .map(item => `
      <button class="drawer-link ${window.AppState.currentPage === item.id ? 'active' : ''}"
        onclick="navigateTo('${item.id}')">
        <span>${escapeHtml(item.label)}</span>
      </button>
    `).join('');
  document.getElementById('drawerLinks').innerHTML = linksHtml;

  document.querySelectorAll('.bnav-item').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.page === window.AppState.currentPage);
  });
}

// --- Page Renderers ---
function renderCurrentPage() {
  const container = document.getElementById('pageContainer');
  if (!container) return;
  const page = window.AppState.currentPage;
  const s = window.AppState;
  const tel = s.telemetry || {};
  const bat = tel.battery || {};
  const ram = tel.ram || {};
  const stor = tel.storage || {};
  const net = tel.network || {};

  switch (page) {
    case 'welcome':
      container.innerHTML = `
        <div class="hero-card">
          <div class="chip active" style="display:inline-block; margin-bottom:10px;">Goriber AI • Smart Controller</div>
          <h1 style="margin:0 0 8px; font-size:24px;">স্বাগতম, ${escapeHtml(s.synced.profile.displayName || 'User')}</h1>
          <p class="muted">১০০টি নেটিভ ফোন কন্ট্রোল, ৪টি AI মডেল, ফ্লোটিং ওভারলে, অটোমেশন ও পূর্ণ নিরাপত্তা ড্যাশবোর্ড।</p>
          <div class="row-gap" style="margin-top:16px;">
            <button class="btn btn-primary" onclick="navigateTo('home')">Open Dashboard</button>
            <button class="btn btn-outline" onclick="navigateTo('ai_assistant')">Ask AI Assistant</button>
            <button class="btn btn-ghost" onclick="navigateTo('floating_settings')">Floating Controller</button>
          </div>
        </div>
        <div class="grid-2">
          <div class="stat-tile"><div class="stat-label">Phone Controls</div><div class="stat-val">100</div></div>
          <div class="stat-tile"><div class="stat-label">AI Engines</div><div class="stat-val">4</div></div>
          <div class="stat-tile"><div class="stat-label">Permissions</div><div class="stat-val">${(s.permissions || []).filter(p => p.granted).length}/15</div></div>
          <div class="stat-tile"><div class="stat-label">Membership</div><div class="stat-val">${escapeHtml((s.synced.profile.planTier || 'free').toUpperCase())}</div></div>
        </div>
      `;
      break;

    case 'home':
      container.innerHTML = `
        <div class="hero-card">
          <div class="row-between">
            <div>
              <div class="muted">Live System Telemetry</div>
              <h2 style="margin:2px 0 0;">${escapeHtml(tel.manufacturer || '')} ${escapeHtml(tel.model || 'Android Device')}</h2>
            </div>
            <button class="btn btn-sm btn-primary" onclick="navigateTo('floating_settings')">Floating UI</button>
          </div>
          <div class="grid-2" style="margin-top:14px;">
            <div class="stat-tile"><div class="stat-label">Battery</div><div class="stat-val">${bat.percent ?? '--'}% ${bat.charging ? '⚡' : ''}</div></div>
            <div class="stat-tile"><div class="stat-label">Free RAM</div><div class="stat-val">${ram.availableMb ?? '--'} MB</div></div>
            <div class="stat-tile"><div class="stat-label">Free Storage</div><div class="stat-val">${stor.freeGb ?? '--'} GB</div></div>
            <div class="stat-tile"><div class="stat-label">Network</div><div class="stat-val" style="font-size:16px;">${escapeHtml(net.transport || 'Checking')}</div></div>
          </div>
        </div>

        <div class="card">
          <strong>Quick AI Command (বাংলা / English)</strong>
          <div class="row-gap" style="margin-top:10px;">
            <input id="homeQuickCmd" class="input" style="flex:1;" placeholder="যেমন: স্ক্রিনের আলো ৫০% করো বা Wi-Fi খোলো" />
            <button class="btn btn-primary" onclick="navigateTo('ai_assistant'); sendAiPrompt(document.getElementById('homeQuickCmd')?.value || '')">Run</button>
            <button class="btn btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.startVoiceRecognition('${s.voiceLang}')">🎙</button>
          </div>
        </div>

        <div class="gold-card">
          <div class="row-between">
            <div>
              <span class="crown-badge">${s.synced.isEffectivePremium ? '👑 ROYAL PREMIUM ACTIVE' : '✨ GORIBER AI PRO & PLAY STORE'}</span>
              <div class="gold-title" style="margin-top:6px;">${escapeHtml(s.synced.profile?.packageName || 'Free Account')}</div>
              <div class="muted" style="font-size:12px;">${s.synced.isEffectivePremium ? 'All 15 Premium Features Unlocked • Code Studio & Custom AI Ready' : 'Upgrade via Google Play, bKash, Nagad or Rocket'}</div>
            </div>
            <button class="btn btn-sm btn-gold" onclick="navigateTo('subscription')">${s.synced.isEffectivePremium ? 'Dashboard' : 'Upgrade'}</button>
          </div>
          <div class="row-gap" style="margin-top:10px;">
            <button class="btn btn-sm btn-outline" onclick="navigateTo('code_studio')">⚡ Code Studio</button>
            <button class="btn btn-sm btn-outline" onclick="navigateTo('custom_assistants')">🤖 Custom AI &amp; Files</button>
            <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge && window.AndroidBridge.openPlayStoreListing()">▶ Play Store</button>
          </div>
        </div>

        <div class="card">
          <div class="row-between" style="margin-bottom:10px;">
            <strong>App Control Center (14 Modules)</strong>
            <button class="btn btn-sm btn-ghost" onclick="navigateTo('analytics')">Analytics →</button>
          </div>
          <div class="grid-3">
            ${[
              ['display_control', '☀ Display (1-10)'],
              ['keyboard_control', '⌨ Keyboard (11-20)'],
              ['app_control', '⊞ Apps (21-30)'],
              ['sound_control', '🔊 Sound (31-40)'],
              ['network_control', '📶 Network (41-50)'],
              ['security_center', '🔒 Security (51-60)'],
              ['permission_center', '🛡 Permissions'],
              ['file_manager', '📁 Files (61-70)'],
              ['battery_manager', '🔋 Battery (71-80)'],
              ['system_manager', '📱 System (81-90)'],
              ['automation_manager', '⏱ Automation'],
              ['subscription', '👑 Premium & Play'],
              ['code_studio', '⚡ Code Studio'],
              ['custom_assistants', '🤖 Custom AI']
            ].map(([id, label]) => `<button class="btn btn-ghost" style="justify-content:flex-start;" onclick="navigateTo('${id}')">${label}</button>`).join('')}
          </div>
        </div>
      `;
      break;

    case 'ai_assistant': {
      const chatHtml = s.chatMessages.map(m => {
        const propBtn = m.proposal ? `
          <div style="margin-top:8px; padding-top:8px; border-top:1px solid var(--border);">
            <div class="muted">Command: <strong>#${m.proposal.controlId} ${escapeHtml(m.proposal.actionName)}</strong></div>
            <button class="btn btn-sm btn-primary" style="margin-top:6px;"
              onclick='runControl(${m.proposal.controlId}, ${JSON.stringify(JSON.parse(m.proposal.parametersJson || "{}"))}, ${JSON.stringify(m.providerName || "AI")})'>
              Review &amp; Execute
            </button>
          </div>` : '';
        const multiHtml = (m.allProposals && m.allProposals.length > 1)
          ? `<div class="muted" style="margin-top:6px; font-size:11px;">Multi-AI: ${m.allProposals.map(x => escapeHtml(x.providerName) + '→#' + x.controlId).join(' | ')}</div>`
          : '';
        return `<div class="chat-msg ${m.role}">${escapeHtml(m.text)}${propBtn}${multiHtml}</div>`;
      }).join('');

      container.innerHTML = `
        <div class="card">
          <div class="row-between">
            <div>
              <h2 class="section-title">AI Phone Assistant</h2>
              <p class="muted">Every AI command is validated &amp; requires your confirmation.</p>
            </div>
            <select id="aiChatProvider" class="select" style="width:auto;">
              <option value="">Default / Multi-AI</option>
              <option value="gemini">Gemini</option>
              <option value="chatgpt">ChatGPT</option>
              <option value="claude">Claude</option>
              <option value="deepseek">DeepSeek</option>
            </select>
          </div>
        </div>
        <div class="card">
          <div class="chat-box">${chatHtml}</div>
          <div class="row-gap">
            <input id="aiPromptInput" class="input" style="flex:1;" placeholder="বাংলা বা ইংরেজিতে নির্দেশ লিখুন..." onkeydown="if(event.key==='Enter') sendAiPrompt()" />
            <button class="btn btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.startVoiceRecognition('${s.voiceLang}')">🎙</button>
            <button class="btn btn-primary" onclick="sendAiPrompt()">Send</button>
          </div>
          <div class="row-gap" style="margin-top:10px;">
            <button class="chip" onclick="sendAiPrompt('স্ক্রিনের আলো ৫০% করো')">আলো ৫০%</button>
            <button class="chip" onclick="sendAiPrompt('Wi-Fi Settings খোলো')">Wi-Fi</button>
            <button class="chip" onclick="sendAiPrompt('আমার ব্যাটারির চার্জ দেখাও')">ব্যাটারি</button>
            <button class="chip" onclick="sendAiPrompt('YouTube চালু করো')">YouTube</button>
          </div>
        </div>
      `;
      break;
    }

    case 'ai_manager':
      container.innerHTML = window.AiManagerModule.render(s);
      break;

    case 'api_manager':
      container.innerHTML = window.ApiManagerModule.render(s);
      break;

    case 'phone_control': {
      const cats = ['All', 'Display', 'Keyboard', 'Apps', 'Sound', 'Network', 'Security', 'Files', 'Battery', 'System', 'AI'];
      const chips = cats.map(c => `<button class="chip ${s.controlFilter === c ? 'active' : ''}" onclick="window.AppState.controlFilter='${c}'; renderCurrentPage();">${c}</button>`).join('');
      const q = (s.controlSearch || '').toLowerCase();
      const filtered = s.catalog.filter(c =>
        (s.controlFilter === 'All' || c.category === s.controlFilter) &&
        (!q || c.name.toLowerCase().includes(q) || c.nameBn.toLowerCase().includes(q) || String(c.id) === q)
      );
      const listHtml = filtered.map(c => `
        <div class="control-item">
          <div style="flex:1;">
            <div><span class="control-id">#${c.id}</span> <strong>${escapeHtml(c.name)}</strong></div>
            <div class="muted">${escapeHtml(c.nameBn)} • ${escapeHtml(c.category)} ${c.limitationNote ? '• ℹ' : ''}</div>
          </div>
          <button class="btn btn-sm btn-primary" onclick="runControl(${c.id}, {}, 'Manual')">Run</button>
        </div>
      `).join('');

      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">100 Phone Control Features</h2>
          <input class="input" placeholder="Search 1-100 (e.g. Brightness, Wi-Fi, Battery)..." value="${escapeHtml(s.controlSearch)}"
            oninput="window.AppState.controlSearch=this.value; renderCurrentPage();" style="margin-top:8px;" />
          <div class="row-gap" style="margin-top:10px;">${chips}</div>
        </div>
        <div class="card">${listHtml || '<p class="muted">No matching controls.</p>'}</div>
      `;
      break;
    }

    case 'display_control':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Display Control (1–10)</h2>
          <div style="margin-top:12px;">
            <div class="row-between"><span>#1 Screen Brightness</span><strong>${tel.brightness ?? 50}%</strong></div>
            <input type="range" class="range-slider" min="5" max="100" value="${tel.brightness ?? 50}"
              onchange="runControl(1, {value: parseInt(this.value,10)}, 'Display')" />
          </div>
          <div class="row-gap" style="margin-top:12px;">
            <button class="btn btn-outline" onclick="runControl(2, {enabled: ${!tel.autoBrightness}}, 'Display')">#2 Auto Brightness (${tel.autoBrightness ? 'ON' : 'OFF'})</button>
            <button class="btn btn-outline" onclick="runControl(3, {seconds: 60}, 'Display')">#3 Timeout 60s</button>
            <button class="btn btn-outline" onclick="runControl(6, {enabled: ${!tel.autoRotate}}, 'Display')">#6 Rotation (${tel.autoRotate ? 'ON' : 'LOCK'})</button>
          </div>
        </div>
        ${renderCategoryList('Display')}
      `;
      break;

    case 'keyboard_control':
      container.innerHTML = `<div class="card"><h2 class="section-title">Keyboard Control (11–20)</h2><p class="muted">System IME &amp; Voice Typing controls.</p></div>${renderCategoryList('Keyboard')}`;
      break;

    case 'app_control': {
      const apps = s.installedApps || [];
      const appsHtml = apps.slice(0, 25).map(a => `
        <div class="control-item">
          <div style="flex:1;"><strong>${escapeHtml(a.label)}</strong><div class="muted">${escapeHtml(a.packageName)}</div></div>
          <div class="row-gap">
            <button class="btn btn-sm btn-primary" onclick="runControl(22, {packageName:'${escapeHtml(a.packageName)}'}, 'Apps')">Open</button>
            <button class="btn btn-sm btn-outline" onclick="runControl(23, {packageName:'${escapeHtml(a.packageName)}'}, 'Apps')">Info</button>
            <button class="btn btn-sm btn-danger" onclick="runControl(28, {packageName:'${escapeHtml(a.packageName)}'}, 'Apps')">Uninstall</button>
          </div>
        </div>
      `).join('');
      container.innerHTML = `
        <div class="card">
          <div class="row-between">
            <h2 class="section-title">Application Control (21–30)</h2>
            <button class="btn btn-sm btn-primary" onclick="runControl(21, {}, 'Apps')">Load Apps</button>
          </div>
        </div>
        ${renderCategoryList('Apps')}
        <div class="card"><strong>Installed Applications</strong><div style="margin-top:10px;">${appsHtml || '<p class="muted">Tap "Load Apps" (#21) to list installed launchable apps.</p>'}</div></div>
      `;
      break;
    }

    case 'sound_control': {
      const aud = tel.audio || {};
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Sound Control (31–40)</h2>
          <p class="muted">Ringer Mode: <strong>${escapeHtml(aud.ringerMode || 'Normal')}</strong></p>
          ${[
            [31, 'Media Volume', aud.media ?? 50],
            [32, 'Call Volume', aud.call ?? 60],
            [33, 'Alarm Volume', aud.alarm ?? 70],
            [34, 'Notification Volume', aud.notification ?? 50],
            [35, 'Ring Volume', aud.ring ?? 60]
          ].map(([id, label, val]) => `
            <div style="margin-top:12px;">
              <div class="row-between"><span>#${id} ${label}</span><strong>${val}%</strong></div>
              <input type="range" class="range-slider" min="0" max="100" value="${val}"
                onchange="runControl(${id}, {value: parseInt(this.value,10)}, 'Sound')" />
            </div>
          `).join('')}
        </div>
        ${renderCategoryList('Sound')}
      `;
      break;
    }

    case 'network_control':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Network Control (41–50)</h2>
          <div class="grid-2" style="margin-top:10px;">
            <div class="stat-tile"><div class="stat-label">Wi-Fi</div><div class="stat-val" style="font-size:16px;">${net.wifiEnabled ? 'Enabled' : 'Disabled'}</div></div>
            <div class="stat-tile"><div class="stat-label">Internet</div><div class="stat-val" style="font-size:16px;">${net.online ? 'Validated' : 'Offline'}</div></div>
            <div class="stat-tile"><div class="stat-label">Transport</div><div class="stat-val" style="font-size:16px;">${escapeHtml(net.transport || 'N/A')}</div></div>
            <div class="stat-tile"><div class="stat-label">Airplane</div><div class="stat-val" style="font-size:16px;">${net.airplaneMode ? 'ON' : 'OFF'}</div></div>
          </div>
        </div>
        ${renderCategoryList('Network')}
      `;
      break;

    case 'security_center':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Security Center (51–60)</h2>
          <p class="muted">Zero-Trust verification, Hardware Keystore vault, and Admin PIN protection.</p>
          <div class="grid-2" style="margin-top:12px;">
            <div class="stat-tile"><div class="stat-label">Keystore AES-256</div><div class="stat-val" style="font-size:15px;">ACTIVE</div></div>
            <div class="stat-tile"><div class="stat-label">Admin Vault PIN</div><div class="stat-val" style="font-size:15px;">${s.aiSnapshot.adminPinSet ? 'SET' : 'NOT SET'}</div></div>
          </div>
          <div style="margin-top:14px;" class="row-gap">
            <input id="secPinInput" type="password" class="input" style="flex:1;" placeholder="Set 4+ digit Security Vault PIN" />
            <button class="btn btn-primary" onclick="saveSecurityPin()">Save PIN</button>
          </div>
          <div class="row-gap" style="margin-top:12px;">
            <button class="btn btn-outline" onclick="navigateTo('permission_center')">Permission Dashboard</button>
            <button class="btn btn-danger" onclick="emergencyStop()">Emergency Stop</button>
          </div>
        </div>
        ${renderCategoryList('Security')}
      `;
      break;

    case 'permission_center':
      container.innerHTML = window.PermissionManagerModule.render(s);
      break;

    case 'file_manager': {
      const filesHtml = (s.safFiles || []).map(f => `
        <div class="control-item">
          <div style="flex:1;"><strong>${f.isDir ? '📁' : '📄'} ${escapeHtml(f.name)}</strong><div class="muted">${escapeHtml(f.mime)}</div></div>
          <button class="btn btn-sm btn-outline" onclick="promptSafRename('${escapeHtml(f.uri)}', '${escapeHtml(f.name)}')">Rename</button>
        </div>
      `).join('');
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">File &amp; Storage Control (61–70)</h2>
          <p class="muted">Play-compliant zero-permission Photo Picker &amp; Storage Access Framework (SAF). Free: ${stor.freeGb ?? '--'} / ${stor.totalGb ?? '--'} GB</p>
        </div>
        ${renderCategoryList('Files')}
        <div class="card">
          <strong>Selected SAF Folder Contents</strong>
          <div style="margin-top:10px;">${filesHtml || '<p class="muted">Tap #61 File Manager above to pick and browse a folder.</p>'}</div>
        </div>
      `;
      break;
    }

    case 'battery_manager':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Battery &amp; Performance (71–80)</h2>
          <div class="grid-2" style="margin-top:10px;">
            <div class="stat-tile"><div class="stat-label">Charge</div><div class="stat-val">${bat.percent ?? '--'}%</div></div>
            <div class="stat-tile"><div class="stat-label">Health / Temp</div><div class="stat-val" style="font-size:15px;">${escapeHtml(bat.health || 'Good')} • ${bat.tempC ?? '--'}°C</div></div>
            <div class="stat-tile"><div class="stat-label">RAM Used</div><div class="stat-val" style="font-size:15px;">${ram.usedMb ?? '--'} / ${ram.totalMb ?? '--'} MB</div></div>
            <div class="stat-tile"><div class="stat-label">CPU Cores</div><div class="stat-val">${tel.cpuCores ?? '--'}</div></div>
          </div>
        </div>
        ${renderCategoryList('Battery')}
      `;
      break;

    case 'system_manager':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Phone &amp; System Control (81–90)</h2>
          <div class="muted">${escapeHtml(tel.manufacturer || '')} ${escapeHtml(tel.model || '')} • Android ${escapeHtml(tel.androidVersion || '')} (API ${tel.sdkInt || ''})</div>
        </div>
        ${renderCategoryList('System')}
      `;
      break;

    case 'automation_manager':
      container.innerHTML = window.AutomationModule.render(s);
      break;

    case 'floating_settings':
      container.innerHTML = window.FloatingControllerModule.render(s);
      break;

    case 'voice_assistant':
      container.innerHTML = `
        <div class="card">
          <div class="row-between">
            <div>
              <h2 class="section-title">AI Voice Assistant</h2>
              <p class="muted">Bengali (bn-BD) &amp; English (en-US) Voice Command + TTS</p>
            </div>
            <button class="switch-btn ${s.voiceEnabled ? 'on' : 'off'}" onclick="window.AppState.voiceEnabled=!window.AppState.voiceEnabled; renderCurrentPage();">
              ${s.voiceEnabled ? 'ON' : 'OFF'}
            </button>
          </div>
          <div class="row-gap" style="margin-top:12px;">
            <button class="chip ${s.voiceLang === 'bn-BD' ? 'active' : ''}" onclick="window.AppState.voiceLang='bn-BD'; renderCurrentPage();">বাংলা (bn-BD)</button>
            <button class="chip ${s.voiceLang === 'en-US' ? 'active' : ''}" onclick="window.AppState.voiceLang='en-US'; renderCurrentPage();">English (en-US)</button>
          </div>
          <div class="row-gap" style="margin-top:14px;">
            <button class="btn btn-primary" onclick="window.AndroidBridge && window.AndroidBridge.startVoiceRecognition('${s.voiceLang}')">🎙 Start Voice Command</button>
            <button class="btn btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.speakText('এআই স্মার্ট ফোন কন্ট্রোলার প্রস্তুত আছে।', 'bn-BD')">🔊 Test TTS</button>
          </div>
        </div>
        <div class="card">
          <strong>Voice Command History</strong>
          <div style="margin-top:10px;">
            ${s.voiceHistory.length ? s.voiceHistory.map(v => `<div class="control-item"><span>${escapeHtml(v.text)}</span><span class="muted">${escapeHtml(v.time)}</span></div>`).join('') : '<p class="muted">No voice commands yet.</p>'}
          </div>
        </div>
      `;
      break;

    case 'activity_history': {
      const logs = s.synced.logs || [];
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">Activity History</h2>
          <p class="muted">Every executed action, AI provider, permission status &amp; confirmation record.</p>
        </div>
        <div class="card">
          ${logs.length ? logs.map(l => `
            <div class="control-item">
              <div style="flex:1;">
                <div><strong>#${l.controlId} ${escapeHtml(l.actionName)}</strong> <span class="chip ${l.success ? 'active' : ''}">${l.success ? 'SUCCESS' : 'FAILED'}</span></div>
                <div class="muted">AI: ${escapeHtml(l.aiProvider)} • Perm: ${escapeHtml(l.permissionStatus)} • Confirmed: ${l.userConfirmed ? 'Yes' : 'No'}</div>
                ${l.errorDetails ? `<div style="color:var(--danger); font-size:12px;">${escapeHtml(l.errorDetails)}</div>` : ''}
              </div>
              <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge && window.AndroidBridge.deleteActivityLog('${l.id}')">Delete</button>
            </div>
          `).join('') : '<p class="muted">No activity recorded yet.</p>'}
        </div>
      `;
      break;
    }

    case 'app_settings': {
      const prof = s.synced.profile || {};
      const curTheme = prof.themeMode || 'dark';
      const curAccent = prof.accentColor || 'cyan';
      const goldUnlocked = !!s.synced.isEffectivePremium || !!s.synced.isAdmin;
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">App Settings &amp; Theme Customizer</h2>
          <p class="muted">Customize Dark, AMOLED, Light, or Black &amp; Gold Royal Premium Theme.</p>
          <div style="margin-top:14px;">
            <strong>Theme Mode</strong>
            <div class="row-gap" style="margin-top:8px;">
              ${['dark', 'amoled', 'light', 'gold_royal'].map(t => `<button class="chip ${curTheme === t ? 'active' : ''}" onclick="setAppTheme('${t}', '${t === 'gold_royal' ? 'gold' : curAccent}')">${t === 'gold_royal' ? (goldUnlocked ? '👑 BLACK & GOLD' : '🔒 BLACK & GOLD') : t.toUpperCase()}</button>`).join('')}
            </div>
          </div>
          <div style="margin-top:14px;">
            <strong>Accent Color</strong>
            <div class="row-gap" style="margin-top:8px;">
              ${['cyan', 'indigo', 'emerald', 'amber', 'rose', 'gold'].map(a => `<button class="chip ${curAccent === a ? 'active' : ''}" onclick="setAppTheme('${curTheme}', '${a}')">${a.toUpperCase()}</button>`).join('')}
            </div>
          </div>
          <div style="margin-top:14px;">
            <strong>UI Font Scale (${s.fontScale}x)</strong>
            <input type="range" class="range-slider" min="0.85" max="1.25" step="0.05" value="${s.fontScale}"
              oninput="window.AppState.fontScale=parseFloat(this.value); applyThemeToDom('${curTheme}','${curAccent}');" />
          </div>
        </div>
        <div class="card">
          <strong>Account, Play Store &amp; Premium</strong>
          <div class="row-gap" style="margin-top:10px;">
            <button class="btn btn-gold" onclick="navigateTo('subscription')">👑 Premium &amp; Play Store</button>
            <button class="btn btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.openPlayStoreListing()">▶ Rate on Google Play</button>
            <button class="btn btn-outline" onclick="navigateTo('analytics')">User Data Analytics</button>
            ${s.synced.isAdmin ? `<button class="btn btn-outline" onclick="navigateTo('admin_panel')">👑 Open Admin Panel</button>` : ''}
            <button class="btn btn-danger" onclick="nativeSignOut()">Sign Out</button>
          </div>
        </div>
      `;
      break;
    }

    case 'analytics': {
      const logs = s.synced.logs || [];
      const totalCmd = s.synced.profile.totalCommands || logs.length;
      const totalAi = s.synced.profile.totalAiQueries || 0;
      const successCount = logs.filter(l => l.success).length;
      const rate = logs.length ? Math.round((successCount * 100) / logs.length) : 100;
      const catCounts = {};
      logs.forEach(l => { catCounts[l.category] = (catCounts[l.category] || 0) + 1; });
      const maxCat = Math.max(1, ...Object.values(catCounts));

      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">User Data Analytics Dashboard</h2>
          <div class="grid-2" style="margin-top:12px;">
            <div class="stat-tile"><div class="stat-label">Total Commands</div><div class="stat-val">${totalCmd}</div></div>
            <div class="stat-tile"><div class="stat-label">AI Queries</div><div class="stat-val">${totalAi}</div></div>
            <div class="stat-tile"><div class="stat-label">Success Rate</div><div class="stat-val">${rate}%</div></div>
            <div class="stat-tile"><div class="stat-label">Automations</div><div class="stat-val">${(s.synced.automations || []).length}</div></div>
          </div>
        </div>
        <div class="card">
          <strong>Command Activity by Category</strong>
          <div style="margin-top:12px;">
            ${Object.keys(catCounts).length ? Object.entries(catCounts).map(([cat, cnt]) => `
              <div style="margin-bottom:10px;">
                <div class="row-between"><span>${escapeHtml(cat)}</span><strong>${cnt}</strong></div>
                <div class="bar-track"><div class="bar-fill" style="width:${Math.round((cnt * 100) / maxCat)}%;"></div></div>
              </div>
            `).join('') : '<p class="muted">Execute phone controls to see category analytics.</p>'}
          </div>
        </div>
      `;
      break;
    }

    case 'subscription':
      container.innerHTML = window.PremiumManagerModule.renderDashboard(s);
      break;

    case 'code_studio':
      container.innerHTML = window.PremiumManagerModule.renderCodeStudio(s);
      break;

    case 'custom_assistants':
      container.innerHTML = window.PremiumManagerModule.renderCustomAssistantsAndFiles(s);
      break;

    case 'admin_panel':
      container.innerHTML = window.AdminPanelModule.render(s);
      break;

    case 'about':
      container.innerHTML = `
        <div class="card">
          <h2 class="section-title">About Goriber AI</h2>
          <p class="muted">Version 1.0 • Ad-Free • User-Controlled Security Architecture</p>
          <p style="margin-top:10px;">This app combines Jetpack Compose, Material 3, Firebase Auth &amp; Firestore, Firebase AI Logic, and native Android APIs with an encrypted Keystore vault.</p>
          <strong>Android OS Security Transparency:</strong>
          <ul class="muted" style="padding-left:18px; margin-top:6px;">
            <li>Wi-Fi, Mobile Data, Airplane Mode &amp; Default Apps open official Android System Panels/Settings on modern Android versions.</li>
            <li>Clearing other apps' cache or uninstalling apps requires explicit system confirmation.</li>
            <li>Notification Shade, Quick Settings &amp; Power Dialog use Accessibility Service only after explicit user consent.</li>
          </ul>
        </div>
      `;
      break;
  }
}

function renderCategoryList(category) {
  const items = (window.AppState.catalog || []).filter(c => c.category === category);
  return `
    <div class="card">
      ${items.map(c => `
        <div class="control-item">
          <div style="flex:1;">
            <div><span class="control-id">#${c.id}</span> <strong>${escapeHtml(c.name)}</strong></div>
            <div class="muted">${escapeHtml(c.nameBn)}${c.limitationNote ? ' — ' + escapeHtml(c.limitationNote) : ''}</div>
          </div>
          <button class="btn btn-sm btn-primary" onclick="runControl(${c.id}, {}, '${escapeHtml(category)}')">Execute</button>
        </div>
      `).join('')}
    </div>
  `;
}

function setAppTheme(theme, accent) {
  if (theme === 'gold_royal' && !window.AppState.synced.isEffectivePremium && !window.AppState.synced.isAdmin) {
    showToast('Black & Gold Royal Theme requires Premium!');
    return;
  }
  applyThemeToDom(theme, accent);
  window.AndroidBridge && window.AndroidBridge.updateTheme(theme, accent);
  if (window.AppState.synced.profile) {
    window.AppState.synced.profile.themeMode = theme;
    window.AppState.synced.profile.accentColor = accent;
  }
  renderCurrentPage();
}

function saveSecurityPin() {
  const pin = (document.getElementById('secPinInput')?.value || '').trim();
  if (pin.length < 4) {
    showToast('PIN must be at least 4 characters.');
    return;
  }
  if (window.AndroidBridge && window.AndroidBridge.setAdminPasscode(pin)) {
    document.getElementById('secPinInput').value = '';
    showToast('Security Vault PIN saved (PBKDF2 hashed).');
    refreshNativeSnapshot();
    renderCurrentPage();
  }
}

function unlockAdminVault() {
  const pin = (document.getElementById('adminUnlockPin')?.value || '').trim();
  if (window.AndroidBridge && window.AndroidBridge.verifyAdminPasscode(pin)) {
    window.AppState.adminUnlocked = true;
    renderCurrentPage();
  } else {
    showToast('Invalid Admin Vault PIN.');
  }
}

function promptSafRename(uri, currentName) {
  const nextName = window.prompt('Rename file:', currentName);
  if (!nextName || nextName === currentName) return;
  openConfirmModal(
    'Rename File',
    'Rename "' + currentName + '" to "' + nextName + '"?',
    'File Manager',
    'Storage (SAF)',
    'Granted',
    false,
    function () {
      if (!window.AndroidBridge) return;
      const res = JSON.parse(window.AndroidBridge.renameSafFile(uri, nextName));
      showToast(res.message);
    }
  );
}

// Initial boot
document.addEventListener('DOMContentLoaded', () => {
  refreshNativeSnapshot();
  renderNavigation();
  renderCurrentPage();
});
