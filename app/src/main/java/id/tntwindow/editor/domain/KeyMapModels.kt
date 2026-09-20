package id.tntwindow.editor.domain

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

data class KeyAction(
    val type: String = TYPE_NONE,
    val cmd: String = "",
    val root: Boolean = false,
    val system: Boolean = false,
    val pkg: String = "",
    val cls: String = "",
    val shortcutId: String = "",
    val intent: String = "",
    val text: String = "",
    val mode: String = MODE_TOGGLE,
) {
    fun isDefault(): Boolean = type == TYPE_DEFAULT
    fun isNone(): Boolean = type == TYPE_NONE
    fun needsSwitch(): Boolean = type in setOf(TYPE_WIFI, TYPE_DATA, TYPE_BT, TYPE_LOCATION, TYPE_FLASHLIGHT, TYPE_MUTE, TYPE_WAKELOCK)

    fun summary(): String {
        val base = KeyActionCatalog.title(type)
        return when (type) {
            TYPE_NONE, TYPE_DEFAULT -> base
            TYPE_SHELL -> if (cmd.isBlank()) base else base + "  " + cmd
            TYPE_APP, TYPE_APP_FREEFORM, TYPE_APP_TNT -> if (pkg.isBlank()) base else base + "  " + pkg
            TYPE_ACTIVITY -> if (cls.isBlank()) base else base + "  " + cls.substringAfterLast('.')
            TYPE_SHORTCUT -> if (shortcutId.isBlank() && intent.isBlank()) base else base + "  " + shortcutId.ifBlank { intent }
            TYPE_INPUT -> if (text.isBlank()) base else base + "  " + text
            TYPE_WIFI, TYPE_DATA, TYPE_BT, TYPE_LOCATION, TYPE_FLASHLIGHT, TYPE_MUTE, TYPE_WAKELOCK -> base + "  " + modeTitle(mode)
            else -> base
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        if (cmd.isNotBlank()) o.put("cmd", cmd)
        if (root) o.put("root", true)
        if (system) o.put("system", true)
        if (pkg.isNotBlank()) o.put("pkg", pkg)
        if (cls.isNotBlank()) o.put("cls", cls)
        if (shortcutId.isNotBlank()) o.put("shortcutId", shortcutId)
        if (intent.isNotBlank()) o.put("intent", intent)
        if (text.isNotBlank()) o.put("text", text)
        if (needsSwitch() || (mode.isNotBlank() && mode != MODE_TOGGLE)) o.put("mode", mode)
        return o
    }

    companion object {
        const val TYPE_NONE = "none"
        const val TYPE_DEFAULT = "default"
        const val TYPE_BACK = "back"
        const val TYPE_HOME = "home"
        const val TYPE_RECENTS = "recents"
        const val TYPE_NOTIFICATIONS = "notifications"
        const val TYPE_QUICK_SETTINGS = "quick_settings"
        const val TYPE_SHELL = "shell"
        const val TYPE_SHORTCUT = "shortcut"
        const val TYPE_ACTIVITY = "activity"
        const val TYPE_APP = "app"
        const val TYPE_APP_FREEFORM = "app_freeform"
        const val TYPE_APP_TNT = "app_tnt"
        const val TYPE_VOICE = "voice"
        const val TYPE_COPY = "copy"
        const val TYPE_PASTE = "paste"
        const val TYPE_CUT = "cut"
        const val TYPE_INPUT = "input"
        const val TYPE_RECORD = "record"
        const val TYPE_WIFI = "wifi"
        const val TYPE_DATA = "data"
        const val TYPE_BT = "bt"
        const val TYPE_LOCATION = "location"
        const val TYPE_LOCK = "lock"
        const val TYPE_SCREENSHOT = "screenshot"
        const val TYPE_FLASHLIGHT = "flashlight"
        const val TYPE_MUTE = "mute"
        const val TYPE_PLAY = "play"
        const val TYPE_NEXT = "next"
        const val TYPE_PREV = "prev"
        const val TYPE_KILL_TOP = "kill_top"
        const val TYPE_APP_NEXT = "app_next"
        const val TYPE_APP_PREV = "app_prev"
        const val TYPE_WAKE = "wake"
        const val TYPE_WAKELOCK = "wakelock"
        const val TYPE_FREEFORM_CURRENT = "freeform_current"
        const val TYPE_FREEFORM_SIDEBAR = "freeform_sidebar"
        const val TYPE_FREEFORM_CLOSE = "freeform_close"
        const val TYPE_FREEFORM_FULLSCREEN = "freeform_fullscreen"
        const val TYPE_ROTATE_REVERSE = "rotate_reverse"
        const val MODE_ON = "on"
        const val MODE_OFF = "off"
        const val MODE_TOGGLE = "toggle"

        fun none(): KeyAction = KeyAction(TYPE_NONE)
        fun default(): KeyAction = KeyAction(TYPE_DEFAULT)

        fun fromJson(o: JSONObject?): KeyAction {
            if (o == null) return none()
            return KeyAction(
                type = o.optString("type", TYPE_NONE).ifBlank { TYPE_NONE },
                cmd = o.optString("cmd", ""),
                root = o.optBoolean("root", false),
                system = o.optBoolean("system", false),
                pkg = o.optString("pkg", ""),
                cls = o.optString("cls", ""),
                shortcutId = o.optString("shortcutId", ""),
                intent = o.optString("intent", ""),
                text = o.optString("text", ""),
                mode = o.optString("mode", MODE_TOGGLE).ifBlank { MODE_TOGGLE },
            )
        }
    }
}

data class KeyBinding(
    val id: String,
    val code: Int,
    val name: String,
    val enabled: Boolean = true,
    val click: KeyAction = KeyAction.default(),
    val doubleClick: KeyAction = KeyAction.default(),
    val longPress: KeyAction = KeyAction.default(),
    val tripleClick: KeyAction = KeyAction.default(),
) {
    fun intercept(): Boolean {
        if (!enabled) return false
        return !click.isDefault() || !doubleClick.isDefault() || !longPress.isDefault() || !tripleClick.isDefault()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("code", code)
        o.put("name", name)
        o.put("enabled", enabled)
        o.put("click", click.toJson())
        o.put("double", doubleClick.toJson())
        o.put("long", longPress.toJson())
        o.put("triple", tripleClick.toJson())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): KeyBinding {
            return KeyBinding(
                id = o.optString("id").ifBlank { "k" + o.optInt("code") },
                code = o.optInt("code"),
                name = o.optString("name").ifBlank { KeyNames.of(o.optInt("code")) },
                enabled = o.optBoolean("enabled", true),
                click = if (o.has("click")) KeyAction.fromJson(o.optJSONObject("click")) else KeyAction.default(),
                doubleClick = if (o.has("double")) KeyAction.fromJson(o.optJSONObject("double")) else KeyAction.default(),
                longPress = if (o.has("long")) KeyAction.fromJson(o.optJSONObject("long")) else KeyAction.default(),
                tripleClick = if (o.has("triple")) KeyAction.fromJson(o.optJSONObject("triple")) else KeyAction.default(),
            )
        }
    }
}

