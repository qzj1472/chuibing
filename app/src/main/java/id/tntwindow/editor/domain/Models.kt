package id.tntwindow.editor.domain

data class WindowSpec(
    val windowMode: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val minWidth: Int? = null,
    val minHeight: Int? = null,
    val resizeMode: Int? = null,
) {
    fun fingerprint(): String {
        fun n(v: Int?) = v?.toString() ?: "-"
        return listOf(n(windowMode), n(width), n(height), n(minWidth), n(minHeight), n(resizeMode)).joinToString("/")
    }

    fun summary(): String {
        val size = if (width != null && height != null) "${width}×${height}" else "无默认尺寸"
        val min = if (minWidth != null && minHeight != null) "最小 ${minWidth}×${minHeight}" else "最小未设"
        return "${windowModeLabel(windowMode)} · $size · $min · ${resizeLabel(resizeMode)}"
    }

    fun scaled(factor: Float): WindowSpec {
        if (factor == 1f) return this
        fun s(v: Int?): Int? = v?.let { kotlin.math.max(1, kotlin.math.round(it * factor).toInt()) }
        return copy(width = s(width), height = s(height), minWidth = s(minWidth), minHeight = s(minHeight))
    }
}

fun windowModeLabel(mode: Int?): String = when (mode) {
    null, -1 -> "不指定"
    0 -> "竖屏"
    1 -> "横屏"
    2 -> "最大化"
    4 -> "全屏"
    5 -> "左半屏"
    6 -> "右半屏"
    7 -> "左上小窗"
    8 -> "右上小窗"
    9 -> "左下小窗"
    16 -> "右下小窗"
    17 -> "上半屏"
    18 -> "下半屏"
    19 -> "竖屏最大化"
    20 -> "竖屏密度缩放"
    else -> "模式 $mode"
}

fun resizeLabel(mode: Int?): String {
    if (mode == null) return "拉伸未设"
    if (mode == 0) return "不能拉伸"
    val stretch = when {
        mode and 1 != 0 -> "可拉伸"
        mode and 2 != 0 -> "仅纵向"
        mode and 8 != 0 -> "按比例"
        else -> null
    }
    val full = if (mode and 4 != 0) "可全屏" else null
    val parts = listOfNotNull(stretch, full)
    return if (parts.isEmpty()) "拉伸 $mode" else parts.joinToString(" + ")
}

fun stretchKind(mode: Int?): Int {
    val m = mode ?: 0
    return when {
        m and 1 != 0 -> 1
        m and 2 != 0 -> 2
        m and 8 != 0 -> 8
        else -> 0
    }
}

fun composeResize(stretch: Int, fullscreen: Boolean): Int {
    val bit = if (stretch == 1 || stretch == 2 || stretch == 8) stretch else 0
    val full = if (fullscreen) 4 else 0
    return bit or full
}

enum class CollectionType { SPEC, CUSTOM }

data class Membership(
    val packageName: String,
    val customSpec: WindowSpec? = null,
)

data class AppCollection(
    val id: String,
    val name: String,
    val type: CollectionType,
    val spec: WindowSpec? = null,
    val members: List<Membership> = emptyList(),
    val pinned: Boolean = false,
    val builtin: Boolean = false,
)

data class VideoSpecial(
    val packageName: String,
    val type: Int,
    val comment: String = "",
)

data class AppNode(
    val packageName: String,
    val spec: WindowSpec,
    val start: Int,
    val end: Int,
    val hasSpecialActivity: Boolean,
    val dirtyAttr: Boolean,
    val duplicateIndex: Int,
)

data class NestedIssue(
    val packageName: String,
    val reason: String,
    val start: Int,
    val end: Int,
    val snippet: String,
)

data class ParseReport(
    val apps: List<AppNode>,
    val videos: List<VideoSpecial>,
    val duplicatePackages: List<String>,
    val duplicateCounts: Map<String, Int>,
    val dirtyAttrPackages: List<String>,
    val nestedIssues: List<NestedIssue>,
    val lowercaseResizePackages: List<String>,
    val missingWindowMode: List<String>,
) {
    val nestedWarning: Boolean get() = nestedIssues.isNotEmpty()
    val lowercaseResize: Boolean get() = lowercaseResizePackages.isNotEmpty()
}

data class BackupItem(
    val kind: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    val size: Long,
    val hash: String = "",
)

data class Issue(
    val group: String,
    val title: String,
    val detail: String,
)

data class DeviceStatus(
    val rootOk: Boolean = false,
    val fileOk: Boolean = false,
    val filePath: String = Paths.SYSTEM,
    val originalBackupAt: Long? = null,
    val lastWriteAt: Long? = null,
    val lastWriteOk: Boolean? = null,
    val appCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val editingAllowed: Boolean = false,
    val pcMode: Int? = null,
)

data class DisplayInfo(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val density: Int = 0,
    val physicalWidthPx: Int = 0,
    val physicalHeightPx: Int = 0,
    val physicalDensity: Int = 0,
    val spoofedSize: Boolean = false,
    val spoofedDensity: Boolean = false,
) {
    val widthDp: Int get() = if (density <= 0) 0 else widthPx * 160 / density
    val heightDp: Int get() = if (density <= 0) 0 else heightPx * 160 / density
    val summary: String
        get() = if (widthPx == 0) "未知" else "${widthPx}×${heightPx} @${density}dpi  (${widthDp}×${heightDp} dp)"
}

