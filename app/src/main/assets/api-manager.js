window.ApiManagerModule = {
  revealed: {},

  render: function (state) {
    const snap = state.aiSnapshot || { providers: [] };
    const providers = snap.providers || [];

    const options = providers.map(p => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.name)}</option>`).join('');
    const listHtml = providers.map(p => {
      const showMask = this.revealed[p.id];
      return `
        <div class="card">
          <div class="row-between">
            <div>
              <strong>${escapeHtml(p.name)}</strong>
              <div class="muted">${escapeHtml(p.endpoint)}</div>
            </div>
            <span class="chip ${p.hasKey ? 'active' : ''}">${escapeHtml(p.status)}</span>
          </div>
          <div style="margin-top:10px; font-family:monospace; font-size:12px; color:var(--accent);">
            Key: ${showMask ? escapeHtml(p.maskedKey || 'Not Set') : '••••••••••••'}
          </div>
          <div class="row-gap" style="margin-top:10px;">
            <button class="btn btn-sm btn-ghost" onclick="window.ApiManagerModule.toggleMask('${p.id}')">
              ${showMask ? 'Hide Mask' : 'Show Mask'}
            </button>
            <button class="btn btn-sm btn-outline" onclick="window.ApiManagerModule.test('${p.id}')">Test Connection</button>
            <button class="btn btn-sm btn-outline" onclick="window.ApiManagerModule.prefill('${p.id}', '${escapeHtml(p.endpoint)}', ${p.usageLimit})">Edit</button>
            ${p.hasCustomKey ? `<button class="btn btn-sm btn-danger" onclick="window.ApiManagerModule.remove('${p.id}')">Delete</button>` : ''}
          </div>
        </div>
      `;
    }).join('');

    return `
      <div class="card">
        <h2 class="section-title">API Management Vault</h2>
        <p class="muted">Keys are encrypted via hardware-backed Android Keystore (AES-256-GCM) and never stored in HTML or JavaScript.</p>
      </div>
      <div class="card">
        <div style="display:flex; flex-direction:column; gap:10px;">
          <label class="muted">AI Provider</label>
          <select id="apiProviderSelect" class="select">${options}</select>
          <label class="muted">API Key (Encrypted in Keystore)</label>
          <input id="apiKeyInput" type="password" class="input" placeholder="Paste API Key (sk-... / AIza...)" />
          <label class="muted">Custom API Endpoint (Optional)</label>
          <input id="apiEndpointInput" type="url" class="input" placeholder="Leave blank for official endpoint" />
          <label class="muted">Daily / Session Usage Limit</label>
          <input id="apiLimitInput" type="number" class="input" value="500" min="1" max="100000" />
          <button class="btn btn-primary" onclick="window.ApiManagerModule.save()">Save &amp; Encrypt Key</button>
        </div>
      </div>
      ${listHtml}
    `;
  },

  toggleMask: function (id) {
    this.revealed[id] = !this.revealed[id];
    renderCurrentPage();
  },

  prefill: function (id, endpoint, limit) {
    const sel = document.getElementById('apiProviderSelect');
    const ep = document.getElementById('apiEndpointInput');
    const lim = document.getElementById('apiLimitInput');
    if (sel) sel.value = id;
    if (ep) ep.value = endpoint.startsWith('firebase-ai-logic') ? '' : endpoint;
    if (lim) lim.value = limit || 500;
    showToast('Ready to edit ' + id);
  },

  save: function () {
    const provider = document.getElementById('apiProviderSelect').value;
    const key = document.getElementById('apiKeyInput').value.trim();
    const endpoint = document.getElementById('apiEndpointInput').value.trim();
    const limit = parseInt(document.getElementById('apiLimitInput').value || '500', 10);
    if (!key && !endpoint) {
      showToast('Enter an API key or custom endpoint.');
      return;
    }
    if (window.AndroidBridge) {
      window.AppState.aiSnapshot = JSON.parse(window.AndroidBridge.saveApiKey(provider, key, endpoint, limit));
      document.getElementById('apiKeyInput').value = '';
      showToast('API Key encrypted and stored in Android Keystore.');
      renderCurrentPage();
    }
  },

  remove: function (id) {
    if (window.AndroidBridge) {
      window.AppState.aiSnapshot = JSON.parse(window.AndroidBridge.deleteApiKey(id));
      showToast('Deleted key for ' + id);
      renderCurrentPage();
    }
  },

  test: function (id) {
    if (!window.AndroidBridge) return;
    const cbId = 'api_test_' + Date.now();
    showToast('Testing ' + id + ' connection...');
    window._apiTestCallbacks[cbId] = function (res) {
      showToast(res.message || (res.ok ? 'Connected' : 'Failed'));
    };
    window.AndroidBridge.testApiConnection(id, cbId);
  }
};
