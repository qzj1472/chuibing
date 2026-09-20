package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.InstallConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class InstallStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "install_config.json")

    fun load(): InstallConfig {
        if (!local.exists() || local.length() == 0L) return InstallConfig.default()
        return try {
            InstallConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            InstallConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: InstallConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_install_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.INSTALL + "' && chown system:system '" + Paths.INSTALL + "' && chmod 644 '" + Paths.INSTALL + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}
