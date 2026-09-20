package id.tntwindow.editor.xposed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.PowerManager
import android.os.UserHandle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import de.robv.android.xposed.XposedHelpers
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.data.TntLaunch
import id.tntwindow.editor.domain.Paths
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object KeyMapActions {
    const val VOICE_ONCE = "once"
    const val VOICE_TOGGLE = "toggle"
    const val VOICE_HOLD = "hold"
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var torchOn = false
    @Volatile var injecting = false
    @Volatile private var screenLock: PowerManager.WakeLock? = null

    fun perform(context: Context, action: KeyAction) {
        run(context, context, action)
    }

    fun run(pwm: Any, context: Context, action: KeyAction, voiceMode: String = VOICE_ONCE) {
        if (action.isNone() || action.isDefault()) return
        try {
            when (action.type) {
                KeyAction.TYPE_BACK -> inject(KeyEvent.KEYCODE_BACK)
                KeyAction.TYPE_HOME -> home(pwm, context)
                KeyAction.TYPE_RECENTS -> recents(pwm)
                KeyAction.TYPE_NOTIFICATIONS -> status(context, "expandNotificationsPanel")
                KeyAction.TYPE_QUICK_SETTINGS -> quick(context)
                KeyAction.TYPE_SHELL -> shell(action)
                KeyAction.TYPE_APP -> launchApp(context, action.pkg)
                KeyAction.TYPE_APP_FREEFORM -> launchFreeform(context, action.pkg, action.cls)
                KeyAction.TYPE_APP_TNT -> launchTnt(context, action.pkg)
                KeyAction.TYPE_VOICE -> launchVoice(context, voiceMode)
                KeyAction.TYPE_ACTIVITY -> launchActivity(context, action.pkg, action.cls)
                KeyAction.TYPE_SHORTCUT -> launchShortcut(context, action)
                KeyAction.TYPE_COPY -> inject(KeyEvent.KEYCODE_COPY)
                KeyAction.TYPE_PASTE -> inject(KeyEvent.KEYCODE_PASTE)
                KeyAction.TYPE_CUT -> inject(KeyEvent.KEYCODE_CUT)
                KeyAction.TYPE_INPUT -> inputText(context, action.text)
                KeyAction.TYPE_RECORD -> record(context)
                KeyAction.TYPE_WIFI -> wifi(context, action.mode)
                KeyAction.TYPE_DATA -> mobileData(context, action.mode)
                KeyAction.TYPE_BT -> bluetooth(action.mode)
                KeyAction.TYPE_LOCATION -> location(context, action.mode)
                KeyAction.TYPE_LOCK -> lock(context)
                KeyAction.TYPE_SCREENSHOT -> inject(KeyEvent.KEYCODE_SYSRQ)
                KeyAction.TYPE_FLASHLIGHT -> flashlight(context, action.mode)
                KeyAction.TYPE_MUTE -> mute(context, action.mode)
                KeyAction.TYPE_PLAY -> inject(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                KeyAction.TYPE_NEXT -> inject(KeyEvent.KEYCODE_MEDIA_NEXT)
                KeyAction.TYPE_PREV -> inject(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                KeyAction.TYPE_KILL_TOP -> killTop(context)
                KeyAction.TYPE_APP_NEXT -> switchApp(context, true)
                KeyAction.TYPE_APP_PREV -> switchApp(context, false)
                KeyAction.TYPE_WAKE -> wake(context)
                KeyAction.TYPE_WAKELOCK -> wakeLock(context, action.mode)
                KeyAction.TYPE_FREEFORM_CURRENT -> FreeformWindows.current(context)
                KeyAction.TYPE_FREEFORM_SIDEBAR -> FreeformModule.openSidebar()
                KeyAction.TYPE_FREEFORM_CLOSE -> FreeformWindows.closeTop()
                KeyAction.TYPE_FREEFORM_FULLSCREEN -> FreeformWindows.fullscreenTop()
                KeyAction.TYPE_ROTATE_REVERSE -> reverseScreen(pwm)
            }
        } catch (_: Throwable) {
        }
    }


    private fun reverseScreen(pwm: Any) {
        var rot = 0
        try {
            rot = XposedHelpers.callMethod(pwm, "getDefaultDisplayRotation") as Int
        } catch (_: Throwable) {
            try {
                val wm = XposedHelpers.getObjectField(pwm, "mWindowManager")
                rot = XposedHelpers.callMethod(wm, "getDefaultDisplayRotation") as Int
            } catch (_: Throwable) {
            }
        }
        val next = (rot + 2) % 4
        try {
            val wms = XposedHelpers.getObjectField(pwm, "mWindowManager")
            XposedHelpers.callMethod(wms, "freezeRotation", next)
        } catch (_: Throwable) {
            try {
                val cl = Class.forName("android.view.WindowManagerGlobal")
                val wms = cl.getMethod("getWindowManagerService").invoke(null)
                XposedHelpers.callMethod(wms, "freezeRotation", next)
            } catch (_: Throwable) {
            }
        }
        try {
            val o = JSONObject()
            o.put("enabled", true)
            o.put("locked", true)
            o.put("rotation", next)
            val f = File(Paths.ROTATION)
            f.parentFile?.mkdirs()
            f.writeText(o.toString(), Charsets.UTF_8)
            File("/data/local/tmp/tnt_rotation_config.json").writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
    fun replay(code: Int) {
        inject(code)
    }

    fun showPowerMenu(pwm: Any) {
        try {
            XposedHelpers.callMethod(pwm, "showGlobalActions")
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(pwm, "showGlobalActionsInternal")
            } catch (_: Throwable) {
            }
        }
    }

    private fun inject(code: Int) {
        injecting = true
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)
            val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, code, 0)
            val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
            XposedHelpers.callMethod(im, "injectInputEvent", down, 0)
            XposedHelpers.callMethod(im, "injectInputEvent", up, 0)
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", code.toString()))
            } catch (_: Throwable) {
            }
        } finally {
            injecting = false
        }
    }

    private fun home(pwm: Any, context: Context) {
        try {
            XposedHelpers.callMethod(pwm, "closeSystemWindows", "homekey")
        } catch (_: Throwable) {
        }
        val intent = homeIntent(context)
        if (startHome(context, pwm, intent)) return
        try {
            XposedHelpers.callMethod(pwm, "startDockOrHome", false, true)
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(pwm, "startDockOrHome", true, true)
            } catch (_: Throwable) {
            }
        }
    }

    private fun startHome(context: Context, pwm: Any, intent: Intent): Boolean {
        val token = Binder.clearCallingIdentity()
        try {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.callMethod(pwm, "startActivityAsUser", intent, UserHandle.getUserHandleForUid(0))
                return true
            } catch (_: Throwable) {
            }
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                XposedHelpers.callMethod(am, "startActivityAsUser", intent, UserHandle.getUserHandleForUid(0))
                return true
            } catch (_: Throwable) {
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return false
    }

    private fun homeIntent(context: Context): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val target = lockedHomeComponent() ?: phoneHomeComponent(context)
        if (target != null) {
            intent.component = target
            intent.setPackage(target.packageName)
            return intent
        }
        try {
            val ri = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val ai = ri?.activityInfo
            val pkg = ai?.packageName
            val name = ai?.name
            if (!pkg.isNullOrBlank() && pkg != "android" && pkg != Paths.DESKTOP && name != null &&
                !name.contains("ResolverActivity") && !name.contains("ChooserActivity")
            ) {
                intent.component = ComponentName(pkg, name)
            }
        } catch (_: Throwable) {
        }
        return intent
    }

    private fun lockedHomeComponent(): ComponentName? {
        val files = arrayOf(Paths.HOME_LOCK, "/data/local/tmp/tnt_home_lock.json")
        for (path in files) {
            try {
                val f = File(path)
                if (!f.exists() || !f.canRead()) continue
                val o = JSONObject(f.readText(Charsets.UTF_8))
                val c = o.optString("component", "").trim()
                val i = c.indexOf('/')
                if (i <= 0 || i >= c.length - 1) continue
                return ComponentName(c.substring(0, i), c.substring(i + 1))
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun phoneHomeComponent(context: Context): ComponentName? {
        val names = listOf(
            ComponentName("com.smartisanos.home", "com.smartisanos.home.Home"),
            ComponentName("com.smartisanos.home", "com.smartisanos.home.Launcher"),
        )
        for (cn in names) {
            try {
                context.packageManager.getActivityInfo(cn, 0)
                return cn
            } catch (_: Throwable) {
            }
        }
        try {
            val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val list = context.packageManager.queryIntentActivities(probe, 0) ?: return null
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == "android" || pkg == Paths.DESKTOP) continue
                if (ai.name.contains("ResolverActivity") || ai.name.contains("ChooserActivity")) continue
                if (pkg.contains("smartisanos.home") || pkg.contains("launcher")) {
                    return ComponentName(pkg, ai.name)
                }
            }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == "android" || pkg == Paths.DESKTOP) continue
                if (ai.name.contains("ResolverActivity") || ai.name.contains("ChooserActivity")) continue
                return ComponentName(pkg, ai.name)
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun recents(pwm: Any) {
        try {
            XposedHelpers.callMethod(pwm, "toggleRecentApps")
        } catch (_: Throwable) {
            inject(KeyEvent.KEYCODE_APP_SWITCH)
        }
    }

    private fun status(context: Context, method: String) {
        val sb = context.getSystemService("statusbar") ?: return
        XposedHelpers.callMethod(sb, method)
    }

    private fun quick(context: Context) {
        val names = listOf(
            "expandQuickSettings",
            "showQuickSettingsPanel",
            "animateExpandSettingsPanel",
            "expandSettingsPanel",
            "togglePanel",
            "openSettingsPanel",
            "expandSettings",
        )
        val sb = context.getSystemService("statusbar")
        if (sb != null && invokeNamed(sb, names)) return
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "statusbar")
            val stubName = "com.android.internal.statusbar.IStatusBarService" + "$" + "Stub"
            val stub = Class.forName(stubName)
            val bar = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder)
            if (bar != null && invokeNamed(bar, names)) return
        } catch (_: Throwable) {
        }
        swipeShade(context, true)
    }

    private fun invokeNamed(target: Any, names: List<String>): Boolean {
        for (name in names) {
            try {
                XposedHelpers.callMethod(target, name)
                return true
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.callMethod(target, name, null as String?)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun swipeShade(context: Context, settings: Boolean) {
        val dm = context.resources.displayMetrics
        val x = if (settings) (dm.widthPixels - 48).toFloat() else dm.widthPixels / 2f
        val y0 = 6f
        val y1 = dm.heightPixels * 0.42f
        injectSwipe(x, y0, x, y1)
    }

    private fun injectSwipe(x0: Float, y0: Float, x1: Float, y1: Float) {
        injecting = true
        try {
            val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x0, y0, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            XposedHelpers.callMethod(im, "injectInputEvent", down, 0)
            down.recycle()
            val steps = 8
            for (i in 1..steps) {
                val t = downAt + i * 12L
                val f = i / steps.toFloat()
                val mv = MotionEvent.obtain(downAt, t, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0)
                mv.source = InputDevice.SOURCE_TOUCHSCREEN
                XposedHelpers.callMethod(im, "injectInputEvent", mv, 0)
                mv.recycle()
            }
            val upAt = downAt + steps * 12L + 16
            val up = MotionEvent.obtain(downAt, upAt, MotionEvent.ACTION_UP, x1, y1, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            XposedHelpers.callMethod(im, "injectInputEvent", up, 0)
            up.recycle()
        } catch (_: Throwable) {
        } finally {
            injecting = false
        }
    }

    private fun shell(action: KeyAction) {
        val cmd = action.cmd.trim()
        if (cmd.isEmpty()) return
        io.execute {
            try {
                val args = when {
                    action.root -> arrayOf("su", "-c", cmd)
                    action.system -> arrayOf("sh", "-c", cmd)
                    else -> arrayOf("sh", "-c", cmd)
                }
                Runtime.getRuntime().exec(args)
            } catch (_: Throwable) {
            }
        }
    }

    private fun launchApp(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchFreeform(context: Context, pkg: String, cls: String = ""): Boolean {
        if (pkg.isBlank()) return false
        val activity = cls.ifBlank {
            try {
                context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className.orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
        if (activity.isBlank()) return false
        val sys = FreeformRuntime.context
        if (sys != null) FreeformWindows.open(sys, pkg, activity)
        else FreeformWindows.enqueue(context, pkg, activity)
        return true
    }

    private fun launchTnt(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        TntLaunch.start(context, intent)
    }

    fun stopVoice(context: Context) {
        launchVoice(context, "stop")
    }

    private fun launchVoice(context: Context, mode: String = VOICE_ONCE) {
        val stop = mode == "stop"
        if (!stop) {
            try {
                val warm = Intent()
                warm.setClassName("id.tntwindow.editor", "id.tntwindow.editor.voice.VoiceWarmService")
                context.startService(warm)
            } catch (_: Throwable) {
            }
        }
        val intent = Intent(if (stop) "id.tntwindow.editor.action.VOICE_STOP" else "id.tntwindow.editor.action.VOICE_LISTEN")
        intent.setClassName("id.tntwindow.editor", "id.tntwindow.editor.voice.VoiceSessionService")
        intent.putExtra("mode", mode)
        try {
            if (stop) context.startService(intent) else context.startForegroundService(intent)
        } catch (_: Throwable) {
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun launchActivity(context: Context, pkg: String, cls: String) {
        if (cls.isBlank()) return
        val intent = Intent()
        intent.component = ComponentName(pkg, cls)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun launchShortcut(context: Context, action: KeyAction) {
        if (action.intent.isNotBlank()) {
            val intent = Intent.parseUri(action.intent, Intent.URI_INTENT_SCHEME)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        if (action.pkg.isNotBlank()) launchApp(context, action.pkg)
    }

    private fun inputText(context: Context, text: String) {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("keymap", text))
        inject(KeyEvent.KEYCODE_PASTE)
    }

    private fun record(context: Context) {
        val pkgs = listOf("com.smartisanos.recorder", "com.android.soundrecorder", "com.smartisanos.sara")
        for (pkg in pkgs) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        val rec = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        rec.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(rec)
        } catch (_: Throwable) {
        }
    }

    private fun wifi(context: Context, mode: String) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val on = when (mode) {
            KeyAction.MODE_ON -> true
            KeyAction.MODE_OFF -> false
            else -> !wm.isWifiEnabled
        }
        wm.isWifiEnabled = on
    }

    private fun mobileData(context: Context, mode: String) {
        val cr = context.contentResolver
        val cur = Settings.Global.getInt(cr, "mobile_data", 1)
        val on = when (mode) {
            KeyAction.MODE_ON -> 1
            KeyAction.MODE_OFF -> 0
            else -> if (cur == 0) 1 else 0
        }
        Settings.Global.putInt(cr, "mobile_data", on)
        try {
            val tm = context.getSystemService("phone")
            XposedHelpers.callMethod(tm, "setDataEnabled", on == 1)
        } catch (_: Throwable) {
        }
    }

    private fun bluetooth(mode: String) {
        val adp = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        val on = when (mode) {
            KeyAction.MODE_ON -> true
            KeyAction.MODE_OFF -> false
            else -> !adp.isEnabled
        }
        if (on) adp.enable() else adp.disable()
    }

    private fun location(context: Context, mode: String) {
        val cr = context.contentResolver
        val cur = Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        val next = when (mode) {
            KeyAction.MODE_ON -> Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            KeyAction.MODE_OFF -> Settings.Secure.LOCATION_MODE_OFF
            else -> if (cur == Settings.Secure.LOCATION_MODE_OFF) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
        }
        Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, next)
    }

    private fun lock(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val now = SystemClock.uptimeMillis()
        try {
            XposedHelpers.callMethod(pm, "goToSleep", now, 4, 0)
            return
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(pm, "goToSleep", now)
            return
        } catch (_: Throwable) {
        }
        inject(KeyEvent.KEYCODE_POWER)
    }

    private fun flashlight(context: Context, mode: String) {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cam.cameraIdList.firstOrNull { cid ->
            cam.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        val on = when (mode) {
            KeyAction.MODE_ON -> true
            KeyAction.MODE_OFF -> false
            else -> !torchOn
        }
        cam.setTorchMode(id, on)
        torchOn = on
    }

    private fun mute(context: Context, mode: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = am.ringerMode
        am.ringerMode = when (mode) {
            KeyAction.MODE_ON -> AudioManager.RINGER_MODE_SILENT
            KeyAction.MODE_OFF -> AudioManager.RINGER_MODE_NORMAL
            else -> if (now == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
        }
    }

    private fun wake(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val now = SystemClock.uptimeMillis()
        try {
            XposedHelpers.callMethod(pm, "wakeUp", now)
            return
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(pm, "wakeUp", now, "android.policy:KEY")
            return
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(pm, "userActivity", now, false)
        } catch (_: Throwable) {
        }
    }

    private fun wakeLock(context: Context, mode: String) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val held = screenLock?.isHeld == true
        val on = when (mode) {
            KeyAction.MODE_ON -> true
            KeyAction.MODE_OFF -> false
            else -> !held
        }
        if (on) {
            if (!held) {
                val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                val wl = pm.newWakeLock(flags, "chuibing:screen")
                wl.setReferenceCounted(false)
                wl.acquire()
                screenLock = wl
            }
            wake(context)
        } else if (held) {
            try {
                screenLock?.release()
            } catch (_: Throwable) {
            }
            screenLock = null
        }
    }

    private fun switchApp(context: Context, next: Boolean) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val tasks = try {
            am.getRunningTasks(20)
        } catch (_: Throwable) {
            return
        } ?: return
        val skip = setOf(
            "id.tntwindow.editor",
            "com.android.systemui",
            "android",
            Paths.DESKTOP,
            Paths.DESKTOP_UI,
            "com.smartisanos.home",
            "com.smartisanos.launcher",
        )
        val usable = tasks.filter { info ->
            val pkg = info.topActivity?.packageName ?: info.baseActivity?.packageName ?: return@filter false
            pkg !in skip
        }
        if (usable.size < 2) return
        val target = if (next) usable[1] else usable[usable.size - 1]
        try {
            XposedHelpers.callMethod(am, "moveTaskToFront", target.id, 0)
        } catch (_: Throwable) {
            try {
                am.moveTaskToFront(target.id, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun killTop(context: Context) {
        io.execute {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val task = am.getRunningTasks(1).firstOrNull()?.topActivity ?: return@execute
                val pkg = task.packageName
                if (pkg == "id.tntwindow.editor" || pkg == "com.android.systemui" || pkg == "android") return@execute
                XposedHelpers.callMethod(am, "forceStopPackage", pkg)
            } catch (_: Throwable) {
            }
        }
    }
}
