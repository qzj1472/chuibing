package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.BuiltinKeys
import id.tntwindow.editor.domain.KeyAction
import id.tntwindow.editor.domain.KeyBinding
import id.tntwindow.editor.domain.KeyCombo
import id.tntwindow.editor.ui.components.Panel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.RowSwitch
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsNavRow
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMapScreen(
    vm: TntViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAction: (String, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.keyMap
    var expandKey by remember { mutableStateOf<String?>(null) }
    var expandCombo by remember { mutableStateOf<String?>(null) }
    var pendingCombo by remember { mutableStateOf<KeyCombo?>(null) }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("按键映射") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.resetKeyMap() }) { Text("重置") }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
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
            SettingsGroup {
                SettingsSwitchRow(
                    title = "启用按键映射",
                    checked = cfg.enabled,
                    onChange = { vm.setKeyMapEnabled(it) },
                    divider = false,
                    subtitle = "拦系统按键，改完立刻生效。需 LSPosed 对 android 生效。",
                )
            }
            SmallTitle("按键")
            cfg.keys.forEach { bind ->
                KeyBindCard(
                    bind = bind,
                    expanded = expandKey == bind.id,
                    onToggle = { expandKey = if (expandKey == bind.id) null else bind.id },
                    onEnabled = { vm.setKeyEnabled(bind.id, it) },
                    onAction = onAction,
                )
            }
            SmallTitle("组合键")
            if (cfg.combos.isEmpty()) {
                Panel {
                    Text("组合键用下面列表勾选，勾选顺序就是按下顺序。", color = scheme.onSurfaceVariant)
                }
            }
            cfg.combos.forEach { item ->
                ComboCard(
                    item = item,
                    expanded = expandCombo == item.id,
                    onToggle = { expandCombo = if (expandCombo == item.id) null else item.id },
                    onEnabled = { vm.setComboEnabled(item.id, it) },
                    onName = { vm.setComboName(item.id, it) },
                    onOrdered = { vm.setComboOrdered(item.id, it) },
                    onTimeout = { vm.setComboTimeout(item.id, it) },
                    onToggleKey = { vm.toggleComboKey(item.id, it) },
                    onReverse = { vm.reverseComboKeys(item.id) },
                    onAction = { onAction("combo", item.id) },
                    onDelete = { pendingCombo = item },
                )
            }
            PrimaryButton("添加组合键", onClick = { vm.addCombo() }, modifier = Modifier.fillMaxWidth())
        }
    }
    val delCombo = pendingCombo
    if (delCombo != null) {
        AlertDialog(
            onDismissRequest = { pendingCombo = null },
            title = { Text("删除组合键") },
            text = { Text("删除「" + delCombo.name + "」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeCombo(delCombo.id)
                    pendingCombo = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingCombo = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun KeyBindCard(
    bind: KeyBinding,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onAction: (String, String) -> Unit,
) {
    val slots = visibleSlots(bind)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
                Text(bind.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    slots.joinToString(" · ") { slotTitle(it.first) + " " + it.second.summary() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = bind.enabled, onCheckedChange = onEnabled)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                slots.forEachIndexed { i, slot ->
                    SettingsNavRow(
                        slotTitle(slot.first),
                        slot.second.summary(),
                        { onAction(slot.first, bind.id) },
                        divider = i != slots.lastIndex,
                    )
                }
            }
        }
    }
}

private fun visibleSlots(bind: KeyBinding): List<Pair<String, KeyAction>> {
    return when (bind.id) {
        BuiltinKeys.POWER -> listOf("double" to bind.doubleClick, "triple" to bind.tripleClick)
        BuiltinKeys.VOL_UP, BuiltinKeys.VOL_DOWN -> listOf("double" to bind.doubleClick, "long" to bind.longPress)
        BuiltinKeys.BACK, BuiltinKeys.HOME, BuiltinKeys.RECENTS, BuiltinKeys.FINGERPRINT -> listOf("click" to bind.click, "double" to bind.doubleClick)
        else -> listOf("click" to bind.click, "double" to bind.doubleClick, "long" to bind.longPress)
    }
}

private fun slotTitle(slot: String): String = when (slot) {
    "click" -> "单击"
    "double" -> "双击"
    "long" -> "长按"
    "triple" -> "三击"
    else -> slot
}

@Composable
private fun ComboCard(
    item: KeyCombo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onOrdered: (Boolean) -> Unit,
    onTimeout: (Int) -> Unit,
    onToggleKey: (Int) -> Unit,
    onReverse: () -> Unit,
    onAction: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(item.id, item.name) { mutableStateOf(item.name) }
    var timeout by remember(item.id, item.timeoutMs) { mutableFloatStateOf(item.timeoutMs.toFloat()) }
    val builtins = remember { BuiltinKeys.defs() }
    DisposableEffect(item.id) {
        onDispose {
            if (name != item.name) onName(name)
        }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = {
                if (expanded && name != item.name) onName(name)
                onToggle()
            })) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (item.codes.isEmpty()) "还没有键" else item.keysLabel()) + " · " + item.action.summary(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabled)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (item.ordered) "按顺序" else "不按顺序", onClick = { onOrdered(!item.ordered) }, modifier = Modifier.weight(1f))
                PrimaryButton("反转顺序", onClick = onReverse, modifier = Modifier.weight(1f), enabled = item.codes.size >= 2)
            }
            Text("间隔 " + timeout.toInt() + " ms", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = timeout,
                onValueChange = { timeout = it },
                valueRange = 120f..3000f,
                onValueChangeFinished = { onTimeout(timeout.toInt()) },
            )
            SmallTitle("按键")
            Text("点整行勾选，勾选顺序即按下顺序。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            builtins.forEach { key ->
                val idx = item.codes.indexOf(key.code)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggleKey(key.code) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = idx >= 0, onCheckedChange = null)
                    Text(key.name, modifier = Modifier.weight(1f))
                    if (idx >= 0) {
                        Text((idx + 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SettingsGroup {
                SettingsNavRow("动作", item.action.summary(), onAction, divider = false)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (name != item.name) onName(name)
                    onDelete()
                }) { Text("删除") }
            }
        }
    }
}

