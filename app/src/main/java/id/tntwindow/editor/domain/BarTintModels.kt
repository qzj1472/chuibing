package id.tntwindow.editor.domain

import org.json.JSONObject

data class BarTintConfig(
    val nav: Boolean = true,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("nav", nav)
        return o.toString()
    }

    companion object {
        fun default(): BarTintConfig = BarTintConfig()

        fun fromJson(text: String): BarTintConfig {
            val o = JSONObject(text)
            return BarTintConfig(nav = o.optBoolean("nav", true))
        }
    }
}
