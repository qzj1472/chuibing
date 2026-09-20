package id.tntwindow.editor.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object SherpaEngine {
    private val lock = Any()
    private var zipId = ""
    private var zip: OnlineRecognizer? = null
    private var senseId = ""
    private var sense: OfflineRecognizer? = null

    fun preload(app: Context, recognizerId: String) {
        val pack = VoiceAsrPacks.byId(recognizerId) ?: return
        if (!VoiceModelDownload.isReady(app, pack)) return
        Thread {
            try {
                if (pack.id == VoiceAsrPacks.ZIPFORMER) online(app, pack) else offline(app, pack)
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun peekOnline(pack: VoiceAsrPack): OnlineRecognizer? {
        synchronized(lock) {
            val cur = zip
            return if (cur != null && zipId == pack.id) cur else null
        }
    }

    fun peekOffline(pack: VoiceAsrPack): OfflineRecognizer? {
        synchronized(lock) {
            val cur = sense
            return if (cur != null && senseId == pack.id) cur else null
        }
    }

    fun online(app: Context, pack: VoiceAsrPack): OnlineRecognizer {
        synchronized(lock) {
            val cur = zip
            if (cur != null && zipId == pack.id) return cur
            releaseZipLocked()
            val dir = VoiceModelDownload.modelDir(app, pack)
            val rec = OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                            joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer",
                    ),
                    enableEndpoint = true,
                ),
            )
            zip = rec
            zipId = pack.id
            return rec
        }
    }

    fun offline(app: Context, pack: VoiceAsrPack): OfflineRecognizer {
        synchronized(lock) {
            val cur = sense
            if (cur != null && senseId == pack.id) return cur
            releaseSenseLocked()
            val dir = VoiceModelDownload.modelDir(app, pack)
            val rec = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(dir, "model.int8.onnx").absolutePath,
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                    ),
                ),
            )
            sense = rec
            senseId = pack.id
            return rec
        }
    }

    private fun releaseZipLocked() {
        val cur = zip
        zip = null
        zipId = ""
        if (cur != null) try { cur.release() } catch (_: Throwable) {}
    }

    private fun releaseSenseLocked() {
        val cur = sense
        sense = null
        senseId = ""
        if (cur != null) try { cur.release() } catch (_: Throwable) {}
    }
}

