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
    private const val WATCH = "/data/local/tmp/tnt_home_watch.sh"
    private const val PID = "/data/local/tmp/tnt_home_watch.pid"
    private const val KICK = "/data/local/tmp/tnt_home_kick"
    private const val BOOT = "/data/adb/service.d/99tnt_home_watch.sh"

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
        val o = JSONObject()
        o.put("lock", lock)
        o.put("component", component.trim())
        val b64 = Base64.encodeToString(o.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val write = RootAccess.su("echo " + b64 + " | base64 -d > '" + STAGED + "' && chmod 644 '" + STAGED + "'")
        if (!write.ok) return write
        val copy = RootAccess.su(
            "cp '" + STAGED + "' '" + Paths.HOME_LOCK + "' && chown system:system '" + Paths.HOME_LOCK + "' && chmod 644 '" + Paths.HOME_LOCK + "'"
        )
        if (!copy.ok) return copy
        return if (lock) startWatch() else stopWatch()
    }

    fun ensureWatch() {
        if (!lockedOn()) return
        startWatch()
    }

    private fun lockedOn(): Boolean {
        val files = listOf(STAGED, Paths.HOME_LOCK)
        for (path in files) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            try {
                if (JSONObject(f.readText(Charsets.UTF_8)).optBoolean("lock", false)) return true
            } catch (_: Exception) {
            }
        }
        val r = RootAccess.su("cat '" + STAGED + "' 2>/dev/null")
        if (!r.ok || r.out.isBlank()) return false
        return try {
            JSONObject(r.out).optBoolean("lock", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun startWatch(): ShellResult {
        val w = writeFile(WATCH, watchScript(), "755")
        if (!w.ok) return w
        RootAccess.su("mkdir -p /data/adb/service.d")
        val b = writeFile(BOOT, bootScript(), "755")
        if (!b.ok) return b
        val fire = "old=@D@(cat " + PID + " 2>/dev/null); if [ -n \"@D@old\" ]; then kill @D@old 2>/dev/null; fi; sleep 0.2; nohup setsid sh " + WATCH + " </dev/null >/data/local/tmp/tnt_home_watch.log 2>&1 & echo started"
        val r = RootAccess.su(fire.replace("@D@", "\u0024"))
        return if (r.ok || r.out.contains("started")) ShellResult(0, r.out.ifBlank { "ok" }, "") else r
    }

    private fun stopWatch(): ShellResult {
        val cmd = "old=@D@(cat " + PID + " 2>/dev/null); if [ -n \"@D@old\" ]; then kill @D@old 2>/dev/null; fi; rm -f " + PID + " " + WATCH + " " + KICK + " " + BOOT
        return RootAccess.su(cmd.replace("@D@", "\u0024"))
    }

    private fun writeFile(path: String, body: String, mode: String): ShellResult {
        val b64 = Base64.encodeToString(body.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return RootAccess.su("echo " + b64 + " | base64 -d > '" + path + "' && chmod " + mode + " '" + path + "'")
    }

    private fun bootScript(): String {
        return """
#!/system/bin/sh
sleep 25
[ -f /data/local/tmp/tnt_home_lock.json ] || exit 0
grep -q '"lock":true' /data/local/tmp/tnt_home_lock.json || exit 0
sh /data/local/tmp/tnt_home_watch.sh >/data/local/tmp/tnt_home_watch.log 2>&1 &
""".trimIndent()
    }

    private fun watchScript(): String {
        val raw = """
#!/system/bin/sh
PIDF=/data/local/tmp/tnt_home_watch.pid
LOCKF=/data/local/tmp/tnt_home_lock.json
KICK=/data/local/tmp/tnt_home_kick
HOT=/data/local/tmp/tnt_hotswap.lck
echo @D@@ > "@D@PIDF"
last=
pending=0
lock_on() {
  [ -f "@D@LOCKF" ] || return 1
  grep -q '"lock":true' "@D@LOCKF"
}
comp() {
  grep -o '"component":"[^"]*"' "@D@LOCKF" 2>/dev/null | head -n 1 | cut -d '"' -f 4
}
busy() {
  [ -d "@D@HOT" ]
}
pc() {
  settings get secure pc_mode_enable 2>/dev/null
}
recover() {
  lock_on || return 0
  busy && return 0
  v=@D@(pc)
  [ "@D@v" = 1 ] && return 0
  [ "@D@v" = 2 ] && return 0
  c=@D@(comp)
  [ -n "@D@c" ] || return 0
  pkg=@D@{c%/*}
  case "@D@pkg" in
    com.smartisanos.home|com.smartisanos.desktop) return 0 ;;
  esac
  am force-stop com.smartisanos.home >/dev/null 2>&1
  i=0
  while [ "@D@i" -lt 3 ]; do
    am force-stop "@D@pkg" >/dev/null 2>&1
    sleep 0.7
    am start --user 0 -n "@D@c" >/dev/null 2>&1
    sleep 0.8
    i=@D@((i+1))
  done
}
if [ "@D@1" = recover ]; then
  recover
  exit 0
fi
while true; do
  lock_on || exit 0
  v=@D@(pc)
  kick=
  if [ -f "@D@KICK" ]; then
    kick=@D@(cat "@D@KICK" 2>/dev/null)
    rm -f "@D@KICK"
  fi
  if [ "@D@kick" = enter ]; then
    last=@D@v
    pending=0
  fi
  if [ "@D@kick" = exit ]; then
    pending=1
  fi
  case "@D@v" in
    1|2)
      last=@D@v
      pending=0
      ;;
    0)
      if [ "@D@last" = 1 ] || [ "@D@last" = 2 ]; then
        pending=1
      fi
      ;;
  esac
  if [ "@D@pending" = 1 ]; then
    if busy; then
      pending=0
    else
      sleep 5
      v2=@D@(pc)
      if busy; then
        pending=0
      elif [ "@D@v2" = 1 ] || [ "@D@v2" = 2 ]; then
        pending=0
        last=@D@v2
      elif [ "@D@v2" = 0 ]; then
        recover
        pending=0
        last=0
      fi
    fi
  fi
  sleep 2
done
""".trimIndent()
        return raw.replace("@D@", "\u0024")
    }
}
