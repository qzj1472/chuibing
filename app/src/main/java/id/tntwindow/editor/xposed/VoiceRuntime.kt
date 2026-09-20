package id.tntwindow.editor.xposed

import android.content.Context
import android.os.SystemClock
import id.tntwindow.editor.domain.VoiceAlias
import id.tntwindow.editor.domain.VoiceCatalog
import id.tntwindow.editor.domain.VoiceCommand
import id.tntwindow.editor.domain.VoiceConfig
import java.io.File
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

object VoiceRuntime {
    private val files = listOf(
        "/data/system/tnt_voice_config.json",
        "/data/local/tmp/tnt_voice_config.json",
    )
    private val lock = Any()
    private var cached: VoiceConfig = VoiceConfig(VoiceConfig.ENGINE_REPLACE, emptyList())
    private var cachedAt = 0L
    private var cachedMtime = -1L
    private var pending: Offer? = null
    private var lastSpeakText: String = ""
    private var lastSpokenText: String = ""
    private var lastDestSpokenText: String = ""
    private var lastDestSpokenAt = 0L
    @Volatile private var ingestFn: ((String) -> Boolean)? = null
    private const val HANDLE_STAMP = "/data/local/tmp/tnt_voice_last_handle"

    private val corrections = listOf(
        "打包" to "打开",
        "打开打开" to "打开",
        "请打开" to "打开",
        "帮我打开" to "打开",
        "打开胶囊" to "打开椒盐",
        "胶囊音乐" to "椒盐音乐",
        "胶盐" to "椒盐",
        "焦盐" to "椒盐",
        "交盐" to "椒盐",
        "叫盐" to "椒盐",
        "angmuaikesi" to "mx",
        "anmuaikesi" to "mx",
        "angmuai" to "mx",
        "mancha" to "mx",
        "曼查" to "mx",
        "曼克斯" to "mx",
        "麦克斯" to "mx",
        "爱姆艾克斯" to "mx",
        "杠两千" to "管理器",
        "刚两千" to "管理器",
        "港两千" to "管理器",
        "emt管理器" to "mt管理器",
        "手机管理器" to "mt管理器",
        "手机管理" to "mt管理器",
        "美团管理器" to "mt管理器",
        "emt管理" to "mt管理器",
        "叉" to "x",
        "锤扁" to "锤柄",
        "锤边" to "锤柄",
        "wfi" to "wifi",
        "wf1" to "wifi",
        "登成wifi" to "当前wifi",
        "登成wfi" to "当前wifi",
        "wifi面" to "wifi名",
        "wfi面" to "wifi名",
        "垂柄" to "锤柄",
    )

    private val weakWords = setOf(
        "音乐", "视频", "播放器", "浏览器", "应用", "软件", "播放", "相册", "相机", "设置", "文件", "笔记", "日历", "邮件",
    )

    private val nameSuffixes = listOf(
        "专业版", "正式版", "极速版", "精简版", "播放器", "浏览器", "音乐", "视频", "应用", "pro", "lite",
    )

    private val weekNames = arrayOf("日", "一", "二", "三", "四", "五", "六")
    private val timeAsk = setOf("几点了", "现在几点", "现在几点了", "几点", "什么时间", "现在时间", "报时", "现在的时间")
    private val dateAsk = setOf("今天几号", "几号", "今天日期", "日期", "几月几号", "今天几月几号")
    private val weekAsk = setOf("今天星期几", "星期几", "周几", "今天周几", "星期几了", "今天是星期几", "今天星期几了")
    private val whoAsk = setOf("你是谁", "你叫什么", "你叫什么名字", "你是什么")
    private val helpAsk = setOf("你能做什么", "你会什么", "有什么功能", "能干什么", "你可以做什么", "你会干什么", "有哪些功能")
    private val hiAsk = setOf("你好", "您好", "在吗")

    enum class Kind {
        LAUNCH, SEARCH, INSTALL, OPEN_WEB, CONFIRM_WEB, CONFIRM_INSTALL, CONFIRM_SEARCH, YES, CANCEL, ASK, CONTROL, NOTE, NONE
    }

    enum class Dest { TNT, PHONE, FREEFORM }

    data class Parsed(val kind: Kind, val query: String, val raw: String, val dest: Dest = Dest.TNT)

    data class Offer(
        val label: String,
        val query: String,
        val packages: List<String>,
        val web: String,
        val at: Long,
    )

