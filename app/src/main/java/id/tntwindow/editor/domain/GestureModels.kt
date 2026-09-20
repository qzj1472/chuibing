package id.tntwindow.editor.domain

import org.json.JSONObject

data class GestureEdge(
    val id: String,
    val enabled: Boolean = false,
    val click: KeyAction = KeyAction.none(),
    val doubleClick: KeyAction = KeyAction.none(),
    val longPress: KeyAction = KeyAction.none(),
    val swipeIn: KeyAction = KeyAction.none(),
    val swipeAlongA: KeyAction = KeyAction.none(),
    val swipeAlongB: KeyAction = KeyAction.none(),
    val hoverIn: KeyAction = KeyAction.none(),
    val hoverAlongA: KeyAction = KeyAction.none(),
    val hoverAlongB: KeyAction = KeyAction.none(),
) {
    fun action(slot: String): KeyAction = when (slot) {
        "click" -> click
        "double" -> doubleClick
        "long" -> longPress
        "swipe_in" -> swipeIn
        "swipe_a" -> swipeAlongA
        "swipe_b" -> swipeAlongB
        "hover_in" -> hoverIn
        "hover_a" -> hoverAlongA
        "hover_b" -> hoverAlongB
        else -> KeyAction.none()
    }

    fun withAction(slot: String, action: KeyAction): GestureEdge = when (slot) {
        "click" -> copy(click = action)
        "double" -> copy(doubleClick = action)
        "long" -> copy(longPress = action)
        "swipe_in" -> copy(swipeIn = action)
        "swipe_a" -> copy(swipeAlongA = action)
        "swipe_b" -> copy(swipeAlongB = action)
        "hover_in" -> copy(hoverIn = action)
        "hover_a" -> copy(hoverAlongA = action)
        "hover_b" -> copy(hoverAlongB = action)
        else -> this
    }

    fun slots(): List<String> = listOf("click", "double", "long", "swipe_in", "swipe_a", "swipe_b")

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("enabled", enabled)
        o.put("click", click.toJson())
        o.put("double", doubleClick.toJson())
        o.put("long", longPress.toJson())
        o.put("swipeIn", swipeIn.toJson())
        o.put("swipeA", swipeAlongA.toJson())
        o.put("swipeB", swipeAlongB.toJson())
        o.put("hoverIn", hoverIn.toJson())
        o.put("hoverA", hoverAlongA.toJson())
        o.put("hoverB", hoverAlongB.toJson())
        return o
    }

    companion object {
        fun fromJson(id: String, o: JSONObject?): GestureEdge {
            if (o == null) return GestureEdge(id)
            return GestureEdge(
                id = o.optString("id", id).ifBlank { id },
                enabled = o.optBoolean("enabled", false),
                click = KeyAction.fromJson(o.optJSONObject("click")),
                doubleClick = KeyAction.fromJson(o.optJSONObject("double")),
                longPress = KeyAction.fromJson(o.optJSONObject("long")),
                swipeIn = KeyAction.fromJson(o.optJSONObject("swipeIn")),
                swipeAlongA = KeyAction.fromJson(o.optJSONObject("swipeA")),
                swipeAlongB = KeyAction.fromJson(o.optJSONObject("swipeB")),
                hoverIn = KeyAction.fromJson(o.optJSONObject("hoverIn")),
                hoverAlongA = KeyAction.fromJson(o.optJSONObject("hoverA")),
                hoverAlongB = KeyAction.fromJson(o.optJSONObject("hoverB")),
            )
        }
    }
}

