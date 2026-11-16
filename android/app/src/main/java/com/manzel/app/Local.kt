package com.manzel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.getcapacitor.JSObject
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * معالجة فيديوهات موجودة على الهاتف: قص مقطع، أو تحويل إلى MP3.
 * تستخدم ffmpeg المدمج مع مكتبة المحرك مباشرة، وكل شيء على الجهاز.
 */
object Local {
    private val procs = ConcurrentHashMap<String, Process>()
    private val timeRe = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
    private val speedRe = Regex("""speed=\s*([\d.]+)x""")

    fun cancel(id: String) {
        procs[id]?.destroy()
    }

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "video.mp4"
        var size = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { /* نكتفي بالاسم الافتراضي */ }
        return Pair(name, size)
    }

    /** معلومات الملف للمعاينة: الاسم والحجم والمدة وصورة مصغّرة. */
    fun describe(ctx: Context, uri: Uri): JSONObject {
        val ns = nameAndSize(ctx, uri)
        val mime = try { ctx.contentResolver.getType(uri) ?: "" } catch (e: Exception) { "" }
        if (mime.startsWith("image/")) {
            var thumb = ""
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (bmp != null) {
                    val w = 240
                    val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
                    val bos = ByteArrayOutputStream()
                    Bitmap.createScaledBitmap(bmp, w, h, true).compress(Bitmap.CompressFormat.JPEG, 70, bos)
                    thumb = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                }
            } catch (e: Exception) { /* بدون معاينة */ }
            return JSONObject().put("uri", uri.toString()).put("name", ns.first).put("size", ns.second)
                .put("duration", 0.0).put("thumb", thumb).put("type", "image")
        }
        val isAudio = mime.startsWith("audio/")
        var durSec = 0.0
        var thumb = ""
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durSec = ms / 1000.0
            val atUs = if (ms > 2000) 1_000_000L else ms * 500
            val bmp = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp != null) {
                val w = 240
                val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
                val sc = Bitmap.createScaledBitmap(bmp, w, h, true)
                val bos = ByteArrayOutputStream()
                sc.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                thumb = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            /* ملف بلا معاينة */
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
        return JSONObject().put("uri", uri.toString()).put("name", ns.first).put("size", ns.second)
            .put("duration", durSec).put("thumb", thumb).put("type", if (isAudio) "audio" else "video")
    }

    private fun durationOf(f: File): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    fun run(ctx: Context, job: Job) {
        Engine.ensureInit(ctx)
        job.stage = "processing"
        Engine.emit("started", JSObject().put("id", job.id))
        val work = File(ctx.cacheDir, "local/${job.id}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val uri = Uri.parse(job.localUri)
            val srcName = nameAndSize(ctx, uri).first
            val srcExt = srcName.substringAfterLast('.', "mp4").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
            val input = File(work, "in.$srcExt")
            val ins = ctx.contentResolver.openInputStream(uri)
                ?: throw ManzelError("unavailable", "تعذّر فتح الملف. اختره من جديد.")
            ins.use { s -> input.outputStream().use { o -> s.copyTo(o) } }
            if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")

            val total = durationOf(input)
            val audio = job.mode == "mp3"
            val start = if (job.startSec > 0.0) job.startSec else 0.0
            var length = total
            if (!audio && job.endSec > 0.0) {
                if (total > 0.0 && start >= total) throw ManzelError("format", "وقت البداية بعد نهاية الفيديو.")
                val end = if (total > 0.0) minOf(job.endSec, total) else job.endSec
                length = end - start
            }
            val outExt = if (audio) "mp3" else if (srcExt in setOf("mp4", "m4v", "mov", "3gp")) "mp4" else "mkv"
            val out = File(work, "out.$outExt")

            if (audio) {
                exec(
                    ctx, job,
                    listOf("-y", "-i", input.path, "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-q:a", "2",
                        "-map_metadata", "0", "-id3v2_version", "3", out.path),
                    length
                )
            } else {
                val head = listOf("-y", "-ss", fmt(start), "-i", input.path, "-t", fmt(length), "-map", "0:v:0?", "-map", "0:a:0?")
                val copyArgs = head + listOf("-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", out.path)
                val preciseArgs = head + listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", out.path)
                if (job.precise) {
                    try {
                        exec(ctx, job, preciseArgs, length)
                    } catch (e: ManzelError) {
                        if (e.code == "cancelled") throw e
                        out.delete()
                        job.percent = 0.0
                        exec(ctx, job, copyArgs, length)
                    }
                } else {
                    exec(ctx, job, copyArgs, length)
                }
            }
            if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")
            if (!out.exists() || out.length() == 0L) throw ManzelError("convert", "لم يُنتج الملف. ربما صيغته غير مدعومة.")

            job.stage = "saving"
            job.percent = 100.0
            Engine.emitProgress(job)
            val base = srcName.substringBeforeLast('.')
            val name = MediaSaver.safeName(job.title.ifEmpty { base }) + "." + outExt
            val friendly = if (audio) true else MediaSaver.isWhatsAppFriendly(out)
            val saved = MediaSaver.save(
                ctx, out, name, MediaSaver.mimeFor(outExt, audio),
                if (audio) MediaSaver.Kind.AUDIO else MediaSaver.Kind.VIDEO, job.folder
            )
            Engine.record("done", saved.toJS().put("id", job.id).put("friendly", friendly))
            DownloadService.notifyDone(ctx, saved.name, true)
        } finally {
            procs.remove(job.id)
            work.deleteRecursively()
        }
    }

    private fun hasAudio(f: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) {
            false
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun copyIn(ctx: Context, uriStr: String, dst: File) {
        val ins = ctx.contentResolver.openInputStream(Uri.parse(uriStr))
            ?: throw ManzelError("unavailable", "تعذّر فتح أحد الملفات. اخترها من جديد.")
        ins.use { s -> dst.outputStream().use { o -> s.copyTo(o) } }
    }

    private fun extOf(name: String, def: String): String =
        name.substringAfterLast('.', def).lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { def }

    /** تركيب: دمج فيديوهات وصور (بالترتيب) مع موسيقى اختيارية في فيديو واحد MP4. */
    fun runMix(ctx: Context, job: Job) {
        Engine.ensureInit(ctx)
        job.stage = "processing"
        Engine.emit("started", JSObject().put("id", job.id))
        val work = File(ctx.cacheDir, "local/${job.id}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val cfg = JSONObject(job.mix)
            val clips = cfg.getJSONArray("clips")
            val keep = cfg.optBoolean("keepOriginal", true)
            val vol = cfg.optDouble("volume", 100.0).coerceIn(0.0, 100.0) / 100.0
            val imgDur = cfg.optDouble("imgDur", 3.0).coerceIn(1.0, 30.0)
            val aspect = cfg.optString("aspect", "v")
            val w = if (aspect == "h") 1280 else 720
            val h = if (aspect == "v") 1280 else if (aspect == "h") 720 else 720
            val music = cfg.optJSONObject("music")
            if (clips.length() == 0) throw ManzelError("format", "أضف فيديو أو صورة واحدة على الأقل.")

            val args = ArrayList<String>()
            args.addAll(listOf("-y"))
            val filters = ArrayList<String>()
            val labels = StringBuilder()
            var total = 0.0
            val n = clips.length()
            for (i in 0 until n) {
                if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")
                val c = clips.getJSONObject(i)
                val isImg = c.optString("type") == "image"
                val ext = extOf(c.optString("name"), if (isImg) "jpg" else "mp4")
                val f = File(work, "c$i.$ext")
                copyIn(ctx, c.getString("uri"), f)
                var len: Double
                if (isImg) {
                    len = imgDur
                    args.addAll(listOf("-loop", "1", "-framerate", "30", "-t", fmt(len), "-i", f.path))
                } else {
                    val full = durationOf(f)
                    val st = c.optDouble("start", -1.0).let { if (it > 0.0) it else 0.0 }
                    val en = c.optDouble("end", -1.0).let { if (it > 0.0 && (full <= 0.0 || it < full)) it else full }
                    len = if (en > st) en - st else full
                    if (len <= 0.0) throw ManzelError("format", "تعذّر قراءة مدة أحد الفيديوهات.")
                    if (st > 0.0) args.addAll(listOf("-ss", fmt(st)))
                    args.addAll(listOf("-t", fmt(len), "-i", f.path))
                }
                total += len
                filters.add(
                    "[$i:v]scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:black," +
                        "setsar=1,fps=30,format=yuv420p[v$i]"
                )
                labels.append("[v$i]")
                if (keep) {
                    if (!isImg && hasAudio(f)) {
                        filters.add("[$i:a]aresample=44100,aformat=channel_layouts=stereo,asetpts=PTS-STARTPTS[a$i]")
                    } else {
                        filters.add("anullsrc=r=44100:cl=stereo,atrim=0:${fmt(len)},asetpts=PTS-STARTPTS[a$i]")
                    }
                    labels.append("[a$i]")
                }
            }
            var musicIdx = -1
            if (music != null && music.optString("uri").isNotEmpty()) {
                val mf = File(work, "music." + extOf(music.optString("name"), "mp3"))
                copyIn(ctx, music.getString("uri"), mf)
                args.addAll(listOf("-stream_loop", "-1", "-i", mf.path))
                musicIdx = n
            }
            filters.add(labels.toString() + "concat=n=$n:v=1:a=${if (keep) 1 else 0}[v]" + (if (keep) "[a]" else ""))
            var aMap: String? = null
            if (musicIdx >= 0) {
                val vs = String.format(java.util.Locale.US, "%.2f", vol)
                if (keep) {
                    filters.add("[$musicIdx:a]volume=$vs,aresample=44100,aformat=channel_layouts=stereo[m]")
                    filters.add("[a][m]amix=inputs=2:duration=first:dropout_transition=0,volume=1.8[ao]")
                } else {
                    filters.add("[$musicIdx:a]volume=$vs,aresample=44100,aformat=channel_layouts=stereo[ao]")
                }
                aMap = "[ao]"
            } else if (keep) aMap = "[a]"

            val out = File(work, "out.mp4")
            val base = args + listOf("-filter_complex", filters.joinToString(";"), "-map", "[v]") +
                (if (aMap != null) listOf("-map", aMap) else emptyList())
            val tail = (if (aMap != null) listOf("-c:a", "aac", "-b:a", "160k") else emptyList()) +
                listOf("-t", fmt(total), "-movflags", "+faststart", out.path)
            val x264 = base + listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-r", "30") + tail
            val mp4v = base + listOf("-c:v", "mpeg4", "-q:v", "4", "-pix_fmt", "yuv420p", "-r", "30") + tail
            try {
                exec(ctx, job, x264, total)
            } catch (e: ManzelError) {
                if (e.code == "cancelled") throw e
                out.delete()
                job.percent = 0.0
                exec(ctx, job, mp4v, total)
            }
            if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")
            if (!out.exists() || out.length() == 0L) throw ManzelError("convert", "لم يُنتج الفيديو. ربما إحدى الصيغ غير مدعومة.")

            job.stage = "saving"
            job.percent = 100.0
            Engine.emitProgress(job)
            val name = MediaSaver.safeName(job.title.ifEmpty { "تركيب" }) + ".mp4"
            val saved = MediaSaver.save(ctx, out, name, "video/mp4", MediaSaver.Kind.VIDEO, job.folder)
            Engine.record("done", saved.toJS().put("id", job.id).put("friendly", true))
            DownloadService.notifyDone(ctx, saved.name, true)
        } finally {
            procs.remove(job.id)
            work.deleteRecursively()
        }
    }

    private fun exec(ctx: Context, job: Job, args: List<String>, length: Double) {
        val pkgs = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") {
            File(pkgs, "$it/usr/lib").absolutePath
        }
        val p = pb.start()
        procs[job.id] = p
        val tail = StringBuilder()
        val sb = StringBuilder()
        val rd = p.inputStream.bufferedReader()
        var lastEmit = 0L
        while (true) {
            val c = rd.read()
            if (c < 0) break
            val ch = c.toChar()
            if (ch == '\r' || ch == '\n') {
                val line = sb.toString()
                sb.setLength(0)
                if (line.isEmpty()) continue
                tail.append(line).append('\n')
                if (tail.length > 1500) tail.delete(0, tail.length - 1500)
                val m = timeRe.find(line)
                if (m != null && length > 0.0) {
                    val t = m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toDouble()
                    job.percent = (t / length * 100.0).coerceIn(0.0, 99.0)
                    val sp = speedRe.find(line)
                    job.speed = if (sp != null) sp.groupValues[1] + "x" else ""
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 400) {
                        lastEmit = now
                        Engine.emitProgress(job)
                    }
                }
            } else {
                sb.append(ch)
            }
        }
        val code = p.waitFor()
        procs.remove(job.id)
        if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")
        if (code != 0) {
            val t = tail.toString()
            if (t.contains("matches no streams")) throw ManzelError("noaudio", "هذا الفيديو لا يحتوي على صوت.", t.takeLast(300))
            throw ManzelError("convert", "تعذّرت معالجة الملف. ربما صيغته غير مدعومة.", t.takeLast(300))
        }
    }
}
