window.AiManagerModule = {
  render: function (state) {
    const snap = state.aiSnapshot || { defaultProvider: 'gemini', multiAiMode: false, providers: [], errors: [] };
    const providers = snap.providers || [];
    const errors = snap.errors || [];

    const cards = providers.map(p => {
      const statusColor = p.status === 'Error' ? 'var(--danger)' : (p.hasKey ? 'var(--success)' : 'var(--warning)');
      return `
        <div class="card">
          <div class="row-between">
            <div>
              <strong>${escapeHtml(p.name)}</strong>
              <div class="muted">${escapeHtml(p.model)} • Usage: ${p.usageCount}/${p.usageLimit}</div>
            </div>
            <button class="switch-btn ${p.enabled ? 'on' : 'off'}"
              onclick="window.AiManagerModule.toggle('${p.id}', ${!p.enabled}, ${p.isDefault}, ${snap.multiAiMode})">
              ${p.enabled ? 'ON' : 'OFF'}
            </button>
          </div>
          <div class="row-between" style="margin-top:10px;">
            <span style="font-size:12px; color:${statusColor};">● ${escapeHtml(p.status)} (${escapeHtml(p.maskedKey || 'No Key')})</span>
            <div class="row-gap">
              <button class="btn btn-sm ${p.isDefault ? 'btn-primary' : 'btn-outline'}"
                onclick="window.AiManagerModule.toggle('${p.id}', true, true, ${snap.multiAiMode})">
                ${p.isDefault ? 'Default' : 'Set Default'}
              </button>
              <button class="btn btn-sm btn-ghost" onclick="navigateTo('api_manager')">API Key</button>
            </div>
          </div>
        </div>
      `;
    }).join('');

    const errorRows = errors.length
      ? errors.slice(0, 8).map(e => `
          <div class="control-item">
            <div>
              <strong>${escapeHtml(e.provider)}</strong>
              <div class="muted">${escapeHtml(e.message)}</div>
            </div>
          </div>
        `).join('')
      : '<p class="muted">No API errors recorded.</p>';

    return `
      <div class="card">
        <div class="row-between">
          <div>
            <h2 class="section-title">AI Manager (4 Providers)</h2>
            <p class="muted">Gemini (Built-in App Check), ChatGPT, Claude &amp; DeepSeek</p>
          </div>
          <button class="switch-btn ${snap.multiAiMode ? 'on' : 'off'}"
            onclick="window.AiManagerModule.toggle('${snap.defaultProvider}', true, true, ${!snap.multiAiMode})">
            Multi-AI: ${snap.multiAiMode ? 'ON' : 'OFF'}
          </button>
        </div>
      </div>
      ${cards}
      <div class="card">
        <div class="row-between" style="margin-bottom:8px;">
          <strong>API Error History</strong>
          <button class="btn btn-sm btn-outline" onclick="window.AiManagerModule.clearErrors()">Clear</button>
        </div>
        ${errorRows}
      </div>
    `;
  },

  toggle: function (id, enabled, isDefault, multiAi) {
    if (window.AndroidBridge) {
      const updated = JSON.parse(window.AndroidBridge.setProviderConfig(id, enabled, isDefault, multiAi));
      window.AppState.aiSnapshot = updated;
      renderCurrentPage();
    }
  },

  clearErrors: function () {
    if (window.AndroidBridge) {
      const updated = JSON.parse(window.AndroidBridge.clearApiErrors());
      window.AppState.aiSnapshot = updated;
      renderCurrentPage();
    }
  }
};