data class GestureConfig(
    val enabled: Boolean = false,
    val vibrate: Boolean = true,
    val thickness: Int = 28,
    val longMs: Int = 400,
    val doubleMs: Int = 280,
    val hoverMs: Int = 400,
    val hoverPx: Int = 24,
    val vibrateMs: Int = 50,
    val vibrateAmp: Int = 255,
    val lockscreen: Boolean = false,
    val left: GestureEdge = GestureEdge("left"),
    val right: GestureEdge = GestureEdge("right"),
    val bottom: GestureEdge = GestureEdge("bottom"),
    val leftTop: GestureEdge = GestureEdge("left_top"),
    val leftMid: GestureEdge = GestureEdge("left_mid"),
    val leftBot: GestureEdge = GestureEdge("left_bot"),
    val rightTop: GestureEdge = GestureEdge("right_top"),
    val rightMid: GestureEdge = GestureEdge("right_mid"),
    val rightBot: GestureEdge = GestureEdge("right_bot"),
    val bottomLeft: GestureEdge = GestureEdge("bottom_left"),
    val bottomMid: GestureEdge = GestureEdge("bottom_mid"),
    val bottomRight: GestureEdge = GestureEdge("bottom_right"),
) {
    fun edge(id: String): GestureEdge? = when (id) {
        "left" -> left
        "right" -> right
        "bottom" -> bottom
        "left_top" -> leftTop
        "left_mid" -> leftMid
        "left_bot" -> leftBot
        "right_top" -> rightTop
        "right_mid" -> rightMid
        "right_bot" -> rightBot
        "bottom_left" -> bottomLeft
        "bottom_mid" -> bottomMid
        "bottom_right" -> bottomRight
        else -> null
    }

    fun withEdge(next: GestureEdge): GestureConfig = when (next.id) {
        "left" -> copy(left = next)
        "right" -> copy(right = next)
        "bottom" -> copy(bottom = next)
        "left_top" -> copy(leftTop = next)
        "left_mid" -> copy(leftMid = next)
        "left_bot" -> copy(leftBot = next)
        "right_top" -> copy(rightTop = next)
        "right_mid" -> copy(rightMid = next)
        "right_bot" -> copy(rightBot = next)
        "bottom_left" -> copy(bottomLeft = next)
        "bottom_mid" -> copy(bottomMid = next)
        "bottom_right" -> copy(bottomRight = next)
        else -> this
    }

    fun overlayIds(): List<String> = listOf(
        "left", "right", "bottom",
        "left_top", "left_mid", "left_bot",
        "right_top", "right_mid", "right_bot",
        "bottom_left", "bottom_mid", "bottom_right",
    )

    fun uiGroups(): List<Pair<String, List<String>>> = listOf(
        "左" to listOf("left_top", "left_mid", "left_bot", "left"),
        "右" to listOf("right_top", "right_mid", "right_bot", "right"),
        "下" to listOf("bottom_left", "bottom_mid", "bottom_right", "bottom"),
    )

    fun enabledCount(): Int = overlayIds().count { edge(it)?.enabled == true }

    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("vibrate", vibrate)
        o.put("thickness", thickness)
        o.put("longMs", longMs)
        o.put("doubleMs", doubleMs)
        o.put("hoverMs", hoverMs)
        o.put("hoverPx", hoverPx)
        o.put("vibrateMs", vibrateMs)
        o.put("vibrateAmp", vibrateAmp)
        o.put("lockscreen", lockscreen)
        for (id in overlayIds()) {
            val e = edge(id) ?: continue
            o.put(id, e.toJson())
        }
        return o.toString(2)
    }

    companion object {
        fun default(): GestureConfig = GestureConfig()

        fun fromJson(text: String): GestureConfig {
            val o = JSONObject(text)
            return GestureConfig(
                enabled = o.optBoolean("enabled", false),
                vibrate = o.optBoolean("vibrate", true),
                thickness = o.optInt("thickness", 28).coerceIn(12, 80),
                longMs = o.optInt("longMs", 400).coerceIn(180, 1500),
                doubleMs = o.optInt("doubleMs", 280).coerceIn(120, 800),
                hoverMs = o.optInt("hoverMs", 400).coerceIn(120, 1500),
                hoverPx = o.optInt("hoverPx", 24).coerceIn(8, 80),
                vibrateMs = o.optInt("vibrateMs", 50).coerceIn(10, 200),
                vibrateAmp = o.optInt("vibrateAmp", 255).coerceIn(1, 255),
                lockscreen = o.optBoolean("lockscreen", false),
                left = GestureEdge.fromJson("left", o.optJSONObject("left")),
                right = GestureEdge.fromJson("right", o.optJSONObject("right")),
                bottom = GestureEdge.fromJson("bottom", o.optJSONObject("bottom")),
                leftTop = GestureEdge.fromJson("left_top", o.optJSONObject("left_top")),
                leftMid = GestureEdge.fromJson("left_mid", o.optJSONObject("left_mid")),
                leftBot = GestureEdge.fromJson("left_bot", o.optJSONObject("left_bot")),
                rightTop = GestureEdge.fromJson("right_top", o.optJSONObject("right_top")),
                rightMid = GestureEdge.fromJson("right_mid", o.optJSONObject("right_mid")),
                rightBot = GestureEdge.fromJson("right_bot", o.optJSONObject("right_bot")),
                bottomLeft = GestureEdge.fromJson("bottom_left", o.optJSONObject("bottom_left")),
                bottomMid = GestureEdge.fromJson("bottom_mid", o.optJSONObject("bottom_mid")),
                bottomRight = GestureEdge.fromJson("bottom_right", o.optJSONObject("bottom_right")),
            )
        }
    }
}

object GestureLabels {
    fun edgeTitle(id: String): String = when (id) {
        "left" -> "左边缘"
        "right" -> "右边缘"
        "bottom" -> "下边缘"
        "left_top" -> "左上"
        "left_mid" -> "左中"
        "left_bot" -> "左下"
        "right_top" -> "右上"
        "right_mid" -> "右中"
        "right_bot" -> "右下"
        "bottom_left" -> "下左"
        "bottom_mid" -> "下中"
        "bottom_right" -> "下右"
        else -> id
    }

    fun hoverSlot(slot: String): String? = when (slot) {
        "swipe_in" -> "hover_in"
        "swipe_a" -> "hover_a"
        "swipe_b" -> "hover_b"
        else -> null
    }

    fun swipeSide(id: String): String = when {
        id.startsWith("left") -> "left"
        id.startsWith("right") -> "right"
        else -> "bottom"
    }

    fun slotTitle(edge: String, slot: String): String {
        val side = swipeSide(edge)
        return when (slot) {
            "click" -> "单击"
            "double" -> "双击"
            "long" -> "长按"
            "swipe_in" -> when (side) {
                "left" -> "右滑"
                "right" -> "左滑"
                else -> "上滑"
            }
            "swipe_a" -> when (side) {
                "bottom" -> "左滑"
                else -> "上滑"
            }
            "swipe_b" -> when (side) {
                "bottom" -> "右滑"
                else -> "下滑"
            }
            "hover_in" -> slotTitle(edge, "swipe_in") + "悬停"
            "hover_a" -> slotTitle(edge, "swipe_a") + "悬停"
            "hover_b" -> slotTitle(edge, "swipe_b") + "悬停"
            else -> slot
        }
    }
}