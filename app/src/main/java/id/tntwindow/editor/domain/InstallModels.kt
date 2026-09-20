package id.tntwindow.editor.domain

import org.json.JSONObject

data class InstallHandler(
    val pkg: String,
    val cls: String,
    val appLabel: String,
    val activityLabel: String,
) {
    val component: String get() = pkg + "/" + cls
}

data class InstallConfig(
    val enabled: Boolean = false,
    val pkg: String = "",
    val cls: String = "",
    val shareUninstall: Boolean = true,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("pkg", pkg)
        o.put("cls", cls)
        o.put("shareUninstall", shareUninstall)
        return o.toString()
    }

    companion object {
        fun default(): InstallConfig = InstallConfig()

        fun fromJson(text: String): InstallConfig {
            val o = JSONObject(text)
            return InstallConfig(
                enabled = o.optBoolean("enabled", false),
                pkg = o.optString("pkg", ""),
                cls = o.optString("cls", ""),
                shareUninstall = o.optBoolean("shareUninstall", true),
            )
        }
    }
}
