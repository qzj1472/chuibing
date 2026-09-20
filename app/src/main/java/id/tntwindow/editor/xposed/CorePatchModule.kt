package id.tntwindow.editor.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.CorePatchConfig
import id.tntwindow.editor.domain.Paths
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class CorePatchModule : IXposedHookLoadPackage {
    @Volatile private var cached = CorePatchConfig.default()
    @Volatile private var cachedAt = -1L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val cl = lpparam.classLoader
        if (pkg == "android" || pkg == "system") {
            hookSystem(cl)
            hookFramework(cl)
        } else if (isInstaller(pkg)) {
            hookInstaller(cl)
            hookFramework(cl)
        }
        hookHiddenApi(cl)
    }

    private fun hookSystem(cl: ClassLoader) {
        hookDowngrade(cl)
        hookSigningCompare(cl)
        hookUsePreSig(cl)
        hookVerificationAgent(cl)
        hookSharedUser(cl)
        hookBlocklist(cl)
    }

    private fun hookInstaller(cl: ClassLoader) {
        hookSigningCompare(cl)
        hookDowngrade(cl)
    }

    private fun hookFramework(cl: ClassLoader) {
        hookDigest(cl)
        hookArsc(cl)
        hookSigningDetails(cl)
    }

    private fun hookDowngrade(cl: ClassLoader) {
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().downgrade) return
                val n = param.method.name
                if (!n.contains("owngrade") && n != "isInstallOfAlreadyInstalledPackageAllowed") return
                if (n.startsWith("is") || n.startsWith("can") || n.startsWith("allow")) {
                    param.result = true
                } else {
                    skipAsSuccess(param)
                }
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageManagerServiceUtils",
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.InstallPackageHelper",
            "com.android.server.pm.ScanPackageUtils",
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.InstallArgs",
            "com.android.server.pm.InstallingSession",
            "com.android.server.pm.PackageInstallerSession",
        )
        for (c in classes) {
            hookMethods(cl, c, skip) { it.contains("owngrade") }
        }
        forceTrue(cl, "com.android.server.pm.PackageManagerServiceUtils", "isDowngradePermitted")
        skipVoid(cl, "com.android.server.pm.PackageManagerServiceUtils", "checkDowngrade")
        skipVoid(cl, "com.android.server.pm.InstallPackageHelper", "checkDowngrade")
        skipVoid(cl, "com.android.server.pm.PackageManagerService", "checkDowngrade")
    }

    private fun hookDigest(cl: ClassLoader) {
        val skipIntegrity = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().digestVerify) return
                skipAsSuccess(param)
            }
        }
        val swallow = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!cfg().digestVerify) return
                val t = param.throwable ?: return
                if (isSignatureThrowable(t)) {
                    val m = param.method as? Method
                    if (m == null || m.returnType == Void.TYPE || m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Int::class.javaPrimitiveType) {
                        param.throwable = null
                        if (m != null && m.returnType == Boolean::class.javaPrimitiveType) param.result = true
                        if (m != null && m.returnType == Int::class.javaPrimitiveType) param.result = 1
                    }
                }
            }
        }
        val names = listOf(
            "android.util.apk.ApkSignatureVerifier",
            "android.util.apk.ApkSigningBlockUtils",
            "android.util.apk.ApkSignatureSchemeV2Verifier",
            "android.util.apk.ApkSignatureSchemeV3Verifier",
            "android.util.apk.ApkSignatureSchemeV31Verifier",
            "android.util.apk.ApkSignatureSchemeV4Verifier",
            "android.content.pm.PackageParser",
            "android.content.pm.parsing.ApkLiteParseUtils",
        )
        for (c in names) {
            hookAll(cl, c, "verifyIntegrity", skipIntegrity)
            hookAll(cl, c, "verifyIntegrityRecursive", skipIntegrity)
            hookAll(cl, c, "unsafeGetCertsWithoutVerification", null)
            hookMethods(cl, c, swallow) { n ->
                n.contains("erify") || n.contains("ollectCert") || n.contains("ignature")
            }
        }
        forceInt(cl, "android.util.apk.ApkSignatureVerifier", "getMinimumSignatureSchemeVersionForTargetSdk", 1)
        hookStrictJar(cl)
    }

    private fun hookStrictJar(cl: ClassLoader) {
        val cls = findClass(cl, "android.util.jar.StrictJarFile") ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().digestVerify) return
                for (i in param.args.indices) {
                    if (param.args[i] is Boolean) {
                        param.args[i] = false
                        break
                    }
                }
            }
        }
        for (c in cls.declaredConstructors) hookMember(c, hook)
    }

    private fun hookArsc(cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().arsc) return
                skipAsSuccess(param)
            }
        }
        val swallow = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!cfg().arsc) return
                val t = param.throwable ?: return
                if (isArscThrowable(t)) {
                    param.throwable = null
                    skipAsSuccess(param)
                }
            }
        }
        val classes = listOf(
            "android.content.pm.parsing.ApkLiteParseUtils",
            "android.content.pm.parsing.ParsingPackageUtils",
            "com.android.internal.pm.pkg.parsing.ParsingPackageUtils",
            "com.android.server.pm.pkg.parsing.ParsingPackageUtils",
            "android.content.pm.PackageParser",
            "android.content.pm.PackageParser2",
            "android.util.apk.ApkSignatureVerifier",
        )
        for (c in classes) {
            hookMethods(cl, c, hook) { n ->
                n.contains("Uncompressed") || n.contains("Aligned") || n.contains("Arsc") || n.contains("ARSC")
            }
            hookMethods(cl, c, swallow) { n ->
                n.contains("arse") || n.contains("erify") || n.contains("ssert")
            }
        }
    }

    private fun hookSigningDetails(cl: ClassLoader) {
        val cap = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val flags = lastInt(param.args) ?: return
                if (shouldForceCapability(flags)) param.result = true
            }
        }
        val exact = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (cfg().exactSig || cfg().signature || cfg().usePreSig) param.result = true
            }
        }
        val match = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (cfg().exactSig) param.result = true
            }
        }
        val classes = listOf(
            "android.content.pm.SigningDetails",
            "android.content.pm.PackageParser\$SigningDetails",
        )
        for (c in classes) {
            hookAll(cl, c, "checkCapability", cap)
            hookAll(cl, c, "checkCapabilityRecover", cap)
            hookAll(cl, c, "hasAncestorOrSelf", exact)
            hookAll(cl, c, "hasCommonSigner", exact)
            hookAll(cl, c, "hasAncestor", exact)
            hookAll(cl, c, "signaturesMatchExactly", match)
        }
    }

    private fun hookSigningCompare(cl: ClassLoader) {
        val ok = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().signature && !cfg().usePreSig) return
                val m = param.method as? Method ?: return
                when {
                    m.returnType == Boolean::class.javaPrimitiveType || m.returnType == java.lang.Boolean::class.java -> param.result = true
                    m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java -> param.result = 0
                    m.returnType == Void.TYPE -> param.result = null
                }
            }
        }
        val swallow = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!cfg().signature && !cfg().usePreSig) return
                val t = param.throwable ?: return
                val code = errorCode(t)
                if (code == -7 || code == -12 || code == -103 || code == -105 || code == -118 || isSignatureThrowable(t)) {
                    param.throwable = null
                    skipAsSuccess(param)
                }
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageManagerServiceUtils",
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.InstallPackageHelper",
            "com.android.server.pm.ReconcilePackageUtils",
            "com.android.server.pm.ScanPackageUtils",
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.VerifyPackageHelper",
        )
        for (c in classes) {
            hookAll(cl, c, "verifySignatures", ok)
            hookAll(cl, c, "compareSignatures", ok)
            hookAll(cl, c, "checkSignatures", ok)
            hookAll(cl, c, "doesSignatureMatchForPermissions", ok)
            hookMethods(cl, c, swallow) { n ->
                n.contains("ignature") || n.contains("econcile") || n.contains("ssert")
            }
        }
    }

    private fun hookUsePreSig(cl: ClassLoader) {
        val replace = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().usePreSig) return
                if (param.args.isEmpty()) return
                val host = param.thisObject ?: return
                val name = packageNameOf(host) ?: return
                val existing = existingSigning(name) ?: return
                param.args[0] = existing
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageSetting",
            "com.android.server.pm.parsing.pkg.PackageImpl",
            "com.android.internal.pm.parsing.pkg.PackageImpl",
            "android.content.pm.parsing.ParsingPackageImpl",
            "com.android.server.pm.pkg.PackageStateImpl",
            "com.android.internal.pm.parsing.pkg.ParsedPackageImpl",
        )
        for (c in classes) {
            hookAll(cl, c, "setSigningDetails", replace)
        }
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!cfg().usePreSig) return
                val obj = param.result ?: param.thisObject ?: return
                applyExistingSigning(obj)
                for (a in param.args) {
                    if (a != null) applyExistingSigning(a)
                }
            }
        }
        hookAll(cl, "com.android.server.pm.InstallPackageHelper", "preparePackageLI", after)
        hookAll(cl, "com.android.server.pm.ScanPackageUtils", "scanPackageOnlyLI", after)
        hookAll(cl, "com.android.server.pm.ReconcilePackageUtils", "reconcilePackages", after)
    }

    private fun hookVerificationAgent(cl: ClassLoader) {
        val disable = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().verifyAgent) return
                val m = param.method as? Method ?: return
                val n = m.name
                if (n.startsWith("is") || n.startsWith("need")) {
                    param.result = false
                    return
                }
                val rt = m.returnType
                when {
                    rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java -> param.result = true
                    rt.isArray -> {
                        val comp = rt.componentType
                        if (comp != null) param.result = java.lang.reflect.Array.newInstance(comp, 0)
                    }
                    java.util.List::class.java.isAssignableFrom(rt) -> param.result = emptyList<Any>()
                    rt == String::class.java -> param.result = null
                    rt == Void.TYPE -> param.result = null
                }
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.VerifyingSession",
            "com.android.server.pm.PackageVerificationState",
            "com.android.server.pm.VerificationParams",
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.InstallPackageHelper",
        )
        for (c in classes) {
            hookAll(cl, c, "isVerificationEnabled", disable)
            hookAll(cl, c, "getRequiredVerifierLPr", disable)
            hookAll(cl, c, "getRequiredVerifierPackages", disable)
            hookAll(cl, c, "getRequiredVerifiersLPr", disable)
            hookAll(cl, c, "isInstallAllowed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (cfg().verifyAgent) param.result = true
                }
            })
            hookAll(cl, c, "isVerificationComplete", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (cfg().verifyAgent) param.result = true
                }
            })
        }
        hookMethods(cl, "com.android.server.pm.PackageManagerService", disable) { n ->
            n.contains("erifier") || n.contains("erification")
        }
    }

    private fun hookSharedUser(cl: ClassLoader) {
        val swallow = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!cfg().sharedUser) return
                val t = param.throwable ?: return
                val code = errorCode(t)
                if (code == -8 || code == -107 || (t.message ?: "").contains("shared user", true)) {
                    param.throwable = null
                    skipAsSuccess(param)
                }
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageManagerServiceUtils",
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.InstallPackageHelper",
            "com.android.server.pm.ReconcilePackageUtils",
            "com.android.server.pm.ScanPackageUtils",
        )
        for (c in classes) {
            hookMethods(cl, c, swallow) { n ->
                n.contains("hared") || n.contains("econcile") || n.contains("ignature")
            }
        }
    }

    private fun hookBlocklist(cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().blocklist) return
                val n = param.method.name
                if (n.startsWith("is") || n.startsWith("has")) param.result = false
                else if (n.startsWith("can") || n.startsWith("allow")) param.result = true
                else skipAsSuccess(param)
            }
        }
        val classes = listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.ComputerEngine",
            "com.android.server.pm.InstallPackageHelper",
            "com.nothing.server.pm.NothingPackageManagerService",
            "com.nothing.server.pm.PackageBlockManager",
        )
        for (c in classes) {
            hookMethods(cl, c, hook) { n ->
                val low = n.lowercase()
                if (low.contains("uninstall")) false
                else low.contains("block") || low.contains("blacklist") || low.contains("denylist") || low.contains("forbidden")
            }
        }
    }

    private fun hookHiddenApi(cl: ClassLoader) {
        val allow = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!cfg().hiddenApi) return
                val info = param.thisObject ?: return
                if (!isSystemApp(info)) return
                val m = param.method as? Method ?: return
                if (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == java.lang.Boolean::class.java) {
                    param.result = true
                } else if (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java) {
                    param.result = 0
                }
            }
        }
        hookAll(cl, "android.content.pm.ApplicationInfo", "isAllowedToUseHiddenApis", allow)
        hookAll(cl, "android.content.pm.ApplicationInfo", "getHiddenApiEnforcementPolicy", allow)
        hookAll(cl, "android.content.pm.ApplicationInfo", "isPackageWhitelistedForHiddenApis", allow)
    }

    private fun isSystemApp(info: Any): Boolean {
        return try {
            val raw = XposedHelpers.getObjectField(info, "flags") as? Number ?: return false
            val flags = raw.toInt()
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun shouldForceCapability(flags: Int): Boolean {
        val c = cfg()
        if ((flags and 16) != 0) return false
        val data = flags and 1 != 0
        val shared = flags and 2 != 0
        val perm = flags and 4 != 0
        val rollback = flags and 8 != 0
        if (c.signature && (data || perm || rollback)) return true
        if (c.sharedUser && shared) return true
        if (c.usePreSig && (data || perm || shared || rollback)) return true
        return false
    }

    private fun applyExistingSigning(obj: Any) {
        val name = packageNameOf(obj) ?: return
        val existing = existingSigning(name) ?: return
        for (n in listOf("setSigningDetails", "setSigningInfo")) {
            try {
                XposedHelpers.callMethod(obj, n, existing)
                return
            } catch (_: Throwable) {
            }
        }
        for (f in listOf("mSigningDetails", "signingDetails")) {
            if (setField(obj, f, existing)) return
        }
    }

    private fun existingSigning(packageName: String): Any? {
        val ctx = systemContext() ?: return null
        return try {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(packageName, 0x40 or 0x08000000)
            val info = pi.signingInfo ?: return null
            XposedHelpers.callMethod(info, "getSigningDetails")
        } catch (_: Throwable) {
            null
        }
    }

    private fun packageNameOf(obj: Any): String? {
        for (n in listOf("getPackageName", "getName")) {
            try {
                val v = XposedHelpers.callMethod(obj, n) as? String
                if (!v.isNullOrBlank()) return v
            } catch (_: Throwable) {
            }
        }
        for (f in listOf("packageName", "mPackageName")) {
            try {
                val v = XposedHelpers.getObjectField(obj, f) as? String
                if (!v.isNullOrBlank()) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun isInstaller(pkg: String): Boolean {
        if (pkg.contains("packageinstaller")) return true
        if (pkg.contains("permissioncontroller")) return true
        return pkg in setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }

    private fun cfg(): CorePatchConfig {
        val files = listOf(File(Paths.COREPATCH), File("/data/local/tmp/tnt_corepatch_config.json"))
        var newest = 0L
        var chosen: File? = null
        for (f in files) {
            if (!f.exists() || !f.canRead()) continue
            if (f.lastModified() >= newest) {
                newest = f.lastModified()
                chosen = f
            }
        }
        if (chosen == null) {
            cached = CorePatchConfig.default()
            cachedAt = 0L
            return cached
        }
        if (newest == cachedAt) return cached
        cached = try {
            val text = chosen.readText(Charsets.UTF_8)
            if (text.isBlank()) CorePatchConfig.default() else CorePatchConfig.fromJson(text)
        } catch (_: Throwable) {
            CorePatchConfig.default()
        }
        cachedAt = newest
        return cached
    }

    private fun systemContext(): Context? {
        return try {
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            val m = at.getDeclaredMethod("currentActivityThread")
            m.isAccessible = true
            val inst = m.invoke(null) ?: return null
            XposedHelpers.callMethod(inst, "getSystemContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun forceTrue(cl: ClassLoader, className: String, method: String) {
        hookAll(cl, className, method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (cfg().downgrade) param.result = true
            }
        })
    }

    private fun forceInt(cl: ClassLoader, className: String, method: String, value: Int) {
        hookAll(cl, className, method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (cfg().digestVerify) param.result = value
            }
        })
    }

    private fun skipVoid(cl: ClassLoader, className: String, method: String) {
        hookAll(cl, className, method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (cfg().downgrade) param.result = null
            }
        })
    }

    private fun skipAsSuccess(param: XC_MethodHook.MethodHookParam) {
        val m = param.method
        val rt = when (m) {
            is Method -> m.returnType
            is Constructor<*> -> Void.TYPE
            else -> Void.TYPE
        }
        param.result = when {
            rt == Void.TYPE || rt == Void::class.java -> null
            rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java -> true
            rt == Int::class.javaPrimitiveType || rt == Integer::class.java -> 1
            rt == Long::class.javaPrimitiveType || rt == java.lang.Long::class.java -> 1L
            rt.isArray -> { val comp = rt.componentType; if (comp != null) java.lang.reflect.Array.newInstance(comp, 0) else null }
            java.util.List::class.java.isAssignableFrom(rt) -> emptyList<Any>()
            else -> null
        }
    }

    private fun lastInt(args: Array<Any?>): Int? {
        for (i in args.indices.reversed()) {
            val v = args[i]
            if (v is Int) return v
        }
        return null
    }

    private fun errorCode(t: Throwable): Int? {
        var cur: Throwable? = t
        while (cur != null) {
            for (f in listOf("error", "errorCode", "mError")) {
                try {
                    val v = XposedHelpers.getObjectField(cur, f)
                    if (v is Int) return v
                } catch (_: Throwable) {
                }
            }
            cur = cur.cause
        }
        return null
    }

    private fun isSignatureThrowable(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val n = cur.javaClass.simpleName
            val m = (cur.message ?: "").lowercase()
            if (n.contains("Security") || n.contains("Digest") || n.contains("Signature") || n.contains("Certificate")) return true
            if (m.contains("signature") || m.contains("digest") || m.contains("certificate") || m.contains("inconsistent")) return true
            cur = cur.cause
        }
        return false
    }

    private fun isArscThrowable(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val m = (cur.message ?: "").lowercase()
            if (m.contains("resources.arsc") || m.contains("4-byte") || m.contains("uncompressed")) return true
            cur = cur.cause
        }
        return false
    }

    private fun hookAll(cl: ClassLoader, className: String, method: String, hook: XC_MethodHook?) {
        if (hook == null) return
        val cls = findClass(cl, className) ?: return
        try {
            XposedBridge.hookAllMethods(cls, method, hook)
        } catch (_: Throwable) {
        }
    }

    private fun hookMethods(cl: ClassLoader, className: String, hook: XC_MethodHook, match: (String) -> Boolean) {
        val cls = findClass(cl, className) ?: return
        for (m in cls.declaredMethods) {
            if (match(m.name)) hookMember(m, hook)
        }
    }

    private fun hookMember(member: java.lang.reflect.Member, hook: XC_MethodHook) {
        try {
            XposedBridge.hookMethod(member, hook)
        } catch (_: Throwable) {
        }
    }

    private fun findClass(cl: ClassLoader?, name: String): Class<*>? {
        return try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            null
        }
    }

    private fun setField(obj: Any, name: String, value: Any?): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return true
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return false
    }
}