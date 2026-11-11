/* منزّل — منطق الواجهة. يعمل داخل Capacitor ويتحدث مع الـplugin عبر window.Native */
(function () {
  'use strict';

  var WEB_VERSION = window.MANZEL_WEB_VERSION || 'dev';
  var ROWS_PER_TAB = 5;
  var HISTORY_MAX = 50;
  var DEFAULT_FILES = ['index.html', 'css/app.css', 'css/fonts.css', 'js/version.js', 'js/native.js', 'js/app.js', 'version.json'];
  var NO_RETRY = { private: 1, drm: 1, age: 1, unsupported: 1, live: 1, wrongtab: 1, geo: 1 };
  var NEEDS_ENGINE = { extractor: 1, bot: 1 };

  /* ----------------------------------------------------------------- أدوات */
  function $(s, r) { return (r || document).querySelector(s); }
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  function lsGet(k, d) { try { var v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }
  function lsSet(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* التخزين غير متاح */ } }
  function el(tag, cls, html) { var n = document.createElement(tag); if (cls) n.className = cls; if (html != null) n.innerHTML = html; return n; }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtDur(s) {
    s = Math.round(s || 0); if (s <= 0) return '';
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return h ? h + ':' + pad(m) + ':' + pad(x) : m + ':' + pad(x);
  }
  function fmtSize(b) {
    if (!b || b <= 0) return '';
    if (b >= 1073741824) return (b / 1073741824).toFixed(2) + ' GB';
    if (b >= 1048576) return (b / 1048576).toFixed(b >= 1.05e8 ? 0 : 1) + ' MB';
    return Math.max(1, Math.round(b / 1024)) + ' KB';
  }
  function fmtDate(t) {
    var d = new Date(t);
    return d.getFullYear() + '/' + pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function uid(p) { return p + '-' + Date.now().toString(36) + Math.floor(Math.random() * 1e3).toString(36); }

  var I = {
    check: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>',
    info: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7.5h.01"/></svg>',
    warn: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 2.5 20h19z"/><path d="M12 10v4.5M12 17.5h.01"/></svg>',
    chev: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>',
    x: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>',
    down: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/></svg>',
    clip: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="5" width="12" height="16" rx="2"/><path d="M9 5V3.5h6V5"/></svg>',
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"/></svg>',
    share: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="18" cy="5.5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="18.5" r="2.5"/><path d="m8.2 10.8 7.6-4M8.2 13.2l7.6 4"/></svg>',
    retry: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/></svg>',
    cut: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="6.5" r="2.5"/><circle cx="6" cy="17.5" r="2.5"/><path d="m8 8 11 9M8 16 19 7"/></svg>',
    trash: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
    film: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="m10 9.5 5 2.5-5 2.5z"/></svg>'
  };

  /* ----------------------------------------------------------------- المنصات */
  var PLATFORMS = [
    { id: 'facebook', name: 'فيسبوك', hosts: /(^|\.)(facebook\.com|fb\.watch|fb\.com|fb\.me)$/,
      icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 8.5h3V4.5h-3a4 4 0 0 0-4 4V11H7v4h3v6h4v-6h3l1-4h-4V8.5z"/></svg>' },
    { id: 'youtube', name: 'يوتيوب', hosts: /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$/,
      icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="6" width="18" height="12" rx="4"/><path d="m10 9.5 5 2.5-5 2.5z"/></svg>' },
    { id: 'instagram', name: 'انستجرام', hosts: /(^|\.)(instagram\.com|instagr\.am)$/,
      icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="5"/><circle cx="12" cy="12" r="3.6"/><path d="M16.8 7.2h.01"/></svg>' },
    { id: 'tiktok', name: 'تيك توك', hosts: /(^|\.)(tiktok\.com)$/,
      icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4v11.5a3.5 3.5 0 1 1-3.5-3.5"/><path d="M14 4c.4 2.4 2 4 4.5 4.2"/></svg>' }
  ];
  var PBYID = {}; PLATFORMS.forEach(function (p) { PBYID[p.id] = p; });

  var DEFAULT_Q = [
    { id: 'best', label: 'أعلى جودة', detail: '', fid: 'bv*+ba/b', recode: false, size: 0 },
    { id: '1080', label: '1080', detail: '1080p', fid: 'bv*[height<=1080]+ba/b[height<=1080]/b', recode: false, size: 0 },
    { id: '720', label: '720', detail: '720p', fid: 'bv*[height<=720]+ba/b[height<=720]/b', recode: false, size: 0 },
    { id: '480', label: '480', detail: '480p', fid: 'bv*[height<=480]+ba/b[height<=480]/b', recode: false, size: 0 },
    { id: 'audio', label: 'صوت فقط MP3', detail: '', fid: 'audio', recode: false, size: 0 }
  ];

  function platformOfUrl(u) {
    try {
      var h = new URL(u).hostname.toLowerCase();
      for (var i = 0; i < PLATFORMS.length; i++) if (PLATFORMS[i].hosts.test(h)) return PLATFORMS[i].id;
    } catch (e) { /* ليس رابطاً */ }
    return null;
  }
  function extractUrl(text) {
    var m = String(text || '').match(/https?:\/\/[^\s<>"']+/i);
    if (!m) return '';
    return m[0].replace(/[)\].,;،؛!؟]+$/, '');
  }

  /* ----------------------------------------------------------------- الحالة */
  var settings = Object.assign({ folder: 'movies', quality: 'best' }, lsGet('manzel.settings', {}));
  var history_ = lsGet('manzel.history', []);
  var rows = {};          // platformId -> [row]
  var rowByJob = {};      // jobId -> row
  var appInfo = { engineVersion: '', appVersionName: '', appVersionCode: 0, sdk: 0 };
  var activeTab = lsGet('manzel.tab', 'facebook');
  if (!PBYID[activeTab]) activeTab = 'facebook';

  function saveSettings() { lsSet('manzel.settings', settings); }
  function saveHistory() { lsSet('manzel.history', history_.slice(0, HISTORY_MAX)); }

  function persistRows() {
    var o = {};
    PLATFORMS.forEach(function (p) {
      rows[p.id].forEach(function (r) {
        if (!r.url && r.state === 'empty') return;
        o[r.id] = { url: r.url, state: r.state, info: r.info, qid: r.qid, story: r.story, jobId: r.jobId, result: r.result, err: r.err };
      });
    });
    lsSet('manzel.rows', o);
  }

  /* ----------------------------------------------------------------- الإشعارات (أعلى الشاشة) */
  function toast(kind, text, ms) {
    var box = $('#toasts');
    while (box.children.length >= 3) box.removeChild(box.firstChild);
    var t = el('div', 'toast ' + kind);
    t.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    t.innerHTML = (kind === 'success' ? I.check : kind === 'error' ? I.warn : I.info);
    var s = el('span'); s.textContent = text; t.appendChild(s);
    box.appendChild(t);
    var api = { dismiss: function () {
      if (!t.parentNode) return;
      t.classList.add('out'); setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 220);
    } };
    if (ms !== 0) setTimeout(api.dismiss, ms || (kind === 'error' ? 6000 : 3500));
    t.addEventListener('click', api.dismiss);
    return api;
  }

  /* ----------------------------------------------------------------- النوافذ (مع زر الرجوع) */
  var overlays = [];
  function openOverlay(node, opts) {
    opts = opts || {};
    var scrim = el('div', 'scrim ' + (opts.cls || ''));
    scrim.appendChild(node);
    $('#overlayRoot').appendChild(scrim);
    var ov = { scrim: scrim, onClose: opts.onClose, locked: !!opts.locked, pushed: !opts.locked, closing: false };
    overlays.push(ov);
    if (ov.pushed) { try { history.pushState({ mz: overlays.length }, ''); } catch (e) { ov.pushed = false; } }
    if (!opts.locked) {
      scrim.addEventListener('click', function (e) { if (e.target === scrim) closeOverlay(ov); });
    }
    return ov;
  }
  function removeOverlay(ov) {
    var i = overlays.indexOf(ov); if (i >= 0) overlays.splice(i, 1);
    if (ov.scrim.parentNode) ov.scrim.parentNode.removeChild(ov.scrim);
    if (ov.onClose) { try { ov.onClose(); } catch (e) { console.error(e); } }
  }
  function closeOverlay(ov) {
    if (ov.closing) return; ov.closing = true;
    if (ov.pushed && overlays[overlays.length - 1] === ov) { ov.viaBack = true; history.back(); }
    else removeOverlay(ov);
  }
  window.addEventListener('popstate', function () {
    for (var i = overlays.length - 1; i >= 0; i--) {
      if (overlays[i].pushed) { overlays[i].closing = true; removeOverlay(overlays[i]); return; }
    }
  });

  function confirmDialog(o) {
    return new Promise(function (resolve) {
      var box = el('div', 'box');
      var h = el('h2'); h.textContent = o.title; box.appendChild(h);
      var p = el('p'); p.textContent = o.text || ''; box.appendChild(p);
      var st = el('div', 'stack'); box.appendChild(st);
      var done = false, ov;
      function fin(v) { if (done) return; done = true; if (ov.locked) removeOverlay(ov); else closeOverlay(ov); resolve(v); }
      (o.buttons || [
        { label: o.ok || 'موافق', value: true, cls: o.danger ? 'danger' : 'primary' },
        { label: o.cancel || 'إلغاء', value: false, cls: 'ghost' }
      ]).forEach(function (b) {
        var btn = el('button', 'btn ' + (b.cls || ''));
        btn.type = 'button'; btn.textContent = b.label;
        btn.addEventListener('click', function () { fin(b.value); });
        st.appendChild(btn);
      });
      ov = openOverlay(box, { cls: 'dialog', locked: !!o.locked, onClose: function () { if (!done) { done = true; resolve(false); } } });
      if (o.locked) { ov.locked = true; }
    });
  }

  /* ----------------------------------------------------------------- الصفوف */
  var STATE_LABEL = { empty: 'فاضي', analyzing: 'جاري التحليل', ready: 'جاهز', queued: 'في الانتظار', downloading: 'جاري التحميل', done: 'تم', failed: 'فشل' };
  var STAGE_LABEL = {
    queued: 'في الانتظار…', downloading: 'جاري التحميل', merging: 'دمج الصوت والفيديو…', audio: 'تحويل إلى MP3…',
    recoding: 'تحويل إلى MP4 (H.264 + AAC) ليناسب واتساب…', saving: 'الحفظ في المعرض…', story: 'قص الفيديو للستوري (أقل من 60 ثانية)…'
  };

  function makeRow(p, n) {
    var r = {
      id: p.id + '-' + n, p: p, n: n, url: '', state: 'empty', info: null, qid: settings.quality, story: false,
      jobId: null, percent: 0, speed: '', eta: -1, stage: 'queued', result: null, err: null, token: 0, autoStart: false, toastA: null, started: 0
    };
    var s = el('section', 'row');
    s.setAttribute('data-p', p.id);
    s.innerHTML =
      '<div class="row-top"><span class="num"></span><span class="chip"></span></div>' +
      '<div class="field"><input type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="ألصق رابط الفيديو هنا" aria-label="رابط الفيديو">' +
      '<button type="button" class="icon-btn paste">' + I.clip + '<span>لصق</span></button>' +
      '<button type="button" class="icon-btn clear" aria-label="مسح">' + I.x + '</button></div>' +
      '<div class="preview"><div class="thumb">' + I.film + '<img alt="" referrerpolicy="no-referrer" hidden><span class="dur" hidden></span></div>' +
      '<div class="meta"><div class="title"></div><div class="sub"></div></div></div>' +
      '<div class="controls"><button type="button" class="btn quality" aria-haspopup="dialog"><span class="ql"></span>' + I.chev + '</button>' +
      '<button type="button" class="btn primary dl">' + I.down + '<span>تحميل</span></button></div>' +
      '<div class="progress"><div class="stage"></div><div class="bar"><i></i></div>' +
      '<div class="pline"><span class="pct"></span><span class="muted spd"></span></div>' +
      '<div class="actions"><button type="button" class="btn danger cancel">إلغاء</button></div></div>' +
      '<div class="doneBox"><div class="okmsg"><span class="okt">تم الحفظ في المعرض</span><small class="oks"></small></div>' +
      '<div class="warnmsg" hidden></div>' +
      '<div class="actions"><button type="button" class="btn primary open">' + I.play + '<span>فتح</span></button>' +
      '<button type="button" class="btn share">' + I.share + '<span>مشاركة</span></button>' +
      '<button type="button" class="btn story">' + I.cut + '<span>جاهز للستوري</span></button>' +
      '<button type="button" class="btn ghost fresh">' + I.x + '<span>مسح</span></button></div></div>' +
      '<div class="errBox"><div class="errmsg"><span class="em"></span><small class="ed"></small></div>' +
      '<div class="actions"><button type="button" class="btn primary retry">' + I.retry + '<span>إعادة المحاولة</span></button>' +
      '<button type="button" class="btn engine">تحديث المحرك</button>' +
      '<button type="button" class="btn ghost clear2">مسح</button></div></div>';
    r.el = s;
    r.q = function (sel) { return $(sel, s); };
    r.q('.num').textContent = n;
    r.input = r.q('input');
    bindRow(r);
    return r;
  }

  function bindRow(r) {
    var timer = null;
    r.input.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () { onUrlChanged(r, r.input.value); }, 700);
    });
    r.input.addEventListener('paste', function (e) {
      var t = (e.clipboardData && e.clipboardData.getData('text')) || '';
      if (t) { e.preventDefault(); clearTimeout(timer); onUrlChanged(r, t, true); }
    });
    r.input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { clearTimeout(timer); onUrlChanged(r, r.input.value); r.input.blur(); } });
    r.q('.paste').addEventListener('click', function () {
      Native.call('readClipboard').then(function (res) {
        var t = (res && res.text) || '';
        if (!t.trim()) return toast('error', 'الحافظة فاضية. انسخ رابط الفيديو أولاً.');
        onUrlChanged(r, t, true);
      }, function () { toast('error', 'تعذّر قراءة الحافظة. الصق الرابط يدوياً في المربع.'); });
    });
    r.q('.clear').addEventListener('click', function () { resetRow(r); });
    r.q('.clear2').addEventListener('click', function () { resetRow(r); });
    r.q('.fresh').addEventListener('click', function () { resetRow(r); });
    r.q('.quality').addEventListener('click', function () { openQualitySheet(r); });
    r.q('.dl').addEventListener('click', function () { startRow(r, true); });
    r.q('.cancel').addEventListener('click', function () { cancelRow(r); });
    r.q('.retry').addEventListener('click', function () { startRow(r, true); });
    r.q('.engine').addEventListener('click', function () { doUpdateEngine(false); });
    r.q('.open').addEventListener('click', function () { openResult(r.result); });
    r.q('.share').addEventListener('click', function () { shareResult(r.result); });
    r.q('.story').addEventListener('click', function () { storyFromResult(r); });
  }

  function qualitiesOf(r) { return (r.info && r.info.qualities && r.info.qualities.length) ? r.info.qualities : DEFAULT_Q; }
  function currentQ(r) {
    var l = qualitiesOf(r);
    for (var i = 0; i < l.length; i++) if (l[i].id === r.qid) return l[i];
    return l[0];
  }

  function render(r) {
    var s = r.el, st = r.state;
    s.setAttribute('data-state', st);
    s.setAttribute('data-info', r.info ? '1' : '0');
    r.q('.chip').textContent = STATE_LABEL[st] || '';
    var busy = st === 'queued' || st === 'downloading';
    if (r.input.value !== r.url) r.input.value = r.url;
    r.input.disabled = busy;
    r.q('.paste').disabled = busy; r.q('.clear').disabled = busy;

    // المعاينة
    var img = r.q('.thumb img'), dur = r.q('.dur');
    if (r.info) {
      r.q('.title').textContent = r.info.title || '';
      var sub = [r.info.uploader, fmtDur(r.info.duration) ? 'المدة ' + fmtDur(r.info.duration) : ''].filter(Boolean).join(' • ');
      r.q('.sub').textContent = sub;
      if (r.info.thumbnail) { if (img.getAttribute('src') !== r.info.thumbnail) img.src = r.info.thumbnail; img.hidden = false; img.onerror = function () { img.hidden = true; }; }
      else img.hidden = true;
      var d = fmtDur(r.info.duration); dur.textContent = d; dur.hidden = !d;
    } else {
      r.q('.title').textContent = st === 'analyzing' ? 'جاري تحليل الرابط…' : '';
      r.q('.sub').textContent = st === 'analyzing' ? 'يتم جلب العنوان والصورة والمدة' : '';
      img.hidden = true; dur.hidden = true;
    }

    // الجودة
    var q = currentQ(r);
    var ql = q.label + (q.detail && q.label !== q.detail && q.id !== 'audio' ? ' (' + q.detail + ')' : '');
    if (q.id !== 'audio' && /^\d+$/.test(q.label)) ql = q.label + 'p';
    if (r.story && q.id !== 'audio') ql += ' • ستوري';
    r.q('.ql').textContent = ql;
    r.q('.dl').disabled = st === 'analyzing';

    // أزرار الحالات
    r.q('.story').hidden = !(r.result && !r.result.audio && !r.result.storyDone);
    if (st === 'failed' && r.err) {
      r.q('.em').textContent = r.err.message || 'تعذّر إتمام العملية.';
      var det = r.err.detail ? String(r.err.detail).slice(-160) : '';
      r.q('.ed').textContent = det; r.q('.ed').hidden = !det;
      r.q('.retry').hidden = !!NO_RETRY[r.err.code];
      r.q('.engine').hidden = !NEEDS_ENGINE[r.err.code];
    }
    if (st === 'done' && r.result) {
      var res = r.result;
      r.q('.okt').textContent = res.storyDone ? 'تم الحفظ — جاهز للستوري' : (res.audio ? 'تم حفظ الصوت' : 'تم الحفظ في المعرض');
      r.q('.oks').textContent = [res.name, fmtSize(res.size), res.folder].filter(Boolean).join(' • ');
      var w = r.q('.warnmsg');
      if (res.friendly === false) { w.textContent = 'تنبيه: صيغة هذا الفيديو قد لا تعمل في ستوري واتساب.'; w.hidden = false; }
      else w.hidden = true;
    }
    renderProgress(r);
    updateBadges();
  }

  function renderProgress(r) {
    if (r.state !== 'queued' && r.state !== 'downloading') return;
    var stage = r.state === 'queued' ? 'queued' : r.stage;
    r.q('.stage').textContent = STAGE_LABEL[stage] || 'جاري التحميل';
    var showPct = r.state === 'downloading' && (stage === 'downloading' || stage === 'saving');
    r.q('.bar i').style.width = (r.state === 'queued' ? 0 : Math.max(2, Math.min(100, r.percent))) + '%';
    r.q('.pct').textContent = showPct ? Math.floor(r.percent) + '%' : '';
    var bits = [];
    if (showPct && r.speed) bits.push(r.speed);
    if (showPct && r.eta >= 0) bits.push('ETA ' + fmtDur(r.eta || 1));
    r.q('.spd').textContent = bits.join('  •  ');
    r.q('.cancel').hidden = stage === 'saving' || stage === 'story';
  }

  function setState(r, st) { r.state = st; render(r); persistRows(); }

  function resetRow(r) {
    r.token++;
    if (r.toastA) { r.toastA.dismiss(); r.toastA = null; }
    if (r.jobId) { delete rowByJob[r.jobId]; }
    r.url = ''; r.info = null; r.result = null; r.err = null; r.jobId = null; r.percent = 0; r.story = false;
    r.autoStart = false; r.qid = settings.quality;
    setState(r, 'empty');
  }

  /* ----------------------------------------------------------------- تحليل الرابط */
  function onUrlChanged(r, raw, immediate) {
    var url = extractUrl(raw) || String(raw || '').trim();
    if (url === r.url && (r.info || r.state === 'analyzing')) { render(r); return; }
    r.token++; r.info = null; r.result = null; r.err = null;
    if (r.toastA) { r.toastA.dismiss(); r.toastA = null; }
    r.url = url;
    if (!url) { r.autoStart = false; setState(r, 'empty'); return; }
    analyzeRow(r);
  }

  function analyzeRow(r) {
    var url = r.url;
    var found = platformOfUrl(url);
    if (!/^https?:\/\//i.test(url)) return failRow(r, 'unsupported', 'هذا ليس رابطاً صحيحاً. انسخ الرابط كاملاً (يبدأ بـ https://).');
    if (found && found !== r.p.id) {
      return failRow(r, 'wrongtab', 'هذا رابط ' + PBYID[found].name + '. افتح تبويب «' + PBYID[found].name + '» ثم ألصقه هناك.');
    }
    if (!found) {
      return failRow(r, 'unsupported', 'الرابط لا يبدو رابط ' + r.p.name + '. تأكد من نسخ رابط الفيديو من التطبيق.');
    }
    var tok = ++r.token;
    r.err = null;
    setState(r, 'analyzing');
    if (r.toastA) r.toastA.dismiss();
    r.toastA = toast('info', 'جاري تحليل الرابط (' + r.p.name + ' — صف ' + r.n + ')…', 0);
    Native.call('analyze', { url: url }).then(function (info) {
      if (tok !== r.token) return;
      if (r.toastA) { r.toastA.dismiss(); r.toastA = null; }
      r.info = info;
      var list = qualitiesOf(r), want = r.qid;
      if (!list.some(function (x) { return x.id === want; })) r.qid = 'best';
      else r.qid = want;
      setState(r, 'ready');
      if (r.autoStart) { r.autoStart = false; startRow(r, false); }
    }, function (e) {
      if (tok !== r.token) return;
      if (r.toastA) { r.toastA.dismiss(); r.toastA = null; }
      r.autoStart = false;
      failRow(r, e.code || 'unknown', e.message, e.detail);
    });
  }

  function failRow(r, code, message, detail) {
    r.err = { code: code, message: message, detail: detail || '' };
    setState(r, 'failed');
    toast('error', message);
  }

  /* ----------------------------------------------------------------- التحميل */
  function startRow(r, announce) {
    if (r.state === 'queued' || r.state === 'downloading' || r.state === 'analyzing') {
      if (r.state === 'analyzing') r.autoStart = true;
      return;
    }
    if (!r.url) { if (announce) toast('error', 'ألصق رابط الفيديو أولاً.'); return; }
    if (!r.info) { r.autoStart = true; r.err = null; analyzeRow(r); return; }
    var q = currentQ(r);
    var audio = q.id === 'audio';
    var jobId = uid(r.id);
    r.jobId = jobId; rowByJob[jobId] = r;
    r.percent = 0; r.speed = ''; r.eta = -1; r.stage = 'queued'; r.err = null; r.result = null; r.started = Date.now();
    setState(r, 'queued');
    Native.call('ensurePermissions').then(function (p) {
      if (p && p.granted === false) throw Object.assign(new Error('بدون صلاحية التخزين لا يمكن حفظ الفيديو. اسمح بها من إعدادات الهاتف ثم أعد المحاولة.'), { code: 'permission' });
      return Native.call('download', {
        id: jobId, url: r.url, quality: q.id, formatId: q.fid, recode: !!q.recode,
        folder: settings.folder, title: r.info.title || ''
      });
    }).catch(function (e) {
      delete rowByJob[jobId];
      r.jobId = null;
      failRow(r, e.code || 'unknown', e.message || 'تعذّر بدء التحميل.');
    });
    if (announce) toast('info', audio ? 'بدأ تحميل الصوت…' : 'بدأ التحميل…', 2200);
  }

  function cancelRow(r) {
    if (!r.jobId) return;
    r.q('.cancel').disabled = true;
    Native.call('cancel', { id: r.jobId }).catch(function () {});
    setTimeout(function () { r.q('.cancel').disabled = false; }, 1500);
  }

  function downloadAll(p) {
    var list = rows[p.id].filter(function (r) { return r.url && r.state !== 'queued' && r.state !== 'downloading' && r.state !== 'done'; });
    list = list.filter(function (r) { return !(r.state === 'failed' && r.err && NO_RETRY[r.err.code]); });
    if (!list.length) {
      var busy = rows[p.id].some(function (r) { return r.state === 'queued' || r.state === 'downloading'; });
      return toast('info', busy ? 'التحميلات شغّالة بالفعل.' : 'ألصق رابطاً واحداً على الأقل في هذا التبويب.');
    }
    toast('info', 'بدأ تحميل ' + list.length + (list.length === 1 ? ' فيديو' : ' ملفات') + ' (بالدور عند الازدحام).', 2600);
    list.forEach(function (r) { startRow(r, false); });
  }

  /* أحداث الـNative */
  function onStarted(d) {
    var r = rowByJob[d.id]; if (!r) return;
    r.stage = 'downloading'; if (r.state === 'queued') setState(r, 'downloading');
  }
  function onProgress(d) {
    var r = rowByJob[d.id]; if (!r) return;
    if (r.state === 'queued') r.state = 'downloading';
    r.percent = d.percent || 0; r.speed = d.speed || ''; r.eta = d.eta == null ? -1 : d.eta; r.stage = d.stage || 'downloading';
    if (r.el.getAttribute('data-state') !== r.state) render(r); else renderProgress(r);
  }
  function onDone(d) {
    var r = rowByJob[d.id]; if (!r) return;
    var q = currentQ(r);
    var res = { uri: d.uri, name: d.name, size: d.size, mime: d.mime, folder: d.folder, friendly: d.friendly, audio: q.id === 'audio', storyDone: false };
    var wantStory = r.story && !res.audio;
    if (wantStory) {
      r.stage = 'story'; r.percent = 100; r.state = 'downloading'; render(r);
      Native.call('makeStoryReady', { uri: d.uri, name: d.name, folder: settings.folder }).then(function (s) {
        if (s && s.unchanged) { res.storyDone = true; return finishDone(r, res); }
        Native.call('deleteFile', { uri: d.uri }).catch(function () {});
        res = { uri: s.uri, name: s.name, size: s.size, mime: s.mime || 'video/mp4', folder: s.folder, friendly: true, audio: false, storyDone: true };
        finishDone(r, res);
      }, function (e) {
        toast('error', e.message || 'تعذّر قص الفيديو للستوري. تم حفظ النسخة الأصلية.');
        finishDone(r, res);
      });
    } else finishDone(r, res);
  }
  function finishDone(r, res) {
    r.result = res; r.err = null; r.percent = 100;
    if (r.jobId) { delete rowByJob[r.jobId]; }
    setState(r, 'done');
    addHistory(r, res);
    toast('success', 'تم التحميل: ' + (r.info && r.info.title ? r.info.title : res.name));
  }
  function onFailed(d) {
    var r = rowByJob[d.id]; if (!r) return;
    delete rowByJob[d.id];
    r.jobId = null;
    if (d.code === 'cancelled') {
      r.err = null; r.percent = 0;
      setState(r, r.info ? 'ready' : 'empty');
      return toast('info', 'تم إلغاء التحميل.', 2200);
    }
    failRow(r, d.code || 'unknown', d.message || 'فشل التحميل.', d.detail);
  }

  function resync() {
    Native.call('getActive').then(function (res) {
      (res.finished || []).forEach(function (f) {
        if (f.event === 'done') onDone(f.data); else if (f.event === 'failed') onFailed(f.data);
      });
      var live = {};
      (res.active || []).forEach(function (a) {
        live[a.id] = 1;
        var r = rowByJob[a.id]; if (r) onProgress(a);
      });
      // صف ينتظر نتيجة لا نعرف عنها شيئاً (ضاعت أحداثها أثناء إيقاف الواجهة)
      Object.keys(rowByJob).forEach(function (jid) {
        var r = rowByJob[jid];
        if (!live[jid] && (r.state === 'queued' || r.state === 'downloading') && Date.now() - r.started > 4000 && r.stage !== 'story') {
          delete rowByJob[jid]; r.jobId = null;
          failRow(r, 'interrupted', 'لم تصلنا نتيجة هذا التحميل. ربما اكتمل — راجع المعرض أو السجل، أو أعد المحاولة.');
        }
      });
    }, function () {});
  }

  /* نتائج الملفات */
  function openResult(res) {
    if (!res) return;
    Native.call('openFile', { uri: res.uri, mime: res.mime }).catch(function (e) { toast('error', e.message); });
  }
  function shareResult(res) {
    if (!res) return;
    Native.call('shareFile', { uri: res.uri, mime: res.mime }).catch(function (e) { toast('error', e.message); });
  }
  function storyFromResult(r) {
    var res = r.result; if (!res) return;
    var btn = r.q('.story'); btn.disabled = true; var lab = btn.querySelector('span'); lab.textContent = 'جاري التجهيز…';
    Native.call('makeStoryReady', { uri: res.uri, name: res.name, folder: settings.folder }).then(function (s) {
      btn.disabled = false; lab.textContent = 'جاهز للستوري';
      if (s && s.unchanged) {
        res.storyDone = true; setState(r, 'done');
        return toast('success', 'الفيديو أقل من 60 ثانية — جاهز للستوري.');
      }
      var nr = { uri: s.uri, name: s.name, size: s.size, mime: s.mime || 'video/mp4', folder: s.folder, friendly: true, audio: false, storyDone: true };
      r.result = nr; setState(r, 'done'); addHistory(r, nr);
      toast('success', 'تم حفظ نسخة جاهزة للستوري (أقل من 60 ثانية).');
    }, function (e) {
      btn.disabled = false; lab.textContent = 'جاهز للستوري';
      toast('error', e.message || 'تعذّر تجهيز الفيديو للستوري.');
    });
  }

  /* ----------------------------------------------------------------- نافذة اختيار الجودة */
  function openQualitySheet(r) {
    var busy = r.state === 'queued' || r.state === 'downloading';
    if (busy) return;
    var sheet = el('div', 'sheet'); sheet.setAttribute('role', 'dialog'); sheet.setAttribute('aria-label', 'اختيار الجودة');
    sheet.setAttribute('data-p', r.p.id);
    var ov;
    function draw() {
      sheet.innerHTML = '<div class="grip"></div><div class="sheet-head"><h2>اختر الجودة</h2><button type="button" class="icon-btn" aria-label="إغلاق">' + I.x + '</button></div>';
      $('.sheet-head .icon-btn', sheet).addEventListener('click', function () { closeOverlay(ov); });
      if (!r.info) {
        var n = el('div', 'warnmsg'); n.style.marginBottom = '10px';
        n.textContent = 'ستظهر هنا الجودات المتاحة فعلاً لهذا الرابط بعد تحليله.'; sheet.appendChild(n);
      }
      qualitiesOf(r).forEach(function (q) {
        var b = el('button', 'opt'); b.type = 'button'; b.setAttribute('role', 'radio'); b.setAttribute('aria-checked', String(q.id === r.qid));
        var t = el('span'); var main = q.id === 'audio' ? q.label : (/^\d+$/.test(q.label) ? q.label + 'p' : q.label);
        t.textContent = main;
        var meta = [q.id !== 'audio' && q.detail && q.detail !== main ? q.detail : '', q.size ? '≈ ' + fmtSize(q.size) : '', q.recode ? 'يُحوَّل لـ MP4' : ''].filter(Boolean).join(' • ');
        if (meta) { var sm = el('small'); sm.textContent = meta; t.appendChild(sm); }
        b.appendChild(t);
        if (q.id === r.qid) { var tk = el('span', 'tick', I.check); b.appendChild(tk); }
        b.addEventListener('click', function () { r.qid = q.id; if (q.id === 'audio') r.story = false; setState(r, r.state); closeOverlay(ov); });
        sheet.appendChild(b);
      });
      if (r.qid !== 'audio') {
        var sb = el('button', 'opt'); sb.type = 'button'; sb.setAttribute('role', 'switch'); sb.setAttribute('aria-checked', String(!!r.story));
        sb.innerHTML = '<span>جاهز للستوري<small>يقص الفيديو لأقل من 60 ثانية ويتأكد من توافقه مع واتساب</small></span>';
        if (r.story) sb.appendChild(el('span', 'tick', I.check));
        sb.addEventListener('click', function () { r.story = !r.story; setState(r, r.state); draw(); });
        sheet.appendChild(sb);
      }
    }
    draw();
    ov = openOverlay(sheet);
  }

  /* ----------------------------------------------------------------- التبويبات */
  function buildUI() {
    var panels = $('#panels'), tabs = $('#tabs');
    PLATFORMS.forEach(function (p) {
      rows[p.id] = [];
      var panel = el('section', 'panel'); panel.id = 'panel-' + p.id; panel.setAttribute('data-p', p.id);
      panel.setAttribute('role', 'tabpanel');
      var bar = el('div', 'panel-bar');
      bar.innerHTML = '<div class="panel-title">' + p.icon + '<div>' + p.name + '<span class="panel-sub">' + ROWS_PER_TAB + ' روابط في نفس الوقت</span></div></div>' +
        '<button type="button" class="btn primary sm all">' + I.down + '<span>تحميل الكل</span></button>';
      $('.all', bar).addEventListener('click', function () { downloadAll(p); });
      panel.appendChild(bar);
      for (var i = 1; i <= ROWS_PER_TAB; i++) {
        var r = makeRow(p, i); rows[p.id].push(r); panel.appendChild(r.el);
      }
      panels.appendChild(panel);

      var tb = el('button', 'tab'); tb.type = 'button'; tb.id = 'tab-' + p.id; tb.setAttribute('role', 'tab'); tb.setAttribute('data-p', p.id);
      tb.setAttribute('aria-controls', 'panel-' + p.id);
      tb.innerHTML = p.icon + '<span>' + p.name + '</span><span class="count" hidden></span>';
      tb.addEventListener('click', function () { selectTab(p.id); });
      tabs.appendChild(tb);
    });
    selectTab(activeTab);
  }

  function selectTab(id) {
    activeTab = id; lsSet('manzel.tab', id);
    PLATFORMS.forEach(function (p) {
      $('#panel-' + p.id).classList.toggle('active', p.id === id);
      $('#tab-' + p.id).setAttribute('aria-selected', String(p.id === id));
    });
    $('#panels').scrollTop = 0;
  }

  function updateBadges() {
    PLATFORMS.forEach(function (p) {
      var n = rows[p.id].filter(function (r) { return r.state === 'queued' || r.state === 'downloading'; }).length;
      var c = $('#tab-' + p.id + ' .count'); if (!c) return;
      c.hidden = n === 0; c.textContent = n;
    });
  }

  /* ----------------------------------------------------------------- السجل */
  function addHistory(r, res) {
    history_.unshift({
      id: uid('h'), title: (r.info && r.info.title) || res.name, thumb: (r.info && r.info.thumbnail && r.info.thumbnail.length < 1500) ? r.info.thumbnail : '',
      platform: r.p.id, uri: res.uri, name: res.name, mime: res.mime, size: res.size, folder: res.folder, date: Date.now(), audio: !!res.audio
    });
    history_ = history_.slice(0, HISTORY_MAX); saveHistory();
  }

  function openHistory() {
    var sheet = el('div', 'sheet full'); var ov;
    function draw() {
      sheet.innerHTML = '<div class="sheet-head"><h2>سجل التحميلات</h2><button type="button" class="icon-btn" aria-label="إغلاق">' + I.x + '</button></div>';
      $('.icon-btn', sheet).addEventListener('click', function () { closeOverlay(ov); });
      if (!history_.length) { sheet.appendChild(el('div', 'empty', 'لا توجد تحميلات بعد.<br>الفيديوهات التي تحمّلها ستظهر هنا.')); return; }
      history_.forEach(function (h) {
        var it = el('div', 'hist-item');
        var th = el('div', 'thumb', I.film);
        if (h.thumb) { var im = el('img'); im.alt = ''; im.referrerPolicy = 'no-referrer'; im.src = h.thumb; im.onerror = function () { im.remove(); }; th.appendChild(im); }
        var meta = el('div', 'meta'); var t = el('div', 'title'); t.textContent = h.title;
        var sb = el('div', 'sub'); sb.textContent = [PBYID[h.platform] ? PBYID[h.platform].name : '', fmtSize(h.size), fmtDate(h.date)].filter(Boolean).join(' • ');
        meta.appendChild(t); meta.appendChild(sb);
        var ac = el('div', 'hist-actions');
        var ob = el('button', 'btn primary', I.play + '<span>فتح</span>'); ob.type = 'button';
        ob.addEventListener('click', function () { openResult(h); });
        var db = el('button', 'btn danger', I.trash + '<span>حذف</span>'); db.type = 'button';
        db.addEventListener('click', function () {
          confirmDialog({
            title: 'حذف من السجل', text: 'هل تريد حذف الملف من الهاتف أيضاً؟',
            buttons: [
              { label: 'حذف الملف والسجل', value: 'both', cls: 'danger' },
              { label: 'إزالة من السجل فقط', value: 'log', cls: '' },
              { label: 'إلغاء', value: false, cls: 'ghost' }
            ]
          }).then(function (v) {
            if (!v) return;
            var rm = function () { history_ = history_.filter(function (x) { return x.id !== h.id; }); saveHistory(); draw(); };
            if (v === 'both') Native.call('deleteFile', { uri: h.uri }).then(function (r) { rm(); toast('success', r && r.deleted ? 'تم حذف الملف.' : 'الملف غير موجود (تمت إزالته من السجل).'); }, function () { toast('error', 'تعذّر حذف الملف.'); });
            else rm();
          });
        });
        ac.appendChild(ob); ac.appendChild(db);
        it.appendChild(th); it.appendChild(meta); it.appendChild(ac); sheet.appendChild(it);
      });
    }
    draw(); ov = openOverlay(sheet);
  }

  /* ----------------------------------------------------------------- الإعدادات */
  function openSettings() {
    var sheet = el('div', 'sheet full'); var ov;
    function seg(items, cur, on) {
      var d = el('div', 'seg');
      items.forEach(function (it) {
        var b = el('button'); b.type = 'button'; b.textContent = it[1]; b.setAttribute('aria-pressed', String(it[0] === cur));
        b.addEventListener('click', function () { on(it[0]); draw(); }); d.appendChild(b);
      });
      return d;
    }
    function group(title) { var g = el('div', 'set-group'); var h = el('h3'); h.textContent = title; g.appendChild(h); sheet.appendChild(g); return g; }
    function draw() {
      sheet.innerHTML = '<div class="sheet-head"><h2>الإعدادات</h2><button type="button" class="icon-btn" aria-label="إغلاق">' + I.x + '</button></div>';
      $('.icon-btn', sheet).addEventListener('click', function () { closeOverlay(ov); });

      var g1 = group('مكان الحفظ');
      g1.appendChild(seg([['movies', 'Movies/Manzel'], ['dcim', 'DCIM/Manzel']], settings.folder, function (v) { settings.folder = v; saveSettings(); }));
      var n1 = el('div', 'sub'); n1.style.whiteSpace = 'normal'; n1.style.marginTop = '6px';
      n1.textContent = 'يُحفظ في معرض الهاتف فيظهر في الاستوديو وواتساب مباشرة. الملفات الصوتية تُحفظ في Music/Manzel.';
      g1.appendChild(n1);

      var g2 = group('الجودة الافتراضية');
      g2.appendChild(seg([['best', 'أعلى جودة'], ['1080', '1080'], ['720', '720'], ['480', '480'], ['audio', 'MP3']], settings.quality, function (v) { settings.quality = v; saveSettings(); }));

      var g3 = group('محرك التحميل');
      var b3 = el('button', 'btn primary', I.retry + '<span>تحديث المحرك</span>'); b3.type = 'button'; b3.style.width = '100%';
      b3.addEventListener('click', function () {
        b3.disabled = true; b3.querySelector('span').textContent = 'جاري التحديث…';
        doUpdateEngine(true).then(function () { draw(); });
      });
      g3.appendChild(b3);
      var n3 = el('div', 'sub'); n3.style.whiteSpace = 'normal'; n3.style.marginTop = '6px';
      n3.textContent = 'المنصات تغيّر نفسها كثيراً. التطبيق يحدّث المحرك تلقائياً مرة كل يوم عند الفتح، ويمكنك التحديث يدوياً هنا.';
      g3.appendChild(n3);

      var g4 = group('السجل');
      var b4 = el('button', 'btn danger', I.trash + '<span>حذف السجل</span>'); b4.type = 'button'; b4.style.width = '100%';
      b4.addEventListener('click', function () {
        if (!history_.length) return toast('info', 'السجل فاضي بالفعل.');
        confirmDialog({ title: 'حذف السجل', text: 'سيتم حذف قائمة التحميلات فقط، أما الفيديوهات فتبقى في المعرض.', ok: 'حذف السجل', danger: true }).then(function (ok) {
          if (ok) { history_ = []; saveHistory(); toast('success', 'تم حذف السجل.'); }
        });
      });
      g4.appendChild(b4);

      var g5 = group('الإصدارات');
      var kv = el('dl', 'kv');
      [['التطبيق', appInfo.appVersionName || '—'], ['الواجهة', WEB_VERSION], ['المحرك (yt-dlp)', appInfo.engineVersion || '—'], ['أندرويد (API)', appInfo.sdk ? String(appInfo.sdk) : '—']].forEach(function (p) {
        var dt = el('dt'); dt.textContent = p[0]; var dd = el('dd'); dd.textContent = p[1]; kv.appendChild(dt); kv.appendChild(dd);
      });
      g5.appendChild(kv);

      var g6 = group('عن التطبيق');
      var b6 = el('button', 'btn', I.info + '<span>تنبيه الاستخدام</span>'); b6.type = 'button'; b6.style.width = '100%';
      b6.addEventListener('click', function () { showDisclaimer(false); });
      g6.appendChild(b6);
    }
    draw(); ov = openOverlay(sheet);
  }

  /* ----------------------------------------------------------------- تحديث المحرك */
  var engineBusy = false;
  function doUpdateEngine(manual) {
    if (engineBusy) return Promise.resolve();
    engineBusy = true;
    var tt = manual ? toast('info', 'جاري تحديث محرك التحميل…', 0) : null;
    return Native.call('updateEngine').then(function (r) {
      if (tt) tt.dismiss();
      if (r.version) appInfo.engineVersion = r.version;
      if (r.status === 'busy') { if (manual) toast('error', 'أوقف التحميلات الجارية ثم حدّث المحرك.'); return; }
      lsSet('manzel.engineAt', Date.now());
      if (r.status === 'updated') toast('success', 'تم تحديث المحرك' + (r.version ? ' إلى ' + r.version : '') + '.');
      else if (manual) toast('success', 'المحرك محدّث بالفعل' + (r.version ? ' (' + r.version + ')' : '') + '.');
    }, function (e) {
      if (tt) tt.dismiss();
      if (manual) toast('error', e.message || 'تعذّر تحديث المحرك.');
    }).then(function () { engineBusy = false; });
  }
  function autoUpdateEngine() {
    var last = lsGet('manzel.engineAt', 0);
    if (Date.now() - last < 24 * 3600 * 1000) return;
    doUpdateEngine(false);
  }

  /* ----------------------------------------------------------------- تحديث التطبيق (APK) */
  var pendingApp = null, apkBusy = false, apkListening = false, apkCb = null;
  function showAppBanner(u) {
    pendingApp = u;
    $('#abTitle').textContent = 'تحديث جديد للتطبيق';
    $('#abSub').textContent = 'الإصدار ' + u.versionName + (u.notes ? ' — ' + u.notes : '') + ' (الحالي ' + u.currentName + ')';
    $('#appBanner').hidden = false; document.body.classList.add('has-banner');
    $('#updDot').hidden = false;
  }
  function installApk(setProgress) {
    if (apkBusy) return Promise.resolve('busy');
    apkBusy = true;
    var abBar = $('#abBar'); abBar.hidden = false; $('#abBtn').disabled = true;
    apkCb = setProgress || null;
    if (!apkListening) {
      apkListening = true;
      Native.on('apkProgress', function (d) {
        var p = d.percent || 0; $('i', abBar).style.width = p + '%'; if (apkCb) apkCb(p);
      });
    }
    return Native.call('downloadAndInstallApk').then(function (r) {
      apkBusy = false; $('#abBtn').disabled = false;
      if (r.status === 'needsPermission') {
        abBar.hidden = true;
        toast('info', 'فعّل «التثبيت من مصادر غير معروفة» لمنزّل من الشاشة التي فُتحت، ثم ارجع واضغط تحديث مرة أخرى.', 8000);
      } else if (r.status === 'installing') {
        toast('success', 'تم تحميل التحديث. أكمل التثبيت من الشاشة التي ظهرت.', 6000);
      } else if (r.status === 'uptodate') {
        abBar.hidden = true; $('#appBanner').hidden = true; document.body.classList.remove('has-banner');
      }
      return r.status;
    }, function (e) {
      apkBusy = false; $('#abBtn').disabled = false; abBar.hidden = true;
      toast('error', e.message || 'تعذّر تحديث التطبيق.');
      return 'error';
    });
  }

  /* ----------------------------------------------------------------- زر التحديث العلوي */
  var updating = false;
  function runUpdate() {
    if (updating) return; updating = true;
    var btn = $('#btnUpdate'); btn.classList.add('spin');
    var box = el('div', 'box');
    box.innerHTML = '<h2></h2><p></p><div class="pbar"><i></i></div><div class="pct">0%</div><div class="stack" style="margin-top:14px" hidden><button type="button" class="btn">إغلاق</button></div>';
    var h2 = $('h2', box), msg = $('p', box), bar = $('.pbar i', box), pct = $('.pct', box), stack = $('.stack', box);
    var ov = openOverlay(box, { cls: 'upd', locked: true });
    function setP(n, m, t) { bar.style.width = n + '%'; pct.textContent = Math.round(n) + '%'; if (m != null) msg.textContent = m; if (t) h2.textContent = t; }
    function end(m, t) {
      setP(100, m, t);
      stack.hidden = false; $('button', stack).addEventListener('click', function () { removeOverlay(ov); });
      updating = false; btn.classList.remove('spin');
    }
    function autoClose(m, t) {
      setP(100, m, t); updating = false; btn.classList.remove('spin');
      setTimeout(function () { removeOverlay(ov); }, 1700);
    }
    setP(4, 'جاري البحث عن تحديثات…', 'تحديث منزّل');

    var webP = fetch('version.json?t=' + Date.now(), { cache: 'no-store' }).then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); }).catch(function () { return null; });
    var appP = Native.call('checkAppUpdate').catch(function () { return null; });
    Promise.all([webP, appP]).then(function (res) {
      var web = res[0], app = res[1];
      if (!web && !app) return end('تعذّر الاتصال. تأكد من الإنترنت وحاول مرة أخرى.', 'فشل التحديث');
      if (app && app.available) {
        showAppBanner(app);
        setP(2, 'جاري تحميل الإصدار ' + app.versionName + ' من التطبيق…', 'تحديث التطبيق');
        return installApk(function (p) { setP(p, 'جاري تحميل الإصدار ' + app.versionName + '…'); }).then(function (st) {
          if (st === 'installing') end('اكتمل التحميل. أكمل التثبيت من الشاشة التي ظهرت، وسيُعاد فتح التطبيق بعده.', 'جاهز للتثبيت');
          else if (st === 'needsPermission') end('فعّل «التثبيت من مصادر غير معروفة» لمنزّل ثم اضغط تحديث مرة أخرى.', 'صلاحية مطلوبة');
          else end('تعذّر تحميل تحديث التطبيق. حاول لاحقاً.', 'فشل التحديث');
        });
      }
      if (!web) return end('تعذّر فحص تحديث الواجهة. تأكد من الإنترنت.', 'فشل التحديث');
      var remote = String(web.web || '');
      if (remote && remote === WEB_VERSION) {
        return autoClose('أنت على آخر إصدار — الواجهة ' + WEB_VERSION + (app && app.currentName ? ' • التطبيق ' + app.currentName : ''), 'كل شيء محدّث');
      }
      var files = (web.files && web.files.length) ? web.files : DEFAULT_FILES;
      var base = location.href.split('#')[0].split('?')[0].replace(/[^/]*$/, '');
      setP(5, 'جاري تحميل الإصدار ' + remote + '…', 'تحديث الواجهة');
      var i = 0;
      return files.reduce(function (p, f) {
        return p.then(function () {
          return fetch(base + f + (f === 'index.html' || f === 'version.json' ? '' : '?v=' + encodeURIComponent(remote)), { cache: 'reload' }).then(function (r) {
            if (!r.ok) throw new Error('http ' + r.status);
            return r.arrayBuffer();
          }).then(function () { i++; setP(5 + (i / files.length) * 80); });
        });
      }, Promise.resolve()).then(function () {
        setP(88, 'جاري مسح الكاش…');
        var jobs = [];
        if (window.caches && caches.keys) jobs.push(caches.keys().then(function (ks) { return Promise.all(ks.map(function (k) { return caches.delete(k); })); }));
        if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) jobs.push(navigator.serviceWorker.getRegistrations().then(function (rs) { return Promise.all(rs.map(function (x) { return x.unregister(); })); }));
        jobs.push(Native.call('clearWebCache').catch(function () {}));
        return Promise.all(jobs.map(function (j) { return j.catch(function () {}); }));
      }).then(function () {
        setP(96, 'جاري إعادة فتح التطبيق…');
        lsSet('manzel.expect', remote);
        setTimeout(function () { location.replace(base + '?r=' + Date.now()); }, 500);
      });
    }).catch(function () {
      end('حدث خطأ أثناء التحديث. تأكد من الإنترنت وحاول مرة أخرى.', 'فشل التحديث');
    });
  }

  function verifyAfterReload() {
    var exp = lsGet('manzel.expect', null);
    if (!exp) return;
    lsSet('manzel.expect', null);
    if (exp === WEB_VERSION) toast('success', 'تم تحديث الواجهة بنجاح — الإصدار ' + WEB_VERSION + ' ✓', 5000);
    else toast('error', 'أُعيد فتح التطبيق لكن الإصدار الظاهر ' + WEB_VERSION + ' وليس ' + exp + '. جرّب زر التحديث مرة أخرى بعد دقيقة.', 9000);
  }

  /* ----------------------------------------------------------------- الوضع الفاتح/الداكن */
  function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme');
    var nx = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', nx); lsSet('manzel.theme', nx);
  }

  /* ----------------------------------------------------------------- تنبيه أول فتح */
  function showDisclaimer(first) {
    return confirmDialog({
      title: 'قبل البدء',
      text: 'منزّل للاستخدام الشخصي فقط: لتحميل محتوى عام، أو محتوى تملكه، أو لديك إذن بتحميله. ' +
            'احترم حقوق أصحاب المحتوى وشروط كل منصة. التطبيق لا يدعم تسجيل الدخول ولا الفيديوهات الخاصة ولا المحتوى المحمي بـ DRM، ' +
            'وستظهر لك رسالة واضحة بدل المحاولة إن كان الرابط كذلك.',
      buttons: [{ label: first ? 'فهمت وأوافق' : 'حسناً', value: true, cls: 'primary' }],
      locked: first
    }).then(function () { if (first) lsSet('manzel.ack', 1); });
  }

  /* ----------------------------------------------------------------- استرجاع الصفوف */
  function restoreRows() {
    var saved = lsGet('manzel.rows', {});
    PLATFORMS.forEach(function (p) {
      rows[p.id].forEach(function (r) {
        var s = saved[r.id]; if (!s) { render(r); return; }
        r.url = s.url || ''; r.info = s.info || null; r.qid = s.qid || settings.quality; r.story = !!s.story;
        r.result = s.result || null; r.err = s.err || null; r.jobId = s.jobId || null;
        var st = s.state;
        if (st === 'analyzing') st = r.info ? 'ready' : (r.url ? 'empty' : 'empty');
        if ((st === 'queued' || st === 'downloading')) {
          if (r.jobId) { rowByJob[r.jobId] = r; r.started = Date.now(); r.stage = 'downloading'; }
          else st = r.info ? 'ready' : 'empty';
        }
        if (st === 'done' && !r.result) st = r.info ? 'ready' : 'empty';
        if (st === 'failed' && !r.err) st = r.info ? 'ready' : 'empty';
        r.state = st; render(r);
      });
    });
  }

  /* ----------------------------------------------------------------- تشغيل */
  function boot() {
    buildUI();
    restoreRows();
    if (!Native.isNative) $('#mockBadge').hidden = false;

    Native.on('started', onStarted); Native.on('progress', onProgress);
    Native.on('done', onDone); Native.on('failed', onFailed);

    $('#btnTheme').addEventListener('click', toggleTheme);
    $('#btnHistory').addEventListener('click', openHistory);
    $('#btnSettings').addEventListener('click', openSettings);
    $('#btnUpdate').addEventListener('click', runUpdate);
    $('#abBtn').addEventListener('click', function () { installApk(); });
    document.addEventListener('visibilitychange', function () { if (!document.hidden) resync(); });

    verifyAfterReload();

    var go = function () {
      Native.call('ensurePermissions').then(function (p) {
        if (p && p.granted === false) toast('error', 'صلاحية التخزين مطلوبة لحفظ الفيديوهات على أندرويد 9 وأقل.', 7000);
      }, function () {});
      Native.call('appInfo').then(function (i) { appInfo = i; }, function () {});
      resync();
      Native.call('checkAppUpdate').then(function (u) { if (u && u.available) showAppBanner(u); }, function () {});
      setTimeout(autoUpdateEngine, 4000);
    };
    if (!lsGet('manzel.ack', 0)) showDisclaimer(true).then(go); else go();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
