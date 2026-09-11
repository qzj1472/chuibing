package id.tntwindow.editor.xposed

import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.WebViewConfig
import java.io.File
import java.lang.reflect.Array as JArray

class WebViewProviderModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" && lpparam.packageName != "system") return
        mark("load " + lpparam.packageName)
        val packagesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    inject(param)
                } catch (t: Throwable) {
                    mark("inject " + t.javaClass.simpleName + " " + (t.message ?: ""))
                }
            }
        }
        val names = listOf(
            "com.android.server.webkit.SystemImpl",
            "com.android.server.webkit.WebViewUpdateServiceImpl",
        )
        for (name in names) {
            hookNamed(name, lpparam.classLoader, "getWebViewPackages", packagesHook)
        }
        hookReturn("com.android.server.webkit.WebViewUpdateServiceImpl", lpparam.classLoader, "providerHasValidSignature", true)
        hookReturn("com.android.server.webkit.WebViewUpdateServiceImpl", lpparam.classLoader, "validityResult", 0)
    }

    private fun hookNamed(className: String, cl: ClassLoader, methodName: String, callback: XC_MethodHook) {
        val clazz = try {
            XposedHelpers.findClass(className, cl)
        } catch (t: Throwable) {
            mark("no class " + className + " " + (t.message ?: ""))
            return
        }
        val hookers = de.robv.android.xposed.XposedBridge::class.java.declaredMethods.filter { it.name == "hookMethod" }
        var n = 0
        for (target in clazz.declaredMethods) {
            if (target.name != methodName) continue
            for (h in hookers) {
                if (h.parameterTypes.size != 2) continue
                try {
                    h.isAccessible = true
                    h.invoke(null, target, callback)
                    n++
                    break
                } catch (t: Throwable) {
                    mark("hookMethod " + methodName + " " + t.javaClass.simpleName + " " + (t.message ?: ""))
                }
            }
        }
        mark("hooked methods " + n + " " + className + "." + methodName)
    }

    private fun hookReturn(className: String, cl: ClassLoader, methodName: String, value: Any) {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = value
            }
        }
        hookNamed(className, cl, methodName, callback)
    }

    private fun inject(param: XC_MethodHook.MethodHookParam) {
        val cfg = config()
        if (!cfg.inject) return
        val raw = param.result ?: return
        if (raw !is Array<*>) return
        val origin = raw.filterNotNull().toMutableList()
        if (origin.isEmpty()) return
        val template = origin[0]
        val existing = HashSet<String>()
        for (item in origin) {
            val pkg = field(item, "packageName") as? String
            if (!pkg.isNullOrBlank()) existing.add(pkg)
        }
        val ctx = (param.thisObject?.let { field(it, "mContext") } as? Context) ?: systemContext()
        if (ctx == null) {
            mark("no context")
            return
        }
        val pm = ctx.packageManager
        val extra = LinkedHashSet<String>()
        extra.addAll(cfg.extra)
        extra.addAll(scan(pm))
        extra.remove("com.thinkdifferent.anywebview")
        val add = ArrayList<Any>()
        for (pkg in extra) {
            if (pkg in existing) continue
            val info = try {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            } catch (_: Throwable) {
                continue
            }
            val sigs = info.signatures
            if (sigs == null || sigs.isEmpty()) {
                mark("no sig " + pkg)
                continue
            }
            val label = try {
                pm.getApplicationLabel(info.applicationInfo).toString()
            } catch (_: Throwable) {
                pkg
            }
            val made = makeProvider(template, pkg, label, sigs)
            if (made == null) {
                mark("make fail " + pkg)
                continue
            }
            add += made
            existing.add(pkg)
        }
        if (add.isEmpty()) return
        val merged = origin + add
        val typed = JArray.newInstance(template.javaClass, merged.size)
        for (i in merged.indices) JArray.set(typed, i, merged[i])
        param.result = typed
        val host = param.thisObject
        if (host != null) setField(host, "mWebViewProviderPackages", typed)
        mark("added " + add.size + " " + add.joinToString(",") { (field(it, "packageName") as? String) ?: "?" })
    }

    private fun makeProvider(template: Any, pkg: String, desc: String, signatures: Array<android.content.pm.Signature>?): Any? {
        val cls = template.javaClass
        val stringSigs = signatures?.let { stringSignatures(it) }
        val argsList = ArrayList<Array<Any?>>()
        if (stringSigs != null) argsList += arrayOf<Any?>(pkg, desc, true, false, stringSigs)
        if (signatures != null) argsList += arrayOf<Any?>(pkg, desc, true, false, signatures)
        argsList += arrayOf<Any?>(pkg, desc, true, false)
        for (args in argsList) {
            val made = newInstance(cls, args)
            if (made != null) return made
        }
        return try {
            val p = Parcel.obtain()
            (template as Parcelable).writeToParcel(p, 0)
            p.setDataPosition(0)
            val creator = staticField(cls, "CREATOR") as Parcelable.Creator<*>
            val copy = creator.createFromParcel(p)
            p.recycle()
            setField(copy, "packageName", pkg)
            setField(copy, "description", desc)
            setField(copy, "availableByDefault", true)
            val encoded = signatureValue(copy, signatures)
            if (encoded != null) setField(copy, "signatures", encoded)
            copy
        } catch (t: Throwable) {
            mark("parcel " + pkg + " " + t.javaClass.simpleName + " " + (t.message ?: ""))
            null
        }
    }

    private fun signatureValue(template: Any, signatures: Array<android.content.pm.Signature>?): Any? {
        if (signatures == null) return null
        val f = findField(template.javaClass, "signatures") ?: return stringSignatures(signatures)
        val type = f.type
        if (type.isArray && type.componentType == String::class.java) {
            return stringSignatures(signatures)
        }
        return signatures
    }

    private fun stringSignatures(signatures: Array<android.content.pm.Signature>): Array<String> {
        return signatures.map { Base64.encodeToString(it.toByteArray(), Base64.DEFAULT).trim() }.toTypedArray()
    }

    private fun scan(pm: PackageManager): List<String> {
        val out = ArrayList<String>()
        val list = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (_: Throwable) {
            return out
        }
        for (p in list) {
            val name = p.packageName ?: continue
            val md = p.applicationInfo?.metaData
            val hit = name.contains("webview", true) ||
                md?.containsKey("com.android.webview.WebViewLibrary") == true ||
                md?.containsKey("android.webkit.WebViewLibrary") == true ||
                name == "com.android.chrome" ||
                name.startsWith("com.chrome.")
            if (hit) out += name
        }
        return out
    }

    private fun config(): WebViewConfig {
        val files = listOf(
            "/data/system/tnt_webview_config.json",
            "/data/local/tmp/tnt_webview_config.json",
        )
        for (path in files) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            val text = try {
                f.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                continue
            }
            if (text.isBlank()) continue
            return try {
                WebViewConfig.fromJson(text)
            } catch (_: Throwable) {
                WebViewConfig.default()
            }
        }
        return WebViewConfig.default()
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

    private fun mark(msg: String) {
        try {
            File("/data/system/tnt_webview_hook.log").appendText(
                System.currentTimeMillis().toString() + " " + msg + "\n",
                Charsets.UTF_8,
            )
        } catch (_: Throwable) {
        }
    }

    private fun field(obj: Any, name: String): Any? {
        return try {
            XposedHelpers.getObjectField(obj, name)
        } catch (_: Throwable) {
            try {
                val f = findField(obj.javaClass, name) ?: return null
                f.isAccessible = true
                f.get(obj)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        try {
            val f = findField(obj.javaClass, name) ?: return
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) {
        }
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun staticField(cls: Class<*>, name: String): Any? {
        return try {
            val f = findField(cls, name) ?: return null
            f.isAccessible = true
            f.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun newInstance(cls: Class<*>, args: Array<Any?>): Any? {
        for (c in cls.declaredConstructors) {
            if (c.parameterTypes.size != args.size) continue
            try {
                c.isAccessible = true
                return c.newInstance(*args)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}