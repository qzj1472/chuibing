package id.tntwindow.editor.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import id.tntwindow.editor.xposed.VoiceRuntime
import id.tntwindow.editor.xposed.VoiceSpeak

class VoiceWarmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SherpaEngine.preload(this, VoiceRuntime.recognizerId())
        VoiceSpeak.prepare(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SherpaEngine.preload(this, VoiceRuntime.recognizerId())
        VoiceSpeak.prepare(this)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            val i = Intent()
            i.setClassName("id.tntwindow.editor", "id.tntwindow.editor.voice.VoiceWarmService")
            try {
                context.startService(i)
            } catch (_: Throwable) {
                try {
                    context.startForegroundService(i)
                } catch (_: Throwable) {
                }
            }
        }
    }
}