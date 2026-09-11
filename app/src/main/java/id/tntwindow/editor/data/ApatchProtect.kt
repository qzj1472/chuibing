package id.tntwindow.editor.data

import android.content.Context
import java.io.File
import java.util.LinkedHashSet

data class ApatchStatus(
    val pinned: Int = 0,
    val fixed: Int = 0,
    val ok: Boolean = false,
    val detail: String = "",
)

object ApatchProtect {
    const val CONFIG = "/data/adb/ap/package_config"
    const val MODULE_DIR = "/data/adb/modules/smartisan_patch"
    const val PIN = "/data/adb/modules/smartisan_patch/package_config.pin"
    const val SELF = "id.tntwindow.editor"
    private const val HEADER = "pkg,exclude,allow,uid,to_uid,sctx"
    private const val SCTX = "u:r:magisk:s0"

    fun readRaw(): String {
        val r = RootAccess.suMount("cat '$CONFIG' 2>/dev/null")
        return r.out
    }

    fun enforce(context: Context): ApatchStatus {
        installModule(context)
        val liveText = readRaw()
        val live = parse(liveText)
        val pinPkgs = LinkedHashSet<String>()
        pinPkgs.add(SELF)
        pinPkgs.addAll(readPinPackages(context))
        for (row in live) if (row.allow == 1) pinPkgs.add(row.pkg)
        val uidMap = HashMap<String, Int>()
        for (pkg in pinPkgs) {
            val uid = uidOf(context, pkg)
            if (uid > 0) uidMap[pkg] = uid
        }
        val byPkg = LinkedHashMap<String, ApRow>()
        for (row in live) byPkg[row.pkg] = row
        var fixed = 0
        for (pkg in pinPkgs) {
            val uid = uidMap[pkg] ?: byPkg[pkg]?.uid ?: continue
            val cur = byPkg[pkg]
            if (cur == null) {
                byPkg[pkg] = ApRow(pkg, 0, 1, uid, 0, SCTX)
                fixed += 1
            } else if (cur.allow != 1 || (uid > 0 && cur.uid != uid)) {
                val nextUid = if (uid > 0) uid else cur.uid
                byPkg[pkg] = cur.copy(allow = 1, uid = nextUid)
                fixed += 1
            }
        }
        val text = render(byPkg.values.toList())
        val write = writeConfig(text)
        writePin(context, pinPkgs, text)
        return ApatchStatus(pinPkgs.size, fixed, write.ok, if (write.ok) "ok" else write.err.ifBlank { write.out })
    }

    fun restore(text: String): ShellResult {
        if (text.isBlank()) return ShellResult(-1, "", "empty")
        return writeConfig(text)
    }

    fun installModule(context: Context) {
        val cache = File(context.cacheDir, "smartisan_patch")
        cache.mkdirs()
        RootAccess.suMount("mkdir -p '$MODULE_DIR'")
        for (name in listOf("module.prop", "service.sh", "post-fs-data.sh")) {
            val local = File(cache, name)
            try {
                context.assets.open("module/$name").use { ins ->
                    local.outputStream().use { ins.copyTo(it) }
                }
            } catch (_: Exception) {
                continue
            }
            val staged = "/data/local/tmp/sp_$name"
            val mode = if (name.endsWith(".sh")) "755" else "644"
            RootAccess.su("cat '" + local.absolutePath + "' > '" + staged + "'")
            RootAccess.suMount("cat '$staged' > '$MODULE_DIR/$name' && chmod $mode '$MODULE_DIR/$name' && rm -f '$staged'")
        }
    }

    private fun uidOf(context: Context, pkg: String): Int {
        try {
            val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
            if (uid > 0) return uid
        } catch (_: Exception) {
        }
        val r = RootAccess.su("stat -c %u /data/data/$pkg 2>/dev/null")
        return r.out.trim().toIntOrNull() ?: 0
    }

    private fun parse(text: String): List<ApRow> {
        val out = ArrayList<ApRow>()
        for (raw in text.split('\n', '\r')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("pkg,")) continue
            val p = line.split(',')
            if (p.size < 6) continue
            val uid = p[3].toIntOrNull() ?: continue
            val allow = p[2].toIntOrNull() ?: continue
            val exclude = p[1].toIntOrNull() ?: 0
            val toUid = p[4].toIntOrNull() ?: 0
            out += ApRow(p[0], exclude, allow, uid, toUid, p[5])
        }
        return out
    }

    private fun render(rows: List<ApRow>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (r in rows) {
            sb.append(r.pkg).append(',').append(r.exclude).append(',').append(r.allow).append(',')
                .append(r.uid).append(',').append(r.toUid).append(',').append(r.sctx).append('\n')
        }
        return sb.toString()
    }

    private fun writeConfig(text: String): ShellResult {
        val tmp = File.createTempFile("apatch", ".csv")
        tmp.writeText(text, Charsets.UTF_8)
        val staged = "/data/local/tmp/apatch_package_config"
        val copy = RootAccess.su("cat '" + tmp.absolutePath + "' > '" + staged + "'")
        tmp.delete()
        if (!copy.ok) return copy
        return RootAccess.suMount("cp '$staged' '$CONFIG' && chmod 600 '$CONFIG' && rm -f '$staged'")
    }

    private fun readPinPackages(context: Context): List<String> {
        val local = File(context.filesDir, "apatch_pin.txt")
        val fromLocal = if (local.exists()) local.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val remote = RootAccess.suMount("cat '$PIN' 2>/dev/null").out
        val fromRemote = parse(remote).map { it.pkg }
        return (fromLocal + fromRemote).distinct()
    }

    private fun writePin(context: Context, pkgs: Set<String>, configText: String) {
        val local = File(context.filesDir, "apatch_pin.txt")
        local.writeText(pkgs.joinToString("\n"), Charsets.UTF_8)
        val tmp = File.createTempFile("apatchpin", ".csv")
        tmp.writeText(configText, Charsets.UTF_8)
        val staged = "/data/local/tmp/apatch_package_config.pin"
        RootAccess.su("cat '" + tmp.absolutePath + "' > '" + staged + "'")
        tmp.delete()
        RootAccess.suMount("cp '$staged' '$PIN' && chmod 600 '$PIN' && rm -f '$staged'")
    }

    private data class ApRow(
        val pkg: String,
        val exclude: Int,
        val allow: Int,
        val uid: Int,
        val toUid: Int,
        val sctx: String,
    )
}