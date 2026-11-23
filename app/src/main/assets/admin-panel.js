window._adminInspectCallbacks = {};

window.onNativeAdminInspect = function (cbId, jsonStr) {
  const cb = window._adminInspectCallbacks[cbId];
  delete window._adminInspectCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

window.AdminPanelModule = {
  render(s) {
    if (!s.synced.isAdmin) {
      return '<div class="card"><strong>Access Denied</strong><p class="muted">Verified Admin account required.</p></div>';
    }
    if (s.aiSnapshot.adminPinSet && !s.adminUnlocked) {
      return `
        <div class="gold-hero">
          <span class="crown-badge">🛡 ADMIN SECURITY VAULT</span>
          <h2 class="gold-title" style="margin-top:8px;">Unlock Admin Control Room</h2>
          <p class="muted">Enter your secondary Admin Vault PIN to access user moderation, packages, payments &amp; audit logs.</p>
          <div class="row-gap" style="margin-top:12px;">
            <input id="adminUnlockPin" type="password" class="input" style="flex:1;" placeholder="Enter Admin Vault PIN" />
            <button class="btn btn-gold" onclick="unlockAdminVault()">Unlock</button>
          </div>
        </div>
      `;
    }

    const tab = s.adminTab || 'overview';
    const users = s.synced.adminUsers || [];
    const pays = s.synced.adminPayments || [];
    const pkgs = s.synced.packages || [];
    const features = s.synced.featureCatalog || [];
    const cfg = s.synced.appConfig || {};
    const auditLogs = s.synced.adminAuditLogs || [];

    const activePremiumUsers = users.filter(u => u.premiumStatus === 'active' && !u.banned).length;
    const bannedUsers = users.filter(u => u.banned).length;
    const pendingPays = pays.filter(p => p.status === 'pending');
    const refundReqs = pays.filter(p => p.status === 'refund_requested');
    const approvedRevenue = pays.filter(p => p.status === 'approved').reduce((acc, p) => acc + (p.amountBdt || 0), 0);

    // Duplicate TrxID detector
    const trxCounts = {};
    pays.forEach(p => {
      const k = (p.transactionId || '').trim().toUpperCase();
      if (k) trxCounts[k] = (trxCounts[k] || 0) + 1;
    });

    return `
      <div class="gold-hero">
        <div class="row-between">
          <div>
            <span class="crown-badge">👑 ADMIN CONTROL ROOM</span>
            <h2 class="gold-title" style="margin-top:6px;">অ্যাডমিন প্যানেল ও প্রিমিয়াম কন্ট্রোল</h2>
            <p class="muted">অ্যাডমিন মানে হলো পুরো অ্যাপের মালিক/নিয়ন্ত্রক—যিনি প্যাকেজ, দাম, ১৫টি ফিচার, ইউজার প্রিমিয়াম, ব্যান, পেমেন্ট ও রিফান্ড নিয়ন্ত্রণ করেন।</p>
          </div>
          <button class="btn btn-sm btn-outline" onclick="window.AppState.adminUnlocked=false; renderCurrentPage();">Lock</button>
        </div>
        <div class="tab-strip" style="margin-top:14px;">
          ${[
            ['overview', 'Overview'],
            ['users', 'Users & Ban (' + users.length + ')'],
            ['packages', 'Packages & Features'],
            ['payments', 'Payments (' + pendingPays.length + ')'],
            ['audit', 'Audit Logs (' + auditLogs.length + ')']
          ].map(([id, label]) => `
            <button class="tab-btn ${tab === id ? 'active' : ''}" onclick="window.AppState.adminTab='${id}'; renderCurrentPage();">${label}</button>
          `).join('')}
        </div>
      </div>

      ${tab === 'overview' ? this.renderOverview(users, activePremiumUsers, bannedUsers, pendingPays.length, refundReqs.length, approvedRevenue, auditLogs) : ''}
      ${tab === 'users' ? this.renderUsersTab(s, users, pkgs) : ''}
      ${tab === 'packages' ? this.renderPackagesAndFeaturesTab(s, pkgs, features, cfg) : ''}
      ${tab === 'payments' ? this.renderPaymentsTab(pays, trxCounts) : ''}
      ${tab === 'audit' ? this.renderAuditTab(auditLogs) : ''}
    `;
  },

  renderOverview(users, activePrem, banned, pendingCnt, refundCnt, revenue, auditLogs) {
    return `
      <div class="gold-card">
        <div class="grid-2">
          <div class="stat-tile"><div class="stat-label">Total Users</div><div class="stat-val">${users.length}</div></div>
          <div class="stat-tile"><div class="stat-label">Active Premium</div><div class="stat-val" style="color:#f5c451;">${activePrem}</div></div>
          <div class="stat-tile"><div class="stat-label">Verified Revenue</div><div class="stat-val" style="color:#10b981;">৳${revenue}</div></div>
          <div class="stat-tile"><div class="stat-label">Pending / Refunds</div><div class="stat-val" style="color:#f59e0b;">${pendingCnt} / ${refundCnt}</div></div>
          <div class="stat-tile"><div class="stat-label">Banned Users</div><div class="stat-val" style="color:#f43f5e;">${banned}</div></div>
          <div class="stat-tile"><div class="stat-label">Admin Actions</div><div class="stat-val">${auditLogs.length}</div></div>
        </div>
      </div>
      <div class="gold-card">
        <strong class="gold-title">Quick Admin Role Guide (অ্যাডমিনের ১৫+ ক্ষমতা)</strong>
        <ul class="muted" style="padding-left:18px; margin-top:8px;">
          <li>যেকোনো ইউজারকে <strong>বিনামূল্যে প্রিমিয়াম Gift</strong>, Lifetime বা কাস্টম দিন (+7/+30/-7) দেওয়া ও কমানো।</li>
          <li>যেকোনো ইউজারের প্রিমিয়াম <strong>Activate, Suspend বা Revoke</strong> করা এবং নিয়ম ভঙ্গকারীকে <strong>Ban / Unban</strong> করা।</li>
          <li>৬টি প্যাকেজের (৭/৩০/৯০/১৮০/৩৬৫ দিন ও Lifetime) মূল্য, নাম, Play Product ID ও নতুন প্যাকেজ তৈরি করা।</li>
          <li>১৫টি প্রিমিয়াম ফিচারের যেকোনোটি গ্লোবালি <strong>ON/OFF</strong> এবং ফ্রি লিমিট নির্ধারণ করা।</li>
          <li>bKash, Nagad, Rocket, Play Store পেমেন্ট ও রিফান্ড রিকোয়েস্ট যাচাই (ডুপ্লিকেট TrxID সতর্কতাসহ) এবং প্রতিটি কাজের <strong>Audit Log</strong> দেখা।</li>
        </ul>
      </div>
    `;
  },

  renderUsersTab(s, users, pkgs) {
    const q = (s.adminUserSearch || '').toLowerCase();
    const filtered = users.filter(u =>
      !q || (u.displayName || '').toLowerCase().includes(q) || (u.userEmail || '').toLowerCase().includes(q) || (u.premiumStatus || '').toLowerCase().includes(q)
    );
    const giftPkgOpts = pkgs.map(p => `<option value="${escapeHtml(p.packageId)}">${escapeHtml(p.name)} (৳${p.priceBdt})</option>`).join('');

    return `
      <div class="gold-card">
        <input class="input" placeholder="Search users by name, email, status..." value="${escapeHtml(s.adminUserSearch || '')}"
          oninput="window.AppState.adminUserSearch=this.value; renderCurrentPage();" />
        <div class="row-gap" style="margin-top:10px;">
          <span class="muted">Gift Package Selection:</span>
          <select id="adminGiftPkgSelect" class="select" style="flex:1;">${giftPkgOpts}</select>
        </div>
      </div>

      ${s.inspectedUser ? `
        <div class="gold-card" style="border-color:var(--gold-bright);">
          <div class="row-between">
            <strong class="gold-title">User Inspector: ${escapeHtml(s.inspectedUser.profile?.email || '')}</strong>
            <button class="btn btn-sm btn-ghost" onclick="window.AppState.inspectedUser=null; renderCurrentPage();">Close</button>
          </div>
          <div class="muted" style="margin-top:6px;">
            Package: <strong>${escapeHtml(s.inspectedUser.profile?.packageName || 'Free')}</strong> •
            Status: <strong>${escapeHtml(s.inspectedUser.profile?.premiumStatus || 'free')}</strong> •
            Sub ID: <strong>${escapeHtml(s.inspectedUser.profile?.subscriptionId || 'NONE')}</strong> •
            Remaining: <strong>${s.inspectedUser.profile?.remainingDays || 0}d</strong>
          </div>
          <div style="margin-top:8px;">
            ${(s.inspectedUser.logs || []).slice(0, 8).map(l => `
              <div class="muted" style="font-size:12px;">• #${l.controlId} ${escapeHtml(l.actionName)} (${escapeHtml(l.aiProvider)}) ${l.success ? '✓' : '✕'}</div>
            `).join('') || '<span class="muted">No recent activity logs.</span>'}
          </div>
        </div>
      ` : ''}

      <div class="gold-card">
        ${filtered.map(u => `
          <div class="pkg-card" style="margin-bottom:10px; border-color:${u.banned ? '#f43f5e' : (u.premiumStatus === 'active' ? 'var(--gold)' : 'var(--border)')};">
            <div class="row-between">
              <div>
                <strong>${escapeHtml(u.displayName)}</strong>
                <span class="chip ${u.premiumStatus === 'active' ? 'active' : ''}">${escapeHtml((u.premiumStatus || 'free').toUpperCase())}</span>
                ${u.banned ? '<span class="chip" style="color:#fb7185; border-color:#f43f5e;">BANNED</span>' : ''}
                <div class="muted" style="font-size:12px;">${escapeHtml(u.userEmail)} • ${escapeHtml(u.deviceModel)}</div>
                <div class="muted" style="font-size:12px;">Pkg: <strong>${escapeHtml(u.packageName || 'Free')}</strong> • Cmds: ${u.commandsCount || 0} • AI: ${u.aiQueriesCount || 0}</div>
              </div>
              <button class="btn btn-sm btn-outline" onclick="window.AdminPanelModule.inspectUser('${escapeHtml(u.userId)}')">Inspect</button>
            </div>
            <div class="row-gap" style="margin-top:10px;">
              <button class="btn btn-sm btn-gold" onclick="window.AdminPanelModule.giftSelected('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}')">🎁 Gift Pack</button>
              <button class="btn btn-sm btn-outline" onclick="window.AndroidBridge.adminGiftOrGrantPackage('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}','pkg_lifetime',true)">👑 Lifetime</button>
              <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge.adminAdjustPremiumDays('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}',7)">+7d</button>
              <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge.adminAdjustPremiumDays('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}',30)">+30d</button>
              <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge.adminAdjustPremiumDays('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}',-7)">-7d</button>
              ${u.premiumStatus === 'active'
                ? `<button class="btn btn-sm btn-outline" onclick="window.AndroidBridge.adminSetUserPremiumStatus('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}','suspended')">Suspend</button>
                   <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge.adminSetUserPremiumStatus('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}','free')">Revoke</button>`
                : `<button class="btn btn-sm btn-primary" onclick="window.AndroidBridge.adminSetUserPremiumStatus('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}','active')">Activate</button>`}
              ${u.banned
                ? `<button class="btn btn-sm btn-primary" onclick="window.AndroidBridge.adminBanUser('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}',false,'')">Unban</button>`
                : `<button class="btn btn-sm btn-danger" onclick="window.AdminPanelModule.promptBan('${escapeHtml(u.userId)}','${escapeHtml(u.userEmail)}')">Ban User</button>`}
            </div>
          </div>
        `).join('') || '<p class="muted">No matching users.</p>'}
      </div>
    `;
  },

  renderPackagesAndFeaturesTab(s, pkgs, features, cfg) {
    const flags = cfg.featureFlags || {};
    return `
      <!-- Package Creator / Editor -->
      <div class="gold-card">
        <div class="row-between" style="margin-bottom:8px;">
          <strong class="gold-title">Create / Edit Premium Package</strong>
          <button class="btn btn-sm btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.adminSeedDefaultPackages()">Sync 6 Default Packs</button>
        </div>
        <div style="display:flex; flex-direction:column; gap:8px;">
          <input id="pkgIdIn" class="input" placeholder="Package ID (e.g. pkg_30d or pkg_vip)" value="${escapeHtml(s.editPkg?.packageId || '')}" />
          <div class="row-gap">
            <input id="pkgNameIn" class="input" style="flex:1;" placeholder="Name (e.g. 30 Days Premium)" value="${escapeHtml(s.editPkg?.name || '')}" />
            <input id="pkgNameBnIn" class="input" style="flex:1;" placeholder="Bengali Name (৩০ দিনের প্রিমিয়াম)" value="${escapeHtml(s.editPkg?.nameBn || '')}" />
          </div>
          <div class="row-gap">
            <input id="pkgDaysIn" type="number" class="input" style="flex:1;" placeholder="Days (0 for Lifetime)" value="${s.editPkg ? s.editPkg.durationDays : 30}" />
            <input id="pkgPriceIn" type="number" class="input" style="flex:1;" placeholder="Price BDT" value="${s.editPkg ? s.editPkg.priceBdt : 299}" />
          </div>
          <div class="row-gap">
            <input id="pkgPlayIn" class="input" style="flex:1;" placeholder="Google Play Product ID (e.g. goriber_ai_30d)" value="${escapeHtml(s.editPkg?.playProductId || '')}" />
            <select id="pkgTierIn" class="select" style="width:auto;">
              <option value="pro" ${s.editPkg?.tier !== 'enterprise' ? 'selected' : ''}>Pro Tier</option>
              <option value="enterprise" ${s.editPkg?.tier === 'enterprise' ? 'selected' : ''}>Enterprise / Lifetime</option>
            </select>
          </div>
          <input id="pkgDescIn" class="input" placeholder="Package description & features" value="${escapeHtml(s.editPkg?.description || '')}" />
          <div class="row-gap">
            <label class="chip"><input id="pkgLifeIn" type="checkbox" ${s.editPkg?.lifetimePlan ? 'checked' : ''} /> Lifetime</label>
            <label class="chip"><input id="pkgActiveIn" type="checkbox" ${s.editPkg?.active !== false ? 'checked' : ''} /> Active</label>
            <button class="btn btn-gold" onclick="window.AdminPanelModule.savePackage()">Save Package</button>
          </div>
        </div>

        <div style="margin-top:14px;">
          ${pkgs.map(p => `
            <div class="control-item">
              <div style="flex:1;">
                <strong>${escapeHtml(p.name)}</strong> — ৳${p.priceBdt} (${p.lifetimePlan ? 'Lifetime' : p.durationDays + 'd'})
                <div class="muted" style="font-size:12px;">ID: ${escapeHtml(p.packageId)} • Play SKU: ${escapeHtml(p.playProductId)} • ${p.active ? 'Active' : 'Hidden'}</div>
              </div>
              <div class="row-gap">
                <button class="btn btn-sm btn-outline" onclick='window.AppState.editPkg=${JSON.stringify(p)}; renderCurrentPage();'>Edit</button>
                <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge.adminDeletePackage('${escapeHtml(p.packageId)}')">Del</button>
              </div>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- 15 Premium Features Global ON/OFF + Payment Config -->
      <div class="gold-card">
        <strong class="gold-title">Global Payment Numbers, Limits &amp; 15 Feature Toggles</strong>
        <div style="display:flex; flex-direction:column; gap:8px; margin-top:10px;">
          <input id="cfgFreeLimit" type="number" class="input" placeholder="Free Daily AI Limit" value="${cfg.freeDailyAiLimit || 15}" />
          <input id="cfgBkash" class="input" placeholder="Merchant bKash Number" value="${escapeHtml(cfg.merchantBkashNumber || '')}" />
          <input id="cfgNagad" class="input" placeholder="Merchant Nagad Number" value="${escapeHtml(cfg.merchantNagadNumber || '')}" />
          <input id="cfgRocket" class="input" placeholder="Merchant Rocket Number" value="${escapeHtml(cfg.merchantRocketNumber || '')}" />
          <input id="cfgWebhook" class="input" placeholder="Optional Verification Webhook URL (https://...)" value="${escapeHtml(cfg.webhookVerifyUrl || '')}" />
          <input id="cfgBanner" class="input" placeholder="Promo Notice" value="${escapeHtml(cfg.promoBanner || '')}" />
        </div>
        <div style="margin-top:12px;">
          ${features.map(f => {
            const on = flags[f.key] !== false;
            return `
              <div class="control-item">
                <div style="flex:1;"><strong>${escapeHtml(f.titleBn)}</strong><div class="muted" style="font-size:11px;">${escapeHtml(f.titleEn)}</div></div>
                <button class="switch-btn ${on ? 'on' : 'off'}" onclick="window.AdminPanelModule.toggleFeatureFlag('${escapeHtml(f.key)}', ${!on})">${on ? 'ON' : 'OFF'}</button>
              </div>
            `;
          }).join('')}
        </div>
        <button class="btn btn-gold" style="width:100%; margin-top:10px;" onclick="window.AdminPanelModule.saveAppConfig()">Save All Config &amp; Feature Flags</button>
      </div>
    `;
  },

  renderPaymentsTab(pays, trxCounts) {
    return `
      <div class="gold-card">
        <strong class="gold-title">Payment &amp; Refund Queue (${pays.length})</strong>
        <div style="margin-top:10px;">
          ${pays.map(p => {
            const trxKey = (p.transactionId || '').trim().toUpperCase();
            const dup = trxCounts[trxKey] > 1;
            return `
              <div class="pkg-card" style="margin-bottom:10px; border-color:${p.status === 'pending' || p.status === 'refund_requested' ? 'var(--gold)' : 'var(--border)'};">
                <div>
                  <strong>${escapeHtml(p.userEmail)}</strong> • ৳${p.amountBdt} (${escapeHtml(p.packageName || p.planId)})
                  ${dup ? '<span class="chip" style="color:#fb7185; border-color:#f43f5e;">⚠ Duplicate TrxID</span>' : ''}
                </div>
                <div class="muted" style="font-size:12px;">${escapeHtml((p.method || '').toUpperCase())} • ${escapeHtml(p.senderNumber)} • Trx: <strong>${escapeHtml(p.transactionId)}</strong></div>
                <div class="muted" style="font-size:12px;">Status: <strong>${escapeHtml((p.status || '').toUpperCase())}</strong> ${p.refundReason ? '• Refund Reason: ' + escapeHtml(p.refundReason) : ''}</div>
                <div class="row-gap" style="margin-top:8px;">
                  ${p.status === 'pending' ? `
                    <button class="btn btn-sm btn-gold" onclick="window.AndroidBridge.adminReviewPaymentV2('${escapeHtml(p.id)}','approved','Verified by Admin')">Approve &amp; Activate</button>
                    <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge.adminReviewPaymentV2('${escapeHtml(p.id)}','rejected','Invalid TrxID')">Reject</button>
                  ` : ''}
                  ${p.status === 'refund_requested' ? `
                    <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge.adminReviewPaymentV2('${escapeHtml(p.id)}','refunded','Refund processed & subscription cancelled')">Approve Refund</button>
                    <button class="btn btn-sm btn-outline" onclick="window.AndroidBridge.adminReviewPaymentV2('${escapeHtml(p.id)}','approved','Refund declined')">Decline Refund</button>
                  ` : ''}
                </div>
              </div>
            `;
          }).join('') || '<p class="muted">No payment records yet.</p>'}
        </div>
      </div>
    `;
  },

  renderAuditTab(auditLogs) {
    return `
      <div class="gold-card">
        <strong class="gold-title">Immutable Admin Audit Logs</strong>
        <div style="margin-top:10px;">
          ${auditLogs.map(a => `
            <div class="control-item">
              <div style="flex:1;">
                <div><span class="control-id">${escapeHtml(a.actionType)}</span> <strong>${escapeHtml(a.targetLabel)}</strong></div>
                <div class="muted" style="font-size:12px;">${escapeHtml(a.details)}</div>
                <div class="muted" style="font-size:11px;">By: ${escapeHtml(a.adminEmail)} • ${window.PremiumManagerModule.formatDate(a.timestampMs)}</div>
              </div>
            </div>
          `).join('') || '<p class="muted">No admin audit logs recorded yet.</p>'}
        </div>
      </div>
    `;
  },

  giftSelected(uid, email) {
    const pkgId = document.getElementById('adminGiftPkgSelect')?.value || 'pkg_30d';
    window.AndroidBridge && window.AndroidBridge.adminGiftOrGrantPackage(uid, email, pkgId, true);
  },

  promptBan(uid, email) {
    const reason = window.prompt('Ban reason for ' + email + ':', 'Policy violation / Abuse');
    if (reason === null) return;
    window.AndroidBridge && window.AndroidBridge.adminBanUser(uid, email, true, reason);
  },

  inspectUser(uid) {
    const cbId = 'insp_' + Date.now();
    window._adminInspectCallbacks[cbId] = function (res) {
      if (res.ok) {
        window.AppState.inspectedUser = res;
        renderCurrentPage();
      } else {
        showToast(res.error || 'Could not inspect user.');
      }
    };
    window.AndroidBridge && window.AndroidBridge.adminInspectUser(uid, cbId);
  },

  savePackage() {
    const payload = {
      packageId: (document.getElementById('pkgIdIn')?.value || '').trim(),
      name: (document.getElementById('pkgNameIn')?.value || '').trim(),
      nameBn: (document.getElementById('pkgNameBnIn')?.value || '').trim(),
      durationDays: parseInt(document.getElementById('pkgDaysIn')?.value || '30', 10) || 0,
      priceBdt: parseInt(document.getElementById('pkgPriceIn')?.value || '299', 10) || 299,
      playProductId: (document.getElementById('pkgPlayIn')?.value || '').trim(),
      tier: document.getElementById('pkgTierIn')?.value || 'pro',
      description: (document.getElementById('pkgDescIn')?.value || '').trim(),
      lifetimePlan: !!document.getElementById('pkgLifeIn')?.checked,
      active: !!document.getElementById('pkgActiveIn')?.checked,
      sortOrder: 10
    };
    if (!payload.packageId || !payload.name) {
      showToast('Package ID এবং Name দিন।');
      return;
    }
    window.AppState.editPkg = null;
    window.AndroidBridge && window.AndroidBridge.adminSavePackage(JSON.stringify(payload));
  },

  toggleFeatureFlag(key, nextVal) {
    const cfg = window.AppState.synced.appConfig || {};
    cfg.featureFlags = cfg.featureFlags || {};
    cfg.featureFlags[key] = nextVal;
    window.AppState.synced.appConfig = cfg;
    renderCurrentPage();
  },

  saveAppConfig() {
    const cfg = window.AppState.synced.appConfig || {};
    const payload = {
      freeDailyAiLimit: parseInt(document.getElementById('cfgFreeLimit')?.value || '15', 10) || 15,
      merchantBkashNumber: (document.getElementById('cfgBkash')?.value || '').trim(),
      merchantNagadNumber: (document.getElementById('cfgNagad')?.value || '').trim(),
      merchantRocketNumber: (document.getElementById('cfgRocket')?.value || '').trim(),
      webhookVerifyUrl: (document.getElementById('cfgWebhook')?.value || '').trim(),
      promoBanner: (document.getElementById('cfgBanner')?.value || '').trim(),
      featureFlags: cfg.featureFlags || {}
    };
    window.AndroidBridge && window.AndroidBridge.adminSaveAppConfig(JSON.stringify(payload));
  }
};
