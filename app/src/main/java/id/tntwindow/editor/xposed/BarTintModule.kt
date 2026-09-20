package id.tntwindow.editor.xposed

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.BarTintConfig
import id.tntwindow.editor.domain.Paths
import java.io.File
import java.lang.ref.WeakReference

class BarTintModule : IXposedHookLoadPackage {
    private val bars = ArrayList<WeakReference<View>>()
    private var handler: Handler? = null
    private var looping = false
    private val lastDarkOf = HashMap<Int, Boolean>()
    private val lastLumaOf = HashMap<Int, Float>()
    private val lastTintAt = HashMap<Int, Long>()
    private var lastRot = -1
    private var lastSw = 0
    private var lastSh = 0
    private var lastClearAt = 0L
    private var lastSampleAt = 0L
    private var lastReloadAt = 0L
    private var scheduled = false
    @Volatile private var applying = false
    @Volatile private var cfg = BarTintConfig.default()
    @Volatile private var cfgAt = -1L
    @Volatile private var cfgText = ""
    @Volatile private var recentsOpen = false
    @Volatile private var shadeOpen = false
    @Volatile private var transitioning = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg != "com.android.systemui" && pkg != "com.android.desktop.systemui") return
        val cl = lpparam.classLoader
        val prefix = if (pkg == "com.android.desktop.systemui") "com.android.desktop.systemui" else "com.android.systemui"
        hookView(cl, prefix + ".statusbar.phone.NavigationBarView")
        hookView(cl, prefix + ".statusbar.phone.PhoneStatusBarView")
        hookView(cl, prefix + ".statusbar.phone.KeyguardStatusBarView")
        hookBgDraw(cl, prefix + ".statusbar.phone." + "BarTransitions" + '$' + "BarBackgroundDrawable")
        hookNamed(cl, listOf(
            prefix + ".statusbar.phone.NavigationBarView",
            prefix + ".statusbar.phone.PhoneStatusBarView",
            prefix + ".statusbar.phone.LightBarController",
            prefix + ".statusbar.phone.LightBarTransitionsController",
            prefix + ".statusbar.phone.StatusBar",
            prefix + ".statusbar.phone.PhoneStatusBar",
            prefix + ".statusbar.policy.DarkIconDispatcherImpl",
            prefix + ".statusbar.StatusBarIconView",
            "com.android.systemui.statusbar.StatusBarIconView",
        ))
        hookMaker(cl, prefix + ".statusbar.phone.StatusBar")
        hookMaker(cl, prefix + ".statusbar.phone.PhoneStatusBar")
        hookOverlay(cl, prefix)
    }

    private fun hookView(cl: ClassLoader, name: String) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        for (m in cls.declaredMethods) {
            if (m.name != "onFinishInflate" && m.name != "onAttachedToWindow" && m.name != "onLayout") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        track(v)
                        if (isNav(v)) clearBar(v)
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookMaker(cl: ClassLoader, name: String) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        for (m in cls.declaredMethods) {
            val n = m.name
            if (!n.contains("StatusBarView") && n != "makeStatusBarView" && n != "getStatusBarView") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.result as? View ?: return
                        track(v)
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookBgDraw(cl: ClassLoader, name: String) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "draw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun hookNamed(cl: ClassLoader, names: List<String>) {
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!applying) scheduleApply(true)
            }
        }
        val force = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                val v = param.thisObject as? View
                if (n == "setNavigationBarDark" && !cfg.nav) return
                val dark = when {
                    n == "setIconsDark" || n == "setStatusBarDark" || n == "setIconTint" -> statusDark()
                    n == "setNavigationBarDark" -> navDark()
                    v != null -> lastDarkOf[System.identityHashCode(v)]
                    else -> return
                } ?: return
                val a0 = param.args.getOrNull(0) ?: return
                if (a0 is Float) param.args[0] = if (dark) 1f else 0f
                else if (a0 is java.lang.Float) param.args[0] = if (dark) 1f else 0f
                else if (a0 is Boolean) param.args[0] = dark
                else if (a0 is java.lang.Boolean) param.args[0] = dark
            }
        }
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                val n = m.name
                try {
                    if (n == "updateLightNavigationBar" || n == "updateNavigationBarBackGround" || n == "updateStatus" || n == "onColorsChanged" || n == "updateNotificationIcons" || n == "updateNotificationShade" || n == "setNotificationsInactive" || n == "updateNotificationViews") {
                        XposedBridge.hookMethod(m, after)
                    } else if (n == "setDarkIntensity" || n == "setIconDark" || n == "setIconsDark" || n == "setStatusBarDark" || n == "setNavigationBarDark" || n == "applyDarkIntensity" || n == "setIconTint") {
                        XposedBridge.hookMethod(m, force)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun hookOverlay(cl: ClassLoader, prefix: String) {
        hookLife(cl, prefix + ".recents.RecentsActivity")
        hookLife(cl, "com.android.systemui.recents.RecentsActivity")
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                when {
                    n.contains("appTransitionStarting") -> transitioning = true
                    n.contains("appTransitionFinished") || n.contains("appTransitionCancelled") -> {
                        transitioning = false
                        scheduleApply(true)
                    }
                    n.contains("showRecent") -> recentsOpen = true
                    n.contains("hideRecent") -> {
                        recentsOpen = false
                        scheduleApply(true)
                    }
                }
            }
        }
        for (name in listOf(prefix + ".statusbar.phone.StatusBar", prefix + ".statusbar.phone.PhoneStatusBar")) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                val n = m.name
                if (!(n.contains("appTransition") || n.contains("Recent"))) continue
                try {
                    XposedBridge.hookMethod(m, after)
                } catch (_: Throwable) {
                }
            }
        }
        val shade = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                val a0 = param.args.getOrNull(0) ?: return
                val v = when (a0) {
                    is Float -> a0
                    is Number -> a0.toFloat()
                    else -> return
                }
                val open = if (n.contains("Fraction")) v > 0.02f else v > 2f
                if (shadeOpen == open) return
                shadeOpen = open
                if (!open) scheduleApply(true)
            }
        }
        for (name in listOf(prefix + ".statusbar.phone.NotificationPanelView", prefix + ".statusbar.phone.PanelView")) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                val n = m.name
                if (n != "setExpandedHeight" && n != "setExpandedHeightInternal" && n != "setExpandedFraction") continue
                try {
                    XposedBridge.hookMethod(m, shade)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun hookLife(cl: ClassLoader, name: String) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        val life = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                if (n == "onResume" || n == "onStart") {
                    recentsOpen = true
                    return
                }
                if (n == "onPause" || n == "onStop") {
                    recentsOpen = false
                    scheduleApply(true)
                }
            }
        }
        for (m in cls.declaredMethods) {
            val n = m.name
            if (n != "onResume" && n != "onPause" && n != "onStart" && n != "onStop") continue
            try {
                XposedBridge.hookMethod(m, life)
            } catch (_: Throwable) {
            }
        }
    }

    private fun track(v: View) {
        if (v.javaClass.name.contains("StatusBarWindowView")) return
        if (isNav(v) && !cfg.nav) return
        bars.removeAll { it.get() == null || it.get() === v }
        bars.add(WeakReference(v))
        if (handler == null) handler = Handler(Looper.getMainLooper())
        if (!looping) {
            looping = true
            handler?.post(object : Runnable {
                override fun run() {
                    applyAll(false)
                    handler?.postDelayed(this, 180L)
                }
            })
        }
    }

    private fun samplingPaused(): Boolean = recentsOpen || shadeOpen || transitioning

    private fun scheduleApply(force: Boolean) {
        val h = handler ?: Handler(Looper.getMainLooper()).also { handler = it }
        if (force) {
            h.post { applyAll(true) }
            return
        }
        if (samplingPaused()) return
        if (scheduled) return
        scheduled = true
        h.post {
            scheduled = false
            applyAll(false)
        }
    }

    private fun applyAll(force: Boolean) {
        if (applying) return
        applying = true
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastReloadAt >= 800L || force) {
                reload()
                lastReloadAt = now
            }
            val live = bars.mapNotNull { it.get() }
            if (live.isEmpty()) return
            if (!force && samplingPaused()) return
            if (!force && now - lastSampleAt < 160L) return
            lastSampleAt = now
            val clearNav = force || now - lastClearAt >= 2000L
            if (clearNav) lastClearAt = now
            for (v in live) {
                if (isNav(v)) {
                    if (!cfg.nav) continue
                    if (clearNav) clearBar(v)
                }
                val id = System.identityHashCode(v)
                val sampled = sampleDark(v, id)
                if (sampled == null) continue
                val last = lastDarkOf[id]
                val tintedAt = lastTintAt[id] ?: 0L
                if (last == sampled && !force && now - tintedAt < 480L) continue
                lastDarkOf[id] = sampled
                lastTintAt[id] = now
                tint(v, sampled)
            }
        } finally {
            applying = false
        }
    }

    private fun isNav(v: View): Boolean {
        val n = v.javaClass.name
        return n.contains("NavigationBar")
    }

    private fun isStatus(v: View): Boolean {
        val n = v.javaClass.simpleName
        return n == "PhoneStatusBarView" || n == "KeyguardStatusBarView"
    }

    private fun statusDark(): Boolean? {
        return bars.mapNotNull { it.get() }.firstOrNull { isStatus(it) }?.let { lastDarkOf[System.identityHashCode(it)] }
    }

    private fun navDark(): Boolean? {
        return bars.mapNotNull { it.get() }.firstOrNull { isNav(it) }?.let { lastDarkOf[System.identityHashCode(it)] }
    }

    private fun sampleDark(v: View, id: Int): Boolean? {
        val loc = IntArray(2)
        try {
            v.getLocationOnScreen(loc)
        } catch (_: Throwable) {
            return lastDarkOf[id]
        }
        val w = v.width
        val h = v.height
        if (w < 8 || h < 8) return lastDarkOf[id]
        val size = Point()
        val display = try { v.display } catch (_: Throwable) { null }
        val rot = try {
            display?.getRealSize(size)
            display?.rotation ?: 0
        } catch (_: Throwable) {
            val dm = v.resources.displayMetrics
            size.x = dm.widthPixels
            size.y = dm.heightPixels
            0
        }
        val dm = v.resources.displayMetrics
        val sw = if (size.x > 0) size.x else dm.widthPixels
        val sh = if (size.y > 0) size.y else dm.heightPixels
        val rotated = rot != lastRot || sw != lastSw || sh != lastSh
        if (rotated) {
            lastRot = rot
            lastSw = sw
            lastSh = sh
            lastDarkOf.clear()
            lastLumaOf.clear()
            lastTintAt.clear()
        }
        if (!isNav(v) && !isStatus(v)) return lastDarkOf[id]
        val rect = adjacentRect(v, loc, w, h, sw, sh) ?: return lastDarkOf[id]
        var luma = ScreenLuma.ofBand(rect, sw, sh, rot, display)
        if (luma < 12f) luma = ScreenLuma.of(rect, rot, display)
        if (rotated) {
            val did = try { display?.displayId ?: -1 } catch (_: Throwable) { -1 }
            try {
                XposedBridge.log("tnt-bar sample display=" + did + " rot=" + rot + " luma=" + luma + " " + sw + "x" + sh)
            } catch (_: Throwable) {
            }
        }
        if (luma < 12f) return if (rotated) ScreenLuma.darkIcons(200f) else lastDarkOf[id]
        lastLumaOf[id] = luma
        val last = lastDarkOf[id]
        return when {
            last == null || rotated -> ScreenLuma.darkIcons(luma)
            last -> luma >= 118f
            else -> luma >= 148f
        }
    }

    private fun adjacentRect(v: View, loc: IntArray, w: Int, h: Int, sw: Int, sh: Int): Rect? {
        val left = loc[0]
        val top = loc[1]
        val right = left + w
        val bottom = top + h
        val band = if (sw > sh) 48 else 36
        val rect = when {
            isNav(v) && top > sh / 2 -> Rect(
                left.coerceAtLeast(0),
                (top - band).coerceAtLeast(0),
                right.coerceAtMost(sw),
                top.coerceAtLeast(8),
            )
            isNav(v) && left > sw / 2 -> Rect(
                (left - band).coerceAtLeast(0),
                top.coerceAtLeast(0),
                left.coerceAtLeast(8),
                bottom.coerceAtMost(sh),
            )
            isNav(v) && right < sw / 2 -> Rect(
                right.coerceAtLeast(0),
                top.coerceAtLeast(0),
                (right + band).coerceAtMost(sw),
                bottom.coerceAtMost(sh),
            )
            else -> Rect(
                left.coerceAtLeast(0),
                bottom.coerceAtMost((sh - 8).coerceAtLeast(0)),
                right.coerceAtMost(sw),
                (bottom + band).coerceAtMost(sh),
            )
        }
        return if (rect.width() >= 8 && rect.height() >= 8) rect else null
    }

    private fun reload() {
        val files = listOf(File(Paths.BARTINT), File("/data/local/tmp/tnt_bartint_config.json"))
        val readable = files.mapNotNull { f ->
            val text = try {
                if (f.exists() && f.length() > 0L) f.readText(Charsets.UTF_8) else return@mapNotNull null
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            if (text.isBlank()) return@mapNotNull null
            val at = try { f.lastModified() } catch (_: Throwable) { 0L }
            Triple(f, text, at)
        }
        val hit = readable.maxByOrNull { it.third } ?: return
        if (hit.second == cfgText) return
        if (cfgText.isNotBlank() && cfgAt > 0L && hit.third <= cfgAt) return
        try {
            cfg = BarTintConfig.fromJson(hit.second)
            cfgText = hit.second
            cfgAt = hit.third
        } catch (_: Throwable) {
        }
    }

    private fun clearBar(v: View) {
        try {
            v.setBackgroundColor(0)
            v.background = null
        } catch (_: Throwable) {
        }
        hideField(v, "mNaviBarBg")
        hideField(v, "mBarBackground")
        hideField(v, "mBackgroundView")
        hideNamed(v)
        try {
            XposedHelpers.callMethod(v, "setNavigationBarBgVisibility", 8)
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(v, "setNavigationBarBgVisibility", false)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideField(obj: Any, name: String) {
        val v = try {
            XposedHelpers.getObjectField(obj, name) as? View
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            v.setBackgroundColor(0)
            v.background = null
            v.visibility = View.GONE
        } catch (_: Throwable) {
        }
    }

    private fun hideNamed(root: View) {
        if (root !is ViewGroup) return
        val stack = ArrayList<ViewGroup>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val g = stack.removeAt(stack.lastIndex)
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                val n = c.javaClass.simpleName.lowercase()
                if (n.contains("background") || n.contains("barbg") || n.contains("navibg")) {
                    try {
                        c.setBackgroundColor(0)
                        c.background = null
                    } catch (_: Throwable) {
                    }
                }
                if (c is ViewGroup) stack.add(c)
            }
        }
    }

    private fun tint(v: View, dark: Boolean) {
        val intensity = if (dark) 1f else 0f
        val names = listOf("setDarkIntensity", "setIconDark", "setIconsDark", "setStatusBarDark", "applyDarkIntensity")
        for (n in names) {
            try {
                XposedHelpers.callMethod(v, n, intensity)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(v, n, dark)
                } catch (_: Throwable) {
                }
            }
        }
        tintKids(v, dark, intensity)
    }

    private fun tintKids(root: View, dark: Boolean, intensity: Float) {
        if (root !is ViewGroup) return
        val stack = ArrayList<ViewGroup>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val g = stack.removeAt(stack.lastIndex)
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                try {
                    XposedHelpers.callMethod(c, "setDarkIntensity", intensity)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(c, "setIconDark", dark)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(c, "onDarkChanged", null, intensity, ScreenLuma.contrast(if (dark) 200f else 40f))
                } catch (_: Throwable) {
                }
                val color = ScreenLuma.contrast(if (dark) 200f else 40f)
                if (c is android.widget.ImageView) {
                    try {
                        c.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
                        c.imageAlpha = 255
                    } catch (_: Throwable) {
                    }
                }
                if (c is android.widget.TextView && c.textSize > 0f) {
                    try {
                        c.setTextColor(color)
                    } catch (_: Throwable) {
                    }
                }
                if (c is ViewGroup) stack.add(c)
            }
        }
    }
}