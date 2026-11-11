package com.manzel.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
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
        Engine.analyzePool.execute {
            try {
                call.resolve(JSObject(Engine.analyze(context, url).toString()))
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
        Engine.submit(context, Job(id, url, formatId, recode, folder, qualityId, title))
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
}
