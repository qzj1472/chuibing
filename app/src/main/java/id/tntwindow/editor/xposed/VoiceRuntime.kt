package id.tntwindow.editor.xposed

import android.os.SystemClock
import id.tntwindow.editor.domain.VoiceAlias
import id.tntwindow.editor.domain.VoiceCatalog
import id.tntwindow.editor.domain.VoiceCommand
import id.tntwindow.editor.domain.VoiceConfig
import java.io.File
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
    )

    private val weakWords = setOf(
        "音乐", "视频", "播放器", "浏览器", "应用", "软件", "播放", "相册", "相机", "设置", "文件", "笔记", "日历", "邮件",
    )

    private val nameSuffixes = listOf(
        "专业版", "正式版", "极速版", "精简版", "播放器", "浏览器", "音乐", "视频", "应用", "pro", "lite",
    )

    enum class Kind {
        LAUNCH, SEARCH, INSTALL, OPEN_WEB, CONFIRM_WEB, CONFIRM_INSTALL, CONFIRM_SEARCH, YES, CANCEL, NONE
    }

    data class Parsed(val kind: Kind, val query: String, val raw: String)

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
        synchronized(lock) { lastSpokenText = text }
    }

    fun correct(spoken: String): String {
        var s = spoken
        for ((a, b) in corrections) s = s.replace(a, b)
        if (!s.contains("闪念") && s.contains("胶囊")) s = s.replace("胶囊", "椒盐")
        return s
    }

    fun normalize(raw: String): String {
        return raw.lowercase(Locale.CHINA)
            .replace(" ", "")
            .replace("　", "")
            .replace("-", "")
            .replace("_", "")
            .replace(".", "")
            .replace("·", "")
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
        for (s in listOf("一下", "应用", "软件")) {
            if (q.endsWith(s) && q.length > s.length) q = q.substring(0, q.length - s.length)
        }
        return q
    }

    fun queryOf(spoken: String): String = stripPrefix(normalize(correct(spoken)))

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
        if (nn.length < 2 || q.length < 2) return 0
        if (nn == q) return 10000 + nn.length * 20
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
        if (a.length != b.length || a.length < 2) return false
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff += 1
        return diff == 1
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
        if (q.length < 2) return null
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
        val rawN = normalize(correct(spoken))
        if (rawN.isEmpty()) return Parsed(Kind.NONE, "", spoken)
        if (rawN in setOf("取消", "不用", "不要", "算了", "停下", "停止")) return Parsed(Kind.CANCEL, "", spoken)
        val webExact = listOf("打开网页", "打开网页端", "用网页", "网页端", "网页打开", "用浏览器", "浏览器打开", "打开浏览器")
        if (webExact.any { rawN == normalize(it) || rawN == normalize(it) + "吧" }) return Parsed(Kind.CONFIRM_WEB, "", spoken)
        val searchExact = listOf("搜索", "搜一下", "搜", "百度一下", "查一下", "找一下", "给我搜")
        if (searchExact.any { rawN == normalize(it) || rawN == normalize(it) + "吧" }) return Parsed(Kind.CONFIRM_SEARCH, "", spoken)
        for (suffix in listOf("的网页端", "网页端", "的网页", "网页")) {
            val ns = normalize(suffix)
            if (rawN.endsWith(ns) && rawN.length > ns.length + 1) {
                val q = stripPrefix(rawN.substring(0, rawN.length - ns.length))
                if (q.length >= 2) return Parsed(Kind.OPEN_WEB, q, spoken)
            }
        }
        val searchPref = listOf("请帮我搜索", "帮我搜索", "搜索一下", "给我搜", "查一下", "找一下", "百度一下", "搜索", "搜一下", "搜")
        for (p in searchPref) {
            val np = normalize(p)
            if (rawN.startsWith(np) && rawN.length > np.length) return Parsed(Kind.SEARCH, rawN.substring(np.length), spoken)
        }
        val instPref = listOf("请帮我安装", "帮我安装", "安装一下", "去安装", "下载安装", "下载一下", "安装", "下载", "装一下")
        for (p in instPref) {
            val np = normalize(p)
            if (rawN == np || rawN == np + "吧") return Parsed(Kind.CONFIRM_INSTALL, "", spoken)
            if (rawN.startsWith(np) && rawN.length > np.length) return Parsed(Kind.INSTALL, rawN.substring(np.length), spoken)
        }
        if (rawN in setOf("好", "好的", "要", "行", "是", "对", "确认", "嗯", "可以")) return Parsed(Kind.YES, "", spoken)
        return Parsed(Kind.LAUNCH, queryOf(spoken), spoken)
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
        if (q.length < 2) return null
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

    fun preview(spoken: String, commands: List<VoiceCommand>, apps: List<Pair<String, String>>): String {
        val parsed = parse(spoken)
        val cmdFirst = matchCustom(spoken, commands)
        if (cmdFirst != null && parsed.kind != Kind.CANCEL && parsed.kind != Kind.YES && parsed.kind != Kind.CONFIRM_WEB && parsed.kind != Kind.CONFIRM_INSTALL && parsed.kind != Kind.CONFIRM_SEARCH) {
            val kind = when (cmdFirst.action) {
                "shell" -> "运行命令"
                "search" -> "搜索"
                else -> "打开应用"
            }
            return "命中自定义指令「" + cmdFirst.phrase + "」→ " + kind + " " + cmdFirst.target
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
            Kind.CANCEL -> return "会取消上一步"
            Kind.YES -> {
                val o = offer()
                return if (o == null) "没有待确认的操作" else if (o.web.isNotBlank()) "有网页和安装两个选项，请说打开网页或安装" else "会去应用市场安装「" + o.label + "」"
            }
            Kind.NONE -> return "没有听清"
            Kind.LAUNCH -> {}
        }
        val cmd = matchCustom(spoken, commands)
        if (cmd != null) {
            val kind = when (cmd.action) {
                "shell" -> "运行命令"
                "search" -> "搜索"
                else -> "打开应用"
            }
            return "命中自定义指令「" + cmd.phrase + "」→ " + kind + " " + cmd.target
        }
        val q = parsed.query
        if (q.length < 2) return "没有强命中"
        val cat = catalogOf(q)
        if (cat != null) {
            val installed = cat.packages.firstOrNull { pkg -> apps.any { it.second == pkg } }
            return if (installed != null) {
                "会打开「" + cat.label + "」"
            } else if (cat.web.isNotBlank()) {
                "没有安装「" + cat.label + "」。可以说打开网页端，或安装。"
            } else {
                "没有安装「" + cat.label + "」。可以说搜索，或安装。"
            }
        }
        val hit = bestHit(apps, spoken)
        return if (hit != null && hit.strong) {
            "会打开「" + hit.label + "」" + hit.pkg
        } else if (q.isNotBlank()) {
            "没有找到「" + q + "」。可以说搜索，或安装。"
        } else {
            "没有强命中"
        }
    }
}