package id.tntwindow.editor.data

import android.content.pm.ApplicationInfo
import id.tntwindow.editor.domain.AppCollection
import id.tntwindow.editor.domain.Categories
import id.tntwindow.editor.domain.CollectionType
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.Membership
import id.tntwindow.editor.domain.ParseReport
import id.tntwindow.editor.domain.Presets
import id.tntwindow.editor.domain.WindowSpec

object Classifier {
    private val exact = mapOf(
        "com.himrsc.viz" to "tool",
        "com.shizi.tool.p3" to "tool",
        "com.hjmore.wpnidle" to "game",
        "com.qingmusic.changqing" to "music",
        "bin.mt.plus.canary" to "tool",
        "bin.mt.plus" to "tool",
        "cn.youxiao.flitik" to "tool",
        "com.leapmusic.leapmusic" to "music",
        "io.legado.app.release" to "reader",
        "com.yjllq.kito" to "video",
        "com.ss.android.ugc.aweme.lite" to "video",
        "com.ss.android.ugc.aweme" to "video",
        "com.salt.music" to "music",
        "com.mxtech.videoplayer.pro" to "video",
        "com.mxtech.videoplayer" to "video",
        "com.google.android.youtube" to "video",
        "com.android.chrome" to "browser",
        "com.android.browser" to "browser",
        "com.tencent.mm" to "social",
        "com.tencent.mobileqq" to "social",
        "com.tencent.tim" to "social",
        "com.tencent.qqlite" to "social",
        "com.qzone" to "social",
        "com.tencent.androidqqmail" to "tool",
        "com.sina.weibo" to "social",
        "tv.danmaku.bili" to "video",
        "com.bilibili.app.in" to "video",
        "com.qiyi.video" to "video",
        "com.youku.phone" to "video",
        "com.tencent.qqlive" to "video",
        "com.smile.gifmaker" to "video",
        "com.ss.android.article.video" to "video",
        "com.netease.cloudmusic" to "music",
        "com.kugou.android" to "music",
        "cn.kuwo.player" to "music",
        "com.tencent.qqmusic" to "music",
        "com.ximalaya.ting.android" to "music",
        "com.tencent.weread" to "reader",
        "com.duokan.reader" to "reader",
        "com.dragon.read" to "reader",
        "com.zhihu.android" to "reader",
        "org.mozilla.firefox" to "browser",
        "com.microsoft.emmx" to "browser",
        "mark.via" to "browser",
        "com.kiwibrowser.browser" to "browser",
        "com.ucmobile" to "browser",
        "com.opera.browser" to "browser",
        "org.telegram.messenger" to "social",
        "org.thunderdog.challegram" to "social",
        "com.twitter.android" to "social",
        "com.instagram.android" to "social",
        "com.discord" to "social",
        "com.whatsapp" to "social",
        "com.termux" to "tool",
        "com.topjohnwu.magisk" to "tool",
        "me.weishu.kernelsu" to "tool",
        "io.github.huskydg.kernelsu" to "tool",
        "org.lsposed.manager" to "tool",
        "com.microsoft.office.word" to "tool",
        "com.microsoft.office.excel" to "tool",
        "com.microsoft.office.powerpoint" to "tool",
        "com.yozo.sts.office" to "tool",
        "cn.wps.moffice_eng" to "tool",
        "cn.wps.moffice" to "tool",
        "com.tencent.android.qqdownloader" to "tool",
        "com.android.vending" to "tool",
        "com.smartisan.notes" to "tool",
        "com.smartisanos.calculator" to "tool",
        "com.smartisanos.filemanager" to "tool",
        "com.smartisanos.music" to "music",
        "com.smartisanos.gallery" to "tool",
        "com.smartisanos.calendar" to "tool",
        "com.smartisanos.email" to "tool",
        "com.smartisanos.browser" to "browser",
        "com.smartisanos.video" to "video",
        "com.smartisanos.voiceassist" to "system",
        "com.smartisanos.virtualremoter" to "system",
        "com.smartisanos.desktop" to "system",
        "com.android.desktop.systemui" to "system",
    )

