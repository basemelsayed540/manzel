package com.manzel.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** شاشة تسجيل دخول اختيارية داخل التطبيق. بعد الدخول اضغط «تم» فتُحفظ الجلسة على الهاتف فقط. */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var site: Site

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Session.site(intent.getStringExtra("site") ?: "") ?: run { finish(); return }

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
            setBackgroundColor(Color.parseColor("#312E81"))
        }
        val title = TextView(this).apply {
            text = "تسجيل الدخول — ${site.nameAr}"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = Button(this).apply { text = "تم"; minimumHeight = 144; minimumWidth = 160 }
        val cancel = Button(this).apply { text = "إلغاء"; minimumHeight = 144 }
        bar.addView(cancel)
        bar.addView(done)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "سجّل الدخول بحسابك (يُفضّل حساب ثانوي) ثم اضغط «تم». تبقى الجلسة على هاتفك فقط."
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#EEF2FF"))
            setTextColor(Color.parseColor("#1E1B4B"))
            textSize = 13f
        }
        root.addView(hint)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        web.webViewClient = WebViewClient()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        cancel.setOnClickListener { setResult(RESULT_CANCELED); finish() }
        done.setOnClickListener {
            if (Session.saveFromWebView(this, site, web.settings.userAgentString)) {
                Toast.makeText(this, "تم حفظ تسجيل الدخول", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, "لم يتم اكتشاف تسجيل دخول بعد. أكمل الدخول ثم اضغط «تم».", Toast.LENGTH_LONG).show()
            }
        }
        web.loadUrl(site.loginUrl)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); super.onBackPressed() }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
