package id.tntwindow.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.tntwindow.editor.BuildConfig
import id.tntwindow.editor.data.BackupStore
import id.tntwindow.editor.data.Classifier
import id.tntwindow.editor.data.CollectionStore
import id.tntwindow.editor.data.DisplayControl
import id.tntwindow.editor.data.HomeLock
import id.tntwindow.editor.data.TntHotswapService
import id.tntwindow.editor.data.TntLaunch
import id.tntwindow.editor.data.InstalledApps
import id.tntwindow.editor.data.Prefs
import id.tntwindow.editor.data.RevoneXml
import id.tntwindow.editor.data.VoiceEngines
import id.tntwindow.editor.data.VoiceStore
import id.tntwindow.editor.data.WebViewControl
import id.tntwindow.editor.data.WebViewStore
import id.tntwindow.editor.data.RootAccess
import id.tntwindow.editor.data.ShellResult
import id.tntwindow.editor.xposed.VoiceRuntime
import id.tntwindow.editor.data.specFromJson
import id.tntwindow.editor.data.specToJson
import id.tntwindow.editor.domain.AppCollection
import id.tntwindow.editor.domain.BackupItem
import id.tntwindow.editor.domain.Categories
import id.tntwindow.editor.domain.CollectionType
import id.tntwindow.editor.domain.DeviceStatus
import id.tntwindow.editor.domain.DisplayInfo
import id.tntwindow.editor.domain.HomeLauncher
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.Issue
import id.tntwindow.editor.domain.Membership
import id.tntwindow.editor.domain.ParseReport
import id.tntwindow.editor.domain.Presets
import id.tntwindow.editor.domain.VideoSpecial
import id.tntwindow.editor.domain.VoiceAlias
import id.tntwindow.editor.domain.VoiceCommand
import id.tntwindow.editor.domain.VoiceEngineInfo
import id.tntwindow.editor.domain.VoiceConfig
import id.tntwindow.editor.domain.WebViewConfig
import id.tntwindow.editor.domain.WebViewSnapshot
import id.tntwindow.editor.domain.WindowSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

data class EditorState(
    val status: DeviceStatus = DeviceStatus(),
    val collections: List<AppCollection> = emptyList(),
    val report: ParseReport? = null,
    val videos: List<VideoSpecial> = emptyList(),
    val installed: List<InstalledApp> = emptyList(),
    val backups: List<BackupItem> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val askRestart: Boolean = false,
    val showDisclaimer: Boolean = false,
    val themeMode: String = "system",
    val display: DisplayInfo = DisplayInfo(),
    val adaptiveFactor: Float = 1f,
    val lsposed: Boolean = false,
    val moduleActive: Boolean = false,
    val voiceEngine: String = VoiceConfig.ENGINE_REPLACE,
    val voiceCommands: List<VoiceCommand> = emptyList(),
    val voiceSpeak: Boolean = true,
    val voiceRecognizer: String = "",
    val voiceTts: String = "",
    val voiceAliases: List<VoiceAlias> = emptyList(),
    val voiceRecognizers: List<VoiceEngineInfo> = emptyList(),
    val voiceTtsEngines: List<VoiceEngineInfo> = emptyList(),
    val webView: WebViewSnapshot = WebViewSnapshot(),
    val webViewInject: Boolean = true,
    val overlayEnabled: Boolean = false,
    val homeLockEnabled: Boolean = false,
    val homeLaunchers: List<HomeLauncher> = emptyList(),
    val currentHome: String = "",
    val lockedHome: String = "",
    val showUninstalled: Boolean = true,
    val showNoLauncher: Boolean = false,
)

class TntViewModel(app: Application) : AndroidViewModel(app) {
    private val backup = BackupStore(app)
    private val collectionsStore = CollectionStore(app)
    val prefs = Prefs(app)
    val apps = InstalledApps(app)
    private val voiceStore = VoiceStore(app)
    private val webViewStore = WebViewStore(app)

    private val _state = MutableStateFlow(
        EditorState(
            showDisclaimer = !prefs.disclaimerAccepted,
            themeMode = prefs.themeMode,
            showUninstalled = prefs.showUninstalled,
            showNoLauncher = prefs.showNoLauncher,
        )
    )
    val state: StateFlow<EditorState> = _state

    fun acceptDisclaimer() {
        prefs.disclaimerAccepted = true
        _state.value = _state.value.copy(showDisclaimer = false)
    }

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setShowUninstalled(on: Boolean) {
        prefs.showUninstalled = on
        _state.value = _state.value.copy(showUninstalled = on)
    }

    fun setShowNoLauncher(on: Boolean) {
        prefs.showNoLauncher = on
        _state.value = _state.value.copy(showNoLauncher = on)
    }

