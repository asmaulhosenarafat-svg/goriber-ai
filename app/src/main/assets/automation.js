window.AutomationModule = {
  masterEnabled: true,

  render: function (state) {
    const rules = (state.synced && state.synced.automations) || [];
    const rulesHtml = rules.length
      ? rules.map(r => `
          <div class="control-item">
            <div style="flex:1;">
              <div><strong>${escapeHtml(r.title)}</strong></div>
              <div class="muted">${escapeHtml(r.triggerType)} (${escapeHtml(r.triggerValue)}) → ${escapeHtml(r.actionCommand)}</div>
            </div>
            <div class="row-gap">
              <button class="switch-btn ${r.enabled ? 'on' : 'off'}"
                onclick="window.AutomationModule.toggleRule('${r.id}', '${escapeHtml(r.title)}', '${r.triggerType}', '${escapeHtml(r.triggerValue)}', '${escapeHtml(r.actionCommand)}', ${!r.enabled})">
                ${r.enabled ? 'ON' : 'OFF'}
              </button>
              <button class="btn btn-sm btn-danger" onclick="window.AutomationModule.deleteRule('${r.id}')">✕</button>
            </div>
          </div>
        `).join('')
      : '<p class="muted">No automation rules yet. Create a scheduled task or battery/storage alert below.</p>';

    return `
      <div class="card">
        <div class="row-between">
          <div>
            <h2 class="section-title">Automation Manager</h2>
            <p class="muted">Daily Automation, Scheduled Tasks, Battery/Storage/Network Alerts &amp; Reminders</p>
          </div>
          <button class="switch-btn ${this.masterEnabled ? 'on' : 'off'}" onclick="window.AutomationModule.toggleMaster()">
            ${this.masterEnabled ? 'ON' : 'OFF'}
          </button>
        </div>
      </div>

      <div class="card">
        <strong>Create Quick Automation</strong>
        <div style="display:flex; flex-direction:column; gap:10px; margin-top:10px;">
          <input id="autoTitle" class="input" placeholder="Rule title (e.g. Low Battery Saver)" />
          <select id="autoType" class="select">
            <option value="daily">Daily Automation</option>
            <option value="scheduled">Scheduled Task</option>
            <option value="battery_low">Battery Alert (&lt;20%)</option>
            <option value="storage_low">Storage Alert</option>
            <option value="network_change">Network Alert</option>
            <option value="app_launch">Scheduled App Launch Reminder</option>
            <option value="custom">Custom Automation</option>
          </select>
          <input id="autoTriggerVal" class="input" placeholder="Time / Threshold (e.g. 08:30 or 20%)" />
          <input id="autoCommand" class="input" placeholder="Action Command (e.g. Battery Saver / Wi-Fi)" />
          <div class="row-gap">
            <button class="btn btn-primary" onclick="window.AutomationModule.saveNew()">Save Automation</button>
            <button class="btn btn-outline" onclick="window.AutomationModule.testAlert()">Test Alert Notification</button>
          </div>
        </div>
      </div>

      <div class="card">
        <strong>Saved Rules (Cloud Synced)</strong>
        <div style="margin-top:10px;">${rulesHtml}</div>
      </div>
    `;
  },

  toggleMaster: function () {
    this.masterEnabled = !this.masterEnabled;
    showToast('Automation Engine ' + (this.masterEnabled ? 'Enabled' : 'Paused'));
    renderCurrentPage();
  },

  saveNew: function () {
    const title = document.getElementById('autoTitle').value.trim();
    const type = document.getElementById('autoType').value;
    const val = document.getElementById('autoTriggerVal').value.trim() || 'Daily';
    const cmd = document.getElementById('autoCommand').value.trim();
    if (!title || !cmd) {
      showToast('Enter a title and action command.');
      return;
    }
    window.AndroidBridge && window.AndroidBridge.saveAutomationRule('', title, type, val, cmd, true);
    showToast('Automation rule saved.');
  },

  toggleRule: function (id, title, type, val, cmd, enabled) {
    window.AndroidBridge && window.AndroidBridge.saveAutomationRule(id, title, type, val, cmd, enabled);
  },

  deleteRule: function (id) {
    window.AndroidBridge && window.AndroidBridge.deleteAutomationRule(id);
    showToast('Rule deleted.');
  },

  testAlert: function () {
    window.AndroidBridge && window.AndroidBridge.triggerTestAlert('AI Controller Alert', 'Battery & Storage monitor is active.');
  }
};
