package com.manzel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** شريط قص مثل واتساب: صور مصغّرة للفيديو + مقبضان للبداية والنهاية + مؤشر التشغيل. */
class TrimBar(ctx: Context) : View(ctx) {
    var durationMs: Long = 1L
    var startMs: Long = 0L
    var endMs: Long = 1L
    var positionMs: Long = -1L
    var maxLenMs: Long = 0L
    var thumbs: Array<Bitmap?> = arrayOfNulls(0)

    /** which: 0 = البداية، 1 = النهاية */
    var onChange: ((Int, Long) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val handleW = dp(16f)
    private val paintBg = Paint().apply { color = 0xFF222222.toInt() }
    private val paintDim = Paint().apply { color = 0xB3000000.toInt() }
    private val paintFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paintGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val paintHead = Paint().apply { color = Color.WHITE }
    private val rect = RectF()
    private var drag = -1

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    private fun minLen(): Long = min(1000L, durationMs / 2).coerceAtLeast(1L)

    private fun xOf(ms: Long): Float {
        val w = width - 2 * handleW
        return handleW + w * (ms.toFloat() / durationMs.toFloat())
    }

    private fun msOf(x: Float): Long {
        val w = width - 2 * handleW
        return (((x - handleW) / w).coerceIn(0f, 1f) * durationMs).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val l = handleW
        val r = width - handleW
        canvas.drawRect(l, 0f, r, h, paintBg)

        val n = thumbs.size
        if (n > 0) {
            val seg = (r - l) / n
            for (i in 0 until n) {
                val b = thumbs[i] ?: continue
                val dst = RectF(l + i * seg, 0f, l + (i + 1) * seg, h)
                val dstAspect = dst.width() / dst.height()
                val srcAspect = b.width.toFloat() / b.height.toFloat()
                val src: Rect = if (srcAspect > dstAspect) {
                    val nw = (b.height * dstAspect).toInt().coerceAtLeast(1)
                    val x0 = (b.width - nw) / 2
                    Rect(x0, 0, x0 + nw, b.height)
                } else {
                    val nh = (b.width / dstAspect).toInt().coerceAtLeast(1)
                    val y0 = (b.height - nh) / 2
                    Rect(0, y0, b.width, y0 + nh)
                }
                canvas.drawBitmap(b, src, dst, null)
            }
        }

        val sx = xOf(startMs)
        val ex = xOf(endMs)
        canvas.drawRect(l, 0f, sx, h, paintDim)
        canvas.drawRect(ex, 0f, r, h, paintDim)

        val t = dp(3f)
        canvas.drawRect(sx, 0f, ex, t, paintFrame)
        canvas.drawRect(sx, h - t, ex, h, paintFrame)

        rect.set(sx - handleW, 0f, sx, h)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paintFrame)
        rect.set(ex, 0f, ex + handleW, h)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paintFrame)
        val g1 = h * 0.35f
        val g2 = h * 0.65f
        canvas.drawLine(sx - handleW / 2, g1, sx - handleW / 2, g2, paintGrip)
        canvas.drawLine(ex + handleW / 2, g1, ex + handleW / 2, g2, paintGrip)

        if (positionMs in startMs..endMs) {
            val px = xOf(positionMs)
            canvas.drawRect(px - dp(1f), 0f, px + dp(1f), h, paintHead)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val sx = xOf(startMs) - handleW / 2
                val ex = xOf(endMs) + handleW / 2
                val ds = abs(e.x - sx)
                val de = abs(e.x - ex)
                drag = if (min(ds, de) > dp(44f)) -1 else if (ds <= de) 0 else 1
                return drag >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return false
                val ms = msOf(e.x)
                if (drag == 0) {
                    var ns = ms.coerceIn(0L, (endMs - minLen()).coerceAtLeast(0L))
                    if (maxLenMs > 0 && endMs - ns > maxLenMs) ns = endMs - maxLenMs
                    startMs = ns
                    onChange?.invoke(0, startMs)
                } else {
                    var ne = ms.coerceIn(startMs + minLen(), durationMs)
                    if (maxLenMs > 0 && ne - startMs > maxLenMs) ne = startMs + maxLenMs
                    endMs = ne
                    onChange?.invoke(1, endMs)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag >= 0) {
                    drag = -1
                    onRelease?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