data class KeyCombo(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val ordered: Boolean = true,
    val timeoutMs: Int = 800,
    val codes: List<Int> = emptyList(),
    val action: KeyAction = KeyAction.none(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("enabled", enabled)
        o.put("ordered", ordered)
        o.put("timeoutMs", timeoutMs)
        val arr = JSONArray()
        codes.forEach { arr.put(it) }
        o.put("codes", arr)
        o.put("action", action.toJson())
        return o
    }

    fun keysLabel(): String {
        val names = codes.map { KeyNames.of(it) }
        return if (ordered) names.joinToString(" → ") else names.joinToString(" + ")
    }

    companion object {
        fun fromJson(o: JSONObject): KeyCombo {
            val arr = o.optJSONArray("codes") ?: JSONArray()
            val codes = mutableListOf<Int>()
            for (i in 0 until arr.length()) codes += arr.optInt(i)
            return KeyCombo(
                id = o.optString("id").ifBlank { "c" + System.currentTimeMillis() },
                name = o.optString("name").ifBlank { "组合键" },
                enabled = o.optBoolean("enabled", true),
                ordered = o.optBoolean("ordered", true),
                timeoutMs = o.optInt("timeoutMs", 800).coerceIn(120, 3000),
                codes = codes,
                action = KeyAction.fromJson(o.optJSONObject("action")),
            )
        }
    }
}

