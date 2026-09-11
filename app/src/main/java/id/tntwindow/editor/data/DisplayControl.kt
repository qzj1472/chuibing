package id.tntwindow.editor.data

import android.os.IBinder
import android.util.Base64
import id.tntwindow.editor.domain.DisplayInfo
import java.nio.charset.StandardCharsets

object DisplayControl {
    fun info(): DisplayInfo {
        val size = RootAccess.su("wm size")
        val dens = RootAccess.su("wm density")
        val text = size.out + "\n" + dens.out
        val physicalSize = Regex("""Physical size:\s*(\d+)x(\d+)""").find(text)
        val overrideSize = Regex("""Override size:\s*(\d+)x(\d+)""").find(text)
        val physicalDens = Regex("""Physical density:\s*(\d+)""").find(text)
        val overrideDens = Regex("""Override density:\s*(\d+)""").find(text)
        val pw = physicalSize?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ph = physicalSize?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val ow = overrideSize?.groupValues?.get(1)?.toIntOrNull()
        val oh = overrideSize?.groupValues?.get(2)?.toIntOrNull()
        val pd = physicalDens?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val od = overrideDens?.groupValues?.get(1)?.toIntOrNull()
        val w = ow ?: pw
        val h = oh ?: ph
        val d = od ?: pd
        return DisplayInfo(
            widthPx = w,
            heightPx = h,
            density = d,
            physicalWidthPx = pw,
            physicalHeightPx = ph,
            physicalDensity = pd,
            spoofedSize = ow != null,
            spoofedDensity = od != null,
        )
    }

    fun setSize(width: Int, height: Int) = RootAccess.su("wm size ${width}x${height}")

    fun resetSize() = RootAccess.su("wm size reset")

    fun setDensity(density: Int) = RootAccess.su("wm density $density")

    fun resetDensity() = RootAccess.su("wm density reset")

    fun pcMode(): Int? {
        val secure = RootAccess.su("settings get secure pc_mode_enable")
        if (secure.ok) {
            val v = secure.out.trim()
            v.toIntOrNull()?.let { return it }
        }
        val r = RootAccess.su("settings get global global_pc_mode_settings")
        if (!r.ok) return null
        val v = r.out.trim()
        if (v.isEmpty() || v == "null") return 0
        return v.toIntOrNull()
    }

    fun armHotswap(extra: String, restoreHome: String = ""): ShellResult {
        val script = buildHotswapScript(extra, restoreHome)
        val b64 = Base64.encodeToString(script.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val write = RootAccess.su("echo " + b64 + " | base64 -d > /data/local/tmp/tnt_hotswap.sh && chmod 755 /data/local/tmp/tnt_hotswap.sh")
        if (!write.ok) return ShellResult(write.code, write.out, write.err.ifBlank { "写热切换脚本失败" })
        val fire = RootAccess.su("nohup setsid sh /data/local/tmp/tnt_hotswap.sh </dev/null >/data/local/tmp/tnt_hotswap.log 2>&1 & echo started")
        return if (fire.ok || fire.out.contains("started")) ShellResult(0, fire.out, "")
        else ShellResult(fire.code, fire.out, fire.err.ifBlank { "热切换脚本没有启动" })
    }

    private fun buildHotswapScript(extra: String, restoreHome: String): String {
        val extraLines = extra.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val extraBlock = extraLines.joinToString("\n")
        val restore = restoreHome.trim().filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '/' || ch == '_' || ch.code == 36 }
        val raw = """
#!/system/bin/sh
LOG=/data/local/tmp/tnt_hotswap.log
LOCK=/data/local/tmp/tnt_hotswap.lck
RESTORE='#RESTORE#'
echo start %(date) > "%LOG"
if ! mkdir "%LOCK" 2>/dev/null; then
  echo busy >> "%LOG"
  exit 0
fi
set_mode() {
  m="%1"
  cmd smt_pcm smtSetDesktopMode "%m" >>"%LOG" 2>&1
  cmd smtpc smtSetDesktopMode "%m" >>"%LOG" 2>&1
  settings put secure pc_mode_enable "%m"
  settings put global global_pc_mode_settings "%m"
}
wait_mode() {
  want="%1"
  i=0
  while [ "%i" -lt 25 ]; do
    v=%(settings get secure pc_mode_enable)
    if [ "%v" = "%want" ]; then
      echo wait %want ok >>"%LOG"
      return 0
    fi
    i=%((i+1))
    sleep 0.4
  done
  echo wait %want fail v=%v >>"%LOG"
  return 1
}
find_disp() {
  dumpsys display 2>/dev/null | tr '\r' '\n' | awk '
    BEGIN { id=0 }
    /DisplayDeviceInfo/ {
      name=%0
      tnt=0
      if (tolower(name) ~ /tnt|smt|virtual|pc/) tnt=1
    }
    /mDisplayId=/ {
      split(%0, a, "mDisplayId=")
      split(a[2], b, /[^0-9]/)
      d=b[1]+0
      if (d>0) {
        if (tnt==1) { print d; exit }
        if (d>id) id=d
      }
    }
    END { if (id>0) print id }
  '
}
set_mode 0
wait_mode 0
sleep 1.5
set_mode 1
wait_mode 1
sleep 1.2
#EXTRA#
d=%(find_disp)
echo disp=%d >>"%LOG"
if [ -n "%d" ] && [ "%d" != "0" ]; then
  am start --display "%d" -n com.smartisanos.desktop/.Desktop >>"%LOG" 2>&1
  am start --display "%d" -a android.intent.action.MAIN -c android.intent.category.HOME -n com.smartisanos.desktop/.Desktop >>"%LOG" 2>&1
else
  am start -n com.smartisanos.desktop/.Desktop >>"%LOG" 2>&1
fi
sleep 0.8
am force-stop com.smartisanos.virtualremoter >>"%LOG" 2>&1
sleep 0.6
am start --display 0 -n com.smartisanos.virtualremoter/com.smartisanos.virtualremoter.ui.activities.ControlActivity >>"%LOG" 2>&1
am start -n com.smartisanos.virtualremoter/com.smartisanos.virtualremoter.ui.activities.ControlActivity >>"%LOG" 2>&1
if [ -n "%RESTORE" ]; then
  cmd package set-home-activity --user 0 "%RESTORE" >>"%LOG" 2>&1
  cmd package set-home-activity "%RESTORE" >>"%LOG" 2>&1
fi
echo done %(date) >>"%LOG"
rmdir "%LOCK"
""".trimIndent()
        return raw.replace("#EXTRA#", extraBlock).replace("#RESTORE#", restore).replace("%", "\u0024")
    }

