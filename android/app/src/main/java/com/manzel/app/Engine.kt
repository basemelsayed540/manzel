package com.manzel.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** خطأ مفهوم للمستخدم: code ثابت للواجهة + رسالة عربية + تفاصيل تقنية مختصرة. */
class ManzelError(val code: String, message: String, val detail: String = "") : Exception(message)

class Job(
    val id: String,
    val url: String,
    val formatId: String,
    val recode: Boolean,
    val folder: String,
    val qualityId: String,
    val title: String
) {
    @Volatile var percent = 0.0
    @Volatile var stage = "queued"
    @Volatile var speed = ""
    @Volatile var eta = -1L
    @Volatile var cancelled = false
    var lastEmit = 0L
    var passes = 0
    val expectedPasses: Int get() = if (formatId.contains("+")) 2 else 1
}

/**
 * قلب التطبيق: تحليل الرابط + التحميل عبر yt-dlp + الحفظ في المعرض.
 * كل شيء يحدث على الجهاز نفسه بدون سيرفر.
 */
object Engine {
    private val initLock = Any()
    @Volatile private var inited = false

    val analyzePool: ExecutorService = Executors.newFixedThreadPool(2)
    val downloadPool: ExecutorService = Executors.newFixedThreadPool(3)
    val workPool: ExecutorService = Executors.newSingleThreadExecutor()

    val jobs = ConcurrentHashMap<String, Job>()
    private val active = AtomicInteger(0)
    private val finished = ConcurrentLinkedQueue<JSObject>()
    private val main = Handler(Looper.getMainLooper())

    /** تضبطه الـplugin لإرسال الأحداث للواجهة. */
    @Volatile var sink: ((String, JSObject) -> Unit)? = null

    fun ensureInit(ctx: Context) {
        if (inited) return
        synchronized(initLock) {
            if (inited) return
            val app = ctx.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            inited = true
        }
    }

