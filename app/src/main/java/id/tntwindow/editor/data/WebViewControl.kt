package id.tntwindow.editor.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import id.tntwindow.editor.domain.Paths
import id.tntwindow.editor.domain.WebViewPackage
import id.tntwindow.editor.domain.WebViewSnapshot
import java.io.File
import java.util.zip.ZipFile

object WebViewControl {
    private const val MODULE = "id.tntwindow.editor"
    private val known = setOf(
        "com.android.webview",
        "com.google.android.webview",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.bromite.webview",
        "org.cromite.webview",
        "app.vanadium.webview",
        "us.spotco.mulch_wv",
    )

    fun snapshot(pm: PackageManager): WebViewSnapshot {
        val dump = RootAccess.su("dumpsys webviewupdate")
        val text = (dump.out + "\n" + dump.err).trim()
        val current = capture(text, """Current WebView package \(name, version\): \(([^,]+), ([^)]+)\)""")
        val preferred = capture(text, """Preferred WebView package \(name, version\): \(([^,]+), ([^)]+)\)""")
        val fallback = text.contains("Fallback logic enabled: true")
        val multi = text.contains("Multiprocess enabled: true")
        val valid = LinkedHashMap<String, Pair<String, Long>>()
        val validRe = Regex("""Valid package ([.\w]+) \(versionName: ([^,]*), versionCode: (\d+)""")
        for (m in validRe.findAll(text)) {
            valid[m.groupValues[1]] = m.groupValues[2] to (m.groupValues[3].toLongOrNull() ?: 0L)
        }
        val currentPkg = current?.first?.trim().orEmpty()
        val preferredPkg = preferred?.first?.trim().orEmpty()
        val seen = LinkedHashSet<String>()
        seen.addAll(valid.keys)
        seen.addAll(known)
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }
        for (p in pkgs) {
            val name = p.packageName ?: continue
            if (isCandidate(p.applicationInfo, name)) seen.add(name)
        }
        seen.remove("com.thinkdifferent.anywebview")
        val providers = ArrayList<WebViewPackage>()
        for (name in seen) {
            val ai = try {
                pm.getApplicationInfo(name, PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                null
            }
            val pi = try {
                pm.getPackageInfo(name, 0)
            } catch (_: Exception) {
                null
            }
            val installed = ai != null
            val cand = installed && isCandidate(ai, name)
            if (!installed && name !in valid) continue
            val label = if (ai != null) {
                try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { name }
            } else name
            val vn = pi?.versionName ?: valid[name]?.first ?: ""
            val vc = if (pi != null) pi.versionCode.toLong() else valid[name]?.second ?: 0L
            val abis = if (ai != null) packageAbis(ai) else emptyList()
            providers += WebViewPackage(
                packageName = name,
                label = label,
                versionName = vn,
                versionCode = vc,
                current = name == currentPkg,
                valid = name in valid,
                preferred = name == preferredPkg,
                candidate = cand || name in valid || name in known,
                abis = abis,
                abiOk = abiCompatible(abis),
            )
        }
        providers.sortWith(compareByDescending<WebViewPackage> { it.current }.thenByDescending { it.valid }.thenBy { it.label })
        val any = try {
            pm.getApplicationInfo("com.thinkdifferent.anywebview", 0)
            true
        } catch (_: Exception) {
            false
        }
        return WebViewSnapshot(
            currentPackage = currentPkg,
            currentVersion = current?.second?.trim().orEmpty(),
            preferredPackage = preferredPkg,
            fallbackEnabled = fallback,
            multiprocess = multi,
            providers = providers,
            anyWebViewInstalled = any,
            dump = text,
            frameworkScoped = frameworkScoped(),
        )
    }

    fun setImplementation(pkg: String): ShellResult {
        RootAccess.su("cmd webviewupdate enable-redundant-packages")
        return RootAccess.su("cmd webviewupdate set-webview-implementation " + pkg)
    }

    fun installed(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun validIn(snap: WebViewSnapshot, pkg: String): Boolean {
        return snap.providers.any { it.packageName == pkg && it.valid }
    }

    fun frameworkScoped(): Boolean {
        val names = moduleScope()
        return "android" in names || "system" in names
    }

    fun ensureVoiceScope(): ShellResult {
        return ensureScope(
            listOf(
                "android",
                "system",
                "com.smartisanos.virtualremoter",
                "com.smartisanos.voice",
                "com.smartisanos.sara",
                "com.smartisanos.desktop",
                "com.android.desktop.systemui",
                "com.android.systemui",
                "id.tntwindow.editor",
            )
        )
    }

    fun ensureFrameworkScope(): ShellResult {
        return ensureScope(listOf("android", "system"))
    }

    fun ensurePackages(pkgs: List<String>): ShellResult {
        return ensureScope(pkgs)
    }

    private fun ensureScope(want: List<String>): ShellResult {
        val names = moduleScope()
        if (want.all { it in names }) return ShellResult(0, "already", "")
        val live = "/data/adb/lspd/config/modules_config.db"
        val staged = "/data/local/tmp/tnt_lspd_scope.db"
        val copy = RootAccess.suMount(
            "cp '" + live + "' '" + staged + "' ; cp '" + live + "-wal' '" + staged + "-wal' 2>/dev/null ; cp '" + live + "-shm' '" + staged + "-shm' 2>/dev/null ; chmod 644 '" + staged + "' '" + staged + "-wal' '" + staged + "-shm' 2>/dev/null"
        )
        if (!File(staged).exists()) {
            return ShellResult(copy.code, copy.out, copy.err.ifBlank { "copy lspd db failed" })
        }
        return try {
            val db = SQLiteDatabase.openDatabase(staged, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val mid = midOf(db) ?: return ShellResult(1, "", "LSPosed 里没有本模块")
                for (pkg in want) {
                    db.execSQL("INSERT OR IGNORE INTO scope(mid, app_pkg_name, user_id) VALUES(?, ?, 0)", arrayOf(mid, pkg))
                }
                db.execSQL("UPDATE modules SET enabled=1 WHERE module_pkg_name=?", arrayOf(MODULE))
            } finally {
                db.close()
            }
            val put = RootAccess.suMount(
                "cp '" + staged + "' '" + live + "' ; cp '" + staged + "-wal' '" + live + "-wal' 2>/dev/null ; cp '" + staged + "-shm' '" + live + "-shm' 2>/dev/null ; chmod 644 '" + live + "'"
            )
            if (put.ok) ShellResult(0, "written", "") else put
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "scope write failed")
        }
    }