    fun boot() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                withContext(Dispatchers.IO) { bootFast() }
                withContext(Dispatchers.IO) { bootSlow() }
                withContext(Dispatchers.IO) { ensureOwnAppWindow() }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = e.message ?: "启动失败")
            }
        }
    }

    private fun bootSync() {
        bootFast()
        bootSlow()
    }

    private fun bootFast() {
        backup.ensureDirs()
        val rootOk = RootAccess.available()
        val fileOk = rootOk && RootAccess.systemFileExists()
        val issues = mutableListOf<Issue>()
        var report: ParseReport? = null
        var xmlText: String? = null
        if (!rootOk) issues += Issue("权限", "没有 Root", "没有 Root 就不能读写窗口配置。")
        if (rootOk && !fileOk) issues += Issue("文件", "找不到配置文件", PathsLabel())
        if (fileOk) {
            val pull = RootAccess.pullTo(backup.workFile)
            if (!pull.ok) {
                issues += Issue("文件", "读取失败", pull.err.ifBlank { pull.out })
            } else {
                xmlText = backup.workFile.readText(StandardCharsets.UTF_8)
                if (!backup.originalExists()) {
                    backup.saveOriginal(xmlText.toByteArray(StandardCharsets.UTF_8))
                }
                report = RevoneXml.parse(xmlText)
                if (report.duplicatePackages.isNotEmpty()) {
                    val lines = report.duplicateCounts.entries.joinToString("\n") { it.key + " 出现 " + it.value + " 次" }
                    issues += Issue("XML", "重复包名", "以下包名出现多次：\n" + lines)
                }
                if (report.nestedIssues.isNotEmpty()) {
                    val lines = report.nestedIssues.joinToString("\n\n") { n ->
                        n.packageName + "\n原因：" + n.reason + "\n位置：" + n.start + "-" + n.end + "\n片段：" + n.snippet
                    }
                    issues += Issue("XML", "嵌套未闭合", lines)
                }
                if (report.dirtyAttrPackages.isNotEmpty()) {
                    issues += Issue("XML", "脏属性", "package 属性写成了 package = ：\n" + report.dirtyAttrPackages.joinToString("\n"))
                }
                if (report.lowercaseResizePackages.isNotEmpty()) {
                    issues += Issue("XML", "resizeMode 大小写", "使用了 <resizemode>：\n" + report.lowercaseResizePackages.joinToString("\n"))
                }
                if (report.missingWindowMode.isNotEmpty()) {
                    issues += Issue("XML", "缺少 windowMode", "没有 windowMode：\n" + report.missingWindowMode.joinToString("\n"))
                }
            }
        }
        val originalOk = backup.originalExists()
        if (fileOk && !originalOk) issues += Issue("备份", "原件备份失败", "没有原件备份时禁止编辑。")
        val editing = rootOk && fileOk && originalOk && xmlText != null
        val installed = try { apps.all() } catch (_: Exception) { emptyList() }
        prefs.addSeen(installed.map { it.packageName })
        var cols = collectionsStore.load()
        val needScan = editing && report != null && (cols == null || collectionsStore.lastSchema < Presets.SCHEMA || Classifier.looksLegacy(cols ?: emptyList()))
        if (needScan && report != null) {
            cols = Classifier.build(report, installed, cols)
            collectionsStore.save(cols)
            prefs.collectionSchema = Presets.SCHEMA
        } else if (cols != null) {
            cols = Classifier.mergeBuiltins(cols)
            collectionsStore.save(cols)
        } else {
            cols = Classifier.builtins()
            collectionsStore.save(cols)
        }
        if (prefs.autoAddEnabled && editing && report != null) {
            cols = autoAssign(cols, installed, report)
            collectionsStore.save(cols)
        }
        var voice = voiceStore.load()
        voice = withoutSampleCommands(withoutRemovedRecognizer(voice)).copy(
            engine = VoiceConfig.ENGINE_REPLACE,
            recognizer = "",
            ttsEngine = "",
        )
        voiceStore.save(voice)
        val pm = getApplication<Application>().packageManager
        val warnings = issues.map { it.title + "：" + it.detail }
        val status = DeviceStatus(
            rootOk = rootOk,
            fileOk = fileOk,
            originalBackupAt = backup.originalTime(),
            lastWriteAt = prefs.lastWriteAt.let { if (it == 0L) null else it },
            lastWriteOk = if (prefs.lastWriteAt == 0L) null else prefs.lastWriteOk,
            appCount = report?.apps?.count { it.duplicateIndex == 0 } ?: 0,
            warnings = warnings,
            issues = issues,
            editingAllowed = editing,
            pcMode = _state.value.status.pcMode,
        )
        _state.value = _state.value.copy(
            status = status,
            collections = cols,
            report = report,
            videos = report?.videos ?: emptyList(),
            installed = installed,
            backups = backup.listRestorable(),
            busy = false,
            themeMode = prefs.themeMode,
            moduleActive = id.tntwindow.editor.xposed.ModuleStatus.ACTIVE,
            voiceEngine = voice.engine,
            voiceCommands = voice.commands,
            voiceSpeak = voice.speak,
            voiceRecognizer = voice.recognizer,
            voiceTts = voice.ttsEngine,
            voiceAliases = voice.aliases,
            voiceRecognizers = try { VoiceEngines.recognizers(pm) } catch (_: Exception) { emptyList() },
            voiceTtsEngines = try { VoiceEngines.tts(pm) } catch (_: Exception) { emptyList() },
            overlayEnabled = prefs.overlayEnabled,
            homeLockEnabled = prefs.homeLockEnabled,
            showUninstalled = prefs.showUninstalled,
            showNoLauncher = prefs.showNoLauncher,
        )
    }

    private fun bootSlow() {
        val rootOk = _state.value.status.rootOk
        val display = if (rootOk) {
            try { DisplayControl.info() } catch (_: Exception) { DisplayInfo() }
        } else DisplayInfo()
        val pcMode = if (rootOk) DisplayControl.pcMode() else null
        if (rootOk) {
            val hasVoice = RootAccess.su("test -s '" + id.tntwindow.editor.domain.Paths.VOICE + "' && echo ok")
            if (!hasVoice.out.contains("ok")) voiceStore.push()
        }
        if (rootOk) {
            try { RootAccess.su("rm -rf /data/adb/modules/smartisan_patch") } catch (_: Exception) {}
        }
        var webCfg = webViewStore.load()
        if (!webViewStore.exists()) {
            webViewStore.save(webCfg)
        }
        if (rootOk) {
            val hasWeb = RootAccess.su("test -s /data/system/tnt_webview_config.json && echo ok")
            if (!hasWeb.out.contains("ok")) webViewStore.push()
        }
        val pm = getApplication<Application>().packageManager
        var webSnap = try { WebViewControl.snapshot(pm) } catch (_: Exception) { WebViewSnapshot() }
        var bootMsg: String? = _state.value.message
        if (rootOk && webCfg.pending.isNotBlank()) {
            if (WebViewControl.validIn(webSnap, webCfg.pending)) {
                val set = WebViewControl.setImplementation(webCfg.pending)
                webSnap = try { WebViewControl.snapshot(pm) } catch (_: Exception) { webSnap }
                if (webSnap.currentPackage == webCfg.pending) {
                    val switched = webCfg.pending
                    webCfg = webCfg.copy(pending = "")
                    webViewStore.save(webCfg)
                    webViewStore.push()
                    bootMsg = "已自动切换系统 WebView：" + switched
                } else {
                    bootMsg = "名单已生效，但切换失败：" + set.err.ifBlank { set.out }
                }
            }
        }
        if (rootOk) {
            try { WebViewControl.ensureVoiceScope() } catch (_: Exception) {}
        }
        if (rootOk) {
            try { voiceStore.push() } catch (_: Exception) {}
            try { HomeLock.enableOfficial() } catch (_: Exception) {}
        }
        val launchers = try { HomeLock.list(getApplication()) } catch (_: Exception) { emptyList() }
        val currentHome = try { HomeLock.current(getApplication()) } catch (_: Exception) { "" }
        if (prefs.homeLockEnabled && prefs.lockedHome.isBlank()) {
            val third = pickThirdParty(launchers, currentHome)
            if (third.isNotBlank()) prefs.lockedHome = third
        }
        if (rootOk) {
            try { HomeLock.push(prefs.homeLockEnabled, prefs.lockedHome) } catch (_: Exception) {}
            try {
                if (prefs.overlayEnabled) DisplayControl.applyOverlay(display) else DisplayControl.clearOverlay()
            } catch (_: Exception) {}
        }
        val lsposed = if (rootOk) DisplayControl.lsposedPresent() else false
        _state.value = _state.value.copy(
            status = _state.value.status.copy(pcMode = pcMode),
            display = display,
            adaptiveFactor = adaptiveFactor(display),
            lsposed = lsposed,
            webView = webSnap,
            webViewInject = webCfg.inject,
            homeLaunchers = launchers,
            currentHome = currentHome,
            lockedHome = prefs.lockedHome,
            overlayEnabled = prefs.overlayEnabled,
            homeLockEnabled = prefs.homeLockEnabled,
            message = bootMsg,
            voiceRecognizers = try { VoiceEngines.recognizers(pm) } catch (_: Exception) { _state.value.voiceRecognizers },
            voiceTtsEngines = try { VoiceEngines.tts(pm) } catch (_: Exception) { _state.value.voiceTtsEngines },
        )
    }

    private fun PathsLabel() = "路径 ${id.tntwindow.editor.domain.Paths.SYSTEM}"

    private fun adaptiveFactor(display: DisplayInfo): Float {
        if (!prefs.adaptiveEnabled) return 1f
        val baseline = prefs.baselineWidthDp.coerceAtLeast(1)
        val current = display.widthDp
        if (current <= 0) return 1f
        return current.toFloat() / baseline.toFloat()
    }

    private fun autoAssign(cols: List<AppCollection>, installed: List<InstalledApp>, report: ParseReport): List<AppCollection> {
        val configured = report.apps.map { it.packageName }.toSet()
        val owned = cols.flatMap { c -> c.members.map { it.packageName } }.toSet()
        val skip = prefs.skipAuto()
        val destId = prefs.defaultCollectionId.ifBlank { Categories.TOOL.id }
        val dest = cols.firstOrNull { it.id == destId } ?: cols.firstOrNull { it.id == Categories.TOOL.id } ?: return cols
        val add = installed.filter { app ->
            app.hasLauncher &&
                app.packageName !in configured &&
                app.packageName !in owned &&
                app.packageName !in skip &&
                app.packageName != getApplication<Application>().packageName
        }
        if (add.isEmpty()) return cols
        val members = dest.members + add.map { app ->
            Membership(app.packageName, if (dest.type == CollectionType.CUSTOM) defaultSpec() else null)
        }
        return cols.map { if (it.id == dest.id) it.copy(members = members) else it }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, askRestart = false)
    }

    fun collection(id: String): AppCollection? = _state.value.collections.firstOrNull { it.id == id }

    fun ownerOf(pkg: String): AppCollection? =
        _state.value.collections.firstOrNull { c -> c.members.any { it.packageName == pkg } }

    fun updateCollections(next: List<AppCollection>) {
        collectionsStore.save(next)
        _state.value = _state.value.copy(collections = next)
    }

    fun rename(id: String, name: String) {
        val col = collection(id) ?: return
        if (col.builtin && col.id != Presets.CUSTOM_ID) return
        updateCollections(_state.value.collections.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun setSpec(id: String, spec: WindowSpec) {
        updateCollections(_state.value.collections.map { if (it.id == id) it.copy(spec = spec) else it })
    }

    fun setCustomSpec(pkg: String, spec: WindowSpec) {
        updateCollections(_state.value.collections.map { c ->
            if (c.type != CollectionType.CUSTOM) c
            else c.copy(members = c.members.map { if (it.packageName == pkg) it.copy(customSpec = spec) else it })
        })
    }

    fun createSpecCollection(name: String, spec: WindowSpec): String {
        val id = "spec_" + System.currentTimeMillis()
        val next = _state.value.collections + AppCollection(id, name, CollectionType.SPEC, spec)
        updateCollections(next)
        return id
    }

    fun addApps(collectionId: String, packages: List<String>): String? {
        val target = collection(collectionId) ?: return "合集不存在"
        var cols = _state.value.collections
        val conflicts = packages.mapNotNull { pkg ->
            val owner = cols.firstOrNull { c -> c.id != collectionId && c.members.any { it.packageName == pkg } }
            if (owner != null) pkg to owner.name else null
        }
        if (conflicts.isNotEmpty()) {
            return "CONFLICT:" + conflicts.joinToString("\n") { it.first + " 已在「" + it.second + "」" }
        }
        prefs.removeSkipAuto(packages)
        cols = cols.map { c ->
            when {
                c.id == collectionId -> {
                    val exist = c.members.map { it.packageName }.toSet()
                    val added = packages.filter { it !in exist }.map { pkg ->
                        val current = _state.value.report?.apps?.firstOrNull { it.packageName == pkg && it.duplicateIndex == 0 }?.spec
                        Membership(pkg, if (c.type == CollectionType.CUSTOM) current ?: defaultSpec() else null)
                    }
                    c.copy(members = c.members + added)
                }
                else -> c
            }
        }
        updateCollections(cols)
        return null
    }

    fun moveApp(pkg: String, toId: String) {
        val from = ownerOf(pkg)
        val specCarry = when {
            from == null -> null
            from.type == CollectionType.CUSTOM -> from.members.firstOrNull { it.packageName == pkg }?.customSpec
            else -> from.spec
        }
        prefs.removeSkipAuto(listOf(pkg))
        var cols = _state.value.collections.map { c ->
            c.copy(members = c.members.filter { it.packageName != pkg })
        }
        cols = cols.map { c ->
            if (c.id != toId) c
            else {
                val member = Membership(pkg, if (c.type == CollectionType.CUSTOM) specCarry ?: defaultSpec() else null)
                c.copy(members = c.members + member)
            }
        }
        updateCollections(cols)
    }

    fun removeMember(pkg: String) {
        prefs.addSkipAuto(listOf(pkg))
        updateCollections(_state.value.collections.map { c ->
            c.copy(members = c.members.filter { it.packageName != pkg })
        })
    }

    fun applyCollection(id: String) {
        viewModelScope.launch(Dispatchers.IO) { applyCollectionBody(id) }
    }

    private fun applyCollectionBody(id: String): Boolean {
        val col = collection(id) ?: return fail("合集不存在")
        val plan = linkedMapOf<String, WindowSpec>()
        if (col.type == CollectionType.SPEC) {
            val spec = col.spec ?: return fail("合集没有规格")
            col.members.forEach { plan[it.packageName] = spec }
        } else {
            col.members.forEach { m ->
                plan[m.packageName] = m.customSpec ?: defaultSpec()
            }
        }
        return applyPlan(plan, emptySet())
    }

    fun scaleAllCollectionNumbers() {
        val factor = adaptiveFactor(_state.value.display)
        if (factor == 1f) {
            _state.value = _state.value.copy(message = "当前倍率是 1，数字没有变化。先打开自适应倍率，或改基准宽。")
            return
        }
        updateCollections(_state.value.collections.map { c ->
            if (c.type == CollectionType.SPEC) {
                val spec = c.spec ?: return@map c
                c.copy(spec = spec.scaled(factor))
            } else {
                c.copy(members = c.members.map { m ->
                    m.copy(customSpec = (m.customSpec ?: defaultSpec()).scaled(factor))
                })
            }
        })
        _state.value = _state.value.copy(
            adaptiveFactor = factor,
            message = "已把全部合集宽高乘上 " + String.format(java.util.Locale.US, "%.2f", factor) + "。点「应用并保存全部合集」才会按这些数字写 XML。",
        )
    }

    fun applyAllCollections() {
        viewModelScope.launch(Dispatchers.IO) { applyAllCollectionsBody() }
    }

    private fun applyAllCollectionsBody(): Boolean {
        val plan = linkedMapOf<String, WindowSpec>()
        for (col in _state.value.collections) {
            if (col.type == CollectionType.SPEC) {
                val spec = col.spec ?: continue
                col.members.forEach { plan[it.packageName] = spec }
            } else {
                col.members.forEach { m ->
                    plan[m.packageName] = m.customSpec ?: defaultSpec()
                }
            }
        }
        if (plan.isEmpty()) return fail("没有可写的窗口配置")
        return applyPlan(plan, emptySet())
    }

    fun applyVideos(list: List<VideoSpecial>) {
        viewModelScope.launch(Dispatchers.IO) { applyVideosBody(list) }
    }

    private fun applyVideosBody(list: List<VideoSpecial>): Boolean {
        if (!ready()) return false
        return mutateXml { RevoneXml.patchVideos(it, list) }
    }

    fun restore(item: BackupItem) {
        viewModelScope.launch(Dispatchers.IO) { restoreBody(item) }
    }

    private fun restoreBody(item: BackupItem): Boolean {
        if (item.kind == "FULL") return restoreFull(item)
        val f = File(item.path)
        if (!f.exists()) return fail("备份不存在")
        val text = f.readText(StandardCharsets.UTF_8)
        RevoneXml.parse(text)
        return writeSystem(text.toByteArray(StandardCharsets.UTF_8), rescan = true)
    }

    private fun restoreFull(item: BackupItem): Boolean {
        val dir = File(item.path)
        if (!dir.isDirectory) return fail("完整备份不存在")
        val xml = File(dir, "revone_window_config.xml")
        val col = File(dir, "collections.json")
        val voice = File(dir, "voice.json")
        val web = File(dir, "webview.json")
        val prefsFile = File(dir, "prefs.json")
        val app = getApplication<Application>()
        if (xml.exists()) {
            if (!RootAccess.available()) return fail("没有 Root")
            val bytes = xml.readBytes()
            backup.tmpFile.writeBytes(bytes)
            val push = RootAccess.pushFrom(backup.tmpFile)
            if (!push.ok) return fail("恢复 XML 失败：" + push.err.ifBlank { push.out })
            backup.workFile.writeBytes(bytes)
        }
        if (col.exists()) File(app.filesDir, "collections.json").writeBytes(col.readBytes())
        if (voice.exists()) {
            File(app.filesDir, "voice_config.json").writeBytes(voice.readBytes())
            if (RootAccess.available()) voiceStore.push()
        }
        if (web.exists()) {
            File(app.filesDir, "webview_config.json").writeBytes(web.readBytes())
            if (RootAccess.available()) webViewStore.push()
        }
        if (prefsFile.exists()) {
            try {
                prefs.applyDump(JSONObject(prefsFile.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
            }
        }
        bootSync()
        _state.value = _state.value.copy(
            message = "已恢复完整备份",
            askRestart = xml.exists() && prefs.askRestartAfterWrite && (_state.value.status.pcMode == 1 || _state.value.status.pcMode == 2),
        )
        return true
    }

    fun deleteBackup(item: BackupItem) {
        if (item.kind == "ORIGINAL") {
            _state.value = _state.value.copy(message = "原件备份不能删")
            return
        }
        backup.delete(item)
        _state.value = _state.value.copy(backups = backup.listRestorable(), message = "已删除备份")
    }

    fun manualBackup() {
        viewModelScope.launch(Dispatchers.IO) { manualBackupBody() }
    }

    fun fullBackup() {
        viewModelScope.launch(Dispatchers.IO) { fullBackupBody() }
    }

    private fun fullBackupBody(): Boolean {
        if (RootAccess.available()) {
            val pull = RootAccess.pullTo(backup.workFile)
            if (!pull.ok) return fail("读取系统文件失败")
        }
        val app = getApplication<Application>()
        val files = linkedMapOf<String, ByteArray>()
        if (backup.workFile.exists()) files["revone_window_config.xml"] = backup.workFile.readBytes()
        val col = File(app.filesDir, "collections.json")
        if (col.exists()) files["collections.json"] = col.readBytes()
        val vf = File(app.filesDir, "voice_config.json")
        if (vf.exists()) files["voice.json"] = vf.readBytes()
        val wf = File(app.filesDir, "webview_config.json")
        if (wf.exists()) files["webview.json"] = wf.readBytes()
        files["prefs.json"] = prefs.dump().toString().toByteArray(Charsets.UTF_8)
        val saved = backup.saveFull(files, BuildConfig.VERSION_NAME)
        _state.value = _state.value.copy(
            backups = backup.listRestorable(),
            message = if (saved == null) "内容相同，未重复保存" else "已建立完整备份",
        )
        return true
    }

    fun setHomeLockEnabled(on: Boolean) {
        prefs.homeLockEnabled = on
        viewModelScope.launch(Dispatchers.IO) {
            if (on) {
                val list = try { HomeLock.list(getApplication()) } catch (_: Exception) { emptyList() }
                val cur = try { HomeLock.current(getApplication()) } catch (_: Exception) { "" }
                val third = pickThirdParty(list, cur)
                if (third.isNotBlank()) prefs.lockedHome = third
            }
            if (RootAccess.available()) HomeLock.push(on, prefs.lockedHome)
            _state.value = _state.value.copy(
                homeLockEnabled = on,
                lockedHome = prefs.lockedHome,
                homeLaunchers = try { HomeLock.list(getApplication()) } catch (_: Exception) { _state.value.homeLaunchers },
                currentHome = try { HomeLock.current(getApplication()) } catch (_: Exception) { _state.value.currentHome },
                message = if (on) "已打开锁桌面" else "已关闭锁桌面",
            )
        }
    }

    fun setLockedHome(component: String) {
        prefs.lockedHome = component
        viewModelScope.launch(Dispatchers.IO) {
            if (RootAccess.available()) HomeLock.push(prefs.homeLockEnabled, component)
            _state.value = _state.value.copy(lockedHome = component)
        }
    }

    fun setOverlayEnabled(on: Boolean) {
        prefs.overlayEnabled = on
        viewModelScope.launch(Dispatchers.IO) {
            if (RootAccess.available()) {
                if (on) DisplayControl.applyOverlay(_state.value.display) else DisplayControl.clearOverlay()
            }
            _state.value = _state.value.copy(
                overlayEnabled = on,
                message = if (on) "已打开 overlay" else "已关闭 overlay",
            )
        }
    }

    private fun pickThirdParty(list: List<HomeLauncher>, current: String): String {
        if (isThird(current)) return current
        val locked = prefs.lockedHome.trim()
        if (isThird(locked)) return locked
        return list.firstOrNull { !it.official }?.component ?: ""
    }

    private fun isThird(c: String): Boolean {
        if (!c.contains('/')) return false
        if (c.startsWith(TntLaunch.HOME_PKG)) return false
        if (c.startsWith(TntLaunch.DESKTOP_PKG)) return false
        return true
    }

    private fun manualBackupBody(): Boolean {
        if (!pullWork()) return false
        val saved = backup.saveManual(backup.workFile.readBytes())
        _state.value = _state.value.copy(
            backups = backup.listRestorable(),
            message = if (saved == null) "内容相同，未重复保存" else "已建立手动备份",
        )
        return true
    }

    fun restartTnt() {
        viewModelScope.launch(Dispatchers.IO) {
            val restore = if (prefs.homeLockEnabled) pickThirdParty(_state.value.homeLaunchers, _state.value.currentHome) else ""
            try {
                val i = android.content.Intent(getApplication(), TntHotswapService::class.java)
                i.putExtra("extra", prefs.restartCommand)
                i.putExtra("restore", restore)
                getApplication<Application>().startService(i)
            } catch (_: Throwable) {
            }
            val r = DisplayControl.armHotswap(prefs.restartCommand, restore)
            val msg = if (r.ok) {
                "已开始热切换。切镜像时本应用关掉也没关系，脚本会自己回到 TNT 桌面。"
            } else {
                "热切换启动失败: " + r.err.ifBlank { r.out }
            }
            _state.value = _state.value.copy(message = msg)
        }
    }

    fun setTntMode(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!_state.value.status.rootOk) {
                fail("没有 Root")
                return@launch
            }
            val name = when (mode) {
                0 -> "镜像"
                1 -> "桌面"
                2 -> "分享"
                else -> mode.toString()
            }
            val ok = DisplayControl.setDesktopMode(mode)
            val now = try { DisplayControl.pcMode() } catch (_: Exception) { null }
            _state.value = _state.value.copy(
                status = _state.value.status.copy(pcMode = now),
                message = if (ok) "已切换到" + name else "切换" + name + "失败",
            )
        }
    }

    fun openTouchpad() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!_state.value.status.rootOk) {
                fail("没有 Root")
                return@launch
            }
            val r = DisplayControl.openTouchpad()
            _state.value = _state.value.copy(
                message = if (r.ok) "已打开虚拟触摸板" else "打开虚拟触摸板失败: " + r.err.ifBlank { r.out },
            )
        }
    }

    fun applyDisplay(width: Int?, height: Int?, density: Int?, resetSize: Boolean, resetDensity: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (resetSize) DisplayControl.resetSize()
            else if (width != null && height != null) DisplayControl.setSize(width, height)
            if (resetDensity) DisplayControl.resetDensity()
            else if (density != null) DisplayControl.setDensity(density)
            val info = DisplayControl.info()
            _state.value = _state.value.copy(
                display = info,
                adaptiveFactor = adaptiveFactor(info),
                message = "显示已更新 · " + info.summary,
            )
        }
    }

    fun defaultSpec(): WindowSpec {
        return try {
            specFromJson(JSONObject(prefs.defaultSpecJson)) ?: Presets.DEFAULT_NEW
        } catch (_: Exception) {
            Presets.DEFAULT_NEW
        }
    }

    fun setDefaultSpec(spec: WindowSpec) {
        prefs.defaultSpecJson = specToJson(spec).toString()
    }

    fun exportBytes(): ByteArray? {
        if (!pullWork()) return null
        return backup.workFile.readBytes()
    }

    fun importBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) { importBytesBody(bytes) }
    }

    private fun importBytesBody(bytes: ByteArray): Boolean {
        val text = bytes.toString(StandardCharsets.UTF_8)
        RevoneXml.parse(text)
        return writeSystem(bytes, rescan = true)
    }

    fun writeOwnApp() {
        viewModelScope.launch(Dispatchers.IO) { writeOwnAppBody() }
    }

    private fun writeOwnAppBody(): Boolean {
        val pkg = getApplication<Application>().packageName
        addApps(Presets.CUSTOM_ID, listOf(pkg))
        setCustomSpec(pkg, defaultSpec())
        return applyPlan(mapOf(pkg to defaultSpec()), emptySet())
    }

    private fun ensureOwnAppWindow() {
        if (!_state.value.status.editingAllowed) return
        val pkg = getApplication<Application>().packageName
        val report = _state.value.report ?: return
        if (report.apps.any { it.packageName == pkg }) return
        val owner = ownerOf(pkg)
        if (owner == null) {
            addApps(Presets.CUSTOM_ID, listOf(pkg))
            setCustomSpec(pkg, defaultSpec())
            applyPlan(mapOf(pkg to defaultSpec()), emptySet())
        } else {
            val spec = if (owner.type == CollectionType.CUSTOM) {
                owner.members.firstOrNull { it.packageName == pkg }?.customSpec ?: defaultSpec()
            } else {
                owner.spec ?: defaultSpec()
            }
            applyPlan(mapOf(pkg to spec), emptySet())
        }
        _state.value = _state.value.copy(askRestart = false, message = null)
    }

    fun searchHits(query: String): List<Triple<String, String, String>> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val installed = _state.value.installed.associateBy { it.packageName }
        val out = mutableListOf<Triple<String, String, String>>()
        for (col in _state.value.collections) {
            for (m in visibleMembers(col.members)) {
                val label = installed[m.packageName]?.label ?: apps.label(m.packageName)
                if (label.contains(q, true) || m.packageName.contains(q, true)) {
                    out += Triple(m.packageName, label, col.name)
                }
            }
        }
        return out
    }

    fun sortMembers(members: List<Membership>): List<Membership> {
        val installed = _state.value.installed.associateBy { it.packageName }
        return members.sortedWith(
            compareBy<Membership> { installed[it.packageName] == null }
                .thenBy { installed[it.packageName]?.system == true }
                .thenBy { (installed[it.packageName]?.label ?: it.packageName).lowercase() }
        )
    }

    fun visibleMembers(members: List<Membership>): List<Membership> {
        val installed = _state.value.installed.associateBy { it.packageName }
        val showUninstalled = _state.value.showUninstalled
        val showNoLauncher = _state.value.showNoLauncher
        return sortMembers(members.filter { m ->
            val app = installed[m.packageName]
            if (app == null) showUninstalled else showNoLauncher || app.hasLauncher
        })
    }

    private fun applyPlan(plan: Map<String, WindowSpec>, deletes: Set<String>): Boolean {
        if (!ready()) return false
        return mutateXml(plan) { src ->
            var t = src
            if (deletes.isNotEmpty()) t = RevoneXml.deletePackages(t, deletes)
            if (plan.isNotEmpty()) t = RevoneXml.patchApplications(t, plan)
            t
        }
    }

    private fun mutateXml(plan: Map<String, WindowSpec> = emptyMap(), transform: (String) -> String): Boolean {
        if (!pullWork()) return false
        val src = backup.workFile.readText(StandardCharsets.UTF_8)
        val out = try {
            transform(src)
        } catch (e: Exception) {
            return fail(e.message ?: "变换失败")
        }
        val parsed = RevoneXml.parse(out)
        if (plan.isNotEmpty()) {
            val mismatch = RevoneXml.verify(parsed, plan)
            if (mismatch != null) return fail(mismatch)
        }
        backup.tmpFile.writeText(out, StandardCharsets.UTF_8)
        val parsedTmp = RevoneXml.parse(backup.tmpFile.readText(StandardCharsets.UTF_8))
        if (plan.isNotEmpty()) {
            val mismatch = RevoneXml.verify(parsedTmp, plan)
            if (mismatch != null) return fail(mismatch)
        }
        return writeSystem(out.toByteArray(StandardCharsets.UTF_8), rescan = false)
    }

    private fun writeSystem(bytes: ByteArray, rescan: Boolean): Boolean {
        if (!pullWork()) return false
        backup.saveLast(backup.workFile.readBytes())
        if (prefs.backupEveryWrite) {
            backup.savePrewrite(backup.workFile.readBytes())
        }
        backup.tmpFile.writeBytes(bytes)
        val push = RootAccess.pushFrom(backup.tmpFile)
        if (!push.ok) {
            RootAccess.pushFrom(backup.workFile)
            prefs.lastWriteAt = System.currentTimeMillis()
            prefs.lastWriteOk = false
            return fail("写盘失败，已回滚: " + push.err.ifBlank { push.out })
        }
        prefs.lastWriteAt = System.currentTimeMillis()
        prefs.lastWriteOk = true
        val tntOn = _state.value.status.pcMode == 1 || _state.value.status.pcMode == 2
        val ask = prefs.askRestartAfterWrite && tntOn
        if (rescan) collectionsStore.clear()
        bootSync()
        _state.value = _state.value.copy(
            message = "已写入系统 XML。点右上角热切换 TNT（镜像→桌面）才会读新窗口。",
            askRestart = ask,
        )
        return true
    }

    private fun pullWork(): Boolean {
        val r = RootAccess.pullTo(backup.workFile)
        if (!r.ok) return fail("读取系统文件失败")
        return true
    }

    private fun ready(): Boolean {
        if (!_state.value.status.editingAllowed) return fail("当前不能编辑")
        return true
    }


    fun setVoiceEngine(engine: String) {
        persistVoice(liveVoice(engine = engine))
    }

    fun setVoiceSpeak(on: Boolean) {
        persistVoice(liveVoice(speak = on))
    }

    fun setVoiceRecognizer(id: String) {
        persistVoice(liveVoice(recognizer = id))
    }

    fun setVoiceTts(id: String) {
        persistVoice(liveVoice(ttsEngine = id))
    }

    fun addVoiceCommand(phrase: String, action: String, target: String) {
        val p = phrase.trim()
        val t = target.trim()
        if (p.isEmpty() || t.isEmpty()) return
        val cmd = VoiceCommand(id = System.currentTimeMillis().toString(), phrase = p, action = action, target = t)
        persistVoice(liveVoice(commands = _state.value.voiceCommands + cmd))
    }

    fun removeVoiceCommand(id: String) {
        persistVoice(liveVoice(commands = _state.value.voiceCommands.filter { it.id != id }))
    }

    fun previewVoice(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        val apps = _state.value.installed.filter { it.hasLauncher }.map { it.label to it.packageName }
        val msg = VoiceRuntime.preview(spoken, _state.value.voiceCommands, apps)
        _state.value = _state.value.copy(message = "虚拟遥控器同一套匹配：" + msg)
    }

    fun addVoiceAlias(spoken: String, packageName: String, label: String) {
        val s = spoken.trim()
        val pkg = packageName.trim()
        if (s.isEmpty() || pkg.isEmpty()) return
        val alias = VoiceAlias(id = System.currentTimeMillis().toString(), spoken = s, packageName = pkg, label = label.trim().ifBlank { s })
        persistVoice(liveVoice(aliases = _state.value.voiceAliases.filter { it.spoken != s } + alias))
    }

    fun removeVoiceAlias(id: String) {
        persistVoice(liveVoice(aliases = _state.value.voiceAliases.filter { it.id != id }))
    }


    private fun withoutSampleCommands(cfg: VoiceConfig): VoiceConfig {
        val sample = setOf("mx1", "mx2", "mx3")
        val cleaned = cfg.commands.filterNot { it.id in sample }
        return if (cleaned.size == cfg.commands.size) cfg else cfg.copy(commands = cleaned)
    }

    private fun withoutRemovedRecognizer(cfg: VoiceConfig): VoiceConfig {
        val id = cfg.recognizer.lowercase()
        return if (id.contains("vosk") || id.contains("tntwindow.editor.voice") || id.contains("google")) {
            cfg.copy(recognizer = "")
        } else cfg
    }

    private fun liveVoice(
        engine: String = _state.value.voiceEngine,
        commands: List<VoiceCommand> = _state.value.voiceCommands,
        speak: Boolean = _state.value.voiceSpeak,
        recognizer: String = _state.value.voiceRecognizer,
        ttsEngine: String = _state.value.voiceTts,
        aliases: List<VoiceAlias> = _state.value.voiceAliases,
    ): VoiceConfig {
        return VoiceConfig(engine, commands, speak, recognizer, ttsEngine, aliases)
    }

    private fun persistVoice(cfg: VoiceConfig) {
        val oldRec = _state.value.voiceRecognizer
        voiceStore.save(cfg)
        val r = voiceStore.push()
        var extra = ""
        if (r.ok && oldRec != cfg.recognizer) {
            val b = bounceVoice()
            extra = if (b.ok) " 已热切虚拟遥控器，不用重启手机。" else " 热切虚拟遥控器失败：" + b.err.ifBlank { b.out }
        }
        _state.value = _state.value.copy(
            voiceEngine = cfg.engine,
            voiceCommands = cfg.commands,
            voiceSpeak = cfg.speak,
            voiceRecognizer = cfg.recognizer,
            voiceTts = cfg.ttsEngine,
            voiceAliases = cfg.aliases,
            message = if (r.ok) "语音配置已写入。" + extra else "本地已保存，写入系统失败：" + r.err.ifBlank { r.out },
        )
    }

    private fun bounceVoice(): ShellResult {
        return RootAccess.su("am force-stop com.smartisanos.virtualremoter; am force-stop com.smartisanos.voice; sleep 0.4; am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.smartisanos.virtualremoter")
    }

    fun refreshVoiceEngines() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val rec = try { VoiceEngines.recognizers(pm) } catch (_: Exception) { emptyList() }
            val tts = try { VoiceEngines.tts(pm) } catch (_: Exception) { emptyList() }
            _state.value = _state.value.copy(
                voiceRecognizers = rec,
                voiceTtsEngines = tts,
                message = if (rec.isEmpty()) "没有扫到听写引擎。" else "扫到 " + rec.size + " 个听写引擎。",
            )
        }
    }

    fun note(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }

    fun refreshWebView() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val snap = withContext(Dispatchers.IO) {
                try { WebViewControl.snapshot(getApplication<Application>().packageManager) } catch (_: Exception) { WebViewSnapshot() }
            }
            _state.value = _state.value.copy(busy = false, webView = snap)
        }
    }

    fun setWebViewInject(on: Boolean) {
        val cur = webViewStore.load()
        persistWebView(cur.copy(inject = on))
    }

    fun setWebViewImplementation(pkg: String, rebootIfNeeded: Boolean = false) {
        val name = pkg.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                if (!WebViewControl.installed(pm, name)) {
                    return@withContext Triple("missing", WebViewSnapshot(), webViewStore.load())
                }
                val early = try { WebViewControl.snapshot(pm) } catch (_: Exception) { WebViewSnapshot() }
                val picked = early.providers.firstOrNull { it.packageName == name }
                if (picked != null && !picked.abiOk) {
                    return@withContext Triple("abi:" + picked.abis.joinToString(","), early, webViewStore.load())
                }
                val cfg = webViewStore.load()
                val extra = (cfg.extra + name).distinct()
                val next = cfg.copy(inject = true, extra = extra, pending = name)
                webViewStore.save(next)
                webViewStore.push()
                WebViewControl.ensureFrameworkScope()
                var snap = try { WebViewControl.snapshot(pm) } catch (_: Exception) { WebViewSnapshot() }
                if (!WebViewControl.validIn(snap, name)) {
                    if (rebootIfNeeded) {
                        WebViewControl.reboot()
                        return@withContext Triple("rebooting", snap, next)
                    }
                    return@withContext Triple("need_reboot", snap, next)
                }
                val set = WebViewControl.setImplementation(name)
                snap = try { WebViewControl.snapshot(pm) } catch (_: Exception) { snap }
                if (snap.currentPackage == name) {
                    val done = next.copy(pending = "")
                    webViewStore.save(done)
                    webViewStore.push()
                    return@withContext Triple("ok", snap, done)
                }
                Triple("cmd:" + set.err.ifBlank { set.out }, snap, next)
            }
            val code = result.first
            val snap = result.second
            val cfg = result.third
            val msg = when {
                code == "missing" -> "这个包没装。"
                code.startsWith("abi:") -> "这个 WebView 没有 64 位库（" + code.removePrefix("abi:") + "）。APatch 等 64 位应用会闪退，不能切。请装 arm64-v8a，或先切回系统 WebView。"
                code == "ok" -> "已切换系统 WebView。已打开的应用请划掉重开。"
                code == "rebooting" -> "已写入注入，正在重启。"
                code == "need_reboot" -> "还不在系统名单，点「注入并重启」。"
                code.startsWith("cmd:") -> "名单里有这个包，但切换失败：" + code.removePrefix("cmd:").ifBlank { "未知错误" }
                else -> "切换失败"
            }
            _state.value = _state.value.copy(
                busy = false,
                webView = snap,
                webViewInject = cfg.inject,
                message = msg,
            )
        }
    }

    fun rebootDevice() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WebViewControl.reboot() }
        }
    }

    fun installWebViewApk(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val msg = withContext(Dispatchers.IO) {
                val tmp = File(getApplication<Application>().cacheDir, "tnt_webview.apk")
                val bytes = try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    return@withContext "读 APK 失败：" + (e.message ?: "")
                }
                if (bytes == null || bytes.isEmpty()) return@withContext "APK 是空的"
                tmp.writeBytes(bytes)
                val zipAbis = WebViewControl.apkAbis(tmp)
                if (!WebViewControl.abiCompatible(zipAbis) && zipAbis.isNotEmpty()) {
                    tmp.delete()
                    return@withContext "这个 APK 只有 " + zipAbis.joinToString() + "，没有 64 位库。APatch 等 64 位应用会闪退。请选 arm64-v8a 或 arm64-v8a+armeabi-v7a。"
                }
                val r = WebViewControl.installApk(tmp)
                tmp.delete()
                val snap = try { WebViewControl.snapshot(getApplication<Application>().packageManager) } catch (_: Exception) { WebViewSnapshot() }
                Triple(r, snap, bytes.size)
            }
            if (msg is Triple<*, *, *>) {
                val r = msg.first as id.tntwindow.editor.data.ShellResult
                val snap = msg.second as WebViewSnapshot
                _state.value = _state.value.copy(
                    busy = false,
                    webView = snap,
                    message = if (r.ok) "WebView APK 已安装。再点「应用并切换」。" else "安装失败：" + r.err.ifBlank { r.out },
                )
            } else {
                _state.value = _state.value.copy(busy = false, message = msg as String)
            }
        }
    }

    private fun persistWebView(cfg: WebViewConfig) {
        webViewStore.save(cfg)
        val r = webViewStore.push()
        _state.value = _state.value.copy(
            webViewInject = cfg.inject,
            message = if (r.ok) "已写入注入配置。第一次或改模块后要重启手机。" else "本地已保存，写入系统失败：" + r.err.ifBlank { r.out },
        )
    }

    private fun fail(msg: String): Boolean {
        _state.value = _state.value.copy(busy = false, message = msg, askRestart = false)
        return false
    }
}

