/*
 * WhaleChat 界面补丁（由 MainActivity.onPageFinished 注入）
 *
 * 1. 把页面标题 / 侧边栏品牌名从「API 使用 demo」换成 WhaleChat。
 * 2. 接管 chat-demo 自带的设置对话框：把它隐藏起来当作“引擎”，
 *    用官方 App 风格的整页设置页承载同样的功能（API key、数据管理、
 *    外观、语言、朗读音色、位置信息），并新增「关于」。
 *
 * 所有交互都是转发到引擎里的真实控件，所以功能与原来完全一致；
 * 选择器一律按语义（role / 结构位置）匹配，不依赖 CSS Modules 的哈希类名。
 */
(function () {
  'use strict';
  if (window.__whaleUiInstalled) return;
  window.__whaleUiInstalled = true;

  var APP_NAME = 'WhaleChat';
  var OLD_BRAND = 'API 使用 demo';

  var APP_VERSION = '';
  try {
    if (window.AndroidBridge && AndroidBridge.appVersion) {
      APP_VERSION = String(AndroidBridge.appVersion());
    }
  } catch (e) {}

  var q = function (s, r) { return (r || document).querySelector(s); };
  var qa = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var text = function (el) { return el ? (el.textContent || '').trim() : ''; };
  var esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  /* ------------------------------------------------------------------ 样式 */

  var CSS = [
    'body { --wc-accent: #4d6bfe; }',
    'body:not(.dark) { --wc-bg:#ffffff; --wc-card:#f4f5f7; --wc-text:#0f1115; --wc-muted:#81858c;',
    '  --wc-line:rgba(15,17,21,.08); --wc-mask:rgba(0,0,0,.35); }',
    'body.dark { --wc-bg:#1a1b1e; --wc-card:#25262a; --wc-text:#e9eaec; --wc-muted:#8d9199;',
    '  --wc-line:rgba(255,255,255,.08); --wc-mask:rgba(0,0,0,.55); }',

    /* 打开我们的设置页时，隐藏引擎的设置对话框与它弹出的菜单 */
    'body.wc-open [role="dialog"][aria-modal="true"]:has([role="tablist"]) { display:none !important; }',
    'body.wc-open [role="listbox"] { visibility:hidden !important; }',

    '.wc-page { position:fixed; inset:0; z-index:2147483000; display:flex; flex-direction:column;',
    '  background:var(--wc-bg); color:var(--wc-text); -webkit-tap-highlight-color:transparent;',
    '  font-family:var(--dsw-font-family, system-ui, -apple-system, sans-serif); overscroll-behavior:contain; }',
    '.wc-head { position:relative; flex:0 0 auto; height:52px; display:flex; align-items:center;',
    '  justify-content:center; padding:0 8px; }',
    '.wc-title { font-size:17px; font-weight:600; }',
    '.wc-iconbtn { position:absolute; left:6px; top:6px; width:40px; height:40px; border:0; border-radius:20px;',
    '  background:transparent; color:inherit; display:flex; align-items:center; justify-content:center; }',
    '.wc-iconbtn:active { background:var(--wc-line); }',
    '.wc-backarrow { width:11px; height:11px; margin-left:4px; border-left:2px solid currentColor;',
    '  border-bottom:2px solid currentColor; transform:rotate(45deg); }',
    '.wc-body { flex:1 1 auto; overflow-y:auto; -webkit-overflow-scrolling:touch;',
    '  padding:2px 16px calc(30px + env(safe-area-inset-bottom, 0px)); }',
    '.wc-group-title { padding:18px 8px 8px; font-size:13px; color:var(--wc-muted); }',
    '.wc-card { background:var(--wc-card); border-radius:14px; overflow:hidden; }',
    '.wc-row { position:relative; display:flex; align-items:center; width:100%; min-height:52px; padding:0 14px;',
    '  border:0; background:transparent; color:inherit; font:inherit; font-size:15px; text-align:left; }',
    '.wc-row + .wc-row::before { content:""; position:absolute; top:0; left:14px; right:0; height:1px;',
    '  background:var(--wc-line); }',
    '.wc-row:active { background:var(--wc-line); }',
    '.wc-label { flex:1 1 auto; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
    '.wc-value { flex:0 1 auto; max-width:56%; margin-left:12px; font-size:14px; color:var(--wc-muted);',
    '  overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
    '.wc-chev { flex:0 0 auto; width:7px; height:7px; margin-left:8px; border-top:1.5px solid var(--wc-muted);',
    '  border-right:1.5px solid var(--wc-muted); transform:rotate(45deg); opacity:.75; }',
    '.wc-danger .wc-label, .wc-danger .wc-value { color:#f2555a; }',
    '.wc-foot { padding:26px 10px 0; font-size:12px; line-height:1.8; color:var(--wc-muted); text-align:center; }',
    '.wc-dim { opacity:.45; }',
    '.wc-toast { position:fixed; left:50%; bottom:96px; transform:translate(-50%,8px); z-index:2147483600;',
    '  max-width:74vw; padding:10px 16px; border-radius:10px; background:rgba(20,22,28,.92); color:#fff;',
    '  font-size:13px; line-height:1.45; text-align:center; opacity:0; pointer-events:none; transition:opacity .16s, transform .16s; }',
    '.wc-toast-on { opacity:1; transform:translate(-50%,0); }',

    '.wc-mask { position:fixed; inset:0; z-index:2147483001; background:var(--wc-mask);',
    '  display:flex; flex-direction:column; justify-content:flex-end; }',
    '.wc-sheet { background:var(--wc-bg); border-radius:18px 18px 0 0; display:flex; flex-direction:column;',
    '  max-height:78vh; padding-bottom:env(safe-area-inset-bottom, 0px); }',
    '.wc-sheet-head { position:relative; flex:0 0 auto; height:52px; display:flex; align-items:center;',
    '  justify-content:center; font-size:16px; font-weight:600; }',
    '.wc-sheet-cancel { position:absolute; right:8px; top:8px; height:36px; padding:0 12px; border:0;',
    '  background:transparent; color:var(--wc-accent); font:inherit; font-size:15px; }',
    '.wc-sheet-body { overflow-y:auto; padding-bottom:8px; }',
    '.wc-opt { display:flex; align-items:center; justify-content:space-between; width:100%; min-height:50px;',
    '  padding:0 20px; border:0; background:transparent; color:inherit; font:inherit; font-size:15px; text-align:left; }',
    '.wc-opt:active { background:var(--wc-line); }',
    '.wc-opt-active { color:var(--wc-accent); }',
    '.wc-opt-label { flex:1 1 auto; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
    '.wc-check { flex:0 0 auto; width:13px; height:7px; margin-left:10px; border-left:2px solid currentColor;',
    '  border-bottom:2px solid currentColor; transform:rotate(-45deg); }',
    '.wc-note { padding:2px 20px 12px; font-size:12px; line-height:1.7; color:var(--wc-muted); }',

    '.wc-pad { padding:0 16px; }',
    '.wc-input { width:100%; box-sizing:border-box; height:46px; border-radius:12px; border:1px solid var(--wc-line);',
    '  background:var(--wc-card); color:inherit; font:inherit; font-size:15px; padding:0 14px; outline:none; }',
    '.wc-input:focus { border-color:var(--wc-accent); }',
    '.wc-btn { width:100%; height:46px; margin:16px 0 0; border:0; border-radius:12px; background:var(--wc-accent);',
    '  color:#fff; font:inherit; font-size:16px; font-weight:600; }',
    '.wc-btn[disabled] { opacity:.5; }',
    '.wc-status { min-height:18px; padding:10px 2px 0; font-size:13px; color:var(--wc-muted); }',
    '.wc-status.wc-err { color:#f2555a; }',
    '.wc-status.wc-ok { color:#20a162; }',

    '.wc-about { position:fixed; inset:0; z-index:2147483050; background:var(--wc-mask);',
    '  display:flex; align-items:center; justify-content:center; padding:26px; }',
    '.wc-about-card { width:100%; max-width:340px; max-height:76vh; overflow-y:auto; background:var(--wc-bg);',
    '  color:var(--wc-text); border-radius:18px; padding:22px 20px 16px; box-sizing:border-box; }',
    '.wc-about-title { font-size:17px; font-weight:600; text-align:center; }',
    '.wc-about-ver { margin:4px 0 14px; font-size:12px; color:var(--wc-muted); text-align:center; }',
    '.wc-about-text { font-size:14px; line-height:1.75; }',
    '.wc-about-text p { margin:0 0 10px; }',
    '.wc-about-text ul { margin:0 0 10px; padding-left:18px; }',
    '.wc-about-text li { margin:0 0 6px; }',
    '.wc-about-ok { width:100%; height:44px; margin-top:10px; border:0; border-radius:12px;',
    '  background:var(--wc-card); color:var(--wc-accent); font:inherit; font-size:16px; font-weight:600; }'
  ].join('\n');

  var styleEl = document.createElement('style');
  styleEl.id = 'wc-style';
  styleEl.textContent = CSS;
  (document.head || document.documentElement).appendChild(styleEl);

  /* ------------------------------------------------------- 引擎（原设置页） */

  function engineDialog() {
    return qa('[role="dialog"][aria-modal="true"]').filter(function (d) {
      return q('[role="tablist"]', d);
    })[0] || null;
  }

  function engineTabs() {
    var d = engineDialog();
    return d ? qa('[role="tab"]', d) : [];
  }

  function engineCells() {
    var d = engineDialog();
    var p = d ? q('[role="tabpanel"]', d) : null;
    return p ? Array.prototype.slice.call(p.children) : [];
  }

  async function useTab(i) {
    var t = engineTabs()[i];
    if (!t) return false;
    if (t.getAttribute('aria-selected') !== 'true') {
      t.click();
      await sleep(160);
    }
    return true;
  }

  // 打开引擎里的下拉菜单，返回 { options: [元素] }；菜单留在打开状态（被 CSS 隐藏）
  async function openEngineMenu(trigger) {
    if (!trigger || trigger.disabled) return null;
    var before = qa('[role="listbox"]');
    trigger.click();
    await sleep(200);
    var boxes = qa('[role="listbox"]').filter(function (b) { return before.indexOf(b) < 0; });
    var box = boxes[boxes.length - 1] || qa('[role="listbox"]').pop();
    if (!box) return null;
    var options = qa('[role="option"]', box);
    return options.length ? { box: box, options: options } : null;
  }

  function closeEngineMenu(trigger) {
    try { if (trigger) trigger.click(); } catch (e) {}
  }

  function setNativeValue(input, value) {
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  /* --------------------------------------------------------------- 状态读取 */

  var state = {
    theme: '', lang: '', tts: '', location: '', apiKeySet: false, exportDisabled: true
  };

  /* 文案：跟随引擎（chat-demo）当前语言，只做中英文两套 */
  var I18N = {
    zh: {
      settings: '设置', back: '返回', cancel: '取消', ok: '我知道了', choose: '请选择',
      account: '账户', data: '数据', app: '应用', aboutGroup: '关于',
      apikey: 'API key', importChats: '导入历史对话', exportChats: '导出所有历史对话',
      deleteChats: '删除所有对话', theme: '外观', lang: '语言', tts: '朗读音色',
      location: '位置信息', about: '关于 ', set: '已设置', unset: '未设置',
      foot1: '基于 DeepSeek Chat-Demo 封装',
      foot2: '非 DeepSeek 官方出品，与深度求索公司无关联',
      keyNote: 'API key 只保存在本机，用于直接请求 DeepSeek 官方 API，不会上传到其他服务器。',
      keyPlaceholder: '请输入 API Key', save: '保存', keyEmpty: '请输入 API Key',
      keyChecking: '正在校验…', keySaved: '已保存', keyFailed: '校验失败，请检查 API Key',
      keyNoInput: '无法定位 API Key 输入框', keyTimeout: '校验超时，请稍后重试',
      exportEmpty: '暂无可导出的历史对话', importFailed: '导入失败，请选择 DeepSeek 导出的 ZIP 文件',
      importing: '正在导入…', exportSaved: '已保存到 Download/WhaleChat',
      aboutVer: '版本 ', aboutIntro: 'WhaleChat 是一个基于 DeepSeek 官方开源 Chat-Demo 封装的 Android 客户端，' +
        '使用系统 WebView 承载界面，并补齐了拍照、相册、图片编辑、文件下载等原生能力。',
      aboutUnofficial: '非官方说明',
      aboutUnofficial1: '本应用不是 DeepSeek 官方出品，与深度求索公司没有任何隶属或合作关系。',
      aboutUnofficial2: '「DeepSeek」名称及相关标识归其权利人所有，本项目仅用于技术演示与个人使用。',
      aboutData: '数据说明',
      aboutData1: '对话内容仅通过 DeepSeek 官方 API 发送与接收，由 DeepSeek 按其服务条款处理。',
      aboutData2: '本应用不额外收集、不上传任何数据，不含统计、广告或崩溃上报 SDK。',
      aboutData3: 'API Key 与历史对话只保存在本机 WebView 存储和本地文件中。',
      aboutSource: '源码：github.com/Sallee1/WhaleChat（MIT License）'
    },
    en: {
      settings: 'Settings', back: 'Back', cancel: 'Cancel', ok: 'Got it', choose: 'Choose',
      account: 'Account', data: 'Data', app: 'App', aboutGroup: 'About',
      apikey: 'API key', importChats: 'Import chat history', exportChats: 'Export all chats',
      deleteChats: 'Delete all chats', theme: 'Appearance', lang: 'Language', tts: 'Voice',
      location: 'Location', about: 'About ', set: 'Set', unset: 'Not set',
      foot1: 'Built on the DeepSeek Chat-Demo',
      foot2: 'Not an official DeepSeek product, not affiliated with DeepSeek',
      keyNote: 'Your API key is stored only on this device and used to call the official DeepSeek API directly. ' +
        'It is never sent anywhere else.',
      keyPlaceholder: 'Enter your API key', save: 'Save', keyEmpty: 'Please enter an API key',
      keyChecking: 'Verifying…', keySaved: 'Saved', keyFailed: 'Invalid API key, please check and retry',
      keyNoInput: 'API key field not found', keyTimeout: 'Verification timed out, try again later',
      exportEmpty: 'No chats to export yet', importFailed: 'Import failed. Pick a ZIP exported by DeepSeek.',
      importing: 'Importing…', exportSaved: 'Saved to Download/WhaleChat',
      aboutVer: 'Version ', aboutIntro: 'WhaleChat is an Android client built on DeepSeek\'s open-source Chat-Demo. ' +
        'It renders the UI in the system WebView and adds native camera, gallery, image editing and file download support.',
      aboutUnofficial: 'Not official',
      aboutUnofficial1: 'This app is not made by DeepSeek and has no affiliation with DeepSeek.',
      aboutUnofficial2: 'The DeepSeek name and marks belong to their respective owners. ' +
        'This project is for technical demonstration and personal use.',
      aboutData: 'Data',
      aboutData1: 'Chat content is sent to and received from the official DeepSeek API only, and handled under DeepSeek\'s terms.',
      aboutData2: 'This app collects no additional data and contains no analytics, ads or crash-reporting SDK.',
      aboutData3: 'Your API key and chat history stay in the local WebView storage and files on this device.',
      aboutSource: 'Source: github.com/Sallee1/WhaleChat (MIT License)'
    }
  };

  function langCode() {
    return /^English/i.test(state.lang || '') ? 'en' : 'zh';
  }

  function t(key) {
    var pack = I18N[langCode()] || I18N.zh;
    return pack[key] != null ? pack[key] : (I18N.zh[key] || key);
  }

  function triggerValue(btn) {
    if (!btn) return '';
    if (q('span[class*="placeholder-"]', btn)) return '';
    return text(btn);
  }

  async function refresh() {
    if (!engineDialog()) return;
    await useTab(0);
    var cs = engineCells();
    var active = cs[0] ? q('[role="radio"][aria-checked="true"]', cs[0]) : null;
    state.theme = text(active);
    state.lang = triggerValue(cs[1] ? q('button', cs[1]) : null);
    state.tts = triggerValue(cs[2] ? q('button', cs[2]) : null);
    var locs = cs[3] ? qa('button[aria-haspopup]', cs[3]) : [];
    state.location = locs.map(triggerValue).filter(Boolean).join(' / ');

    await useTab(1);
    cs = engineCells();
    var keyInput = cs[0] ? q('input', cs[0]) : null;
    // 已保存态没有输入框，只剩打码的值 +「修改 API Key」按钮
    state.apiKeySet = keyInput ? !!keyInput.value : !!(cs[0] && q('button', cs[0]));
    state.exportDisabled = !!(cs[2] && q('button', cs[2]) && q('button', cs[2]).disabled);

    paint();
  }

  /* ------------------------------------------------------------------ 弹层 */

  var layers = [];

  function closeLayer() {
    var m = layers.pop();
    if (m && m.parentNode) m.parentNode.removeChild(m);
  }

  function closeAllLayers() {
    while (layers.length) closeLayer();
  }

  function openMask(z) {
    var mask = document.createElement('div');
    mask.className = 'wc-mask';
    mask.style.zIndex = String(z || (2147483001 + layers.length));
    document.body.appendChild(mask);
    layers.push(mask);
    return mask;
  }

  function openOptionSheet(title, items, onPick, onCancel) {
    var mask = openMask();
    var body = items.map(function (it, i) {
      return '<button class="wc-opt' + (it.active ? ' wc-opt-active' : '') + '" data-i="' + i + '">' +
        '<span class="wc-opt-label">' + esc(it.label) + '</span>' +
        (it.active ? '<span class="wc-check"></span>' : '') + '</button>';
    }).join('');
    mask.innerHTML = '<div class="wc-sheet">' +
      '<div class="wc-sheet-head">' + esc(title) + '<button class="wc-sheet-cancel">' + esc(t('cancel')) + '</button></div>' +
      '<div class="wc-sheet-body">' + body + '</div></div>';

    var cancel = function () {
      closeLayer();
      if (onCancel) onCancel();
    };
    q('.wc-sheet-cancel', mask).addEventListener('click', cancel);
    mask.addEventListener('click', function (e) { if (e.target === mask) cancel(); });
    mask.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('.wc-opt');
      if (!btn) return;
      if (onPick) onPick(parseInt(btn.getAttribute('data-i'), 10));
    });
  }

  /* ------------------------------------------------------------ 具体动作 */

  async function chooseFromMenu(title, pickTrigger) {
    await useTab(0);
    var trig = pickTrigger();
    if (!trig || trig.disabled) return;
    var menu = await openEngineMenu(trig);
    if (!menu) return;
    var items = menu.options.map(function (o) {
      return { label: text(o), active: o.getAttribute('aria-selected') === 'true' };
    });
    openOptionSheet(title, items, async function (i) {
      closeLayer();
      if (menu.options[i]) menu.options[i].click();
      await sleep(280);
      await refresh();
    }, function () { closeEngineMenu(trig); });
  }

  async function openThemeSheet() {
    await useTab(0);
    var cs = engineCells();
    var radios = cs[0] ? qa('[role="radio"]', cs[0]) : [];
    if (!radios.length) return;
    var items = radios.map(function (r) {
      return { label: text(r), active: r.getAttribute('aria-checked') === 'true' };
    });
    openOptionSheet(t('theme'), items, async function (i) {
      closeLayer();
      if (radios[i]) radios[i].click();
      await sleep(220);
      await refresh();
    });
  }

  async function openLocationSheet() {
    await useTab(0);
    var cs = engineCells();
    var trigs = cs[3] ? qa('button[aria-haspopup]', cs[3]) : [];
    if (!trigs.length) return;
    var items = trigs.map(function (b) {
      return { label: triggerValue(b) || text(b), active: false };
    });
    openOptionSheet(t('location'), items, async function (i) {
      closeLayer();
      var trig = trigs[i];
      var menu = await openEngineMenu(trig);
      if (!menu) return;
      var opts = menu.options.map(function (o) {
        return { label: text(o), active: o.getAttribute('aria-selected') === 'true' };
      });
      openOptionSheet(t('choose'), opts, async function (k) {
        closeLayer();
        if (menu.options[k]) menu.options[k].click();
        await sleep(300);
        await refresh();
      }, function () { closeEngineMenu(trig); });
    });
  }

  async function runDataAction(index) {
    await useTab(1);
    var c = engineCells()[index];
    var b = c ? q('button', c) : null;
    if (b && !b.disabled) b.click();
  }

  function openApiKeySheet() {
    var mask = openMask(2147483040);
    mask.innerHTML = '<div class="wc-sheet">' +
      '<div class="wc-sheet-head">' + esc(t('apikey')) + '<button class="wc-sheet-cancel">' + esc(t('cancel')) + '</button></div>' +
      '<div class="wc-note">' + esc(t('keyNote')) + '</div>' +
      '<div class="wc-pad"><input class="wc-input" type="password" placeholder="' + esc(t('keyPlaceholder')) + '" ' +
      'autocomplete="off" spellcheck="false"></div>' +
      '<div class="wc-pad"><div class="wc-status"></div></div>' +
      '<div class="wc-pad"><button class="wc-btn">' + esc(t('save')) + '</button></div>' +
      '<div style="height:16px"></div></div>';

    var input = q('.wc-input', mask);
    var btn = q('.wc-btn', mask);
    var status = q('.wc-status', mask);
    var setStatus = function (msg, kind) {
      status.textContent = msg || '';
      status.className = 'wc-status' + (kind ? ' wc-' + kind : '');
    };

    q('.wc-sheet-cancel', mask).addEventListener('click', function () { closeLayer(); });
    mask.addEventListener('click', function (e) { if (e.target === mask) closeLayer(); });

    btn.addEventListener('click', async function () {
      var key = (input.value || '').trim();
      if (!key) { setStatus(t('keyEmpty'), 'err'); return; }
      btn.disabled = true;
      setStatus(t('keyChecking'));
      var res = await submitApiKey(key);
      btn.disabled = false;
      if (res.ok) {
        setStatus(t('keySaved'), 'ok');
        await refresh();
        setTimeout(closeLayer, 700);
      } else {
        setStatus(res.message || t('keyFailed'), 'err');
      }
    });
  }

  /* 引擎的账户单元格：没存 key 时是输入框；存了之后标签不变，只是输入框换成打码的值 +「修改 API Key」按钮 */
  var VERIFYING_RE = /正在验证|验证中|Verifying/i;
  var KEY_ERROR_RE = /无效|失败|错误|invalid|failed|error/i;
  var KEY_START_RE = /^(输入\s*API\s*Key\s*开始使用|Enter an API Key to get started)/i;

  async function keyCell() {
    await useTab(1);
    return engineCells()[0] || null;
  }

  /* 拿到可输入的 key 输入框；已经处于「已保存」态就先点「修改 API Key」切回编辑 */
  async function findKeyInput() {
    var cell = await keyCell();
    if (!cell) return { cell: null, input: null };
    var input = q('input', cell);
    if (input) return { cell: cell, input: input };
    // 已保存态的按钮只有图标（文案在 aria-label 里），且点它只切回编辑、不会真的改 key
    var edit = q('button', cell);
    if (edit && !edit.disabled) {
      edit.click();
      await sleep(300);
      cell = engineCells()[0] || cell;
      input = q('input', cell);
    }
    return { cell: cell, input: input || null };
  }

  async function submitApiKey(key) {
    var found = await findKeyInput();
    var cell = found.cell;
    var input = found.input;
    if (!input) return { ok: false, message: t('keyNoInput') };
    setNativeValue(input, key);
    await sleep(60);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    await sleep(80);
    input.dispatchEvent(new Event('change', { bubbles: true }));
    try { input.blur(); } catch (e) {}

    // 最多等 60s。校验中的按钮文案会变成「正在验证…」；成功后输入框被换成打码的值 + 修改按钮。
    // 超时不算失败：请求可能还在后台跑，下一次保存会先看单元格是不是已经切到已保存态。
    for (var i = 0; i < 150; i++) {
      await sleep(400);
      var cellNow = engineCells()[0] || cell;
      if (!cellNow || !cellNow.isConnected) return { ok: true };   // 设置弹窗被引擎关掉 = 收下了
      var inputNow = q('input', cellNow);
      var btn = q('button', cellNow);
      var verifying = !!(btn && VERIFYING_RE.test(text(btn)));
      if (verifying) continue;
      var rest = text(cellNow).replace(KEY_START_RE, '').trim();
      if (KEY_ERROR_RE.test(rest)) return { ok: false, message: rest.slice(0, 80) };
      // 输入框没了、但单元格里还有「修改 API Key」按钮 → 引擎已经存下这个 key
      if (!inputNow && btn) return { ok: true };
    }
    return { ok: false, message: t('keyTimeout') };
  }

  /* 轻提示（引擎的按钮有时点不动，需要给个反馈） */
  var toastTimer = 0;

  function toast(msg) {
    var el = q('.wc-toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'wc-toast';
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.classList.add('wc-toast-on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.remove('wc-toast-on');
    }, 2200);
  }

  /* 导入历史对话：原生开 DocumentsUI → 文件复制到 cache → 页面 fetch 成 File 喂给引擎 */

  var importSeq = 0;
  var importWaiters = {};

  window.__whaleImportReady = function (id, url) {
    var w = importWaiters[id];
    delete importWaiters[id];
    if (w) w(url);
  };

  window.__whaleImportCancelled = function (id) {
    window.__whaleImportReady(id, null);
  };

  function pickImportFile() {
    return new Promise(function (resolve) {
      var bridge = window.AndroidBridge;
      if (!bridge || !bridge.pickImportZip) { resolve(null); return; }
      var id = ++importSeq;
      importWaiters[id] = resolve;
      try {
        bridge.pickImportZip(id);
      } catch (e) {
        delete importWaiters[id];
        resolve(null);
      }
      setTimeout(function () {
        if (importWaiters[id]) { delete importWaiters[id]; resolve(null); }
      }, 5 * 60 * 1000);
    });
  }

  async function importHistory() {
    var url = await pickImportFile();
    if (!url) return;
    toast(t('importing'));
    try {
      var res = await fetch(url);
      var blob = await res.blob();
      var file = new File([blob], 'history.zip', { type: 'application/zip' });
      await useTab(1);
      var cell = engineCells()[1];
      var input = cell ? q('input[type="file"]', cell) : null;
      if (!input) { toast(t('importFailed')); return; }
      var dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await waitImportSettled();
      await refresh();
    } catch (e) {
      toast(t('importFailed'));
    }
  }

  /** 等引擎的「确认导入 / 正在导入」流程走完，再重读状态 */
  async function waitImportSettled() {
    for (var i = 0; i < 30; i++) {
      await sleep(1000);
      var busy = qa('[role="dialog"][aria-modal="true"]').some(function (d) {
        return !q('[role="tablist"]', d);
      });
      if (busy) continue;
      await refresh();
      if (!state.exportDisabled) return;
    }
  }

  function openAbout() {
    if (q('.wc-about')) return;
    var mask = document.createElement('div');
    mask.className = 'wc-about';
    mask.innerHTML = '<div class="wc-about-card">' +
      '<div class="wc-about-title">' + esc(APP_NAME) + '</div>' +
      '<div class="wc-about-ver">' + esc(t('aboutVer') + (APP_VERSION || '1.0.0')) + '</div>' +
      '<div class="wc-about-text">' +
      '<p>' + esc(t('aboutIntro')) + '</p>' +
      '<p><strong>' + esc(t('aboutUnofficial')) + '</strong></p>' +
      '<ul>' +
      '<li>' + esc(t('aboutUnofficial1')) + '</li>' +
      '<li>' + esc(t('aboutUnofficial2')) + '</li>' +
      '</ul>' +
      '<p><strong>' + esc(t('aboutData')) + '</strong></p>' +
      '<ul>' +
      '<li>' + esc(t('aboutData1')) + '</li>' +
      '<li>' + esc(t('aboutData2')) + '</li>' +
      '<li>' + esc(t('aboutData3')) + '</li>' +
      '</ul>' +
      '<p>' + esc(t('aboutSource')) + '</p>' +
      '</div>' +
      '<button class="wc-about-ok">' + esc(t('ok')) + '</button></div>';
    document.body.appendChild(mask);
    var close = function () { if (mask.parentNode) mask.parentNode.removeChild(mask); };
    q('.wc-about-ok', mask).addEventListener('click', close);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
  }

  /* ------------------------------------------------------------ 设置页 DOM */

  var ui = null;

  function rowHtml(act, label, valueKey, danger) {
    return '<button class="wc-row' + (danger ? ' wc-danger' : '') + '" data-act="' + act + '">' +
      '<span class="wc-label">' + esc(label) + '</span>' +
      (valueKey ? '<span class="wc-value" data-v="' + valueKey + '"></span>' : '') +
      '<span class="wc-chev"></span></button>';
  }

  function buildPage() {
    var root = document.createElement('div');
    root.className = 'wc-page';
    root.innerHTML =
      '<div class="wc-head"><button class="wc-iconbtn" data-act="back" aria-label="' + esc(t('back')) + '">' +
      '<span class="wc-backarrow"></span></button><div class="wc-title">' + esc(t('settings')) + '</div></div>' +
      '<div class="wc-body">' +
      '<div class="wc-group-title">' + esc(t('account')) + '</div><div class="wc-card">' +
      rowHtml('apikey', t('apikey'), 'apikey') + '</div>' +
      '<div class="wc-group-title">' + esc(t('data')) + '</div><div class="wc-card">' +
      rowHtml('import', t('importChats')) +
      rowHtml('export', t('exportChats')) +
      rowHtml('delete', t('deleteChats'), null, true) + '</div>' +
      '<div class="wc-group-title">' + esc(t('app')) + '</div><div class="wc-card">' +
      rowHtml('theme', t('theme'), 'theme') +
      rowHtml('lang', t('lang'), 'lang') +
      rowHtml('tts', t('tts'), 'tts') +
      rowHtml('location', t('location'), 'location') + '</div>' +
      '<div class="wc-group-title">' + esc(t('aboutGroup')) + '</div><div class="wc-card">' +
      rowHtml('about', t('about') + APP_NAME, 'about') + '</div>' +
      '<div class="wc-foot">' + esc(APP_NAME) + ' ' + esc(t('foot1')) + '<br>' +
      esc(t('foot2')) + '</div>' +
      '</div>';

    root.addEventListener('click', function (e) {
      var row = e.target.closest && e.target.closest('[data-act]');
      if (!row) return;
      var act = row.getAttribute('data-act');
      if (act === 'back') closeSettingsPage();
      else if (act === 'about') openAbout();
      else if (act === 'apikey') openApiKeySheet();
      else if (act === 'import') importHistory();
      else if (act === 'export') {
        if (state.exportDisabled) toast(t('exportEmpty'));
        else {
          runDataAction(2).then(function () {
            setTimeout(function () { toast(t('exportSaved')); }, 2500);
          });
        }
      }
      else if (act === 'delete') runDataAction(3);
      else if (act === 'theme') openThemeSheet();
      else if (act === 'lang') {
        chooseFromMenu(t('lang'), function () { var c = engineCells()[1]; return c ? q('button', c) : null; });
      } else if (act === 'tts') {
        chooseFromMenu(t('tts'), function () { var c = engineCells()[2]; return c ? q('button', c) : null; });
      } else if (act === 'location') openLocationSheet();
    });

    return root;
  }

  function paint() {
    if (!ui) return;
    if (ui.lang !== langCode()) rebuildForLang();
    var set = function (key, value) {
      var el = q('[data-v="' + key + '"]', ui.root);
      if (el) el.textContent = value || '';
    };
    set('apikey', state.apiKeySet ? t('set') : t('unset'));
    set('theme', state.theme);
    set('lang', state.lang);
    set('tts', state.tts);
    set('location', state.location || t('unset'));
    set('about', APP_VERSION || '1.0.0');
    var exp = q('[data-act="export"]', ui.root);
    if (exp) exp.classList.toggle('wc-dim', !!state.exportDisabled);
  }

  /** 引擎语言变了（用户在「语言」里切换）→ 整页重建，文案跟着换 */
  function rebuildForLang() {
    if (!ui) return;
    if (ui.root.parentNode) ui.root.parentNode.removeChild(ui.root);
    ui = { root: buildPage(), lang: langCode() };
    document.body.appendChild(ui.root);
  }

  function openSettingsPage() {
    if (ui) return;
    document.body.classList.add('wc-open');
    ui = { root: buildPage(), lang: langCode() };
    document.body.appendChild(ui.root);
    refresh();
  }

  function closeSettingsPage(silent) {
    closeAllLayers();
    if (ui) {
      if (ui.root.parentNode) ui.root.parentNode.removeChild(ui.root);
      ui = null;
    }
    document.body.classList.remove('wc-open');
    if (!silent) {
      var d = engineDialog();
      var close = d ? q('button', d) : null;
      if (close) close.click();
    }
  }

  // 供原生返回键调用
  window.__whaleBack = function () {
    var about = q('.wc-about');
    if (about) { about.parentNode.removeChild(about); return 'closed'; }
    if (layers.length) { closeLayer(); return 'closed'; }
    if (ui) { closeSettingsPage(); return 'closed'; }
    return 'none';
  };

  /* --------------------------------------------------------- 标题 / 观察器 */

  var lastBrandFix = 0;

  function fixBrand() {
    var now = Date.now();
    if (now - lastBrandFix < 400) return;
    lastBrandFix = now;
    if (document.title !== APP_NAME) document.title = APP_NAME;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var node;
    var hits = [];
    while ((node = walker.nextNode())) {
      if (node.nodeValue && node.nodeValue.indexOf(OLD_BRAND) >= 0) hits.push(node);
    }
    hits.forEach(function (n) { n.nodeValue = n.nodeValue.split(OLD_BRAND).join(APP_NAME); });
  }

  var wasHidden = false;

  function sync() {
    fixBrand();
    var dialog = engineDialog();
    if (dialog && !ui) openSettingsPage();
    if (!dialog && ui) closeSettingsPage(true);

    if (ui) {
      // 引擎自己弹出的确认框（删除确认等）出现时，先让出屏幕
      var others = qa('[role="dialog"][aria-modal="true"]').filter(function (d) {
        return !q('[role="tablist"]', d);
      });
      var hiddenNow = others.length > 0;
      ui.root.style.display = hiddenNow ? 'none' : '';
      // 确认框关掉后（比如导入完成）重读一次状态，导出按钮才会亮起来
      if (wasHidden && !hiddenNow) refreshAfterShow();
      wasHidden = hiddenNow;
    }
  }

  /** 回到我们自己的界面后多读几次，避开引擎异步落库的延迟 */
  function refreshAfterShow() {
    [200, 1200, 3000].forEach(function (delay) {
      setTimeout(function () { if (ui) refresh(); }, delay);
    });
  }

  var pending = false;
  function scheduleSync() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; sync(); }, 80);
  }

  new MutationObserver(scheduleSync).observe(document.documentElement, {
    childList: true, subtree: true, characterData: true
  });

  document.title = APP_NAME;
  sync();
})();
