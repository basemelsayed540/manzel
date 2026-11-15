package com.manzel.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import java.io.File

/**
 * الجسر بين الواجهة (HTML/JS) والـNative.
 * الأحداث المُرسلة للواجهة: started / progress / done / failed / apkProgress
 */
@CapacitorPlugin(
    name = "Manzel",
    permissions = [
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notif"),
        Permission(strings = [Manifest.permission.WRITE_EXTERNAL_STORAGE], alias = "storage")
    ]
)
class ManzelPlugin : Plugin() {

    override fun load() {
        Engine.sink = { name, data -> notifyListeners(name, data) }
    }

    override fun handleOnDestroy() {
        Engine.sink = null
    }

    // ------------------------------------------------------------ معلومات وتهيئة

    @PluginMethod
    fun appInfo(call: PluginCall) {
        Engine.workPool.execute {
            val o = JSObject()
            try {
                o.put("engineVersion", Engine.engineVersion(context))
            } catch (e: Exception) {
                o.put("engineVersion", "")
            }
            o.put("appVersionName", Updater.installedName(context))
            o.put("appVersionCode", Updater.installedCode(context))
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("updaterConfigured", Updater.configured())
            call.resolve(o)
        }
    }

    @PluginMethod
    fun getActive(call: PluginCall) {
        val active = JSONArray()
        Engine.jobs.values.forEach { active.put(Engine.jobJson(it)) }
        val fin = JSONArray()
        Engine.drainFinished().forEach { fin.put(it) }
        call.resolve(JSObject().put("active", active).put("finished", fin))
    }

    // ------------------------------------------------------------ تحليل وتحميل

