package id.tntwindow.editor

import android.content.Context
import android.os.Binder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import java.io.File

object Feel {
    fun vibrate(context: Context, ms: Int, amp: Int, view: View? = null, pwm: Any? = null) {
        val duration = ms.coerceIn(10, 200).toLong()
        val strength = amp.coerceIn(1, 255)
        if (pwm != null) {
            val codes = intArrayOf(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.CLOCK_TICK,
            )
            for (code in codes) {
                try {
                    val m = pwm.javaClass.methods.firstOrNull { it.name == "performHapticFeedbackLw" && it.parameterTypes.size == 3 }
                    if (m != null) {
                        val r = m.invoke(pwm, null, code, true)
                        if (r == null || r == true) return
                    }
                } catch (_: Throwable) {
                }
            }
        }
        val token = Binder.clearCallingIdentity()
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            try {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                return
            } catch (_: Throwable) {
            }
            try {
                if (v.hasAmplitudeControl()) {
                    v.vibrate(VibrationEffect.createOneShot(duration, strength))
                    return
                }
            } catch (_: Throwable) {
            }
            try {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
                return
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        if (view != null) {
            try {
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                if (view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, flags)) return
            } catch (_: Throwable) {
            }
        }
        try {
            File("/sys/class/timed_output/vibrator/enable").writeText(duration.toString())
        } catch (_: Throwable) {
        }
    }
}
