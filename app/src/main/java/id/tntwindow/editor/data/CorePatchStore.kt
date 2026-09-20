package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.CorePatchConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class CorePatchStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "corepatch_config.json")

    fun load(): CorePatchConfig {
        if (!local.exists() || local.length() == 0L) return CorePatchConfig.default()
        return try {
            CorePatchConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            CorePatchConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: CorePatchConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_corepatch_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.COREPATCH + "' && chown system:system '" + Paths.COREPATCH + "' && chmod 644 '" + Paths.COREPATCH + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }

    companion object {
        val SCOPE = listOf(
            "android",
            "system",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.smartisanos.packageinstaller",
        )
    }
}