    @PluginMethod
    fun analyze(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("الرابط مفقود", "bad_request")
        val referer = call.getString("referer") ?: ""
        Engine.analyzePool.execute {
            try {
                call.resolve(JSObject(Engine.analyze(context, url, referer).toString()))
            } catch (e: ManzelError) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                val m = Engine.classify(e.message ?: e.toString())
                call.reject(m.message, m.code)
            }
        }
    }

    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مفقود", "bad_request")
        val url = call.getString("url") ?: return call.reject("الرابط مفقود", "bad_request")
        val qualityId = call.getString("quality") ?: "best"
        val formatId = call.getString("formatId") ?: "bv*+ba/b"
        val recode = call.getBoolean("recode", false) ?: false
        val folder = call.getString("folder") ?: "movies"
        val title = call.getString("title") ?: ""
        val referer = call.getString("referer") ?: ""
        val start = call.getDouble("start") ?: -1.0
        val end = call.getDouble("end") ?: -1.0
        Engine.submit(context, Job(id, url, formatId, recode, folder, qualityId, title, referer, start, end))
        call.resolve(JSObject().put("queued", true))
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مفقود", "bad_request")
        Engine.cancel(id)
        call.resolve()
    }

    @PluginMethod
    fun updateEngine(call: PluginCall) {
        Engine.workPool.execute {
            try {
                val r = Engine.updateEngine(context)
                call.resolve(JSObject(r.toString()))
            } catch (e: Exception) {
                val m = Engine.classify(e.message ?: e.toString())
                val code = if (m.code == "unknown") "update_failed" else m.code
                call.reject("تعذّر تحديث المحرك. تأكد من الاتصال بالإنترنت.", code)
            }
        }
    }

    // ------------------------------------------------------------ صلاحيات

    @PluginMethod
    fun ensurePermissions(call: PluginCall) {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add("notif")
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add("storage")
        if (need.isEmpty()) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        requestPermissionForAliases(need.toTypedArray(), call, "permissionsCallback")
    }

    @PermissionCallback
    fun permissionsCallback(call: PluginCall) {
        val storageOk = Build.VERSION.SDK_INT > 28 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        // رفض الإشعارات لا يمنع التحميل؛ الصلاحية الضرورية فقط هي التخزين على أندرويد 9 وأقل
        call.resolve(JSObject().put("granted", storageOk))
    }

    // ------------------------------------------------------------ الحافظة والملفات

    @PluginMethod
    fun readClipboard(call: PluginCall) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        var text = ""
        if (clip != null && clip.itemCount > 0) {
            text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
        }
        call.resolve(JSObject().put("text", text))
    }

    @PluginMethod
    fun openFile(call: PluginCall) {
        val uri = call.getString("uri") ?: return call.reject("uri مفقود", "bad_request")
        val mime = call.getString("mime") ?: "video/*"
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(i)
            call.resolve()
        } catch (e: ActivityNotFoundException) {
            call.reject("لا يوجد تطبيق لفتح هذا الملف.", "no_app")
        } catch (e: Exception) {
            call.reject("تعذّر فتح الملف. ربما تم حذفه.", "open_failed")
        }
    }

    @PluginMethod
    fun shareFile(call: PluginCall) {
        val uri = call.getString("uri") ?: return call.reject("uri مفقود", "bad_request")
        val mime = call.getString("mime") ?: "video/*"
        val whatsapp = call.getBoolean("whatsapp", false) ?: false
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            if (whatsapp) {
                try {
                    activity.startActivity(Intent(send).setPackage("com.whatsapp"))
                    call.resolve()
                    return
                } catch (e: ActivityNotFoundException) {
                    // واتساب غير مثبت: نكمل بقائمة المشاركة العامة
                }
            }
            activity.startActivity(Intent.createChooser(send, "مشاركة"))
            call.resolve()
        } catch (e: Exception) {
            call.reject("تعذّرت المشاركة.", "share_failed")
        }
    }

    @PluginMethod
    fun deleteFile(call: PluginCall) {
        val uri = call.getString("uri") ?: return call.reject("uri مفقود", "bad_request")
        call.resolve(JSObject().put("deleted", MediaSaver.delete(context, uri)))
    }

    /** يجهّز الفيديو للستوري: لو أطول من 59 ثانية يُقص بدون إعادة ترميز ويُحفظ كنسخة جديدة. */
    @PluginMethod
    fun makeStoryReady(call: PluginCall) {
        val uri = call.getString("uri") ?: return call.reject("uri مفقود", "bad_request")
        val name = call.getString("name") ?: "video.mp4"
        val folder = call.getString("folder") ?: "movies"
        Engine.workPool.execute {
            var tmp: File? = null
            try {
                val u = Uri.parse(uri)
                val dur = MediaSaver.durationMs(context, u)
                if (dur in 1..59_900) {
                    call.resolve(JSObject().put("unchanged", true))
                    return@execute
                }
                tmp = MediaSaver.trimToTemp(context, u, 59_000)
                val base = name.substringBeforeLast('.')
                val saved = MediaSaver.save(
                    context, tmp, MediaSaver.safeName("$base (ستوري)") + ".mp4",
                    "video/mp4", MediaSaver.Kind.VIDEO, folder
                )
                call.resolve(saved.toJS().put("unchanged", false))
            } catch (e: ManzelError) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject("تعذّر تجهيز الفيديو للستوري.", "story_failed")
            } finally {
                tmp?.delete()
            }
        }
    }

    // ------------------------------------------------------------ الواجهة والتحديث

    @PluginMethod
    fun clearWebCache(call: PluginCall) {
        activity.runOnUiThread {
            try { bridge.webView.clearCache(true) } catch (ignored: Exception) {}
            call.resolve()
        }
    }

    @PluginMethod
    fun checkAppUpdate(call: PluginCall) {
        Engine.workPool.execute {
            val cur = Updater.installedCode(context)
            val o = JSObject().put("currentCode", cur).put("currentName", Updater.installedName(context))
            try {
                val r = Updater.fetchRemote()
                if (r == null) {
                    call.resolve(o.put("available", false).put("configured", Updater.configured()))
                } else {
                    call.resolve(
                        o.put("available", r.code > cur).put("configured", true)
                            .put("versionCode", r.code).put("versionName", r.name).put("notes", r.notes)
                    )
                }
            } catch (e: Exception) {
                call.resolve(o.put("available", false).put("error", true))
            }
        }
    }

    @PluginMethod
    fun downloadAndInstallApk(call: PluginCall) {
        if (!Updater.canInstall(context)) {
            try { Updater.openInstallPermissionSettings(context) } catch (ignored: Exception) {}
            call.resolve(JSObject().put("status", "needsPermission"))
            return
        }
        Engine.workPool.execute {
            try {
                val r = Updater.fetchRemote()
                    ?: throw ManzelError("update", "تعذّر الوصول لمعلومات التحديث.")
                if (r.code <= Updater.installedCode(context)) {
                    call.resolve(JSObject().put("status", "uptodate"))
                    return@execute
                }
                val apk = Updater.download(context, r) { p ->
                    notifyListeners("apkProgress", JSObject().put("percent", p))
                }
                Updater.install(context, apk)
                call.resolve(JSObject().put("status", "installing"))
            } catch (e: ManzelError) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject("تعذّر تحميل التحديث. تأكد من الاتصال بالإنترنت.", "network")
            }
        }
    }

    // ------------------------------------------------------------ فيديوهات الهاتف: اختيار + قص + MP3

    @PluginMethod
    fun pickVideos(call: PluginCall) {
        val multiple = call.getBoolean("multiple", true) ?: true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "pickResult")
    }

    @ActivityCallback
    fun pickResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val d = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || d == null) {
            return call.reject("لم يتم اختيار ملف.", "cancelled")
        }
        val uris = ArrayList<Uri>()
        val clip = d.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        else d.data?.let { uris.add(it) }
        Engine.analyzePool.execute {
            try {
                val arr = JSONArray()
                for (u in uris) {
                    try {
                        context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) { /* الصلاحية المؤقتة تكفي */ }
                    arr.put(Local.describe(context, u))
                }
                call.resolve(JSObject().put("files", arr))
            } catch (e: Exception) {
                call.reject("تعذّر قراءة الملفات المختارة.", "unknown")
            }
        }
    }

    @PluginMethod
    fun openTrimmer(call: PluginCall) {
        val uri = call.getString("uri") ?: return call.reject("الملف مفقود", "bad_request")
        val intent = Intent(context, TrimActivity::class.java)
            .putExtra("uri", uri)
            .putExtra("start", call.getDouble("start") ?: -1.0)
            .putExtra("end", call.getDouble("end") ?: -1.0)
        startActivityForResult(call, intent, "trimResult")
    }

    @ActivityCallback
    fun trimResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val d = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && d != null) {
            call.resolve(
                JSObject()
                    .put("start", d.getDoubleExtra("start", 0.0))
                    .put("end", d.getDoubleExtra("end", 0.0))
            )
        } else call.reject("تم إلغاء القص.", "cancelled")
    }

    @PluginMethod
    fun localProcess(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مفقود", "bad_request")
        val uri = call.getString("uri") ?: return call.reject("الملف مفقود", "bad_request")
        val mode = call.getString("mode") ?: "cut"
        val folder = call.getString("folder") ?: "movies"
        val title = call.getString("title") ?: ""
        val start = call.getDouble("start") ?: -1.0
        val end = call.getDouble("end") ?: -1.0
        val precise = call.getBoolean("precise", false) ?: false
        Engine.submit(
            context,
            Job(
                id = id, url = "", formatId = "", recode = false, folder = folder, qualityId = mode, title = title,
                startSec = start, endSec = end, localUri = uri, mode = mode, precise = precise
            )
        )
        call.resolve(JSObject().put("queued", true))
    }

    // ------------------------------------------------------------ متصفح التقاط الفيديو + حسابات اختيارية

    @PluginMethod
    fun sniff(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("الرابط مفقود", "bad_request")
        val intent = Intent(context, BrowserActivity::class.java).putExtra("url", url)
        startActivityForResult(call, intent, "sniffResult")
    }

    @ActivityCallback
    fun sniffResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val d = result.data
        val media = d?.getStringExtra("mediaUrl")
        if (result.resultCode == android.app.Activity.RESULT_OK && !media.isNullOrEmpty()) {
            call.resolve(
                JSObject()
                    .put("mediaUrl", media)
                    .put("pageUrl", d?.getStringExtra("pageUrl") ?: "")
                    .put("title", d?.getStringExtra("title") ?: "")
            )
        } else call.reject("تم إغلاق المتصفح بدون اختيار فيديو.", "cancelled")
    }

    @PluginMethod
    fun openLogin(call: PluginCall) {
        val site = call.getString("site") ?: return call.reject("الموقع مفقود", "bad_request")
        if (Session.site(site) == null) return call.reject("موقع غير مدعوم", "bad_request")
        val intent = Intent(context, LoginActivity::class.java).putExtra("site", site)
        startActivityForResult(call, intent, "loginResult")
    }

    @ActivityCallback
    fun loginResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        call.resolve(JSObject().put("saved", result.resultCode == android.app.Activity.RESULT_OK))
    }

    @PluginMethod
    fun loginStatus(call: PluginCall) {
        val o = JSObject()
        Session.sites.forEach { o.put(it.id, Session.isLoggedIn(context, it.id)) }
        call.resolve(o)
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        val site = call.getString("site") ?: return call.reject("الموقع مفقود", "bad_request")
        activity.runOnUiThread {
            Session.logout(context, site)
            call.resolve()
        }
    }
}