    fun config(): VoiceConfig {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val file = files.map { File(it) }.firstOrNull { it.exists() && it.canRead() }
            val mtime = file?.lastModified() ?: 0L
            if (cachedAt != 0L && mtime == cachedMtime && now - cachedAt < 1000L) return cached
            val text = try {
                file?.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            cached = if (text.isNullOrBlank()) VoiceConfig(VoiceConfig.ENGINE_REPLACE, emptyList()) else try {
                VoiceConfig.fromJson(text)
            } catch (_: Throwable) {
                VoiceConfig(VoiceConfig.ENGINE_REPLACE, emptyList())
            }
            cachedAt = now
            cachedMtime = mtime
            return cached
        }
    }

    fun replaceEngine(): Boolean = config().engine == VoiceConfig.ENGINE_REPLACE

    fun speakEnabled(): Boolean = config().speak

    fun recognizerId(): String = config().recognizer.trim()

    fun ttsEngine(): String = config().ttsEngine.trim()

    fun lastSpeak(): String = synchronized(lock) { lastSpeakText }

    fun setLastSpeak(text: String) {
        synchronized(lock) { lastSpeakText = text }
    }

    fun lastSpoken(): String = synchronized(lock) { lastSpokenText }

    fun setLastSpoken(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            lastSpokenText = t
            if (destOf(t) != Dest.TNT) {
                lastDestSpokenText = t
                lastDestSpokenAt = SystemClock.uptimeMillis()
            }
        }
    }

    fun destHint(text: String): Dest {
        val live = destOf(text)
        if (live != Dest.TNT) return live
        val prev: String
        val at: Long
        synchronized(lock) {
            prev = lastDestSpokenText
            at = lastDestSpokenAt
        }
        if (prev.isBlank() || at == 0L) return Dest.TNT
        if (SystemClock.uptimeMillis() - at > 2000L) return Dest.TNT
        val hinted = destOf(prev)
        if (hinted == Dest.TNT) return Dest.TNT
        val q = queryOf(text)
        val pq = queryOf(prev)
        if (q.isEmpty() || pq.isEmpty()) return Dest.TNT
        if (pq.contains(q) || q.contains(pq)) return hinted
        val a = catalogOf(q)
        val b = catalogOf(pq)
        return if (a != null && b != null && a.label == b.label) hinted else Dest.TNT
    }

    fun withDest(parsed: Parsed, hint: Dest): Parsed {
        if (parsed.dest != Dest.TNT || hint == Dest.TNT) return parsed
        return parsed.copy(dest = hint)
    }

    fun markHandled(pkg: String, dest: Dest) {
        val now = SystemClock.uptimeMillis()
        try {
            File(HANDLE_STAMP).writeText(now.toString() + "|" + pkg + "|" + dest.name, Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    fun recentlyHandled(windowMs: Long = 4000L): Boolean {
        return handleStamp(windowMs) != null
    }

    fun lastHandlePkg(): String = handleStamp()?.second ?: ""

    fun lastHandleDest(): Dest {
        val name = handleStamp()?.third ?: return Dest.TNT
        return try {
            Dest.valueOf(name)
        } catch (_: Throwable) {
            Dest.TNT
        }
    }

    private fun handleStamp(windowMs: Long = 4000L): Triple<Long, String, String>? {
        return try {
            val f = File(HANDLE_STAMP)
            if (!f.exists()) return null
            val line = f.readText(Charsets.UTF_8).trim()
            val p = line.split("|")
            if (p.size < 2) return null
            val at = p[0].toLongOrNull() ?: return null
            if (SystemClock.uptimeMillis() - at > windowMs) return null
            Triple(at, p[1], p.getOrNull(2) ?: Dest.TNT.name)
        } catch (_: Throwable) {
            null
        }
    }

    fun setIngest(fn: (String) -> Boolean) {
        ingestFn = fn
    }

    fun ingest(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val fn = ingestFn ?: return false
        return try {
            fn(t)
        } catch (_: Throwable) {
            false
        }
    }

    fun takeCommit(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val f = File("/data/local/tmp/tnt_voice_last_commit")
            try {
                if (f.exists()) {
                    val line = f.readText(Charsets.UTF_8)
                    val sp = line.indexOf('|')
                    if (sp > 0) {
                        val at = line.substring(0, sp).toLongOrNull() ?: 0L
                        val prev = line.substring(sp + 1)
                        if (prev == t && now - at < 2500L) return false
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                f.writeText(now.toString() + "|" + t, Charsets.UTF_8)
            } catch (_: Throwable) {
            }
            return true
        }
    }

    fun clearCommit(text: String) {
        val t = text.trim()
        synchronized(lock) {
            val f = File("/data/local/tmp/tnt_voice_last_commit")
            try {
                if (!f.exists()) return
                val line = f.readText(Charsets.UTF_8)
                val sp = line.indexOf('|')
                if (sp > 0 && line.substring(sp + 1) == t) f.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun correct(spoken: String): String {
        var s = spoken
        for ((a, b) in corrections) s = s.replace(a, b)
        if (!s.contains("闪念") && s.contains("胶囊")) s = s.replace("胶囊", "椒盐")
        return s
    }

    private val talkFillers = listOf(
        "能不能够", "能不能", "可不可以", "可以不可以", "麻烦你", "麻烦",
        "一下下", "一下子", "一下", "给我", "帮我", "请", "先",
        "吗", "呢", "吧", "啊", "呀", "嘛", "哈", "哦", "喔", "么",
        "啦", "咯", "哟", "嗯", "唉",
    )

    fun normalize(raw: String): String {
        return raw.lowercase(Locale.CHINA)
            .replace(" ", "")
            .replace("　", "")
            .replace("-", "")
            .replace("_", "")
            .replace(".", "")
            .replace("·", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("？", "")
            .replace("?", "")
            .replace("！", "")
            .replace("!", "")
            .replace("、", "")
            .replace("：", "")
            .replace(":", "")
    }

    fun prepared(spoken: String): String = normalize(correct(normalize(spoken)))

    fun flex(raw: String): String {
        var t = normalize(raw)
        val fills = talkFillers.map { normalize(it) }.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (f in fills) {
                if (t.contains(f)) {
                    val n = t.replace(f, "")
                    if (n != t) {
                        t = n
                        changed = true
                    }
                }
            }
        }
        return t
    }

    fun hasAll(q: String, keys: List<String>): Boolean {
        if (q.isEmpty() || keys.isEmpty()) return false
        return keys.all { q.contains(it) }
    }

    fun hasAny(q: String, keys: List<String>): Boolean {
        if (q.isEmpty() || keys.isEmpty()) return false
        return keys.any { q.contains(it) }
    }

    fun looseHit(q: String, phrase: String): Boolean {
        val a = flex(q)
        val b = flex(phrase)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (b.length < 2) return false
        if (a.contains(b)) return true
        return b.length >= 2 && b.contains(a) && a.length >= 2
    }

    fun stripPrefix(raw: String): String {
        var q = raw
        val prefixes = listOf("请帮我", "帮我", "我想", "我要", "请", "打开一下", "打开", "启动", "运行", "打包", "开启", "进入", "用一下")
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                val np = normalize(p)
                if (np.isNotEmpty() && q.startsWith(np) && q.length > np.length) {
                    q = q.substring(np.length)
                    changed = true
                }
            }
        }
        for (s in listOf("一下", "应用", "软件", "吧", "啊", "呀", "呢", "嘛", "了", "啦", "咯")) {
            if (q.endsWith(s) && q.length > s.length) q = q.substring(0, q.length - s.length)
        }
        return q
    }

    fun queryOf(spoken: String): String = stripPrefix(splitDest(prepared(spoken)).second)

    fun destOf(spoken: String): Dest = splitDest(prepared(spoken)).first

    private fun splitDest(n: String): Pair<Dest, String> {
        if (n.isEmpty()) return Dest.TNT to n
        val marks = listOf(
            Dest.FREEFORM to listOf("以小窗的形式打开", "用小窗的形式打开", "以小窗形式打开", "用小窗形式打开", "以小窗的方式打开", "用小窗的方式打开", "以小窗方式打开", "用小窗方式打开", "小窗形式打开", "小窗方式打开", "以小窗的形式", "用小窗的形式", "以小窗形式", "用小窗形式", "以小窗的方式", "用小窗的方式", "以小窗打开", "已小窗打开", "一小窗打开", "在小窗打开", "用小窗打开", "小窗打开", "打开小窗", "以悬浮窗打开", "已悬浮窗打开", "在悬浮窗打开", "用悬浮窗打开", "悬浮窗打开", "打开悬浮窗", "以米窗打开", "已米窗打开", "在米窗打开", "用米窗打开", "米窗打开", "打开米窗", "以小窗", "已小窗", "一小窗", "在小窗", "用小窗", "在悬浮窗", "用悬浮窗", "在米窗", "用米窗", "挂小窗", "小窗"),
            Dest.PHONE to listOf("在手机端打开", "用手机端打开", "到手机端打开", "手机端打开", "在手机上打开", "用手机上打开", "到手机上打开", "在手机端", "用手机端", "到手机端", "手机端", "在手机上", "用手机上", "到手机上", "在手机打开", "用手机打开", "手机打开", "在本机打开", "用本机打开", "本机打开", "在全屏打开", "用全屏打开", "全屏打开", "在手机", "用手机", "在本机", "用本机", "在全屏", "用全屏"),
            Dest.TNT to listOf("在tnt端打开", "用tnt端打开", "到tnt端打开", "tnt端打开", "在tnt桌面打开", "在锤子桌面打开", "在大屏打开", "用大屏打开", "到大屏打开", "大屏打开", "在tnt端", "用tnt端", "到tnt端", "tnt端", "在tnt桌面", "在锤子桌面", "在大屏", "用大屏", "到大屏", "在tnt打开", "用tnt打开", "tnt打开", "打开tnt", "在电脑打开", "用电脑打开", "电脑打开", "在桌面打开", "用桌面打开", "在扩展屏打开", "用扩展屏打开", "在tnt", "用tnt", "在电脑", "用电脑", "在桌面", "用桌面", "在扩展屏", "用扩展屏"),
        )
        val suffixes = listOf(
            Dest.FREEFORM to listOf("的小窗", "挂小窗", "小窗", "的悬浮窗", "悬浮窗", "的米窗", "米窗"),
            Dest.PHONE to listOf("的手机端", "在手机端", "到手机端", "手机端", "的手机", "的本机", "的全屏", "在手机上", "到手机上", "手机上", "本机上"),
            Dest.TNT to listOf("的tnt端", "在tnt端", "到tnt端", "tnt端", "的大屏", "在大屏", "到大屏", "大屏上", "大屏", "的tnt", "tnt上", "的电脑", "电脑上", "的桌面", "的扩展屏", "扩展屏"),
        )
        val prefixMarks = marks.flatMap { (dest, list) -> list.map { dest to normalize(it) } }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.length }
        fun cutPrefix(s: String): Pair<Dest, String>? {
            for ((dest, nm) in prefixMarks) {
                if (s.startsWith(nm) && s.length > nm.length) return dest to s.substring(nm.length)
            }
            return null
        }
        cutPrefix(n)?.let { return it }
        for (p in listOf("请帮我", "帮我", "请", "给我", "我想", "我要")) {
            if (n.startsWith(p) && n.length > p.length) cutPrefix(n.substring(p.length))?.let { return it }
        }
        val suffixMarks = suffixes.flatMap { (dest, list) -> list.map { dest to normalize(it) } }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.length }
        for ((dest, nm) in suffixMarks) {
            if (n.endsWith(nm) && n.length > nm.length + 1) return dest to n.substring(0, n.length - nm.length)
        }
        for ((dest, nm) in prefixMarks) {
            if (nm.length < 4) continue
            val idx = n.indexOf(nm)
            if (idx > 0 && idx + nm.length < n.length) return dest to (n.substring(0, idx) + n.substring(idx + nm.length))
        }
        return Dest.TNT to n
    }

    fun aliasesOf(label: String): List<String> {
        val out = LinkedHashSet<String>()
        val raw = label.trim()
        if (raw.isNotEmpty()) out.add(raw)
        val n = normalize(raw)
        if (n.isNotEmpty()) out.add(n)
        var stem = n
        var changed = true
        while (changed) {
            changed = false
            for (s in nameSuffixes) {
                val ns = normalize(s)
                if (ns.isNotEmpty() && stem.endsWith(ns) && stem.length - ns.length >= 2) {
                    stem = stem.substring(0, stem.length - ns.length)
                    out.add(stem)
                    changed = true
                }
            }
        }
        return out.toList()
    }

    fun scoreName(name: String, query: String): Int {
        val nn = normalize(name)
        val q = normalize(query)
        if (nn.isEmpty() || q.isEmpty()) return 0
        if (nn == q) return 10000 + nn.length * 20
        if (nn.length < 2 || q.length < 2) return 0
        if (isWeakQuery(nn) && nn != q) return 0
        var best = 0
        if (q.startsWith(nn)) best = maxOf(best, 7000 + nn.length * 30)
        if (nn.startsWith(q)) best = maxOf(best, 8500 + q.length * 10)
        if (q.contains(nn)) best = maxOf(best, 3000 + nn.length * 40)
        if (nn.contains(q)) best = maxOf(best, 2000 + q.length * 20)
        val ln = latin(nn)
        val lq = latin(q)
        if (ln.length >= 2 && lq.length >= 2) {
            if (ln == lq) best = maxOf(best, 9200 + ln.length * 10)
            else if (lq.contains(ln) || ln.contains(lq)) best = maxOf(best, 6000 + minOf(ln.length, lq.length) * 20)
        }
        if (similarHan(nn, q)) best = maxOf(best, 7800)
        return best
    }

    private fun latin(s: String): String = s.lowercase().filter { it.isLetterOrDigit() && it.code < 128 }

    private fun similarHan(a: String, b: String): Boolean {
        if (a.length < 2 || b.length < 2) return false
        if (a.length == b.length) {
            var diff = 0
            for (i in a.indices) if (a[i] != b[i]) diff += 1
            return diff == 1
        }
        if (Math.abs(a.length - b.length) != 1) return false
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
        var i = 0
        var j = 0
        var skip = 0
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i += 1
                j += 1
            } else {
                skip += 1
                i += 1
                if (skip > 1) return false
            }
        }
        return skip + (longer.length - i) <= 1
    }

    fun isWeakQuery(query: String): Boolean = normalize(query) in weakWords

    fun isStrong(best: Int, second: Int, query: String, unique: Boolean): Boolean {
        if (best <= 0) return false
        if (isWeakQuery(query) && !unique) return false
        if (best >= 5000 && unique) return true
        if (best >= 5000 && best - second >= 1500) return true
        return unique && best >= 2500 && best - second >= 1500
    }

    fun extraKeys(packageName: String): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(VoiceCatalog.keysFor(packageName))
        for (a in config().aliases) {
            if (a.packageName == packageName) {
                out.add(a.spoken)
                out.add(a.label)
            }
        }
        return out.toList()
    }

    fun matchCustom(spoken: String, commands: List<VoiceCommand> = config().commands): VoiceCommand? {
        val q = queryOf(spoken)
        if (q.isEmpty()) return null
        val scored = ArrayList<Pair<VoiceCommand, Int>>()
        for (c in commands) {
            if (!c.enabled) continue
            val names = LinkedHashSet<String>()
            names.add(c.phrase)
            names.add(stripPrefix(normalize(c.phrase)))
            names.addAll(aliasesOf(c.phrase))
            var local = 0
            for (n in names) local = maxOf(local, scoreName(n, q))
            if (local > 0) scored += c to local
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.second }
        val best = scored[0]
        val second = scored.getOrNull(1)?.second ?: 0
        val unique = scored.count { it.second == best.second } == 1
        if (best.second >= 8500) return best.first
        return if (isStrong(best.second, second, q, unique)) best.first else null
    }

    fun parse(spoken: String): Parsed {
        val full = prepared(spoken)
        if (full.isEmpty()) return Parsed(Kind.NONE, "", spoken, Dest.TNT)
        if (isVoiceCancel(full)) return Parsed(Kind.CANCEL, "close", spoken, Dest.TNT)
        val destPair = splitDest(full)
        val dest = destPair.first
        val rawN = destPair.second
        val ctrlFull = VoiceAssist.control(full)
        if (ctrlFull != null) return Parsed(Kind.CONTROL, ctrlFull.first + "|" + ctrlFull.second, spoken, dest)
        val rawFlex = flex(rawN)
        if (dest == Dest.FREEFORM && (rawN.isEmpty() || rawN.contains("当前") || rawN.contains("这个") || rawFlex in setOf("打开", "开", "挂", "开启", "将", "把"))) {
            return Parsed(Kind.CONTROL, "freeform_current|toggle", spoken, dest)
        }
        if (dest == Dest.FREEFORM && rawFlex in setOf("关闭", "关掉", "关上", "收起", "退出", "关了", "收了")) {
            return Parsed(Kind.CONTROL, "freeform_close|toggle", spoken, dest)
        }
        if (rawN.isEmpty()) return Parsed(Kind.NONE, "", spoken, dest)
        if (isVoiceCancel(rawN) || flex(rawN) in setOf("关闭", "关掉", "关上")) return Parsed(Kind.CANCEL, "close", spoken, dest)
        val webExact = listOf("打开网页", "打开网页端", "用网页", "网页端", "网页打开", "用浏览器", "浏览器打开", "打开浏览器")
        if (webExact.any { rawN == normalize(it) || rawN == normalize(it) + "吧" }) return Parsed(Kind.CONFIRM_WEB, "", spoken, dest)
        val searchExact = listOf("搜索", "搜一下", "搜", "百度一下", "查一下", "找一下", "给我搜")
        if (searchExact.any { rawN == normalize(it) || rawN == normalize(it) + "吧" }) return Parsed(Kind.CONFIRM_SEARCH, "", spoken, dest)
        for (suffix in listOf("的网页端", "网页端", "的网页", "网页")) {
            val ns = normalize(suffix)
            if (rawN.endsWith(ns) && rawN.length > ns.length + 1) {
                val q = stripPrefix(rawN.substring(0, rawN.length - ns.length))
                if (q.length >= 2) return Parsed(Kind.OPEN_WEB, q, spoken, dest)
            }
        }
        val note = VoiceAssist.noteText(rawN)
        if (note != null) return Parsed(Kind.NOTE, note, spoken, dest)
        val typed = VoiceAssist.typedText(rawN)
        if (typed != null) return Parsed(Kind.CONTROL, "input|" + typed, spoken, dest)
        for (p in listOf("关闭一下", "关掉", "关上", "关闭", "结束", "退出")) {
            val np = normalize(p)
            if (rawN.startsWith(np) && rawN.length > np.length + 1) {
                val rest = stripPrefix(rawN.substring(np.length))
                if (rest.length >= 2 && catalogOf(rest) != null) {
                    return Parsed(Kind.CONTROL, "kill_app|" + rest, spoken, dest)
                }
            }
        }
        val ask = askKey(rawN)
        if (ask != null && !(ask in setOf("hi", "who", "help") && (rawN.length > 4 || rawN.contains("打开") || rawN.contains("关闭") || dest != Dest.TNT))) {
            return Parsed(Kind.ASK, ask, spoken, dest)
        }
        val ctrl = VoiceAssist.control(rawN)
        if (ctrl != null) return Parsed(Kind.CONTROL, ctrl.first + "|" + ctrl.second, spoken, dest)
        val searchPref = listOf("请帮我搜索", "帮我搜索", "请帮我找", "帮我找", "搜索一下", "给我搜", "给我找", "查一下", "找一找", "找一下", "百度一下", "搜索", "搜一下", "找", "搜")
        for (p in searchPref) {
            val np = normalize(p)
            if (rawN.startsWith(np) && rawN.length > np.length) return Parsed(Kind.SEARCH, rawN.substring(np.length), spoken, dest)
        }
        val instPref = listOf("请帮我安装", "帮我安装", "安装一下", "去安装", "下载安装", "下载一下", "安装", "下载", "装一下")
        for (p in instPref) {
            val np = normalize(p)
            if (rawN == np || rawN == np + "吧") return Parsed(Kind.CONFIRM_INSTALL, "", spoken, dest)
            if (rawN.startsWith(np) && rawN.length > np.length) return Parsed(Kind.INSTALL, rawN.substring(np.length), spoken, dest)
        }
        if (flex(rawN) in setOf("好", "好的", "要", "行", "是", "对", "确认", "嗯", "可以", "中", "成")) return Parsed(Kind.YES, "", spoken, dest)
        return Parsed(Kind.LAUNCH, stripPrefix(rawN), spoken, dest)
    }

    private fun isVoiceCancel(raw: String): Boolean {
        val x = flex(raw)
        if (x.isEmpty()) return false
        val exact = setOf("取消", "不用", "不要", "算了", "停下", "停止", "退下", "再见", "拜拜", "关闭语音", "退出语音", "关掉语音", "关闭助手", "退出助手", "关闭语音助手", "退出语音助手", "结束语音", "关掉助手")
        if (exact.any { flex(it) == x || it == x }) return true
        val talk = x.contains("语音") || x.contains("助手")
        val stop = x.contains("关闭") || x.contains("退出") || x.contains("关掉") || x.contains("结束")
        return talk && stop
    }

    private fun hitSet(q: String, phrases: Set<String>, keys: List<String>): Boolean {
        if (keys.any { q.contains(it) }) return true
        return phrases.any { looseHit(q, it) }
    }

    fun askKey(spoken: String): String? {
        val full = prepared(spoken)
        val n = splitDest(full).second
        val cands = listOf(full, n, stripPrefix(n), flex(full), flex(n)).filter { it.isNotEmpty() }.distinct()
        for (c in cands) {
            if (hitSet(c, timeAsk, listOf("几点", "报时", "什么时间", "现在时间"))) return "time"
            if (hitSet(c, dateAsk, listOf("几号", "日期", "几月几"))) return "date"
            if (hitSet(c, weekAsk, listOf("星期几", "周几", "礼拜几"))) return "week"
            if (hitSet(c, whoAsk, listOf("你是谁", "你叫什么"))) return "who"
            if (hitSet(c, helpAsk, listOf("你能做", "你会什么", "有什么功能", "能干什么"))) return "help"
            if (hiAsk.any { flex(c) == flex(it) || normalize(c) == normalize(it) }) return "hi"
            val extra = VoiceAssist.askKey(listOf(c))
            if (extra != null) return extra
        }
        return null
    }

    fun askReply(spoken: String, ctx: Context? = null): String {
        val cal = Calendar.getInstance()
        val key = askKey(spoken) ?: parse(spoken).query
        val extra = VoiceAssist.askReply(key, ctx)
        if (extra.isNotBlank()) return extra
        return when (key) {
            "time" -> {
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val m = cal.get(Calendar.MINUTE)
                if (m == 0) "现在" + h + "点整" else "现在" + h + "点" + (if (m < 10) "零" else "") + m + "分"
            }
            "date" -> (cal.get(Calendar.MONTH) + 1).toString() + "月" + cal.get(Calendar.DAY_OF_MONTH) + "号"
            "week" -> "今天星期" + weekNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            "who" -> "我是锤柄语音"
            "help" -> "可以说打开应用、小窗打开、开关Wi-Fi蓝牙流量、截屏、复制粘贴、录音、亮屏、小窗当前、侧边栏、查电量天气、记备忘、找东西"
            "hi" -> "在的"
            "weather" -> "正在查天气"
            else -> "没听懂"
        }
    }

    fun offer(): Offer? {
        synchronized(lock) {
            val p = pending ?: return null
            if (SystemClock.uptimeMillis() - p.at > 25000L) {
                pending = null
                return null
            }
            return p
        }
    }

    fun setOffer(offer: Offer?) {
        synchronized(lock) { pending = offer }
    }

    fun makeOffer(label: String, query: String, packages: List<String>, web: String): Offer {
        return Offer(label, query, packages, web, SystemClock.uptimeMillis())
    }

    data class AppHit(val pkg: String, val label: String, val score: Int, val strong: Boolean)

    fun bestHit(apps: List<Pair<String, String>>, spoken: String): AppHit? {
        val q = queryOf(spoken)
        if (q.isEmpty()) return null
        var bestLabel = ""
        var bestPkg = ""
        var bestScore = 0
        var second = 0
        for ((label, pkg) in apps) {
            var local = 0
            for (n in aliasesOf(label) + extraKeys(pkg)) local = maxOf(local, scoreName(n, q))
            val pkgNorm = normalize(pkg)
            if (q.length >= 4 && pkgNorm.contains(q) && local < 1500) local = 1500
            if (local <= 0) continue
            if (local > bestScore) {
                second = bestScore
                bestScore = local
                bestLabel = label
                bestPkg = pkg
            } else if (local > second) second = local
        }
        if (bestPkg.isEmpty()) return null
        val unique = apps.count { (label, pkg) ->
            var local = 0
            for (n in aliasesOf(label) + extraKeys(pkg)) local = maxOf(local, scoreName(n, q))
            local == bestScore && bestScore > 0
        } == 1
        return AppHit(bestPkg, bestLabel, bestScore, isStrong(bestScore, second, q, unique))
    }

    fun catalogOf(query: String): VoiceCatalog.Entry? {
        val q = queryOf(query).ifBlank { normalize(query) }
        val builtin = VoiceCatalog.match(q)
        var bestAlias: VoiceAlias? = null
        var best = 0
        var second = 0
        for (a in config().aliases) {
            val local = maxOf(scoreName(a.spoken, q), scoreName(a.label, q))
            if (local > best) {
                second = best
                best = local
                bestAlias = a
            } else if (local > second) {
                second = local
            }
        }
        val aliasOk = bestAlias != null && (best >= 7000 || (best >= 5000 && best - second >= 800))
        if (aliasOk) {
            val a = bestAlias!!
            return VoiceCatalog.Entry(a.label, listOf(a.spoken, a.label), listOf(a.packageName), "")
        }
        return builtin
    }

    private fun openWords(dest: Dest): String {
        return when (dest) {
            Dest.FREEFORM -> "会在小窗打开"
            Dest.PHONE -> "会在手机端打开"
            Dest.TNT -> "会在TNT打开"
        }
    }

    private fun actionWords(action: String): String {
        return when (action) {
            "shell" -> "运行命令"
            "search" -> "搜索"
            "freeform" -> "小窗打开"
            else -> "打开应用"
        }
    }

    fun preview(spoken: String, commands: List<VoiceCommand>, apps: List<Pair<String, String>>): String {
        val parsed = parse(spoken)
        val cmdFirst = matchCustom(spoken, commands)
        if (cmdFirst != null && parsed.kind != Kind.CANCEL && parsed.kind != Kind.YES && parsed.kind != Kind.CONFIRM_WEB && parsed.kind != Kind.CONFIRM_INSTALL && parsed.kind != Kind.CONFIRM_SEARCH) {
            val act = if (parsed.dest == Dest.FREEFORM && cmdFirst.action != "search" && cmdFirst.action != "shell") "freeform" else cmdFirst.action
            return "命中自定义指令「" + cmdFirst.phrase + "」→ " + actionWords(act) + " " + cmdFirst.target
        }
        when (parsed.kind) {
            Kind.SEARCH, Kind.CONFIRM_SEARCH -> {
                val q = parsed.query.ifBlank { offer()?.query ?: offer()?.label ?: "" }
                return if (q.isNotBlank()) "会搜索「" + q + "」" else "会搜索"
            }
            Kind.INSTALL, Kind.CONFIRM_INSTALL -> {
                val q = parsed.query.ifBlank { offer()?.query ?: "" }
                val cat = if (q.isNotEmpty()) catalogOf(q) else null
                val name = cat?.label ?: q.ifBlank { offer()?.label ?: "这个应用" }
                return "会去应用市场安装「" + name + "」"
            }
            Kind.OPEN_WEB -> {
                val cat = catalogOf(parsed.query)
                val name = cat?.label ?: parsed.query
                return "会打开「" + name + "」网页"
            }
            Kind.CONFIRM_WEB -> {
                val o = offer()
                return if (o != null) "会打开「" + o.label + "」网页" else "会打开网页搜索"
            }
            Kind.CANCEL -> return "会关闭语音"
            Kind.YES -> {
                val o = offer()
                return if (o == null) "没有待确认的操作" else if (o.web.isNotBlank()) "有网页和安装两个选项，请说打开网页或安装" else "会去应用市场安装「" + o.label + "」"
            }
            Kind.NONE -> return "没有听清"
            Kind.ASK -> return askReply(spoken)
            Kind.CONTROL -> {
                val p = parsed.query.split("|")
                return "会" + VoiceAssist.controlPreview(p.getOrNull(0) ?: "", p.getOrNull(1) ?: "")
            }
            Kind.NOTE -> return "会记下「" + parsed.query + "」"
            Kind.LAUNCH -> {}
        }
        val cmd = matchCustom(spoken, commands)
        if (cmd != null) {
            val act = if (parsed.dest == Dest.FREEFORM && cmd.action != "search" && cmd.action != "shell") "freeform" else cmd.action
            return "命中自定义指令「" + cmd.phrase + "」→ " + actionWords(act) + " " + cmd.target
        }
        val q = parsed.query
        if (q.length < 2) return "没有强命中"
        val cat = catalogOf(q)
        if (cat != null) {
            val installed = cat.packages.firstOrNull { pkg -> apps.any { it.second == pkg } }
            return if (installed != null) {
                openWords(parsed.dest) + "「" + cat.label + "」"
            } else if (cat.web.isNotBlank()) {
                "没有安装「" + cat.label + "」。可以说打开网页端，或安装。"
            } else {
                "没有安装「" + cat.label + "」。可以说搜索，或安装。"
            }
        }
        val hit = bestHit(apps, spoken)
        return if (hit != null && hit.strong) {
            openWords(parsed.dest) + "「" + hit.label + "」" + hit.pkg
        } else if (q.isNotBlank()) {
            "没有找到「" + q + "」。可以说搜索，或安装。"
        } else {
            "没有强命中"
        }
    }
}
