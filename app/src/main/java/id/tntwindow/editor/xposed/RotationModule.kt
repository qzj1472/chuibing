package id.tntwindow.editor.xposed

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.RotationConfig
import java.io.File

class RotationModule : IXposedHookLoadPackage {
    @Volatile private var cfg = RotationConfig.default()
    @Volatile private var cfgAt = -1L
    @Volatile private var cfgText = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val cl = lpparam.classLoader
        if (pkg == "android" || pkg == "system") {
            hookPwm(cl)
            hookRecord(cl)
            hookToken(cl)
            hookWms(cl)
            hookInfo(cl)
        }
        hookActivity(cl)
        hookInfo(cl)
    }

    private fun hookPwm(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.policy.PhoneWindowManagerSMT",
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.OemPhoneWindowManager",
            "com.android.server.policy.SmartisanPhoneWindowManager",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                if (m.name == "rotationForOrientationLw") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!ready()) return
                                allowAll(param.thisObject)
                                val ix = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Integer::class.java }
                                if (ix >= 0) param.args[ix] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!ready()) return
                                allowAll(param.thisObject)
                                if (cfg.locked) {
                                    param.result = cfg.rotation
                                    return
                                }
                                val sensor = proposed(param.thisObject)
                                if (sensor >= 0) param.result = sensor
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (m.name == "needSensorRunningLp") {
                    hookAfterTrue(m)
                }
                if (m.name == "rotationHasCompatibleMetricsLw") {
                    hookAfterTrue(m)
                }
                if (m.name == "isDefaultOrientationForced") {
                    hookAfterFalse(m)
                }
                if (m.name == "systemReady" || m.name == "systemBooted") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) allowAll(param.thisObject)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun hookRecord(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.am.ActivityRecord",
            "com.android.server.wm.ActivityRecord",
            "com.android.server.am.TaskRecord",
            "com.android.server.wm.Task",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (c in cls.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(c, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (ready()) forceFields(param.thisObject)
                        }
                    })
                } catch (_: Throwable) {
                }
            }
            for (m in cls.declaredMethods) {
                if (m.name == "setRequestedOrientation" || m.name == "setOrientation") {
                    val ix = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Integer::class.java }
                    if (ix < 0) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ready()) param.args[ix] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) forceFields(param.thisObject)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (m.name == "getOrientation" || m.name == "getRequestedOrientation") {
                    if (m.returnType != Int::class.javaPrimitiveType && m.returnType != Integer::class.java) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) param.result = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (m.name == "getMaxAspectRatio") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) param.result = 0f
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun hookToken(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.wm.AppWindowToken",
            "com.android.server.wm.ActivityRecord",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                if (m.name == "setOrientation") {
                    val ix = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Integer::class.java }
                    if (ix < 0) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ready()) param.args[ix] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) forceFields(param.thisObject)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (m.name == "getOrientation" || m.name == "getOverrideOrientation") {
                    if (m.returnType != Int::class.javaPrimitiveType && m.returnType != Integer::class.java) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) param.result = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun hookWms(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.wm.WindowManagerService",
            "com.android.server.wm.DisplayRotation",
            "com.android.server.wm.DisplayContent",
        )
        for (name in names) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                val n = m.name
                if (n.startsWith("freeze")) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ready()) param.result = null
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (n == "setUserRotationMode") {
                    val ix = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Integer::class.java }
                    if (ix < 0) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ready()) param.args[ix] = 0
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (n == "updateDisplayAndOrientation" || n == "computeScreenConfiguration") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ready()) setNum(param.thisObject, "mAltOrientation", 0)
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (ready()) fixDisplayInfo(param.thisObject)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (n == "setAltOrientation") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!ready()) return
                                val a0 = param.args.getOrNull(0) ?: return
                                if (a0 is Boolean) param.args[0] = false
                                else if (a0 is java.lang.Boolean) param.args[0] = false
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
                if (n == "getAltOrientation") {
                    hookAfterFalse(m)
                }
                if (n == "setLastOrientation") {
                    val ix = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Integer::class.java }
                    if (ix >= 0) {
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (ready()) param.args[ix] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                                }
                            })
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (n == "updateRotationUnchecked" || n == "updateRotationUncheckedLocked" || n == "updateRotation") {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!ready()) return
                                setNum(param.thisObject, "mAltOrientation", 0)
                                fixDisplayInfo(param.thisObject)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun hookInfo(cl: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass("android.content.pm.ActivityInfo", cl)
        } catch (_: Throwable) {
            return
        }
        for (m in cls.declaredMethods) {
            val n = m.name
            if (n == "isFixedOrientation" || n == "isFixedOrientationLandscape" || n == "isFixedOrientationPortrait") {
                hookAfterFalse(m)
            }
            if (n == "getMaxAspectRatio") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (ready()) param.result = 0f
                        }
                    })
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun hookActivity(cl: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass("android.app.Activity", cl)
        } catch (_: Throwable) {
            return
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "setRequestedOrientation", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ready()) param.args[0] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            })
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "getRequestedOrientation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (ready()) param.result = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            })
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "onConfigurationChanged", Configuration::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ready()) return
                    val c = param.args[0] as? Configuration ?: return
                    if (c.orientation == Configuration.ORIENTATION_PORTRAIT && c.screenWidthDp > c.screenHeightDp) {
                        c.orientation = Configuration.ORIENTATION_LANDSCAPE
                    }
                    if (c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenHeightDp > c.screenWidthDp) {
                        c.orientation = Configuration.ORIENTATION_PORTRAIT
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun hookAfterTrue(m: java.lang.reflect.Method) {
        try {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (ready()) param.result = true
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun hookAfterFalse(m: java.lang.reflect.Method) {
        try {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (ready()) param.result = false
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun forceFields(obj: Any) {
        setNum(obj, "mRequestedOrientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        setNum(obj, "requestedOrientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        setNum(obj, "mOrientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        setNum(obj, "mOverrideOrientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
    }

    private fun allowAll(pwm: Any) {
        setNum(pwm, "mAllowAllRotations", 1)
        setNum(pwm, "mAltOrientation", 0)
        if (cfg.locked) {
            setNum(pwm, "mUserRotationMode", 1)
            setNum(pwm, "mUserRotation", cfg.rotation)
        } else {
            setNum(pwm, "mUserRotationMode", 0)
        }
    }

    private fun getNum(obj: Any, name: String): Int {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                val t = f.type
                if (t == Int::class.javaPrimitiveType || t == Integer::class.java) return f.getInt(obj)
                if (t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java) return if (f.getBoolean(obj)) 1 else 0
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return -1
    }

    private fun getObj(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun fixDisplayInfo(dc: Any) {
        val rot = getNum(dc, "mRotation")
        val bw = getNum(dc, "mBaseDisplayWidth")
        val bh = getNum(dc, "mBaseDisplayHeight")
        if (bw <= 0 || bh <= 0 || rot < 0) return
        val rotated = rot == 1 || rot == 3
        val dw = if (rotated) bh else bw
        val dh = if (rotated) bw else bh
        val info = getObj(dc, "mDisplayInfo") ?: return
        setNum(info, "logicalWidth", dw)
        setNum(info, "logicalHeight", dh)
        setNum(info, "appWidth", dw)
        setNum(info, "appHeight", dh)
    }

    private fun setNum(obj: Any, name: String, v: Int) {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                val t = f.type
                if (t == Int::class.javaPrimitiveType || t == Integer::class.java) {
                    f.setInt(obj, v)
                } else if (t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java) {
                    f.setBoolean(obj, v != 0)
                } else {
                    f.set(obj, v)
                }
                return
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
    }

    private fun proposed(pwm: Any): Int {
        val fields = listOf("mOrientationListener", "mWindowOrientationListener")
        for (f in fields) {
            val listener = try {
                XposedHelpers.getObjectField(pwm, f)
            } catch (_: Throwable) {
                null
            } ?: continue
            val v = try {
                XposedHelpers.callMethod(listener, "getProposedRotation") as? Int
            } catch (_: Throwable) {
                null
            }
            if (v != null && v >= 0) return v
        }
        return -1
    }

    private fun ready(): Boolean {
        reload()
        return cfg.enabled
    }

    private fun reload() {
        val files = listOf(File(Paths.ROTATION), File("/data/local/tmp/tnt_rotation_config.json"))
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
            cfg = RotationConfig.fromJson(hit.second)
            cfgText = hit.second
            cfgAt = hit.third
        } catch (_: Throwable) {
        }
    }
}
