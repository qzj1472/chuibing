package id.tntwindow.editor.voice

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object VoiceModelDownload {
    private val lock = Any()
    const val NONE = "none"
    const val DOWNLOADING = "downloading"
    const val EXTRACTING = "extracting"
    const val READY = "ready"
    const val FAILED = "failed"

    private const val PREF = "voice_asr_dl"
    private const val CHANNEL = "chuibing-asr-model"
    private const val NID = 1851

    private val extracting = HashSet<String>()
    private val packLocks = HashMap<String, Any>()
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    fun bump() {
        _tick.value = System.currentTimeMillis()
    }

    fun modelDir(context: Context, pack: VoiceAsrPack): File {
        return File(File(context.filesDir, "voice-models"), pack.dirName)
    }

    fun archiveFile(context: Context, pack: VoiceAsrPack): File {
        val root = context.getExternalFilesDir("voice-models") ?: File(context.getExternalFilesDir(null), "voice-models")
        return File(root, pack.archiveName)
    }

    fun hasArchive(context: Context, pack: VoiceAsrPack): Boolean {
        val f = archiveFile(context, pack)
        return f.isFile && f.length() > 1_000_000L
    }

    fun isReady(context: Context, pack: VoiceAsrPack): Boolean {
        val dir = modelDir(context, pack)
        if (!File(dir, "ready").exists()) return false
        return pack.files.all { File(dir, it).isFile && File(dir, it).length() > 0L }
    }

    fun state(context: Context, pack: VoiceAsrPack): String {
        if (isReady(context, pack)) return READY
        val id = downloadId(context, pack)
        if (id > 0L) {
            val st = queryStatus(context, id)
            if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) {
                return DOWNLOADING
            }
        }
        val busy = synchronized(lock) { extracting.contains(pack.dirName) }
        if (busy) return EXTRACTING
        val err = prefs(context).getString("err_" + pack.dirName, "")
        if (!err.isNullOrBlank()) return FAILED
        if (id > 0L && queryStatus(context, id) == DownloadManager.STATUS_FAILED && !hasArchive(context, pack)) return FAILED
        if (hasArchive(context, pack)) return EXTRACTING
        return NONE
    }

    fun stateText(context: Context, pack: VoiceAsrPack): String {
        return when (state(context, pack)) {
            READY -> "已就绪"
            DOWNLOADING -> "下载中"
            EXTRACTING -> "解压中"
            FAILED -> if (hasArchive(context, pack)) "解压失败，点一下继续" else "下载失败，点一下重试"
            else -> "未下载"
        }
    }

    fun start(context: Context, packId: String): String {
        val pack = VoiceAsrPacks.byId(packId) ?: return "没有这个模型"
        val app = context.applicationContext
        if (isReady(app, pack)) return pack.title + " 已经下好了"
        val old = downloadId(app, pack)
        if (old > 0L) {
            val st = queryStatus(app, old)
            if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) {
                bump()
                return pack.title + " 正在下载，通知栏看进度"
            }
        }
        val busy = synchronized(lock) { extracting.contains(pack.dirName) }
        if (busy) {
            bump()
            return pack.title + " 正在解压，通知栏看进度"
        }
        if (hasArchive(app, pack)) {
            prefs(app).edit().remove("err_" + pack.dirName).apply()
            requestExtract(app, pack, -1L)
            return pack.title + " 正在解压，通知栏看进度"
        }
        val dest = archiveFile(app, pack)
        try {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
        } catch (_: Throwable) {
        }
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(pack.url))
        req.setTitle(pack.title)
        req.setDescription("正在下载听写模型")
        req.setMimeType("application/octet-stream")
        req.setAllowedOverMetered(true)
        req.setAllowedOverRoaming(true)
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        req.setDestinationInExternalFilesDir(app, "voice-models", pack.archiveName)
        val id = try {
            dm.enqueue(req)
        } catch (t: Throwable) {
            rememberErr(app, pack, t.message ?: t.javaClass.simpleName)
            notify(app, pack.title + " 下载失败", t.message ?: t.javaClass.simpleName)
            bump()
            return pack.title + " 下载失败：" + (t.message ?: t.javaClass.simpleName)
        }
        prefs(app).edit()
            .putLong("dl_" + pack.dirName, id)
            .putString("pack_" + id, pack.id)
            .remove("err_" + pack.dirName)
            .apply()
        bump()
        return "已开始下载 " + pack.title + "，通知栏看进度，不影响现在用"
    }

    fun handleDownload(context: Context, downloadId: Long) {
        completeDownload(context.applicationContext, downloadId)
    }

    fun handlePack(context: Context, packId: String) {
        val pack = VoiceAsrPacks.byId(packId) ?: return
        runExtract(context.applicationContext, pack)
    }

    fun requestExtract(context: Context, pack: VoiceAsrPack, downloadId: Long) {
        val app = context.applicationContext
        val accepted = synchronized(lock) {
            if (isReady(app, pack)) false
            else extracting.add(pack.dirName)
        }
        if (!accepted) return
        bump()
        try {
            if (downloadId > 0L) VoiceModelExtractService.startDownload(app, downloadId, pack.id)
            else VoiceModelExtractService.startPack(app, pack.id)
        } catch (t: Throwable) {
            synchronized(lock) { extracting.remove(pack.dirName) }
            rememberErr(app, pack, t.message ?: t.javaClass.simpleName)
            notify(app, pack.title + " 解压失败", t.message ?: t.javaClass.simpleName)
            bump()
        }
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        for (pack in VoiceAsrPacks.all) {
            if (isReady(app, pack)) continue
            val busy = synchronized(lock) { extracting.contains(pack.dirName) }
            if (busy) continue
            val id = downloadId(app, pack)
            if (id > 0L) {
                val st = queryStatus(app, id)
                if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) continue
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    requestExtract(app, pack, id)
                    continue
                }
            }
            if (!hasArchive(app, pack)) continue
            val err = prefs(app).getString("err_" + pack.dirName, "")
            if (!err.isNullOrBlank()) continue
            requestExtract(app, pack, -1L)
        }
    }

    private fun completeDownload(context: Context, downloadId: Long) {
        val packId = prefs(context).getString("pack_" + downloadId, "") ?: ""
        val pack = VoiceAsrPacks.byId(packId) ?: return
        val st = queryStatus(context, downloadId)
        if (st != DownloadManager.STATUS_SUCCESSFUL) {
            if (hasArchive(context, pack)) {
                runExtract(context, pack)
                return
            }
            val reason = queryReason(context, downloadId)
            rememberErr(context, pack, "下载失败 " + reason)
            notify(context, pack.title + " 下载失败", "网络或 GitHub 不可用")
            bump()
            return
        }
        runExtract(context, pack)
        if (isReady(context, pack)) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(downloadId)
            } catch (_: Throwable) {
            }
        }
    }

    private fun packLock(dirName: String): Any {
        synchronized(lock) {
            val oldLock = packLocks[dirName]
            if (oldLock != null) return oldLock
            val created = Any()
            packLocks[dirName] = created
            return created
        }
    }

    private fun runExtract(context: Context, pack: VoiceAsrPack) {
        synchronized(packLock(pack.dirName)) {
            synchronized(lock) { extracting.add(pack.dirName) }
            bump()
            try {
                if (isReady(context, pack)) return
                extract(context, pack)
                prefs(context).edit().remove("err_" + pack.dirName).apply()
                notify(context, pack.title + " 已就绪", "可以在听写引擎里选用了")
            } catch (t: Throwable) {
                rememberErr(context, pack, t.message ?: t.javaClass.simpleName)
                notify(context, pack.title + " 解压失败", t.message ?: t.javaClass.simpleName)
            } finally {
                synchronized(lock) { extracting.remove(pack.dirName) }
                bump()
            }
        }
    }

    private fun extract(context: Context, pack: VoiceAsrPack) {
        val src = archiveFile(context, pack)
        if (!src.isFile || src.length() <= 1_000_000L) throw IllegalStateException("安装包不存在")
        val dest = modelDir(context, pack)
        val staging = File(dest.parentFile, pack.dirName + ".staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        var tin: TarArchiveInputStream? = null
        try {
            tin = TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(src))))
            while (true) {
                val entry = tin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = File(entry.name).name
                if (!pack.files.contains(name)) continue
                val out = File(staging, name)
                FileOutputStream(out).use { os ->
                    tin.copyTo(os)
                }
            }
        } finally {
            try { tin?.close() } catch (_: Throwable) {}
        }
        val missing = pack.files.filterNot { File(staging, it).isFile && File(staging, it).length() > 0L }
        if (missing.isNotEmpty()) {
            staging.deleteRecursively()
            throw IllegalStateException("缺文件 " + missing.joinToString(","))
        }
        File(staging, "ready").writeText("ok")
        if (dest.exists()) dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            dest.mkdirs()
            staging.listFiles()?.forEach { child ->
                child.copyRecursively(File(dest, child.name), true)
            }
            staging.deleteRecursively()
        }
        try { src.delete() } catch (_: Throwable) {}
    }

    private fun downloadId(context: Context, pack: VoiceAsrPack): Long {
        return prefs(context).getLong("dl_" + pack.dirName, -1L)
    }

    private fun queryStatus(context: Context, id: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        val c = try { dm.query(q) } catch (_: Throwable) { null } ?: return -1
        c.use {
            if (!it.moveToFirst()) return -1
            val idx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (idx < 0) return -1
            return it.getInt(idx)
        }
    }

    private fun queryReason(context: Context, id: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        val c = try { dm.query(q) } catch (_: Throwable) { null } ?: return -1
        c.use {
            if (!it.moveToFirst()) return -1
            val idx = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            if (idx < 0) return -1
            return it.getInt(idx)
        }
    }

    private fun rememberErr(context: Context, pack: VoiceAsrPack, msg: String) {
        prefs(context).edit().putString("err_" + pack.dirName, msg).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun notify(context: Context, title: String, text: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "听写模型", NotificationManager.IMPORTANCE_DEFAULT)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
            val n = Notification.Builder(context, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()
            nm.notify(NID + title.hashCode(), n)
        } catch (_: Throwable) {
        }
    }
}