package id.tntwindow.editor.voice

class VoiceAsrPack(
    val id: String,
    val dirName: String,
    val title: String,
    val url: String,
    val archiveName: String,
    val files: List<String>,
)

object VoiceAsrPacks {
    const val ZIPFORMER = "sherpa:zipformer"
    const val SENSEVOICE = "sherpa:sensevoice"

    val zipformer = VoiceAsrPack(
        id = ZIPFORMER,
        dirName = "zipformer",
        title = "Zipformer 快档",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2",
        archiveName = "zipformer.tar.bz2",
        files = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
    )

    val senseVoice = VoiceAsrPack(
        id = SENSEVOICE,
        dirName = "sensevoice",
        title = "SenseVoice 口音档",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        archiveName = "sensevoice.tar.bz2",
        files = listOf(
            "model.int8.onnx",
            "tokens.txt",
        ),
    )

    val all = listOf(zipformer, senseVoice)

    fun byId(id: String): VoiceAsrPack? {
        val t = id.trim()
        return all.firstOrNull { it.id == t }
    }
}