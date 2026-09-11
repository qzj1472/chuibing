package id.tntwindow.editor.domain

data class WebViewPackage(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val current: Boolean,
    val valid: Boolean,
    val preferred: Boolean,
    val candidate: Boolean,
    val abis: List<String> = emptyList(),
    val abiOk: Boolean = true,
)

data class WebViewSnapshot(
    val currentPackage: String = "",
    val currentVersion: String = "",
    val preferredPackage: String = "",
    val fallbackEnabled: Boolean = false,
    val multiprocess: Boolean = false,
    val providers: List<WebViewPackage> = emptyList(),
    val anyWebViewInstalled: Boolean = false,
    val dump: String = "",
    val frameworkScoped: Boolean = false,
)

data class WebViewConfig(
    val inject: Boolean = true,
    val extra: List<String> = emptyList(),
    val pending: String = "",
) {
    fun toJson(): String {
        val o = org.json.JSONObject()
        o.put("inject", inject)
        val arr = org.json.JSONArray()
        for (p in extra) arr.put(p)
        o.put("extra", arr)
        o.put("pending", pending)
        return o.toString(2)
    }

    companion object {
        fun default(): WebViewConfig = WebViewConfig()

        fun fromJson(text: String): WebViewConfig {
            val o = org.json.JSONObject(text)
            val arr = o.optJSONArray("extra") ?: org.json.JSONArray()
            val extra = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p.isNotEmpty()) extra += p
            }
            return WebViewConfig(
                inject = o.optBoolean("inject", true),
                extra = extra,
                pending = o.optString("pending", ""),
            )
        }
    }
}