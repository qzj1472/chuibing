package id.tntwindow.editor.domain

data class CorePatchConfig(
    val downgrade: Boolean = true,
    val digestVerify: Boolean = true,
    val arsc: Boolean = true,
    val signature: Boolean = true,
    val exactSig: Boolean = false,
    val usePreSig: Boolean = false,
    val hiddenApi: Boolean = true,
    val sharedUser: Boolean = false,
    val verifyAgent: Boolean = true,
    val blocklist: Boolean = true,
) {
    fun enabledCount(): Int {
        return listOf(downgrade, digestVerify, arsc, signature, exactSig, usePreSig, hiddenApi, sharedUser, verifyAgent, blocklist).count { it }
    }

    fun get(key: String): Boolean {
        return when (key) {
            KEY_DOWNGRADE -> downgrade
            KEY_DIGEST -> digestVerify
            KEY_ARSC -> arsc
            KEY_SIGNATURE -> signature
            KEY_EXACT -> exactSig
            KEY_PRESIG -> usePreSig
            KEY_HIDDEN -> hiddenApi
            KEY_SHARED -> sharedUser
            KEY_AGENT -> verifyAgent
            KEY_BLOCK -> blocklist
            else -> false
        }
    }

    fun with(key: String, on: Boolean): CorePatchConfig {
        return when (key) {
            KEY_DOWNGRADE -> copy(downgrade = on)
            KEY_DIGEST -> copy(digestVerify = on)
            KEY_ARSC -> copy(arsc = on)
            KEY_SIGNATURE -> copy(signature = on)
            KEY_EXACT -> copy(exactSig = on)
            KEY_PRESIG -> copy(usePreSig = on)
            KEY_HIDDEN -> copy(hiddenApi = on)
            KEY_SHARED -> copy(sharedUser = on, signature = if (on) true else signature)
            KEY_AGENT -> copy(verifyAgent = on)
            KEY_BLOCK -> copy(blocklist = on)
            else -> this
        }
    }

    fun toJson(): String {
        val o = org.json.JSONObject()
        o.put(KEY_DOWNGRADE, downgrade)
        o.put(KEY_DIGEST, digestVerify)
        o.put(KEY_ARSC, arsc)
        o.put(KEY_SIGNATURE, signature)
        o.put(KEY_EXACT, exactSig)
        o.put(KEY_PRESIG, usePreSig)
        o.put(KEY_HIDDEN, hiddenApi)
        o.put(KEY_SHARED, sharedUser)
        o.put(KEY_AGENT, verifyAgent)
        o.put(KEY_BLOCK, blocklist)
        return o.toString(2)
    }

    companion object {
        const val KEY_DOWNGRADE = "downgrade"
        const val KEY_DIGEST = "digestVerify"
        const val KEY_ARSC = "arsc"
        const val KEY_SIGNATURE = "signature"
        const val KEY_EXACT = "exactSig"
        const val KEY_PRESIG = "usePreSig"
        const val KEY_HIDDEN = "hiddenApi"
        const val KEY_SHARED = "sharedUser"
        const val KEY_AGENT = "verifyAgent"
        const val KEY_BLOCK = "blocklist"

        fun default(): CorePatchConfig = CorePatchConfig()

        fun preset(n: Int): CorePatchConfig {
            return when (n) {
                3 -> CorePatchConfig(
                    downgrade = true,
                    digestVerify = true,
                    arsc = false,
                    signature = true,
                    exactSig = false,
                    usePreSig = false,
                    hiddenApi = false,
                    sharedUser = false,
                    verifyAgent = false,
                    blocklist = false,
                )
                5 -> CorePatchConfig(
                    downgrade = true,
                    digestVerify = true,
                    arsc = true,
                    signature = true,
                    exactSig = false,
                    usePreSig = false,
                    hiddenApi = false,
                    sharedUser = false,
                    verifyAgent = true,
                    blocklist = false,
                )
                else -> CorePatchConfig(
                    downgrade = true,
                    digestVerify = true,
                    arsc = true,
                    signature = true,
                    exactSig = false,
                    usePreSig = false,
                    hiddenApi = true,
                    sharedUser = false,
                    verifyAgent = true,
                    blocklist = true,
                )
            }
        }

        fun fromJson(text: String): CorePatchConfig {
            val o = org.json.JSONObject(text)
            return CorePatchConfig(
                downgrade = o.optBoolean(KEY_DOWNGRADE, true),
                digestVerify = o.optBoolean(KEY_DIGEST, true),
                arsc = o.optBoolean(KEY_ARSC, true),
                signature = o.optBoolean(KEY_SIGNATURE, true),
                exactSig = o.optBoolean(KEY_EXACT, false),
                usePreSig = o.optBoolean(KEY_PRESIG, false),
                hiddenApi = o.optBoolean(KEY_HIDDEN, true),
                sharedUser = o.optBoolean(KEY_SHARED, false),
                verifyAgent = o.optBoolean(KEY_AGENT, true),
                blocklist = o.optBoolean(KEY_BLOCK, true),
            )
        }
    }
}

data class CorePatchSwitch(
    val key: String,
    val title: String,
    val summary: String,
    val danger: Boolean = false,
)

object CorePatchSwitches {
    val ALL = listOf(
        CorePatchSwitch(CorePatchConfig.KEY_DOWNGRADE, "允许降级安装应用", "允许应用在已安装新版本的情况下直接覆盖安装旧版本"),
        CorePatchSwitch(CorePatchConfig.KEY_DIGEST, "禁用软件包管理器签名验证", "关闭安装时的摘要校验，可以安装被改过的 APK"),
        CorePatchSwitch(CorePatchConfig.KEY_ARSC, "绕过 resources.arsc 对齐限制", "允许在 Android 11 及以上安装 resources.arsc 已压缩或未按 4 字节对齐的 APK"),
        CorePatchSwitch(CorePatchConfig.KEY_SIGNATURE, "禁用 APK 签名验证", "允许直接覆盖安装同包名不同签名的应用"),
        CorePatchSwitch(CorePatchConfig.KEY_EXACT, "禁用唯一签名匹配", "允许分包 APK 使用不同签名。仅在确有必要时开启。", danger = true),
        CorePatchSwitch(CorePatchConfig.KEY_PRESIG, "安装时始终使用已装 APP 的签名", "非常危险。任意安装包都能覆盖已装应用。小米/红米不要开，可能无法开机。", danger = true),
        CorePatchSwitch(CorePatchConfig.KEY_HIDDEN, "允许系统应用使用隐藏 API", "允许与平台签名不一致的系统应用任意使用隐藏 API"),
        CorePatchSwitch(CorePatchConfig.KEY_SHARED, "绕过共享用户签名验证", "允许安装与其共享用户签名不同的应用。需要同时打开“禁用 APK 签名验证”。", danger = true),
        CorePatchSwitch(CorePatchConfig.KEY_AGENT, "禁用安装包验证代理", "关闭 Play 保护等安装验证代理"),
        CorePatchSwitch(CorePatchConfig.KEY_BLOCK, "绕过黑名单", "绕过某些设备如 Nothing Phone 上的安装黑名单"),
    )
}