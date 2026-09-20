package id.tntwindow.editor.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.tntwindow.editor.Feel
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.domain.BuiltinKeys
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.KeyBinding
import id.tntwindow.editor.domain.KeyCombo
import id.tntwindow.editor.domain.KeyMapConfig
import id.tntwindow.editor.domain.KeyNames
import id.tntwindow.editor.domain.Paths
import org.json.JSONObject
import java.io.File

class KeyMapModule : IXposedHookLoadPackage {
    @Volatile private var cfg = KeyMapConfig.default()
    @Volatile private var cfgAt = -1L
    @Volatile private var cfgFailAt = -2L
    @Volatile private var capOn = false
    @Volatile private var capCode = 0
    private val lock = Any()
    private var handler: Handler? = null
    private var pending: Pending? = null
    private var combo: ComboRun? = null
    private val comboHold = HashSet<Int>()
    private val hookedKeys = HashSet<String>()
    private var lastStamp = 0L
    private var lastCode = 0
    private var lastAct = -1
    private var lastRepeat = -1
    private var lastEat = false
    @Volatile private var policy: Any? = null
    @Volatile private var smartSeen = 0
    @Volatile private var lastFpAt = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg != "android" && pkg != "system") return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val event = param.args.firstOrNull { it is KeyEvent } as? KeyEvent ?: return
                    val name = (param.method as? java.lang.reflect.Method)?.name ?: ""
                    if (!eat(param.thisObject, event, name)) return
                    val rt = (param.method as? java.lang.reflect.Method)?.returnType ?: return
                    param.result = when (rt) {
                        java.lang.Long.TYPE, java.lang.Long::class.java -> -1L
                        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> true
                        Void.TYPE -> null
                        else -> 0
                    }
                } catch (_: Throwable) {
                }
            }
        }
        val names = listOf(
            "com.android.server.policy.PhoneWindowManagerSMT",
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.OemPhoneWindowManager",
            "com.android.server.policy.SmartisanPhoneWindowManager",
            "com.android.server.policy.PhoneWindowManagerEx",
            "com.android.server.policy.TntPhoneWindowManagerImpl",
            "com.android.server.policy.PhoneCaseKeyManager",
            "com.android.server.policy.GlobalKeyManager",
            "com.android.server.wm.WindowManagerService",
        )
        var hooked = false
        for (name in names) {
            val cls = findClass(lpparam.classLoader, name) ?: continue
            if (name.contains("PhoneWindowManager")) hookCtor(cls)
            if (hookKeyMethods(cls, hook)) hooked = true
            try {
                for (inner in cls.declaredClasses) {
                    if (hookKeyMethods(inner, hook)) hooked = true
                }
            } catch (_: Throwable) {
            }
        }
        if (hookInputFilter(lpparam.classLoader, hook)) hooked = true
        hookFingerprint(lpparam.classLoader)
        reload()
        if (hooked) XposedBridge.log("chuibing keymap hooked")
    }

    private fun hookCtor(cls: Class<*>) {
        try {
            for (c in cls.declaredConstructors) {
                XposedBridge.hookMethod(c, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        policy = param.thisObject
                    }
                })
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookKeyMethods(cls: Class<*>, hook: XC_MethodHook): Boolean {
        var ok = false
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            val methods = try {
                cur.declaredMethods
            } catch (_: Throwable) {
                emptyArray<java.lang.reflect.Method>()
            }
            for (m in methods) {
                if (!shouldHookKey(cur, m)) continue
                val key = cur.name + "#" + m.name + "#" + m.parameterTypes.joinToString { it.name }
                if (!hookedKeys.add(key)) continue
                try {
                    XposedBridge.hookMethod(m, hook)
                    ok = true
                } catch (_: Throwable) {
                    hookedKeys.remove(key)
                }
            }
            cur = cur.superclass
        }
        return ok
    }

    private fun shouldHookKey(cls: Class<*>, m: java.lang.reflect.Method): Boolean {
        val name = m.name
        if (name == "interceptKeyBeforeQueueing" ||
            name == "interceptKeyBeforeDispatching" ||
            name == "interceptKeyForExtendScreen" ||
            name == "handleSmartKeyBeforeDispatching" ||
            name == "interceptSmartDoubleClick" ||
            name == "interceptKeyChord" ||
            name == "interceptKeyChordInternal") return true
        if (!name.contains("SmartKey")) return false
        val cn = cls.name
        if (!cn.contains("PhoneWindowManager") && !cn.contains("PhoneCaseKeyManager")) return false
        return m.parameterTypes.any { it == KeyEvent::class.java || it.name == "android.view.KeyEvent" }
    }

    private fun hookInputFilter(cl: ClassLoader, hook: XC_MethodHook): Boolean {
        val ims = findClass(cl, "com.android.server.input.InputManagerService") ?: return false
        val targets = ArrayList<Class<*>>()
        targets += ims
        try {
            targets += ims.declaredClasses
        } catch (_: Throwable) {
        }
        val named = findClass(cl, "com.android.server.input.InputManagerService" + '$' + "InputFilterHost")
        if (named != null) targets += named
        var ok = false
        for (cls in targets.distinct()) {
            val methods = try {
                cls.declaredMethods
            } catch (_: Throwable) {
                continue
            }
            for (m in methods) {
                if (m.name != "filterInputEvent") continue
                val key = cls.name + "#" + m.name + "#" + m.parameterTypes.joinToString { it.name }
                if (!hookedKeys.add(key)) continue
                try {
                    XposedBridge.hookMethod(m, hook)
                    ok = true
                } catch (_: Throwable) {
                    hookedKeys.remove(key)
                }
            }
        }
        return ok
    }

    private fun hookFingerprint(cl: ClassLoader) {
        val names = listOf(
            "com.android.server.fingerprint.FingerprintService",
            "com.android.server.biometrics.fingerprint.FingerprintService",
        )
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (!fpOk(param)) return
                    val host = policy ?: return
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFpAt < 240L) return
                    lastFpAt = now
                    val code = BuiltinKeys.fingerprintCode()
                    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)
                    val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0)
                    eat(host, down, "fingerprint")
                    eat(host, up, "fingerprint")
                } catch (_: Throwable) {
                }
            }
        }
        for (name in names) {
            val cls = findClass(cl, name) ?: continue
            hookFpClass(cls, hook)
            try {
                cls.declaredClasses.forEach { hookFpClass(it, hook) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookFpClass(cls: Class<*>, hook: XC_MethodHook) {
        val methods = try {
            cls.declaredMethods
        } catch (_: Throwable) {
            return
        }
        for (m in methods) {
            if (!m.name.contains("Authenticated") && m.name != "onAuthenticated") continue
            val key = cls.name + "#" + m.name + "#" + m.parameterTypes.joinToString { it.name }
            if (!hookedKeys.add(key)) continue
            try {
                XposedBridge.hookMethod(m, hook)
            } catch (_: Throwable) {
                hookedKeys.remove(key)
            }
        }
    }

    private fun fpOk(param: XC_MethodHook.MethodHookParam): Boolean {
        val args = param.args ?: return false
        var saw = false
        for (a in args) {
            when (a) {
                is Boolean -> {
                    saw = true
                    if (a) return true
                }
                is Int -> {
                    saw = true
                    if (a != 0) return true
                }
            }
        }
        return !saw
    }

    private fun eat(pwm: Any, event: KeyEvent, methodName: String): Boolean {
        if (KeyMapActions.injecting) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.flags and KeyEvent.FLAG_CANCELED != 0) return false
        if (pwm.javaClass.name.contains("PhoneWindowManager")) policy = pwm
        val host = policy ?: pwm
        val code = norm(event, methodName)
        if (isNavCode(code) && !isVirtualNav(event)) {
            XposedBridge.log("chuibing keymap skip nav code=" + code + " flags=" + event.flags + " scan=" + event.scanCode + " dev=" + event.deviceId + " src=" + event.source)
            return false
        }
        if (event.eventTime == lastStamp && code == lastCode && event.action == lastAct && event.repeatCount == lastRepeat) {
            return lastEat
        }
        reload()
        val result = if (capOn) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                writeCapture(code)
            }
            true
        } else if (!cfg.enabled) {
            false
        } else {
            ensureHandler(host)
            synchronized(lock) {
                if (event.action == KeyEvent.ACTION_DOWN) onDown(host, code, event.repeatCount) else onUp(host, code)
            }
        }
        lastStamp = event.eventTime
        lastCode = code
        lastAct = event.action
        lastRepeat = event.repeatCount
        lastEat = result
        if (isSmart(code) || isNavCode(code) || methodName.contains("Smart")) {
            XposedBridge.log("chuibing keymap " + methodName + " code=" + code + " act=" + event.action + " scan=" + event.scanCode + " eat=" + result)
        }
        return result
    }

    private fun norm(event: KeyEvent, methodName: String): Int {
        if (methodName == "handleSmartKeyBeforeDispatching" || event.scanCode == 766 || isSmart(event.keyCode)) {
            smartSeen = event.keyCode
            return BuiltinKeys.smartCode()
        }
        return event.keyCode
    }

    private fun isSmart(code: Int): Boolean {
        if (code == BuiltinKeys.smartCode()) return true
        if (smartSeen != 0 && code == smartSeen) return true
        if (code == cfg.key(BuiltinKeys.SMART)?.code) return true
        return false
    }

    private fun bindFor(code: Int): KeyBinding? {
        return cfg.byCode(code) ?: if (isSmart(code)) cfg.key(BuiltinKeys.SMART) else null
    }

    private fun onDown(pwm: Any, code: Int, repeatCount: Int): Boolean {
        if (repeatCount > 0) {
            val p = pending
            if (p == null || p.code != code) return false
            val longDefault = p.bind?.longPress?.isDefault() != false
            if (isVolume(code) && longDefault) {
                p.holdRepeat = true
                p.longTask?.let { handler?.removeCallbacks(it) }
                p.longTask = null
                KeyMapActions.replay(code)
                return true
            }
            return true
        }
        val cur = pending
        if (cur != null && cur.code == code && !cur.longFired && !cur.holdRepeat) {
            cur.clickTask?.let { handler?.removeCallbacks(it) }
            cur.clickTask = null
            cur.longTask?.let { handler?.removeCallbacks(it) }
            cur.longTask = null
            return true
        }
        if (combo == null && comboHold.contains(code)) return true
        val run = combo
        if (run != null) {
            if (run.got.lastOrNull() == code) return true
            if (acceptCombo(run, code)) {
                run.got += code
                run.lastAt = System.currentTimeMillis()
                val done = run.items.filter { matchCombo(it, run.got) }
                if (done.isNotEmpty()) {
                    fireCombo(pwm, done.first())
                    return true
                }
                run.items = run.items.filter { prefixCombo(it, run.got) }
                if (run.items.isEmpty()) {
                    failCombo(pwm)
                } else {
                    scheduleCombo(pwm, run)
                }
                return true
            } else {
                failCombo(pwm)
            }
        }
        val bind = bindFor(code)?.takeIf { it.enabled }
        val starters = cfg.combos.filter { it.enabled && startsCombo(it, code) && it.codes.size >= 2 && !it.action.isNone() && !it.action.isDefault() }
        val intercept = (bind != null && bind.intercept()) || starters.isNotEmpty()
        if (!intercept) return false
        cancelPending(false)
        pending = Pending(code, bind)
        if (starters.isNotEmpty()) {
            comboHold.clear()
            for (item in starters) comboHold.addAll(item.codes)
            combo = ComboRun(starters.toMutableList(), mutableListOf(code), System.currentTimeMillis(), starters.maxOf { it.timeoutMs })
            scheduleCombo(pwm, combo!!)
            return true
        }
        val h = handler
        val p = pending
        if (h != null && p != null && supportsLong(code)) {
            if (isPower(code)) {
                p.longTask = Runnable {
                    synchronized(lock) {
                        val now = pending
                        if (now == null || now.code != code || now.longFired) return@synchronized
                        now.longFired = true
                        combo = null
                        KeyMapActions.showPowerMenu(pwm)
                    }
                }
                h.postDelayed(p.longTask!!, cfg.longMs.toLong())
            } else {
                val longAct = bind?.longPress
                if (longAct != null && !longAct.isDefault()) {
                    p.longTask = Runnable {
                        synchronized(lock) {
                            val now = pending
                            if (now == null || now.code != code || now.longFired) return@synchronized
                            now.longFired = true
                            combo = null
                            fire(pwm, longAct, if (longAct.type == KeyAction.TYPE_VOICE) KeyMapActions.VOICE_HOLD else KeyMapActions.VOICE_ONCE)
                        }
                    }
                    h.postDelayed(p.longTask!!, cfg.longMs.toLong())
                }
            }
        }
        return true
    }

    private fun onUp(pwm: Any, code: Int): Boolean {
        val p = pending
        if (comboHold.contains(code)) {
            if (p != null && p.code == code) {
                if (combo != null) {
                    p.up = true
                    return true
                }
                pending = null
            }
            comboHold.remove(code)
            return true
        }
        if (p == null || p.code != code) {
            return bindFor(code)?.enabled == true && bindFor(code)?.intercept() == true
        }
        p.longTask?.let { handler?.removeCallbacks(it) }
        p.longTask = null
        if (p.longFired) {
            val longAct = p.bind?.longPress
            val clickAct = p.bind?.click
            pending = null
            combo = null
            if (longAct != null && longAct.type == KeyAction.TYPE_VOICE) {
                val clickVoice = clickAct != null && !clickAct.isDefault() && clickAct.type == KeyAction.TYPE_VOICE
                val held = SystemClock.uptimeMillis() - p.downAt
                if (!(clickVoice && held < cfg.longMs + 280)) {
                    val ctx = context(pwm)
                    if (ctx != null) KeyMapActions.stopVoice(ctx)
                }
            }
            return true
        }
        if (p.holdRepeat) {
            pending = null
            combo = null
            return true
        }
        if (combo != null) {
            p.up = true
            return true
        }
        scheduleClick(pwm, p)
        return true
    }

    private fun scheduleClick(pwm: Any, p: Pending) {
        val bind = p.bind
        val doubleAct = bind?.doubleClick
        val tripleAct = bind?.tripleClick
        val canDouble = doubleAct != null && !doubleAct.isDefault()
        val canTriple = tripleAct != null && !tripleAct.isDefault()
        p.clickTask?.let { handler?.removeCallbacks(it) }
        p.clicks += 1
        if (p.clicks >= 3 && canTriple) {
            pending = null
            fire(pwm, tripleAct!!)
            return
        }
        if (p.clicks >= 2 && canDouble && !canTriple) {
            pending = null
            fire(pwm, doubleAct!!)
            return
        }
        val wait = if ((canDouble || canTriple) && p.clicks < 3) cfg.doubleMs.toLong() else 0L
        val task = Runnable {
            synchronized(lock) {
                val cur = pending
                if (cur == null || cur.code != p.code || cur.longFired) return@synchronized
                val n = cur.clicks
                pending = null
                when {
                    n >= 2 && canDouble -> fire(pwm, doubleAct!!)
                    n >= 2 -> {}
                    else -> fireClick(pwm, bind, p.code)
                }
            }
        }
        p.clickTask = task
        if (wait <= 0L) task.run() else handler?.postDelayed(task, wait)
    }

    private fun fireClick(pwm: Any, bind: KeyBinding?, code: Int) {
        val act = bind?.click ?: KeyAction.default()
        when {
            act.isNone() -> {}
            act.isDefault() -> if (code != 0 && code != BuiltinKeys.fingerprintCode()) KeyMapActions.replay(code)
            else -> fire(pwm, act)
        }
    }

    private fun fireCombo(pwm: Any, item: KeyCombo) {
        combo?.timeoutTask?.let { handler?.removeCallbacks(it) }
        pending?.longTask?.let { handler?.removeCallbacks(it) }
        pending?.clickTask?.let { handler?.removeCallbacks(it) }
        pending = null
        combo = null
        comboHold.clear()
        comboHold.addAll(item.codes)
        fire(pwm, item.action)
    }

    private fun failCombo(pwm: Any) {
        combo?.timeoutTask?.let { handler?.removeCallbacks(it) }
        val run = combo
        combo = null
        comboHold.clear()
        val p = pending
        if (p != null && !p.longFired && !p.holdRepeat) {
            p.clickTask?.let { handler?.removeCallbacks(it) }
            p.longTask?.let { handler?.removeCallbacks(it) }
            pending = null
            scheduleClick(pwm, p)
        } else if (p == null && run != null) {
            val code = run.got.firstOrNull() ?: return
            fireClick(pwm, bindFor(code), code)
        }
    }

    private fun scheduleCombo(pwm: Any, run: ComboRun) {
        run.timeoutTask?.let { handler?.removeCallbacks(it) }
        val task = Runnable {
            synchronized(lock) {
                if (combo !== run) return@synchronized
                failCombo(pwm)
            }
        }
        run.timeoutTask = task
        handler?.postDelayed(task, run.timeoutMs.toLong())
    }

    private fun cancelPending(fireClick: Boolean) {
        val p = pending ?: return
        p.longTask?.let { handler?.removeCallbacks(it) }
        p.clickTask?.let { handler?.removeCallbacks(it) }
        pending = null
        combo?.timeoutTask?.let { handler?.removeCallbacks(it) }
        combo = null
        comboHold.clear()
    }

    private fun fire(pwm: Any, action: KeyAction, voiceMode: String = KeyMapActions.VOICE_ONCE) {
        if (action.isNone()) return
        if (action.isDefault()) return
        val ctx = context(pwm) ?: return
        if (cfg.vibrate) vibrate(ctx, pwm)
        KeyMapActions.run(pwm, ctx, action, voiceMode)
    }

    private fun vibrate(context: Context, pwm: Any) {
        Feel.vibrate(context, cfg.vibrateMs, cfg.vibrateAmp, null, pwm)
    }

    private fun isPower(code: Int) = code == KeyEvent.KEYCODE_POWER
    private fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
    private fun supportsLong(code: Int): Boolean {
        if (isPower(code)) return true
        if (isNavCode(code)) return false
        if (code == BuiltinKeys.fingerprintCode()) return false
        return true
    }
    private fun isNavCode(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH
    private fun isVirtualNav(event: KeyEvent): Boolean {
        if (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) return true
        val scan = event.scanCode
        if (scan == 158 || scan == 172 || scan == 580) return true
        val name = try {
            event.device?.name ?: ""
        } catch (_: Throwable) {
            ""
        }
        return name == "fts_ts"
    }

    private fun startsCombo(item: KeyCombo, code: Int): Boolean {
        if (item.codes.isEmpty()) return false
        return if (item.ordered) item.codes.first() == code else item.codes.contains(code)
    }

    private fun acceptCombo(run: ComboRun, code: Int): Boolean {
        return run.items.any { prefixCombo(it, run.got + code) }
    }

    private fun prefixCombo(item: KeyCombo, got: List<Int>): Boolean {
        if (got.size > item.codes.size) return false
        return if (item.ordered) item.codes.take(got.size) == got else item.codes.containsAll(got) && got.size == got.toSet().size
    }

    private fun matchCombo(item: KeyCombo, got: List<Int>): Boolean {
        if (got.size != item.codes.size) return false
        return if (item.ordered) item.codes == got else item.codes.toSet() == got.toSet()
    }

    private fun ensureHandler(pwm: Any) {
        if (handler != null) return
        handler = try {
            XposedHelpers.getObjectField(pwm, "mHandler") as Handler
        } catch (_: Throwable) {
            Handler(Looper.getMainLooper())
        }
    }

    private fun context(pwm: Any): Context? {
        return try {
            XposedHelpers.getObjectField(pwm, "mContext") as Context
        } catch (_: Throwable) {
            try {
                val at = XposedHelpers.findClass("android.app.ActivityThread", null)
                val inst = at.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
                XposedHelpers.callMethod(inst, "getSystemContext") as? Context
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun reload() {
        val files = listOf(File(Paths.KEYMAP), File("/data/local/tmp/tnt_keymap_config.json"))
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
        val newest = try {
            f?.lastModified() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        if (newest == 0L) {
            if (cfgFailAt != 0L) {
                cfgFailAt = 0L
                XposedBridge.log("chuibing keymap cfg missing")
            }
            reloadCap()
            return
        }
        if (newest != cfgAt) {
            try {
                val text = f!!.readText(Charsets.UTF_8)
                if (text.isNotBlank()) {
                    cfg = KeyMapConfig.fromJson(text)
                    cfgAt = newest
                    XposedBridge.log("chuibing keymap cfg enabled=" + cfg.enabled + " keys=" + cfg.keys.size)
                } else if (cfgFailAt != newest) {
                    cfgFailAt = newest
                    XposedBridge.log("chuibing keymap cfg empty")
                }
            } catch (t: Throwable) {
                if (cfgFailAt != newest) {
                    cfgFailAt = newest
                    XposedBridge.log("chuibing keymap cfg read fail " + t.javaClass.simpleName)
                }
            }
        }
        reloadCap()
    }

    private fun reloadCap() {
        val f = File(Paths.KEYMAP_CAPTURE)
        if (!f.exists()) {
            capOn = false
            capCode = 0
            return
        }
        try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            capOn = o.optBoolean("on", false)
            capCode = o.optInt("code", 0)
        } catch (_: Throwable) {
        }
    }

    private fun writeCapture(code: Int) {
        if (code == 0 || capCode != 0) return
        try {
            val o = JSONObject()
            o.put("on", true)
            o.put("code", code)
            o.put("name", KeyNames.of(code))
            o.put("at", System.currentTimeMillis())
            val text = o.toString()
            File("/data/local/tmp/tnt_keymap_capture.json").writeText(text, Charsets.UTF_8)
            File(Paths.KEYMAP_CAPTURE).writeText(text, Charsets.UTF_8)
            capCode = code
        } catch (_: Throwable) {
        }
    }

    private fun findClass(cl: ClassLoader, name: String): Class<*>? {
        return try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            null
        }
    }

    private class Pending(
        val code: Int,
        val bind: KeyBinding?,
        var clicks: Int = 0,
        var longFired: Boolean = false,
        var holdRepeat: Boolean = false,
        var up: Boolean = false,
        var longTask: Runnable? = null,
        var clickTask: Runnable? = null,
        val downAt: Long = SystemClock.uptimeMillis(),
    )

    private class ComboRun(
        var items: List<KeyCombo>,
        val got: MutableList<Int>,
        var lastAt: Long,
        val timeoutMs: Int,
        var timeoutTask: Runnable? = null,
    )
}