@Composable
fun KeyMapSettingsScreen(vm: TntViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.keyMap
    var longMs by remember(cfg.longMs) { mutableFloatStateOf(cfg.longMs.toFloat()) }
    var doubleMs by remember(cfg.doubleMs) { mutableFloatStateOf(cfg.doubleMs.toFloat()) }
    var comboMs by remember(cfg.comboMs) { mutableFloatStateOf(cfg.comboMs.toFloat()) }
    SettingsPage("按键设置", onBack, onReset = { vm.resetKeyMap() }) {
        SettingsGroup {
            SettingsSwitchRow("按键震动", cfg.vibrate, { vm.setKeyMapVibrate(it) }, divider = false)
        }
        Panel {
            Text("长按时间  " + longMs.toInt() + " ms")
            Slider(
                value = longMs,
                onValueChange = { longMs = it },
                valueRange = 180f..1500f,
                onValueChangeFinished = { vm.setKeyMapTiming(longMs = longMs.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("双击间隔  " + doubleMs.toInt() + " ms")
            Slider(
                value = doubleMs,
                onValueChange = { doubleMs = it },
                valueRange = 120f..800f,
                onValueChangeFinished = { vm.setKeyMapTiming(doubleMs = doubleMs.toInt()) },
            )
            Spacer(Modifier.height(8.dp))
            Text("组合键默认间隔  " + comboMs.toInt() + " ms")
            Slider(
                value = comboMs,
                onValueChange = { comboMs = it },
                valueRange = 120f..3000f,
                onValueChangeFinished = { vm.setKeyMapTiming(comboMs = comboMs.toInt()) },
            )
        }
    }
}
