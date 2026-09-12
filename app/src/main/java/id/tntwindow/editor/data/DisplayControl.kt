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
        val fire = RootAccess.su("nohup setsid sh /data/local/tmp/tnt_hotswap.sh </dev/null >>/data/local/tmp/tnt_hotswap.log 2>&1 & echo started")
        return if (fire.ok || fire.out.contains("started")) ShellResult(0, fire.out, "")
        else ShellResult(fire.code, fire.out, fire.err.ifBlank { "热切换脚本没有启动" })
    }

    private fun buildHotswapScript(extra: String, restoreHome: String): String {
        val extraLines = extra.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val extraBlock = extraLines.joinToString("\n")
        val raw = """
#!/system/bin/sh
LOCK=/data/local/tmp/tnt_hotswap.lck
PIDF=/data/local/tmp/tnt_hotswap.lck/pid
echo ==== @D@(date) pid=@D@@D@ ====
if ! mkdir "@D@LOCK" 2>/dev/null; then
  old=
  if [ -f "@D@PIDF" ]; then
    old=@D@(cat "@D@PIDF" 2>/dev/null)
  fi
  if [ -n "@D@old" ] && kill -0 "@D@old" 2>/dev/null; then
    echo busy pid=@D@old
    exit 0
  fi
  echo steal lock old=@D@old
  rm -rf "@D@LOCK"
  if ! mkdir "@D@LOCK" 2>/dev/null; then
    echo busy
    exit 0
  fi
fi
echo @D@@D@ > "@D@PIDF"
trap 'rm -rf /data/local/tmp/tnt_hotswap.lck' EXIT INT TERM HUP
set_mode() {
  m="@D@1"
  cmd smt_pcm smtSetDesktopMode "@D@m" 2>&1
  cmd smtpc smtSetDesktopMode "@D@m" 2>&1
  settings put secure pc_mode_enable "@D@m"
  settings put global global_pc_mode_settings "@D@m"
}
wait_mode() {
  want="@D@1"
  i=0
  while [ "@D@i" -lt 25 ]; do
    v=@D@(settings get secure pc_mode_enable)
    if [ "@D@v" = "@D@want" ]; then
      echo wait @D@want ok
      return 0
    fi
    i=@D@((i+1))
    sleep 0.4
  done
  echo wait @D@want fail v=@D@v
  return 1
}
find_disp() {
  dumpsys display 2>/dev/null | tr '\r' '\n' | /sbin/busybox awk '
    BEGIN { id=0 }
    /DisplayDeviceInfo/ {
      name=@D@0
      tnt=0
      if (tolower(name) ~ /tnt|smt|virtual|pc|hdmi|external/) tnt=1
    }
    /mDisplayId=/ {
      split(@D@0, a, "mDisplayId=")
      split(a[2], b, /[^0-9]/)
      d=b[1]+0
      if (d>0) {
        if (tnt==1) { print d; exit }
        if (d>id) id=d
      }
    }
    END { if (id>0) print id }
  ' 2>/dev/null
}
set_mode 0
wait_mode 0
sleep 1.5
set_mode 1
wait_mode 1
sleep 1.2
#EXTRA#
d=""
n=0
while [ "@D@n" -lt 15 ]; do
  d=@D@(find_disp)
  if [ -n "@D@d" ] && [ "@D@d" != "0" ]; then
    break
  fi
  n=@D@((n+1))
  sleep 0.4
done
echo disp=@D@d
if [ -n "@D@d" ] && [ "@D@d" != "0" ]; then
  am start --display "@D@d" -n com.smartisanos.desktop/.Desktop 2>&1
else
  echo skip phone desktop
fi
sleep 0.8
am force-stop com.smartisanos.virtualremoter 2>&1
sleep 0.6
am start --display 0 -n com.smartisanos.virtualremoter/com.smartisanos.virtualremoter.ui.activities.ControlActivity 2>&1
echo done @D@(date)
""".trimIndent()
        return raw.replace("#EXTRA#", extraBlock).replace("@D@", "\u0024")
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
        val cmds = listOf(
            "test -d /data/adb/lspd && echo yes",
            "test -f /data/adb/lspd/config/modules_config.db && echo yes",
            "test -d /data/adb/modules/zygisk_lsposed && echo yes",
            "test -d /data/adb/modules/riru_lsposed && echo yes",
            "ls /data/adb/modules 2>/dev/null",
        )
        for (c in cmds) {
            val r = RootAccess.su(c)
            val t = r.out.lowercase()
            if (t.contains("yes") || t.contains("lsposed")) return true
        }
        return false
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
