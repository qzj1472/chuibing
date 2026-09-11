package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.VoiceConfig
import java.io.File

class VoiceStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "voice_config.json")

    fun load(): VoiceConfig {
        if (!local.exists() || local.length() == 0L) return VoiceConfig.default()
        return try {
            VoiceConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            VoiceConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: VoiceConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_voice_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.VOICE + "' && chown system:system '" + Paths.VOICE + "' && chmod 644 '" + Paths.VOICE + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}