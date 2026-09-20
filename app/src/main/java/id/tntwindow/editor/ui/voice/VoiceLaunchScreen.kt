package id.tntwindow.editor.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.InstalledApp
import id.tntwindow.editor.domain.VoiceAlias
import id.tntwindow.editor.domain.VoiceCommand
import id.tntwindow.editor.domain.VoiceEngineInfo
import id.tntwindow.editor.ui.components.AppIcon
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.RowSwitch
import id.tntwindow.editor.ui.theme.Mono
import id.tntwindow.editor.voice.VoiceAsrPacks
import id.tntwindow.editor.voice.VoiceListen
import id.tntwindow.editor.voice.VoiceModelDownload
import id.tntwindow.editor.xposed.VoiceSpeak
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLaunchScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    var phrase by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("launch") }
    var target by remember { mutableStateOf("") }
    var targetLabel by remember { mutableStateOf("") }
    var pickApp by remember { mutableStateOf(false) }
    var pickAlias by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<VoiceCommand?>(null) }
    var aliasSpoken by remember { mutableStateOf("") }
    var aliasPkg by remember { mutableStateOf("") }
    var aliasLabel by remember { mutableStateOf("") }
    var pendingAlias by remember { mutableStateOf<VoiceAlias?>(null) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var pickRec by remember { mutableStateOf(false) }
    var pickTts by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val listener = remember { VoiceListen(context) }
    DisposableEffect(Unit) {
        onDispose { listener.stop() }
    }
    val asrTick by VoiceModelDownload.tick.collectAsState()
    LaunchedEffect(Unit) {
        VoiceModelDownload.sync(context)
    }
    LaunchedEffect(asrTick) {
        vm.refreshVoiceEngines()
    }
    val startListen: () -> Unit = {
        listening = true
        heard = "正在听…"
        listener.start(
            state.voiceRecognizer,
            onPartial = { heard = it },
            onFinal = { text ->
                listening = false
                heard = text
                test = text
                vm.previewVoice(text)
            },
            onError = { err ->
                listening = false
                heard = err
            },
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListen() else heard = "没有麦克风权限"
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("语音模块") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.moduleActive) {
                Panel(accent = true) {
                    Text("模块未注入", color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                }
            }
            Panel {
                Text("语音引擎", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { pickRec = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("听写引擎")
                        Text(
                            engineLabel(state.voiceRecognizer, state.voiceRecognizers),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("›", color = scheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { pickTts = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("播报引擎")
                        Text(
                            engineLabel(state.voiceTts, state.voiceTtsEngines),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("›", color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                RowSwitch("语音播报", state.voiceSpeak) { vm.setVoiceSpeak(it) }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "试听播报",
                    onClick = { VoiceSpeak.say(context, "这是当前播报引擎") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.voiceSpeak,
                )
            }
            Panel {
                Text("自定义指令", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("口令") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == "launch", onClick = { action = "launch" }, label = { Text("打开应用") })
                    FilterChip(selected = action == "freeform", onClick = { action = "freeform" }, label = { Text("小窗打开") })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == "search", onClick = { action = "search" }, label = { Text("搜索词条") })
                    FilterChip(selected = action == "shell", onClick = { action = "shell" }, label = { Text("运行命令") })
                }
                Spacer(Modifier.height(8.dp))
                if (action == "launch" || action == "freeform") {
                    Text(if (target.isBlank()) "还没选应用" else targetLabel + "\n" + target, style = if (target.isBlank()) MaterialTheme.typography.bodyMedium else Mono)
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("选择应用", onClick = { pickApp = true }, modifier = Modifier.fillMaxWidth())
                } else if (action == "search") {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("要搜索的词") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("shell 命令") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "添加指令",
                    onClick = {
                        vm.addVoiceCommand(phrase, action, target)
                        phrase = ""
                        if (action == "launch" || action == "freeform") {
                            target = ""
                            targetLabel = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phrase.isNotBlank() && target.isNotBlank(),
                )
            }
            Panel {
                Text("自定义别名", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasSpoken,
                    onValueChange = { aliasSpoken = it },
                    label = { Text("口头叫法") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                if (aliasLabel.isNotBlank()) {
                    Text(aliasLabel + "  " + aliasPkg, style = Mono, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton("选择对应应用", onClick = { pickAlias = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "添加别名",
                    onClick = {
                        vm.addVoiceAlias(aliasSpoken, aliasPkg, aliasLabel)
                        aliasSpoken = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = aliasSpoken.isNotBlank() && aliasPkg.isNotBlank(),
                )
            }
            Panel {
                Text("已有指令和别名", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.voiceCommands.isEmpty() && state.voiceAliases.isEmpty()) {
                    Text("还没有自定义内容。", color = scheme.onSurfaceVariant)
                }
                state.voiceCommands.forEach { cmd ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cmd.phrase, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (cmd.action) {
                                    "shell" -> "命令 "
                                    "search" -> "搜索 "
                                    "freeform" -> "小窗打开 "
                                    else -> "打开 "
                                } + cmd.target,
                                style = Mono,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pendingDelete = cmd }) { Text("删除") }
                    }
                }
                state.voiceAliases.forEach { alias ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(alias.spoken, style = MaterialTheme.typography.titleSmall)
                            Text(alias.label + "  " + alias.packageName, style = Mono, color = scheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { pendingAlias = alias }) { Text("删除") }
                    }
                }
            }
            Panel {
                Text("试匹配", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    if (listening) "正在听，点一下停止" else "对着麦克风说",
                    onClick = {
                        if (listening) {
                            listener.finish()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) startListen() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (heard.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(heard, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = test,
                    onValueChange = { test = it },
                    label = { Text("手打核对（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("用手打的再试一次", onClick = { vm.previewVoice(test) }, modifier = Modifier.fillMaxWidth(), enabled = test.isNotBlank())
                val msg = state.message
                if (!msg.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
        if (pickRec) {
        RecEngineDialog(
            engines = state.voiceRecognizers,
            onDismiss = { pickRec = false },
            onPick = { id ->
                vm.setVoiceRecognizer(id)
                pickRec = false
            },
            onDownload = { id -> vm.startSherpaDownload(id) },
        )
    }
    if (pickTts) {
        EnginePickDialog(
            title = "播报引擎",
            engines = state.voiceTtsEngines,
            onDismiss = { pickTts = false },
            onPick = { id ->
                vm.setVoiceTts(id)
                pickTts = false
            },
        )
    }
    if (pickApp) {
        AppPickDialog(
            installed = state.installed,
            onDismiss = { pickApp = false },
            onPick = { app ->
                target = app.packageName
                targetLabel = app.label
                pickApp = false
            },
        )
    }
    if (pickAlias) {
        AppPickDialog(
            installed = state.installed,
            onDismiss = { pickAlias = false },
            onPick = { app ->
                aliasPkg = app.packageName
                aliasLabel = app.label
                pickAlias = false
            },
        )
    }
    val aliasDel = pendingAlias
    if (aliasDel != null) {
        AlertDialog(
            onDismissRequest = { pendingAlias = null },
            title = { Text("删除别名") },
            text = { Text("删除「" + aliasDel.spoken + "」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeVoiceAlias(aliasDel.id)
                    pendingAlias = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAlias = null }) { Text("取消") }
            },
        )
    }
    val del = pendingDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除指令") },
            text = { Text("删除「" + del.phrase + "」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeVoiceCommand(del.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun engineLabel(id: String, list: List<VoiceEngineInfo>): String {
    if (id.isBlank()) return "系统默认"
    if (id == VoiceAsrPacks.ZIPFORMER) return "Zipformer 快档"
    if (id == VoiceAsrPacks.SENSEVOICE) return "SenseVoice 口音档"
    return list.firstOrNull { it.id == id }?.label ?: id
}


@Composable
private fun RecEngineDialog(
    engines: List<VoiceEngineInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    val context = LocalContext.current
    val tick by VoiceModelDownload.tick.collectAsState()
    val refresh = tick
    LaunchedEffect(Unit) {
        while (true) {
            VoiceModelDownload.sync(context)
            delay(1000)
        }
    }
    val packs = VoiceAsrPacks.all
    val items = listOf(VoiceEngineInfo("", "系统默认")) + engines.filter { !it.id.startsWith("sherpa:") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.72f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("听写引擎", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        val st = VoiceModelDownload.stateText(context, pack)
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                if (VoiceModelDownload.isReady(context, pack)) onPick(pack.id) else onDownload(pack.id)
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(pack.title)
                            Text(st, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(items, key = { it.id.ifBlank { "_" } }) { eng ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(eng.id) }.padding(vertical = 10.dp),
                        ) {
                            Text(eng.label)
                            if (eng.id.isNotBlank()) {
                                Text(eng.id, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun EnginePickDialog(
    title: String,
    engines: List<VoiceEngineInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val items = listOf(VoiceEngineInfo("", "系统默认")) + engines
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.72f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.id.ifBlank { "_" } }) { eng ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(eng.id) }.padding(vertical = 10.dp),
                        ) {
                            Text(eng.label)
                            if (eng.id.isNotBlank()) {
                                Text(eng.id, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickDialog(installed: List<InstalledApp>, onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(installed, tab, query) {
        installed
            .filter { app -> if (tab == 0) !app.system else app.system }
            .filter { app -> app.hasLauncher }
            .filter { app ->
                query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true)
            }
            .sortedBy { it.label }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择应用", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("用户") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("系统") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索名称或包名") },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, 36)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(app.label)
                                Text(app.packageName, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}


