package com.manzel.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

/**
 * محرر قص بشكل ستوري واتساب: الفيديو يعمل أمامك، وتحته شريط صور بمقبضين للبداية والنهاية.
 * يرجع للتطبيق وقتي البداية والنهاية (بالثواني) ويقوم التطبيق بالقص الفعلي.
 */
class TrimActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var bar: TrimBar
    private lateinit var info: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var prepared = false
    private var dragging = false
    private var durationMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (prepared && !dragging) {
                val p = video.currentPosition.toLong()
                bar.positionMs = p
                bar.invalidate()
                if (video.isPlaying && p >= bar.endMs) video.seekTo(bar.startMs.toInt())
            }
            ui.postDelayed(this, 50)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val tenth = (ms % 1000) / 100
        return "${s / 60}:${String.format("%02d", s % 60)}.$tenth"
    }

    private fun refreshInfo() {
        info.text = "البداية ${fmt(bar.startMs)}   •   المدة ${fmt(bar.endMs - bar.startMs)}   •   النهاية ${fmt(bar.endMs)}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = Uri.parse(intent.getStringExtra("uri") ?: "")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            durationMs = 0L
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
        if (durationMs <= 0L) {
            Toast.makeText(this, "تعذّر قراءة الفيديو.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        // الشريط العلوي
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val close = Button(this).apply { text = "إلغاء" }
        val title = TextView(this).apply {
            text = "قص الفيديو"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }
        top.addView(close)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(64), 1))
        root.addView(top)

        // المعاينة
        val frame = FrameLayout(this)
        video = VideoView(this)
        frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // معلومات الوقت
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        root.addView(info)

        // شريط القص
        bar = TrimBar(this)
        bar.durationMs = durationMs
        val iStart = ((intent.getDoubleExtra("start", -1.0)) * 1000).toLong()
        val iEnd = ((intent.getDoubleExtra("end", -1.0)) * 1000).toLong()
        if (iEnd > iStart && iStart >= 0L && iEnd <= durationMs + 50) {
            bar.startMs = iStart
            bar.endMs = minOf(iEnd, durationMs)
        } else {
            bar.startMs = 0L
            bar.endMs = if (durationMs > 60_000L) 60_000L else durationMs
        }
        val n = 10
        bar.thumbs = arrayOfNulls(n)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            setMargins(dp(12), dp(4), dp(12), dp(4))
        })

        // حد الستوري
        val story = CheckBox(this).apply {
            text = "ستوري: حد أقصى 60 ثانية"
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(story, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val done = Button(this).apply {
            text = "تم — استخدم هذا المقطع"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C2410C"))
            textSize = 16f
        }
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            setMargins(dp(12), dp(6), dp(12), dp(12))
        })
        setContentView(root)
        refreshInfo()

        // المعاينة والتشغيل
        video.setVideoURI(uri)
        video.setOnPreparedListener {
            prepared = true
            video.seekTo(bar.startMs.toInt())
            video.start()
        }
        video.setOnCompletionListener {
            video.seekTo(bar.startMs.toInt())
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "تعذّرت المعاينة لهذا الفيديو، لكن يمكنك تحديد المقطع بالشريط.", Toast.LENGTH_LONG).show()
            true
        }
        video.setOnClickListener {
            if (!prepared) return@setOnClickListener
            if (video.isPlaying) {
                video.pause()
            } else {
                if (video.currentPosition >= bar.endMs) video.seekTo(bar.startMs.toInt())
                video.start()
            }
        }

        bar.onChange = { _, ms ->
            dragging = true
            if (prepared) {
                video.pause()
                video.seekTo(ms.toInt())
            }
            refreshInfo()
        }
        bar.onRelease = {
            dragging = false
            if (prepared) {
                video.seekTo(bar.startMs.toInt())
                video.start()
            }
            refreshInfo()
        }
        story.setOnCheckedChangeListener { _, on ->
            bar.maxLenMs = if (on) 60_000L else 0L
            if (on && bar.endMs - bar.startMs > 60_000L) {
                bar.endMs = minOf(bar.startMs + 60_000L, durationMs)
                bar.invalidate()
                refreshInfo()
            }
        }
        close.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        done.setOnClickListener {
            val data = Intent()
                .putExtra("start", bar.startMs / 1000.0)
                .putExtra("end", bar.endMs / 1000.0)
            setResult(RESULT_OK, data)
            finish()
        }

        // الصور المصغّرة
        Thread {
            val mr = MediaMetadataRetriever()
            try {
                mr.setDataSource(this, uri)
                for (i in 0 until n) {
                    val tUs = durationMs * (2 * i + 1) / (2 * n) * 1000
                    val bmp = mr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val h = 120
                    val w = (bmp.width * h.toFloat() / bmp.height).toInt().coerceAtLeast(1)
                    val sc: Bitmap = Bitmap.createScaledBitmap(bmp, w, h, true)
                    bar.thumbs[i] = sc
                    bar.postInvalidate()
                }
            } catch (e: Exception) {
                /* بدون صور مصغّرة */
            } finally {
                try { mr.release() } catch (e: Exception) { }
            }
        }.start()

        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (prepared) video.pause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { video.stopPlayback() } catch (e: Exception) { }
        super.onDestroy()
    }
}