object Paths {
    const val SYSTEM = "/data/system/revone_window_config.xml"
    const val SYSTEM_ETC = "/system/etc/revone_window_config.xml"
    const val DESKTOP = "com.smartisanos.desktop"
    const val DESKTOP_UI = "com.android.desktop.systemui"
    const val TNT_ANYWHERE = "com.smartisanos.tntanywhere"
    const val VIRTUAL_REMOTER = "com.smartisanos.virtualremoter"
    const val VOICE = "/data/system/tnt_voice_config.json"
    const val HOME_LOCK = "/data/system/tnt_home_lock.json"
    const val WEBVIEW = "/data/system/tnt_webview_config.json"
    const val COREPATCH = "/data/system/tnt_corepatch_config.json"
    const val KEYMAP = "/data/system/tnt_keymap_config.json"
    const val KEYMAP_CAPTURE = "/data/system/tnt_keymap_capture.json"
    const val GESTURE = "/data/system/tnt_gesture_config.json"
    const val GESTURE_HIGHLIGHT = "/data/local/tmp/tnt_gesture_highlight"
    const val GESTURE_HIGHLIGHT_SYS = "/data/system/tnt_gesture_highlight"
    const val FREEFORM = "/data/system/tnt_freeform_config.json"
    const val FREEFORM_REQUEST = "/data/system/tnt_freeform_request"
    const val FREEFORM_REQUEST_TMP = "/data/local/tmp/tnt_freeform_request"
    const val FREEFORM_HIGHLIGHT = "/data/local/tmp/tnt_freeform_highlight"
    const val FREEFORM_HIGHLIGHT_SYS = "/data/system/tnt_freeform_highlight"
    const val UNINSTALL = "/data/system/tnt_uninstall_config.json"
    const val INSTALL = "/data/system/tnt_install_config.json"
    const val ROTATION = "/data/system/tnt_rotation_config.json"
    const val BARTINT = "/data/system/tnt_bartint_config.json"
    const val WEBVIEW_SETTINGS = "android.settings.WEBVIEW_SETTINGS"
    const val WEBVIEW_ACTIVITY = "com.android.settings.WebViewImplementation"
    const val SETTINGS_PKG = "com.android.settings"
}

object Presets {
    val LAND_900 = WindowSpec(1, 900, 694, 360, 694, 5)
    val PORT_360 = WindowSpec(0, 360, 694, 360, 694, 1)
    val FULL = WindowSpec(4, null, null, null, null, 0)
    val LAND_1095 = WindowSpec(1, 1095, 694, 1095, 694, 4)
    val SMALL_480 = WindowSpec(1, 480, 450, 160, 480, 5)
    val SMALL_320_LAND = WindowSpec(1, 320, 480, 160, 480, 5)
    val SMALL_320_PORT = WindowSpec(0, 320, 480, 160, 480, 5)
    val DEFAULT_NEW = LAND_900
    const val CUSTOM_ID = "custom"
    const val SYSTEM_CUSTOM_ID = "system_custom"
    const val SCHEMA = 3
    const val BASELINE_WIDTH_DP = 1800
}

data class CategoryDef(
    val id: String,
    val name: String,
    val spec: WindowSpec,
)

object Categories {
    val VIDEO = CategoryDef("video", "影视", Presets.LAND_900)
    val MUSIC = CategoryDef("music", "音乐", Presets.SMALL_320_LAND)
    val BROWSER = CategoryDef("browser", "浏览器", Presets.LAND_1095)
    val READER = CategoryDef("reader", "阅读", Presets.PORT_360)
    val SOCIAL = CategoryDef("social", "社交", Presets.PORT_360)
    val TOOL = CategoryDef("tool", "工具", Presets.SMALL_480)
    val GAME = CategoryDef("game", "游戏", Presets.FULL)
    val SYSTEM = CategoryDef("system", "系统", Presets.LAND_900)
    val ALL = listOf(VIDEO, MUSIC, BROWSER, READER, SOCIAL, TOOL, GAME, SYSTEM)
    val IDS = ALL.map { it.id }.toSet() + Presets.CUSTOM_ID + Presets.SYSTEM_CUSTOM_ID
    val LEGACY_IDS = setOf("land900", "port360", "full", "land1095", "small480", "small320l", "small320p")
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val hasLauncher: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val category: Int = -1,
)

data class HomeLauncher(
    val packageName: String,
    val activity: String,
    val label: String,
    val official: Boolean,
) {
    val component: String get() = packageName + "/" + activity
}

object WindowModes {
    val ROW1 = listOf(-1 to "不指定", 0 to "竖屏", 1 to "横屏", 2 to "最大化", 4 to "全屏")
    val ROW2 = listOf(5 to "左半屏", 6 to "右半屏", 17 to "上半屏", 18 to "下半屏")
    val ROW3 = listOf(7 to "左上", 8 to "右上", 9 to "左下", 16 to "右下")
    val ROW4 = listOf(19 to "竖屏最大化", 20 to "竖屏密度")
    val ALL = ROW1 + ROW2 + ROW3 + ROW4
}


