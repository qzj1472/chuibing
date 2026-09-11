package id.tntwindow.editor.data

import android.content.Context
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.WebViewConfig
import java.io.File

class WebViewStore(private val context: Context) {
    private val local: File get() = File(context.filesDir, "webview_config.json")

    fun load(): WebViewConfig {
        if (!local.exists() || local.length() == 0L) return WebViewConfig.default()
        return try {
            WebViewConfig.fromJson(local.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            WebViewConfig.default()
        }
    }

    fun exists(): Boolean = local.exists() && local.length() > 0L

    fun save(cfg: WebViewConfig) {
        local.parentFile?.mkdirs()
        local.writeText(cfg.toJson(), Charsets.UTF_8)
    }

    fun push(): ShellResult {
        if (!local.exists()) save(load())
        val src = local.absolutePath
        val staged = "/data/local/tmp/tnt_webview_config.json"
        val copy = RootAccess.su("cat '" + src + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su(
            "cp '" + staged + "' '" + Paths.WEBVIEW + "' && chown system:system '" + Paths.WEBVIEW + "' && chmod 644 '" + Paths.WEBVIEW + "'"
        )
        return if (install.ok) ShellResult(0, "ok", "") else install
    }
}