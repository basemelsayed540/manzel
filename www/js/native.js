/* جسر الواجهة ↔ Native (Capacitor plugin "Manzel").
 * لو التطبيق يعمل في متصفح عادي (بدون Capacitor) يتحول تلقائياً لوضع "تجريبي" يحاكي النتائج،
 * وده بس لاختبار الواجهة — لا يحمّل شيئاً فعلياً. */
(function () {
  'use strict';
  var cap = window.Capacitor;
  var isNative = !!(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform());
  var listeners = {};

  function fire(ev, data) {
    (listeners[ev] || []).slice().forEach(function (cb) { try { cb(data); } catch (e) { console.error(e); } });
  }
  function on(ev, cb) { (listeners[ev] = listeners[ev] || []).push(cb); }

  if (isNative) {
    var P = (cap.Plugins && cap.Plugins.Manzel) || cap.registerPlugin('Manzel');
    ['started', 'progress', 'done', 'failed', 'apkProgress'].forEach(function (ev) {
      P.addListener(ev, function (d) { fire(ev, d); });
    });
    window.Native = {
      isNative: true,
      on: on,
      call: function (method, args) {
        return P[method](args || {}).catch(function (e) {
          var err = new Error((e && e.message) || 'خطأ غير معروف');
          err.code = (e && e.code) || 'unknown';
          throw err;
        });
      }
    };
    return;
  }

  /* ---------------------- الوضع التجريبي (متصفح فقط) ---------------------- */
  var timers = {};
  function wait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function fail(code, message) { var e = new Error(message); e.code = code; return e; }
  function thumb(c) {
    return 'data:image/svg+xml;utf8,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="184" height="128"><rect width="184" height="128" fill="' + c + '"/>' +
      '<path d="M78 44l36 20-36 20z" fill="#fff" opacity=".85"/></svg>');
  }
  var mock = {
    analyze: function (a) {
      var u = (a.url || '').toLowerCase();
      return wait(900).then(function () {
        if (u.indexOf('private') >= 0) throw fail('private', 'الفيديو خاص أو يحتاج تسجيل دخول. التطبيق يحمّل الروابط العامة فقط.');
        if (u.indexOf('drm') >= 0) throw fail('drm', 'هذا المحتوى محمي بـ DRM ولا يمكن تحميله.');
        if (u.indexOf('bad') >= 0) throw fail('unsupported', 'الرابط غير مدعوم أو غير صحيح. تأكد أنه رابط فيديو عام.');
        if (u.indexOf('bot') >= 0) throw fail('extractor', 'الموقع غيّر طريقة العرض. اضغط «تحديث المحرك» من الإعدادات ثم أعد المحاولة.');
        var long_ = u.indexOf('long') >= 0;
        var small = u.indexOf('small') >= 0;
        var q = [{ id: 'best', label: 'أعلى جودة', detail: small ? '720p' : '1080p', fid: 'v1+a1', recode: false, size: 48e6 }];
        if (!small) q.push({ id: '1080', label: '1080', detail: '1080p', fid: 'v1+a1', recode: false, size: 48e6 });
        q.push({ id: '720', label: '720', detail: '720p', fid: 'v2+a1', recode: u.indexOf('recode') >= 0, size: 24e6 });
        q.push({ id: '480', label: '480', detail: '480p', fid: 'v3+a1', recode: false, size: 11e6 });
        q.push({ id: 'audio', label: 'صوت فقط MP3', detail: '', fid: 'audio', recode: false, size: 4e6 });
        return {
          title: 'فيديو تجريبي — ' + (a.url || '').replace(/^https?:\/\//, '').slice(0, 28),
          thumbnail: thumb('#4F46E5'), duration: long_ ? 245 : 38, uploader: 'قناة تجريبية',
          extractor: 'Mock', qualities: q
        };
      });
    },
    download: function (a) {
      var id = a.id, pct = 0, failIt = (a.url || '').toLowerCase().indexOf('fail') >= 0;
      setTimeout(function () { fire('started', { id: id }); }, 50);
      timers[id] = setInterval(function () {
        pct += 7 + Math.random() * 8;
        if (failIt && pct > 40) {
          clearInterval(timers[id]); delete timers[id];
          fire('failed', { id: id, code: 'network', message: 'مشكلة في الاتصال بالإنترنت. تأكد من الشبكة وأعد المحاولة.', detail: 'urlopen error timed out' });
          return;
        }
        if (pct >= 100) {
          clearInterval(timers[id]); delete timers[id];
          var audio = a.quality === 'audio';
          fire('progress', { id: id, percent: 100, speed: '', eta: 0, stage: 'saving' });
          setTimeout(function () {
            fire('done', {
              id: id, uri: 'content://mock/' + id, name: (a.title || 'video') + (audio ? '.mp3' : '.mp4'),
              size: audio ? 4e6 : 24e6, mime: audio ? 'audio/mpeg' : 'video/mp4',
              folder: audio ? 'Music/Manzel' : 'Movies/Manzel', friendly: a.url.indexOf('recode') < 0
            });
          }, 300);
          return;
        }
        fire('progress', {
          id: id, percent: pct, speed: (1.4 + Math.random()).toFixed(1) + 'MiB/s', eta: Math.round((100 - pct) / 12),
          stage: pct > 90 && a.recode ? 'recoding' : 'downloading'
        });
      }, 350);
      return wait(20).then(function () { return { queued: true }; });
    },
    cancel: function (a) {
      if (timers[a.id]) {
        clearInterval(timers[a.id]); delete timers[a.id];
        setTimeout(function () { fire('failed', { id: a.id, code: 'cancelled', message: 'تم الإلغاء', detail: '' }); }, 50);
      }
      return wait(10).then(function () { return {}; });
    },
    updateEngine: function () { return wait(1500).then(function () { return { status: 'updated', version: '2026.11.01', before: '2026.10.01' }; }); },
    appInfo: function () { return wait(30).then(function () { return { engineVersion: '2026.10.01 (تجريبي)', appVersionName: '1.0.0', appVersionCode: 10000, sdk: 0, updaterConfigured: true }; }); },
    getActive: function () { return wait(10).then(function () { return { active: [], finished: [] }; }); },
    ensurePermissions: function () { return wait(10).then(function () { return { granted: true }; }); },
    readClipboard: function () {
      if (navigator.clipboard && navigator.clipboard.readText) {
        return navigator.clipboard.readText().then(function (t) { return { text: t }; }, function () { return { text: '' }; });
      }
      return Promise.resolve({ text: '' });
    },
    openFile: function () { return Promise.resolve({}); },
    shareFile: function () { return Promise.resolve({}); },
    deleteFile: function () { return Promise.resolve({ deleted: true }); },
    makeStoryReady: function (a) {
      return wait(1200).then(function () {
        return { unchanged: false, uri: 'content://mock/story', name: (a.name || 'video').replace(/\.\w+$/, '') + ' (ستوري).mp4', size: 9e6, mime: 'video/mp4', folder: 'Movies/Manzel' };
      });
    },
    clearWebCache: function () { return Promise.resolve({}); },
    checkAppUpdate: function () {
      var want = /[?&]mockupdate=1/.test(location.search);
      return wait(500).then(function () {
        return want ? { available: true, configured: true, currentCode: 10000, currentName: '1.0.0', versionCode: 10001, versionName: '1.0.1', notes: 'تحسينات في المحرك' }
                    : { available: false, configured: true, currentCode: 10000, currentName: '1.0.0' };
      });
    },
    downloadAndInstallApk: function () {
      var p = 0;
      return new Promise(function (res) {
        var t = setInterval(function () {
          p += 20; fire('apkProgress', { percent: Math.min(p, 100) });
          if (p >= 100) { clearInterval(t); res({ status: 'installing' }); }
        }, 250);
      });
    }
  };

  window.Native = {
    isNative: false,
    on: on,
    call: function (method, args) {
      var fn = mock[method];
      return fn ? Promise.resolve().then(function () { return fn(args || {}); }) : Promise.reject(fail('unknown', 'غير مدعوم'));
    }
  };
})();
