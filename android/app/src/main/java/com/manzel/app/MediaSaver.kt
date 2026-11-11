package com.manzel.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** الحفظ في المعرض عبر MediaStore + أدوات الفيديو (فحص التوافق وقص الستوري) بدون ffmpeg. */
object MediaSaver {
    enum class Kind { VIDEO, AUDIO }

    class Saved(val uri: String, val name: String, val size: Long, val mime: String, val folder: String) {
        fun toJS(): JSObject = JSObject()
            .put("uri", uri).put("name", name).put("size", size).put("mime", mime).put("folder", folder)
    }

    fun safeName(raw: String): String {
        var s = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.length > 80) s = s.substring(0, 80).trim()
        return s.ifEmpty { "manzel" }
    }

    fun mimeFor(ext: String, audio: Boolean): String {
        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        if (m != null) return m
        return if (audio) "audio/mpeg" else "video/mp4"
    }

    private fun relPath(kind: Kind, folder: String): String = when {
        kind == Kind.AUDIO -> "Music/Manzel"
        folder == "dcim" -> "DCIM/Manzel"
        else -> "Movies/Manzel"
    }

    fun save(ctx: Context, src: File, displayName: String, mime: String, kind: Kind, folder: String): Saved {
        val rel = relPath(kind, folder)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(ctx, src, displayName, mime, kind, rel)
        else saveLegacy(ctx, src, displayName, mime, kind, rel)
    }

    private fun saveQ(ctx: Context, src: File, name: String, mime: String, kind: Kind, rel: String): Saved {
        val resolver = ctx.contentResolver
        val collection = if (kind == Kind.AUDIO) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("تعذّر إنشاء الملف في المعرض")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("تعذّر فتح الملف للكتابة")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (ignored: Exception) {}
            throw ManzelError("save", "تعذّر حفظ الملف في المعرض.", e.message ?: "")
        }
        return Saved(uri.toString(), name, src.length(), mime, rel)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(ctx: Context, src: File, name: String, mime: String, kind: Kind, rel: String): Saved {
        val root = when {
            kind == Kind.AUDIO -> Environment.DIRECTORY_MUSIC
            rel.startsWith("DCIM") -> Environment.DIRECTORY_DCIM
            else -> Environment.DIRECTORY_MOVIES
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(root), "Manzel")
        if (!dir.exists() && !dir.mkdirs()) {
            throw ManzelError("permission", "لا توجد صلاحية للحفظ في الذاكرة. اسمح بها من إعدادات التطبيق.")
        }
        var dest = File(dir, name)
        var n = 1
        val base = dest.nameWithoutExtension
        val ext = dest.extension
        while (dest.exists()) { dest = File(dir, "$base ($n).$ext"); n++ }
        try {
            src.copyTo(dest, overwrite = false)
        } catch (e: Exception) {
            throw ManzelError("save", "تعذّر حفظ الملف.", e.message ?: "")
        }
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime)) { _, u ->
            scanned = u
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        val uri = scanned ?: FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
        return Saved(uri.toString(), dest.name, dest.length(), mime, "$root/Manzel")
    }

    fun delete(ctx: Context, uri: String): Boolean {
        return try {
            ctx.contentResolver.delete(Uri.parse(uri), null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ توافق واتساب

    /** واتساب يتوقع MP4 بفيديو H.264 وصوت AAC. */
    fun isWhatsAppFriendly(f: File): Boolean {
        val n = f.name.lowercase()
        if (!(n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov"))) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            var ok = true
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && mime != "video/avc") ok = false
                if (mime.startsWith("audio/") && mime != "audio/mp4a-latm") ok = false
            }
            ok
        } catch (e: Exception) {
            true
        } finally {
            ex.release()
        }
    }

    fun durationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    /**
     * قص الفيديو إلى أول maxMs ملّي ثانية بدون إعادة ترميز (نسخ الـsamples مباشرة).
     * يعمل مع MP4 (H.264/AAC) وهو الشكل الذي ينتجه التطبيق عادةً.
     */
    fun trimToTemp(ctx: Context, uri: Uri, maxMs: Long): File {
        val out = File(ctx.cacheDir, "story_${System.currentTimeMillis()}.mp4")
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(ctx, uri, null)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val rotation = run {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (e: Exception) { 0 } finally { r.release() }
            }
            muxer.setOrientationHint(rotation)

            val map = HashMap<Int, Int>()
            var maxInput = 1 * 1024 * 1024
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    map[i] = muxer.addTrack(f)
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = maxOf(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            }
            if (map.isEmpty()) throw ManzelError("format", "صيغة الفيديو غير مدعومة للقص.")
            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(maxOf(maxInput, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val limitUs = maxMs * 1000
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > limitUs) break
                info.set(0, size, t, ex.sampleFlags)
                val dst = map[ex.sampleTrackIndex]
                if (dst != null) muxer.writeSampleData(dst, buf, info)
                ex.advance()
            }
            return out
        } catch (e: ManzelError) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw ManzelError("format", "تعذّر قص هذا الفيديو.", e.message ?: "")
        } finally {
            try { if (started) muxer?.stop() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
            ex.release()
        }
    }
}