    private val videoKeys = listOf(
        "youtube", "bili", "iqiyi", "qiyi", "youku", "tudou", "qqlive", "video", "movie", "player",
        "aweme", "kuaishou", "gifmaker", "tiktok", "netflix", "iqy", "wetv", "mgtv", "sohu",
        "pptv", "leishi", "leshi", "letv", "mxplayer", "vlc", "potplayer", "cinema", "drama", "film",
        "huya", "douyu", "hunantv", "wasu", "cntv", "cctv", "funshion", "baofeng", "yingshi",
        "cibn", "dangbei", "migu.video", "miguvideo", "xigua", "watermelon", "kankan", "btime",
        "livetv", ".live", "tv.", "smartisanos.video", "yjllq", "kito", "kimivideo", "aliyun.video",
    )
    private val musicKeys = listOf(
        "music", "kugou", "kuwo", "qqmusic", "netease.cloud", "spotify", "ximalaya", "radio",
        "podcast", "audio", "fm.", "tune", "salt.music", "qingmusic", "leapmusic", "changba",
        "karaoke", "lyric", "piano", "xiami", "migu.music", "smartisanos.music", "netease.cloudmusic",
        "allmusic", "soundcloud",
    )
    private val browserKeys = listOf(
        "chrome", "browser", "firefox", "webkit", "via", "kiwi", "edge", "ucmobile", "opera",
        "brave", "safari", "webview", "uc.browser", "qihoo.browser", "baidu.browser", "sogou.browser",
        "sougou", "tencent.mtt", "quark", "samsung.android.browser", "smartisanos.browser",
    )
    private val readerKeys = listOf(
        "reader", "book", "legado", "kindle", "weread", "duokan", "novel", "ebook", "zhihu",
        "news", "article", "read.", "ireader", "shuqi", "qidian", "qqreader", "sfacg", "manhua",
        "manga", "comic", "webtoon", "toutiao", "netease.news", "ucpro", "jianshu", "douban.frodo",
        "smartisan.notes",
    )
    private val socialKeys = listOf(
        "wechat", "tencent.mm", "mobileqq", "telegram", "discord", "weibo", "twitter",
        "instagram", "whatsapp", "facebook", "messenger", "tieba", "douban", "social",
        "dingtalk", "wework", "alibaba.android.rimet", "tim", "qqlite", "qzone", "challegram",
        "feishu", "lark", "kakaotalk", "line.android", "snapchat", "reddit", "xiaohongshu",
        "xingin", "coolapk",
    )
    private val gameKeys = listOf(
        "game", "mihoyo", "hoyoverse", "tmgp", "unity", "netease.game", "rpg", "wpnidle",
        "hypergryph", "sunborn", "arknights", "genshin", "bh3", "pubgm", "sgame", "aligames",
        "kurogame", "papegames", "nexon", "garena", "supercell", "mojang", "minecraft", "roblox",
        "rovio", "popcap", "blizzard", "gameloft", "lilith", "onmyoji", "hearthstone", "simcity",
        "pvz", "angrybirds", "kihan", "hyxd", "mrzh", "dwrg", "wyclx", "frxy", "pesam", "freestyle",
        "korogarimasu", "gettingoverit", "polybridge", "altos", "machinarium",
    )
    private val toolKeys = listOf(
        "tool", "mt.plus", "termux", "magisk", "kernelsu", "lsposed", "busybox", "filemanager",
        "explorer", "helper", "util", "editor", "terminal", "adb", "root", "flitik",
        "shizi", "himrsc", "estrongs", "fdroid", "aurora", "appops", "brevent", "icebox",
        "greenify", "tasker", "titanium", "office", "word", "excel", "powerpoint", "ppt", "wps",
        "yozo", "mindmap", "mindnode", "mindflow", "picsart", "calculator", "calendar", "mail",
        "scanner", "translate", "vpn", "proxy", "cleaner", "backup", "installer", "periodictable",
        "kinemaster", "remote", "setting",
    )

    private fun hit(blob: String, keys: List<String>) = keys.any { blob.contains(it) }

