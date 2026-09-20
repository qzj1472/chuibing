package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.UninstallConfig
import java.io.File

class UninstallStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "uninstall_config.json")

    fun load(): UninstallConfig {
        if (!local.exists() || local.length() == 0L) return UninstallConfig.default()
        return try {
            UninstallConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            UninstallConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: UninstallConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_uninstall_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.UNINSTALL + "' && chown system:system '" + Paths.UNINSTALL + "' && chmod 644 '" + Paths.UNINSTALL + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}
