package com.manzel.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * تحديث الجزء Native: يقرأ version.json من Release باسم latest-apk،
 * ولو فيه إصدار أحدث يحمّل الـAPK ويفتح شاشة التثبيت.
 * التحديث ينجح فقط لو الـAPK موقّع بنفس الـkeystore.
 */
object Updater {
    class Remote(val code: Long, val name: String, val notes: String, val apkUrl: String, val sha256: String)

    fun configured(): Boolean = BuildConfig.GITHUB_REPO.isNotEmpty()

    private fun base(): String = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/latest-apk"

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    fun fetchRemote(): Remote? {
        if (!configured()) return null
        val c = open("${base()}/version.json")
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            return Remote(
                code = j.optLong("versionCode", 0),
                name = j.optString("versionName", ""),
                notes = j.optString("notes", ""),
                apkUrl = j.optString("apkUrl", "${base()}/Manzel.apk"),
                sha256 = j.optString("sha256", "")
            )
        } finally {
            c.disconnect()
        }
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun download(ctx: Context, r: Remote, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "Manzel.apk")
        val c = open(r.apkUrl)
        try {
            if (c.responseCode != 200) throw ManzelError("network", "تعذّر تحميل ملف التحديث (${c.responseCode}).")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            if (r.sha256.isNotEmpty()) {
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(r.sha256, ignoreCase = true)) {
                    out.delete()
                    throw ManzelError("update", "ملف التحديث تالف (فشل التحقق). أعد المحاولة.")
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
