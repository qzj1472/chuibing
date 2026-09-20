package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.domain.CorePatchConfig
import id.tntwindow.editor.domain.CorePatchSwitches
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.SettingsPage

@Composable
fun CorePatchScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.corePatch
    var askPreSig by remember { mutableStateOf(false) }
    SettingsPage("核心破解", onBack, onReset = { vm.resetCorePatch() }) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("三项", { vm.setCorePatchPreset(3) }, Modifier.weight(1f))
            PrimaryButton("五项", { vm.setCorePatchPreset(5) }, Modifier.weight(1f))
            PrimaryButton("七项", { vm.setCorePatchPreset(7) }, Modifier.weight(1f))
        }
        Text(
            "开关立刻写入。安装相关项要等 LSPosed 对本模块的 android 作用域生效，必要时重启。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        SmallTitle("软件包管理")
        SettingsGroup {
            CorePatchSwitches.ALL.forEachIndexed { i, item ->
                SettingsSwitchRow(
                    title = item.title,
                    checked = cfg.get(item.key),
                    onChange = { on ->
                        if (item.key == CorePatchConfig.KEY_PRESIG && on) {
                            askPreSig = true
                        } else {
                            vm.setCorePatch(item.key, on)
                        }
                    },
                    divider = i != CorePatchSwitches.ALL.lastIndex,
                    subtitle = item.summary,
                )
            }
        }
        Text(
            "危险项默认关闭。绕过共享用户签名时会同时打开“禁用 APK 签名验证”。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (askPreSig) {
        AlertDialog(
            onDismissRequest = { askPreSig = false },
            title = { Text("非常危险") },
            text = { Text("开启后，任意安装包都可以覆盖已装应用。小米/红米不要开，可能导致无法开机。") },
            confirmButton = {
                TextButton(onClick = {
                    askPreSig = false
                    vm.setCorePatch(CorePatchConfig.KEY_PRESIG, true)
                }) { Text("仍然开启") }
            },
            dismissButton = { TextButton(onClick = { askPreSig = false }) { Text("取消") } },
        )
    }
}