    fun engineVersion(ctx: Context): String {
        return try {
            ensureInit(ctx)
            YoutubeDL.getInstance().version(ctx.applicationContext) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun updateEngine(ctx: Context): JSONObject {
        if (active.get() > 0) return JSONObject().put("status", "busy").put("version", engineVersion(ctx))
        ensureInit(ctx)
        val before = engineVersion(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)
        val after = engineVersion(ctx)
        val status = when {
            st == null -> "unknown"
            st.toString().contains("ALREADY", ignoreCase = true) -> "up_to_date"
            else -> "updated"
        }
        return JSONObject().put("status", status).put("version", after).put("before", before)
    }

    // ------------------------------------------------------------------ تحليل

    private class F(
        val id: String, val ext: String, val w: Int, val h: Int,
        val v: String, val a: String, val tbr: Double, val size: Long
    ) {
        val hasVideo: Boolean get() = v != "none"
        val hasAudio: Boolean get() = a != "none"
        val h264: Boolean get() = v.startsWith("avc") || v.startsWith("h264") || (v == "" && ext == "mp4")
        val aac: Boolean get() = a.startsWith("mp4a") || a.startsWith("aac") || (a == "" && ext == "mp4")
        /** جودة الفيديو بالضلع الأقصر، عشان الفيديوهات الرأسية (ريلز/تيك توك) تتصنّف صح. */
        val q: Int get() = if (w > 0 && h > 0) min(w, h) else h
    }

    private fun str(j: JSONObject, key: String): String =
        if (j.isNull(key)) "" else j.optString(key, "")

    private fun codec(raw: String): String =
        if (raw.isEmpty() || raw == "null") "" else raw.lowercase()

    fun analyze(ctx: Context, url: String): JSONObject {
        ensureInit(ctx)
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-playlist")
        req.addOption("--playlist-items", "1")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "20")
        val out: String = try {
            YoutubeDL.getInstance().execute(req, null, null).out ?: ""
        } catch (e: Exception) {
            throw classify(e.message ?: e.toString())
        }
        val s = out.trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw classify("empty response: $s")
        var info = JSONObject(s.substring(a, b + 1))
        if (info.optString("_type") == "playlist") {
            val entries = info.optJSONArray("entries")
            info = entries?.optJSONObject(0) ?: throw ManzelError("unavailable", "لم يتم العثور على فيديو في هذا الرابط.")
        }
        if (info.optBoolean("is_live", false)) {
            throw ManzelError("live", "البث المباشر غير مدعوم. جرّب بعد انتهائه.")
        }
        val avail = str(info, "availability")
        if (avail == "needs_auth" || avail == "private" || avail == "subscriber_only" || avail == "premium_only") {
            throw ManzelError("private", "الفيديو خاص أو يحتاج اشتراكاً/تسجيل دخول. التطبيق يحمّل الروابط العامة فقط.")
        }

        val duration = if (info.isNull("duration")) 0.0 else info.optDouble("duration", 0.0)
        val fmts = ArrayList<F>()
        var drmCount = 0
        var totalCount = 0
        val arr: JSONArray = info.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val ext = str(f, "ext").lowercase()
            val note = str(f, "format_note").lowercase()
            val proto = str(f, "protocol").lowercase()
            if (ext == "mhtml" || note.contains("storyboard") || proto.contains("mhtml")) continue
            totalCount++
            if (f.optBoolean("has_drm", false)) { drmCount++; continue }
            val v = codec(str(f, "vcodec"))
            val a2 = codec(str(f, "acodec"))
            val size = when {
                !f.isNull("filesize") -> f.optLong("filesize")
                !f.isNull("filesize_approx") -> f.optLong("filesize_approx")
                !f.isNull("tbr") && duration > 0 -> (f.optDouble("tbr") * duration * 125).toLong()
                else -> 0L
            }
            fmts.add(
                F(
                    id = str(f, "format_id"), ext = ext,
                    w = f.optInt("width", 0), h = f.optInt("height", 0),
                    v = v, a = a2,
                    tbr = if (f.isNull("tbr")) 0.0 else f.optDouble("tbr", 0.0),
                    size = size
                )
            )
        }
        if (totalCount > 0 && drmCount == totalCount) {
            throw ManzelError("drm", "هذا المحتوى محمي بـ DRM ولا يمكن تحميله.")
        }

        val videos = fmts.filter { it.hasVideo && it.id.isNotEmpty() }
        val audios = fmts.filter { !it.hasVideo && it.hasAudio && it.id.isNotEmpty() }
        val qualities = JSONArray()

        fun sizeOf(v: F, au: F?): Long = (if (v.size > 0) v.size else 0L) + (au?.size ?: 0L)

        fun bestAudioFor(v: F): F? {
            if (v.hasAudio) return null
            return audios.sortedWith(
                compareByDescending<F> { it.aac }.thenByDescending { it.tbr }
            ).firstOrNull()
        }

        fun entry(qid: String, label: String, v: F): JSONObject {
            val au = bestAudioFor(v)
            val fid = if (au != null) "${v.id}+${au.id}" else v.id
            val vOk = v.h264
            val aOk = if (au != null) au.aac else (!v.hasAudio || v.aac)
            return JSONObject()
                .put("id", qid)
                .put("label", label)
                .put("detail", if (v.q > 0) "${v.q}p" else "")
                .put("fid", fid)
                .put("recode", !(vOk && aOk))
                .put("size", sizeOf(v, au))
        }

        if (videos.isNotEmpty()) {
            val maxQ = videos.maxOf { it.q }
            // أعلى جودة: نفضّل H.264 عشان يشتغل في واتساب بدون تحويل
            val h264s = videos.filter { it.h264 }
            val bestPool = if (h264s.isNotEmpty()) h264s else videos
            val best = bestPool.sortedWith(compareByDescending<F> { it.q }.thenByDescending { it.tbr }).first()
            qualities.put(entry("best", "أعلى جودة", best))

            for (tier in listOf(1080, 720, 480)) {
                if (maxQ < tier * 0.9) continue
                val cands = videos.filter { it.q in 1..tier }
                if (cands.isEmpty()) continue
                val pick = cands.sortedWith(
                    compareByDescending<F> { it.q }.thenByDescending { it.h264 }.thenByDescending { it.tbr }
                ).first()
                qualities.put(entry(tier.toString(), "$tier", pick))
            }
        }
        qualities.put(
            JSONObject()
                .put("id", "audio").put("label", "صوت فقط MP3").put("detail", "")
                .put("fid", "audio").put("recode", false)
                .put("size", if (duration > 0) (duration * 20000).toLong() else 0L)
        )

        var thumb = str(info, "thumbnail")
        if (thumb.isEmpty()) {
            val th = info.optJSONArray("thumbnails")
            if (th != null && th.length() > 0) thumb = str(th.optJSONObject(th.length() - 1) ?: JSONObject(), "url")
        }
        return JSONObject()
            .put("title", str(info, "title").ifEmpty { str(info, "id") })
            .put("thumbnail", thumb)
            .put("duration", duration)
            .put("uploader", str(info, "uploader"))
            .put("extractor", str(info, "extractor_key"))
            .put("qualities", qualities)
    }

    // ------------------------------------------------------------------ تحميل

