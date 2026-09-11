package id.tntwindow.editor.domain

import org.json.JSONArray
import org.json.JSONObject

data class VoiceCommand(
    val id: String,
    val phrase: String,
    val action: String,
    val target: String,
    val enabled: Boolean = true,
)

data class VoiceAlias(
    val id: String,
    val spoken: String,
    val packageName: String,
    val label: String,
)

data class VoiceEngineInfo(
    val id: String,
    val label: String,
)

data class VoiceConfig(
    val engine: String,
    val commands: List<VoiceCommand>,
    val speak: Boolean = true,
    val recognizer: String = "",
    val ttsEngine: String = "",
    val aliases: List<VoiceAlias> = emptyList(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("engine", engine)
        o.put("speak", speak)
        o.put("recognizer", recognizer)
        o.put("ttsEngine", ttsEngine)
        val arr = JSONArray()
        for (c in commands) {
            val x = JSONObject()
            x.put("id", c.id)
            x.put("phrase", c.phrase)
            x.put("action", c.action)
            x.put("target", c.target)
            x.put("on", c.enabled)
            arr.put(x)
        }
        o.put("commands", arr)
        val aliasesArr = JSONArray()
        for (a in aliases) {
            val x = JSONObject()
            x.put("id", a.id)
            x.put("spoken", a.spoken)
            x.put("pkg", a.packageName)
            x.put("label", a.label)
            aliasesArr.put(x)
        }
        o.put("aliases", aliasesArr)
        return o.toString(2)
    }

    companion object {
        const val ENGINE_EXTEND = "extend"
        const val ENGINE_REPLACE = "replace"

        fun default(): VoiceConfig {
            return VoiceConfig(
                engine = ENGINE_REPLACE,
                commands = emptyList(),
                speak = true,
            )
        }

        fun fromJson(text: String): VoiceConfig {
            val o = JSONObject(text)
            val engine = o.optString("engine", ENGINE_REPLACE).ifBlank { ENGINE_REPLACE }
            val arr = o.optJSONArray("commands") ?: JSONArray()
            val list = ArrayList<VoiceCommand>()
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                val phrase = x.optString("phrase").trim()
                val target = x.optString("target").trim()
                if (phrase.isEmpty() || target.isEmpty()) continue
                list += VoiceCommand(
                    id = x.optString("id").ifBlank { System.nanoTime().toString() },
                    phrase = phrase,
                    action = x.optString("action", "launch").ifBlank { "launch" },
                    target = target,
                    enabled = x.optBoolean("on", true),
                )
            }
            val aliasArr = o.optJSONArray("aliases") ?: JSONArray()
            val aliasList = ArrayList<VoiceAlias>()
            for (i in 0 until aliasArr.length()) {
                val x = aliasArr.optJSONObject(i) ?: continue
                val spoken = x.optString("spoken").trim()
                val pkg = x.optString("pkg").trim()
                if (spoken.isEmpty() || pkg.isEmpty()) continue
                aliasList += VoiceAlias(
                    id = x.optString("id").ifBlank { System.nanoTime().toString() },
                    spoken = spoken,
                    packageName = pkg,
                    label = x.optString("label").ifBlank { spoken },
                )
            }
            return VoiceConfig(
                engine = engine,
                commands = list,
                speak = o.optBoolean("speak", true),
                recognizer = o.optString("recognizer", ""),
                ttsEngine = o.optString("ttsEngine", ""),
                aliases = aliasList,
            )
        }
    }
}