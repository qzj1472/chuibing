package id.tntwindow.editor.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.IBinder
import android.view.Display
import android.view.Surface

object ScreenLuma {
    fun of(rect: Rect, rotation: Int = 0, display: Display? = null): Float {
        val bmp = shot(rect, rotation, 32, 16, display) ?: return -1f
        val luma = lumaOf(bmp, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return luma
    }

    fun ofBand(rect: Rect, displayW: Int, displayH: Int, rotation: Int, display: Display? = null): Float {
        val mapped = mapRect(rect, displayW, displayH, rotation)
        val a = of(rect, 0, display)
        if (a >= 12f) return a
        val b = of(mapped, 0, display)
        if (b >= 12f) return b
        val c = of(rect, rotation, display)
        if (c >= 12f) return c
        val d = of(mapped, rotation, display)
        if (d >= 12f) return d
        return listOf(a, b, c, d).maxOrNull() ?: -1f
    }

    fun darkIcons(luma: Float): Boolean = luma >= 140f

    fun contrast(luma: Float): Int {
        return if (darkIcons(luma)) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
    }

    private fun mapRect(rect: Rect, dw: Int, dh: Int, rotation: Int): Rect {
        val out = when (rotation) {
            Surface.ROTATION_90 -> Rect(rect.top, dw - rect.right, rect.bottom, dw - rect.left)
            Surface.ROTATION_180 -> Rect(dw - rect.right, dh - rect.bottom, dw - rect.left, dh - rect.top)
            Surface.ROTATION_270 -> Rect(dh - rect.bottom, rect.left, dh - rect.top, rect.right)
            else -> Rect(rect)
        }
        if (out.left > out.right) {
            val x = out.left
            out.left = out.right
            out.right = x
        }
        if (out.top > out.bottom) {
            val y = out.top
            out.top = out.bottom
            out.bottom = y
        }
        return out
    }

    private fun lumaOf(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Float {
        val w = (right - left).coerceAtLeast(0)
        val h = (bottom - top).coerceAtLeast(0)
        if (w < 2 || h < 2) return -1f
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        val stepX = if (w > 8) w / 8 else 1
        val stepY = if (h > 8) h / 8 else 1
        var y = top
        while (y < bottom && y < bmp.height) {
            var x = left
            while (x < right && x < bmp.width) {
                val c = bmp.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n <= 0) return -1f
        return (0.299f * r + 0.587f * g + 0.114f * b) / n
    }

    private fun shot(rect: Rect, rotation: Int, destW: Int = 0, destH: Int = 0, display: Display? = null): Bitmap? {
        val clz = try {
            Class.forName("android.view.SurfaceControl")
        } catch (_: Throwable) {
            return null
        }
        val srcW = rect.width().coerceAtLeast(4)
        val srcH = rect.height().coerceAtLeast(4)
        val w = if (destW > 0) destW.coerceAtMost(srcW).coerceAtLeast(4) else srcW
        val h = if (destH > 0) destH.coerceAtMost(srcH).coerceAtLeast(4) else srcH
        val id = try {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Throwable) {
            Display.DEFAULT_DISPLAY
        }
        val tokens = tokens(clz, display, id)
        for (token in tokens) {
            val bmp = shotToken(clz, token, rect, w, h, rotation)
            if (bmp != null) return bmp
        }
        if (id != Display.DEFAULT_DISPLAY) return null
        val tries = listOf<(Class<*>) -> Any?>(
            { c ->
                c.getDeclaredMethod(
                    "screenshot",
                    Rect::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(null, rect, w, h, rotation)
            },
            { c ->
                c.getDeclaredMethod(
                    "screenshot",
                    Rect::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(null, rect, w, h, 0, 220000, true, rotation)
            },
        )
        for (t in tries) {
            val v = try {
                t(clz)
            } catch (_: Throwable) {
                null
            }
            if (v is Bitmap && v.width > 0 && v.height > 0) return v
        }
        return null
    }

    private fun shotToken(clz: Class<*>, token: IBinder, rect: Rect, w: Int, h: Int, rotation: Int): Bitmap? {
        val tries = listOf<(Class<*>) -> Any?>(
            { c ->
                c.getDeclaredMethod(
                    "screenshot",
                    IBinder::class.java,
                    Rect::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(null, token, rect, w, h, 0, 220000, true, rotation)
            },
            { c ->
                c.getDeclaredMethod(
                    "screenshot",
                    IBinder::class.java,
                    Rect::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(null, token, rect, w, h, rotation)
            },
        )
        for (t in tries) {
            val v = try {
                t(clz)
            } catch (_: Throwable) {
                null
            }
            if (v is Bitmap && v.width > 0 && v.height > 0) return v
        }
        return null
    }

    private fun tokens(clz: Class<*>, display: Display?, id: Int): List<IBinder> {
        val out = ArrayList<IBinder>()
        addToken(out, call0(display, "getDisplayToken"))
        addToken(out, fieldToken(display, "mDisplayToken"))
        addToken(out, builtIn(clz, id))
        if (id != Display.DEFAULT_DISPLAY) {
            addToken(out, builtIn(clz, 1))
            addToken(out, managerToken(id))
        } else {
            addToken(out, builtIn(clz, 0))
        }
        return out
    }

    private fun addToken(out: ArrayList<IBinder>, token: IBinder?) {
        if (token == null) return
        if (out.any { it === token }) return
        out.add(token)
    }

    private fun builtIn(clz: Class<*>, id: Int): IBinder? {
        if (id < 0 || id > 5) return null
        return try {
            clz.getDeclaredMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, id) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun call0(obj: Any?, name: String): IBinder? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: return null
            m.isAccessible = true
            m.invoke(obj) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun fieldToken(obj: Any?, name: String): IBinder? {
        if (obj == null) return null
        return try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(obj) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun managerToken(id: Int): IBinder? {
        return try {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val inst = global.getMethod("getInstance").invoke(null) ?: return null
            val dm = try {
                val f = global.getDeclaredField("mDm")
                f.isAccessible = true
                f.get(inst)
            } catch (_: Throwable) {
                null
            } ?: return null
            for (m in dm.javaClass.methods) {
                if (!m.name.contains("Token", ignoreCase = true)) continue
                if (m.parameterTypes.size != 1) continue
                val p0 = m.parameterTypes[0]
                val arg: Any? = when {
                    p0 == Int::class.javaPrimitiveType || p0 == Integer::class.java -> id
                    p0 == Long::class.javaPrimitiveType || p0 == java.lang.Long::class.java -> id.toLong()
                    else -> continue
                }
                m.isAccessible = true
                val v = try {
                    m.invoke(dm, arg)
                } catch (_: Throwable) {
                    null
                }
                if (v is IBinder) return v
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
