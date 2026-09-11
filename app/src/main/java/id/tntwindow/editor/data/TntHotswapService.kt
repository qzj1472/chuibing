package id.tntwindow.editor.data

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TntHotswapService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extra = intent?.getStringExtra("extra") ?: ""
        val restore = intent?.getStringExtra("restore") ?: ""
        Thread {
            try {
                DisplayControl.armHotswap(extra, restore)
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }
}
