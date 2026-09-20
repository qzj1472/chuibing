package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.RotationConfig
import java.io.File

class RotationStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "rotation_config.json")

    fun load(): RotationConfig {
        if (!local.exists() || local.length() == 0L) return RotationConfig.default()
        return try {
            RotationConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            RotationConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: RotationConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_rotation_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.ROTATION + "' && chown system:system '" + Paths.ROTATION + "' && chmod 644 '" + Paths.ROTATION + "'"
        )
        val cfg = load()
        if (cfg.enabled && cfg.locked) {
            RootAccess.su("settings put system accelerometer_rotation 0")
            RootAccess.su("settings put system user_rotation " + cfg.rotation)
        } else if (cfg.enabled) {
            RootAccess.su("settings put system accelerometer_rotation 1")
        }
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}
