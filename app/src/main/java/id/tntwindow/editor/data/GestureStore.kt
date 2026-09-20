package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.GestureConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class GestureStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "gesture_config.json")

    fun load(): GestureConfig {
        if (!local.exists() || local.length() == 0L) return GestureConfig.default()
        return try {
            GestureConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            GestureConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: GestureConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun setHighlight(on: Boolean) {
        val text = if (on) "1" else "0"
        val mark = File(context.filesDir, "gesture_highlight")
        mark.writeText(text, Charsets.UTF_8)
        RootAccess.writeSystemJson(mark, Paths.GESTURE_HIGHLIGHT_SYS, Paths.GESTURE_HIGHLIGHT)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        return RootAccess.writeSystemJson(local, Paths.GESTURE, "/data/local/tmp/tnt_gesture_config.json")
    }
}