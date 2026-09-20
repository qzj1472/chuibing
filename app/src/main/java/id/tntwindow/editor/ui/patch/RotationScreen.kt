package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsSwitchRow
import id.tntwindow.editor.ui.settings.SettingsPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotationScreen(vm: TntViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.rotation
    SettingsPage("屏幕方向", onBack, onReset = { vm.resetRotation() }) {
        Text(
            "开启后允许横屏和倒置。默认方向可锁到某一边，也可以跟随传感器。反向会立刻把当前画面转 180 度并锁住，再按一次转回来。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        SettingsGroup {
            SettingsSwitchRow(
                title = "允许横屏和倒置",
                checked = cfg.enabled,
                onChange = { vm.setRotationEnabled(it) },
                subtitle = if (cfg.enabled && cfg.locked) rotationLockLabel(cfg.rotation) else if (cfg.enabled) "跟随传感器" else "",
                divider = false,
            )
        }
        Text("默认方向", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = !cfg.locked, onClick = { vm.setRotationDefault(null) }, label = { Text("跟随传感器") })
            FilterChip(selected = cfg.locked && cfg.rotation == 0, onClick = { vm.setRotationDefault(0) }, label = { Text("竖屏") })
            FilterChip(selected = cfg.locked && cfg.rotation == 1, onClick = { vm.setRotationDefault(1) }, label = { Text("横屏") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = cfg.locked && cfg.rotation == 2, onClick = { vm.setRotationDefault(2) }, label = { Text("倒置竖屏") })
            FilterChip(selected = cfg.locked && cfg.rotation == 3, onClick = { vm.setRotationDefault(3) }, label = { Text("倒置横屏") })
        }
        PrimaryButton(
            if (cfg.locked) "再反向一次" else "反向屏幕",
            onClick = { vm.reverseRotation() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun rotationLockLabel(rotation: Int): String {
    return when (rotation) {
        1 -> "锁定横屏"
        2 -> "锁定倒置竖屏"
        3 -> "锁定倒置横屏"
        else -> "锁定竖屏"
    }
}
