package id.tntwindow.editor.xposed

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import id.tntwindow.editor.data.TntLaunch
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
                        val want = wantedRecognizer() ?: return
                        for (a in param.args) {
                            if (a is Intent) rewriteRecognizerIntent(a, want, m.name.startsWith("bindService"))
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
            hookOne(m, voiceHook())
        }
    }

    private fun hookNamed(cl: ClassLoader, className: String, names: Set<String>) {
        val clazz = try {
            XposedHelpers.findClass(className, cl)
        } catch (_: Throwable) {
            return
        }
        hookMethods(clazz, names)
    }

    private fun hookMethods(clazz: Class<*>, names: Set<String>) {
        for (m in clazz.declaredMethods) {
            if (m.name in names) hookOne(m, voiceHook())
        }
    }

    private fun voiceHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val spoken = spokenFrom(param)
                if (spoken.isNotEmpty()) {
                    log(param.method.declaringClass.simpleName + "." + param.method.name + " " + spoken.joinToString("|"))
                    VoiceRuntime.setLastSpoken(spoken.first())
                }
                if (spoken.isEmpty()) {
                    if (recentlyHandled()) skipWith(param, true)
                    return
                }
                if (!VoiceRuntime.replaceEngine() && !isCallback(param)) return
                if (tryHandle(spoken)) skipWith(param, true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                handleAfter(param)
            }
        }
    }

    private fun isCallback(param: XC_MethodHook.MethodHookParam): Boolean {
        val n = param.method.name
        return n == "onResultRecived" || n == "onRecognizedText" || n == "onResults"
    }

    private fun hookOne(method: Method, hook: XC_MethodHook) {
        try {
            val types = method.parameterTypes
            val args = arrayOfNulls<Any>(types.size + 1)
            for (i in types.indices) args[i] = types[i]
            args[types.size] = hook
            XposedHelpers.findAndHookMethod(method.declaringClass.name, method.declaringClass.classLoader, method.name, *args)
        } catch (t: Throwable) {
            log("hook fail " + method.declaringClass.name + "." + method.name + " " + t.javaClass.simpleName)
        }
    }

    private fun handleAfter(param: XC_MethodHook.MethodHookParam) {
        val spoken = spokenFrom(param)
        if (spoken.isEmpty()) return
        val originalMiss = isNoMatch(param.result)
        if (tryHandle(spoken)) {
            if (originalMiss) markHandled(param) else neutralize(param)
        }
    }

    private fun tryHandle(spoken: List<String>): Boolean {
        val app = currentApp() ?: return false
        VoiceSpeak.prepare(app)
        for (text in spoken) {
            if (handleOne(app, text)) return true
        }
        return false
    }

    private fun handleOne(app: Context, text: String): Boolean {
        val parsed = VoiceRuntime.parse(text)
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
        val cmd = VoiceRuntime.matchCustom(text)
        if (cmd != null && runCommand(app, cmd)) {
            if (cmd.action != "search" && cmd.action != "shell") {
                VoiceSpeak.say(app, "正在打开" + cmd.phrase)
            }
            return true
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
        if (parsed.kind != VoiceRuntime.Kind.LAUNCH) return false
        val q = parsed.query
        if (q.length < 2) return false
        val cat = VoiceRuntime.catalogOf(q)
        if (cat != null) {
            for (pkg in cat.packages) {
                if (launchPkg(app, pkg, cat.label)) return true
            }
            return offerMissing(app, cat.label, q, cat.packages, cat.web)
        }
        val hit = bestApp(app, text)
        if (hit != null && hit.strong && launchPkg(app, hit.pkg, hit.label)) return true
        return offerMissing(app, q, q, emptyList(), "")
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
        return out.distinct()
    }

    private fun collectSpoken(a: Any?, out: ArrayList<String>, depth: Int, seen: IdentityHashMap<Any, Boolean>) {
        if (a == null || depth > 4) return
        if (seen.put(a, true) != null) return
        when (a) {
            is String -> if (looksUtterance(a)) out.add(a.trim())
            is CharSequence -> {
                val t = a.toString()
                if (looksUtterance(t)) out.add(t.trim())
            }
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
                if (looksUtterance(printed) && printed != a.javaClass.name) out.add(printed.trim())
                try {
                    for (f in a.javaClass.declaredFields) {
                        if (f.name == "shadow\$_klass_" || f.name == "shadow\$_monitor_") continue
                        f.isAccessible = true
                        collectSpoken(f.get(a), out, depth + 1, seen)
                    }
                } catch (_: Throwable) {
                }
                for (name in listOf("getText", "getResult", "getResults", "getCommand", "getQuery", "getBestResult", "getContent")) {
                    try {
                        val m = a.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: continue
                        collectSpoken(m.invoke(a), out, depth + 1, seen)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun skipReflect(a: Any): Boolean {
        val n = a.javaClass.name
        return n.startsWith("android.view.") ||
            n.startsWith("android.widget.") ||
            n.startsWith("android.app.Activity") ||
            n.startsWith("android.app.Application") ||
            n.startsWith("android.content.Context") ||
            n.startsWith("android.content.pm") ||
            n.startsWith("android.os.Looper") ||
            n.startsWith("android.os.Handler") ||
            n.startsWith("dalvik.") ||
            n.startsWith("java.lang.Class") ||
            n.startsWith("android.graphics.")
    }

    private fun looksUtterance(s: String): Boolean {
        val t = s.trim()
        if (t.length < 1 || t.length > 80) return false
        if (t.startsWith("/") || t.startsWith("http") || t.contains("\n")) return false
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
            text.contains("未识别")
    }

    private fun markHandled(param: XC_MethodHook.MethodHookParam) {
        when (val result = param.result) {
            is Boolean -> param.setResult(true)
            is Int -> if (result <= 0) param.setResult(1)
            is Number -> if (result.toInt() <= 0) param.setResult(1)
        }
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
            return lastAt != 0L && SystemClock.uptimeMillis() - lastAt < 1500L
        }
    }

    private fun launchPkg(app: Context, pkg: String, label: String = ""): Boolean {
        synchronized(launchLock) {
            val now = SystemClock.uptimeMillis()
            if (pkg == lastPkg && now - lastAt < 1500L) return true
            return try {
                val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (!TntLaunch.start(app, intent)) return false
                lastPkg = pkg
                lastAt = now
                val name = label.ifBlank {
                    try {
                        val ai = app.packageManager.getApplicationInfo(pkg, 0)
                        app.packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Throwable) {
                        pkg
                    }
                }
                VoiceSpeak.say(app, "正在打开" + name)
                true
            } catch (_: Throwable) {
                false
            }
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

    private fun log(msg: String) {
        try {
            XposedBridge.log("tnt-voice " + msg)
        } catch (_: Throwable) {
        }
    }
}