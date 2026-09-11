package id.tntwindow.editor.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import id.tntwindow.editor.domain.AppCollection
import id.tntwindow.editor.domain.Categories
import id.tntwindow.editor.domain.BackupItem
import id.tntwindow.editor.domain.CollectionType
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.Membership
import id.tntwindow.editor.domain.Presets
import id.tntwindow.editor.domain.WindowSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, "backups")
    val originalFile: File get() = File(root, "original/revone_window_config.xml")
    private val prewriteDir: File get() = File(root, "prewrite")
    private val manualDir: File get() = File(root, "manual")
    private val fullDir: File get() = File(root, "full")
    val workFile: File get() = File(context.filesDir, "work/revone_window_config.xml")
    val tmpFile: File get() = File(context.cacheDir, "revone_window_config.xml.tmp")
    val lastFile: File get() = File(root, "last/revone_window_config.xml")

    fun ensureDirs() {
        originalFile.parentFile?.mkdirs()
        prewriteDir.mkdirs()
        manualDir.mkdirs()
        fullDir.mkdirs()
        lastFile.parentFile?.mkdirs()
        workFile.parentFile?.mkdirs()
    }

    fun originalExists(): Boolean = originalFile.exists() && originalFile.length() > 0L

    fun originalTime(): Long? = if (originalExists()) originalFile.lastModified() else null

    fun saveOriginal(bytes: ByteArray) {
        if (originalExists()) return
        originalFile.parentFile?.mkdirs()
        originalFile.writeBytes(bytes)
    }

    fun saveLast(bytes: ByteArray) {
        lastFile.parentFile?.mkdirs()
        lastFile.writeBytes(bytes)
    }

    fun savePrewrite(bytes: ByteArray): File? = saveDedup(prewriteDir, "", bytes)

    fun saveManual(bytes: ByteArray): File? = saveDedup(manualDir, "manual_", bytes)

    fun delete(item: BackupItem): Boolean {
        if (item.kind == "ORIGINAL") return false
        val f = File(item.path)
        if (f.isDirectory) return f.deleteRecursively()
        return f.exists() && f.delete()
    }

    fun listRestorable(): List<BackupItem> {
        val items = mutableListOf<BackupItem>()
        if (originalExists()) {
            items += BackupItem(
                "ORIGINAL",
                "原件备份",
                originalFile.absolutePath,
                originalFile.lastModified(),
                originalFile.length(),
                hashOf(originalFile),
            )
        }
        fun addDir(dir: File, kind: String) {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                if (f.isFile) {
                    items += BackupItem(kind, f.name, f.absolutePath, f.lastModified(), f.length(), hashOf(f))
                }
            }
        }
        addDir(prewriteDir, "PREWRITE")
        addDir(manualDir, "MANUAL")
        fullDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { d ->
            val man = File(d, "manifest.json")
            items += BackupItem(
                "FULL",
                "完整备份 " + d.name,
                d.absolutePath,
                if (man.exists()) man.lastModified() else d.lastModified(),
                d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                "",
            )
        }
        return items
    }

    fun saveFull(files: Map<String, ByteArray>, version: String): File? {
        fullDir.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (k, v) ->
            md.update(k.toByteArray(Charsets.UTF_8))
            md.update(v)
        }
        val hash = md.digest().joinToString("") { b -> "%02x".format(b) }
        fullDir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val man = File(d, "manifest.json")
            if (man.exists()) {
                try {
                    val o = JSONObject(man.readText(Charsets.UTF_8))
                    if (o.optString("hash") == hash) return null
                } catch (_: Exception) {
                }
            }
        }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(fullDir, name)
        dir.mkdirs()
        files.forEach { (k, v) -> File(dir, k).writeBytes(v) }
        val man = JSONObject()
        man.put("kind", "FULL")
        man.put("version", version)
        man.put("createdAt", System.currentTimeMillis())
        man.put("hash", hash)
        man.put("files", JSONArray(files.keys.toList()))
        File(dir, "manifest.json").writeText(man.toString(), Charsets.UTF_8)
        return dir
    }

    private fun saveDedup(dir: File, prefix: String, bytes: ByteArray): File? {
        dir.mkdirs()
        val hash = sha256(bytes)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.length() == bytes.size.toLong() && hashOf(f) == hash) return null
        }
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + hash.take(8) + ".xml"
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    private fun hashOf(file: File): String {
        return try {
            sha256(file.readBytes())
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { b -> "%02x".format(b) }
        }
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tnt_window", Context.MODE_PRIVATE)

    var restartCommand: String
        get() = sp.getString("restart_cmd", "") ?: ""
        set(v) { sp.edit().putString("restart_cmd", v).apply() }

    var askRestartAfterWrite: Boolean
        get() = sp.getBoolean("ask_restart", true)
        set(v) { sp.edit().putBoolean("ask_restart", v).apply() }

    var showNoLauncher: Boolean
        get() = sp.getBoolean("show_no_launcher", false)
        set(v) { sp.edit().putBoolean("show_no_launcher", v).apply() }

    var showUninstalled: Boolean
        get() = sp.getBoolean("show_uninstalled", true)
        set(v) { sp.edit().putBoolean("show_uninstalled", v).apply() }

    var lastWriteAt: Long
        get() = sp.getLong("last_write_at", 0L)
        set(v) { sp.edit().putLong("last_write_at", v).apply() }

    var lastWriteOk: Boolean
        get() = sp.getBoolean("last_write_ok", false)
        set(v) { sp.edit().putBoolean("last_write_ok", v).apply() }

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean("disclaimer", false)
        set(v) { sp.edit().putBoolean("disclaimer", v).apply() }

    var defaultSpecJson: String
        get() = sp.getString("default_spec", specToJson(Presets.DEFAULT_NEW).toString()) ?: ""
        set(v) { sp.edit().putString("default_spec", v).apply() }

    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) { sp.edit().putString("theme_mode", v).apply() }

    var backupEveryWrite: Boolean
        get() = sp.getBoolean("backup_every", false)
        set(v) { sp.edit().putBoolean("backup_every", v).apply() }

    var defaultCollectionId: String
        get() = sp.getString("default_col", Categories.TOOL.id) ?: Categories.TOOL.id
        set(v) { sp.edit().putString("default_col", v).apply() }

    var autoAddEnabled: Boolean
        get() = sp.getBoolean("auto_add", true)
        set(v) { sp.edit().putBoolean("auto_add", v).apply() }

    var adaptiveEnabled: Boolean
        get() = sp.getBoolean("adaptive", false)
        set(v) { sp.edit().putBoolean("adaptive", v).apply() }

    var baselineWidthDp: Int
        get() = sp.getInt("baseline_dp", Presets.BASELINE_WIDTH_DP)
        set(v) { sp.edit().putInt("baseline_dp", v).apply() }

    var collectionSchema: Int
        get() = sp.getInt("col_schema", 0)
        set(v) { sp.edit().putInt("col_schema", v).apply() }

    fun seenPackages(): MutableSet<String> {
        return sp.getStringSet("seen", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun addSeen(pkgs: Collection<String>) {
        val s = seenPackages()
        s.addAll(pkgs)
        sp.edit().putStringSet("seen", s).apply()
    }

    fun skipAuto(): MutableSet<String> {
        return sp.getStringSet("skip_auto", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun addSkipAuto(pkgs: Collection<String>) {
        val s = skipAuto()
        s.addAll(pkgs)
        sp.edit().putStringSet("skip_auto", s).apply()
    }

    fun removeSkipAuto(pkgs: Collection<String>) {
        val s = skipAuto()
        s.removeAll(pkgs.toSet())
        sp.edit().putStringSet("skip_auto", s).apply()
    }

    var apatchProtect: Boolean
        get() = sp.getBoolean("apatch_protect", true)
        set(v) { sp.edit().putBoolean("apatch_protect", v).apply() }

    var quietInstall: Boolean
        get() = sp.getBoolean("quiet_install", true)
        set(v) { sp.edit().putBoolean("quiet_install", v).apply() }

    var lockedHome: String
        get() = sp.getString("locked_home", "") ?: ""
        set(v) { sp.edit().putString("locked_home", v).apply() }

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_on", false)
        set(v) { sp.edit().putBoolean("overlay_on", v).apply() }

    var homeLockEnabled: Boolean
        get() = sp.getBoolean("home_lock_on", true)
        set(v) { sp.edit().putBoolean("home_lock_on", v).apply() }

    fun dump(): JSONObject {
        val o = JSONObject()
        o.put("restart_cmd", restartCommand)
        o.put("ask_restart", askRestartAfterWrite)
        o.put("show_no_launcher", showNoLauncher)
        o.put("show_uninstalled", showUninstalled)
        o.put("disclaimer", disclaimerAccepted)
        o.put("default_spec", defaultSpecJson)
        o.put("theme_mode", themeMode)
        o.put("backup_every", backupEveryWrite)
        o.put("default_col", defaultCollectionId)
        o.put("auto_add", autoAddEnabled)
        o.put("adaptive", adaptiveEnabled)
        o.put("baseline_dp", baselineWidthDp)
        o.put("col_schema", collectionSchema)
        o.put("apatch_protect", apatchProtect)
        o.put("quiet_install", quietInstall)
        o.put("locked_home", lockedHome)
        o.put("overlay_on", overlayEnabled)
        o.put("home_lock_on", homeLockEnabled)
        o.put("seen", JSONArray(seenPackages().toList()))
        o.put("skip_auto", JSONArray(skipAuto().toList()))
        return o
    }

    fun applyDump(o: JSONObject) {
        restartCommand = o.optString("restart_cmd", restartCommand)
        askRestartAfterWrite = o.optBoolean("ask_restart", askRestartAfterWrite)
        showNoLauncher = o.optBoolean("show_no_launcher", showNoLauncher)
        showUninstalled = o.optBoolean("show_uninstalled", showUninstalled)
        disclaimerAccepted = o.optBoolean("disclaimer", disclaimerAccepted)
        if (o.has("default_spec")) defaultSpecJson = o.optString("default_spec", defaultSpecJson)
        themeMode = o.optString("theme_mode", themeMode)
        backupEveryWrite = o.optBoolean("backup_every", backupEveryWrite)
        defaultCollectionId = o.optString("default_col", defaultCollectionId)
        autoAddEnabled = o.optBoolean("auto_add", autoAddEnabled)
        adaptiveEnabled = o.optBoolean("adaptive", adaptiveEnabled)
        if (o.has("baseline_dp")) baselineWidthDp = o.optInt("baseline_dp", baselineWidthDp)
        if (o.has("col_schema")) collectionSchema = o.optInt("col_schema", collectionSchema)
        apatchProtect = o.optBoolean("apatch_protect", apatchProtect)
        quietInstall = o.optBoolean("quiet_install", quietInstall)
        lockedHome = o.optString("locked_home", lockedHome)
        overlayEnabled = o.optBoolean("overlay_on", overlayEnabled)
        homeLockEnabled = o.optBoolean("home_lock_on", homeLockEnabled)
        if (o.has("seen")) {
            val s = mutableSetOf<String>()
            val arr = o.optJSONArray("seen") ?: JSONArray()
            for (i in 0 until arr.length()) s += arr.optString(i)
            sp.edit().putStringSet("seen", s).apply()
        }
        if (o.has("skip_auto")) {
            val s = mutableSetOf<String>()
            val arr = o.optJSONArray("skip_auto") ?: JSONArray()
            for (i in 0 until arr.length()) s += arr.optString(i)
            sp.edit().putStringSet("skip_auto", s).apply()
        }
    }
}

class CollectionStore(private val context: Context) {
    private val file: File get() = File(context.filesDir, "collections.json")
    var lastSchema: Int = 0
        private set

    fun load(): List<AppCollection>? {
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return null
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            lastSchema = o.optInt("schema", 1)
            readList(o.optJSONArray("items") ?: JSONArray())
        } else {
            lastSchema = 1
            readList(JSONArray(trimmed))
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
        lastSchema = 0
    }

    fun save(list: List<AppCollection>) {
        val root = JSONObject()
        root.put("schema", Presets.SCHEMA)
        val arr = JSONArray()
        list.forEach { arr.put(writeCollection(it)) }
        root.put("items", arr)
        file.writeText(root.toString(), Charsets.UTF_8)
        lastSchema = Presets.SCHEMA
    }

    private fun readList(arr: JSONArray): List<AppCollection> {
        val out = mutableListOf<AppCollection>()
        for (i in 0 until arr.length()) out += readCollection(arr.getJSONObject(i))
        return out
    }
}

fun specToJson(spec: WindowSpec): JSONObject {
    val o = JSONObject()
    o.put("windowMode", spec.windowMode ?: JSONObject.NULL)
    o.put("width", spec.width ?: JSONObject.NULL)
    o.put("height", spec.height ?: JSONObject.NULL)
    o.put("minWidth", spec.minWidth ?: JSONObject.NULL)
    o.put("minHeight", spec.minHeight ?: JSONObject.NULL)
    o.put("resizeMode", spec.resizeMode ?: JSONObject.NULL)
    return o
}

fun specFromJson(o: JSONObject?): WindowSpec? {
    if (o == null) return null
    fun n(key: String): Int? = if (o.isNull(key)) null else o.optInt(key)
    return WindowSpec(n("windowMode"), n("width"), n("height"), n("minWidth"), n("minHeight"), n("resizeMode"))
}

private fun writeCollection(c: AppCollection): JSONObject {
    val o = JSONObject()
    o.put("id", c.id)
    o.put("name", c.name)
    o.put("type", c.type.name)
    o.put("pinned", c.pinned)
    o.put("builtin", c.builtin)
    if (c.spec != null) o.put("spec", specToJson(c.spec))
    val members = JSONArray()
    c.members.forEach { m ->
        val mo = JSONObject()
        mo.put("packageName", m.packageName)
        if (m.customSpec != null) mo.put("customSpec", specToJson(m.customSpec))
        members.put(mo)
    }
    o.put("members", members)
    return o
}

private fun readCollection(o: JSONObject): AppCollection {
    val members = mutableListOf<Membership>()
    val arr = o.optJSONArray("members") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val mo = arr.getJSONObject(i)
        members += Membership(
            packageName = mo.getString("packageName"),
            customSpec = if (mo.has("customSpec")) specFromJson(mo.getJSONObject("customSpec")) else null,
        )
    }
    val id = o.getString("id")
    return AppCollection(
        id = id,
        name = o.getString("name"),
        type = CollectionType.valueOf(o.getString("type")),
        spec = if (o.has("spec")) specFromJson(o.getJSONObject("spec")) else null,
        members = members,
        pinned = o.optBoolean("pinned"),
        builtin = o.optBoolean("builtin") || id in Categories.IDS,
    )
}

class InstalledApps(private val context: Context) {
    fun all(): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = HashSet<String>()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).forEach { launcher += it.activityInfo.packageName }
        val list = mutableListOf<InstalledApp>()
        pm.getInstalledApplications(0).forEach { info ->
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            val pi = try {
                pm.getPackageInfo(info.packageName, 0)
            } catch (_: Exception) {
                null
            }
            val cat = if (Build.VERSION.SDK_INT >= 26) info.category else -1
            list += InstalledApp(
                packageName = info.packageName,
                label = label,
                system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                hasLauncher = launcher.contains(info.packageName),
                firstInstallTime = pi?.firstInstallTime ?: 0L,
                lastUpdateTime = pi?.lastUpdateTime ?: 0L,
                category = cat,
            )
        }
        return list
    }

    fun icon(packageName: String): android.graphics.drawable.Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    fun label(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun launch(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}