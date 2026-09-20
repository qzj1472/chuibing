package id.tntwindow.editor.domain

import org.json.JSONObject

data class RotationConfig(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val rotation: Int = 0,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("locked", locked)
        o.put("rotation", rotation)
        return o.toString()
    }

    companion object {
        fun default(): RotationConfig = RotationConfig()

        fun fromJson(text: String): RotationConfig {
            val o = JSONObject(text)
            return RotationConfig(
                enabled = o.optBoolean("enabled", false),
                locked = o.optBoolean("locked", false),
                rotation = o.optInt("rotation", 0).coerceIn(0, 3),
            )
        }
    }
}
