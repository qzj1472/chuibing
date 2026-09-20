package id.tntwindow.editor.data

import android.content.Intent
import android.content.pm.PackageManager
import id.tntwindow.editor.domain.VoiceEngineInfo
import java.util.LinkedHashMap

object VoiceEngines {
    fun recognizers(pm: PackageManager): List<VoiceEngineInfo> {
        return services(pm, Intent("android.speech.RecognitionService"))
    }

    fun tts(pm: PackageManager): List<VoiceEngineInfo> {
        return services(pm, Intent("android.intent.action.TTS_SERVICE"))
    }

    private fun services(pm: PackageManager, intent: Intent): List<VoiceEngineInfo> {
        val found = try {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }
        val map = LinkedHashMap<String, VoiceEngineInfo>()
        for (ri in found) {
            val si = ri.serviceInfo ?: continue
            val pkg = si.packageName ?: continue
            val name = si.name ?: continue
            val id = pkg + "/" + name
            val raw = try {
                ri.loadLabel(pm).toString().trim()
            } catch (_: Exception) {
                pkg
            }
            map[id] = VoiceEngineInfo(id, pretty(pkg, raw.ifBlank { pkg }))
        }
        return map.values.toList().sortedBy { it.label }
    }

    private fun pretty(pkg: String, fallback: String): String {
        val p = pkg.lowercase()
        return when {
            p.contains("googlequicksearchbox") -> "谷歌语音"
            p.contains("google.android.tts") -> "谷歌文字转语音"
            p.contains("speechservices") || p.contains("google") -> "谷歌语音"
            p.contains("iflytek") || p.contains("ifly") -> "讯飞"
            p.contains("sogou") -> "搜狗"
            p.contains("baidu") -> "百度"
            p.contains("sherpa") -> "Sherpa 离线"
            p.contains("pico") -> "Pico 离线"
            p.contains("smartisan") || p.contains("revone") -> "锤子自带"
            else -> fallback
        }
    }
}
