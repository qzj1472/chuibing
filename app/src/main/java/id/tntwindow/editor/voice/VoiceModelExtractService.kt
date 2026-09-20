package id.tntwindow.editor.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

class VoiceModelExtractService : Service() {
    private val lock = Any()
    private var jobs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packId = intent?.getStringExtra(EXTRA_PACK).orEmpty()
        val downloadId = intent?.getLongExtra(EXTRA_DOWNLOAD, -1L) ?: -1L
        val title = VoiceAsrPacks.byId(packId)?.title ?: "听写模型"
        startFg(title)
        synchronized(lock) { jobs += 1 }
        Thread {
            try {
                if (downloadId > 0L) VoiceModelDownload.handleDownload(applicationContext, downloadId)
                else VoiceModelDownload.handlePack(applicationContext, packId)
            } catch (_: Throwable) {
            } finally {
                val idle = synchronized(lock) {
                    jobs -= 1
                    jobs <= 0
                }
                if (idle) {
                    stopFg()
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startFg(title: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "听写模型", NotificationManager.IMPORTANCE_LOW)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
            val n = Notification.Builder(this, CHANNEL)
                .setContentTitle("正在解压 " + title)
                .setContentText("解压完会再通知，不影响现在用")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
            startForeground(NID, n)
        } catch (_: Throwable) {
        }
    }

    private fun stopFg() {
        try { stopForeground(true) } catch (_: Throwable) {}
    }

    companion object {
        const val EXTRA_PACK = "pack"
        const val EXTRA_DOWNLOAD = "download"
        private const val CHANNEL = "chuibing-asr-extract"
        private const val NID = 1853

        fun startPack(context: Context, packId: String) {
            val i = Intent(context, VoiceModelExtractService::class.java)
            i.putExtra(EXTRA_PACK, packId)
            ContextCompat.startForegroundService(context, i)
        }

        fun startDownload(context: Context, downloadId: Long, packId: String) {
            val i = Intent(context, VoiceModelExtractService::class.java)
            i.putExtra(EXTRA_DOWNLOAD, downloadId)
            i.putExtra(EXTRA_PACK, packId)
            ContextCompat.startForegroundService(context, i)
        }
    }
}