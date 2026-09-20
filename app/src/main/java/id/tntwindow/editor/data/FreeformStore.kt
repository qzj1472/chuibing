package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.FreeformConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class FreeformStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "freeform_config.json")

    fun load(): FreeformConfig {
        val remote = readRemote()
        if (remote != null) {
            try {
                save(remote)
            } catch (_: Exception) {
            }
            return remote
        }
        if (!local.exists() || local.length() == 0L) return FreeformConfig.default()
        return try {
            FreeformConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            FreeformConfig.default()
        }
    }

    private fun readRemote(): FreeformConfig? {
        val paths = listOf(Paths.FREEFORM, "/data/local/tmp/tnt_freeform_config.json")
        for (path in paths) {
            val r = try {
                RootAccess.su("cat '" + path + "' 2>/dev/null")
            } catch (_: Exception) {
                continue
            }
            val text = r.out.trim()
            if (text.isBlank() || !text.startsWith("{")) continue
            return try {
                FreeformConfig.fromJson(text)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: FreeformConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun setHighlight(on: Boolean) {
        val text = if (on) "1" else "0"
        val mark = File(context.filesDir, "freeform_highlight")
        mark.writeText(text, Charsets.UTF_8)
        RootAccess.writeSystemJson(mark, Paths.FREEFORM_HIGHLIGHT_SYS, Paths.FREEFORM_HIGHLIGHT)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        return RootAccess.writeSystemJson(local, Paths.FREEFORM, "/data/local/tmp/tnt_freeform_config.json")
    }
}