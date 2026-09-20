package id.tntwindow.editor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.FreeformConfig
import id.tntwindow.editor.domain.GestureConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

class FreeformModule : IXposedHookLoadPackage {
    private var handler: Handler? = null
    private var wm: WindowManager? = null
    private var app: Context? = null
    private var watching = false
    private var bar: View? = null
    private var pill: View? = null
    private var panel: View? = null
    private var picker: View? = null
    private var open = false
    private var pickerMode: String? = null
    private var lastKey = ""
    private var animating = false
    private var lastLuma = 40f
    private var lastLumaAt = 0L
    private var requestRecv = false

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
                    FreeformRuntime.policy = param.thisObject
                    ensure(param.thisObject)
                } catch (t: Throwable) {
                    XposedBridge.log("chuibing freeform ready fail " + t.javaClass.simpleName)
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
    }

    private fun ensure(pwm: Any) {
        if (handler == null) handler = Handler(Looper.getMainLooper())
        if (app == null) app = context(pwm)
        val ctx = app ?: return
        FreeformRuntime.context = ctx
        if (wm == null) {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        VoiceHud.ensure(ctx)
        try {
            val warm = Intent()
            warm.setClassName("id.tntwindow.editor", "id.tntwindow.editor.voice.VoiceWarmService")
            ctx.startService(warm)
        } catch (_: Throwable) {
        }
        if (!requestRecv) {
            try {
                val filter = IntentFilter("id.tntwindow.editor.action.FREEFORM_OPEN")
                filter.addAction("id.tntwindow.editor.action.VOICE_COMMIT")
                ctx.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == "id.tntwindow.editor.action.VOICE_COMMIT") {
                            val text = intent.getStringExtra("text") ?: ""
                            if (!VoiceRuntime.ingest(text)) VoiceHud.error(ctx, "没听清")
                            return
                        }
                        val pkg = intent?.getStringExtra("pkg") ?: return
                        val cls = intent.getStringExtra("cls") ?: ""
                        FreeformWindows.open(ctx, pkg, cls)
                    }
                }, filter)
                requestRecv = true
            } catch (_: Throwable) {
            }
        }
        if (!watching) {
            watching = true
            handler?.post(object : Runnable {
                override fun run() {
                    apply()
                    handler?.postDelayed(this, 400L)
                }
            })
        } else {
            handler?.post { apply() }
        }
    }

    fun showSidebar() {
        val run = Runnable {
            if (ScreenGate.locked(app, FreeformRuntime.policy) && !FreeformRuntime.cfg.sidebarLockscreen) return@Runnable
            if (animating) return@Runnable
            if (open && pickerMode == null) return@Runnable
            pickerMode = null
            animating = true
            val ctx = app
            val p = pill
            if (ctx != null && p != null) {
                val left = FreeformRuntime.cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
                val dx = if (left) -dp(ctx, 10).toFloat() else dp(ctx, 10).toFloat()
                p.animate().cancel()
                p.animate().alpha(0f).translationX(dx).setDuration(140).setInterpolator(AccelerateInterpolator()).start()
            }
            open = true
            apply(force = true)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler?.post(run) ?: run.run()
    }

    private fun hideSidebar() {
        if (animating) return
        val pick = picker?.findViewWithTag<View>("chuibing-ff-sheet")
        if (pick != null) {
            animating = true
            pick.animate().cancel()
            pick.pivotX = pick.width / 2f
            pick.pivotY = pick.height / 2f
            pick.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(140).setInterpolator(AccelerateInterpolator()).withEndAction {
                animating = false
                open = false
                pickerMode = null
                apply(force = true)
            }.start()
            return
        }
        val slide = panel?.findViewWithTag<View>("chuibing-ff-sheet")
        if (slide != null && pickerMode == null) {
            animating = true
            val left = FreeformRuntime.cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
            val dx = if (left) -slide.width.toFloat() else slide.width.toFloat()
            slide.animate().cancel()
            slide.animate().translationX(dx).setDuration(160).setInterpolator(AccelerateInterpolator()).withEndAction {
                animating = false
                open = false
                pickerMode = null
                apply(force = true)
            }.start()
            return
        }
        open = false
        pickerMode = null
        apply(force = true)
    }

    private fun showPicker(mode: String) {
        if (animating) return
        val slide = panel?.findViewWithTag<View>("chuibing-ff-sheet")
        if (slide != null) {
            animating = true
            val left = FreeformRuntime.cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
            val dx = if (left) -slide.width.toFloat() else slide.width.toFloat()
            slide.animate().cancel()
            slide.animate().translationX(dx).setDuration(140).setInterpolator(AccelerateInterpolator()).withEndAction {
                open = false
                pickerMode = mode
                apply(force = true)
            }.start()
            return
        }
        open = false
        pickerMode = mode
        animating = true
        apply(force = true)
    }

    private fun apply(force: Boolean = false) {
        FreeformRuntime.reload()
        val ctx = app ?: return
        FreeformWindows.drain(ctx)
        if (animating && !force) {
            val locked = ScreenGate.locked(ctx, FreeformRuntime.policy) && !FreeformRuntime.cfg.sidebarLockscreen
            if (!locked) return
        }
        val manager = wm ?: return
        val cfg = FreeformRuntime.cfg
        val preview = previewOn()
        val blocked = ScreenGate.locked(ctx, FreeformRuntime.policy) && !cfg.sidebarLockscreen
        if (blocked && (open || pickerMode != null)) {
            animating = false
            open = false
            pickerMode = null
        }
        val picking = pickerMode
        val wantBar = cfg.sidebar && picking == null && !open && !blocked
        val size = screenSize(ctx)
        val key = listOf(
            wantBar.toString(),
            blocked.toString(),
            open.toString(),
            picking.orEmpty(),
            preview.toString(),
            cfg.sidebarSide,
            cfg.sidebarTopPct.toString(),
            cfg.apps.joinToString(","),
            thickness().toString(),
            size.x.toString(),
            size.y.toString(),
        ).joinToString("|")
        if (!force && key == lastKey) {
            if (wantBar) colorBar(ctx, cfg, preview)
            return
        }
        lastKey = key
        val thick = thickness()
        val barH = dp(ctx, 88) * 8 / 9
        val top = ((size.y * (cfg.sidebarTopPct / 100f)).toInt()).coerceIn(0, (size.y - barH).coerceAtLeast(0))
        if (wantBar) {
            if (bar == null) addBar(ctx, manager, cfg, size, thick, barH, top, preview)
            else placeBar(ctx, manager, cfg, size, thick, barH, top, preview)
        } else if (!(animating && open && picking == null && bar != null)) {
            drop(manager, bar)
            bar = null
            pill = null
        }
        if (open && picking == null) {
            if (panel == null) addPanel(ctx, manager, cfg, size, thick, top)
        } else {
            drop(manager, panel)
            panel = null
        }
        if (picking != null) {
            if (picker == null) addPicker(ctx, manager, cfg, size, picking)
        } else {
            drop(manager, picker)
            picker = null
        }
    }

    private fun addBar(
        ctx: Context,
        manager: WindowManager,
        cfg: FreeformConfig,
        size: Point,
        thick: Int,
        barH: Int,
        top: Int,
        preview: Boolean,
    ) {
        val wrap = FrameLayout(ctx)
        wrap.isClickable = true
        var downX = 0f
        var downY = 0f
        wrap.setOnTouchListener { _, ev ->
            val th = dp(ctx, 6).toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val left = cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
                    if (left && dx > th) showSidebar()
                    if (!left && dx < -th) showSidebar()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val adx = kotlin.math.abs(ev.rawX - downX)
                    val ady = kotlin.math.abs(ev.rawY - downY)
                    if (adx < th && ady < th) showSidebar()
                    true
                }
                else -> true
            }
        }
        val pillView = View(ctx)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 3).toFloat()
        bg.setColor(pillColor(ctx, cfg, size, thick, barH, top, preview))
        pillView.background = bg
        val pl = FrameLayout.LayoutParams(dp(ctx, 4), barH)
        pl.gravity = Gravity.CENTER
        wrap.addView(pillView, pl)
        pill = pillView
        colorBarView(wrap, preview)
        val lp = baseLp("chuibing-ff-bar")
        lp.type = 2010
        lp.gravity = if (cfg.sidebarSide == FreeformConfig.SIDE_RIGHT) Gravity.RIGHT or Gravity.TOP else Gravity.LEFT or Gravity.TOP
        lp.width = thick
        lp.height = barH
        lp.x = 0
        lp.y = top
        if (!addTyped(manager, wrap, lp)) return
        bar = wrap
        val left = cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
        pillView.alpha = 0f
        pillView.translationX = if (left) -dp(ctx, 10).toFloat() else dp(ctx, 10).toFloat()
        pillView.animate().alpha(1f).translationX(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun placeBar(
        ctx: Context,
        manager: WindowManager,
        cfg: FreeformConfig,
        size: Point,
        thick: Int,
        barH: Int,
        top: Int,
        preview: Boolean,
    ) {
        val v = bar ?: run {
            addBar(ctx, manager, cfg, size, thick, barH, top, preview)
            return
        }
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: run {
            drop(manager, v)
            bar = null
            pill = null
            addBar(ctx, manager, cfg, size, thick, barH, top, preview)
            return
        }
        lp.gravity = if (cfg.sidebarSide == FreeformConfig.SIDE_RIGHT) Gravity.RIGHT or Gravity.TOP else Gravity.LEFT or Gravity.TOP
        lp.width = thick
        lp.height = barH
        lp.x = 0
        lp.y = top
        try {
            manager.updateViewLayout(v, lp)
        } catch (_: Throwable) {
            drop(manager, v)
            bar = null
            pill = null
            addBar(ctx, manager, cfg, size, thick, barH, top, preview)
            return
        }
        val p = pill
        if (p != null) {
            val pl = p.layoutParams as? FrameLayout.LayoutParams
            if (pl != null) {
                pl.height = barH
                p.layoutParams = pl
            }
        }
        colorBar(ctx, cfg, preview)
    }

    private fun addPanel(ctx: Context, manager: WindowManager, cfg: FreeformConfig, size: Point, thick: Int, top: Int) {
        val rowH = dp(ctx, 56)
        val vis = 6
        val panelH = (rowH * vis).coerceAtMost((size.y - top - dp(ctx, 8)).coerceAtLeast(rowH))
        val panelW = dp(ctx, 72)
        val radius = dp(ctx, 16).toFloat()
        val pad = dp(ctx, 12)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.tag = "chuibing-ff-sheet"
        root.isClickable = true
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = radius
        bg.setColor(0xFFFFFFFF.toInt())
        root.background = bg
        root.clipToOutline = true
        root.outlineProvider = roundOutline(radius)
        root.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8))
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(sideCard(ctx, "全部") { showPicker("all") })
        for (pkg in cfg.apps) {
            col.addView(appIcon(ctx, pkg) {
                val cls = try {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.className.orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                FreeformWindows.open(ctx, pkg, cls)
                hideSidebar()
            })
        }
        col.addView(sideCard(ctx, "添加") { showPicker("add") })
        scroll.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        val overlay = FrameLayout(ctx)
        overlay.setBackgroundColor(0)
        overlay.isClickable = true
        overlay.setOnClickListener { hideSidebar() }
        val left = cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
        val slp = FrameLayout.LayoutParams(panelW, panelH)
        slp.gravity = (if (left) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
        if (left) slp.leftMargin = thick else slp.rightMargin = thick
        slp.topMargin = top
        root.translationX = if (left) -panelW.toFloat() else panelW.toFloat()
        overlay.addView(root, slp)
        val lp = baseLp("chuibing-ff-panel")
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = size.x
        lp.height = size.y
        lp.x = 0
        lp.y = 0
        if (!addOverlay(manager, overlay, lp)) {
            animating = false
            return
        }
        panel = overlay
        root.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).withEndAction {
            animating = false
            apply(force = true)
        }.start()
    }

    private fun addPicker(ctx: Context, manager: WindowManager, cfg: FreeformConfig, size: Point, mode: String) {
        val w = (size.x * 0.78f).toInt().coerceIn(dp(ctx, 240), size.x - dp(ctx, 24))
        val h = (size.y * 0.62f).toInt().coerceIn(dp(ctx, 320), size.y - dp(ctx, 80))
        val radius = dp(ctx, 16).toFloat()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = radius
        bg.setColor(0xFFFFFFFF.toInt())
        root.background = bg
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        val title = TextView(ctx)
        title.text = if (mode == "add") "添加软件" else "全部软件"
        title.setTextColor(0xFF1A1A1A.toInt())
        title.textSize = 16f
        title.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 10))
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(ctx)
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12))
        val apps = launchApps(ctx)
        var row: LinearLayout? = null
        apps.forEachIndexed { i, app ->
            if (i % 4 == 0) {
                row = LinearLayout(ctx)
                row!!.orientation = LinearLayout.HORIZONTAL
                grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            val iconBox = FrameLayout(ctx)
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            try {
                iv.setImageDrawable(ctx.packageManager.getApplicationIcon(app.pkg))
            } catch (_: Throwable) {
                iv.setBackgroundColor(0x44FFFFFF)
            }
            iconBox.addView(iv, FrameLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
            if (mode == "add" && app.pkg in cfg.apps) {
                val badge = View(ctx)
                val bbg = GradientDrawable()
                bbg.shape = GradientDrawable.OVAL
                bbg.setColor(0xFF2E7D32.toInt())
                badge.background = bbg
                val blp = FrameLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12))
                blp.gravity = Gravity.TOP or Gravity.END
                iconBox.addView(badge, blp)
            }
            cell.addView(iconBox, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
            val name = TextView(ctx)
            name.text = app.label
            name.setTextColor(0xFF1A1A1A.toInt())
            name.textSize = 10f
            name.gravity = Gravity.CENTER
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            cell.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            cell.setOnClickListener {
                if (mode == "add") {
                    if (app.pkg.isNotBlank() && app.pkg !in cfg.apps) persistApps(cfg.apps + app.pkg)
                    hideSidebar()
                } else {
                    FreeformWindows.open(ctx, app.pkg, app.cls)
                    hideSidebar()
                }
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        scroll.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.tag = "chuibing-ff-sheet"
        root.isClickable = true
        val overlay = FrameLayout(ctx)
        overlay.setBackgroundColor(0)
        overlay.isClickable = true
        overlay.setOnClickListener { hideSidebar() }
        val clp = FrameLayout.LayoutParams(w, h)
        clp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        clp.topMargin = ((size.y - h) / 2 - dp(ctx, 48)).coerceAtLeast(dp(ctx, 24))
        overlay.addView(root, clp)
        val lp = baseLp("chuibing-ff-pick")
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = size.x
        lp.height = size.y
        lp.x = 0
        lp.y = 0
        if (!addOverlay(manager, overlay, lp)) {
            animating = false
            return
        }
        picker = overlay
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.post {
            root.pivotX = root.width / 2f
            root.pivotY = root.height / 2f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).withEndAction {
                animating = false
            }.start()
        }
    }

    private fun appIcon(ctx: Context, pkg: String, click: () -> Unit): View {
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            iv.setImageDrawable(ctx.packageManager.getApplicationIcon(pkg))
        } catch (_: Throwable) {
            iv.setBackgroundColor(0x44FFFFFF)
        }
        iv.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = dp(ctx, 4)
        lp.bottomMargin = dp(ctx, 4)
        iv.layoutParams = lp
        return iv
    }

    private fun sideCard(ctx: Context, label: String, click: () -> Unit): View {
        val card = FrameLayout(ctx)
        val radius = dp(ctx, 16).toFloat()
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = radius
        bg.setColor(0xFFF0F0F0.toInt())
        card.background = bg
        card.clipToOutline = true
        card.outlineProvider = roundOutline(radius)
        val tv = TextView(ctx)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.setTextColor(0xFF1A1A1A.toInt())
        tv.textSize = 13f
        card.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48))
        lp.topMargin = dp(ctx, 4)
        lp.bottomMargin = dp(ctx, 4)
        card.layoutParams = lp
        return card
    }

    private fun roundOutline(radius: Float): ViewOutlineProvider {
        return object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun bringBarFront() {
        val manager = wm ?: return
        val v = bar ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
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

    private fun persistApps(apps: List<String>) {
        val next = FreeformRuntime.cfg.copy(apps = apps)
        FreeformRuntime.cfg = next
        val text = next.toJson()
        FreeformRuntime.cfgText = text
        FreeformRuntime.cfgAt = System.currentTimeMillis()
        val paths = listOf(
            Paths.FREEFORM,
            "/data/local/tmp/tnt_freeform_config.json",
            "/data/user/0/id.tntwindow.editor/files/freeform_config.json",
            "/data/data/id.tntwindow.editor/files/freeform_config.json",
        )
        for (i in 0 until 3) {
            var ok = false
            for (path in paths) {
                if (writeText(path, text)) ok = true
            }
            val sys = readText(Paths.FREEFORM)
            val tmp = readText("/data/local/tmp/tnt_freeform_config.json")
            if (ok && (sys == text || tmp == text)) break
        }
    }

    private fun writeText(path: String, text: String): Boolean {
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            val fos = java.io.FileOutputStream(f, false)
            try {
                fos.write(text.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            } finally {
                fos.close()
            }
            try {
                f.setReadable(true, false)
                f.setWritable(true, false)
            } catch (_: Throwable) {
            }
            f.readText(Charsets.UTF_8) == text
        } catch (_: Throwable) {
            false
        }
    }

    private fun readText(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) null else f.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private data class LaunchApp(val pkg: String, val cls: String, val label: String)

    private fun launchApps(ctx: Context): List<LaunchApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val out = ArrayList<LaunchApp>()
        val seen = HashSet<String>()
        for (ri in list) {
            val info = ri.activityInfo ?: continue
            val pkg = info.packageName ?: continue
            val cls = info.name ?: continue
            if (!seen.add(pkg)) continue
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            out.add(LaunchApp(pkg, cls, label))
        }
        return out.sortedBy { it.label }
    }

    private fun drop(manager: WindowManager, v: View?) {
        if (v == null) return
        try {
            manager.removeView(v)
        } catch (_: Throwable) {
            try {
                manager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
    }

    private fun addTyped(manager: WindowManager, view: View, lp: WindowManager.LayoutParams): Boolean {
        val types = intArrayOf(lp.type, 2010, 2038, 2003, 2002, 2024)
        for (type in types) {
            lp.type = type
            try {
                manager.addView(view, lp)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun addOverlay(manager: WindowManager, view: View, lp: WindowManager.LayoutParams): Boolean {
        val types = intArrayOf(2038, 2032, 2024, 2006, 2003, 2002, 2010)
        for (type in types) {
            lp.type = type
            try {
                manager.addView(view, lp)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun removeAll(manager: WindowManager) {
        for (v in listOf(picker, panel, bar)) {
            if (v == null) continue
            try {
                manager.removeView(v)
            } catch (_: Throwable) {
                try {
                    manager.removeViewImmediate(v)
                } catch (_: Throwable) {
                }
            }
        }
        picker = null
        panel = null
        bar = null
        pill = null
    }

    private fun colorBar(ctx: Context, cfg: FreeformConfig, preview: Boolean) {
        val v = bar ?: return
        colorBarView(v, preview)
        val p = pill ?: return
        val size = screenSize(ctx)
        val thick = thickness()
        val barH = dp(ctx, 88) * 8 / 9
        val top = ((size.y * (cfg.sidebarTopPct / 100f)).toInt()).coerceIn(0, (size.y - barH).coerceAtLeast(0))
        val bg = p.background as? GradientDrawable ?: GradientDrawable().also {
            it.shape = GradientDrawable.RECTANGLE
            it.cornerRadius = dp(ctx, 3).toFloat()
            p.background = it
        }
        bg.setColor(pillColor(ctx, cfg, size, thick, barH, top, preview))
    }

    private fun colorBarView(v: View, preview: Boolean) {
        v.setBackgroundColor(0)
    }

    private fun pillColor(ctx: Context, cfg: FreeformConfig, size: Point, thick: Int, barH: Int, top: Int, preview: Boolean): Int {
        val sampled = sampleLuma(size, cfg, thick, barH, top)
        return ScreenLuma.contrast(sampled)
    }

    private fun sampleLuma(size: Point, cfg: FreeformConfig, thick: Int, barH: Int, top: Int): Float {
        if (animating || open || pickerMode != null || panel != null || picker != null) return lastLuma
        val now = SystemClock.uptimeMillis()
        if (now - lastLumaAt < 400L) return lastLuma
        lastLumaAt = now
        val pad = (thick + 48).coerceAtLeast(56)
        val x = if (cfg.sidebarSide == FreeformConfig.SIDE_RIGHT) {
            (size.x - pad - 32).coerceAtLeast(0)
        } else {
            pad.coerceAtMost((size.x - 32).coerceAtLeast(0))
        }
        val y = (top + barH / 2 - 16).coerceIn(0, (size.y - 32).coerceAtLeast(0))
        val luma = ScreenLuma.of(Rect(x, y, x + 32, y + 32))
        if (luma >= 0f) lastLuma = luma
        return lastLuma
    }

    private fun previewOn(): Boolean {
        val files = listOf(File(Paths.FREEFORM_HIGHLIGHT_SYS), File(Paths.FREEFORM_HIGHLIGHT))
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

    private fun thickness(): Int {
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
        if (f == null) return 28
        return try {
            GestureConfig.fromJson(f.readText(Charsets.UTF_8)).thickness.coerceIn(12, 80)
        } catch (_: Throwable) {
            28
        }
    }

    private fun screenSize(ctx: Context): Point {
        val p = Point()
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(p)
        } catch (_: Throwable) {
            val dm = ctx.resources.displayMetrics
            p.x = dm.widthPixels
            p.y = dm.heightPixels
        }
        return p
    }

    private fun baseLp(title: String): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams()
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.packageName = "android"
        lp.title = title
        lp.alpha = 1f
        return lp
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * (if (d > 0f) d else 2.5f) + 0.5f).toInt()
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

    companion object {
        @Volatile private var inst: FreeformModule? = null
        fun openSidebar() {
            inst?.showSidebar()
        }

        fun raiseBar() {
            inst?.bringBarFront()
        }
    }

    init {
        inst = this
    }
}




