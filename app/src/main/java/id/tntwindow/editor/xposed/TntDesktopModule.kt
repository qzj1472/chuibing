package id.tntwindow.editor.xposed

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.data.TntLaunch
import id.tntwindow.editor.domain.Paths
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

class TntDesktopModule : IXposedHookLoadPackage {
    @Volatile private var cachedLock = false
    @Volatile private var cachedComponent = ""
    @Volatile private var cachedAt = 0L
    @Volatile private var lastKickAt = 0L
    @Volatile private var lastKickKind = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val cl = lpparam.classLoader ?: return
        when (pkg) {
            "android", "system" -> {
                hookClass(cl, "com.android.server.am.ActivityStarter") { m ->
                    m.parameterTypes.contains(Intent::class.java)
                }
                hookClass(cl, "com.android.server.am.ActivityStackSupervisor") { m ->
                    m.name.contains("Home") || m.parameterTypes.contains(Intent::class.java)
                }
                hookDefaultLauncher(cl)
                hookHomeResolve(cl)
                hookTntHomeGate(cl)
                hookSwitchHomeDialog(cl)
                hookHomeWrite(cl)
                hookWidgetListen(cl)
                hookTntExit(cl)
            }
            TntLaunch.HOME_PKG, TntLaunch.DESKTOP_PKG, Paths.DESKTOP_UI, Paths.SETTINGS_PKG -> {
                hookDefaultLauncher(cl)
                hookLauncherChecks(cl)
                hookSwitchHomeDialog(cl)
            }
        }
    }

    private fun hookDefaultLauncher(cl: ClassLoader) {
        val accept: (Method) -> Boolean = { m ->
            m.name == "getDefaultLauncherPkg" || m.name == "getHomeActivityForUser"
        }
        hookNamed(cl, "android.app.SmtPCUtilsInner", accept)
        hookNamed(cl, "android.app.SmtPCUtils", accept)
    }

    private fun hookHomeResolve(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.pm.PackageManagerService",
            "android.app.ApplicationPackageManager",
            "android.app.ContextImpl",
        )
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!lockOn() || !pcCaller()) return
                val intent = param.args?.firstOrNull { it is Intent } as? Intent ?: return
                if (TntLaunch.isHome(intent)) spoof(param)
            }
        }
        for (name in names) {
            val clazz = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name != "resolveActivity" && m.name != "resolveActivityAsUser") continue
                if (!m.parameterTypes.contains(Intent::class.java)) continue
                hookOne(m, hook)
            }
        }
    }

    private fun hookTntHomeGate(cl: ClassLoader) {
        val names = listOf("com.android.server.pc.TntManagerService", "com.android.server.pc.SmtPCManagerService")
        val accept: (Method) -> Boolean = { m ->
            val n = m.name.lowercase(Locale.ROOT)
            n.contains("switchhome") || n.contains("defaulthome") || n.contains("defaultlauncher") ||
                n.contains("needswitch") || n.contains("checklauncher") || n.contains("isofficial") ||
                n.contains("ishomevalid") || n.contains("hasvalidhome") || n.contains("getdefaultlauncher")
        }
        for (name in names) hookNamed(cl, name, accept)
    }

    private fun hookLauncherChecks(cl: ClassLoader) {
        val names = listOf("android.app.SmtPCUtilsInner", "android.app.SmtPCUtils")
        val accept: (Method) -> Boolean = { m ->
            val n = m.name.lowercase(Locale.ROOT)
            n.contains("launcher") || n.contains("officialhome") || n.contains("switchhome") ||
                n.contains("defaulthome") || n.contains("smartisanhome") || n.contains("needswitch")
        }
        for (name in names) hookNamed(cl, name, accept)
    }

    private fun hookNamed(cl: ClassLoader, className: String, accept: (Method) -> Boolean) {
        val clazz = try {
            XposedHelpers.findClass(className, cl)
        } catch (_: Throwable) {
            return
        }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (lockOn() && pcCaller()) spoof(param)
            }
        }
        for (m in clazz.declaredMethods) {
            if (accept(m)) hookOne(m, hook)
        }
    }

    private fun hookSwitchHomeDialog(cl: ClassLoader) {
        val block = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!lockOn()) return
                if (!switchHomeText(param.thisObject) && !switchHomeArgs(param.args)) return
                clickPositive(param.thisObject)
                param.result = null
            }
        }
        val dialog = try {
            XposedHelpers.findClass("android.app.Dialog", cl)
        } catch (_: Throwable) {
            null
        }
        if (dialog != null) {
            for (m in dialog.declaredMethods) {
                if (m.name == "show" && m.parameterTypes.isEmpty()) hookOne(m, block)
            }
        }
    }

    private fun clickPositive(obj: Any?) {
        if (obj == null) return
        try {
            val m = obj.javaClass.methods.firstOrNull {
                it.name == "getButton" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Integer::class.java)
            }
            val button = m?.invoke(obj, -1)
            if (button != null) {
                val click = button.javaClass.methods.firstOrNull { it.name == "performClick" && it.parameterTypes.isEmpty() }
                click?.invoke(button)
                return
            }
        } catch (_: Throwable) {
        }
        try {
            val alertField = findField(obj.javaClass, "mAlert")
            val alert = if (alertField != null) {
                alertField.isAccessible = true
                alertField.get(obj) ?: obj
            } else obj
            val msgField = findField(alert.javaClass, "mButtonPositiveMessage")
            if (msgField != null) {
                msgField.isAccessible = true
                val msg = msgField.get(alert)
                if (msg != null) {
                    val objField = findField(msg.javaClass, "obj")
                    if (objField != null) {
                        objField.isAccessible = true
                        val listener = objField.get(msg)
                        if (listener != null) {
                            val onClick = listener.javaClass.methods.firstOrNull {
                                it.name == "onClick" && it.parameterTypes.size == 2
                            }
                            onClick?.invoke(listener, obj, -1)
                        }
                    }
                }
            }
            val pos = findField(alert.javaClass, "mButtonPositive")
            if (pos != null) {
                pos.isAccessible = true
                val btn = pos.get(alert)
                if (btn != null) {
                    val click = btn.javaClass.methods.firstOrNull { it.name == "performClick" && it.parameterTypes.isEmpty() }
                    click?.invoke(btn)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun switchHomeText(obj: Any?): Boolean {
        if (obj == null) return false
        try {
            for (name in listOf("mMessage", "mTitle", "mAlert")) {
                val f = findField(obj.javaClass, name) ?: continue
                f.isAccessible = true
                val v = f.get(obj) ?: continue
                if (v is CharSequence && isSwitchHomeText(v.toString())) return true
                if (switchHomeText(v) && v !== obj) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun switchHomeArgs(args: Array<Any?>?): Boolean {
        if (args == null) return false
        for (a in args) {
            if (a is CharSequence && isSwitchHomeText(a.toString())) return true
        }
        return false
    }

    private fun isSwitchHomeText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase(Locale.ROOT)
        val official = t.contains("锤子") || t.contains("官方") || t.contains("smartisan") || t.contains("坚果")
        val action = t.contains("切换") || t.contains("改回") || t.contains("换回") || t.contains("switch")
        return official && action
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    private fun lockOn(): Boolean {
        refreshLock()
        return cachedLock
    }

    private fun lockedComponent(): String {
        refreshLock()
        return cachedComponent
    }

    private fun refreshLock() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - cachedAt < 800L) return
        cachedAt = now
        var lock = false
        var component = ""
        val files = arrayOf(Paths.HOME_LOCK, "/data/local/tmp/tnt_home_lock.json")
        for (path in files) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                lock = o.optBoolean("lock", false)
                component = o.optString("component", "").trim()
                break
            } catch (_: Throwable) {
            }
        }
        cachedLock = lock
        cachedComponent = component
    }

    private fun spoof(param: XC_MethodHook.MethodHookParam) {
        val name = param.method.name.lowercase(Locale.ROOT)
        val result = param.result
        if (name.contains("needswitch") || name.contains("showswitch") || name.contains("shouldswitch")) {
            if (result is Boolean) param.result = false
            return
        }
        if (result is Boolean) {
            if (name.contains("official") || name.contains("valid") || name.contains("smartisan") ||
                name.contains("isdefault") || name.contains("checklauncher") || name.contains("ishome")
            ) {
                param.result = true
            }
            return
        }
        if (result is String) {
            if (result.contains('.') && result != TntLaunch.HOME_PKG && result != TntLaunch.DESKTOP_PKG) {
                param.result = TntLaunch.HOME_PKG
            }
            return
        }
        if (result is ComponentName) {
            val pkg = result.packageName
            if (pkg != TntLaunch.HOME_PKG && pkg != TntLaunch.DESKTOP_PKG) {
                param.result = TntLaunch.homeComponent()
            }
            return
        }
        if (result is ResolveInfo) {
            try {
                val ai = result.activityInfo ?: return
                if (ai.packageName == TntLaunch.HOME_PKG || ai.packageName == TntLaunch.DESKTOP_PKG) return
                val copy = ResolveInfo(result)
                val info = ActivityInfo(ai)
                info.packageName = TntLaunch.HOME_PKG
                info.name = TntLaunch.HOME_CLS
                copy.activityInfo = info
                param.result = copy
            } catch (_: Throwable) {
            }
            return
        }
        if (result == null && (name.contains("launcher") || name.contains("home"))) {
            val ret = (param.method as Method).returnType
            when {
                ret == String::class.java -> param.result = TntLaunch.HOME_PKG
                ret == ComponentName::class.java -> param.result = TntLaunch.homeComponent()
                ret == java.lang.Boolean.TYPE || ret == java.lang.Boolean::class.java -> {
                    param.result = !(name.contains("need") || name.contains("switch"))
                }
            }
        }
    }

    private fun pcCaller(): Boolean {
        val st = Throwable().stackTrace
        for (e in st) {
            val n = (e.className + "." + e.methodName).lowercase(Locale.ROOT)
            if (n.contains("smtpc") || n.contains("pcmode") || n.contains("smt_pcm") ||
                n.contains("extdisplay") || n.contains("tntmanager") || n.contains("easycast") ||
                n.contains("smtsetdesktop")
            ) return true
        }
        return false
    }

    private fun hookClass(cl: ClassLoader, name: String, accept: (Method) -> Boolean) {
        val clazz = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                patchStart(param)
            }
        }
        for (m in clazz.declaredMethods) {
            if (accept(m)) hookOne(m, hook)
        }
    }

    private fun patchStart(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        var intent: Intent? = null
        var options: Any? = null
        for (a in args) {
            if (a is Intent && intent == null) intent = a
            if (a != null) {
                val n = a.javaClass.name
                if (n.contains("ActivityOptions")) options = a
            }
        }
        if (intent == null) return
        val displayId = TntLaunch.extractDisplayId(args)
        interceptPhoneHome(intent, displayId)
        val marked = intent.getBooleanExtra(TntLaunch.EXTRA_DISPLAY, false)
        if (!marked && !(displayId > 0 && TntLaunch.isHome(intent))) return
        val tntId = TntLaunch.displayId(null)
        val id = if (displayId > 0) displayId else tntId
        TntLaunch.applyDisplayOptions(options, id)
        if (TntLaunch.isHome(intent) && displayId > 0) {
            TntLaunch.rewriteHome(intent)
        }
        if (TntLaunch.isHome(intent) && marked && tntId > 0) {
            TntLaunch.rewriteHome(intent)
            TntLaunch.applyDisplayOptions(options, tntId)
        }
    }


    private fun interceptPhoneHome(intent: Intent, displayId: Int) {
        if (!lockOn()) return
        if (displayId > 0) return
        if (pcCaller()) return
        val c = lockedComponent()
        if (!c.contains('/')) return
        if (c.startsWith(TntLaunch.HOME_PKG) || c.startsWith(TntLaunch.DESKTOP_PKG)) return
        val cmp = intent.component
        if (cmp != null && cmp.packageName == TntLaunch.DESKTOP_PKG) return
        if (cmp != null && (cmp.packageName + "/" + cmp.className) == c) return
        val official = cmp != null && cmp.packageName == TntLaunch.HOME_PKG
        if (!official && !TntLaunch.isHome(intent)) return
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        intent.component = ComponentName(pkg, cls)
        intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun hookHomeWrite(cl: ClassLoader) {
        val clazz = try {
            XposedHelpers.findClass("com.android.server.pm.PackageManagerService", cl)
        } catch (_: Throwable) {
            return
        }
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!lockOn()) return
                val name = param.method.name
                if (name == "clearPackagePreferredActivities") {
                    val pkg = param.args?.firstOrNull { it is String } as? String ?: return
                    val locked = lockedComponent().substringBefore('/')
                    if (pkg == locked || pkg == TntLaunch.HOME_PKG) skipCall(param)
                    return
                }
                val filter = param.args?.firstOrNull { it is IntentFilter } as? IntentFilter
                if (name == "setHomeActivity" || (filter != null && isHomeFilter(filter))) {
                    skipCall(param)
                }
            }
        }
        for (m in clazz.declaredMethods) {
            val n = m.name
            if (n == "setHomeActivity" || n == "replacePreferredActivity" || n == "replacePreferredActivityInternal" ||
                n == "addPreferredActivity" || n == "addPreferredActivityInternal" || n == "clearPackagePreferredActivities"
            ) {
                hookOne(m, skip)
            }
        }
    }

    private fun isHomeFilter(filter: IntentFilter): Boolean {
        return filter.hasCategory(Intent.CATEGORY_HOME)
    }

    private fun skipCall(param: XC_MethodHook.MethodHookParam) {
        val n = (param.method as Method).returnType.name
        param.result = when (n) {
            "void" -> null
            "boolean", "java.lang.Boolean" -> java.lang.Boolean.TRUE
            "int", "java.lang.Integer" -> 0
            else -> null
        }
    }

    private fun hookWidgetListen(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.appwidget.AppWidgetServiceImpl",
            "com.android.server.appwidget.AppWidgetService",
        )
        val start = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!lockOn()) return
                val c = lockedComponent()
                if (!c.contains('/') || c.startsWith(TntLaunch.HOME_PKG) || c.startsWith(TntLaunch.DESKTOP_PKG)) return
                val pkg = callerPkg(param)
                if (pkg != TntLaunch.HOME_PKG) return
                try {
                    param.result = java.util.ArrayList<Any>()
                } catch (_: Throwable) {
                    param.result = null
                }
            }
        }
        val stop = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!lockOn()) return
                val c = lockedComponent()
                if (!c.contains('/')) return
                val locked = c.substringBefore('/')
                if (locked == TntLaunch.HOME_PKG || locked == TntLaunch.DESKTOP_PKG) return
                val pkg = callerPkg(param)
                if (pkg != locked) return
                skipCall(param)
            }
        }
        for (name in names) {
            val clazz = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name == "startListening") hookOne(m, start)
                if (m.name == "stopListening") hookOne(m, stop)
            }
        }
    }

    private fun callerPkg(param: XC_MethodHook.MethodHookParam): String {
        val args = param.args ?: return ""
        for (a in args) {
            if (a is String && a.contains('.') && !a.contains('/')) return a
        }
        return ""
    }

    private fun hookTntExit(cl: ClassLoader) {
        val names = listOf(
            "android.app.SmtPCUtilsInner",
            "android.app.SmtPCUtils",
            "com.android.server.pc.TntManagerService",
            "com.android.server.pc.SmtPCManagerService",
        )
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val mode = modeArg(param) ?: return
                if (mode == 0) kick("exit")
                else if (mode == 1 || mode == 2) kick("enter")
            }
        }
        for (name in names) {
            val clazz = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name == "smtSetDesktopMode") hookOne(m, hook)
            }
        }
    }

    private fun modeArg(param: XC_MethodHook.MethodHookParam): Int? {
        val args = param.args ?: return null
        for (a in args) {
            if (a is Int) return a
            if (a is Number) return a.toInt()
        }
        return null
    }

    private fun kick(kind: String) {
        if (!lockOn()) return
        if (File("/data/local/tmp/tnt_hotswap.lck").isDirectory) return
        val now = android.os.SystemClock.uptimeMillis()
        if (kind == lastKickKind && now - lastKickAt < 4000L) return
        lastKickAt = now
        lastKickKind = kind
        Thread {
            try {
                File("/data/local/tmp/tnt_home_kick").writeText(kind)
            } catch (_: Throwable) {
            }
            if (kind != "exit") return@Thread
            try {
                Thread.sleep(5200)
            } catch (_: Throwable) {
            }
            if (File("/data/local/tmp/tnt_hotswap.lck").isDirectory) return@Thread
            val f = File("/data/local/tmp/tnt_home_kick")
            if (!f.exists()) return@Thread
            try {
                f.delete()
            } catch (_: Throwable) {
            }
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "sh /data/local/tmp/tnt_home_watch.sh recover"))
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun hookOne(method: Method, hook: XC_MethodHook) {
        try {
            XposedBridge.hookMethod(method, hook)
        } catch (_: Throwable) {
            try {
                val types = method.parameterTypes
                val args = arrayOfNulls<Any>(types.size + 1)
                for (i in types.indices) args[i] = types[i]
                args[types.size] = hook
                XposedHelpers.findAndHookMethod(method.declaringClass.name, method.declaringClass.classLoader, method.name, *args)
            } catch (_: Throwable) {
            }
        }
    }
}
