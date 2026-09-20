package id.tntwindow.editor.xposed

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

object VoiceSpeak {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var engine = ""

    fun prepare(context: Context) {
        val app = context.applicationContext ?: context
        main.post { ensure(app) }
    }

    fun say(context: Context, text: String) {
        if (!VoiceRuntime.speakEnabled()) return
        if (text.isBlank()) return
        VoiceRuntime.setLastSpeak(text)
        val app = context.applicationContext ?: context
        main.post { speakOnMain(app, text) }
    }

    private fun speakOnMain(context: Context, text: String) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        synchronized(lock) {
            pending = text
        }
        ensure(context)
        val engineTts: TextToSpeech?
        val isReady: Boolean
        synchronized(lock) {
            engineTts = tts
            isReady = ready
        }
        if (engineTts != null && isReady) {
            flushPending(engineTts)
        }
    }

    private fun ensure(context: Context) {
        synchronized(lock) {
            val want = VoiceRuntime.ttsEngine()
            if (tts != null && engine != want) {
                try { tts?.shutdown() } catch (_: Throwable) {}
                tts = null
                ready = false
            }
            engine = want
            if (tts != null) return
            val listener = TextToSpeech.OnInitListener { status ->
                synchronized(lock) {
                    ready = status == TextToSpeech.SUCCESS
                    val engineTts = tts
                    if (ready && engineTts != null) {
                        try { engineTts.language = Locale.CHINA } catch (_: Throwable) {}
                        main.post { flushPending(engineTts) }
                    } else {
                        tone()
                    }
                }
            }
            tts = try {
                if (want.isBlank()) TextToSpeech(context, listener) else TextToSpeech(context, listener, want)
            } catch (_: Throwable) {
                try {
                    TextToSpeech(context, listener)
                } catch (_: Throwable) {
                    tone()
                    null
                }
            }
        }
    }

    private fun flushPending(engineTts: TextToSpeech) {
        val text: String?
        synchronized(lock) {
            text = pending
            pending = null
        }
        if (text.isNullOrBlank()) return
        if (!speakNow(engineTts, text)) tone()
    }

    private fun speakNow(engineTts: TextToSpeech, text: String): Boolean {
        return try {
            engineTts.setSpeechRate(0.96f)
            engineTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tnt-voice") == TextToSpeech.SUCCESS
        } catch (_: Throwable) {
            false
        }
    }

    fun cue() {
        tone()
    }

    private fun tone() {
        try {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            g.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            main.postDelayed({
                try { g.release() } catch (_: Throwable) {}
            }, 240)
        } catch (_: Throwable) {
        }
    }
}