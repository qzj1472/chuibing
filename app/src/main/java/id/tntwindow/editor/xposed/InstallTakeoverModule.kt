package id.tntwindow.editor.xposed

import android.app.Activity
import android.content.ClipData
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
import java.io.File
import java.lang.reflect.Method

class InstallTakeoverModule : IXposedHookLoadPackage {
    @Volatile private var cfg = InstallConfig.default()
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
        }
        if (pkg.contains("packageinstaller")) hookInstaller(cl)
    }

    private fun hookInstaller(cl: ClassLoader) {
        val names = listOf(
            "com.android.packageinstaller.PackageInstallerActivity",
            "com.android.packageinstaller.InstallStart",
            "com.android.packageinstaller.InstallStaging",
            "com.android.packageinstaller.television.PackageInstallerActivity",
            "com.android.packageinstaller.handheld.PackageInstallerActivity",
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
        try {
            act.startActivity(next)
            act.finish()
        } catch (t: Throwable) {
            XposedBridge.log("chuibing install redirect fail " + t.javaClass.simpleName)
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
        if (!isInstall(intent)) return false
        val cur = intent.component
        if (cur != null && cur.packageName == cfg.pkg && cur.className == cfg.cls) return false
        return try {
            applyTarget(intent)
            true
        } catch (t: Throwable) {
            XposedBridge.log("chuibing install rewrite fail " + t.javaClass.simpleName)
            false
        }
    }

    private fun applyTarget(intent: Intent) {
        val data = intent.data
        intent.selector = null
        intent.setPackage(null)
        intent.component = ComponentName(cfg.pkg, cfg.cls)
        if (intent.action.isNullOrBlank()) {
            intent.action = if (data != null) Intent.ACTION_VIEW else Intent.ACTION_INSTALL_PACKAGE
        }
        if (intent.type.isNullOrBlank() && isApkUri(data)) {
            intent.setDataAndType(data, "application/vnd.android.package-archive")
        }
        if (intent.clipData == null && data != null) {
            intent.clipData = ClipData.newRawUri("", data)
        }
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    private fun isInstall(intent: Intent): Boolean {
        val cls = intent.component?.className.orEmpty()
        val low = cls.lowercase()
        if (low.contains("uninstall") || low.contains("uninstaller") || low.contains("deletepackage")) return false
        if (
            low.contains("packageinstalleractivity") ||
            low.contains("installstart") ||
            low.contains("installstaging") ||
            low.contains("installappprogress") ||
            (low.contains("package") && low.contains("installer"))
        ) return true
        val action = intent.action.orEmpty()
        if (action == Intent.ACTION_UNINSTALL_PACKAGE || action == Intent.ACTION_DELETE) return false
        if (action == Intent.ACTION_INSTALL_PACKAGE) return true
        if (action == "android.intent.action.PACKAGE_INSTALL") return true
        if (action == "android.content.pm.action.CONFIRM_INSTALL") return true
        if (action.endsWith(".INSTALL_PACKAGE") || action.endsWith(".CONFIRM_INSTALL")) return true
        val type = intent.type.orEmpty().lowercase()
        if (type == "application/vnd.android.package-archive") return true
        if (hasInstallExtra(intent)) return true
        if (isApkUri(intent.data)) return true
        val stream = try {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } catch (_: Throwable) {
            null
        }
        if (isApkUri(stream)) return true
        return false
    }

    private fun hasInstallExtra(intent: Intent): Boolean {
        val keys = listOf(
            Intent.EXTRA_NOT_UNKNOWN_SOURCE,
            Intent.EXTRA_INSTALLER_PACKAGE_NAME,
            "android.content.pm.extra.SESSION_ID",
            "EXTRA_SESSION_ID",
        )
        for (k in keys) {
            if (try { intent.hasExtra(k) } catch (_: Throwable) { false }) return true
        }
        return false
    }

    private fun isApkUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val last = uri.lastPathSegment.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return last.endsWith(".apk") || path.endsWith(".apk")
    }

    private fun ready(): Boolean = cfg.enabled && cfg.pkg.isNotBlank() && cfg.cls.isNotBlank()

    private fun reload() {
        val files = listOf(File(Paths.INSTALL), File("/data/local/tmp/tnt_install_config.json"))
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
            cfg = InstallConfig.fromJson(hit.second)
            cfgText = hit.second
            cfgAt = hit.third
        } catch (_: Throwable) {
        }
    }
}
