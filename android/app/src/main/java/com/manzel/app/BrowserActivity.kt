package com.manzel.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONTokener

/**
 * متصفح صغير: افتح الصفحة وشغّل الفيديو، فيلتقط التطبيق روابط الفيديو الحقيقية (mp4 / m3u8 …)
 * من حركة الصفحة، واختر منها ما تريد تحميله. لا يتجاوز أي حماية: المحتوى المحمي بـ DRM سيفشل برسالة واضحة.
 */
class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val found = LinkedHashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            scanDom()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("url") ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#6D28D9"))
        }
        val title = TextView(this).apply {
            text = "التقاط الفيديو من الصفحة"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = Button(this).apply { text = "إغلاق"; minimumHeight = 144 }
        bar.addView(close)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (isMedia(u)) ui.post { add(u) }
                return null
            }
            override fun onPageFinished(view: WebView, url: String) {
                scanDom()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            text = "شغّل الفيديو داخل الصفحة. ستظهر هنا الفيديوهات الملتقَطة."
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#F5F3FF"))
            setTextColor(Color.parseColor("#2E1065"))
            textSize = 13f
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply { addView(list) }
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360))
        setContentView(root)

        close.setOnClickListener { setResult(RESULT_CANCELED); finish() }
        web.loadUrl(start)
        ui.postDelayed(poll, 2000)
    }

    private fun isMedia(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".webm") ||
            path.endsWith(".mov") || path.endsWith(".m4v") || path.endsWith(".mpd")
    }

    private fun scanDom() {
        val js = "(function(){var r=[];document.querySelectorAll('video,video source,audio').forEach(function(v){" +
            "var s=v.currentSrc||v.src;if(s&&s.indexOf('http')==0)r.push(s)});return JSON.stringify(r)})()"
        web.evaluateJavascript(js) { raw ->
            try {
                val inner = JSONTokener(raw ?: "null").nextValue()
                if (inner is String) {
                    val arr = JSONArray(inner)
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
            } catch (e: Exception) { /* تجاهل */ }
        }
    }

    private fun add(u: String) {
        if (!found.add(u)) return
        val kind = when {
            u.lowercase().contains(".m3u8") -> "HLS"
            u.lowercase().contains(".mpd") -> "DASH"
            else -> "فيديو"
        }
        val name = u.substringBefore('?').substringAfterLast('/').take(40)
        val host = try { java.net.URI(u).host ?: "" } catch (e: Exception) { "" }
        val b = Button(this).apply {
            text = "$kind • $host • $name"
            minimumHeight = 150
            setOnClickListener { finishWith(u) }
        }
        list.addView(b, 0)
        status.text = "تم التقاط ${found.size} فيديو. اضغط على الذي تريد تحميله:"
    }

    private fun finishWith(mediaUrl: String) {
        val data = Intent()
            .putExtra("mediaUrl", mediaUrl)
            .putExtra("pageUrl", web.url ?: "")
            .putExtra("title", web.title ?: "")
        setResult(RESULT_OK, data)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); super.onBackPressed() }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
