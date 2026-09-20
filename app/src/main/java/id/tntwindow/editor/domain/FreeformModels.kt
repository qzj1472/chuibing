package id.tntwindow.editor.domain

import org.json.JSONArray
import org.json.JSONObject

data class FreeformConfig(
    val widthPct: Int = 72,
    val heightPct: Int = 52,
    val maxWindows: Int = 3,
    val left: KeyAction = KeyAction(KeyAction.TYPE_FREEFORM_CLOSE),
    val right: KeyAction = KeyAction(KeyAction.TYPE_FREEFORM_FULLSCREEN),
    val sidebar: Boolean = true,
    val sidebarSide: String = SIDE_LEFT,
    val sidebarTopPct: Int = 31,
    val sidebarLockscreen: Boolean = false,
    val apps: List<String> = emptyList(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("widthPct", widthPct)
        o.put("heightPct", heightPct)
        o.put("maxWindows", maxWindows)
        o.put("left", left.toJson())
        o.put("right", right.toJson())
        o.put("sidebar", sidebar)
        o.put("sidebarSide", sidebarSide)
        o.put("sidebarTopPct", sidebarTopPct)
        o.put("sidebarLockscreen", sidebarLockscreen)
        val arr = JSONArray()
        for (pkg in apps) arr.put(pkg)
        o.put("apps", arr)
        return o.toString()
    }

    companion object {
        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"

        fun default(): FreeformConfig = FreeformConfig()

        fun fromJson(text: String): FreeformConfig {
            val o = JSONObject(text)
            val arr = o.optJSONArray("apps")
            val apps = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i, "")
                    if (pkg.isNotBlank()) apps.add(pkg)
                }
            }
            val side = o.optString("sidebarSide", SIDE_LEFT)
            return FreeformConfig(
                widthPct = o.optInt("widthPct", 72).coerceIn(40, 95),
                heightPct = o.optInt("heightPct", 52).coerceIn(35, 90),
                maxWindows = o.optInt("maxWindows", 3).coerceIn(1, 8),
                left = if (o.has("left")) KeyAction.fromJson(o.optJSONObject("left")) else KeyAction(KeyAction.TYPE_FREEFORM_CLOSE),
                right = if (o.has("right")) KeyAction.fromJson(o.optJSONObject("right")) else KeyAction(KeyAction.TYPE_FREEFORM_FULLSCREEN),
                sidebar = o.optBoolean("sidebar", true),
                sidebarSide = if (side == SIDE_RIGHT) SIDE_RIGHT else SIDE_LEFT,
                sidebarTopPct = o.optInt("sidebarTopPct", 31).coerceIn(0, 90),
                sidebarLockscreen = o.optBoolean("sidebarLockscreen", false),
                apps = apps,
            )
        }
    }
}
