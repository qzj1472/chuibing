package id.tntwindow.editor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import id.tntwindow.editor.domain.FreeformConfig
import id.tntwindow.editor.domain.GestureConfig
import id.tntwindow.editor.domain.Paths
import java.io.File

object VoiceHud {
    const val ACTION = "id.tntwindow.editor.action.VOICE_HUD"

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var card: View? = null
    private var label: TextView? = null
    private var hideRun: Runnable? = null
    private var recv = false

    fun ensure(context: Context) {
        if (!isOwner(context)) return
        if (recv) return
        try {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val state = intent?.getStringExtra("state") ?: return
                    val text = intent.getStringExtra("text") ?: ""
                    apply(context, state, text)
                }
            }, IntentFilter(ACTION))
            recv = true
        } catch (_: Throwable) {
        }
    }

    fun listen(context: Context) {
        emit(context, "listen", "正在听…")
    }

    fun partial(context: Context, text: String) {
        if (text.isBlank()) return
        emit(context, "partial", text)
    }

    fun result(context: Context, text: String) {
        emit(context, "result", text)
    }

    fun error(context: Context, text: String) {
        emit(context, "error", text)
    }

    fun hide(context: Context) {
        emit(context, "hide", "")
    }

    fun emit(context: Context, state: String, text: String) {
        if (isOwner(context)) {
            apply(context, state, text)
            return
        }
        try {
            val i = Intent(ACTION)
            i.putExtra("state", state)
            i.putExtra("text", text)
            context.sendBroadcast(i)
        } catch (_: Throwable) {
        }
    }

    private fun apply(context: Context, state: String, text: String) {
        if (!isOwner(context) && !recv) return
        val ctx = FreeformRuntime.context ?: context
        main.post {
            when (state) {
                "listen" -> {
                    show(ctx, text.ifBlank { "正在听…" })
                    scheduleHide(12000L)
                }
                "partial" -> {
                    show(ctx, text)
                    scheduleHide(12000L)
                }
                "result" -> {
                    show(ctx, text)
                    scheduleHide(400L)
                }
                "error" -> {
                    show(ctx, text.ifBlank { "没听清" })
                    scheduleHide(800L)
                }
                "hide" -> drop()
            }
        }
    }

    private fun show(ctx: Context, text: String) {
        val manager = wm ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        wm = manager
        val existing = card
        val tv = label
        if (existing != null && tv != null && existing.parent != null) {
            tv.text = text
            place(ctx, manager)
            return
        }
        if (existing != null) drop()
        val cardView = TextView(ctx)
        cardView.text = text
        cardView.setTextColor(0xFF1A1A1A.toInt())
        cardView.textSize = 14f
        cardView.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        cardView.maxWidth = dp(ctx, 220)
        cardView.minWidth = dp(ctx, 88)
        cardView.maxLines = 2
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 16).toFloat()
        bg.setColor(0xFFFFFFFF.toInt())
        cardView.background = bg
        cardView.elevation = dp(ctx, 6).toFloat()
        cardView.isClickable = true
        cardView.setOnClickListener { drop() }
        val lp = baseLp()
        lp.type = 2010
        placeInto(ctx, lp)
        if (!addTyped(manager, cardView, lp)) return
        card = cardView
        label = cardView
        val left = FreeformRuntime.cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
        cardView.alpha = 0f
        cardView.translationX = if (left) -dp(ctx, 10).toFloat() else dp(ctx, 10).toFloat()
        cardView.animate().alpha(1f).translationX(0f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        cardView.post { place(ctx, manager) }
    }

    private fun place(ctx: Context, manager: WindowManager) {
        val v = card ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        placeInto(ctx, lp)
        try {
            manager.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    private fun placeInto(ctx: Context, lp: WindowManager.LayoutParams) {
        FreeformRuntime.reload()
        val cfg = FreeformRuntime.cfg
        val size = screenSize(ctx)
        val thick = thickness()
        val barH = dp(ctx, 88) * 8 / 9
        val top = ((size.y * (cfg.sidebarTopPct / 100f)).toInt()).coerceIn(0, (size.y - barH).coerceAtLeast(0))
        val cardH = card?.height?.takeIf { it > 0 } ?: dp(ctx, 36)
        val left = cfg.sidebarSide != FreeformConfig.SIDE_RIGHT
        lp.gravity = (if (left) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
        lp.x = thick + dp(ctx, 8)
        lp.y = (top + barH / 2 - cardH / 2).coerceAtLeast(0)
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun drop() {
        hideRun?.let { main.removeCallbacks(it) }
        hideRun = null
        val v = card
        val manager = wm
        card = null
        label = null
        if (v == null || manager == null) return
        try {
            v.animate().cancel()
        } catch (_: Throwable) {
        }
        try {
            manager.removeView(v)
        } catch (_: Throwable) {
            try {
                manager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleHide(ms: Long) {
        hideRun?.let { main.removeCallbacks(it) }
        val r = Runnable { drop() }
        hideRun = r
        main.postDelayed(r, ms)
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

    private fun baseLp(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams()
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.packageName = "android"
        lp.title = "chuibing-voice-hud"
        lp.alpha = 1f
        return lp
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

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * (if (d > 0f) d else 2.5f) + 0.5f).toInt()
    }

    private fun isOwner(context: Context): Boolean {
        return try {
            context.packageName == "android"
        } catch (_: Throwable) {
            false
        }
    }
}