    fun submit(ctx: Context, job: Job) {
        jobs[job.id] = job
        onStart(ctx)
        downloadPool.execute { runJob(ctx.applicationContext, job) }
    }

    fun cancel(id: String) {
        val j = jobs[id] ?: return
        j.cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (ignored: Exception) {}
    }

    private fun runJob(ctx: Context, job: Job) {
        val tmp = File(ctx.cacheDir, "dl/${job.id}")
        try {
            if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")
            ensureInit(ctx)
            job.stage = "downloading"
            emit("started", JSObject().put("id", job.id))
            tmp.deleteRecursively()
            tmp.mkdirs()

            val req = YoutubeDLRequest(job.url)
            req.addOption("-o", "${tmp.absolutePath}/%(id)s.%(ext)s")
            req.addOption("--no-playlist")
            req.addOption("--playlist-items", "1")
            req.addOption("--no-mtime")
            req.addOption("--retries", "5")
            req.addOption("--fragment-retries", "5")
            req.addOption("--socket-timeout", "20")
            req.addOption("--concurrent-fragments", "4")
            val audio = job.qualityId == "audio"
            if (audio) {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            } else {
                req.addOption("-f", "${job.formatId}/bv*+ba/b")
                if (job.recode) {
                    // mkv ثم تحويل إلى mp4 (H.264 + AAC) ليتوافق مع واتساب
                    req.addOption("--merge-output-format", "mkv")
                    req.addOption("--recode-video", "mp4")
                } else {
                    req.addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(req, job.id) { progress, eta, line ->
                onProgress(ctx, job, progress.toDouble(), eta.toLong(), line ?: "")
            }
            if (job.cancelled) throw ManzelError("cancelled", "تم الإلغاء")

            job.stage = "saving"
            job.percent = 100.0
            emitProgress(job)
            val out = tmp.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
                ?.maxByOrNull { it.length() }
                ?: throw ManzelError("unknown", "انتهى التحميل لكن لم يتم العثور على الملف الناتج.")

            val ext = out.extension.lowercase()
            val friendly = if (audio) true else MediaSaver.isWhatsAppFriendly(out)
            val name = MediaSaver.safeName(job.title.ifEmpty { out.nameWithoutExtension }) + "." + ext
            val saved = MediaSaver.save(
                ctx, out, name, MediaSaver.mimeFor(ext, audio),
                if (audio) MediaSaver.Kind.AUDIO else MediaSaver.Kind.VIDEO, job.folder
            )
            val data = saved.toJS().put("id", job.id).put("friendly", friendly)
            record("done", data)
            DownloadService.notifyDone(ctx, saved.name, true)
        } catch (e: ManzelError) {
            failJob(ctx, job, e)
        } catch (e: Exception) {
            if (job.cancelled) failJob(ctx, job, ManzelError("cancelled", "تم الإلغاء"))
            else failJob(ctx, job, classify(e.message ?: e.toString()))
        } finally {
            tmp.deleteRecursively()
            jobs.remove(job.id)
            onEnd(ctx)
        }
    }

    private fun failJob(ctx: Context, job: Job, e: ManzelError) {
        record(
            "failed",
            JSObject().put("id", job.id).put("code", e.code).put("message", e.message ?: "").put("detail", e.detail)
        )
        if (e.code != "cancelled") DownloadService.notifyDone(ctx, job.title, false)
    }

    private val speedRe = Regex("""at\s+(\S+/s)""")

    private fun onProgress(ctx: Context, job: Job, pct: Double, eta: Long, line: String) {
        if (line.contains("[download] Destination:")) job.passes++
        when {
            line.contains("[Merger]") -> job.stage = "merging"
            line.contains("[ExtractAudio]") -> job.stage = "audio"
            line.contains("[VideoConvertor]") -> job.stage = "recoding"
            line.contains("[download]") -> job.stage = "downloading"
        }
        if (job.stage == "merging" || job.stage == "audio" || job.stage == "recoding") {
            job.percent = 100.0
        } else if (pct >= 0) {
            val passes = job.expectedPasses
            val idx = (job.passes - 1).coerceIn(0, passes - 1)
            job.percent = ((idx + pct / 100.0) / passes) * 100.0
        }
        speedRe.find(line)?.let { job.speed = it.groupValues[1] }
        if (eta >= 0) job.eta = eta
        val now = System.currentTimeMillis()
        if (now - job.lastEmit >= 400) {
            job.lastEmit = now
            emitProgress(job)
            DownloadService.updateProgress(ctx, aggregateText(), aggregatePercent())
        }
    }

    private fun emitProgress(job: Job) {
        emit(
            "progress",
            JSObject().put("id", job.id).put("percent", job.percent).put("speed", job.speed)
                .put("eta", job.eta).put("stage", job.stage)
        )
    }

    fun jobJson(j: Job): JSObject =
        JSObject().put("id", j.id).put("percent", j.percent).put("speed", j.speed)
            .put("eta", j.eta).put("stage", j.stage)

    private fun aggregatePercent(): Int {
        val l = jobs.values.toList()
        if (l.isEmpty()) return 0
        return (l.sumOf { it.percent } / l.size).toInt()
    }

    private fun aggregateText(): String {
        val n = jobs.size
        return if (n <= 1) "جاري التحميل…" else "جاري تحميل $n ملفات"
    }

    // ------------------------------------------------------------------ أحداث

    fun emit(event: String, data: JSObject) {
        try { sink?.invoke(event, data) } catch (ignored: Exception) {}
    }

    /** done/failed: لو الواجهة غير متصلة نحتفظ بالنتيجة حتى تفتح وتسأل getActive. */
    private fun record(event: String, data: JSObject) {
        if (sink == null) {
            finished.add(JSObject().put("event", event).put("data", data))
        }
        emit(event, data)
    }

    fun drainFinished(): List<JSObject> {
        val l = ArrayList<JSObject>()
        while (true) { l.add(finished.poll() ?: break) }
        return l
    }

    private fun onStart(ctx: Context) {
        if (active.incrementAndGet() == 1) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (ignored: Exception) {
                // لو النظام منع الخدمة، التحميل يكمل طالما التطبيق مفتوح
            }
        }
    }

    private fun onEnd(ctx: Context) {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            // تأخير بسيط عشان لا نوقف الخدمة قبل ما تكمل startForeground
            main.postDelayed({
                if (active.get() == 0) ctx.stopService(Intent(ctx, DownloadService::class.java))
            }, 1500)
        }
    }

