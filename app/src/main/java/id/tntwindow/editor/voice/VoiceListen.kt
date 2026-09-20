package id.tntwindow.editor.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceListen(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var sherpa: SherpaListen? = null
    private var lastPartial = ""
    private var hold = false
    private var settled = false
    private var gen = 0
    private var endWait: Runnable? = null
    private var totalWait: Runnable? = null
    private var onFinalFn: ((String) -> Unit)? = null
    private var onErrorFn: ((String) -> Unit)? = null

    fun start(
        recognizerId: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        holdOpen: Boolean = false,
    ) {
        stop()
        gen += 1
        val my = gen
        lastPartial = ""
        hold = holdOpen
        settled = false
        onFinalFn = onFinal
        onErrorFn = onError
        if (recognizerId.trim().startsWith("sherpa:")) {
            val s = SherpaListen(app)
            sherpa = s
            s.start(
                recognizerId,
                { text ->
                    if (my != gen || settled) return@start
                    if (text.isNotBlank() && text != "正在听…") lastPartial = text
                    onPartial(text)
                },
                { text ->
                    if (my != gen) return@start
                    settleFinal(text)
                },
                { err ->
                    if (my != gen) return@start
                    settleError(err)
                },
                holdOpen,
            )
            armTotal(my)
            return
        }
        val sr = try {
            build(recognizerId)
        } catch (t: Throwable) {
            onError("听写引擎打不开：" + (t.message ?: t.javaClass.simpleName))
            return
        }
        if (sr == null) {
            onError("听写引擎打不开")
            return
        }
        rec = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (my != gen || settled || hold) return
                try { rec?.stopListening() } catch (_: Throwable) {}
                armEnd(my)
            }
            override fun onError(error: Int) {
                if (my != gen || settled) return
                if (lastPartial.isNotBlank() && lastPartial != "正在听…") {
                    settleFinal(lastPartial)
                    return
                }
                if (endWait != null && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    return
                }
                settleError(errText(error))
            }
            override fun onResults(results: Bundle?) {
                if (my != gen || settled) return
                val text = first(results)
                if (text.isBlank()) settleError("没听清") else settleFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (my != gen || settled) return
                val text = first(partialResults)
                if (text.isNotBlank()) {
                    lastPartial = text
                    onPartial(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        if (holdOpen) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60000)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
        }
        try {
            sr.startListening(intent)
            armTotal(my)
        } catch (t: Throwable) {
            onError("开始听写失败：" + (t.message ?: t.javaClass.simpleName))
            stop()
        }
    }

    fun finish() {
        val my = gen
        try { sherpa?.finish() } catch (_: Throwable) {}
        val cur = rec
        if (cur != null) {
            try { cur.stopListening() } catch (_: Throwable) {}
        }
        if (!hold && !settled) armEnd(my)
    }

    fun stop() {
        gen += 1
        settled = true
        clearWaits()
        onFinalFn = null
        onErrorFn = null
        lastPartial = ""
        val s = sherpa
        sherpa = null
        try { s?.stop() } catch (_: Throwable) {}
        val cur = rec
        rec = null
        if (cur == null) return
        try { cur.cancel() } catch (_: Throwable) {}
        try { cur.destroy() } catch (_: Throwable) {}
    }

    private fun armTotal(my: Int) {
        if (hold) return
        val wait = Runnable {
            if (my != gen || settled) return@Runnable
            try { sherpa?.finish() } catch (_: Throwable) {}
            try { rec?.stopListening() } catch (_: Throwable) {}
            armEnd(my)
        }
        totalWait?.let { main.removeCallbacks(it) }
        totalWait = wait
        main.postDelayed(wait, 12000)
    }

    private fun armEnd(my: Int) {
        if (hold || settled || my != gen) return
        val wait = Runnable {
            if (my != gen || settled) return@Runnable
            if (lastPartial.isNotBlank() && lastPartial != "正在听…") settleFinal(lastPartial)
            else settleError("没听清")
        }
        endWait?.let { main.removeCallbacks(it) }
        endWait = wait
        main.postDelayed(wait, 2000)
    }

    private fun clearWaits() {
        endWait?.let { main.removeCallbacks(it) }
        totalWait?.let { main.removeCallbacks(it) }
        endWait = null
        totalWait = null
    }

    private fun settleFinal(text: String) {
        if (settled) return
        val t = text.trim()
        if (t.isEmpty() || t == "正在听…") {
            settleError("没听清")
            return
        }
        settled = true
        clearWaits()
        onFinalFn?.invoke(t)
    }

    private fun settleError(msg: String) {
        if (settled) return
        if (lastPartial.isNotBlank() && lastPartial != "正在听…") {
            settleFinal(lastPartial)
            return
        }
        settled = true
        clearWaits()
        onErrorFn?.invoke(msg.ifBlank { "没听清" })
    }

    private fun build(recognizerId: String): SpeechRecognizer? {
        val id = recognizerId.trim()
        return if (id.isEmpty()) {
            SpeechRecognizer.createSpeechRecognizer(app)
        } else {
            val cn = ComponentName.unflattenFromString(id) ?: return null
            SpeechRecognizer.createSpeechRecognizer(app, cn)
        }
    }

    private fun first(bundle: Bundle?): String {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun errText(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "麦克风读不到声音"
            SpeechRecognizer.ERROR_CLIENT -> "听写引擎启动失败"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "引擎要联网，网络失败"
            SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "听写引擎正忙"
            SpeechRecognizer.ERROR_SERVER -> "听写服务失败"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到说话"
            else -> "听写失败 $error"
        }
    }
}