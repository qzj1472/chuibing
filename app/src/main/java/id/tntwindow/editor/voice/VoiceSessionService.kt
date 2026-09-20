package id.tntwindow.editor.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import id.tntwindow.editor.xposed.VoiceHud
import id.tntwindow.editor.xposed.VoiceRuntime
import id.tntwindow.editor.xposed.VoiceSpeak

class VoiceSessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var listen: VoiceListen? = null
    private var seq = 0
    private var fg = false
    @Volatile private var live = false
    @Volatile private var committing = false
    private var lastPartial = ""
    private var sessionMode = MODE_ONCE
    private var commitWait: Runnable? = null
    private var onceWait: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        listen = VoiceListen(this)
        SherpaEngine.preload(this, VoiceRuntime.recognizerId())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        if (mode.isNotBlank() && mode != MODE_STOP) sessionMode = mode
        val stop = intent?.action == ACTION_STOP || mode == MODE_STOP || (mode == MODE_TOGGLE && live)
        if (stop) {
            if (live) {
                startFg()
                commit()
            } else {
                stopFg()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startFg()
        beginListen()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        seq += 1
        live = false
        committing = false
        commitWait?.let { main.removeCallbacks(it) }
        commitWait = null
        onceWait?.let { main.removeCallbacks(it) }
        onceWait = null
        try { listen?.stop() } catch (_: Throwable) {}
        listen = null
        stopFg()
        super.onDestroy()
    }

    private fun beginListen() {
        commitWait?.let { main.removeCallbacks(it) }
        commitWait = null
        onceWait?.let { main.removeCallbacks(it) }
        onceWait = null
        committing = false
        lastPartial = ""
        seq += 1
        live = true
        val my = seq
        val rec = listen ?: VoiceListen(this).also { listen = it }
        val sherpa = VoiceRuntime.recognizerId().trim().startsWith("sherpa:")
        if (!sherpa) {
            VoiceSpeak.cue()
            VoiceHud.listen(this)
        }
        val begin = Runnable {
            if (my != seq || committing) return@Runnable
            rec.stop()
            rec.start(
                VoiceRuntime.recognizerId(),
                { text ->
                    if (my != seq) return@start
                    if (text == "正在听…") VoiceSpeak.cue()
                    else lastPartial = text
                    VoiceHud.partial(this, text)
                },
                { text ->
                    if (my != seq) return@start
                    doneOk(text, my)
                },
                { err ->
                    if (my != seq) return@start
                    if (lastPartial.isNotBlank() && lastPartial != "正在听…") doneOk(lastPartial, my)
                    else doneErr(err, my)
                },
                sessionMode != MODE_ONCE,
            )
        }
        begin.run()
        if (sessionMode == MODE_ONCE) {
            val wait = Runnable {
                if (my != seq || !live || committing) return@Runnable
                commit()
            }
            onceWait = wait
            main.postDelayed(wait, 12000)
        }
    }

    private fun commit() {
        if (!live) {
            stopFg()
            stopSelf()
            return
        }
        if (committing) return
        committing = true
        try { listen?.finish() } catch (_: Throwable) {}
        val my = seq
        val wait = Runnable {
            if (!live || my != seq) return@Runnable
            if (lastPartial.isNotBlank() && lastPartial != "正在听…") doneOk(lastPartial, my) else doneErr("没听清", my)
        }
        commitWait = wait
        main.postDelayed(wait, 5000)
    }

    private fun doneOk(text: String, my: Int) {
        if (my != seq) return
        val t = text.trim()
        if (t.isEmpty() || t == "正在听…") {
            doneErr("没听清", my)
            return
        }
        VoiceHud.result(this, t)
        try {
            val i = Intent(ACTION_COMMIT)
            i.putExtra("text", t)
            sendBroadcast(i)
        } catch (_: Throwable) {
        }
        finish(my)
    }

    private fun doneErr(msg: String, my: Int) {
        if (my != seq) return
        VoiceHud.error(this, msg.ifBlank { "没听清" })
        finish(my)
    }

    private fun finish(my: Int) {
        if (my != seq) return
        live = false
        committing = false
        commitWait?.let { main.removeCallbacks(it) }
        commitWait = null
        onceWait?.let { main.removeCallbacks(it) }
        onceWait = null
        try { listen?.stop() } catch (_: Throwable) {}
        stopFg()
        stopSelf()
    }

    private fun startFg() {
        if (fg) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "语音", NotificationManager.IMPORTANCE_MIN)
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val n = Notification.Builder(this, CHANNEL)
                .setContentTitle("正在听")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
            startForeground(NID, n)
            fg = true
        } catch (_: Throwable) {
            try {
                startForeground(NID, Notification())
                fg = true
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopFg() {
        if (!fg) return
        fg = false
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val ACTION_LISTEN = "id.tntwindow.editor.action.VOICE_LISTEN"
        const val ACTION_STOP = "id.tntwindow.editor.action.VOICE_STOP"
        const val ACTION_COMMIT = "id.tntwindow.editor.action.VOICE_COMMIT"
        const val EXTRA_MODE = "mode"
        const val MODE_ONCE = "once"
        const val MODE_TOGGLE = "toggle"
        const val MODE_HOLD = "hold"
        const val MODE_STOP = "stop"
        private const val CHANNEL = "chuibing-voice"
        private const val NID = 1847
    }
}
