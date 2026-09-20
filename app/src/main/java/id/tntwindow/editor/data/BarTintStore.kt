package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.BarTintConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class BarTintStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "bartint_config.json")

    fun load(): BarTintConfig {
        if (!local.exists() || local.length() == 0L) return BarTintConfig.default()
        return try {
            BarTintConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            BarTintConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: BarTintConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_bartint_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.BARTINT + "' && chown system:system '" + Paths.BARTINT + "' && chmod 644 '" + Paths.BARTINT + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}
