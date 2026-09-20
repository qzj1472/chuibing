package id.tntwindow.editor.xposed

import android.app.Activity
import android.app.ActivityManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.Display
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.data.TntLaunch
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.VoiceCommand
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

class VoiceExpandModule : IXposedHookLoadPackage {
    private val launchLock = Any()
    private var lastPkg = ""
    private var lastAt = 0L
    private var lastCommit = ""
    private var lastCommitAt = 0L
    private var commitHandler: Handler? = null
    private var commitRun: Runnable? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "id.tntwindow.editor") {
            try {
                val clazz = XposedHelpers.findClass("id.tntwindow.editor.xposed.ModuleStatus", lpparam.classLoader)
                clazz.getField("ACTIVE").setBoolean(null, true)
            } catch (_: Throwable) {
            }
            return
        }
        val targets = setOf(
            "com.smartisanos.virtualremoter",
            "com.smartisanos.voice",
            "com.smartisanos.sara",
            "android",
            "com.smartisanos.desktop",
            "com.android.desktop.systemui",
        )
        if (pkg !in targets) return
        if (pkg == "android") {
            VoiceRuntime.setIngest { tryHandle(listOf(it)) }
        }
        log("load " + pkg)
        val names = setOf(
            "matchCommand",
            "parseVoiceCommands",
            "executeCommands",
            "onResultRecived",
            "onRecognizedText",
            "onResults",
            "onSearchResult",
            "onSearch",
            "getVoiceCommands",
        )
        hookNamed(lpparam.classLoader, "android.app.SmtVoiceCommandParser", names)
        hookNamed(lpparam.classLoader, "android.app.SmtVoiceCommandManager", names)
        hookNamed(lpparam.classLoader, "android.app.SmtVoiceInputCallback", names)
        hookNamed(lpparam.classLoader, "android.voice.ISmtVoiceCommandCallback", names)
        hookNamed(lpparam.classLoader, "android.view.SmtOnVoiceCommandListener", names)
        hookNamed(lpparam.classLoader, "com.smartisanos.virtualremoter.VoiceInputManager", names)
        hookAllMethods(lpparam.classLoader, "android.app.SmtVoiceInputCallback")
        hookIndex(lpparam.classLoader)
        hookPopText(lpparam.classLoader)
        hookRecognizer(lpparam.classLoader)
        hookIntentRewrite(lpparam.classLoader)
        hookVoiceClasses(lpparam.classLoader, listOf(
            "com.smartisanos.sara.voicecommand.VoiceCommandActivity",
            "com.smartisanos.sara.voicecommand.VoiceCommandActivity" + '$' + "IVoiceAssistantCallbackImpl",
            "smartisanos.app.voiceassistant.IVoiceAssistantCallback" + '$' + "Stub",
            "smartisanos.app.voiceassistant.VoiceAssistantCallbackV2Adapter",
            "com.iflytek.business.speech.RecognitionListener" + '$' + "Stub",
            "com.iflytek.business.speech.RecognizerResult",
            "com.smartisanos.sara.voicecommand.VoiceCommand",
            "com.smartisanos.sara.bubble.AbstractVoiceActivity",
            "com.smartisanos.sara.bullet.widget.VoiceRecognizeView",
        ))
        hookOfficialUi(lpparam.classLoader)
    }

    private fun hookVoiceClasses(cl: ClassLoader, names: List<String>) {
        for (name in names) {
            val clazz = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.parameterTypes.isEmpty()) continue
                if (!isVoiceName(m.name)) continue
                hookOne(m, voiceHook())
            }
            val inners = try {
                clazz.declaredClasses
            } catch (_: Throwable) {
                emptyArray()
            }
            for (inner in inners) {
                for (m in inner.declaredMethods) {
                    if (m.parameterTypes.isEmpty()) continue
                    if (!isVoiceName(m.name)) continue
                    hookOne(m, voiceHook())
                }
            }
        }
    }
    private fun hookRecognizer(cl: ClassLoader) {
        val clazz = try {
            XposedHelpers.findClass("android.speech.SpeechRecognizer", cl)
        } catch (_: Throwable) {
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name != "createSpeechRecognizer") continue
            hookOne(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val want = wantedRecognizer() ?: return
                    if (param.args.size >= 2 && param.args[1] is ComponentName) {
                        param.args[1] = want
                        return
                    }
                    if (param.args.size == 1 && param.args[0] is Context) {
                        try {
                            param.setResult(SpeechRecognizer.createSpeechRecognizer(param.args[0] as Context, want))
                        } catch (_: Throwable) {
                        }
                    }
                }
            })
        }
    }

    private fun hookIntentRewrite(cl: ClassLoader) {
        val names = listOf("android.app.ContextImpl", "android.app.Activity", "android.content.ContextWrapper")
        for (cn in names) {
            val clazz = try {
                XposedHelpers.findClass(cn, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name != "startActivity" && m.name != "startActivityForResult" && !m.name.startsWith("bindService")) continue
                hookOne(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val want = wantedRecognizer()
                        for (a in param.args) {
                            if (a !is Intent) continue
                            if (want != null) rewriteRecognizerIntent(a, want, m.name.startsWith("bindService"))
                            if (m.name.startsWith("startActivity")) blockNativeLaunch(param, a)
                        }
                    }
                })
            }
        }
    }

    private fun wantedRecognizer(): ComponentName? {
        val id = VoiceRuntime.recognizerId()
        if (id.isBlank()) return null
        return try {
            ComponentName.unflattenFromString(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun rewriteRecognizerIntent(intent: Intent, want: ComponentName, bind: Boolean) {
        val action = intent.action ?: ""
        val hit = action == "android.speech.action.RECOGNIZE_SPEECH" ||
            action == "android.speech.action.WEB_SEARCH" ||
            action == "android.speech.RecognitionService" ||
            (bind && intent.component != null && (intent.component?.className ?: "").contains("Recognition"))
        if (!hit) return
        try {
            intent.component = want
            intent.setPackage(want.packageName)
        } catch (_: Throwable) {
        }
    }

    private fun hookIndex(cl: ClassLoader) {
        val clazz = try {
            XposedHelpers.findClass("android.app.SmtVoiceCommandIndex", cl)
        } catch (_: Throwable) {
            return
        }
        hookMethods(clazz, setOf("matchCommand", "parseVoiceCommands"))
        val inners = try {
            clazz.declaredClasses
        } catch (_: Throwable) {
            emptyArray()
        }
        for (inner in inners) {
            hookMethods(inner, setOf("matchCommand", "parseVoiceCommands"))
        }
        for (name in listOf("Launcher", "Music", "Browser", "Gallery", "FileManager", "Office", "Notes", "Email", "Calendar", "SystemUI", "TouchPad", "CaptionBar")) {
            hookNamed(cl, "android.app.SmtVoiceCommandIndex$" + name, setOf("matchCommand", "parseVoiceCommands"))
        }
    }

    private fun hookPopText(cl: ClassLoader) {
        val classes = listOf(
            "android.app.SmtVoiceTextPopView",
            "android.app.SmtVoiceInputPopView",
            "android.app.SmtVoiceInputConfirmPopView",
        )
        for (cn in classes) {
            val clazz = try {
                XposedHelpers.findClass(cn, cl)
            } catch (_: Throwable) {
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name != "setText" && m.name != "setMessage") continue
                hookOne(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isEmpty()) return
                        val raw = param.args[0]?.toString() ?: return
                        if (!isNoMatchText(raw)) return
                        if (!recentlyHandled()) return
                        val speak = VoiceRuntime.lastSpeak()
                        if (speak.isNotBlank()) param.args[0] = speak
                    }
                })
            }
        }
    }

    private fun hookAllMethods(cl: ClassLoader, className: String) {
        val clazz = try {
            XposedHelpers.findClass(className, cl)
        } catch (_: Throwable) {
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.parameterTypes.isEmpty()) continue
            if (!isVoiceName(m.name)) continue
            hookOne(m, voiceHook())
        }
    }

    private fun hookNamed(cl: ClassLoader, className: String, names: Set<String>) {
        val clazz = try {
            XposedHelpers.findClass(className, cl)
        } catch (_: Throwable) {
            return
        }
        if (clazz.isInterface) {
            val stubName = className + '$' + "Stub"
            val stub = try {
                XposedHelpers.findClass(stubName, cl)
            } catch (_: Throwable) {
                null
            }
            if (stub != null) {
                hookMethods(stub, names)
                val inners = try {
                    stub.declaredClasses
                } catch (_: Throwable) {
                    emptyArray()
                }
                for (inner in inners) hookMethods(inner, names)
            }
            return
        }
        hookMethods(clazz, names)
    }

    private fun hookMethods(clazz: Class<*>, names: Set<String>) {
        for (m in clazz.declaredMethods) {
            if (m.name in names) hookOne(m, voiceHook())
        }
    }

    private fun isVoiceName(n: String): Boolean {
        val l = n.lowercase()
        if (l == "matchcommand" || l == "parsevoicecommands" || l == "executecommands" || l == "getvoicecommands") return true
        if (l == "onresultrecived" || l == "resultrecived" || l == "onrecognizedtext" || l == "onresults" || l == "onresult") return true
        if (l == "onpartialresult" || l == "parcailresult" || l == "onrecognizeover" || l == "onsearchresult" || l == "onsearch" || l == "onvoicebuttonresult") return true
        if (l.contains("parcail") || l.contains("partial")) return true
        return (l.contains("recogniz") || l.contains("utter")) && !l.startsWith("set")
    }

    private fun voiceHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isVoiceName(param.method.name)) return
                val spoken = spokenFrom(param)
                if (spoken.isNotEmpty() || isFinalName(param.method.name)) {
                    log(param.method.declaringClass.simpleName + "." + param.method.name + " " + spoken.joinToString("|"))
                }
                if (isNativeExec(param.method.name) && recentlyHandled()) {
                    skipWith(param, true)
                    return
                }
                if (spoken.isNotEmpty()) {
                    rememberSpoken(spoken.first())
                    if (isPartial(param)) {
                        val app = currentApp()
                        if (app != null) VoiceHud.partial(app, spoken.first())
                        return
                    }
                }
                if (spoken.isEmpty()) {
                    if (isFinalName(param.method.name)) {
                        val app = currentApp()
                        if (app != null) VoiceHud.error(app, "没听清")
                        skipWith(param, true)
                        return
                    }
                    if (recentlyHandled()) skipWith(param, true)
                    return
                }
                if (!VoiceRuntime.replaceEngine() && !isCallback(param)) return
                if (commitSpoken(spoken, "hook")) skipWith(param, true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                handleAfter(param)
            }
        }
    }

    private fun isCallback(param: XC_MethodHook.MethodHookParam): Boolean {
        val n = param.method.name
        return n == "onResultRecived" || n == "onRecognizedText" || n == "onResults" ||
            n == "onResult" || n == "onPartialResult" || n == "onRecognizeOver" ||
            n == "onSearchResult" || n == "onVoiceButtonResult"
    }

    private fun hookOne(method: Method, hook: XC_MethodHook) {
        try {
            XposedBridge.hookMethod(method, hook)
        } catch (_: Throwable) {
            try {
                XposedBridge.hookAllMethods(method.declaringClass, method.name, hook)
            } catch (t: Throwable) {
                log("hook fail " + method.declaringClass.name + "." + method.name + " " + t.javaClass.simpleName)
            }
        }
    }

    private fun handleAfter(param: XC_MethodHook.MethodHookParam) {
        if (isPartial(param)) return
        val spoken = spokenFrom(param)
        if (spoken.isNotEmpty()) {
            rememberSpoken(spoken.first())
            if (commitSpoken(spoken, "after") && isNoMatch(param.result)) markHandled(param)
        }
    }

    private fun tryHandle(spoken: List<String>): Boolean {
        return try {
            val app = currentApp()
            if (app == null) {
                log("handle skip no-app " + spoken.joinToString("|"))
                return false
            }
            VoiceSpeak.prepare(app)
            val destHint = spoken.map { VoiceRuntime.destHint(it) }.firstOrNull { it != VoiceRuntime.Dest.TNT }
                ?: VoiceRuntime.destHint(VoiceRuntime.lastSpoken())
            for (text in spoken) {
                if (!looksUtterance(text)) continue
                if (handleOne(app, text, destHint)) {
                    val msg = VoiceRuntime.lastSpeak().ifBlank { text }
                    VoiceHud.result(app, msg)
                    synchronized(launchLock) {
                        lastAt = SystemClock.uptimeMillis()
                    }
                    log("handle ok " + text + " dest=" + destHint + " -> " + msg)
                    return true
                }
            }
            log("handle miss " + spoken.joinToString("|"))
            false
        } catch (t: Throwable) {
            log("handle fail " + t.javaClass.simpleName + " " + (t.message ?: ""))
            false
        }
    }

    private fun handleOne(app: Context, text: String, destHint: VoiceRuntime.Dest = VoiceRuntime.Dest.TNT): Boolean {
        val parsed = VoiceRuntime.withDest(VoiceRuntime.parse(text), destHint)
        if (parsed.kind == VoiceRuntime.Kind.CANCEL) {
            VoiceRuntime.setOffer(null)
            VoiceHud.hide(app)
            VoiceSpeak.say(app, "好的")
            return true
        }
        val live = VoiceRuntime.offer()
        if (live != null) {
            when (parsed.kind) {
                VoiceRuntime.Kind.CANCEL -> {
                    VoiceRuntime.setOffer(null)
                    VoiceSpeak.say(app, "已取消")
                    return true
                }
                VoiceRuntime.Kind.CONFIRM_WEB -> return openWeb(app, live)
                VoiceRuntime.Kind.CONFIRM_INSTALL -> return openInstall(app, live.label, live.packages)
                VoiceRuntime.Kind.YES -> {
                    if (live.web.isNotBlank()) {
                        VoiceSpeak.say(app, "请说打开网页，或者安装")
                        return true
                    }
                    return openInstall(app, live.label, live.packages)
                }
                VoiceRuntime.Kind.INSTALL -> {
                    if (parsed.query.isEmpty()) return openInstall(app, live.label, live.packages)
                }
                VoiceRuntime.Kind.SEARCH, VoiceRuntime.Kind.CONFIRM_SEARCH -> {
                    val q = parsed.query.ifBlank { live.query.ifBlank { live.label } }
                    if (q.length >= 2) {
                        VoiceRuntime.setOffer(null)
                        val ok = openSearch(app, q)
                        if (ok) VoiceSpeak.say(app, "正在搜索" + q)
                        return ok
                    }
                }
                else -> {}
            }
        }
        if (parsed.kind == VoiceRuntime.Kind.ASK) {
            if (parsed.query == "weather") {
                VoiceHud.result(app, "正在查天气")
                Thread {
                    val msg = VoiceAssist.weatherText()
                    if (msg.isNotBlank()) {
                        VoiceSpeak.say(app, msg)
                        VoiceHud.result(app, msg)
                    } else {
                        val launched = VoiceAssist.weatherApps().any { launchPkg(app, it, "天气") }
                        if (launched) VoiceSpeak.say(app, "正在打开天气")
                        else {
                            VoiceSpeak.say(app, "暂时查不到天气")
                            VoiceHud.result(app, "暂时查不到天气")
                        }
                    }
                }.start()
                return true
            }
            VoiceSpeak.say(app, VoiceRuntime.askReply(text, app))
            return true
        }
        val cmd = VoiceRuntime.matchCustom(text)
        if (cmd != null) {
            val run = if (parsed.dest == VoiceRuntime.Dest.FREEFORM && cmd.action != "search" && cmd.action != "shell") {
                cmd.copy(action = "freeform")
            } else cmd
            if (runCommand(app, run)) {
                if (run.action != "search" && run.action != "shell") {
                    val prefix = if (run.action == "freeform") "正在小窗打开" else "正在打开"
                    VoiceSpeak.say(app, prefix + cmd.phrase)
                }
                return true
            }
        }
        when (parsed.kind) {
            VoiceRuntime.Kind.SEARCH -> {
                val q = parsed.query
                if (q.length < 2) return false
                val ok = openSearch(app, q)
                if (ok) VoiceSpeak.say(app, "正在搜索" + q)
                return ok
            }
            VoiceRuntime.Kind.INSTALL -> {
                val q = parsed.query
                if (q.length < 2) return false
                val cat = VoiceRuntime.catalogOf(q)
                val label = cat?.label ?: q
                val pkgs = cat?.packages ?: emptyList()
                val ok = openInstall(app, label, pkgs)
                if (ok) VoiceSpeak.say(app, "正在去应用市场安装" + label)
                return ok
            }
            VoiceRuntime.Kind.OPEN_WEB -> {
                val q = parsed.query
                val cat = VoiceRuntime.catalogOf(q)
                val offer = VoiceRuntime.makeOffer(cat?.label ?: q, q, cat?.packages ?: emptyList(), cat?.web ?: "")
                return openWeb(app, offer)
            }
            VoiceRuntime.Kind.CONFIRM_WEB -> return openSearch(app, queryOrLast(parsed.query))
            else -> {}
        }
        if (parsed.kind == VoiceRuntime.Kind.CONTROL) {
            val p = parsed.query.split("|")
            val type = p.getOrNull(0).orEmpty()
            val rest = parsed.query.substringAfter("|", "")
            if (type.isBlank()) return false
            if (type == KeyAction.TYPE_INPUT) {
                KeyMapActions.perform(app, KeyAction(type = KeyAction.TYPE_INPUT, text = rest))
                VoiceSpeak.say(app, "已输入")
                return true
            }
            if (type == "kill_app") {
                val cat = VoiceRuntime.catalogOf(rest)
                val label = cat?.label ?: rest
                val pkgs = cat?.packages.orEmpty()
                if (pkgs.isEmpty()) {
                    VoiceSpeak.say(app, "没找到" + rest)
                    return true
                }
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                var ok = false
                for (pkg in pkgs) {
                    try {
                        XposedHelpers.callMethod(am, "forceStopPackage", pkg)
                        ok = true
                    } catch (_: Throwable) {
                    }
                }
                VoiceSpeak.say(app, if (ok) "已关闭" + label else "没能关闭" + label)
                return true
            }
            val mode = rest
            KeyMapActions.perform(app, VoiceAssist.controlAction(type, mode))
            VoiceSpeak.say(app, VoiceAssist.controlSay(type, mode))
            return true
        }
        if (parsed.kind == VoiceRuntime.Kind.NOTE) {
            val body = parsed.query.trim()
            if (body.isEmpty()) return false
            VoiceAssist.addNote(body)
            VoiceSpeak.say(app, "已记下" + body)
            return true
        }
        if (parsed.kind != VoiceRuntime.Kind.LAUNCH) return false
        val q = parsed.query
        if (q.isEmpty()) {
            if (parsed.dest == VoiceRuntime.Dest.FREEFORM) {
                KeyMapActions.perform(app, KeyAction(type = KeyAction.TYPE_FREEFORM_CURRENT))
                VoiceSpeak.say(app, "已小窗当前应用")
                return true
            }
            return false
        }
        val cat = VoiceRuntime.catalogOf(q)
        if (cat != null) {
            val ordered = cat.packages.sortedByDescending { pkg ->
                var score = 0
                for (k in VoiceRuntime.extraKeys(pkg) + listOf(pkg, cat.label)) {
                    score = maxOf(score, VoiceRuntime.scoreName(k, q))
                }
                score
            }
            for (pkg in ordered) {
                if (launchPkg(app, pkg, cat.label, parsed.dest)) return true
            }
            return offerMissing(app, cat.label, q, cat.packages, cat.web)
        }
        val hit = bestApp(app, text)
        if (hit != null && hit.strong && launchPkg(app, hit.pkg, hit.label, parsed.dest)) return true
        val rawN = VoiceRuntime.normalize(VoiceRuntime.correct(text))
        if (rawN.startsWith("打开") || rawN.startsWith("启动") || rawN.startsWith("运行")) {
            VoiceSpeak.say(app, "没找到" + q)
            return true
        }
        VoiceSpeak.say(app, "没听懂")
        return true
    }

    private fun queryOrLast(q: String): String {
        if (q.length >= 2) return q
        return VoiceRuntime.offer()?.query ?: q
    }

    private fun offerMissing(app: Context, label: String, query: String, packages: List<String>, web: String): Boolean {
        val offer = VoiceRuntime.makeOffer(label, query, packages, web)
        VoiceRuntime.setOffer(offer)
        val msg = if (web.isNotBlank()) {
            "没有找到" + label + "。可以说打开网页端，或者安装。"
        } else {
            "没有找到" + label + "。可以说搜索，或者安装。"
        }
        VoiceSpeak.say(app, msg)
        return true
    }

    private fun openWeb(app: Context, offer: VoiceRuntime.Offer): Boolean {
        VoiceRuntime.setOffer(null)
        val url = if (offer.web.isNotBlank()) offer.web else ("https://www.baidu.com/s?wd=" + Uri.encode(offer.label))
        val ok = startView(app, Uri.parse(url), null)
        if (ok) VoiceSpeak.say(app, "正在打开" + offer.label + "网页")
        return ok
    }

    private fun openSearch(app: Context, query: String): Boolean {
        val search = Intent(Intent.ACTION_WEB_SEARCH)
        search.putExtra(SearchManager.QUERY, query)
        search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (TntLaunch.start(app, search)) return true
        } catch (_: Throwable) {
        }
        return startView(app, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(query)), null)
    }

    private fun openInstall(app: Context, label: String, packages: List<String>): Boolean {
        VoiceRuntime.setOffer(null)
        for (pkg in packages) {
            val uri = Uri.parse("market://details?id=" + pkg)
            if (startView(app, uri, "com.smartisanos.appstore")) return true
            if (startView(app, uri, "com.android.vending")) return true
            if (startView(app, uri, null)) return true
        }
        val q = Uri.encode(label)
        if (startView(app, Uri.parse("market://search?q=" + q), "com.smartisanos.appstore")) return true
        val search = Intent(Intent.ACTION_SEARCH)
        search.setPackage("com.smartisanos.appstore")
        search.putExtra(SearchManager.QUERY, label)
        search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            TntLaunch.start(app, search)
            return true
        } catch (_: Throwable) {
        }
        val extra = Intent("com.smartisanos.appstore.search_packages")
        extra.setPackage("com.smartisanos.appstore")
        extra.putExtra("query", label)
        extra.putExtra("keyword", label)
        extra.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            TntLaunch.start(app, extra)
            return true
        } catch (_: Throwable) {
        }
        if (startView(app, Uri.parse("market://search?q=" + q), "com.android.vending")) return true
        return startView(app, Uri.parse("https://www.baidu.com/s?wd=" + q + Uri.encode(" 安卓应用")), null)
    }

    private fun startView(app: Context, uri: Uri, pkg: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!pkg.isNullOrBlank()) intent.setPackage(pkg)
            TntLaunch.start(app, intent)
        } catch (_: Throwable) {
            false
        }
    }

    private fun runCommand(app: Context, cmd: VoiceCommand): Boolean {
        return when (cmd.action) {
            "shell" -> runShell(cmd.target)
            "search" -> {
                val ok = openSearch(app, cmd.target)
                if (ok) VoiceSpeak.say(app, "正在搜索" + cmd.target)
                ok
            }
            "freeform" -> launchPkg(app, cmd.target, cmd.phrase, VoiceRuntime.Dest.FREEFORM)
            else -> launchPkg(app, cmd.target, cmd.phrase)
        }
    }

    private fun runShell(command: String): Boolean {
        if (command.isBlank()) return false
        synchronized(launchLock) {
            val now = SystemClock.uptimeMillis()
            if (command == lastPkg && now - lastAt < 1500L) return true
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                p.waitFor(6, TimeUnit.SECONDS)
                lastPkg = command
                lastAt = now
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun spokenFrom(param: XC_MethodHook.MethodHookParam): List<String> {
        val out = ArrayList<String>()
        val args = param.args ?: return out
        for (a in args) collectSpoken(a, out, 0, IdentityHashMap())
        return cleanSpoken(out)
    }

    private fun collectSpoken(a: Any?, out: ArrayList<String>, depth: Int, seen: IdentityHashMap<Any, Boolean>) {
        if (a == null || depth > 4) return
        if (seen.put(a, true) != null) return
        when (a) {
            is String -> pushSpoken(a, out)
            is CharSequence -> pushSpoken(a.toString(), out)
            is Array<*> -> a.forEach { collectSpoken(it, out, depth + 1, seen) }
            is Collection<*> -> a.forEach { collectSpoken(it, out, depth + 1, seen) }
            is Bundle -> {
                for (k in a.keySet()) collectSpoken(a.get(k), out, depth + 1, seen)
            }
            is Intent -> {
                collectSpoken(a.extras, out, depth + 1, seen)
                val data = a.dataString
                if (!data.isNullOrBlank() && looksUtterance(data)) out.add(data)
            }
            else -> {
                if (skipReflect(a)) return
                val printed = try { a.toString() } catch (_: Throwable) { "" }
                val extracted = extractRecognizeItems(printed)
                if (extracted.isNotEmpty()) out.addAll(extracted)
                else if (printed.any { ch -> ch.code in 0x4E00..0x9FFF } && looksUtterance(printed) && printed != a.javaClass.name) out.add(printed.trim())
                try {
                    for (f in a.javaClass.declaredFields) {
                        if (f.name == "shadow\$_klass_" || f.name == "shadow\$_monitor_") continue
                        f.isAccessible = true
                        collectSpoken(f.get(a), out, depth + 1, seen)
                    }
                } catch (_: Throwable) {
                }
                for (name in listOf("getText", "getResult", "getResults", "getCommand", "getQuery", "getBestResult", "getContent", "getResultStr", "getResultString", "getPartialResult", "getUtterance", "getAsrResult", "getVoiceResult", "getNluText")) {
                    try {
                        val m = a.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: continue
                        collectSpoken(m.invoke(a), out, depth + 1, seen)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun pushSpoken(raw: String?, out: ArrayList<String>) {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return
        val json = fromRecognizerJson(t)
        if (json.isNotEmpty()) {
            out.addAll(json)
            return
        }
        if (looksUtterance(t)) out.add(t)
    }

    private fun fromRecognizerJson(s: String): List<String> {
        if (!(s.contains("{") && (s.contains("\"w\"") || s.contains("\"text\"") || s.contains("\"orig_text\"")))) return emptyList()
        val words = ArrayList<String>()
        val w = Regex("\"w\"\\s*:\\s*\"([^\"]*)\"")
        for (m in w.findAll(s)) {
            val v = m.groupValues[1]
            if (v.isNotBlank() && v != "。" && v != "." && v != "，" && v != ",") words.add(v)
        }
        if (words.isNotEmpty()) {
            val joined = words.joinToString("")
            return if (looksUtterance(joined)) listOf(joined) else emptyList()
        }
        val textRe = Regex("\"(?:text|orig_text|result)\"\\s*:\\s*\"([^\"]+)\"")
        val t = textRe.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        return if (t.isNotEmpty() && looksUtterance(t)) listOf(t) else emptyList()
    }

    private fun skipReflect(a: Any): Boolean {
        val n = a.javaClass.name
        return n.startsWith("android.view.") ||
            n.startsWith("android.widget.") ||
            n.startsWith("android.app.Activity") ||
            n.startsWith("android.app.Application") ||
            n.startsWith("android.content.Context") ||
            n.startsWith("android.content.pm") ||
            n.startsWith("android.os.") ||
            n.startsWith("android.media.") ||
            n.startsWith("android.util.") ||
            n.startsWith("dalvik.") ||
            n.startsWith("java.lang.") ||
            n.startsWith("sun.misc.") ||
            n.startsWith("libcore.") ||
            n.startsWith("android.graphics.")
    }

    private fun looksUtterance(s: String): Boolean {
        val t = s.trim()
        if (t.length < 1 || t.length > 200) return false
        if (t.startsWith("/") || t.startsWith("http") || t.contains("\n")) return false
        if (t.equals("true", true) || t.equals("false", true)) return false
        if (t.startsWith("FLAG_") || t.contains("@") || t.startsWith("[") || t.startsWith("{")) return false
        if (t.contains("RecognizeResult") || t.contains("mReplaceStart") || t.contains("mItems") || t.contains("Item{")) return false
        if (t.contains("android.") || t.contains("java.") || t.contains("Landroid") || t.contains("com.smartisanos.")) return false
        val hasCjk = t.any { ch -> ch.code in 0x4E00..0x9FFF }
        val hasLetter = t.any { it.isLetter() }
        return hasCjk || (hasLetter && t.length in 2..40)
    }

    private fun isNoMatch(result: Any?): Boolean {
        if (result == null) return true
        if (result is Boolean) return !result
        if (result is Number) return result.toInt() <= 0
        if (result is Collection<*>) return result.isEmpty()
        if (result is Array<*>) return result.isEmpty()
        return isNoMatchText(result.toString())
    }

    private fun isNoMatchText(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT)
        return n.contains("no_match") ||
            n.contains("nomatch") ||
            n.contains("revone_no_match") ||
            n.contains("unrecognized") ||
            text.contains("未识别") ||
            text.contains("未找到对应项目") ||
            text.contains("未找到结果") ||
            text.contains("未找到搜索结果") ||
            text.contains("未安装该应用")
    }

    private fun markHandled(param: XC_MethodHook.MethodHookParam) {
        skipWith(param, true)
    }

    private fun neutralize(param: XC_MethodHook.MethodHookParam) {
        when (val result = param.result) {
            is Boolean -> param.setResult(false)
            is Int -> param.setResult(0)
            is Number -> param.setResult(0)
            is Collection<*> -> param.setResult(emptyList<Any>())
            is Array<*> -> param.setResult(emptyArray<Any>())
            else -> {
                if (result != null) {
                    try {
                        param.setResult(null)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun skipWith(param: XC_MethodHook.MethodHookParam, handled: Boolean) {
        val rt = (param.method as? Method)?.returnType
        when {
            rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java -> param.setResult(handled)
            rt == Int::class.javaPrimitiveType || rt == Integer::class.java -> param.setResult(if (handled) 1 else 0)
            else -> {
                try {
                    param.setResult(null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun recentlyHandled(): Boolean {
        synchronized(launchLock) {
            if (lastAt != 0L && SystemClock.uptimeMillis() - lastAt < 4000L) return true
        }
        return VoiceRuntime.recentlyHandled()
    }

    private fun isNativeExec(name: String): Boolean {
        val l = name.lowercase()
        return l == "executecommands" || l == "matchcommand" || l == "parsevoicecommands" || l == "getvoicecommands"
    }

    private fun blockNativeLaunch(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        if (intent.getBooleanExtra(TntLaunch.EXTRA_VOICE, false)) return
        if (!recentlyHandled()) return
        val pkg = intent.component?.packageName ?: intent.`package` ?: return
        if (pkg == "com.smartisanos.virtualremoter" || pkg == "com.smartisanos.sara" || pkg == "com.smartisanos.voice") return
        val last = synchronized(launchLock) { lastPkg }.ifBlank { VoiceRuntime.lastHandlePkg() }
        val dest = VoiceRuntime.lastHandleDest()
        val launch = intent.action == Intent.ACTION_MAIN || intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.component != null
        if (!launch) return
        if (dest == VoiceRuntime.Dest.PHONE || (last.isNotBlank() && pkg != last)) {
            param.result = null
        }
    }

    private fun launchPkg(app: Context, pkg: String, label: String = "", dest: VoiceRuntime.Dest = VoiceRuntime.Dest.TNT): Boolean {
        synchronized(launchLock) {
            val now = SystemClock.uptimeMillis()
            if (pkg == lastPkg && now - lastAt < 1500L) return true
            return try {
                val ok = when (dest) {
                    VoiceRuntime.Dest.FREEFORM -> KeyMapActions.launchFreeform(app, pkg)
                    VoiceRuntime.Dest.PHONE -> {
                        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        intent.putExtra(TntLaunch.EXTRA_VOICE, true)
                        val started = TntLaunch.startPhone(app, intent)
                        if (started) movePkgToDisplay(app, pkg, Display.DEFAULT_DISPLAY)
                        started
                    }
                    VoiceRuntime.Dest.TNT -> {
                        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        intent.putExtra(TntLaunch.EXTRA_VOICE, true)
                        TntLaunch.start(app, intent)
                    }
                }
                if (!ok) return false
                lastPkg = pkg
                lastAt = now
                VoiceRuntime.markHandled(pkg, dest)
                log("launch " + pkg + " dest=" + dest)
                val name = label.ifBlank {
                    try {
                        val ai = app.packageManager.getApplicationInfo(pkg, 0)
                        app.packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Throwable) {
                        pkg
                    }
                }
                val say = when (dest) {
                    VoiceRuntime.Dest.FREEFORM -> "正在小窗打开"
                    VoiceRuntime.Dest.PHONE -> "正在手机端打开"
                    VoiceRuntime.Dest.TNT -> "正在打开"
                }
                VoiceSpeak.say(app, say + name)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun movePkgToDisplay(app: Context, pkg: String, displayId: Int) {
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = try {
                am.getRunningTasks(40)
            } catch (_: Throwable) {
                null
            } ?: return
            for (t in tasks) {
                val p = t.topActivity?.packageName ?: t.baseActivity?.packageName ?: continue
                if (p != pkg) continue
                try {
                    XposedHelpers.callMethod(am, "moveTaskToDisplay", t.id, displayId)
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.callMethod(am, "moveTaskToDisplay", t.id, displayId, true)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun currentApp(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private data class Hit(val pkg: String, val label: String, val score: Int, val strong: Boolean)

    private fun bestApp(context: Context, spoken: String): Hit? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val apps = ArrayList<Pair<String, String>>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            apps += label to pkg
        }
        val hit = VoiceRuntime.bestHit(apps, spoken) ?: return null
        return Hit(hit.pkg, hit.label, hit.score, hit.strong)
    }

    private fun aliases(label: String, pkg: String): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(VoiceRuntime.aliasesOf(label))
        out.addAll(VoiceRuntime.extraKeys(pkg))
        out.add(label.replace(" ", ""))
        val n = VoiceRuntime.normalize(label)
        if (pkg.startsWith("com.mxtech.videoplayer") || (n.contains("mx") && (n.contains("player") || label.contains("播放")))) {
            out.add("mx播放器")
            out.add("mxplayer")
            out.add("mx player")
            out.add("mx播放器pro")
            out.add("mxplayerpro")
        }
        return out.toList()
    }



    private fun hookOfficialUi(cl: ClassLoader) {
        val clazz = try {
            XposedHelpers.findClass("com.smartisanos.sara.voicecommand.VoiceCommandActivity", cl)
        } catch (_: Throwable) {
            return
        }
        val names = setOf(
            "onCreate",
            "onResume",
            "onStart",
            "onWindowFocusChanged",
            "startUI",
            "initView",
            "gaussianBlur",
            "startRecognize",
            "parcailResult",
            "resultRecived",
            "onRecognizeOver",
            "showNoResultView",
            "error",
            "finish",
        )
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                if (n == "gaussianBlur") {
                    param.result = null
                    return
                }
                if (n == "showNoResultView" || n == "error") {
                    if (recentlyHandled()) {
                        param.result = null
                        hideOfficial(param.thisObject)
                    }
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val n = param.method.name
                if (n == "onCreate" || n == "onResume" || n == "onStart" || n == "onWindowFocusChanged" || n == "startUI" || n == "initView" || n == "showNoResultView") {
                    hideOfficial(param.thisObject)
                }
                val app = currentApp()
                if (n == "onCreate" || n == "startRecognize") {
                    cancelCommit()
                    if (app != null) VoiceHud.listen(app)
                    hideOfficial(param.thisObject)
                }
                if (n == "parcailResult") {
                    val spoken = spokenFrom(param)
                    val t = spoken.firstOrNull().orEmpty()
                    if (t.isNotBlank()) {
                        rememberSpoken(t)
                        if (app != null) VoiceHud.partial(app, t)
                    }
                }
                if (n == "resultRecived" || n == "onRecognizeOver") {
                    val spoken = spokenFrom(param)
                    if (spoken.isNotEmpty()) {
                        rememberSpoken(spoken.first())
                        commitSpoken(spoken, n)
                    } else if (app != null) {
                        VoiceHud.error(app, "没听清")
                    }
                }
                if (n == "error") {
                    if (recentlyHandled()) return
                    if (app != null) VoiceHud.error(app, "没听清")
                }
                if (n == "finish") {
                    if (recentlyHandled()) return
                    if (app != null) VoiceHud.hide(app)
                }
            }
        }
        for (m in clazz.declaredMethods) {
            if (m.name in names) hookOne(m, hook)
        }
    }

    private fun hideOfficial(obj: Any?) {
        val run = Runnable {
            val act = obj as? Activity ?: return@Runnable
            try {
                act.overridePendingTransition(0, 0)
            } catch (_: Throwable) {
            }
            val w = try {
                act.window
            } catch (_: Throwable) {
                null
            } ?: return@Runnable
            try {
                w.setBackgroundDrawable(ColorDrawable(0))
            } catch (_: Throwable) {
            }
            try {
                w.setDimAmount(0f)
            } catch (_: Throwable) {
            }
            try {
                val lp = w.attributes
                lp.alpha = 0f
                lp.dimAmount = 0f
                lp.width = 1
                lp.height = 1
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = 0
                lp.y = 0
                lp.flags = lp.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                w.attributes = lp
            } catch (_: Throwable) {
            }
            try {
                val f = act.javaClass.getDeclaredField("isActivityVisble")
                f.isAccessible = true
                f.setBoolean(act, true)
            } catch (_: Throwable) {
            }
        }
        val looper = Looper.getMainLooper()
        if (looper == null || Looper.myLooper() == looper) run.run() else Handler(looper).post(run)
    }

    private fun isFinalName(n: String): Boolean {
        val l = n.lowercase()
        return l == "resultrecived" || l == "onresultrecived" || l == "onresults" || l == "onresult" ||
            l == "onrecognizedtext" || l == "onrecognizeover" || l == "onvoicebuttonresult" ||
            l == "shownoresultview" || l == "error" || l == "finish"
    }

    private fun rememberSpoken(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !looksUtterance(t)) return
        VoiceRuntime.setLastSpoken(t)
    }

    private fun commitSpoken(spoken: List<String>, reason: String): Boolean {
        cancelCommit()
        val texts = cleanSpoken(spoken)
        if (texts.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        val first = texts.first()
        synchronized(launchLock) {
            if (first == lastCommit && now - lastCommitAt < 2500L) return true
        }
        if (!VoiceRuntime.takeCommit(first)) return true
        val ok = tryHandle(texts)
        log("commit " + reason + " ok=" + ok + " " + texts.joinToString("|"))
        if (ok) {
            synchronized(launchLock) {
                lastCommit = first
                lastCommitAt = SystemClock.uptimeMillis()
            }
        } else {
            VoiceRuntime.clearCommit(first)
        }
        return ok
    }

    private fun scheduleSpoken(spoken: List<String>, delayMs: Long, reason: String) {
        val texts = ArrayList(cleanSpoken(spoken))
        if (texts.isEmpty()) return
        rememberSpoken(texts.first())
        val looper = Looper.getMainLooper()
        if (looper == null || delayMs <= 0L) {
            commitSpoken(texts, reason)
            return
        }
        val h = commitHandler ?: Handler(looper).also { commitHandler = it }
        commitRun?.let { h.removeCallbacks(it) }
        val run = Runnable { commitSpoken(texts, reason) }
        commitRun = run
        h.postDelayed(run, delayMs)
    }

    private fun cancelCommit() {
        val h = commitHandler ?: return
        commitRun?.let { h.removeCallbacks(it) }
        commitRun = null
    }

    private fun cleanSpoken(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (item in raw) {
            val items = extractRecognizeItems(item)
            if (items.isNotEmpty()) {
                out.addAll(items)
                continue
            }
            val t = item.trim()
            if (looksUtterance(t)) out.add(t)
        }
        return out.toList()
    }

    private fun extractRecognizeItems(s: String): List<String> {
        if (!s.contains("Item{")) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val start = s.indexOf("Item{", i)
            if (start < 0) break
            val comma = s.indexOf(',', start)
            val end = s.indexOf('}', start)
            if (comma > start && end > comma) {
                val v = s.substring(comma + 1, end).trim()
                if (v.isNotEmpty() && looksUtterance(v)) out.add(v)
            }
            i = if (end > start) end + 1 else start + 5
        }
        return out.distinct()
    }

    private fun isPartial(param: XC_MethodHook.MethodHookParam): Boolean {
        val n = param.method.name
        return n == "onPartialResult" || n == "parcailResult" || n.contains("Partial")
    }

    private fun log(msg: String) {
        try {
            XposedBridge.log("tnt-voice " + msg)
        } catch (_: Throwable) {
        }
    }
}
