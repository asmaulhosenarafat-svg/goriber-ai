window.FloatingControllerModule = {
  colorHex: '#06B6D4',
  sizeDp: 56,

  render: function (state) {
    const perms = state.permissions || [];
    const overlayPerm = perms.find(p => p.id === 'overlay');
    const hasOverlay = overlayPerm ? overlayPerm.granted : false;
    const isRunning = state.telemetry && state.telemetry.floatingRunning;

    const colors = ['#06B6D4', '#6366F1', '#10B981', '#F59E0B', '#F43F5E'];
    const colorChips = colors.map(c => `
      <button class="chip ${this.colorHex === c ? 'active' : ''}"
        style="border-color:${c}; color:${c};"
        onclick="window.FloatingControllerModule.setColor('${c}')">${c}</button>
    `).join('');

    return `
      <div class="card">
        <div class="row-between">
          <div>
            <h2 class="section-title">Floating Controller</h2>
            <p class="muted">Draggable compact &amp; expanded AI overlay over other apps via Foreground Service.</p>
          </div>
          <span class="chip ${isRunning ? 'active' : ''}">${isRunning ? 'ACTIVE' : 'STOPPED'}</span>
        </div>
      </div>

      <div class="card">
        <div class="row-between">
          <div>
            <strong>1. Overlay Permission</strong>
            <div class="muted">Required before Floating Controller can start.</div>
          </div>
          <button class="switch-btn ${hasOverlay ? 'on' : 'off'}"
            onclick="window.PermissionManagerModule.handleToggle('overlay')">
            ${hasOverlay ? 'GRANTED' : 'ENABLE'}
          </button>
        </div>
      </div>

      <div class="card">
        <strong>2. Service &amp; Visibility Controls</strong>
        <div class="row-gap" style="margin-top:12px;">
          <button class="btn btn-primary" onclick="window.FloatingControllerModule.cmd('start')">Start Floating Controller</button>
          <button class="btn btn-outline" onclick="window.FloatingControllerModule.cmd('show')">Show</button>
          <button class="btn btn-outline" onclick="window.FloatingControllerModule.cmd('hide')">Hide</button>
          <button class="btn btn-danger" onclick="window.FloatingControllerModule.cmd('stop')">Stop Service</button>
        </div>
      </div>

      <div class="card">
        <strong>3. Customize Bubble Color &amp; Size (${this.sizeDp}dp)</strong>
        <div class="row-gap" style="margin:10px 0;">${colorChips}</div>
        <input type="range" class="range-slider" min="44" max="80" value="${this.sizeDp}"
          oninput="window.FloatingControllerModule.setSize(this.value)" />
        <div style="margin-top:14px; display:flex; align-items:center; gap:14px;">
          <div style="width:${this.sizeDp}px; height:${this.sizeDp}px; border-radius:50%; background:${this.colorHex}; border:2px solid #fff; display:flex; align-items:center; justify-content:center; font-weight:700; color:#fff;">AI</div>
          <span class="muted">Live Floating Bubble Preview</span>
        </div>
      </div>
    `;
  },

  setColor: function (hex) {
    this.colorHex = hex;
    this.cmd('style');
    renderCurrentPage();
  },

  setSize: function (val) {
    this.sizeDp = parseInt(val, 10) || 56;
    this.cmd('style');
  },

  cmd: function (action) {
    if (!window.AndroidBridge) return;
    const res = JSON.parse(window.AndroidBridge.controlFloating(action, this.colorHex, this.sizeDp));
    if (!res.success && res.needsPermission === 'overlay') {
      window.PermissionManagerModule.handleToggle('overlay');
    } else {
      showToast(res.message);
      refreshNativeSnapshot();
    }
  }
};
