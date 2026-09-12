package id.tntwindow.editor.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import id.tntwindow.editor.domain.HomeLauncher
import id.tntwindow.editor.domain.Paths
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

object HomeLock {
    private const val STAGED = "/data/local/tmp/tnt_home_lock.json"

    fun officialComponent(): String = TntLaunch.HOME_PKG + "/" + TntLaunch.HOME_CLS

    fun list(context: Context): List<HomeLauncher> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        val infos = try {
            pm.queryIntentActivities(intent, flags)
        } catch (_: Exception) {
            pm.queryIntentActivities(intent, 0)
        }
        val out = LinkedHashMap<String, HomeLauncher>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == "android") continue
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Exception) {
                ai.packageName
            }
            val item = HomeLauncher(
                packageName = ai.packageName,
                activity = ai.name,
                label = label,
                official = ai.packageName == TntLaunch.HOME_PKG,
            )
            out.putIfAbsent(item.component, item)
        }
        if (out.values.none { it.official }) {
            val fallback = HomeLauncher(TntLaunch.HOME_PKG, TntLaunch.HOME_CLS, "锤子桌面", true)
            out[fallback.component] = fallback
        }
        return out.values.sortedWith(compareByDescending<HomeLauncher> { it.official }.thenBy { it.label })
    }

    fun current(context: Context): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        val ai = ri?.activityInfo ?: return ""
        val pkg = ai.packageName ?: return ""
        val name = ai.name ?: ""
        if (pkg == "android" || name.contains("ResolverActivity") || name.contains("ChooserActivity")) return ""
        return pkg + "/" + name
    }

    fun target(): String {
        val files = listOf(Paths.HOME_LOCK, STAGED)
        for (path in files) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            try {
                val c = JSONObject(f.readText(Charsets.UTF_8)).optString("component").trim()
                if (c.contains('/')) return c
            } catch (_: Exception) {
            }
        }
        return ""
    }

    fun enableOfficial(): ShellResult {
        val cmds = listOf(
            "pm enable --user 0 " + TntLaunch.HOME_PKG,
            "pm enable --user 0 " + TntLaunch.HOME_PKG + "/" + TntLaunch.HOME_CLS,
            "pm unhide " + TntLaunch.HOME_PKG,
        )
        var last = ShellResult(-1, "", "")
        for (c in cmds) last = RootAccess.su(c)
        return last
    }

    fun setHome(component: String): ShellResult {
        val c = component.trim()
        if (c.isEmpty() || !c.contains('/')) return ShellResult(1, "", "组件为空")
        enableOfficial()
        val cmds = listOf(
            "cmd package set-home-activity --user 0 " + c,
            "cmd package set-home-activity " + c,
            "pm set-home-activity --user 0 " + c,
            "pm set-home-activity " + c,
        )
        var last = ShellResult(1, "", "设置失败")
        for (cmd in cmds) {
            val r = RootAccess.su(cmd)
            val text = (r.out + "\n" + r.err).lowercase()
            if (r.ok && !text.contains("unknown") && !text.contains("not found") && !text.contains("error") && !text.contains("exception")) {
                return ShellResult(0, r.out.ifBlank { "ok" }, "")
            }
            last = r
        }
        return last
    }

    fun ensureOfficialPreferred(): ShellResult = setHome(officialComponent())

    fun push(lock: Boolean, component: String): ShellResult {
        cleanupWatch()
        val o = JSONObject()
        o.put("lock", lock)
        o.put("component", component.trim())
        val b64 = Base64.encodeToString(o.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val write = RootAccess.su("echo " + b64 + " | base64 -d > '" + STAGED + "' && chmod 644 '" + STAGED + "'")
        if (!write.ok) return write
        return RootAccess.su(
            "cp '" + STAGED + "' '" + Paths.HOME_LOCK + "' && chown system:system '" + Paths.HOME_LOCK + "' && chmod 644 '" + Paths.HOME_LOCK + "'"
        )
    }

    fun cleanupWatch() {
        val cmd = "old=@D@(cat /data/local/tmp/tnt_home_watch.pid 2>/dev/null); if [ -n \"@D@old\" ]; then kill @D@old 2>/dev/null; fi; rm -f /data/local/tmp/tnt_home_watch.pid /data/local/tmp/tnt_home_watch.sh /data/local/tmp/tnt_home_kick /data/adb/service.d/99tnt_home_watch.sh"
        RootAccess.su(cmd.replace("@D@", "\u0024"))
    }
}
