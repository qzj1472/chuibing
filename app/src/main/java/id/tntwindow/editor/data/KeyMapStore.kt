package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.KeyMapConfig
import id.tntwindow.editor.domain.Paths
import org.json.JSONObject
import java.io.File

class KeyMapStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "keymap_config.json")

    fun load(): KeyMapConfig {
        if (!local.exists() || local.length() == 0L) return KeyMapConfig.default()
        return try {
            KeyMapConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            KeyMapConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: KeyMapConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        return RootAccess.writeSystemJson(local, Paths.KEYMAP, "/data/local/tmp/tnt_keymap_config.json")
    }

    fun setCapture(on: Boolean) {
        writeCapture(JSONObject().put("on", on).put("code", 0).put("name", "").put("at", 0L))
    }

    fun writeCaptured(code: Int, name: String) {
        writeCapture(
            JSONObject()
                .put("on", true)
                .put("code", code)
                .put("name", name)
                .put("at", System.currentTimeMillis()),
        )
    }

    fun readCapture(): Pair<Int, String>? {
        val text = RootAccess.su("cat '" + Paths.KEYMAP_CAPTURE + "' 2>/dev/null; cat /data/local/tmp/tnt_keymap_capture.json 2>/dev/null").out
        if (text.isBlank()) return null
        return try {
            val o = JSONObject(text.lineSequence().first { it.contains("{") })
            val code = o.optInt("code", 0)
            if (!o.optBoolean("on", false) || code == 0) null else code to o.optString("name")
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCapture(o: JSONObject) {
        val cache = File(context.filesDir, "keymap_capture.json")
        cache.writeText(o.toString(), Charsets.UTF_8)
        RootAccess.writeSystemJson(cache, Paths.KEYMAP_CAPTURE, "/data/local/tmp/tnt_keymap_capture.json")
    }
}