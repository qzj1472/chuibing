package id.tntwindow.editor

import android.app.Application
import id.tntwindow.editor.data.HomeLock
import id.tntwindow.editor.data.RootAccess

class TntApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                if (RootAccess.available()) HomeLock.cleanupWatch()
            } catch (_: Exception) {
            }
        }.start()
    }
}