    fun isSystemPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.startsWith("com.smartisan") ||
            p.startsWith("com.smartisanos") ||
            p.startsWith("com.android.") ||
            p.startsWith("com.google.android.") ||
            p.startsWith("com.qualcomm.") ||
            p.startsWith("com.qti.") ||
            p.startsWith("android.") ||
            p == "android"
    }

    fun classify(pkg: String, label: String, system: Boolean, category: Int, spec: WindowSpec?): String {
        exact[pkg]?.let { return it }
        if (isSystemPackage(pkg)) return Categories.SYSTEM.id
        val blob = (pkg + " " + label).lowercase()
        if (hit(blob, videoKeys)) return Categories.VIDEO.id
        if (hit(blob, musicKeys)) return Categories.MUSIC.id
        if (hit(blob, browserKeys)) return Categories.BROWSER.id
        if (hit(blob, readerKeys)) return Categories.READER.id
        if (hit(blob, socialKeys)) return Categories.SOCIAL.id
        if (hit(blob, gameKeys)) return Categories.GAME.id
        if (hit(blob, toolKeys)) return Categories.TOOL.id
        when (category) {
            ApplicationInfo.CATEGORY_VIDEO -> return Categories.VIDEO.id
            ApplicationInfo.CATEGORY_AUDIO -> return Categories.MUSIC.id
            ApplicationInfo.CATEGORY_GAME -> return Categories.GAME.id
            ApplicationInfo.CATEGORY_SOCIAL -> return Categories.SOCIAL.id
            ApplicationInfo.CATEGORY_NEWS -> return Categories.READER.id
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return Categories.TOOL.id
        }
        val fp = spec?.fingerprint().orEmpty()
        if (fp == "4/-/-/-/-/0") return Categories.GAME.id
        if (fp == "1/1095/694/1095/694/4") return Categories.GAME.id
        if (system) return Categories.SYSTEM.id
        return ""
    }

    fun builtins(): List<AppCollection> {
        val custom = AppCollection(
            id = Presets.CUSTOM_ID,
            name = "自定义",
            type = CollectionType.CUSTOM,
            members = emptyList(),
            pinned = true,
            builtin = true,
        )
        val systemCustom = AppCollection(
            id = Presets.SYSTEM_CUSTOM_ID,
            name = "系统自定义",
            type = CollectionType.CUSTOM,
            members = emptyList(),
            builtin = true,
        )
        val cats = Categories.ALL.map {
            AppCollection(
                id = it.id,
                name = it.name,
                type = CollectionType.SPEC,
                spec = it.spec,
                members = emptyList(),
                builtin = true,
            )
        }
        return listOf(custom, systemCustom) + cats
    }

    fun build(
        report: ParseReport,
        installed: List<InstalledApp>,
        old: List<AppCollection>?,
    ): List<AppCollection> {
        val byPkg = installed.associateBy { it.packageName }
        val buckets = linkedMapOf<String, MutableList<Membership>>()
        builtins().forEach { buckets[it.id] = mutableListOf() }
        val first = report.apps.filter { it.duplicateIndex == 0 && it.packageName.isNotEmpty() }
        val xmlPkgs = first.map { it.packageName }.toSet()
        for (app in first) {
            val info = byPkg[app.packageName]
            val id = classify(
                app.packageName,
                info?.label ?: app.packageName,
                info?.system ?: false,
                info?.category ?: -1,
                app.spec,
            ).ifBlank { Presets.SYSTEM_CUSTOM_ID }
            val target = if (id in buckets) id else Presets.SYSTEM_CUSTOM_ID
            val customType = target == Presets.CUSTOM_ID || target == Presets.SYSTEM_CUSTOM_ID
            val member = if (customType) Membership(app.packageName, app.spec) else Membership(app.packageName)
            buckets.getValue(target) += member
        }
        var rebuilt = builtins().map { it.copy(members = buckets[it.id].orEmpty()) }
        if (old == null) return rebuilt
        val extras = old.filter { it.id !in Categories.IDS && it.id !in Categories.LEGACY_IDS }
        val extraPkgs = extras.flatMap { c -> c.members.map { it.packageName } }.toSet()
        if (extraPkgs.isNotEmpty()) {
            rebuilt = rebuilt.map { col ->
                col.copy(members = col.members.filter { it.packageName !in extraPkgs })
            }
        }
        val oldCustom = old.firstOrNull { it.id == Presets.CUSTOM_ID }
        val keepCustom = oldCustom?.members.orEmpty().filter { it.packageName !in xmlPkgs && it.packageName !in extraPkgs }
        if (keepCustom.isNotEmpty()) {
            rebuilt = rebuilt.map { col ->
                if (col.id == Presets.CUSTOM_ID) col.copy(members = col.members + keepCustom) else col
            }
        }
        return rebuilt + extras
    }

    fun looksLegacy(cols: List<AppCollection>): Boolean {
        if (cols.any { it.id in Categories.LEGACY_IDS }) return true
        val ids = cols.map { it.id }.toSet()
        if (Presets.SYSTEM_CUSTOM_ID !in ids) return true
        return Categories.ALL.none { it.id in ids }
    }

    fun mergeBuiltins(cols: List<AppCollection>): List<AppCollection> {
        val byId = cols.associateBy { it.id }
        val built = builtins().map { base ->
            val old = byId[base.id]
            if (old == null) base
            else base.copy(
                name = if (base.id == Presets.CUSTOM_ID || base.id == Presets.SYSTEM_CUSTOM_ID) base.name else old.name.ifBlank { base.name },
                spec = if (base.type == CollectionType.SPEC) (old.spec ?: base.spec) else null,
                members = old.members,
            )
        }
        val extras = cols.filter { it.id !in Categories.IDS && it.id !in Categories.LEGACY_IDS }
        return built + extras
    }
}