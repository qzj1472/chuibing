package id.tntwindow.editor.xposed

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import id.tntwindow.editor.domain.KeyAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object VoiceAssist {
    private val noteFiles = listOf(
        File("/data/system/tnt_voice_notes.json"),
        File("/data/local/tmp/tnt_voice_notes.json"),
    )

    private val weatherAsk = setOf(
        "天气", "今天天气", "天气怎么样", "天气如何", "现在天气", "外面热不热",
        "下雨了吗", "今天下雨吗", "气温", "多少度", "几度", "天气预报", "今天热不热",
        "今天下雨不下雨", "外面天气", "查看天气",
    )
    private val batteryAsk = setOf(
        "电量", "还有多少电", "电池还有多少", "还剩多少电", "电量多少",
        "电池电量", "还剩多少电量", "快没电了吗", "还有电吗", "电池怎么样",
        "还有百分之多少电", "电还剩多少",
    )
    private val wifiAsk = setOf("wifi开了吗", "无线开了吗", "wifi开没开", "wifi状态", "无线网开了吗")
    private val wifiNameAsk = setOf(
        "当前wifi名是什么", "当前wifi名", "wifi名是什么", "wifi叫什么", "wifi名称", "wifi名字", "wifi是什么", "现在wifi名", "现在的wifi名",
        "连的哪个wifi", "现在连的wifi", "现在的wifi", "无线网名字", "当前无线网", "wifi名",
        "现在连着什么wifi", "连的什么wifi", "wifi是什么",
    )
    private val btAsk = setOf("蓝牙开了吗", "蓝牙开没开", "蓝牙状态")
    private val dataAsk = setOf("流量开了吗", "移动数据开了吗", "数据开了吗", "流量开没开")
    private val notesAsk = setOf("我记了什么", "看看备忘", "最近记的", "记了啥", "备忘内容", "看看记下的", "备忘录", "我的备忘")
    private val netAsk = setOf("现在什么网", "用的什么网", "当前网络", "现在是wifi吗")
    private val phoneAsk = setOf(
        "手机情况", "当前情况", "现在什么情况", "手机状态", "当前状态",
        "系统状态", "现在怎么样", "手机怎么样", "当前手机情况",
    )

    private val switches = listOf(
        listOf("无线网络", "无线网", "wifi", "wi-fi", "无线") to KeyAction.TYPE_WIFI,
        listOf("移动数据", "蜂窝数据", "流量", "数据网络", "数据") to KeyAction.TYPE_DATA,
        listOf("蓝牙") to KeyAction.TYPE_BT,
        listOf("手电筒", "手电", "闪光灯") to KeyAction.TYPE_FLASHLIGHT,
        listOf("定位服务", "位置服务", "定位", "位置", "gps") to KeyAction.TYPE_LOCATION,
        listOf("静音模式", "静音") to KeyAction.TYPE_MUTE,
        listOf("截个屏", "截张图", "截个图", "截屏", "截图") to KeyAction.TYPE_SCREENSHOT,
        listOf("退出回主页", "退回主页", "回到桌面", "回桌面", "回主页", "回到主页", "回到主屏幕", "回首页", "去主页", "打开主页", "主屏幕", "主页") to KeyAction.TYPE_HOME,
        listOf("返回上一页", "返回", "退回", "后退") to KeyAction.TYPE_BACK,
        listOf("最近任务", "多任务", "后台任务", "后台") to KeyAction.TYPE_RECENTS,
        listOf("通知栏", "通知中心", "下拉通知") to KeyAction.TYPE_NOTIFICATIONS,
        listOf("快捷设置", "控制中心", "下拉设置") to KeyAction.TYPE_QUICK_SETTINGS,
        listOf("下一首歌", "下一首", "下一曲") to KeyAction.TYPE_NEXT,
        listOf("上一首歌", "上一首", "上一曲") to KeyAction.TYPE_PREV,
        listOf("播放暂停", "暂停播放", "暂停", "播放") to KeyAction.TYPE_PLAY,
        listOf(
            "结束当前应用", "关掉当前应用", "关闭当前应用", "关掉当前软件", "关闭当前软件", "结束当前软件",
            "退出当前应用", "退出当前软件", "退出这个软件", "退出这个应用", "关闭这个应用", "关掉这个应用",
            "结束这个软件", "关掉这个软件", "关闭这个软件", "结束这个应用", "关掉这个", "关闭前台", "结束前台",
            "退出应用", "退出软件", "退出当前", "关掉当前", "关闭当前", "结束当前", "结束", "退出",
        ) to KeyAction.TYPE_KILL_TOP,
        listOf("锁住屏幕", "锁上屏幕", "锁屏") to KeyAction.TYPE_LOCK,
        listOf("点亮屏幕", "唤醒屏幕", "亮屏") to KeyAction.TYPE_WAKE,
        listOf("保持亮屏", "常亮", "锁亮屏") to KeyAction.TYPE_WAKELOCK,
        listOf("打开侧边栏", "侧边栏") to KeyAction.TYPE_FREEFORM_SIDEBAR,
        listOf(
            "小窗当前的软件", "当前应用小窗", "把当前变成小窗", "当前转小窗", "当前小窗", "小窗当前",
            "将当前软件小窗", "小窗当前软件", "小窗当前应用", "当前应用挂小窗", "当前软件挂小窗",
            "把当前软件小窗", "将当前应用小窗", "当前软件小窗", "把当前应用变成小窗", "将当前变成小窗",
            "挂当前小窗", "当前挂小窗", "把这个软件小窗", "小窗这个软件", "打开当前小窗",
            "打开小窗", "小窗打开", "开小窗", "挂小窗", "打开悬浮窗", "打开米窗",
        ) to KeyAction.TYPE_FREEFORM_CURRENT,
        listOf(
            "关掉小窗", "关闭小窗", "收起小窗", "关上小窗", "关小窗", "退出小窗",
            "关掉悬浮窗", "关闭悬浮窗", "收起悬浮窗", "关掉米窗", "关闭米窗", "把小窗关掉", "把小窗关闭",
        ) to KeyAction.TYPE_FREEFORM_CLOSE,
        listOf("小窗最大化", "小窗全屏", "全屏小窗") to KeyAction.TYPE_FREEFORM_FULLSCREEN,
        listOf("切换下一个软件", "下一个软件", "下一个应用", "切到下一个", "切换下一个") to KeyAction.TYPE_APP_NEXT,
        listOf("切换上一个软件", "上一个软件", "上一个应用", "切到上一个", "切换上一个") to KeyAction.TYPE_APP_PREV,
        listOf("屏幕倒转", "屏幕反向", "反向屏幕", "倒过来") to KeyAction.TYPE_ROTATE_REVERSE,
        listOf("一键录音", "开始录音", "录音") to KeyAction.TYPE_RECORD,
        listOf("复制", "拷贝") to KeyAction.TYPE_COPY,
        listOf("粘贴", "黏贴") to KeyAction.TYPE_PASTE,
        listOf("剪切", "剪贴") to KeyAction.TYPE_CUT,
    )

    private val stateful = setOf(
        KeyAction.TYPE_WIFI, KeyAction.TYPE_DATA, KeyAction.TYPE_BT,
        KeyAction.TYPE_FLASHLIGHT, KeyAction.TYPE_LOCATION, KeyAction.TYPE_MUTE,
        KeyAction.TYPE_WAKELOCK,
    )

    fun askKey(cands: List<String>): String? {
        val extra = ArrayList<String>()
        for (c in cands) {
            extra.add(c)
            for (p in listOf("查一下", "问一下", "看看", "帮我看", "看一下")) {
                if (c.startsWith(p) && c.length > p.length) extra.add(c.substring(p.length))
            }
        }
        for (c in extra) {
            val x = VoiceRuntime.flex(c)
            if (hitAsk(c, weatherAsk) || VoiceRuntime.hasAny(x, listOf("天气", "下雨", "气温", "几度", "多少度", "热不热", "冷不冷"))) return "weather"
            if (hitAsk(c, batteryAsk) || VoiceRuntime.hasAny(x, listOf("电量", "电池")) || x.contains("多少电") || x.contains("还有电") || x.contains("剩多少电")) return "battery"
            if (x.contains("wifi") && VoiceRuntime.hasAny(x, listOf("名", "叫", "哪个", "什么", "名称"))) return "wifiname"
            if ((x.contains("无线网") || (x.contains("无线") && x.contains("网"))) && VoiceRuntime.hasAny(x, listOf("名", "叫"))) return "wifiname"
            if (hitAsk(c, wifiNameAsk)) return "wifiname"
            if (hitAsk(c, wifiAsk) || ((x.contains("wifi") || x.contains("无线")) && VoiceRuntime.hasAny(x, listOf("开了", "开没开", "状态")) && !x.contains("名"))) return "wifi"
            if (hitAsk(c, btAsk) || (x.contains("蓝牙") && VoiceRuntime.hasAny(x, listOf("开了", "开没开", "状态")))) return "bt"
            if (hitAsk(c, dataAsk) || ((x.contains("流量") || x.contains("数据")) && VoiceRuntime.hasAny(x, listOf("开了", "开没开", "状态")))) return "data"
            if (hitAsk(c, notesAsk) || VoiceRuntime.hasAny(x, listOf("备忘")) || x.contains("记了")) return "notes"
            if (hitAsk(c, netAsk) || x.contains("什么网") || x.contains("当前网络") || x.contains("现在是wifi")) return "net"
            if (hitAsk(c, phoneAsk) || VoiceRuntime.hasAny(x, listOf("手机情况", "当前情况", "手机状态", "当前状态", "系统状态"))) return "phone"
        }
        return null
    }

    fun askReply(key: String, ctx: Context?): String {
        return when (key) {
            "battery" -> batteryText(ctx)
            "wifi" -> if (wifiOn(ctx)) "Wi-Fi已打开" else "Wi-Fi已关闭"
            "wifiname" -> wifiNameText(ctx)
            "bt" -> if (btOn()) "蓝牙已打开" else "蓝牙已关闭"
            "data" -> if (dataOn(ctx)) "移动数据已打开" else "移动数据已关闭"
            "net" -> netText(ctx)
            "notes" -> notesReply()
            "phone" -> phoneText(ctx)
            else -> ""
        }
    }

    fun noteText(rawN: String): String? {
        val prefs = listOf("请帮我记一下", "帮我记一下", "请记一下", "帮我记", "记下来", "记一下", "记下", "备忘一下", "记住", "备忘")
        for (p in prefs) {
            val np = VoiceRuntime.normalize(p)
            if (rawN.startsWith(np) && rawN.length > np.length) {
                val t = rawN.substring(np.length)
                if (t.length >= 1 && t !in setOf("事本", "笔记", "事")) return t
            }
        }
        return null
    }

    fun typedText(rawN: String): String? {
        val prefs = listOf("请帮我输入", "帮我输入", "输入文字", "打字输入", "输入")
        for (p in prefs) {
            val np = VoiceRuntime.normalize(p)
            if (rawN.startsWith(np) && rawN.length > np.length) {
                val t = rawN.substring(np.length)
                if (t.length >= 1) return t
            }
        }
        return null
    }

    fun control(rawN: String): Pair<String, String>? {
        var n = rawN
        if (n.isEmpty()) return null
        for (p in listOf("请帮我", "能不能", "可不可以", "帮我", "给我", "我想", "我要", "请")) {
            if (n.startsWith(p) && n.length > p.length) {
                n = n.substring(p.length)
                break
            }
        }
        if (closeWindow(n)) return KeyAction.TYPE_FREEFORM_CLOSE to KeyAction.MODE_TOGGLE
        if (currentWindow(n)) return KeyAction.TYPE_FREEFORM_CURRENT to KeyAction.MODE_TOGGLE
        if (goHome(n)) return KeyAction.TYPE_HOME to KeyAction.MODE_TOGGLE
        if (killTop(n)) return KeyAction.TYPE_KILL_TOP to KeyAction.MODE_TOGGLE
        var mode: String? = null
        var rest = n
        when {
            n.startsWith("把") && n.endsWith("打开") && n.length > 3 -> {
                mode = KeyAction.MODE_ON
                rest = n.substring(1, n.length - 2)
            }
            n.startsWith("把") && (n.endsWith("关掉") || n.endsWith("关闭") || n.endsWith("关上")) && n.length > 3 -> {
                mode = KeyAction.MODE_OFF
                rest = n.substring(1, n.length - 2)
            }
            n.startsWith("关闭一下") || n.startsWith("关掉") || n.startsWith("关上") || n.startsWith("关闭") || n.startsWith("关了") -> {
                mode = KeyAction.MODE_OFF
                rest = peel(n, listOf("关闭一下", "关掉", "关上", "关闭", "关了"))
            }
            n.startsWith("切换") || n.startsWith("开关") -> {
                mode = KeyAction.MODE_TOGGLE
                rest = peel(n, listOf("切换一下", "开关一下", "切换", "开关"))
            }
            n.startsWith("打开一下") || n.startsWith("开启") || n.startsWith("打开") -> {
                mode = KeyAction.MODE_ON
                rest = peel(n, listOf("打开一下", "开启", "打开"))
            }
            n.endsWith("打开") && n.length > 2 -> {
                mode = KeyAction.MODE_ON
                rest = n.substring(0, n.length - 2)
            }
            n.endsWith("关掉") && n.length > 2 -> {
                mode = KeyAction.MODE_OFF
                rest = n.substring(0, n.length - 2)
            }
            n.endsWith("关闭") && n.length > 2 -> {
                mode = KeyAction.MODE_OFF
                rest = n.substring(0, n.length - 2)
            }
        }
        val type = switchType(rest) ?: switchType(n)
        if (type == null) return null
        if (mode == null && type in stateful) return null
        val use = mode ?: KeyAction.MODE_TOGGLE
        return type to use
    }

    private fun hitAsk(raw: String, phrases: Set<String>): Boolean {
        return phrases.any { VoiceRuntime.looseHit(raw, it) }
    }

    private fun closeWindow(n: String): Boolean {
        if (n.contains("语音") || n.contains("助手")) return false
        val win = n.contains("小窗") || n.contains("悬浮窗") || n.contains("米窗")
        if (!win) return false
        return VoiceRuntime.hasAny(n, listOf("关闭", "关掉", "关上", "收起", "退出", "关了", "收了", "关下", "关小窗"))
    }

    private fun currentWindow(n: String): Boolean {
        if (closeWindow(n) || VoiceRuntime.hasAny(n, listOf("关闭", "关掉", "关上", "收起", "退出", "关了"))) return false
        val win = n.contains("小窗") || n.contains("悬浮窗") || n.contains("米窗")
        if (!win) return false
        if (n.contains("当前") || n.contains("这个")) return true
        var core = n
        for (w in listOf("悬浮窗", "米窗", "小窗", "打开", "开启", "开", "挂", "变成", "变", "转成", "转", "弄成", "弄", "改成", "改", "成", "用", "以", "在", "形式", "方式", "来", "个")) {
            core = core.replace(w, "")
        }
        core = VoiceRuntime.flex(core)
        return core.isEmpty()
    }

    private fun goHome(n: String): Boolean {
        val home = n.contains("主页") || n.contains("桌面") || n.contains("主屏幕") || n.contains("首页")
        if (!home) return false
        return VoiceRuntime.hasAny(n, listOf("回", "到", "去", "退", "出", "退出"))
    }

    private fun killTop(n: String): Boolean {
        if (n.contains("小窗") || n.contains("悬浮窗") || n.contains("米窗")) return false
        if (n.contains("语音") || n.contains("助手")) return false
        if (VoiceRuntime.hasAny(n, listOf("wifi", "蓝牙", "主页", "桌面", "通知", "手电筒", "侧边栏"))) return false
        val x = VoiceRuntime.flex(n)
        if (x in setOf("退出", "结束", "退出应用", "退出软件", "退出当前", "关掉当前", "关闭当前", "结束当前", "关掉它", "关上它", "关了它")) return true
        val act = VoiceRuntime.hasAny(n, listOf("关闭", "关掉", "关上", "结束", "退出", "关了"))
        val target = VoiceRuntime.hasAny(n, listOf("当前", "这个", "那个", "它", "软件", "应用", "程序", "前台"))
        return act && target
    }

    private fun peel(n: String, prefixes: List<String>): String {
        for (p in prefixes) {
            if (n.startsWith(p) && n.length > p.length) return n.substring(p.length)
        }
        return n
    }

    fun controlAction(type: String, mode: String): KeyAction {
        return KeyAction(type = type, mode = mode)
    }

    fun controlPreview(type: String, mode: String): String {
        val name = title(type)
        return when (mode) {
            KeyAction.MODE_ON -> "打开" + name
            KeyAction.MODE_OFF -> "关闭" + name
            else -> if (type in stateful) "切换" + name else name
        }
    }

    fun controlSay(type: String, mode: String): String {
        val name = title(type)
        return when {
            type == KeyAction.TYPE_INPUT -> "已输入"
            type !in stateful -> "已" + name
            mode == KeyAction.MODE_ON -> "已打开" + name
            mode == KeyAction.MODE_OFF -> "已关闭" + name
            else -> "已切换" + name
        }
    }

    fun addNote(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val arr = loadNotes()
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("text", t)
        arr.put(o)
        while (arr.length() > 30) arr.remove(0)
        val body = arr.toString()
        for (f in noteFiles) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(body, Charsets.UTF_8)
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun notesReply(): String {
        val arr = loadNotes()
        if (arr.length() <= 0) return "还没有备忘"
        val n = minOf(3, arr.length())
        val parts = ArrayList<String>()
        for (i in arr.length() - n until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) parts.add(t)
        }
        return if (parts.isEmpty()) "还没有备忘" else "最近记下了" + parts.joinToString("，")
    }

    fun weatherText(): String {
        val urls = listOf(
            "https://wttr.in/?format=%C+%t&lang=zh",
            "https://wttr.in/China?format=%C+%t&lang=zh",
        )
        for (u in urls) {
            val t = httpGet(u) ?: continue
            val clean = t.replace("+", "").replace("\n", " ").trim()
            if (clean.length in 2..80 && !clean.contains("<") && !clean.contains("{")) {
                return "今天天气" + clean
            }
        }
        return ""
    }

    fun weatherApps(): List<String> {
        return listOf(
            "com.smartisanos.weather",
            "com.android.weather",
            "com.miui.weather2",
            "com.huawei.android.totemweather",
            "com.coloros.weather2",
            "com.sec.android.daemonapp",
        )
    }

    private fun switchType(raw: String): String? {
        val q = VoiceRuntime.flex(raw)
        if (q.isEmpty()) return null
        var best: String? = null
        var bestLen = 0
        for ((keys, type) in switches) {
            for (k in keys) {
                val kk = VoiceRuntime.flex(k)
                if (kk.isEmpty()) continue
                val hit = q == kk || (kk.length >= 2 && q.contains(kk) && VoiceRuntime.flex(q.replaceFirst(kk, "")).isEmpty())
                if (hit && kk.length >= bestLen) {
                    best = type
                    bestLen = kk.length
                }
            }
        }
        return best
    }

    private fun title(type: String): String {
        return when (type) {
            KeyAction.TYPE_WIFI -> "Wi-Fi"
            KeyAction.TYPE_DATA -> "移动数据"
            KeyAction.TYPE_BT -> "蓝牙"
            KeyAction.TYPE_FLASHLIGHT -> "手电筒"
            KeyAction.TYPE_LOCATION -> "定位"
            KeyAction.TYPE_MUTE -> "静音"
            KeyAction.TYPE_SCREENSHOT -> "截屏"
            KeyAction.TYPE_HOME -> "回到主页"
            KeyAction.TYPE_BACK -> "返回"
            KeyAction.TYPE_RECENTS -> "打开最近任务"
            KeyAction.TYPE_NOTIFICATIONS -> "打开通知栏"
            KeyAction.TYPE_QUICK_SETTINGS -> "打开快捷设置"
            KeyAction.TYPE_NEXT -> "下一曲"
            KeyAction.TYPE_PREV -> "上一曲"
            KeyAction.TYPE_PLAY -> "播放暂停"
            KeyAction.TYPE_KILL_TOP -> "结束前台应用"
            KeyAction.TYPE_LOCK -> "锁屏"
            KeyAction.TYPE_WAKE -> "亮屏"
            KeyAction.TYPE_WAKELOCK -> "锁亮屏"
            KeyAction.TYPE_FREEFORM_SIDEBAR -> "打开侧边栏"
            KeyAction.TYPE_FREEFORM_CURRENT -> "小窗当前应用"
            KeyAction.TYPE_FREEFORM_CLOSE -> "关闭小窗"
            KeyAction.TYPE_FREEFORM_FULLSCREEN -> "小窗全屏"
            KeyAction.TYPE_APP_NEXT -> "切换下一个软件"
            KeyAction.TYPE_APP_PREV -> "切换上一个软件"
            KeyAction.TYPE_ROTATE_REVERSE -> "屏幕反向"
            KeyAction.TYPE_RECORD -> "录音"
            KeyAction.TYPE_COPY -> "复制"
            KeyAction.TYPE_PASTE -> "粘贴"
            KeyAction.TYPE_CUT -> "剪切"
            KeyAction.TYPE_INPUT -> "输入"
            else -> "操作"
        }
    }

    private fun phoneText(ctx: Context?): String {
        val bat = batteryText(ctx)
        val net = netText(ctx)
        val wifi = if (wifiOn(ctx)) "Wi-Fi开" else "Wi-Fi关"
        val bt = if (btOn()) "蓝牙开" else "蓝牙关"
        val data = if (dataOn(ctx)) "数据开" else "数据关"
        return bat + "，" + net + "，" + wifi + "，" + data + "，" + bt
    }

    private fun batteryText(ctx: Context?): String {
        if (ctx == null) return "暂时查不到电量"
        return try {
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charge = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            "电量" + pct + "%" + if (charge) "，正在充电" else ""
        } catch (_: Throwable) {
            "暂时查不到电量"
        }
    }

    private fun wifiNameText(ctx: Context?): String {
        if (ctx == null) return "暂时查不到Wi-Fi名称"
        if (!wifiOn(ctx)) return "Wi-Fi已关闭"
        return try {
            val info = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            var ssid = info?.ssid?.trim().orEmpty()
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            when {
                ssid.isEmpty() || ssid == "<unknown ssid>" || ssid.equals("0x", true) -> "现在连着Wi-Fi，但还查不到名称"
                else -> "当前Wi-Fi是" + ssid
            }
        } catch (_: Throwable) {
            "暂时查不到Wi-Fi名称"
        }
    }

    private fun wifiOn(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Throwable) {
            false
        }
    }

    private fun btOn(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun dataOn(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    private fun netText(ctx: Context?): String {
        val wifi = wifiOn(ctx)
        val data = dataOn(ctx)
        return when {
            wifi -> "现在用的是Wi-Fi"
            data -> "现在用的是移动数据"
            else -> "现在没有网络"
        }
    }

    private fun loadNotes(): JSONArray {
        for (f in noteFiles) {
            try {
                if (!f.exists() || f.length() <= 0L) continue
                val t = f.readText(Charsets.UTF_8)
                if (t.isNotBlank()) return JSONArray(t)
            } catch (_: Throwable) {
            }
        }
        return JSONArray()
    }

    private fun httpGet(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 2500
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "curl/8.0")
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }
}