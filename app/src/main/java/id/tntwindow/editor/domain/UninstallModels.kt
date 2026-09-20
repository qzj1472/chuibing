package id.tntwindow.editor.domain

import org.json.JSONObject

data class UninstallHandler(
    val pkg: String,
    val cls: String,
    val appLabel: String,
    val activityLabel: String,
) {
    val component: String get() = pkg + "/" + cls
}

data class UninstallConfig(
    val enabled: Boolean = false,
    val pkg: String = "",
    val cls: String = "",
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("pkg", pkg)
        o.put("cls", cls)
        return o.toString()
    }

    companion object {
        fun default(): UninstallConfig = UninstallConfig()

        fun fromJson(text: String): UninstallConfig {
            val o = JSONObject(text)
            return UninstallConfig(
                enabled = o.optBoolean("enabled", false),
                pkg = o.optString("pkg", ""),
                cls = o.optString("cls", ""),
            )
        }
    }
}
