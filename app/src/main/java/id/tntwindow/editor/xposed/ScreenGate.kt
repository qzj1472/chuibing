package id.tntwindow.editor.xposed

import android.app.KeyguardManager
import android.content.Context
import de.robv.android.xposed.XposedHelpers

object ScreenGate {
    fun locked(ctx: Context?, pwm: Any? = null): Boolean {
        val host = pwm ?: FreeformRuntime.policy
        if (host != null) {
            for (name in listOf("isKeyguardShowing", "isKeyguardLocked", "isKeyguardShowingAndNotOccluded")) {
                try {
                    val v = XposedHelpers.callMethod(host, name)
                    if (v is Boolean && v) return true
                } catch (_: Throwable) {
                }
            }
        }
        if (ctx == null) return false
        return try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.isKeyguardLocked || km.inKeyguardRestrictedInputMode()
        } catch (_: Throwable) {
            false
        }
    }
}