package id.tntwindow.editor.xposed

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import id.tntwindow.editor.domain.FreeformConfig
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.Paths
import java.io.File
import java.util.LinkedHashMap

object FreeformRuntime {
    @Volatile var cfg: FreeformConfig = FreeformConfig.default()
    @Volatile var cfgAt: Long = -1L
    @Volatile var policy: Any? = null
    @Volatile var context: Context? = null

    @Volatile var cfgText: String = ""

    fun reload() {
        val files = listOf(File(Paths.FREEFORM), File("/data/local/tmp/tnt_freeform_config.json"))
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
            if (cfgText.isNotBlank()) return
            cfg = FreeformConfig.default()
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
        val at = try { f.lastModified() } catch (_: Throwable) { 0L }
        if (cfgText.isNotBlank() && cfgAt > 0L && at <= cfgAt) return
        try {
            if (text.isNotBlank()) {
                cfg = FreeformConfig.fromJson(text)
                cfgText = text
                cfgAt = at
            }
        } catch (_: Throwable) {
        }
    }
}

object FreeformWindows {
    private val sessions = LinkedHashMap<String, Session>()
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0
    private var injectFn: ((MotionEvent, Int) -> Boolean)? = null
    private var lastOpenPkg = ""
    private var lastOpenCls = ""
    private var lastOpenAt = 0L

