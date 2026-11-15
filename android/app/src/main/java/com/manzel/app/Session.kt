package com.manzel.app

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.URI

class Site(
    val id: String,
    val nameAr: String,
    val loginUrl: String,
    val cookieUrls: List<String>,
    val domain: String,
    val hostRegex: Regex,
    val requiredCookie: String
)

/**
 * جلسة دخول اختيارية من حساب المستخدم نفسه (إنستجرام/فيسبوك).
 * الكوكيز تُحفظ على الهاتف فقط في مجلد التطبيق الخاص، وتُنسخ مؤقتاً لكل عملية ثم تُحذف.
 */
object Session {
    val sites = listOf(
        Site(
            "instagram", "إنستجرام", "https://www.instagram.com/accounts/login/",
            listOf("https://www.instagram.com", "https://instagram.com"), ".instagram.com",
            Regex("(^|\\.)(instagram\\.com|instagr\\.am)$"), "sessionid"
        ),
        Site(
            "facebook", "فيسبوك", "https://m.facebook.com/login/",
            listOf("https://www.facebook.com", "https://m.facebook.com", "https://facebook.com"), ".facebook.com",
            Regex("(^|\\.)(facebook\\.com|fb\\.watch|fb\\.com|fb\\.me)$"), "c_user"
        )
    )

    fun site(id: String): Site? = sites.firstOrNull { it.id == id }

    fun siteForUrl(url: String): Site? {
        val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return null
        return sites.firstOrNull { it.hostRegex.containsMatchIn(host) }
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "cookies").apply { mkdirs() }
    private fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.txt")
    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("manzel_session", Context.MODE_PRIVATE)

    fun isLoggedIn(ctx: Context, id: String): Boolean = file(ctx, id).let { it.exists() && it.length() > 0 }

    /** يقرأ كوكيز الـWebView بعد تسجيل الدخول ويحفظها بصيغة Netscape. يرجع false إن لم يظهر تسجيل دخول. */
    fun saveFromWebView(ctx: Context, site: Site, ua: String): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val map = LinkedHashMap<String, String>()
        for (u in site.cookieUrls) {
            val raw = cm.getCookie(u) ?: continue
            for (part in raw.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        }
        if (!map.containsKey(site.requiredCookie)) return false
        val exp = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        for ((k, v) in map) {
            sb.append(site.domain).append("\tTRUE\t/\tTRUE\t").append(exp).append('\t').append(k).append('\t').append(v).append('\n')
        }
        file(ctx, site.id).writeText(sb.toString())
        prefs(ctx).edit().putString("ua_${site.id}", ua).apply()
        return true
    }

    fun logout(ctx: Context, id: String) {
        file(ctx, id).delete()
        prefs(ctx).edit().remove("ua_$id").apply()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    /** يضيف --cookies/--user-agent إن كان المستخدم مسجّلاً لهذا الموقع. يرجع النسخة المؤقتة لحذفها بعد الانتهاء. */
    fun applyTo(ctx: Context, req: YoutubeDLRequest, url: String): File? {
        val s = siteForUrl(url) ?: return null
        val f = file(ctx, s.id)
        if (!f.exists()) return null
        val tmpDir = File(ctx.cacheDir, "ck").apply { mkdirs() }
        val tmp = File(tmpDir, "${s.id}-${System.nanoTime()}.txt")
        f.copyTo(tmp, overwrite = true)
        req.addOption("--cookies", tmp.absolutePath)
        val ua = prefs(ctx).getString("ua_${s.id}", null)
        if (!ua.isNullOrBlank()) req.addOption("--user-agent", ua)
        return tmp
    }
}
