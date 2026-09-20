package id.tntwindow.editor

import android.app.Application
import id.tntwindow.editor.data.HomeLock
import id.tntwindow.editor.data.RootAccess
import id.tntwindow.editor.voice.SherpaEngine
import id.tntwindow.editor.voice.VoiceWarmService
import id.tntwindow.editor.xposed.VoiceRuntime
import id.tntwindow.editor.xposed.VoiceSpeak

class TntApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                if (RootAccess.available()) HomeLock.cleanupWatch()
            } catch (_: Exception) {
            }
        }.start()
        VoiceWarmService.start(this)
        SherpaEngine.preload(this, VoiceRuntime.recognizerId())
        VoiceSpeak.prepare(this)
    }
}