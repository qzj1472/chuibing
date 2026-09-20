package id.tntwindow.editor.xposed

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.tntwindow.editor.Feel
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.GestureConfig
import id.tntwindow.editor.domain.GestureEdge
import id.tntwindow.editor.domain.GestureLabels
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.Paths
import java.io.File

class GestureModule : IXposedHookLoadPackage {
    @Volatile private var cfg = GestureConfig.default()
    @Volatile private var cfgAt = -1L
    @Volatile private var cfgText = ""
    private var lastSig = ""
    @Volatile private var policy: Any? = null
    private var handler: Handler? = null
    private var wm: WindowManager? = null
    private var app: Context? = null
    private val views = HashMap<String, EdgeStrip>()
    private var watching = false
    private var previewShown = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg != "android" && pkg != "system") return
        val names = listOf(
            "com.android.server.policy.PhoneWindowManagerSMT",
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.OemPhoneWindowManager",
            "com.android.server.policy.SmartisanPhoneWindowManager",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, lpparam.classLoader)
            } catch (_: Throwable) {
                continue
            }
            hookReady(cls)
        }
    }

    private fun hookReady(cls: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    policy = param.thisObject
                    ensure(param.thisObject)
                } catch (t: Throwable) {
                    XposedBridge.log("chuibing gesture ready fail " + t.javaClass.simpleName)
                }
            }
        }
        for (name in listOf("systemReady", "systemBooted")) {
            try {
                XposedHelpers.findAndHookMethod(cls, name, hook)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findAndHookMethod(cls, name, Boolean::class.javaPrimitiveType, hook)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            for (c in cls.declaredConstructors) {
                XposedBridge.hookMethod(c, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        policy = param.thisObject
                    }
                })
            }
        } catch (_: Throwable) {
        }
        hookRecents(cls)
    }

    private fun hookRecents(cls: Class<*>) {
        val raise = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handler?.post { raiseViews() }
                handler?.postDelayed({ raiseViews() }, 90)
                handler?.postDelayed({ raiseViews() }, 280)
            }
        }
        for (name in listOf("showRecentApps", "toggleRecentApps", "hideRecentApps", "preloadRecentApps")) {
            try {
                XposedHelpers.findAndHookMethod(cls, name, raise)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(cls, name, Boolean::class.javaPrimitiveType, raise)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(cls, name, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, raise)
            } catch (_: Throwable) {
            }
        }
    }

    private fun raiseViews() {
        val manager = wm ?: return
        for (id in views.keys.toList()) {
            val v = views[id] ?: continue
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            try {
                manager.removeView(v)
                manager.addView(v, lp)
            } catch (_: Throwable) {
                try {
                    manager.updateViewLayout(v, lp)
                } catch (_: Throwable) {
                }
            }
        }
        FreeformModule.raiseBar()
    }

    private fun ensure(pwm: Any) {
        if (handler == null) handler = Handler(Looper.getMainLooper())
        if (app == null) app = context(pwm)
        val ctx = app ?: return
        if (wm == null) {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        if (!watching) {
            watching = true
            handler?.post(object : Runnable {
                override fun run() {
                    apply()
                    handler?.postDelayed(this, 200L)
                }
            })
        } else {
            handler?.post { apply() }
        }
    }

    private fun apply() {
        reload()
        val ctx = app ?: return
        val manager = wm ?: return
        val preview = previewOn()
        val blocked = ScreenGate.locked(ctx, policy) && !cfg.lockscreen
        val want = if (cfg.enabled && !blocked) {
            cfg.overlayIds().filter { cfg.edge(it)?.enabled == true }
        } else {
            emptyList()
        }
        val sig = cfg.enabled.toString() + "|" + want.joinToString(",") + "|" + cfg.thickness + "|" + preview + "|" + blocked
        if (views.keys.toSet() != want.toSet() || previewShown != preview || sig != lastSig) {
            lastSig = sig
            for (id in views.keys.toList()) remove(manager, id)
            previewShown = preview
            for (id in want) {
                val edge = cfg.edge(id) ?: continue
                add(ctx, manager, edge, preview)
            }
            FreeformModule.raiseBar()
            return
        }
        for (id in want) {
            val edge = cfg.edge(id) ?: continue
            val cur = views[id] ?: continue
            cur.bind(cfg, edge, preview)
            val lp = params(ctx, id, preview) ?: continue
            if (!sameLayout(cur, lp)) {
                try {
                    manager.updateViewLayout(cur, lp)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun previewOn(): Boolean {
        val files = listOf(File(Paths.GESTURE_HIGHLIGHT_SYS), File(Paths.GESTURE_HIGHLIGHT))
        val f = files.filter {
            try {
                it.exists() && it.length() > 0L
            } catch (_: Throwable) {
                false
            }
        }.maxByOrNull {
            try {
                it.lastModified()
            } catch (_: Throwable) {
                0L
            }
        } ?: return false
        return try {
            f.readText(Charsets.UTF_8).trim() == "1"
        } catch (_: Throwable) {
            false
        }
    }

    private fun add(ctx: Context, manager: WindowManager, edge: GestureEdge, preview: Boolean) {
        val strip = EdgeStrip(ctx, edge.id)
        strip.bind(cfg, edge, preview)
        val lp = params(ctx, edge.id, preview) ?: return
        val types = intArrayOf(2024, 2032, 2027, 2015, 2010, 2003, 2002)
        var added = false
        for (type in types) {
            lp.type = type
            try {
                manager.addView(strip, lp)
                views[edge.id] = strip
                added = true
                XposedBridge.log("chuibing gesture add " + edge.id + " type=" + lp.type + " w=" + lp.width + " h=" + lp.height + " x=" + lp.x + " y=" + lp.y)
                break
            } catch (err: Throwable) {
                XposedBridge.log("chuibing gesture add fail " + edge.id + " type=" + type + " " + err.javaClass.simpleName)
            }
        }
        if (!added) {
            XposedBridge.log("chuibing gesture add fail2 " + edge.id)
        }
    }

    private fun remove(manager: WindowManager, id: String) {
        val v = views.remove(id) ?: return
        try {
            manager.removeView(v)
        } catch (_: Throwable) {
            try {
                manager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
    }

    private fun screenSize(ctx: Context): Point {
        val p = Point()
        try {
            wm?.defaultDisplay?.getRealSize(p)
        } catch (_: Throwable) {
        }
        if (p.x <= 0 || p.y <= 0) {
            val dm = ctx.resources.displayMetrics
            p.x = dm.widthPixels
            p.y = dm.heightPixels
        }
        return p
    }

    private fun params(ctx: Context, id: String, preview: Boolean): WindowManager.LayoutParams? {
        val thick = cfg.thickness.coerceIn(12, 80)
        val size = screenSize(ctx)
        val lp = WindowManager.LayoutParams()
        lp.type = 2024
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.packageName = "android"
        lp.title = "chuibing-edge-" + id
        lp.alpha = 1f
        val thirdH = size.y / 3
        val thirdW = size.x / 3
        val botH = size.y - thirdH * 2
        val rightW = size.x - thirdW * 2
        when (id) {
            "left" -> fill(lp, Gravity.LEFT or Gravity.TOP, thick, size.y, 0, 0)
            "right" -> fill(lp, Gravity.RIGHT or Gravity.TOP, thick, size.y, 0, 0)
            "bottom" -> fill(lp, Gravity.BOTTOM or Gravity.LEFT, size.x, thick, 0, 0)
            "left_top" -> fill(lp, Gravity.LEFT or Gravity.TOP, thick, thirdH, 0, 0)
            "left_mid" -> fill(lp, Gravity.LEFT or Gravity.TOP, thick, thirdH, 0, thirdH)
            "left_bot" -> fill(lp, Gravity.LEFT or Gravity.TOP, thick, botH, 0, thirdH * 2)
            "right_top" -> fill(lp, Gravity.RIGHT or Gravity.TOP, thick, thirdH, 0, 0)
            "right_mid" -> fill(lp, Gravity.RIGHT or Gravity.TOP, thick, thirdH, 0, thirdH)
            "right_bot" -> fill(lp, Gravity.RIGHT or Gravity.TOP, thick, botH, 0, thirdH * 2)
            "bottom_left" -> fill(lp, Gravity.BOTTOM or Gravity.LEFT, thirdW, thick, 0, 0)
            "bottom_mid" -> fill(lp, Gravity.BOTTOM or Gravity.LEFT, thirdW, thick, thirdW, 0)
            "bottom_right" -> fill(lp, Gravity.BOTTOM or Gravity.LEFT, rightW, thick, thirdW * 2, 0)
            else -> return null
        }
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT && lp.height == WindowManager.LayoutParams.MATCH_PARENT) return null
        if (lp.width == 0 || lp.height == 0) return null
        return lp
    }

    private fun fill(lp: WindowManager.LayoutParams, gravity: Int, w: Int, h: Int, x: Int, y: Int) {
        lp.gravity = gravity
        lp.width = w
        lp.height = h
        lp.x = x
        lp.y = y
    }

    private fun sameLayout(view: View, lp: WindowManager.LayoutParams): Boolean {

        val old = view.layoutParams as? WindowManager.LayoutParams ?: return false
        return old.width == lp.width &&
            old.height == lp.height &&
            old.x == lp.x &&
            old.y == lp.y &&
            old.gravity == lp.gravity &&
            old.type == lp.type
    }

    private fun reload() {
        val files = listOf(File(Paths.GESTURE), File("/data/local/tmp/tnt_gesture_config.json"))
        val f = files.filter {
            try {
                it.exists() && it.length() > 0L
            } catch (_: Throwable) {
                false
            }
        }.maxByOrNull {
            try {
                it.lastModified()
            } catch (_: Throwable) {
                0L
            }
        }
        if (f == null) {
            cfg = GestureConfig.default()
            cfgAt = 0L
            cfgText = ""
            return
        }
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        if (text == cfgText) return
        try {
            if (text.isNotBlank()) {
                cfg = GestureConfig.fromJson(text)
                cfgText = text
                cfgAt = try { f.lastModified() } catch (_: Throwable) { 0L }
            }
        } catch (_: Throwable) {
        }
    }

    private fun swipePx(): Float {
        val d = try {
            app?.resources?.displayMetrics?.density ?: 2.5f
        } catch (_: Throwable) {
            2.5f
        }
        return (8f * d).coerceIn(18f, 28f)
    }

    private fun fire(edge: GestureEdge, slot: String, view: View?) {
        val action = edge.action(slot)
        if (action.isNone() || action.isDefault()) return
        val pwm = policy ?: return
        val ctx = app ?: context(pwm) ?: return
        if (ScreenGate.locked(ctx, pwm) && !cfg.lockscreen) return
        if (cfg.vibrate) vibrate(ctx, view, pwm)
        KeyMapActions.run(pwm, ctx, action)
    }

    private fun vibrate(context: Context, view: View?, pwm: Any) {
        Feel.vibrate(context, cfg.vibrateMs, cfg.vibrateAmp, view, pwm)
    }

    private fun context(pwm: Any): Context? {
        return try {
            XposedHelpers.getObjectField(pwm, "mContext") as Context
        } catch (_: Throwable) {
            try {
                val at = XposedHelpers.findClass("android.app.ActivityThread", null)
                val inst = at.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
                XposedHelpers.callMethod(inst, "getSystemContext") as? Context
            } catch (_: Throwable) {
                null
            }
        }
    }

    private inner class EdgeStrip(context: Context, val edgeId: String) : View(context) {
        private var edge = GestureEdge(edgeId)
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false
        private var longFired = false
        private var swipeFired = false
        private var hoverFired = false
        private var hoverX = 0f
        private var hoverY = 0f
        private var swipeKind: String? = null
        private var clicks = 0
        private var longTask: Runnable? = null
        private var clickTask: Runnable? = null
        private var hoverTask: Runnable? = null

        init {
            setBackgroundColor(0x01000000)
            isClickable = true
            isFocusable = false
            isHapticFeedbackEnabled = true
        }

        fun bind(config: GestureConfig, next: GestureEdge, preview: Boolean) {
            edge = next
            cfg = config
            val color = when {
                !preview -> 0x01000000
                edgeId == "left" || edgeId == "right" || edgeId == "bottom" -> 0xCC7EB6FF.toInt()
                else -> 0xCC5DCCA0.toInt()
            }
            background = ColorDrawable(color)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val h = handler ?: return true
            if (ScreenGate.locked(context, policy) && !cfg.lockscreen) {
                longTask?.let { h.removeCallbacks(it) }
                longTask = null
                clickTask?.let { h.removeCallbacks(it) }
                clickTask = null
                cancelHover(h)
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    longFired = false
                    swipeFired = false
                    hoverFired = false
                    swipeKind = null
                    clickTask?.let { h.removeCallbacks(it) }
                    clickTask = null
                    longTask?.let { h.removeCallbacks(it) }
                    cancelHover(h)
                    val task = Runnable {
                        if (!moved && !longFired && !swipeFired) {
                            longFired = true
                            clicks = 0
                            fire(edge, "long", this)
                        }
                    }
                    longTask = task
                    h.postDelayed(task, cfg.longMs.toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longFired) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val adx = kotlin.math.abs(dx)
                    val ady = kotlin.math.abs(dy)
                    val th = swipePx()
                    if (adx > th || ady > th) {
                        moved = true
                        longTask?.let { h.removeCallbacks(it) }
                        longTask = null
                        val slot = swipeSlot(dx, dy, adx, ady)
                        if (slot != null) {
                            clicks = 0
                            if (swipeKind != slot) {
                                swipeKind = slot
                                if (hasHover(slot) && !hoverFired) armHover(h, slot, event.rawX, event.rawY) else cancelHover(h)
                            } else if (hasHover(slot) && !hoverFired) {
                                trackHover(h, event.rawX, event.rawY)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longTask?.let { h.removeCallbacks(it) }
                    longTask = null
                    cancelHover(h)
                    if (longFired) {
                        clicks = 0
                        return true
                    }
                    if (moved) {
                        clicks = 0
                        if (hoverFired) return true
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val sdx = event.rawX - downX
                            val sdy = event.rawY - downY
                            val slot = swipeKind ?: swipeSlot(sdx, sdy, kotlin.math.abs(sdx), kotlin.math.abs(sdy))
                            if (slot != null) fire(edge, slot, this)
                        }
                        return true
                    }
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        clicks = 0
                        return true
                    }
                    clicks += 1
                    if (clicks >= 2) {
                        clicks = 0
                        fire(edge, "double", this)
                        return true
                    }
                    val task = Runnable {
                        if (clicks == 1) fire(edge, "click", this)
                        clicks = 0
                    }
                    clickTask = task
                    h.postDelayed(task, cfg.doubleMs.toLong())
                }
            }
            return true
        }

        private fun swipeSlot(dx: Float, dy: Float, adx: Float, ady: Float): String? {
            val horiz = adx >= ady
            return when (GestureLabels.swipeSide(edgeId)) {
                "left" -> when {
                    horiz && dx > 0f -> "swipe_in"
                    !horiz && dy < 0f -> "swipe_a"
                    !horiz && dy > 0f -> "swipe_b"
                    else -> null
                }
                "right" -> when {
                    horiz && dx < 0f -> "swipe_in"
                    !horiz && dy < 0f -> "swipe_a"
                    !horiz && dy > 0f -> "swipe_b"
                    else -> null
                }
                else -> when {
                    !horiz && dy < 0f -> "swipe_in"
                    horiz && dx < 0f -> "swipe_a"
                    horiz && dx > 0f -> "swipe_b"
                    else -> null
                }
            }
        }

        private fun hasHover(slot: String): Boolean {
            val hoverSlot = GestureLabels.hoverSlot(slot) ?: return false
            val act = edge.action(hoverSlot)
            return !act.isNone() && !act.isDefault()
        }

        private fun cancelHover(h: Handler) {
            hoverTask?.let { h.removeCallbacks(it) }
            hoverTask = null
        }

        private fun armHover(h: Handler, slot: String, x: Float, y: Float) {
            swipeKind = slot
            cancelHover(h)
            val hoverSlot = GestureLabels.hoverSlot(slot) ?: return
            val act = edge.action(hoverSlot)
            if (act.isNone() || act.isDefault()) return
            hoverX = x
            hoverY = y
            hoverFired = false
            val task = Runnable {
                if (!hoverFired && !longFired) {
                    hoverFired = true
                    fire(edge, hoverSlot, this)
                }
            }
            hoverTask = task
            h.postDelayed(task, cfg.hoverMs.toLong())
        }

        private fun trackHover(h: Handler, x: Float, y: Float) {
            if (hoverFired) return
            val kind = swipeKind ?: return
            val lim = cfg.hoverPx.toFloat()
            if (kotlin.math.abs(x - hoverX) <= lim && kotlin.math.abs(y - hoverY) <= lim) return
            armHover(h, kind, x, y)
        }
    }
}