    fun reloadDesktop(extra: String): ShellResult {
        return armHotswap(extra)
    }

    fun setDesktopMode(mode: Int): Boolean {
        val reflect = invokeSmtPc("smtSetDesktopMode", mode) ||
            (mode == 0 && invokeSmtPc("showFooDisplay", null)) ||
            invokeSmtPcUtils(mode)
        val root = setDesktopModeRoot(mode)
        RootAccess.su("settings put secure pc_mode_enable $mode")
        RootAccess.su("settings put global global_pc_mode_settings $mode")
        return reflect || root
    }

    fun openTouchpad(): ShellResult {
        val c = "com.smartisanos.virtualremoter/com.smartisanos.virtualremoter.ui.activities.ControlActivity"
        val a = RootAccess.su("am start --display 0 -n $c")
        if (a.ok) return a
        return RootAccess.su("am start -n $c")
    }

    private fun setDesktopModeRoot(mode: Int): Boolean {
        val cmds = listOf(
            "cmd smt_pcm smtSetDesktopMode $mode",
            "cmd smtpc smtSetDesktopMode $mode",
        )
        var any = false
        for (c in cmds) {
            val r = RootAccess.su(c, timeoutSec = 3)
            val text = (r.out + "\n" + r.err).lowercase()
            if (r.ok && !text.contains("unknown") && !text.contains("not found") && !text.contains("no such")) {
                any = true
            }
        }
        return any
    }

    private fun invokeSmtPc(methodName: String, mode: Int?): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "smt_pcm") as? IBinder
                ?: return false
            val stub = Class.forName("android.pc.ISmtPCManager\$Stub")
            val mgr = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return false
            val methods = mgr.javaClass.methods.filter { it.name == methodName }
            for (m in methods) {
                try {
                    m.isAccessible = true
                    val args = Array(m.parameterTypes.size) { i ->
                        val t = m.parameterTypes[i]
                        when {
                            t == Int::class.javaPrimitiveType || t == Integer::class.java -> mode ?: 0
                            t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java -> mode == 1
                            else -> null
                        }
                    }
                    m.invoke(mgr, *args)
                    return true
                } catch (_: Throwable) {
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeSmtPcUtils(mode: Int): Boolean {
        val names = listOf("android.app.SmtPCUtils", "android.app.SmtPCUtilsInner")
        for (name in names) {
            try {
                val clazz = Class.forName(name)
                for (m in clazz.methods.filter { it.name == "smtSetDesktopMode" }) {
                    try {
                        m.isAccessible = true
                        val args = Array(m.parameterTypes.size) { i ->
                            val t = m.parameterTypes[i]
                            when {
                                t == Int::class.javaPrimitiveType || t == Integer::class.java -> mode
                                t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java -> mode == 1
                                else -> null
                            }
                        }
                        if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                            m.invoke(null, *args)
                        } else {
                            val inst = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                            m.invoke(inst, *args)
                        }
                        return true
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun lsposedPresent(): Boolean {
        val r = RootAccess.su("sh -c 'if [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/riru_lsposed ] || [ -d /data/adb/lspd ]; then echo yes; fi'")
        return r.ok && r.out.contains("yes")
    }

    fun applyOverlay(info: DisplayInfo? = null): ShellResult {
        val d = info ?: info()
        val w = if (d.widthPx > 0) d.widthPx else 1920
        val h = if (d.heightPx > 0) d.heightPx else 1080
        val dpi = if (d.density > 0) d.density else 240
        val spec = w.toString() + "x" + h.toString() + "/" + dpi.toString()
        RootAccess.su("setprop persist.easycast.show_overlay_display 1")
        RootAccess.su("resetprop persist.easycast.show_overlay_display 1")
        RootAccess.su("resetprop -n persist.easycast.show_overlay_display 1")
        RootAccess.su("printf '%s' '" + spec + "' > /data/system/tnt_overlay_on && chmod 644 /data/system/tnt_overlay_on")
        return RootAccess.su("settings put global overlay_display_devices " + spec)
    }

    fun clearOverlay(): ShellResult {
        RootAccess.su("setprop persist.easycast.show_overlay_display 0")
        RootAccess.su("resetprop persist.easycast.show_overlay_display 0")
        RootAccess.su("resetprop -n persist.easycast.show_overlay_display 0")
        RootAccess.su("rm -f /data/system/tnt_overlay_on")
        RootAccess.su("settings put global overlay_display_devices ''")
        return RootAccess.su("settings delete global overlay_display_devices")
    }
}
