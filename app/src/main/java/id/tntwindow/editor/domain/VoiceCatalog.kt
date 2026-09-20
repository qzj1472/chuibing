package id.tntwindow.editor.domain

object VoiceCatalog {
    data class Entry(
        val label: String,
        val keys: List<String>,
        val packages: List<String>,
        val web: String,
    )

    val builtin: List<Entry> = listOf(
        Entry("哔哩哔哩", listOf("b站", "bilibili", "哔哩哔哩", "哔哩哔哩动画", "哔哩", "小破站", "bili", "b站app", "哔哩哔哩app"), listOf("tv.danmaku.bili", "com.bilibili.app.in", "tv.danmaku.bilibilihd"), "https://m.bilibili.com"),
        Entry("YouTube", listOf("油管", "youtube", "youtub", "优土鳖", "yt", "油管视频"), listOf("com.google.android.youtube"), "https://m.youtube.com"),
        Entry("微信", listOf("微信", "wechat", "weixin", "威信", "weixinapp"), listOf("com.tencent.mm"), "https://wx.qq.com"),
        Entry("QQ", listOf("qq", "扣扣", "企鹅", "qq手机版"), listOf("com.tencent.mobileqq"), "https://im.qq.com"),
        Entry("抖音", listOf("抖音", "douyin", "tiktok", "某音", "抖音短视频"), listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"), "https://m.douyin.com"),
        Entry("快手", listOf("快手", "kuaishou"), listOf("com.smile.gifmaker"), "https://m.kuaishou.com"),
        Entry("小红书", listOf("小红书", "红薯", "rednote", "小红薯", "某书"), listOf("com.xingin.xhs"), "https://www.xiaohongshu.com"),
        Entry("淘宝", listOf("淘宝", "某宝", "taobao"), listOf("com.taobao.taobao"), "https://m.taobao.com"),
        Entry("支付宝", listOf("支付宝", "alipay", "某付宝"), listOf("com.eg.android.AlipayGphone"), "https://www.alipay.com"),
        Entry("京东", listOf("京东", "某东", "jd"), listOf("com.jingdong.app.mall"), "https://m.jd.com"),
        Entry("拼多多", listOf("拼多多", "pdd", "拼夕夕"), listOf("com.xunmeng.pinduoduo"), "https://mobile.yangkeduo.com"),
        Entry("闲鱼", listOf("闲鱼", "咸鱼"), listOf("com.taobao.idlefish"), "https://2.taobao.com"),
        Entry("微博", listOf("微博", "weibo", "新浪微博"), listOf("com.sina.weibo"), "https://m.weibo.cn"),
        Entry("知乎", listOf("知乎", "zhihu"), listOf("com.zhihu.android"), "https://www.zhihu.com"),
        Entry("百度", listOf("百度", "baidu"), listOf("com.baidu.searchbox"), "https://m.baidu.com"),
        Entry("百度网盘", listOf("百度网盘", "网盘", "度盘"), listOf("com.baidu.netdisk"), "https://pan.baidu.com"),
        Entry("网易云音乐", listOf("网易云", "网易云音乐", "云村", "网易云村"), listOf("com.netease.cloudmusic"), "https://music.163.com"),
        Entry("QQ音乐", listOf("qq音乐", "企鹅音乐"), listOf("com.tencent.qqmusic"), "https://y.qq.com"),
        Entry("酷狗音乐", listOf("酷狗", "酷狗音乐"), listOf("com.kugou.android"), "https://m.kugou.com"),
        Entry("酷我音乐", listOf("酷我", "酷我音乐"), listOf("cn.kuwo.player"), "https://www.kuwo.cn"),
        Entry("汽水音乐", listOf("汽水", "汽水音乐"), listOf("com.luna.music"), ""),
        Entry("椒盐音乐", listOf("椒盐", "椒盐音乐", "salt", "盐盐", "椒盐播放器", "saltplayer", "salt music"), listOf("com.salt.music"), ""),
        Entry("MX播放器", listOf("mx", "mx播放器", "mxplayer", "mx player", "mx播放器pro", "angmuaikesi", "anmuaikesi", "emex", "emx", "mancha", "曼查", "麦克斯", "曼克斯", "爱姆艾克斯", "mxpro", "mxplayerpro"), listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad"), ""),
        Entry("阅读", listOf("legado", "开源阅读", "阅读app", "legado阅读"), listOf("io.legado.app.release"), ""),
        Entry("Chrome", listOf("chrome", "谷歌浏览器", "谷歌", "google浏览器"), listOf("com.android.chrome"), "https://www.google.com"),
        Entry("夸克", listOf("夸克", "夸克浏览器"), listOf("com.quark.browser"), ""),
        Entry("Via", listOf("via", "via浏览器"), listOf("mark.via", "mark.via.gp"), ""),
        Entry("Firefox", listOf("firefox", "火狐", "火狐浏览器"), listOf("org.mozilla.firefox"), "https://www.mozilla.org"),
        Entry("Telegram", listOf("telegram", "tg", "纸飞机", "电报"), listOf("org.telegram.messenger"), "https://web.telegram.org"),
        Entry("Discord", listOf("discord", "dc"), listOf("com.discord"), "https://discord.com"),
        Entry("Twitter", listOf("twitter", "推特", "x", "叉"), listOf("com.twitter.android"), "https://x.com"),
        Entry("Instagram", listOf("instagram", "ins", "ig"), listOf("com.instagram.android"), "https://www.instagram.com"),
        Entry("Facebook", listOf("facebook", "脸书", "fb"), listOf("com.facebook.katana"), "https://m.facebook.com"),
        Entry("WhatsApp", listOf("whatsapp", "wa"), listOf("com.whatsapp"), "https://web.whatsapp.com"),
        Entry("Reddit", listOf("reddit", "红迪"), listOf("com.reddit.frontpage"), "https://www.reddit.com"),
        Entry("GitHub", listOf("github"), listOf("com.github.android"), "https://github.com"),
        Entry("酷安", listOf("酷安"), listOf("com.coolapk.market"), "https://www.coolapk.com"),
        Entry("钉钉", listOf("钉钉", "dingtalk"), listOf("com.alibaba.android.rimet"), "https://www.dingtalk.com"),
        Entry("企业微信", listOf("企业微信", "企微"), listOf("com.tencent.wework"), "https://work.weixin.qq.com"),
        Entry("飞书", listOf("飞书", "lark"), listOf("com.ss.android.lark"), "https://www.feishu.cn"),
        Entry("腾讯会议", listOf("腾讯会议", "会议"), listOf("com.tencent.wemeet.app"), "https://meeting.tencent.com"),
        Entry("美团", listOf("美团", "某团"), listOf("com.sankuai.meituan"), "https://i.meituan.com"),
        Entry("饿了么", listOf("饿了么", "饿了吗"), listOf("me.ele"), "https://h5.ele.me"),
        Entry("滴滴", listOf("滴滴", "滴滴出行"), listOf("com.sdu.didi.psnger"), "https://m.didiglobal.com"),
        Entry("高德地图", listOf("高德", "高德地图", "导航"), listOf("com.autonavi.minimap"), "https://m.amap.com"),
        Entry("百度地图", listOf("百度地图"), listOf("com.baidu.BaiduMap"), "https://map.baidu.com"),
        Entry("腾讯地图", listOf("腾讯地图"), listOf("com.tencent.map"), "https://map.qq.com"),
        Entry("谷歌地图", listOf("谷歌地图", "googlemap", "maps"), listOf("com.google.android.apps.maps"), "https://maps.google.com"),
        Entry("腾讯视频", listOf("腾讯视频"), listOf("com.tencent.qqlive"), "https://m.v.qq.com"),
        Entry("爱奇艺", listOf("爱奇艺", "爱奇异"), listOf("com.qiyi.video"), "https://m.iqiyi.com"),
        Entry("优酷", listOf("优酷", "土豆"), listOf("com.youku.phone"), "https://www.youku.com"),
        Entry("芒果TV", listOf("芒果", "芒果tv"), listOf("com.hunantv.imgo.activity"), "https://m.mgtv.com"),
        Entry("西瓜视频", listOf("西瓜", "西瓜视频"), listOf("com.ss.android.article.video"), "https://m.ixigua.com"),
        Entry("原神", listOf("原神", "原"), listOf("com.miHoYo.Yuanshen", "com.miHoYo.ys"), ""),
        Entry("王者荣耀", listOf("王者", "王者荣耀", "农药"), listOf("com.tencent.tmgp.sgame"), ""),
        Entry("今日头条", listOf("头条", "今日头条"), listOf("com.ss.android.article.news"), "https://m.toutiao.com"),
        Entry("番茄小说", listOf("番茄", "番茄小说"), listOf("com.dragon.read"), ""),
        Entry("喜马拉雅", listOf("喜马拉雅", "喜马"), listOf("com.ximalaya.ting.android"), "https://m.ximalaya.com"),
        Entry("小宇宙", listOf("小宇宙"), listOf("app.podcast.cosmos"), ""),
        Entry("剪映", listOf("剪映"), listOf("com.lemon.lv"), ""),
        Entry("12306", listOf("12306", "铁路", "火车票"), listOf("com.MobileTicket"), "https://www.12306.cn"),
        Entry("应用商店", listOf("应用商店", "锤子商店", "应用市场"), listOf("com.smartisanos.appstore"), ""),
        Entry("设置", listOf("设置", "系统设置"), listOf("com.android.settings"), ""),
        Entry("相机", listOf("相机", "拍照"), listOf("com.android.camera2", "com.smartisanos.camera"), ""),
        Entry("相册", listOf("相册", "图库", "照片"), listOf("com.android.gallery3d", "com.smartisanos.gallery"), ""),
        Entry("锤柄", listOf("锤柄", "锤子补丁", "chuibing", "tntwindow"), listOf("id.tntwindow.editor"), ""),
        Entry("MT管理器", listOf("mt管理器", "mtmanager", "mt manager", "mt杠两千", "杠两千", "mtplus", "mt加", "mt+", "binmt", "mt", "手机管理器", "手机管理", "emt管理器"), listOf("bin.mt.plus.canary", "bin.mt.plus"), ""),
        Entry("文件管理", listOf("文件", "文件管理", "文件管理器"), listOf("com.smartisanos.filemanager"), ""),
        Entry("Termux", listOf("termux", "终端"), listOf("com.termux"), ""),
        Entry("计算器", listOf("计算器", "计算"), listOf("com.android.calculator2"), ""),
        Entry("时钟", listOf("时钟", "闹钟"), listOf("com.android.deskclock"), ""),
        Entry("天气", listOf("天气", "天气预报", "气象"), listOf("com.smartisanos.weather", "com.android.weather"), ""),
        Entry("便签", listOf("便签", "备忘录", "笔记"), listOf("com.smartisanos.notes"), ""),
        Entry("日历", listOf("日历", "日程"), listOf("com.android.calendar"), ""),
        Entry("短信", listOf("短信", "信息"), listOf("com.android.mms"), ""),
        Entry("电话", listOf("电话", "拨号"), listOf("com.android.dialer", "com.android.contacts"), ""),
        Entry("Gmail", listOf("gmail", "谷歌邮箱"), listOf("com.google.android.gm"), "https://mail.google.com"),
        Entry("Outlook", listOf("outlook", "微软邮箱"), listOf("com.microsoft.office.outlook"), "https://outlook.live.com"),
        Entry("Steam", listOf("steam"), listOf("com.valvesoftware.android.steam.community"), "https://store.steampowered.com"),
        Entry("Clash", listOf("clash", "代理"), listOf("com.github.kr328.clash", "com.github.metacubex.clash.meta"), ""),
        Entry("Edge", listOf("edge", "微软浏览器"), listOf("com.microsoft.emmx"), "https://www.bing.com"),
    )

    fun match(query: String): Entry? {
        val q = query.trim().lowercase().replace(" ", "")
        if (q.isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0
        var second = 0
        for (e in builtin) {
            var local = 0
            for (k in e.keys + e.label) {
                local = maxOf(local, score(k, q))
            }
            if (local > bestScore) {
                second = bestScore
                bestScore = local
                best = e
            } else if (local > second) {
                second = local
            }
        }
        if (best == null) return null
        if (bestScore >= 8500) return best
        if (bestScore >= 5000 && bestScore - second >= 800) return best
        return null
    }

    fun keysFor(packageName: String): List<String> {
        val out = ArrayList<String>()
        for (e in builtin) if (packageName in e.packages) out.addAll(e.keys + e.label)
        return out
    }

    private val weak = setOf("音乐", "视频", "播放器", "浏览器", "应用", "软件", "播放", "相册", "相机", "设置", "文件")

    private fun score(name: String, query: String): Int {
        val nn = name.lowercase().replace(" ", "")
        val q = query.lowercase().replace(" ", "")
        if (nn.isEmpty() || q.isEmpty()) return 0
        if (nn == q) return 10000 + nn.length * 20
        if (nn.length < 2 || q.length < 2) return 0
        if (nn in weak && nn != q) return 0
        return when {
            q.startsWith(nn) -> 7000 + nn.length * 30
            nn.startsWith(q) -> 8500 + q.length * 10
            q.contains(nn) -> 3000 + nn.length * 40
            nn.contains(q) -> 2000 + q.length * 20
            else -> 0
        }
    }
}