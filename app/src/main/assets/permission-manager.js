window.PermissionManagerModule = {
  render: function (state) {
    const perms = state.permissions || [];
    const grantedCount = perms.filter(p => p.granted).length;

    const rows = perms.map(p => `
      <div class="control-item">
        <div style="flex:1;">
          <div><strong>${escapeHtml(p.title)}</strong> <span class="muted">(${escapeHtml(p.titleBn)})</span></div>
          <div class="muted">${escapeHtml(p.description)}</div>
        </div>
        <div class="row-gap">
          <button class="switch-btn ${p.granted ? 'on' : 'off'}"
            onclick="window.PermissionManagerModule.handleToggle('${p.id}')">
            ${p.granted ? 'ON' : 'OFF'}
          </button>
        </div>
      </div>
    `).join('');

    return `
      <div class="card">
        <div class="row-between">
          <div>
            <h2 class="section-title">Permission Center (15 Controls)</h2>
            <p class="muted">Android grants/revokes real permissions only through system dialogs or Settings.</p>
          </div>
          <span class="chip active">${grantedCount}/${perms.length} ON</span>
        </div>
      </div>
      <div class="card">
        ${rows || '<p class="muted">Loading permissions...</p>'}
      </div>
    `;
  },

  handleToggle: function (permId) {
    const perm = (window.AppState.permissions || []).find(p => p.id === permId);
    if (!perm) return;
    if (perm.granted) {
      openPermModal(
        perm.title + ' (Manage in Settings)',
        'Android does not allow apps to revoke permissions silently. Open App Settings to turn this permission OFF.',
        function () {
          window.AndroidBridge && window.AndroidBridge.openPermissionSettings(permId);
        }
      );
    } else {
      openPermModal(
        perm.title,
        perm.rationale,
        function () {
          window.AndroidBridge && window.AndroidBridge.requestPermission(permId);
        }
      );
    }
  }
};
