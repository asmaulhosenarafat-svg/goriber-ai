window._codeCallbacks = {};
window._assistantCallbacks = {};
window._fileAnalysisCallbacks = {};

window.onNativeCodeGenResponse = function (cbId, jsonStr) {
  const cb = window._codeCallbacks[cbId];
  delete window._codeCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

window.onNativeCustomAssistantReply = function (cbId, jsonStr) {
  const cb = window._assistantCallbacks[cbId];
  delete window._assistantCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

window.onNativeFileAnalysis = function (cbId, jsonStr) {
  const cb = window._fileAnalysisCallbacks[cbId];
  delete window._fileAnalysisCallbacks[cbId];
  if (cb) cb(JSON.parse(jsonStr));
};

window.PremiumManagerModule = {
  formatDate(ms) {
    if (!ms || ms <= 0) return 'N/A';
    try {
      return new Date(ms).toLocaleDateString('en-GB', {
        day: '2-digit', month: 'short', year: 'numeric'
      });
    } catch (e) {
      return 'N/A';
    }
  },

  renderDashboard(s) {
    const synced = s.synced || {};
    const prof = synced.profile || {};
    const pkgs = (synced.packages || []).filter(p => p.active !== false);
    const features = synced.featureCatalog || [];
    const cfg = synced.appConfig || {};
    const myPays = synced.myPayments || [];
    const play = synced.playBilling || {};
    const isPrem = !!synced.isEffectivePremium;

    if (!s.selectedPackageId && pkgs.length) {
      s.selectedPackageId = (pkgs.find(p => p.packageId === 'pkg_30d') || pkgs[0]).packageId;
    }
    const selectedPkg = pkgs.find(p => p.packageId === s.selectedPackageId) || pkgs[0] || {
      packageId: 'pkg_30d', name: '30 Days Premium', nameBn: '৩০ দিনের প্রিমিয়াম', durationDays: 30, priceBdt: 299, playProductId: 'goriber_ai_30d'
    };

    const statusUpper = (prof.premiumStatus || 'free').toUpperCase();
    const remDays = prof.lifetimePlan ? 'Lifetime (∞)' : (isPrem ? (prof.remainingDays || 0) + ' Days' : '0 Days');
    const totalDays = selectedPkg.durationDays > 0 ? selectedPkg.durationDays : 30;
    const pct = prof.lifetimePlan ? 100 : (isPrem ? Math.min(100, Math.max(8, Math.round(((prof.remainingDays || 1) * 100) / Math.max(1, totalDays)))) : 0);

    const statusColor = isPrem ? '#10b981' : (prof.premiumStatus === 'suspended' ? '#f43f5e' : '#f5c451');

    return `
      <!-- 1. Black & Gold Crown Hero Dashboard -->
      <div class="gold-hero">
        <div class="row-between">
          <div>
            <span class="crown-badge">👑 GORIBER AI ROYAL PREMIUM</span>
            <h2 style="margin:8px 0 2px;" class="gold-title">${escapeHtml(prof.packageName || 'Free Account')}</h2>
            <p class="muted">Status: <strong style="color:${statusColor};">${escapeHtml(statusUpper)}</strong> • Tier: <strong>${escapeHtml((prof.planTier || 'free').toUpperCase())}</strong> ${prof.giftedByAdmin ? '• 🎁 Gifted' : ''}</p>
          </div>
          <button class="btn btn-sm btn-gold" onclick="document.getElementById('packagesSection')?.scrollIntoView({behavior:'smooth'})">
            ${isPrem ? 'Renew / Upgrade' : 'Upgrade Now'}
          </button>
        </div>

        <div class="grid-2" style="margin-top:14px;">
          <div class="stat-tile" style="background:rgba(0,0,0,0.45); border-color:rgba(212,175,55,0.3);">
            <div class="stat-label">Subscription ID</div>
            <div class="stat-val" style="font-size:13px; color:#fde68a; word-break:break-all;">${escapeHtml(prof.subscriptionId || 'NONE')}</div>
          </div>
          <div class="stat-tile" style="background:rgba(0,0,0,0.45); border-color:rgba(212,175,55,0.3);">
            <div class="stat-label">Remaining Days</div>
            <div class="stat-val" style="font-size:16px; color:#fde68a;">${remDays}</div>
          </div>
          <div class="stat-tile" style="background:rgba(0,0,0,0.45); border-color:rgba(212,175,55,0.3);">
            <div class="stat-label">Purchase Date</div>
            <div class="stat-val" style="font-size:14px; color:#faf6e8;">${this.formatDate(prof.purchasedAtMs)}</div>
          </div>
          <div class="stat-tile" style="background:rgba(0,0,0,0.45); border-color:rgba(212,175,55,0.3);">
            <div class="stat-label">Expiration Date</div>
            <div class="stat-val" style="font-size:14px; color:#faf6e8;">${prof.lifetimePlan ? 'Lifetime' : this.formatDate(prof.expiresAtMs)}</div>
          </div>
        </div>

        <div style="margin-top:12px;">
          <div class="row-between"><span class="muted">Subscription Progress</span><span style="color:#f5c451; font-size:12px;">${pct}%</span></div>
          <div class="bar-track" style="background:rgba(255,255,255,0.08);"><div class="bar-fill-gold" style="width:${pct}%;"></div></div>
        </div>

        <div class="row-gap" style="margin-top:14px;">
          <button class="btn btn-sm btn-gold" onclick="navigateTo('code_studio')">⚡ AI Code Studio</button>
          <button class="btn btn-sm btn-outline" style="border-color:rgba(212,175,55,0.5); color:#fde68a;" onclick="navigateTo('custom_assistants')">🤖 Custom AI &amp; Files</button>
          ${isPrem ? `<button class="btn btn-sm btn-danger" onclick="window.PremiumManagerModule.confirmCancelSubscription()">Cancel Subscription</button>` : ''}
        </div>
      </div>

      <!-- 2. Google Play Store Integration & Billing -->
      <div class="gold-card">
        <div class="row-between">
          <div>
            <div class="gold-title">▶ Google Play Store &amp; Play Billing</div>
            <p class="muted" style="margin-top:4px;">${escapeHtml(play.statusMessage || 'Checking Play Billing status...')}</p>
          </div>
          <span class="chip ${play.billingConnected ? 'active' : ''}">${play.billingConnected ? 'Billing Ready' : (play.playStoreInstalled ? 'Play Detected' : 'No Play Store')}</span>
        </div>
        <div class="row-gap" style="margin-top:12px;">
          <button class="btn btn-sm btn-gold" onclick="window.PremiumManagerModule.buyViaPlayStore('${escapeHtml(selectedPkg.playProductId)}')">
            Buy ${escapeHtml(selectedPkg.name)} via Play Store
          </button>
          <button class="btn btn-sm btn-outline" onclick="window.AndroidBridge && window.AndroidBridge.restorePlayPurchases()">Restore Play Purchases</button>
          <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge && window.AndroidBridge.openPlayStoreListing()">Open Play Store</button>
          <button class="btn btn-sm btn-ghost" onclick="window.AndroidBridge && window.AndroidBridge.openPlaySubscriptions()">Play Subscriptions</button>
        </div>
      </div>

      <!-- 3. 6 Configurable Premium Packages -->
      <div id="packagesSection" class="gold-card">
        <div class="row-between" style="margin-bottom:10px;">
          <div>
            <div class="gold-title">1. Choose Premium Package (${pkgs.length} Plans)</div>
            <div class="muted">7 Days, 30 Days, 90 Days, 180 Days, 365 Days &amp; Lifetime</div>
          </div>
        </div>
        <div style="display:flex; flex-direction:column; gap:10px;">
          ${pkgs.map(pl => `
            <div class="pkg-card ${s.selectedPackageId === pl.packageId ? 'selected' : ''}"
              onclick="window.AppState.selectedPackageId='${escapeHtml(pl.packageId)}'; renderCurrentPage();">
              <div class="row-between">
                <div>
                  <strong style="color:#fde68a;">${escapeHtml(pl.name)}</strong>
                  ${pl.nameBn ? `<span class="muted"> • ${escapeHtml(pl.nameBn)}</span>` : ''}
                  <div class="muted" style="font-size:12px; margin-top:2px;">${escapeHtml(pl.description || pl.featuresSummary)}</div>
                  <div class="muted" style="font-size:11px; margin-top:2px;">Play SKU: ${escapeHtml(pl.playProductId || 'N/A')} • ${pl.lifetimePlan ? 'Lifetime' : pl.durationDays + ' Days'}</div>
                </div>
                <div style="text-align:right;">
                  <div style="font-size:18px; font-weight:700; color:#f5c451;">৳${pl.priceBdt}</div>
                  <span class="chip ${s.selectedPackageId === pl.packageId ? 'active' : ''}">${s.selectedPackageId === pl.packageId ? 'Selected' : 'Select'}</span>
                </div>
              </div>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- 4. Bangladesh Mobile Banking Checkout (bKash / Nagad / Rocket) -->
      <div class="gold-card">
        <div class="gold-title">2. Bangladesh Payment (bKash / Nagad / Rocket)</div>
        <div class="row-gap" style="margin:10px 0;">
          ${[['bkash', 'bKash'], ['nagad', 'Nagad'], ['rocket', 'Rocket']].map(([m, label]) => `
            <button class="chip ${s.selectedPayMethod === m ? 'active' : ''}" onclick="window.AppState.selectedPayMethod='${m}'; renderCurrentPage();">${label}</button>
          `).join('')}
        </div>
        <div class="confirm-meta" style="margin:8px 0 12px;">
          <div>Merchant Number (${escapeHtml((s.selectedPayMethod || 'bkash').toUpperCase())}):
            <strong style="color:#f5c451;">${escapeHtml(
              s.selectedPayMethod === 'nagad' ? (cfg.merchantNagadNumber || '01700-000000')
              : s.selectedPayMethod === 'rocket' ? (cfg.merchantRocketNumber || '01700-000000')
              : (cfg.merchantBkashNumber || '01700-000000')
            )}</strong>
          </div>
          <div class="muted" style="font-size:12px;">Send <strong>৳${selectedPkg.priceBdt}</strong> for <strong>${escapeHtml(selectedPkg.name)}</strong>. Note: Manual TrxID requests stay <strong>PENDING</strong> until verified by Admin (no fake instant activation).</div>
        </div>
        <div style="display:flex; flex-direction:column; gap:10px;">
          <input id="paySenderInput" class="input" placeholder="Sender ${(s.selectedPayMethod || 'bkash').toUpperCase()} Number (e.g. 017XXXXXXXX)" />
          <input id="payTrxInput" class="input" placeholder="Transaction ID (TrxID)" />
          <button class="btn btn-gold" onclick="window.PremiumManagerModule.submitSelectedPayment('${escapeHtml(selectedPkg.packageId)}')">
            Submit ৳${selectedPkg.priceBdt} Payment for Verification
          </button>
        </div>
      </div>

      <!-- 5. 15 Premium Features Entitlement Grid -->
      <div class="gold-card">
        <div class="gold-title" style="margin-bottom:8px;">15 Premium Features (${features.filter(f => f.unlocked).length}/15 Unlocked)</div>
        <div>
          ${features.map(f => `
            <div class="control-item">
              <div style="flex:1;">
                <div><strong>${escapeHtml(f.titleBn)}</strong> <span class="muted">(${escapeHtml(f.titleEn)})</span></div>
                <div class="muted" style="font-size:12px;">${escapeHtml(f.descriptionBn)}</div>
              </div>
              ${!f.globallyEnabled
                ? '<span class="chip">Admin Off</span>'
                : (f.unlocked
                    ? `<button class="btn btn-sm btn-gold" onclick="navigateTo('${escapeHtml(f.targetPage)}')">Open</button>`
                    : '<span class="chip">🔒 Pro</span>')}
            </div>
          `).join('')}
        </div>
      </div>

      <!-- 6. Payment History & Refunds -->
      <div class="gold-card">
        <div class="gold-title" style="margin-bottom:8px;">Payment History &amp; Refund Requests</div>
        <div>
          ${myPays.length ? myPays.map(p => `
            <div class="control-item">
              <div style="flex:1;">
                <div><strong>${escapeHtml(p.packageName || p.planId)}</strong> — ৳${p.amountBdt}</div>
                <div class="muted">${escapeHtml((p.method || '').toUpperCase())} • Trx: ${escapeHtml(p.transactionId)} • Status: <strong>${escapeHtml((p.status || '').toUpperCase())}</strong></div>
                ${p.adminNote ? `<div class="muted">Admin: ${escapeHtml(p.adminNote)}</div>` : ''}
                ${p.refundReason ? `<div class="muted">Refund reason: ${escapeHtml(p.refundReason)}</div>` : ''}
              </div>
              ${p.status === 'approved' ? `<button class="btn btn-sm btn-outline" onclick="window.PremiumManagerModule.promptRefund('${escapeHtml(p.id)}')">Refund</button>` : `<span class="chip active">${escapeHtml((p.status || '').toUpperCase())}</span>`}
            </div>
          `).join('') : '<p class="muted">No payment records yet.</p>'}
        </div>
      </div>
    `;
  },

  buyViaPlayStore(productId) {
    if (!window.AndroidBridge) return;
    const res = JSON.parse(window.AndroidBridge.launchPlayPurchase(productId || ''));
    showToast(res.message || 'Play Billing invoked.');
  },

  submitSelectedPayment(packageId) {
    const sender = (document.getElementById('paySenderInput')?.value || '').trim();
    const trx = (document.getElementById('payTrxInput')?.value || '').trim();
    if (sender.length < 6 || trx.length < 4) {
      showToast('সঠিক ফোন নম্বর এবং Transaction ID দিন।');
      return;
    }
    window.AndroidBridge && window.AndroidBridge.submitPackagePayment(
      packageId,
      window.AppState.selectedPayMethod || 'bkash',
      sender,
      trx
    );
    document.getElementById('paySenderInput').value = '';
    document.getElementById('payTrxInput').value = '';
  },

  promptRefund(paymentId) {
    const reason = window.prompt('Refund request reason (রিফান্ডের কারণ লিখুন):', 'Accidental purchase');
    if (reason === null) return;
    window.AndroidBridge && window.AndroidBridge.requestPaymentRefund(paymentId, reason);
  },

  confirmCancelSubscription() {
    openConfirmModal(
      'Cancel Premium Subscription',
      'Are you sure you want to cancel your active Premium plan and revert to Free?',
      'Premium Dashboard',
      'Account',
      'Active',
      true,
      function () {
        window.AndroidBridge && window.AndroidBridge.cancelMySubscription();
      }
    );
  },

  renderCodeStudio(s) {
    const unlocked = (s.synced.featureCatalog || []).some(f => (f.key === 'ai_code_gen' || f.key === 'html_css_js_studio') && f.unlocked) || s.synced.isAdmin;
    const studio = s.codeStudio || {
      mode: 'web_bundle',
      provider: 'gemini',
      prompt: '',
      explanation: '',
      code: '',
      html: '<div class="box"><h1>Goriber AI Web Preview</h1><p>Generate HTML, CSS & JS with AI!</p></div>',
      css: 'body{font-family:sans-serif;background:#090c14;color:#f5c451;display:flex;justify-content:center;align-items:center;min-height:90vh;margin:0;} .box{padding:24px;border:1px solid #d4af37;border-radius:16px;text-align:center;}',
      js: 'console.log("Preview ready");',
      loading: false
    };
    s.codeStudio = studio;

    if (!unlocked) {
      return `
        <div class="gold-hero">
          <span class="crown-badge">🔒 PREMIUM FEATURE</span>
          <h2 class="gold-title" style="margin-top:8px;">AI Code &amp; HTML/CSS/JS Live Studio</h2>
          <p class="muted">AI দিয়ে যেকোনো কোড তৈরি এবং HTML, CSS, JavaScript লাইভ প্রিভিউ করতে Premium প্যাকেজ প্রয়োজন।</p>
          <button class="btn btn-gold" style="margin-top:14px;" onclick="navigateTo('subscription')">Upgrade to Premium 👑</button>
        </div>
      `;
    }

    const srcDoc = `<!DOCTYPE html><html><head><style>${studio.css || ''}</style></head><body>${studio.html || ''}<script>${studio.js || ''}<\/script></body></html>`;

    return `
      <div class="gold-hero">
        <span class="crown-badge">⚡ AI CODE &amp; WEB STUDIO</span>
        <h2 class="gold-title" style="margin-top:8px;">AI Code Generator &amp; Live Preview</h2>
        <p class="muted">HTML/CSS/JS ওয়েব অ্যাপ, Kotlin, Python, JavaScript বা App Scaffold তৈরি করুন।</p>
        <div class="row-gap" style="margin-top:12px;">
          <select id="codeModeSel" class="select" style="flex:1;" onchange="window.AppState.codeStudio.mode=this.value; renderCurrentPage();">
            <option value="web_bundle" ${studio.mode === 'web_bundle' ? 'selected' : ''}>HTML + CSS + JS Live Web App</option>
            <option value="kotlin" ${studio.mode === 'kotlin' ? 'selected' : ''}>Kotlin / Android Compose</option>
            <option value="python" ${studio.mode === 'python' ? 'selected' : ''}>Python Script</option>
            <option value="javascript" ${studio.mode === 'javascript' ? 'selected' : ''}>JavaScript / Node</option>
            <option value="app_scaffold" ${studio.mode === 'app_scaffold' ? 'selected' : ''}>App Builder Architecture</option>
          </select>
          <select id="codeProvSel" class="select" style="width:auto;" onchange="window.AppState.codeStudio.provider=this.value;">
            <option value="gemini" ${studio.provider === 'gemini' ? 'selected' : ''}>Gemini</option>
            <option value="chatgpt" ${studio.provider === 'chatgpt' ? 'selected' : ''}>ChatGPT</option>
            <option value="claude" ${studio.provider === 'claude' ? 'selected' : ''}>Claude</option>
            <option value="deepseek" ${studio.provider === 'deepseek' ? 'selected' : ''}>DeepSeek</option>
          </select>
        </div>
        <textarea id="codePromptInput" class="textarea" rows="3" style="margin-top:10px;" placeholder="কী কোড বা ওয়েব অ্যাপ তৈরি করতে চান লিখুন (যেমন: একটি গোল্ডেন ক্যালকুলেটর বা ল্যান্ডিং পেজ তৈরি করো)...">${escapeHtml(studio.prompt)}</textarea>
        <div class="row-gap" style="margin-top:10px;">
          <button class="btn btn-gold" onclick="window.PremiumManagerModule.generateCode()">
            ${studio.loading ? 'Generating...' : '✨ Generate Code with AI'}
          </button>
          <button class="chip" onclick="document.getElementById('codePromptInput').value='Create a modern luxury dark & gold scientific calculator web app';">Calculator Preset</button>
          <button class="chip" onclick="document.getElementById('codePromptInput').value='Create an animated todo & habit tracker HTML CSS JS widget';">Todo Preset</button>
        </div>
      </div>

      ${studio.explanation ? `<div class="gold-card"><strong class="gold-title">AI Summary:</strong> <span class="muted">${escapeHtml(studio.explanation)}</span></div>` : ''}

      ${studio.mode === 'web_bundle' ? `
        <div class="gold-card">
          <div class="row-between" style="margin-bottom:8px;">
            <strong class="gold-title">Live Sandboxed Web Preview</strong>
            <button class="btn btn-sm btn-outline" onclick="renderCurrentPage()">Refresh Preview</button>
          </div>
          <iframe class="code-preview-frame" sandbox="allow-scripts" srcdoc="${escapeHtml(srcDoc)}"></iframe>
        </div>
        <div class="gold-card">
          <strong class="gold-title">HTML</strong>
          <textarea class="code-editor" oninput="window.AppState.codeStudio.html=this.value;">${escapeHtml(studio.html)}</textarea>
          <strong class="gold-title" style="display:block; margin-top:10px;">CSS</strong>
          <textarea class="code-editor" oninput="window.AppState.codeStudio.css=this.value;">${escapeHtml(studio.css)}</textarea>
          <strong class="gold-title" style="display:block; margin-top:10px;">JavaScript</strong>
          <textarea class="code-editor" oninput="window.AppState.codeStudio.js=this.value;">${escapeHtml(studio.js)}</textarea>
        </div>
      ` : `
        <div class="gold-card">
          <strong class="gold-title">Generated Code (${escapeHtml(studio.mode.toUpperCase())})</strong>
          <textarea class="code-editor" style="min-height:260px; margin-top:8px;">${escapeHtml(studio.code || '// Generated code will appear here')}</textarea>
        </div>
      `}
    `;
  },

  generateCode() {
    const prompt = (document.getElementById('codePromptInput')?.value || '').trim();
    if (!prompt) {
      showToast('প্রম্পট লিখুন।');
      return;
    }
    const studio = window.AppState.codeStudio;
    studio.prompt = prompt;
    studio.loading = true;
    renderCurrentPage();

    const cbId = 'cg_' + Date.now();
    window._codeCallbacks[cbId] = function (res) {
      studio.loading = false;
      if (!res.ok) {
        showToast(res.errorMessage || 'Generation failed.');
        renderCurrentPage();
        return;
      }
      studio.explanation = res.explanation || '';
      studio.code = res.code || '';
      if (res.html) studio.html = res.html;
      if (res.css) studio.css = res.css;
      if (res.js) studio.js = res.js;
      showToast('Code generated via ' + (res.providerName || 'AI') + '!');
      renderCurrentPage();
    };
    window.AndroidBridge && window.AndroidBridge.generateAiCode(prompt, studio.mode, studio.provider, cbId);
  },

  renderCustomAssistantsAndFiles(s) {
    const unlockedAst = (s.synced.featureCatalog || []).some(f => f.key === 'custom_ai_assistant' && f.unlocked) || s.synced.isAdmin;
    const unlockedFiles = (s.synced.featureCatalog || []).some(f => f.key === 'file_ai_analysis' && f.unlocked) || s.synced.isAdmin;
    const assistants = s.synced.customAssistants || [];
    const selectedAst = assistants.find(a => a.id === s.selectedAssistantId) || assistants[0];
    if (selectedAst && !s.selectedAssistantId) s.selectedAssistantId = selectedAst.id;

    return `
      <!-- Custom AI Assistant Builder -->
      <div class="gold-hero">
        <span class="crown-badge">🤖 CUSTOM AI ASSISTANT BUILDER</span>
        <h2 class="gold-title" style="margin-top:8px;">Custom AI Assistant তৈরি ও চ্যাট</h2>
        ${!unlockedAst ? `
          <p class="muted">নিজস্ব কাস্টম অ্যাসিস্ট্যান্ট তৈরি করতে Premium প্যাকেজ প্রয়োজন।</p>
          <button class="btn btn-gold" style="margin-top:10px;" onclick="navigateTo('subscription')">Upgrade Now 👑</button>
        ` : `
          <div style="display:flex; flex-direction:column; gap:8px; margin-top:10px;">
            <input id="astNameInput" class="input" placeholder="Assistant Name (e.g. Bengali Study Tutor)" />
            <input id="astRoleInput" class="input" placeholder="Role Title (e.g. Android & Coding Mentor)" />
            <textarea id="astPromptInput" class="textarea" rows="3" placeholder="System Prompt / Instructions..."></textarea>
            <div class="row-gap">
              <select id="astProvSelect" class="select" style="flex:1;">
                <option value="gemini">Gemini</option>
                <option value="chatgpt">ChatGPT</option>
                <option value="claude">Claude</option>
                <option value="deepseek">DeepSeek</option>
              </select>
              <button class="btn btn-gold" onclick="window.PremiumManagerModule.saveAssistant()">Save Assistant</button>
            </div>
          </div>
        `}
      </div>

      ${unlockedAst ? `
        <div class="gold-card">
          <strong class="gold-title">Saved Custom Assistants (${assistants.length})</strong>
          <div style="margin-top:10px;">
            ${assistants.length ? assistants.map(a => `
              <div class="control-item" style="border-color:${s.selectedAssistantId === a.id ? 'var(--gold-bright)' : 'var(--border)'};">
                <div style="flex:1;" onclick="window.AppState.selectedAssistantId='${escapeHtml(a.id)}'; renderCurrentPage();">
                  <strong>${escapeHtml(a.name)}</strong> <span class="chip">${escapeHtml(a.providerId)}</span>
                  <div class="muted">${escapeHtml(a.roleTitle || a.systemPrompt.slice(0, 70))}</div>
                </div>
                <button class="btn btn-sm btn-danger" onclick="window.AndroidBridge && window.AndroidBridge.deleteCustomAssistant('${escapeHtml(a.id)}')">Delete</button>
              </div>
            `).join('') : '<p class="muted">No custom assistants saved yet.</p>'}
          </div>
          ${selectedAst ? `
            <div style="margin-top:12px; padding-top:12px; border-top:1px solid var(--border);">
              <div class="muted">Chat with <strong>${escapeHtml(selectedAst.name)}</strong></div>
              <div class="row-gap" style="margin-top:8px;">
                <input id="astChatInput" class="input" style="flex:1;" placeholder="Ask ${escapeHtml(selectedAst.name)} anything..." />
                <button class="btn btn-gold" onclick="window.PremiumManagerModule.chatWithAssistant('${escapeHtml(selectedAst.id)}')">Ask</button>
              </div>
              ${s.lastAssistantReply ? `<div class="chat-msg ai" style="margin-top:10px; max-width:100%;">${escapeHtml(s.lastAssistantReply)}</div>` : ''}
            </div>
          ` : ''}
        </div>
      ` : ''}

      <!-- File Upload & AI Analysis -->
      <div class="gold-card">
        <div class="row-between">
          <div>
            <div class="gold-title">📂 File Upload &amp; AI Analysis</div>
            <p class="muted">যেকোনো কোড, টেক্সট, JSON বা লগ ফাইল সিলেক্ট করে AI দিয়ে বিশ্লেষণ করুন।</p>
          </div>
        </div>
        ${!unlockedFiles ? `
          <button class="btn btn-gold" style="margin-top:10px;" onclick="navigateTo('subscription')">Unlock with Premium</button>
        ` : `
          <div class="row-gap" style="margin-top:12px;">
            <button class="btn btn-gold" onclick="window.AndroidBridge && window.AndroidBridge.pickDocumentForAi()">Pick File (SAF)</button>
            <span class="muted">${s.synced.pickedDocName ? 'Selected: ' + escapeHtml(s.synced.pickedDocName) : 'No file selected'}</span>
          </div>
          ${s.synced.pickedDocPreview ? `<pre class="code-editor" style="min-height:90px; margin-top:8px;">${escapeHtml(s.synced.pickedDocPreview)}</pre>` : ''}
          <div class="row-gap" style="margin-top:10px;">
            <input id="fileAnalysisQuestion" class="input" style="flex:1;" placeholder="What should AI check? (e.g. Summarize or find bugs)" />
            <button class="btn btn-primary" onclick="window.PremiumManagerModule.analyzeFile()">Analyze</button>
          </div>
          ${s.lastFileAnalysis ? `<div class="chat-msg ai" style="margin-top:10px; max-width:100%;">${escapeHtml(s.lastFileAnalysis)}</div>` : ''}
        `}
      </div>
    `;
  },

  saveAssistant() {
    const name = (document.getElementById('astNameInput')?.value || '').trim();
    const role = (document.getElementById('astRoleInput')?.value || '').trim();
    const prompt = (document.getElementById('astPromptInput')?.value || '').trim();
    const prov = document.getElementById('astProvSelect')?.value || 'gemini';
    if (!name || !prompt) {
      showToast('নাম এবং সিস্টেম প্রম্পট দিন।');
      return;
    }
    window.AndroidBridge && window.AndroidBridge.saveCustomAssistant('', name, role, prompt, prov);
    document.getElementById('astNameInput').value = '';
    document.getElementById('astRoleInput').value = '';
    document.getElementById('astPromptInput').value = '';
  },

  chatWithAssistant(assistantId) {
    const msg = (document.getElementById('astChatInput')?.value || '').trim();
    if (!msg) return;
    window.AppState.lastAssistantReply = 'Thinking...';
    renderCurrentPage();
    const cbId = 'ast_' + Date.now();
    window._assistantCallbacks[cbId] = function (res) {
      window.AppState.lastAssistantReply = res.ok ? (res.reply || '') : (res.errorMessage || 'Error');
      renderCurrentPage();
    };
    window.AndroidBridge && window.AndroidBridge.runCustomAssistantChat(assistantId, msg, cbId);
  },

  analyzeFile() {
    const q = (document.getElementById('fileAnalysisQuestion')?.value || '').trim();
    window.AppState.lastFileAnalysis = 'Analyzing file...';
    renderCurrentPage();
    const cbId = 'fa_' + Date.now();
    window._fileAnalysisCallbacks[cbId] = function (res) {
      window.AppState.lastFileAnalysis = res.ok ? (res.analysis || '') : (res.errorMessage || 'Analysis failed');
      renderCurrentPage();
    };
    window.AndroidBridge && window.AndroidBridge.analyzePickedDocument(q, '', cbId);
  }
};