class SherpaListen(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gen = AtomicInteger(0)
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var finishReq = false

    fun start(
        recognizerId: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        holdOpen: Boolean = false,
    ) {
        stop()
        val my = gen.incrementAndGet()
        val pack = VoiceAsrPacks.byId(recognizerId)
        if (pack == null) {
            onError("没有这个离线引擎")
            return
        }
        if (!VoiceModelDownload.isReady(app, pack)) {
            onError(pack.title + " 还没下好")
            return
        }
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                if (pack.id == VoiceAsrPacks.ZIPFORMER) {
                    runZipformer(my, pack, onPartial, onFinal, onError, holdOpen)
                } else {
                    runSenseVoice(my, pack, onPartial, onFinal, onError, holdOpen)
                }
            } catch (t: Throwable) {
                if (my != gen.get()) return@Thread
                val msg = t.message ?: t.javaClass.simpleName
                emit(my, onError, "离线引擎失败：" + msg)
            } finally {
                releaseRec()
            }
        }.start()
    }

    fun finish() {
        finishReq = true
    }

    fun stop() {
        finishReq = false
        gen.incrementAndGet()
        releaseRec()
    }

    private fun runZipformer(
        my: Int,
        pack: VoiceAsrPack,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        holdOpen: Boolean,
    ) {
        val recorder = openRec()
        rec = recorder
        recorder.startRecording()
        emit(my, onPartial, "正在听…")
        val recognizer = AtomicReference<OnlineRecognizer?>(SherpaEngine.peekOnline(pack))
        val loadErr = AtomicReference<String?>(null)
        if (recognizer.get() == null) {
            Thread {
                try {
                    recognizer.set(SherpaEngine.online(app, pack))
                } catch (t: Throwable) {
                    loadErr.set(t.message ?: t.javaClass.simpleName)
                }
            }.start()
        }
        val streamHold = arrayOfNulls<com.k2fsa.sherpa.onnx.OnlineStream>(1)
        val pending = ArrayList<FloatArray>()
        var queued = 0
        val buf = ShortArray(1600)
        var last = ""
        var idle = 0
        try {
            while (my == gen.get()) {
                val err = loadErr.get()
                if (err != null) {
                    emit(my, onError, "离线引擎失败：" + err)
                    return
                }
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0f
                    samples[i] = v
                    sum += (v * v).toDouble()
                }
                var stream = streamHold[0]
                var recg = recognizer.get()
                if (stream == null) {
                    if (recg == null) {
                        pending.add(samples)
                        queued += n
                        if (queued > 16000 * 12) {
                            emit(my, onError, "引擎还没就绪")
                            return
                        }
                        continue
                    }
                    stream = recg.createStream()
                    streamHold[0] = stream
                    for (p in pending) stream.acceptWaveform(p, 16000)
                    pending.clear()
                    queued = 0
                }
                stream.acceptWaveform(samples, 16000)
                while (recg != null && recg.isReady(stream)) recg.decode(stream)
                val text = recg?.getResult(stream)?.text?.trim().orEmpty()
                if (text.isNotBlank() && text != last) {
                    last = text
                    emit(my, onPartial, text)
                }
                val energy = kotlin.math.sqrt(sum / n.toDouble())
                if (energy < 0.01 && pending.isEmpty() && !finishReq) {
                    idle += 100
                } else if (energy >= 0.01) {
                    idle = 0
                }
                if (!holdOpen && last.isNotBlank() && idle >= 800 && recg != null) {
                    try { stream.inputFinished() } catch (_: Throwable) {}
                    while (recg.isReady(stream)) recg.decode(stream)
                    val done = recg.getResult(stream).text.trim().ifBlank { last }
                    if (done.isBlank()) emit(my, onError, "没听清") else emit(my, onFinal, done)
                    return
                }
                if (!holdOpen && last.isBlank() && idle >= 8000) {
                    emit(my, onError, "没有听到说话")
                    return
                }
                if (!holdOpen && recg != null && recg.isEndpoint(stream)) {
                    val done = recg.getResult(stream).text.trim().ifBlank { last }
                    if (done.isBlank()) emit(my, onError, "没听清") else emit(my, onFinal, done)
                    return
                }
                if (finishReq) {
                    val cur = streamHold[0]
                    val ready = recognizer.get()
                    if (cur != null && ready != null) {
                        try { cur.inputFinished() } catch (_: Throwable) {}
                        while (ready.isReady(cur)) ready.decode(cur)
                        val done = ready.getResult(cur).text.trim().ifBlank { last }
                        if (done.isBlank()) emit(my, onError, "没听清") else emit(my, onFinal, done)
                        return
                    }
                    if (last.isNotBlank()) {
                        emit(my, onFinal, last)
                        return
                    }
                    if (ready == null && loadErr.get() == null) continue
                    emit(my, onError, "没听清")
                    return
                }
            }
        } finally {
            try { streamHold[0]?.release() } catch (_: Throwable) {}
        }
    }

    private fun runSenseVoice(
        my: Int,
        pack: VoiceAsrPack,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        holdOpen: Boolean,
    ) {
        val recorder = openRec()
        rec = recorder
        recorder.startRecording()
        emit(my, onPartial, "正在听…")
        val recognizer = AtomicReference<OfflineRecognizer?>(SherpaEngine.peekOffline(pack))
        val loadErr = AtomicReference<String?>(null)
        if (recognizer.get() == null) {
            Thread {
                try {
                    recognizer.set(SherpaEngine.offline(app, pack))
                } catch (t: Throwable) {
                    loadErr.set(t.message ?: t.javaClass.simpleName)
                }
            }.start()
        }
        val buf = ShortArray(1600)
        val collected = ArrayList<Float>(16000 * 8)
        var speaking = false
        var silence = 0
        var idle = 0
        while (my == gen.get()) {
            if (finishReq) break
            val err = loadErr.get()
            if (err != null) {
                emit(my, onError, "离线引擎失败：" + err)
                return
            }
            val n = recorder.read(buf, 0, buf.size)
            if (n <= 0) continue
            var sum = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768.0f
                collected.add(v)
                sum += (v * v).toDouble()
            }
            val energy = kotlin.math.sqrt(sum / n.toDouble())
            if (energy >= 0.015) {
                speaking = true
                silence = 0
                idle = 0
            } else if (speaking) {
                silence += 100
                if (!holdOpen && silence >= 1000) break
            } else {
                idle += 100
                if (idle >= 8000) {
                    emit(my, onError, "没有听到说话")
                    return
                }
            }
            if (collected.size >= 16000 * (if (holdOpen) 30 else 12)) break
        }
        if (my != gen.get()) return
        if (collected.size < 1600 || (!speaking && !finishReq)) {
            emit(my, onError, "没听清")
            return
        }
        var recg = recognizer.get()
        val waitUntil = System.currentTimeMillis() + 8000L
        while (recg == null && my == gen.get() && System.currentTimeMillis() < waitUntil) {
            val err = loadErr.get()
            if (err != null) {
                emit(my, onError, "离线引擎失败：" + err)
                return
            }
            try { Thread.sleep(20) } catch (_: Throwable) {}
            recg = recognizer.get()
        }
        if (recg == null) {
            emit(my, onError, "引擎还没就绪")
            return
        }
        val samples = collected.toFloatArray()
        val stream = recg.createStream()
        stream.acceptWaveform(samples, 16000)
        recg.decode(stream)
        val text = recg.getResult(stream).text.trim()
        try { stream.release() } catch (_: Throwable) {}
        if (text.isBlank()) emit(my, onError, "没听清") else emit(my, onFinal, text)
    }

    private fun openRec(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = if (min > 0) min * 2 else 16000
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            throw IllegalStateException("麦克风打不开")
        }
        return rec
    }

    private fun releaseRec() {
        val cur = rec
        rec = null
        if (cur == null) return
        try { cur.stop() } catch (_: Throwable) {}
        try { cur.release() } catch (_: Throwable) {}
    }

    private fun emit(my: Int, fn: (String) -> Unit, text: String) {
        if (my != gen.get()) return
        main.post {
            if (my != gen.get()) return@post
            fn(text)
        }
    }
}