    // ------------------------------------------------------------------ تصنيف الأخطاء

    fun classify(raw: String): ManzelError {
        val m = raw.lowercase()
        fun e(code: String, msg: String) = ManzelError(code, msg, raw.trim().takeLast(300))
        fun has(vararg k: String) = k.any { m.contains(it) }
        return when {
            has("drm") ->
                e("drm", "هذا المحتوى محمي بـ DRM ولا يمكن تحميله.")
            has("not a bot", "confirm you’re not a bot", "confirm you're not a bot") ->
                e("bot", "يوتيوب طلب التحقق قبل التحميل. جرّب لاحقاً أو اضغط «تحديث المحرك» من الإعدادات.")
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") ->
                e("age", "المحتوى مقيّد بالعمر ويحتاج تسجيل دخول، والتطبيق يحمّل الروابط العامة فقط.")
            has("private video", "this video is private", "login required", "log in", "sign in", "requires authentication",
                "need to log in", "not logged in", "cookies", "checkpoint", "restricted video", "members-only", "members only") ->
                e("private", "الفيديو خاص أو يحتاج تسجيل دخول. التطبيق يحمّل الروابط العامة فقط.")
            has("not available in your country", "geo restricted", "geo-restricted", "blocked it in your country") ->
                e("geo", "الفيديو غير متاح في بلدك.")
            has("unsupported url") ->
                e("unsupported", "الرابط غير مدعوم أو غير صحيح. تأكد أنه رابط فيديو عام.")
            has("is live", "live event will begin", "live stream") ->
                e("live", "البث المباشر غير مدعوم.")
            has("429", "too many requests") ->
                e("rate_limit", "الموقع طلب الانتظار قليلاً. جرّب بعد بضع دقائق.")
            has("unable to download", "urlopen error", "timed out", "name or service not known",
                "network is unreachable", "connection reset", "temporary failure in name resolution", "ssl", "connection refused") ->
                e("network", "مشكلة في الاتصال بالإنترنت. تأكد من الشبكة وأعد المحاولة.")
            has("no space left") ->
                e("space", "مساحة التخزين ممتلئة. احذف بعض الملفات وأعد المحاولة.")
            has("requested format is not available") ->
                e("format", "الجودة المطلوبة غير متاحة لهذا الفيديو. اختر جودة أخرى.")
            has("video unavailable", "has been removed", "404", "does not exist", "no video formats", "not available", "deleted") ->
                e("unavailable", "الفيديو غير متاح أو تم حذفه.")
            has("unable to extract", "extractorerror", "nsig", "unable to find", "please report this issue", "no such file", "errno") ->
                e("extractor", "الموقع غيّر طريقة العرض. اضغط «تحديث المحرك» من الإعدادات ثم أعد المحاولة.")
            else ->
                e("unknown", "تعذّر إتمام العملية.")
        }
    }
}