    fun reboot(): ShellResult {
        return RootAccess.su("reboot")
    }

    fun installApk(file: File): ShellResult {
        val staged = "/data/local/tmp/tnt_webview.apk"
        val copy = RootAccess.su("cat '" + file.absolutePath + "' > '" + staged + "' && chmod 644 '" + staged + "'")
        if (!copy.ok) return copy
        val install = RootAccess.su("pm install -r '" + staged + "'", timeoutSec = 120)
        RootAccess.su("rm -f '" + staged + "'")
        return install
    }

    fun openSystemPicker(context: Context): Boolean {
        val intents = listOf(
            Intent(Paths.WEBVIEW_SETTINGS),
            Intent().setClassName(Paths.SETTINGS_PKG, Paths.WEBVIEW_ACTIVITY),
        )
        for (base in intents) {
            try {
                val i = Intent(base)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun moduleScope(): Set<String> {
        val live = "/data/adb/lspd/config/modules_config.db"
        val staged = "/data/local/tmp/tnt_lspd_read.db"
        RootAccess.suMount(
            "cp '" + live + "' '" + staged + "' ; cp '" + live + "-wal' '" + staged + "-wal' 2>/dev/null ; cp '" + live + "-shm' '" + staged + "-shm' 2>/dev/null ; chmod 644 '" + staged + "' '" + staged + "-wal' '" + staged + "-shm' 2>/dev/null"
        )
        val f = File(staged)
        if (!f.exists() || f.length() == 0L) return emptySet()
        return try {
            val db = SQLiteDatabase.openDatabase(staged, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val mid = midOf(db) ?: return emptySet()
                val out = LinkedHashSet<String>()
                val c = db.rawQuery("SELECT app_pkg_name FROM scope WHERE mid=?", arrayOf(mid.toString()))
                while (c.moveToNext()) out += c.getString(0)
                c.close()
                out
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun midOf(db: SQLiteDatabase): Long? {
        val c = db.rawQuery("SELECT mid FROM modules WHERE module_pkg_name=?", arrayOf(MODULE))
        val mid = if (c.moveToFirst()) c.getLong(0) else null
        c.close()
        return mid
    }

    fun deviceNeeds64(): Boolean {
        return Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }

    fun apkAbis(file: File): List<String> {
        val out = LinkedHashSet<String>()
        try {
            ZipFile(file).use { z ->
                val e = z.entries()
                while (e.hasMoreElements()) {
                    val name = e.nextElement().name.replace("\\", "/")
                    if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
                    val parts = name.split("/")
                    if (parts.size >= 3) mapLib(parts[1])?.let { out += it }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    fun abiCompatible(abis: List<String>): Boolean {
        if (!deviceNeeds64()) return true
        if (abis.isEmpty()) return true
        return abis.any { it.contains("64") }
    }

    private fun packageAbis(ai: ApplicationInfo): List<String> {
        val out = LinkedHashSet<String>()
        abiField(ai, "primaryCpuAbi")?.let { out += it }
        abiField(ai, "secondaryCpuAbi")?.let { out += it }
        val nlib = ai.nativeLibraryDir
        if (!nlib.isNullOrBlank()) {
            mapLib(File(nlib).name)?.let { out += it }
            File(nlib).parentFile?.listFiles()?.forEach { child ->
                mapLib(child.name)?.let { out += it }
            }
        }
        val src = ai.sourceDir
        if (!src.isNullOrBlank()) {
            out += apkAbis(File(src))
            File(src).parentFile?.resolve("oat")?.listFiles()?.forEach { child ->
                mapLib(child.name)?.let { out += it }
            }
        }
        ai.splitSourceDirs?.forEach { out += apkAbis(File(it)) }
        return out.toList()
    }

    private fun abiField(ai: ApplicationInfo, name: String): String? {
        return try {
            val f = ApplicationInfo::class.java.getDeclaredField(name)
            f.isAccessible = true
            (f.get(ai) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapLib(name: String): String? {
        return when (name) {
            "arm64", "arm64-v8a" -> "arm64-v8a"
            "arm", "armeabi-v7a", "armeabi" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
    }

    private fun isCandidate(ai: ApplicationInfo?, pkg: String): Boolean {
        if (pkg in known) return true
        if (pkg.contains("webview", true)) return true
        val md = ai?.metaData ?: return false
        return md.containsKey("com.android.webview.WebViewLibrary") || md.containsKey("android.webkit.WebViewLibrary")
    }

    private fun capture(text: String, pattern: String): Pair<String, String>? {
        val m = Regex(pattern).find(text) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }
}