    fun open(context: Context, pkg: String, cls: String) {
        if (pkg.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastOpenPkg && cls == lastOpenCls && now - lastOpenAt < 1200L) return
        lastOpenPkg = pkg
        lastOpenCls = cls
        lastOpenAt = now
        val run = Runnable {
            val act = cls.ifBlank { launchClass(context, pkg, "") }
            show(context, pkg, act, findTaskId(context, pkg))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler.post(run)
    }

    fun enqueue(context: Context, pkg: String, cls: String) {
        val sys = FreeformRuntime.context
        if (sys != null) {
            open(sys, pkg, cls)
            return
        }
        val line = pkg + "\n" + cls + "\n" + System.currentTimeMillis()
        var wrote = false
        for (path in listOf(Paths.FREEFORM_REQUEST_TMP, Paths.FREEFORM_REQUEST)) {
            try {
                File(path).writeText(line)
                wrote = true
                break
            } catch (_: Throwable) {
            }
        }
        if (wrote) return
        try {
            val i = Intent("id.tntwindow.editor.action.FREEFORM_OPEN")
            i.putExtra("pkg", pkg)
            i.putExtra("cls", cls)
            context.sendBroadcast(i)
        } catch (_: Throwable) {
        }
    }

    fun drain(context: Context) {
        val files = listOf(File(Paths.FREEFORM_REQUEST_TMP), File(Paths.FREEFORM_REQUEST))
        val f = files.firstOrNull {
            try {
                it.exists() && it.length() > 0L
            } catch (_: Throwable) {
                false
            }
        } ?: return
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        try {
            f.delete()
        } catch (_: Throwable) {
        }
        val lines = text.split('\n')
        val pkg = lines.getOrNull(0)?.trim().orEmpty()
        val cls = lines.getOrNull(1)?.trim().orEmpty()
        if (pkg.isNotBlank()) open(context, pkg, cls)
    }

    fun packages(): Set<String> = sessions.keys.toSet()

    fun closeTop() {
        val last = sessions.values.lastOrNull() ?: return
        last.close()
    }

    fun fullscreenTop() {
        val last = sessions.values.lastOrNull() ?: return
        last.fullscreen()
    }

    fun current(context: Context) {
        val hit = topApp(context) ?: return
        val run = Runnable { show(context, hit.pkg, hit.cls, hit.taskId) }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler.post(run)
    }

    private fun show(context: Context, pkg: String, cls: String, taskId: Int) {
        FreeformRuntime.reload()
        val old = sessions[pkg]
        if (old != null) {
            if (taskId > 0) old.taskId = taskId
            old.raise()
            old.launch()
            return
        }
        val max = FreeformRuntime.cfg.maxWindows.coerceIn(1, 8)
        while (sessions.size >= max) sessions.values.first().close(true)
        val session = Session(context, pkg, cls, taskId)
        sessions[pkg] = session
        if (taskId > 0) session.attach() else session.prepareThenAttach()
    }

    private fun drop(pkg: String) {
        sessions.remove(pkg)
    }

    private data class Target(val pkg: String, val cls: String, val taskId: Int = -1)

    private fun topApp(context: Context): Target? {
        val skip = setOf(
            "com.android.systemui",
            "android",
        )
        val open = packages()
        val focused = focusedApp(context)
        if (focused != null && focused.pkg !in skip && focused.pkg !in open) return focused
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = try {
            am.getRunningTasks(20)
        } catch (_: Throwable) {
            null
        } ?: return focused
        for (info in tasks) {
            val top = info.topActivity ?: info.baseActivity ?: continue
            val pkg = top.packageName ?: continue
            if (pkg in skip || pkg in open) continue
            val cls = launchClass(context, pkg, top.className)
            if (cls.isBlank()) continue
            return Target(pkg, cls, info.id)
        }
        return focused
    }

    private fun focusedApp(context: Context): Target? {
        val info = try {
            val native = Class.forName("android.app.ActivityManagerNative")
            val am = native.getMethod("getDefault").invoke(null)
            XposedHelpers.callMethod(am, "getFocusedStackInfo")
        } catch (_: Throwable) {
            try {
                val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                XposedHelpers.callMethod(am, "getFocusedStackInfo")
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        val top = try {
            XposedHelpers.getObjectField(info, "topActivity") as? ComponentName
        } catch (_: Throwable) {
            null
        } ?: return null
        val pkg = top.packageName ?: return null
        val cls = launchClass(context, pkg, top.className)
        if (cls.isBlank()) return null
        val ids = try {
            XposedHelpers.getObjectField(info, "taskIds") as? IntArray
        } catch (_: Throwable) {
            null
        }
        val taskId = ids?.lastOrNull() ?: -1
        return Target(pkg, cls, taskId)
    }

    private fun findTaskId(context: Context, pkg: String): Int {
        if (pkg.isBlank()) return -1
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = try {
            am.getRunningTasks(30)
        } catch (_: Throwable) {
            null
        }
        if (tasks != null) {
            for (info in tasks) {
                val top = info.topActivity ?: info.baseActivity ?: continue
                if (top.packageName == pkg) return info.id
            }
        }
        val recents = try {
            am.getRecentTasks(40, ActivityManager.RECENT_WITH_EXCLUDED)
        } catch (_: Throwable) {
            null
        }
        if (recents != null) {
            for (info in recents) {
                val p = info.topActivity?.packageName
                    ?: info.baseActivity?.packageName
                    ?: info.origActivity?.packageName
                    ?: info.baseIntent?.component?.packageName
                if (p != pkg) continue
                if (info.id > 0) return info.id
                if (info.persistentId > 0) return info.persistentId
            }
        }
        return -1
    }

    private fun launchClass(context: Context, pkg: String, fallback: String?): String {
        try {
            val n = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className.orEmpty()
            if (n.isNotBlank()) return n
        } catch (_: Throwable) {
        }
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(pkg)
            val n = context.packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.name.orEmpty()
            if (n.isNotBlank()) return n
        } catch (_: Throwable) {
        }
        return fallback.orEmpty()
    }

    private class Session(val context: Context, val pkg: String, val cls: String, var taskId: Int) {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        private val screen = Point()
        private val lp = WindowManager.LayoutParams()
        private var root: FrameLayout? = null
        private var vd: VirtualDisplay? = null
        private var surface: Surface? = null
        private var displayId = -1
        private var stackId = -1
        private var startTries = 0
        private var coldTries = 0
        private var keepDefaultTid = -1
        private var contentW = 0
        private var contentH = 0
        private var added = false
        private var closing = false
        private var body: View? = null
        private val name = "chuibing-ff-" + pkg + "-" + (++seq)

        fun prepareThenAttach() {
            attach()
        }

        fun attach() {
            try {
                wm.defaultDisplay.getRealSize(screen)
            } catch (_: Throwable) {
                val m = context.resources.displayMetrics
                screen.x = m.widthPixels
                screen.y = m.heightPixels
            }
            val cfg = FreeformRuntime.cfg
            val w = (screen.x * (cfg.widthPct / 100f)).toInt().coerceIn(dp(240), screen.x - dp(24))
            val h = (screen.y * (cfg.heightPct / 100f)).toInt().coerceIn(dp(320), screen.y - dp(80))
            val i = (sessions.size - 1).coerceAtLeast(0)
            lp.type = 2010
            lp.format = PixelFormat.TRANSLUCENT
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            lp.gravity = Gravity.TOP or Gravity.START
            val shadow = dp(10)
            lp.width = w + shadow * 2
            lp.height = h + shadow * 2
            lp.x = ((screen.x - w) / 2 - shadow).coerceAtLeast(0)
            lp.y = ((screen.y - h) / 2 - dp(48) + i * dp(36) - shadow).coerceAtLeast(0)
            lp.packageName = "android"
            lp.title = name
            val radius = dp(16).toFloat()
            val frame = FrameLayout(context)
            frame.clipChildren = false
            frame.clipToPadding = false
            frame.setPadding(shadow, shadow, shadow, shadow)
            val chromeH = dp(20)
            val boxW = w
            val boxH = (h - chromeH).coerceAtLeast(dp(80))
            val uiScale = 0.82f
            contentW = (boxW / uiScale).toInt().coerceAtLeast(1)
            contentH = (boxH / uiScale).toInt().coerceAtLeast(1)
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            col.setBackgroundColor(0xFFFFFFFF.toInt())
            col.elevation = dp(6).toFloat()
            col.clipToOutline = true
            col.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            val box = FrameLayout(context)
            box.setBackgroundColor(0xFFFFFFFF.toInt())
            val tv = TextureView(context)
            tv.isOpaque = true
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    try {
                        st.setDefaultBufferSize(contentW, contentH)
                    } catch (_: Throwable) {
                    }
                    bindSurface(Surface(st), contentW, contentH)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    try {
                        st.setDefaultBufferSize(contentW, contentH)
                    } catch (_: Throwable) {
                    }
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releaseDisplay()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            tv.setOnTouchListener { v, ev ->
                val id = displayId
                val dw = contentW
                val dh = contentH
                if (id >= 0 && v.width > 0 && v.height > 0 && dw > 0 && dh > 0) {
                    val copy = MotionEvent.obtain(ev)
                    val m = Matrix()
                    m.setScale(dw / v.width.toFloat(), dh / v.height.toFloat())
                    copy.transform(m)
                    inject(copy, id)
                    copy.recycle()
                }
                true
            }
            val tvLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            box.addView(tv, tvLp)
            col.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(chrome(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, chromeH))
            frame.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            body = col
            root = frame
            addWindow(frame)
        }

        fun raise() {
            val v = root ?: return
            if (!added) return
            try {
                wm.removeView(v)
                wm.addView(v, lp)
            } catch (_: Throwable) {
                try {
                    wm.updateViewLayout(v, lp)
                } catch (_: Throwable) {
                }
            }
        }

        fun launch() {
            val id = displayId
            if (id < 0) return
            startOn(id)
        }

        fun fullscreen() {
            if (closing) return
            closing = true
            val ids = ArrayList<Int>()
            if (taskId > 0) ids.add(taskId)
            ids.addAll(tasksOnDisplay(displayId))
            var moved = false
            for (id in ids.distinct()) {
                if (moveToStack(id, 1)) moved = true
                if (moveTask(id, Display.DEFAULT_DISPLAY)) moved = true
            }
            if (!moved) startOn(Display.DEFAULT_DISPLAY)
            playOut { dismiss(false) }
        }

        fun close(immediate: Boolean = false) {
            if (immediate) {
                dismiss(true)
                return
            }
            if (closing) return
            closing = true
            playOut { dismiss(true) }
        }

        private fun playIn() {
            val v = body ?: return
            v.alpha = 0f
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.post {
                if (closing) return@post
                if (v.width > 0 && v.height > 0) {
                    v.pivotX = v.width / 2f
                    v.pivotY = v.height / 2f
                }
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
        }

        private fun playOut(end: () -> Unit) {
            val v = body
            if (v == null || !added) {
                end()
                return
            }
            try {
                v.animate().cancel()
            } catch (_: Throwable) {
            }
            if (v.width > 0 && v.height > 0) {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }
            v.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140).setInterpolator(AccelerateInterpolator()).withEndAction { end() }.start()
        }

        private fun dismiss(kill: Boolean) {
            closing = true
            try {
                body?.animate()?.cancel()
            } catch (_: Throwable) {
            }
            body = null
            val token = Binder.clearCallingIdentity()
            try {
                if (kill) removeTasks()
            } catch (_: Throwable) {
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            releaseDisplay()
            val v = root
            root = null
            if (v != null && added) {
                added = false
                try {
                    wm.removeView(v)
                } catch (_: Throwable) {
                    try {
                        wm.removeViewImmediate(v)
                    } catch (_: Throwable) {
                    }
                }
            }
            drop(pkg)
        }

        private fun chrome(): View {
            val bar = LinearLayout(context)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setBackgroundColor(0xFFFFFFFF.toInt())
            val leftSlot = FrameLayout(context)
            val left = dot()
            left.setOnClickListener { runDot(true) }
            leftSlot.addView(left, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            val mid = FrameLayout(context)
            val handle = View(context)
            val handleBg = GradientDrawable()
            handleBg.shape = GradientDrawable.RECTANGLE
            handleBg.cornerRadius = dp(2).toFloat()
            handleBg.setColor(0xFF1A1A1A.toInt())
            handle.background = handleBg
            val hl = FrameLayout.LayoutParams(dp(84), dp(4))
            hl.gravity = Gravity.CENTER
            mid.addView(handle, hl)
            mid.setOnTouchListener(dragListener())
            val rightSlot = FrameLayout(context)
            val right = dot()
            right.setOnClickListener { runDot(false) }
            rightSlot.addView(right, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            bar.addView(leftSlot, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            bar.addView(mid, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.MATCH_PARENT))
            bar.addView(rightSlot, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            return bar
        }

        private fun dot(): View {
            val wrap = FrameLayout(context)
            val v = View(context)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1A1A1A.toInt())
            v.background = bg
            val lp = FrameLayout.LayoutParams(dp(4), dp(4))
            lp.gravity = Gravity.CENTER
            wrap.addView(v, lp)
            return wrap
        }

        private fun runDot(left: Boolean) {
            FreeformRuntime.reload()
            val action = if (left) FreeformRuntime.cfg.left else FreeformRuntime.cfg.right
            when {
                action.isNone() -> {}
                action.isDefault() -> if (left) close() else fullscreen()
                action.type == KeyAction.TYPE_FREEFORM_CLOSE -> close()
                action.type == KeyAction.TYPE_FREEFORM_FULLSCREEN -> fullscreen()
                else -> {
                    val pwm = FreeformRuntime.policy ?: return
                    KeyMapActions.run(pwm, context, action)
                }
            }
        }

        private fun addWindow(view: View) {
            val types = intArrayOf(lp.type, 2038, 2010, 2003, 2002, 2024)
            for (type in types) {
                lp.type = type
                try {
                    wm.addView(view, lp)
                    added = true
                    XposedBridge.log("chuibing freeform add " + pkg + " type=" + type)
                    playIn()
                    return
                } catch (err: Throwable) {
                    XposedBridge.log("chuibing freeform add fail type=" + type + " " + err.javaClass.simpleName)
                }
            }
            drop(pkg)
        }

        private fun bindSurface(s: Surface, w: Int, h: Int) {
            contentW = w
            contentH = h
            val cur = vd
            if (cur != null && displayId >= 0) {
                return
            }
            surface = s
            val token = Binder.clearCallingIdentity()
            try {
                if (keepDefaultTid <= 0) keepDefaultTid = defaultKeepTaskId()
                val own = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                var created: VirtualDisplay? = null
                try {
                    created = dm.createVirtualDisplay(name, w, h, dpi(), s, own)
                } catch (_: Throwable) {
                }
                if (created == null) {
                    created = dm.createVirtualDisplay(name, w, h, dpi(), s, own or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC)
                }
                val vdCreated = created ?: return
                vd = vdCreated
                displayId = vdCreated.display.displayId
                startTries = 0
                coldTries = 0
                XposedBridge.log("chuibing freeform vd " + pkg + " display=" + displayId + " " + w + "x" + h)
                handler.postDelayed({ startOn(displayId) }, 60)
            } catch (err: Throwable) {
                XposedBridge.log("chuibing freeform vd fail " + err.javaClass.simpleName + " " + err.message)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        private fun startOn(id: Int) {
            if (displayId != id) return
            if (keepDefaultTid <= 0) keepDefaultTid = defaultKeepTaskId()
            val stack = ensureStack(id)
            if (stack < 0 && startTries < 12) {
                startTries += 1
                handler.postDelayed({ startOn(id) }, 50)
                return
            }
            val onVd = tasksOnDisplay(id)
            val foundVd = onVd.firstOrNull() ?: 0
            if (foundVd > 0) {
                startTries = 0
                coldTries = 0
                taskId = foundVd
                restoreDefaultTop()
                return
            }
            val existing = runningTaskId()
            val existingDisp = displayOfTask(existing)
            if (existing > 0 && existingDisp == id) {
                startTries = 0
                coldTries = 0
                taskId = existing
                restoreDefaultTop()
                return
            }
            if (existing > 0 && existingDisp <= 0 && coldTries >= 2) {
                if (stack >= 0) moveToStack(existing, stack)
                moveTask(existing, id)
                taskId = existing
                restoreDefaultTop()
                if (tasksOnDisplay(id).contains(existing)) {
                    startTries = 0
                    coldTries = 0
                    return
                }
            }
            if (coldTries < 8) {
                if (coldTries == 0 || coldTries == 3) launchOn(id, stack)
                coldTries += 1
                handler.postDelayed({ startOn(id) }, 200)
                return
            }
            restoreDefaultTop()
            XposedBridge.log("chuibing freeform cold timeout " + pkg)
        }
        private fun defaultKeepTaskId(): Int {
            val skip = setOf(pkg, "com.android.systemui", "android", "id.tntwindow.editor")
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = try {
                XposedHelpers.callMethod(am, "getAllStackInfos") as? List<*>
            } catch (_: Throwable) {
                null
            }
            if (infos != null) {
                var best = -1
                var bestVis = -1
                for (info in infos) {
                    if (info == null) continue
                    val d = try {
                        (XposedHelpers.getObjectField(info, "displayId") as? Number)?.toInt() ?: -1
                    } catch (_: Throwable) {
                        -1
                    }
                    if (d > 0) continue
                    val top = try {
                        XposedHelpers.getObjectField(info, "topActivity") as? ComponentName
                    } catch (_: Throwable) {
                        null
                    }
                    val p = top?.packageName.orEmpty()
                    if (p.isBlank() || p in skip) continue
                    val ids = try {
                        XposedHelpers.getObjectField(info, "taskIds") as? IntArray
                    } catch (_: Throwable) {
                        null
                    }
                    val tid = ids?.lastOrNull() ?: 0
                    if (tid <= 0) continue
                    val vis = try {
                        (XposedHelpers.getObjectField(info, "visible") as? Boolean) ?: true
                    } catch (_: Throwable) {
                        true
                    }
                    val pri = if (vis) 1 else 0
                    if (pri >= bestVis) {
                        bestVis = pri
                        best = tid
                    }
                }
                if (best > 0) return best
            }
            val tasks = try {
                am.getRunningTasks(20)
            } catch (_: Throwable) {
                null
            }
            if (tasks != null) {
                for (info in tasks) {
                    val top = info.topActivity ?: info.baseActivity ?: continue
                    val p = top.packageName ?: continue
                    if (p in skip) continue
                    if (info.id > 0) return info.id
                }
            }
            return -1
        }

        private fun displayOfTask(tid: Int): Int {
            if (tid <= 0) return -1
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = try {
                XposedHelpers.callMethod(am, "getAllStackInfos") as? List<*>
            } catch (_: Throwable) {
                null
            } ?: return -1
            for (info in infos) {
                if (info == null) continue
                val ids = try {
                    XposedHelpers.getObjectField(info, "taskIds") as? IntArray
                } catch (_: Throwable) {
                    null
                }
                if (ids == null || !ids.contains(tid)) continue
                return try {
                    (XposedHelpers.getObjectField(info, "displayId") as? Number)?.toInt() ?: -1
                } catch (_: Throwable) {
                    -1
                }
            }
            return -1
        }

        private fun restoreDefaultTop() {
            val tid = keepDefaultTid
            if (tid <= 0) return
            if (displayId >= 0 && tasksOnDisplay(displayId).contains(tid)) return
            moveTaskToFront(tid)
        }

        private fun moveTaskToFront(tid: Int) {
            if (tid <= 0) return
            val token = Binder.clearCallingIdentity()
            try {
                val ams = iam()
                if (ams != null) {
                    try {
                        XposedHelpers.callMethod(ams, "moveTaskToFront", tid, 0)
                        return
                    } catch (_: Throwable) {
                    }
                    try {
                        XposedHelpers.callMethod(ams, "moveTaskToFront", tid, 0, null)
                        return
                    } catch (_: Throwable) {
                    }
                }
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                try {
                    am.moveTaskToFront(tid, 0)
                } catch (_: Throwable) {
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
        private fun runningTaskId(): Int {
            if (pkg.isBlank()) return -1
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = try {
                am.getRunningTasks(30)
            } catch (_: Throwable) {
                null
            }
            if (tasks != null) {
                for (info in tasks) {
                    val top = info.topActivity ?: info.baseActivity ?: continue
                    if (top.packageName == pkg && info.id > 0) return info.id
                }
            }
            val infos = try {
                XposedHelpers.callMethod(am, "getAllStackInfos") as? List<*>
            } catch (_: Throwable) {
                null
            }
            if (infos != null) {
                for (info in infos) {
                    if (info == null) continue
                    val top = try {
                        XposedHelpers.getObjectField(info, "topActivity") as? ComponentName
                    } catch (_: Throwable) {
                        null
                    }
                    if (top?.packageName != pkg) continue
                    val ids = try {
                        XposedHelpers.getObjectField(info, "taskIds") as? IntArray
                    } catch (_: Throwable) {
                        null
                    }
                    val tid = ids?.lastOrNull() ?: 0
                    if (tid > 0) return tid
                }
            }
            return -1
        }

        private fun launchDefault() {
            wakeDefault()
        }

        private fun wakeDefault() {
            if (pkg.isBlank()) return
            val token = Binder.clearCallingIdentity()
            try {
                val intent = try {
                    context.packageManager.getLaunchIntentForPackage(pkg)
                } catch (_: Throwable) {
                    null
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (!startAms(intent, android.os.Bundle())) startAsUser(intent, null)
                    return
                }
                launchOn(Display.DEFAULT_DISPLAY, -1)
            } catch (_: Throwable) {
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        private fun startAsUser(intent: Intent, bundle: android.os.Bundle?): Boolean {
            val user = UserHandle.getUserHandleForUid(0)
            try {
                if (bundle == null || bundle.isEmpty) {
                    XposedHelpers.callMethod(context, "startActivityAsUser", intent, user)
                } else {
                    XposedHelpers.callMethod(context, "startActivityAsUser", intent, bundle, user)
                }
                return true
            } catch (_: Throwable) {
            }
            val c = cls.ifBlank { launchClass(context, pkg, "") }
            if (c.isBlank()) return false
            return try {
                Runtime.getRuntime().exec(arrayOf("am", "start", "--user", "0", "-n", pkg + "/" + c, "-f", "0x10200000"))
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun shellStart() {
            val c = if (cls.isNotBlank()) cls else launchClass(context, pkg, cls)
            if (c.isBlank()) return
            try {
                Runtime.getRuntime().exec(arrayOf("am", "start", "--user", "0", "-n", pkg + "/" + c, "-f", "0x10200000"))
            } catch (_: Throwable) {
            }
        }

        private fun launchOn(id: Int, stack: Int) {
            val c = cls.ifBlank { launchClass(context, pkg, "") }
            if (c.isBlank()) {
                XposedBridge.log("chuibing freeform no component " + pkg)
                return
            }
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.component = ComponentName(pkg, c)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (id == Display.DEFAULT_DISPLAY) {
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val opts = makeOpts(id, stack)
            val bundle = makeBundle(opts, id, stack)
            val token = Binder.clearCallingIdentity()
            try {
                if (!startAms(intent, bundle)) {
                    startAsUser(intent, bundle)
                }
            } catch (err: Throwable) {
                XposedBridge.log("chuibing freeform start fail " + err.javaClass.simpleName + " " + err.message)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        private fun makeOpts(id: Int, stack: Int): ActivityOptions {
            val opts = ActivityOptions.makeBasic()
            if (id >= 0) {
                try {
                    opts.launchDisplayId = id
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(opts, "setLaunchDisplayId", id)
                } catch (_: Throwable) {
                }
            }
            if (stack >= 0) {
                try {
                    XposedHelpers.callMethod(opts, "setLaunchStackId", stack)
                } catch (_: Throwable) {
                    try {
                        val f = ActivityOptions::class.java.getDeclaredField("mLaunchStackId")
                        f.isAccessible = true
                        f.setInt(opts, stack)
                    } catch (_: Throwable) {
                    }
                }
            }
            return opts
        }

        private fun makeBundle(opts: ActivityOptions, id: Int, stack: Int): android.os.Bundle {
            val bundle = opts.toBundle() ?: android.os.Bundle()
            if (id >= 0) bundle.putInt("android.activity.launchDisplayId", id)
            if (stack >= 0) bundle.putInt("android.activity.launchStackId", stack)
            return bundle
        }

        private fun startedOk(result: Any?): Boolean {
            if (result == null) return true
            if (result is Boolean) return result
            val n = (result as? Number)?.toInt() ?: return true
            return n >= 0
        }

        private fun startAms(intent: Intent, bundle: android.os.Bundle): Boolean {
            val am = iam() ?: return false
            val type = try {
                intent.resolveTypeIfNeeded(context.contentResolver)
            } catch (_: Throwable) {
                null
            }
            val callers = arrayOf("android", pkg)
            for (caller in callers) {
                try {
                    val r = XposedHelpers.callMethod(am, "startActivity", null, caller, intent, type, null, null, 0, 0, null, bundle)
                    if (startedOk(r)) return true
                } catch (_: Throwable) {
                }
                try {
                    val r = XposedHelpers.callMethod(am, "startActivityAsUser", null, caller, intent, type, null, null, 0, 0, null, bundle, 0)
                    if (startedOk(r)) return true
                } catch (_: Throwable) {
                }
            }
            return false
        }

        private fun startFromRecents(tid: Int, id: Int, stack: Int): Boolean {
            if (tid <= 0) return false
            val am = iam() ?: return false
            val bundle = makeBundle(makeOpts(id, stack), id, stack)
            val token = Binder.clearCallingIdentity()
            return try {
                val r = XposedHelpers.callMethod(am, "startActivityFromRecents", tid, bundle)
                startedOk(r)
            } catch (_: Throwable) {
                false
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        private fun ensureOn(id: Int, stack: Int) {
            if (displayId != id) return
            val sid = if (stack >= 0) stack else ensureStack(id)
            val onVd = tasksOnDisplay(id).firstOrNull() ?: 0
            if (onVd > 0) {
                taskId = onVd
                restoreDefaultTop()
                return
            }
            launchOn(id, sid)
            handler.postDelayed({ startOn(id) }, 80)
        }
        private fun ensureStack(id: Int): Int {
            if (stackId >= 0) return stackId
            val am = iam() ?: return -1
            val token = Binder.clearCallingIdentity()
            try {
                val sid = 90 + (kotlin.math.abs(name.hashCode()) % 30)
                try {
                    val created = XposedHelpers.callMethod(am, "createStackOnDisplay", sid, id)
                    val n = (created as? Number)?.toInt() ?: sid
                    if (n >= 90) {
                        stackId = n
                        return n
                    }
                    if (n == sid) {
                        stackId = n
                        return n
                    }
                } catch (_: Throwable) {
                }
                try {
                    val created = XposedHelpers.callMethod(am, "createStackOnDisplay", id)
                    val n = (created as? Number)?.toInt() ?: -1
                    if (n >= 90) {
                        stackId = n
                        return n
                    }
                } catch (_: Throwable) {
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            return -1
        }
        private fun moveToStack(tid: Int, stack: Int): Boolean {
            if (tid <= 0 || stack < 0) return false
            val am = iam() ?: return false
            val token = Binder.clearCallingIdentity()
            try {
                try {
                    XposedHelpers.callMethod(am, "moveTaskToStack", tid, stack, true)
                    taskId = tid
                    return true
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(am, "moveTaskToStack", tid, stack, true, true)
                    taskId = tid
                    return true
                } catch (_: Throwable) {
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            return false
        }

        private fun moveTask(id: Int, displayId: Int): Boolean {
            if (id <= 0 || displayId < 0) return false
            val am = iam() ?: return false
            val token = Binder.clearCallingIdentity()
            try {
                try {
                    XposedHelpers.callMethod(am, "moveTaskToDisplay", id, displayId)
                    taskId = id
                    return true
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(am, "moveTaskToDisplay", id, displayId, true)
                    taskId = id
                    return true
                } catch (_: Throwable) {
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            return false
        }

        private fun tasksOnDisplay(id: Int): List<Int> {
            if (id < 0) return emptyList()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = try {
                XposedHelpers.callMethod(am, "getAllStackInfos") as? List<*>
            } catch (_: Throwable) {
                null
            } ?: return emptyList()
            val ids = ArrayList<Int>()
            for (info in infos) {
                if (info == null) continue
                val d = try {
                    (XposedHelpers.getObjectField(info, "displayId") as? Number)?.toInt() ?: -1
                } catch (_: Throwable) {
                    -1
                }
                if (d != id) continue
                val tasks = try {
                    XposedHelpers.getObjectField(info, "taskIds") as? IntArray
                } catch (_: Throwable) {
                    null
                }
                if (tasks != null) ids.addAll(tasks.toList())
            }
            return ids
        }

        private fun releaseDisplay() {
            val cur = vd
            vd = null
            displayId = -1
            stackId = -1
            if (cur != null) {
                try {
                    cur.release()
                } catch (_: Throwable) {
                }
            }
            surface = null
        }

        private fun removeTasks() {
            val id = displayId
            if (id < 0) return
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = try {
                XposedHelpers.callMethod(am, "getAllStackInfos") as? List<*>
            } catch (_: Throwable) {
                null
            }
            val ids = ArrayList<Int>()
            if (infos != null) {
                for (info in infos) {
                    if (info == null) continue
                    val d = try {
                        (XposedHelpers.getObjectField(info, "displayId") as? Number)?.toInt() ?: -1
                    } catch (_: Throwable) {
                        -1
                    }
                    if (d != id) continue
                    val tasks = try {
                        XposedHelpers.getObjectField(info, "taskIds") as? IntArray
                    } catch (_: Throwable) {
                        null
                    }
                    if (tasks != null) ids.addAll(tasks.toList())
                }
            }
            for (taskId in ids) {
                try {
                    XposedHelpers.callMethod(am, "removeTask", taskId)
                } catch (_: Throwable) {
                }
            }
        }

        private fun dragListener(): View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            return View.OnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (startX + (ev.rawX - downX)).toInt()
                        lp.y = (startY + (ev.rawY - downY)).toInt().coerceAtLeast(0)
                        val v = root
                        if (v != null && added) {
                            try {
                                wm.updateViewLayout(v, lp)
                            } catch (_: Throwable) {
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        private fun dpi(): Int {
            val v = context.resources.displayMetrics.densityDpi
            return if (v > 0) v else 400
        }

        private fun dp(v: Int): Int {
            val d = context.resources.displayMetrics.density
            return (v * (if (d > 0f) d else 2.5f) + 0.5f).toInt()
        }
    }

    private fun iam(): Any? {
        return try {
            Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        } catch (_: Throwable) {
            try {
                Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun inject(ev: MotionEvent, displayId: Int): Boolean {
        val fn = injectFn ?: resolveInject().also { injectFn = it }
        val copy = MotionEvent.obtain(ev)
        return try {
            setDisplay(copy, displayId)
            fn(copy, displayId)
        } catch (_: Throwable) {
            false
        } finally {
            copy.recycle()
        }
    }

    private fun setDisplay(ev: MotionEvent, displayId: Int) {
        try {
            ev.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId)
            return
        } catch (_: Throwable) {
        }
        try {
            var c: Class<*>? = ev.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField("mDisplayId")
                    f.isAccessible = true
                    f.setInt(ev, displayId)
                    return
                } catch (_: Throwable) {
                    c = c.superclass
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun resolveInject(): (MotionEvent, Int) -> Boolean {
        try {
            val local = Class.forName("com.android.server.LocalServices")
            val imiClass = try {
                Class.forName("android.hardware.input.InputManagerInternal")
            } catch (_: Throwable) {
                Class.forName("com.android.server.input.InputManagerInternal")
            }
            val imi = local.getMethod("getService", Class::class.java).invoke(null, imiClass)
            if (imi != null) {
                val m3 = imi.javaClass.methods.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 3
                }
                if (m3 != null) {
                    return { ev, id ->
                        try {
                            m3.invoke(imi, ev, id, 0) as? Boolean ?: true
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return { ev, _ ->
            try {
                val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
                XposedHelpers.callMethod(im, "injectInputEvent", ev, 0) as? Boolean ?: true
            } catch (_: Throwable) {
                false
            }
        }
    }
}



