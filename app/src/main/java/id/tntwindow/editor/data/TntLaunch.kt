package id.tntwindow.editor.data

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import java.lang.reflect.Modifier

object TntLaunch {
    const val DESKTOP_PKG = "com.smartisanos.desktop"
    const val DESKTOP_CLS = "com.smartisanos.desktop.Desktop"
    const val HOME_PKG = "com.smartisanos.home"
    const val HOME_CLS = "com.smartisanos.home.Home"
    const val EXTRA_DISPLAY = "id.tntwindow.editor.tnt_display"
    const val APP_PKG = "id.tntwindow.editor"
    const val APP_ACTIVITY = "id.tntwindow.editor.MainActivity"
    const val REMOTER_PKG = "com.smartisanos.virtualremoter"

    fun desktopComponent(): ComponentName = ComponentName(DESKTOP_PKG, DESKTOP_CLS)

    fun homeComponent(): ComponentName = ComponentName(HOME_PKG, HOME_CLS)

    fun displayId(context: Context? = null): Int {
        val field = fieldDisplayId()
        if (field > 0) return field
        val mgr = managerDisplayId()
        if (mgr > 0) return mgr
        return displays(context)
    }

    fun start(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_DISPLAY, true)
        val id = displayId(context)
        if (id > 0) {
            try {
                val opts = ActivityOptions.makeBasic()
                opts.launchDisplayId = id
                context.startActivity(intent, opts.toBundle())
                return true
            } catch (_: Throwable) {
            }
            if (startWithAm(intent, id)) return true
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun startDesktop(context: Context? = null): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.component = desktopComponent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (context != null) return start(context, intent)
        val id = displayId(null)
        return startWithAm(intent, id)
    }

    fun applyDisplayOptions(options: Any?, id: Int): Boolean {
        if (options == null || id <= 0) return false
        return try {
            val m = options.javaClass.methods.firstOrNull {
                it.name == "setLaunchDisplayId" && it.parameterTypes.size == 1
            } ?: return false
            m.isAccessible = true
            m.invoke(options, id)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun extractDisplayId(args: Array<Any?>?): Int {
        if (args == null) return 0
        var found = 0
        for (a in args) {
            if (a == null) continue
            if (a is Int && a >= 100000) return a
            val n = a.javaClass.name
            if (n.contains("ActivityOptions") || n.contains("Bundle")) {
                try {
                    val m = a.javaClass.methods.firstOrNull {
                        it.name == "getLaunchDisplayId" && it.parameterTypes.isEmpty()
                    }
                    val v = m?.invoke(a) as? Int ?: 0
                    if (v > 0 && v != Display.DEFAULT_DISPLAY) {
                        if (v >= 100000) return v
                        if (v > found) found = v
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return found
    }

    fun isHome(intent: Intent): Boolean {
        if (intent.hasCategory(Intent.CATEGORY_HOME)) return true
        return intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true
    }

    fun rewriteHome(intent: Intent) {
        intent.component = desktopComponent()
        intent.setPackage(DESKTOP_PKG)
        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_DISPLAY, true)
    }

    fun isPcMode(): Boolean {
        val names = listOf("android.app.SmtPCUtilsInner", "android.app.SmtPCUtils")
        for (name in names) {
            try {
                val clazz = Class.forName(name)
                for (m in clazz.declaredMethods) {
                    if (m.name != "isPcMode" && m.name != "getIsPcMode") continue
                    if (m.parameterTypes.isNotEmpty()) continue
                    m.isAccessible = true
                    val v = if (Modifier.isStatic(m.modifiers)) m.invoke(null) else null
                    if (v is Boolean) return v
                }
                for (f in clazz.declaredFields) {
                    if (f.name != "sIsPcMode" && f.name != "mIsPcMode") continue
                    f.isAccessible = true
                    val v = f.get(null)
                    if (v is Boolean) return v
                }
            } catch (_: Throwable) {
            }
        }
        return displayId(null) > 0
    }

    private fun startWithAm(intent: Intent, id: Int): Boolean {
        val cmd = amCommand(intent, id) ?: return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun amCommand(intent: Intent, id: Int): String? {
        val parts = ArrayList<String>()
        parts.add("am start")
        if (id > 0) {
            parts.add("--display")
            parts.add(id.toString())
        }
        parts.add("-f")
        parts.add(Intent.FLAG_ACTIVITY_NEW_TASK.toString())
        val cmp = intent.component
        if (cmp != null) {
            parts.add("-n")
            parts.add(cmp.flattenToShortString())
        } else {
            val action = intent.action
            if (!action.isNullOrBlank()) {
                parts.add("-a")
                parts.add(action)
            }
            val data = intent.dataString
            if (!data.isNullOrBlank()) {
                parts.add("-d")
                parts.add("'" + data.replace("'", "") + "'")
            }
            if (!intent.`package`.isNullOrBlank()) {
                parts.add("-p")
                parts.add(intent.`package`!!)
            }
        }
        val cats = intent.categories
        if (cats != null) {
            for (c in cats) {
                parts.add("-c")
                parts.add(c)
            }
        }
        return parts.joinToString(" ")
    }

    private fun fieldDisplayId(): Int {
        val names = listOf("android.app.SmtPCUtilsInner", "android.app.SmtPCUtils")
        for (name in names) {
            try {
                val clazz = Class.forName(name)
                for (f in clazz.declaredFields) {
                    if (!f.name.contains("DisplayId")) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(null)
                    } catch (_: Throwable) {
                        null
                    }
                    if (v is Int && v > 0 && v != Display.DEFAULT_DISPLAY) return v
                }
                for (m in clazz.declaredMethods) {
                    val n = m.name
                    if (!(n.contains("ExtDisplayId") || n.contains("DisplayIdInPc") || n == "getExtDisplayId" || n == "getCurrentExtDisplayId")) continue
                    if (m.parameterTypes.isNotEmpty()) continue
                    m.isAccessible = true
                    if (!Modifier.isStatic(m.modifiers)) continue
                    val v = m.invoke(null)
                    if (v is Int && v > 0 && v != Display.DEFAULT_DISPLAY) return v
                }
            } catch (_: Throwable) {
            }
        }
        return 0
    }

    private fun managerDisplayId(): Int {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "smt_pcm") as? IBinder
                ?: return 0
            val stub = Class.forName("android.pc.ISmtPCManager\$Stub")
            val mgr = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return 0
            for (m in mgr.javaClass.methods) {
                if (m.name != "getCurrentExtDisplayId" && m.name != "getExtDisplayId") continue
                if (m.parameterTypes.isNotEmpty()) continue
                val v = m.invoke(mgr)
                if (v is Int && v > 0 && v != Display.DEFAULT_DISPLAY) return v
            }
            0
        } catch (_: Throwable) {
            0
        }
    }

    private fun displays(context: Context?): Int {
        if (context == null) return 0
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0
        var named = 0
        var other = 0
        for (d in dm.displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            val name = try {
                d.name ?: ""
            } catch (_: Throwable) {
                ""
            }.lowercase()
            if (d.displayId >= 100000) return d.displayId
            if (name.contains("tnt") || name.contains("smt") || name.contains("virtual") || name.contains("pc")) {
                named = d.displayId
            } else if (d.displayId > other) {
                other = d.displayId
            }
        }
        return if (named > 0) named else other
    }
}