data class KeyMapConfig(
    val enabled: Boolean = false,
    val vibrate: Boolean = true,
    val vibrateMs: Int = 50,
    val vibrateAmp: Int = 255,
    val longMs: Int = 400,
    val doubleMs: Int = 280,
    val comboMs: Int = 800,
    val keys: List<KeyBinding> = emptyList(),
    val combos: List<KeyCombo> = emptyList(),
) {
    fun key(id: String): KeyBinding? = keys.firstOrNull { it.id == id }
    fun combo(id: String): KeyCombo? = combos.firstOrNull { it.id == id }
    fun byCode(code: Int): KeyBinding? = keys.firstOrNull { it.code == code }

    fun withBuiltins(): KeyMapConfig {
        val prev = keys.associateBy { it.id }
        val byCode = keys.associateBy { it.code }
        val merged = BuiltinKeys.defs().map { def ->
            val old = prev[def.id] ?: byCode[def.code]
            val next = if (old == null) def else def.copy(
                enabled = old.enabled,
                click = old.click,
                doubleClick = old.doubleClick,
                longPress = old.longPress,
                tripleClick = old.tripleClick,
            )
            when (next.id) {
                BuiltinKeys.POWER -> next.copy(click = KeyAction.default(), longPress = KeyAction.default())
                BuiltinKeys.VOL_UP, BuiltinKeys.VOL_DOWN -> next.copy(click = KeyAction.default())
                BuiltinKeys.BACK, BuiltinKeys.HOME, BuiltinKeys.RECENTS, BuiltinKeys.FINGERPRINT -> next.copy(longPress = KeyAction.default())
                else -> next
            }
        }
        val allow = merged.map { it.code }.toSet()
        return copy(
            keys = merged,
            combos = combos.map { item -> item.copy(codes = item.codes.filter { it in allow }) },
        )
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("vibrate", vibrate)
        o.put("vibrateMs", vibrateMs)
        o.put("vibrateAmp", vibrateAmp)
        o.put("longMs", longMs)
        o.put("doubleMs", doubleMs)
        o.put("comboMs", comboMs)
        val k = JSONArray()
        keys.forEach { k.put(it.toJson()) }
        o.put("keys", k)
        val c = JSONArray()
        combos.forEach { c.put(it.toJson()) }
        o.put("combos", c)
        return o.toString(2)
    }

    companion object {
        fun default(): KeyMapConfig = KeyMapConfig(keys = BuiltinKeys.defs())

        fun fromJson(text: String): KeyMapConfig {
            val o = JSONObject(text)
            val keys = mutableListOf<KeyBinding>()
            val ka = o.optJSONArray("keys") ?: JSONArray()
            for (i in 0 until ka.length()) {
                val item = ka.optJSONObject(i) ?: continue
                keys += KeyBinding.fromJson(item)
            }
            val combos = mutableListOf<KeyCombo>()
            val ca = o.optJSONArray("combos") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val item = ca.optJSONObject(i) ?: continue
                combos += KeyCombo.fromJson(item)
            }
            return KeyMapConfig(
                enabled = o.optBoolean("enabled", false),
                vibrate = o.optBoolean("vibrate", true),
                vibrateMs = o.optInt("vibrateMs", 50).coerceIn(10, 200),
                vibrateAmp = o.optInt("vibrateAmp", 255).coerceIn(1, 255),
                longMs = o.optInt("longMs", 400).coerceIn(180, 1500),
                doubleMs = o.optInt("doubleMs", 280).coerceIn(120, 800),
                comboMs = o.optInt("comboMs", 800).coerceIn(120, 3000),
                keys = keys,
                combos = combos,
            ).withBuiltins()
        }
    }
}

