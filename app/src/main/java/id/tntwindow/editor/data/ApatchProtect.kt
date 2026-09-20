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
    const val MODULE_DIR = "/data/adb/modules/chuibing_apatch"
    const val PIN = "/data/adb/ap/chuibing_pin"
    const val PIN_MODULE = "/data/adb/modules/chuibing_apatch/package_config.pin"
    private const val OLD_MODULE = "/data/adb/modules/smartisan_patch"
    const val SELF = "id.tntwindow.editor"
    private const val HEADER = "pkg,exclude,allow,uid,to_uid,sctx"
    private const val SCTX = "u:r:magisk:s0"
    private const val PID = "/data/local/tmp/chuibing_apatch.pid"

    fun readRaw(): String {
        val r = RootAccess.suMount("cat '" + CONFIG + "' 2>/dev/null")
        return r.out
    }

    fun present(): Boolean {
        val r = RootAccess.su("test -d /data/adb/ap -o -f /data/adb/ap/package_config && echo yes")
        return r.out.contains("yes")
    }

    fun apply(context: Context, on: Boolean): ApatchStatus {
        if (!on) {
            stopGuard()
            return ApatchStatus(ok = true, detail = "off")
        }
        if (!present()) {
            stopGuard()
            return ApatchStatus(ok = false, detail = "no apatch")
        }
        val st = enforce(context)
        startGuard(context)
        return st
    }

    fun enforce(context: Context): ApatchStatus {
        val mod = installModule(context)
        val liveText = readRaw()
        val origLines = liveText.split('\n').map { it.trimEnd('\r') }
        val byPkg = LinkedHashMap<String, ApRow>()
        for (line in origLines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("pkg,")) continue
            val row = parseRow(t) ?: continue
            if (!byPkg.containsKey(row.pkg)) byPkg[row.pkg] = row
        }
        val pinPkgs = LinkedHashSet<String>()
        pinPkgs.add(SELF)
        for (row in byPkg.values) if (row.allow == 1) pinPkgs.add(row.pkg)
        pinPkgs.addAll(readPinPackages(context))
        val uidMap = HashMap<String, Int>()
        for (pkg in pinPkgs) {
            val uid = uidOf(context, pkg)
            if (uid > 0) uidMap[pkg] = uid
        }
        var fixed = 0
        val seen = LinkedHashSet<String>()
        val outLines = ArrayList<String>()
        outLines.add(HEADER)
        for (line in origLines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("pkg,")) continue
            val row = parseRow(t)
            if (row == null) continue
            if (!seen.add(row.pkg)) continue
            if (pinPkgs.contains(row.pkg)) {
                val uid = uidMap[row.pkg] ?: row.uid
                val sctx = if (row.sctx.isBlank()) SCTX else row.sctx
                val next = row.copy(
                    exclude = 0,
                    allow = 1,
                    uid = if (uid > 0) uid else row.uid,
                    sctx = sctx,
                )
                if (next != row) fixed += 1
                outLines.add(renderRow(next))
                byPkg[row.pkg] = next
            } else {
                outLines.add(line)
            }
        }
        for (pkg in pinPkgs) {
            if (seen.contains(pkg)) continue
            val uid = uidMap[pkg] ?: byPkg[pkg]?.uid ?: continue
            if (uid <= 0) continue
            val prev = byPkg[pkg]
            val next = ApRow(pkg, 0, 1, uid, prev?.toUid ?: 0, prev?.sctx?.ifBlank { SCTX } ?: SCTX)
            outLines.add(renderRow(next))
            byPkg[pkg] = next
            seen.add(pkg)
            fixed += 1
        }
        val text = outLines.joinToString("\n") + "\n"
        writePin(context, pinPkgs, byPkg)
        if (needsWrite(origLines, pinPkgs, byPkg)) writeConfig(text)
        return ApatchStatus(pinPkgs.size, fixed, mod, if (mod) "ok" else "module")
    }

    fun restore(text: String): ShellResult {
        if (text.isBlank()) return ShellResult(-1, "", "empty")
        return writeConfig(text)
    }

    fun installModule(context: Context): Boolean {
        RootAccess.su("rm -rf '" + OLD_MODULE + "'")
        RootAccess.suMount("rm -rf '" + OLD_MODULE + "'")
        val cache = File(context.cacheDir, "chuibing_apatch")
        cache.mkdirs()
        val dirs = listOf(MODULE_DIR, "/data/adb/modules_update/chuibing_apatch")
        for (dir in dirs) {
            RootAccess.su("mkdir -p '" + dir + "'")
            RootAccess.suMount("mkdir -p '" + dir + "'")
        }
        var ok = false
        for (name in listOf("module.prop", "service.sh", "post-fs-data.sh")) {
            val local = File(cache, name)
            try {
                context.assets.open("module/" + name).use { ins ->
                    local.outputStream().use { ins.copyTo(it) }
                }
            } catch (_: Exception) {
                continue
            }
            val staged = "/data/local/tmp/cb_" + name
            val mode = if (name.endsWith(".sh")) "755" else "644"
            RootAccess.su("cat '" + local.absolutePath + "' > '" + staged + "'")
            for (dir in dirs) {
                val w1 = RootAccess.su("cat '" + staged + "' > '" + dir + "/" + name + "' && chmod " + mode + " '" + dir + "/" + name + "'")
                val w2 = RootAccess.suMount("cat '" + staged + "' > '" + dir + "/" + name + "' && chmod " + mode + " '" + dir + "/" + name + "'")
                if (w1.ok || w2.ok) ok = true
            }
            RootAccess.su("rm -f '" + staged + "'")
        }
        RootAccess.su("rm -f '" + MODULE_DIR + "/disable' /data/adb/modules_update/chuibing_apatch/disable")
        val sdName = "99chuibing_apatch.sh"
        val sdLocal = File(cache, sdName)
        try {
            context.assets.open("module/" + sdName).use { ins ->
                sdLocal.outputStream().use { ins.copyTo(it) }
            }
            val staged = "/data/local/tmp/cb_" + sdName
            RootAccess.su("mkdir -p /data/adb/service.d")
            RootAccess.suMount("mkdir -p /data/adb/service.d")
            RootAccess.su("cat '" + sdLocal.absolutePath + "' > '" + staged + "'")
            val w1 = RootAccess.su("cat '" + staged + "' > '/data/adb/service.d/" + sdName + "' && chmod 755 '/data/adb/service.d/" + sdName + "'")
            val w2 = RootAccess.suMount("cat '" + staged + "' > '/data/adb/service.d/" + sdName + "' && chmod 755 '/data/adb/service.d/" + sdName + "'")
            if (w1.ok || w2.ok) ok = true
            RootAccess.su("rm -f '" + staged + "'")
        } catch (_: Exception) {
        }
        val check = RootAccess.su("test -f '" + MODULE_DIR + "/module.prop' -a -f '" + MODULE_DIR + "/service.sh' && echo yes")
        val check2 = RootAccess.suMount("test -f '" + MODULE_DIR + "/module.prop' -a -f '" + MODULE_DIR + "/service.sh' && echo yes")
        return check.out.contains("yes") || check2.out.contains("yes") || ok
    }

    fun startGuard(context: Context): ShellResult {
        installModule(context)
        RootAccess.su("rm -f '" + MODULE_DIR + "/disable'")
        RootAccess.suMount("rm -f '" + MODULE_DIR + "/disable'")
        RootAccess.su("sh '" + MODULE_DIR + "/service.sh' merge")
        RootAccess.suMount("sh '" + MODULE_DIR + "/service.sh' merge")
        val d = "\u0024"
        val start = "old=" + d + "(cat " + PID + " 2>/dev/null); if [ -n \"" + d + "old\" ] && kill -0 \"" + d + "old\" 2>/dev/null; then exit 0; fi; trap '' HUP; if command -v setsid >/dev/null 2>&1; then setsid sh '" + MODULE_DIR + "/service.sh' >/dev/null 2>&1 & else nohup sh '" + MODULE_DIR + "/service.sh' >/dev/null 2>&1 & fi"
        return RootAccess.su(start)
    }

    fun stopGuard(): ShellResult {
        val d = "\u0024"
        val cmd = "mkdir -p '" + MODULE_DIR + "'; echo x > '" + MODULE_DIR + "/disable'; old=" + d + "(cat " + PID + " 2>/dev/null); if [ -n \"" + d + "old\" ]; then kill " + d + "old 2>/dev/null; fi; rm -f " + PID
        return RootAccess.su(cmd)
    }

    private fun uidOf(context: Context, pkg: String): Int {
        try {
            val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
            if (uid > 0) return uid
        } catch (_: Exception) {
        }
        val r = RootAccess.su("stat -c %u /data/data/" + pkg + " 2>/dev/null")
        return r.out.trim().toIntOrNull() ?: 0
    }

    private fun splitLimit(line: String, limit: Int): List<String> {
        if (limit <= 1) return listOf(line)
        val out = ArrayList<String>(limit)
        var start = 0
        var left = limit
        while (left > 1) {
            val i = line.indexOf(',', start)
            if (i < 0) {
                out.add(line.substring(start))
                return out
            }
            out.add(line.substring(start, i))
            start = i + 1
            left -= 1
        }
        out.add(line.substring(start))
        return out
    }

    private fun parseRow(line: String): ApRow? {
        val p = splitLimit(line, 6)
        if (p.size < 6) return null
        val pkg = p[0].trim()
        val exclude = p[1].trim().toIntOrNull() ?: return null
        val allow = p[2].trim().toIntOrNull() ?: return null
        val uid = p[3].trim().toIntOrNull() ?: return null
        val toUid = p[4].trim().toIntOrNull() ?: return null
        var sctx = p[5].trim()
        if (sctx.isEmpty()) sctx = SCTX
        if (pkg.isEmpty() || !pkg.contains('.')) return null
        return ApRow(pkg, exclude, allow, uid, toUid, sctx)
    }

    private fun renderRow(r: ApRow): String {
        val sctx = if (r.sctx.isBlank()) SCTX else r.sctx
        return r.pkg + "," + r.exclude + "," + r.allow + "," + r.uid + "," + r.toUid + "," + sctx
    }

    private fun needsWrite(origLines: List<String>, pinPkgs: Set<String>, byPkg: Map<String, ApRow>): Boolean {
        val seen = LinkedHashSet<String>()
        val live = LinkedHashMap<String, ApRow>()
        var bad = false
        for (line in origLines) {
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("pkg,")) continue
            val row = parseRow(raw)
            if (row == null) {
                bad = true
                continue
            }
            if (!seen.add(row.pkg)) bad = true
            if (!live.containsKey(row.pkg)) live[row.pkg] = row
        }
        if (bad) return true
        for (pkg in pinPkgs) {
            val row = live[pkg]
            if (row == null || row.allow != 1) return true
        }
        return false
    }

    private fun normalize(text: String): String {
        return text.replace("\r", "").trim() + "\n"
    }

    private fun writeConfig(text: String): ShellResult {
        return writeKeep(CONFIG, text)
    }

    private fun writeKeep(path: String, text: String): ShellResult {
        val tmp = File.createTempFile("apatch", ".csv")
        tmp.writeText(text, Charsets.UTF_8)
        val staged = "/data/local/tmp/cb_" + path.substringAfterLast('/')
        val copy = RootAccess.su("cat '" + tmp.absolutePath + "' > '" + staged + "'")
        tmp.delete()
        if (!copy.ok) return copy
        val tmpPath = path + ".tmp"
        return RootAccess.suMount(
            "mkdir -p /data/adb/ap '" + MODULE_DIR + "' && cat '" + staged + "' > '" + tmpPath + "' && chmod 600 '" + tmpPath + "' && mv -f '" + tmpPath + "' '" + path + "' && rm -f '" + staged + "'"
        )
    }

    private fun readPinPackages(context: Context): List<String> {
        val local = File(context.filesDir, "apatch_pin.txt")
        val fromLocal = if (local.exists()) local.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val remote = RootAccess.suMount("cat '" + PIN + "' '" + PIN_MODULE + "' 2>/dev/null").out
        val fromRemote = ArrayList<String>()
        for (raw in remote.split('\n')) {
            val row = parseRow(raw.trimEnd('\r').trim()) ?: continue
            if (row.allow == 1) fromRemote.add(row.pkg)
        }
        return (fromLocal + fromRemote).distinct()
    }

    private fun writePin(context: Context, pkgs: Set<String>, byPkg: Map<String, ApRow>) {
        val local = File(context.filesDir, "apatch_pin.txt")
        local.writeText(pkgs.joinToString("\n"), Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (pkg in pkgs) {
            val row = byPkg[pkg] ?: continue
            if (row.uid <= 0 && pkg != SELF) continue
            val uid = if (row.uid > 0) row.uid else 0
            val sctx = if (row.sctx.isBlank()) SCTX else row.sctx
            sb.append(pkg).append(",0,1,").append(uid).append(',').append(row.toUid).append(',').append(sctx).append('\n')
        }
        val text = sb.toString()
        writeKeep(PIN, text)
        writeKeep(PIN_MODULE, text)
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
