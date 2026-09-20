package id.tntwindow.editor.xposed

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.InstallConfig
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.UninstallConfig
import java.io.File
import java.lang.reflect.Method

class UninstallTakeoverModule : IXposedHookLoadPackage {
    @Volatile private var cfg = UninstallConfig.default()
    @Volatile private var cfgAt = -1L
    @Volatile private var cfgText = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val cl = lpparam.classLoader
        hookIntentMethods(cl, "android.app.Instrumentation") { n -> n.startsWith("execStartActivity") }
        hookIntentMethods(cl, "android.app.ContextImpl") { n -> n.startsWith("startActivity") }
        hookIntentMethods(cl, "android.app.Activity") { n -> n.startsWith("startActivity") }
        if (pkg == "android" || pkg == "system") {
            hookIntentMethods(cl, "com.android.server.am.ActivityStarter") { n ->
                n.startsWith("startActivity") || n == "execute"
            }
            hookIntentMethods(cl, "com.android.server.am.ActivityStackSupervisor") { n ->
                n.startsWith("startActivity") || n == "startActivityMayWait" || n.startsWith("resolve")
            }
            hookIntentMethods(cl, "com.android.server.am.ActivityStarterImpl") { n ->
                n.startsWith("startActivity") || n == "execute"
            }
            hookIntentMethods(cl, "com.android.server.pm.PackageInstallerService") { n ->
                n.startsWith("uninstall")
            }
        }
        if (pkg.contains("packageinstaller")) hookInstaller(cl)
    }

    private fun hookInstaller(cl: ClassLoader) {
        val names = listOf(
            "com.android.packageinstaller.UninstallerActivity",
            "com.android.packageinstaller.television.UninstallerActivity",
            "com.android.packageinstaller.handheld.UninstallerActivity",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            try {
                XposedHelpers.findAndHookMethod(cls, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        redirectInstaller(param.thisObject)
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun redirectInstaller(obj: Any?) {
        val act = obj as? Activity ?: return
        reload()
        if (!ready()) return
        val intent = try {
            act.intent
        } catch (_: Throwable) {
            null
        } ?: return
        val cur = intent.component
        if (cur != null && cur.packageName == cfg.pkg && cur.className == cfg.cls) return
        val next = Intent(intent)
        applyTarget(next)
        if (next.data == null) return
        try {
            act.startActivity(next)
            act.finish()
        } catch (t: Throwable) {
            XposedBridge.log("chuibing uninstall redirect fail " + t.javaClass.simpleName)
        }
    }

    private fun hookIntentMethods(cl: ClassLoader, name: String, accept: (String) -> Boolean) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            return
        }
        for (m in cls.declaredMethods) {
            if (!accept(m.name)) continue
            val ix = m.parameterTypes.indexOfFirst { it == Intent::class.java }
            if (ix < 0) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[ix] as? Intent ?: return
                        if (!rewrite(intent)) return
                        patchResolved(param, m)
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun rewrite(intent: Intent): Boolean {
        reload()
        if (!ready()) return false
        if (!isUninstall(intent)) return false
        val cur = intent.component
        if (cur != null && cur.packageName == cfg.pkg && cur.className == cfg.cls) return false
        return try {
            applyTarget(intent)
            true
        } catch (t: Throwable) {
            XposedBridge.log("chuibing uninstall rewrite fail " + t.javaClass.simpleName)
            false
        }
    }

    private fun applyTarget(intent: Intent) {
        val data = intent.data
        val extraPkg = extraPackage(intent)
        intent.selector = null
        intent.setPackage(null)
        intent.component = ComponentName(cfg.pkg, cfg.cls)
        if (intent.action.isNullOrBlank()) intent.action = Intent.ACTION_UNINSTALL_PACKAGE
        if (intent.data == null) {
            val pkg = extraPkg ?: data?.schemeSpecificPart
            if (!pkg.isNullOrBlank()) intent.data = Uri.fromParts("package", pkg, null)
        }
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun patchResolved(param: XC_MethodHook.MethodHookParam, m: Method) {
        val info = targetInfo() ?: return
        val types = m.parameterTypes
        for (i in types.indices) {
            if (types[i] == ActivityInfo::class.java) param.args[i] = info
        }
    }

    private fun targetInfo(): ActivityInfo? {
        return try {
            val at = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread").invoke(null)
            val ctx = XposedHelpers.callMethod(at, "getSystemContext") as android.content.Context
            ctx.packageManager.getActivityInfo(ComponentName(cfg.pkg, cfg.cls), 0)
        } catch (_: Throwable) {
            null
        }
    }

    private fun extraPackage(intent: Intent): String? {
        val keys = listOf(Intent.EXTRA_PACKAGE_NAME, "android.intent.extra.PACKAGE_NAME", "package", "pkg")
        for (k in keys) {
            val v = try {
                intent.getStringExtra(k)
            } catch (_: Throwable) {
                null
            }
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun isUninstall(intent: Intent): Boolean {
        val cls = intent.component?.className.orEmpty()
        val low = cls.lowercase()
        if (low.contains("uninstalleractivity") || low.contains("packageuninstall") || low.contains("uninstallactivity")) return true
        val action = intent.action.orEmpty()
        if (action == Intent.ACTION_UNINSTALL_PACKAGE) return true
        val scheme = intent.data?.scheme
        if (action == Intent.ACTION_DELETE && (scheme == "package" || extraPackage(intent) != null)) return true
        if (action.endsWith(".UNINSTALL_PACKAGE") || action.endsWith(".UNINSTALL")) return true
        return false
    }

    private fun ready(): Boolean {
        reload()
        return cfg.enabled && cfg.pkg.isNotBlank() && cfg.cls.isNotBlank()
    }

    private fun readNewest(paths: List<String>): Pair<String, Long>? {
        var best: Pair<String, Long>? = null
        for (path in paths) {
            try {
                val f = File(path)
                if (!f.exists() || f.length() <= 0L) continue
                val text = f.readText(Charsets.UTF_8)
                if (text.isBlank()) continue
                val at = try { f.lastModified() } catch (_: Throwable) { 0L }
                if (best == null || at >= best.second) best = text to at
            } catch (_: Throwable) {
            }
        }
        return best
    }

    private fun reload() {
        val instHit = readNewest(listOf(Paths.INSTALL, "/data/local/tmp/tnt_install_config.json"))
        if (instHit != null) {
            try {
                val inst = InstallConfig.fromJson(instHit.first)
                if (inst.shareUninstall && inst.enabled && inst.pkg.isNotBlank() && inst.cls.isNotBlank()) {
                    cfg = UninstallConfig(enabled = true, pkg = inst.pkg, cls = inst.cls)
                    cfgText = "share:" + instHit.first
                    cfgAt = instHit.second
                    return
                }
            } catch (_: Throwable) {
            }
        }
        val files = listOf(File(Paths.UNINSTALL), File("/data/local/tmp/tnt_uninstall_config.json"))
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
            cfg = UninstallConfig.fromJson(hit.second)
            cfgText = hit.second
            cfgAt = hit.third
        } catch (_: Throwable) {
        }
    }
}