object BuiltinKeys {
    const val POWER = "power"
    const val VOL_UP = "vol_up"
    const val VOL_DOWN = "vol_down"
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val SMART = "smart"
    const val FINGERPRINT = "fingerprint"

    fun smartCode(): Int {
        val c = KeyEvent.keyCodeFromString("KEYCODE_SMART")
        return if (c > 0) c else 310
    }

    fun fingerprintCode(): Int {
        val c = KeyEvent.keyCodeFromString("KEYCODE_FINGERPRINT")
        return if (c > 0) c else 285
    }

    fun defs(): List<KeyBinding> {
        val d = KeyAction.default()
        return listOf(
            KeyBinding(POWER, KeyEvent.KEYCODE_POWER, "电源", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(VOL_UP, KeyEvent.KEYCODE_VOLUME_UP, "音量+", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(VOL_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, "音量-", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(BACK, KeyEvent.KEYCODE_BACK, "虚拟返回", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(HOME, KeyEvent.KEYCODE_HOME, "虚拟 Home", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(RECENTS, KeyEvent.KEYCODE_APP_SWITCH, "虚拟最近任务", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(SMART, smartCode(), "快捷键", click = d, doubleClick = d, longPress = d, tripleClick = d),
            KeyBinding(FINGERPRINT, fingerprintCode(), "指纹", click = d, doubleClick = d, longPress = d, tripleClick = d),
        )
    }
}

object KeyNames {
    fun of(code: Int): String {
        return when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> "音量+"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "音量-"
            KeyEvent.KEYCODE_POWER -> "电源"
            KeyEvent.KEYCODE_HOME -> "虚拟 Home"
            KeyEvent.KEYCODE_BACK -> "虚拟返回"
            KeyEvent.KEYCODE_APP_SWITCH -> "虚拟最近任务"
            KeyEvent.KEYCODE_MENU -> "菜单"
            KeyEvent.KEYCODE_CAMERA -> "相机"
            KeyEvent.KEYCODE_FOCUS -> "对焦"
            KeyEvent.KEYCODE_HEADSETHOOK -> "耳机键"
            KeyEvent.KEYCODE_MUTE -> "静音键"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放暂停"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "下一曲"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一曲"
            KeyEvent.KEYCODE_ASSIST -> "助手键"
            KeyEvent.KEYCODE_VOICE_ASSIST -> "语音助手键"
            KeyEvent.KEYCODE_BRIGHTNESS_UP -> "亮度+"
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN -> "亮度-"
            KeyEvent.KEYCODE_SYSRQ -> "截屏键"
            KeyEvent.KEYCODE_SEARCH -> "搜索键"
            else -> when (code) {
                BuiltinKeys.smartCode() -> "快捷键"
                BuiltinKeys.fingerprintCode() -> "指纹"
                else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
            }
        }
    }
}

object KeyActionCatalog {
    data class Group(val title: String, val types: List<String>)

    val groups = listOf(
        Group("基础", listOf(KeyAction.TYPE_NONE, KeyAction.TYPE_DEFAULT, KeyAction.TYPE_BACK, KeyAction.TYPE_HOME, KeyAction.TYPE_RECENTS, KeyAction.TYPE_NOTIFICATIONS, KeyAction.TYPE_QUICK_SETTINGS, KeyAction.TYPE_SCREENSHOT, KeyAction.TYPE_KILL_TOP, KeyAction.TYPE_APP_NEXT, KeyAction.TYPE_APP_PREV, KeyAction.TYPE_VOICE, KeyAction.TYPE_ROTATE_REVERSE)),
        Group("开关", listOf(KeyAction.TYPE_WIFI, KeyAction.TYPE_DATA, KeyAction.TYPE_BT, KeyAction.TYPE_LOCATION, KeyAction.TYPE_FLASHLIGHT, KeyAction.TYPE_MUTE, KeyAction.TYPE_WAKELOCK)),
        Group("媒体", listOf(KeyAction.TYPE_PLAY, KeyAction.TYPE_NEXT, KeyAction.TYPE_PREV, KeyAction.TYPE_RECORD)),
        Group("编辑", listOf(KeyAction.TYPE_COPY, KeyAction.TYPE_PASTE, KeyAction.TYPE_CUT, KeyAction.TYPE_INPUT)),
        Group("启动", listOf(KeyAction.TYPE_APP, KeyAction.TYPE_APP_FREEFORM, KeyAction.TYPE_APP_TNT, KeyAction.TYPE_ACTIVITY, KeyAction.TYPE_SHORTCUT, KeyAction.TYPE_SHELL)),
        Group("小窗", listOf(KeyAction.TYPE_FREEFORM_CURRENT, KeyAction.TYPE_FREEFORM_SIDEBAR, KeyAction.TYPE_FREEFORM_CLOSE, KeyAction.TYPE_FREEFORM_FULLSCREEN)),
    )

    fun title(type: String): String = when (type) {
        KeyAction.TYPE_NONE -> "无"
        KeyAction.TYPE_DEFAULT -> "默认"
        KeyAction.TYPE_BACK -> "返回"
        KeyAction.TYPE_HOME -> "主页"
        KeyAction.TYPE_RECENTS -> "最近任务"
        KeyAction.TYPE_NOTIFICATIONS -> "通知栏"
        KeyAction.TYPE_QUICK_SETTINGS -> "快捷设置"
        KeyAction.TYPE_SHELL -> "Shell 指令"
        KeyAction.TYPE_SHORTCUT -> "快捷方式"
        KeyAction.TYPE_ACTIVITY -> "活动"
        KeyAction.TYPE_APP -> "应用"
        KeyAction.TYPE_APP_FREEFORM -> "小窗应用"
        KeyAction.TYPE_APP_TNT -> "TNT 应用"
        KeyAction.TYPE_VOICE -> "唤醒语音助手"
        KeyAction.TYPE_COPY -> "复制"
        KeyAction.TYPE_PASTE -> "粘贴"
        KeyAction.TYPE_CUT -> "剪切"
        KeyAction.TYPE_INPUT -> "输入文字"
        KeyAction.TYPE_RECORD -> "一键录音"
        KeyAction.TYPE_WIFI -> "Wi-Fi"
        KeyAction.TYPE_DATA -> "移动数据"
        KeyAction.TYPE_BT -> "蓝牙"
        KeyAction.TYPE_LOCATION -> "位置"
        KeyAction.TYPE_LOCK -> "锁屏"
        KeyAction.TYPE_SCREENSHOT -> "截屏"
        KeyAction.TYPE_FLASHLIGHT -> "手电筒"
        KeyAction.TYPE_MUTE -> "静音"
        KeyAction.TYPE_WAKE -> "亮屏"
        KeyAction.TYPE_WAKELOCK -> "锁亮屏"
        KeyAction.TYPE_PLAY -> "播放暂停"
        KeyAction.TYPE_NEXT -> "下一曲"
        KeyAction.TYPE_PREV -> "上一曲"
        KeyAction.TYPE_KILL_TOP -> "结束前台应用"
        KeyAction.TYPE_APP_NEXT -> "切换下一个软件"
        KeyAction.TYPE_APP_PREV -> "切换上一个软件"
        KeyAction.TYPE_FREEFORM_CURRENT -> "小窗当前的软件"
        KeyAction.TYPE_FREEFORM_SIDEBAR -> "打开侧边栏"
        KeyAction.TYPE_FREEFORM_CLOSE -> "关闭小窗"
        KeyAction.TYPE_FREEFORM_FULLSCREEN -> "小窗全屏"
        KeyAction.TYPE_ROTATE_REVERSE -> "屏幕反向"
        else -> type
    }
}

fun modeTitle(mode: String): String = when (mode) {
    KeyAction.MODE_ON -> "开"
    KeyAction.MODE_OFF -> "关"
    else -> "切换"
}

