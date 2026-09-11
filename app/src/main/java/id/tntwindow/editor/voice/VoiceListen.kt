package id.tntwindow.editor.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceListen(context: Context) {
    private val app = context.applicationContext
    private var rec: SpeechRecognizer? = null

    fun start(
        recognizerId: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
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
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onError(errText(error))
            }
            override fun onResults(results: Bundle?) {
                val text = first(results)
                if (text.isBlank()) onError("没听清") else onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = first(partialResults)
                if (text.isNotBlank()) onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            onError("开始听写失败：" + (t.message ?: t.javaClass.simpleName))
            stop()
        }
    }

    fun stop() {
        val cur = rec
        rec = null
        if (cur == null) return
        try { cur.cancel() } catch (_: Throwable) {}
        try { cur.destroy() } catch (_: Throwable) {}
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