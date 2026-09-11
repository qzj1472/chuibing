package id.tntwindow.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.BuildConfig
import id.tntwindow.editor.EditorState
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SettingsNavRow
import id.tntwindow.editor.ui.components.SmallTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: EditorState,
    vm: TntViewModel,
    onOpen: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val themeLabel = when (state.themeMode) {
        "dark" -> "深色"
        "light" -> "浅色"
        else -> "跟随系统"
    }
    val defaultCol = state.collections.firstOrNull { it.id == vm.prefs.defaultCollectionId }?.name ?: "未选"
    var themeMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(title = { Text("设置") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background))
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallTitle("界面")
            SettingsGroup {
                Box {
                    SettingsInfoRow("外观", themeLabel, onClick = { themeMenu = true }, trailing = "›", divider = false)
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        listOf("system" to "跟随系统", "dark" to "深色", "light" to "浅色").forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.setThemeMode(id)
                                    themeMenu = false
                                },
                            )
                        }
                    }
                }
            }
            SmallTitle("窗口")
            SettingsGroup {
                SettingsNavRow("默认添加", if (vm.prefs.autoAddEnabled) "开 · " + defaultCol else "关", { onOpen("settings/defaults") })
                SettingsNavRow("TNT", "锁桌面 · overlay · 热切换", { onOpen("settings/tnt") })
                SettingsNavRow("编辑", "显示范围", { onOpen("settings/edit") }, divider = false)
            }
            SmallTitle("数据")
            SettingsGroup {
                SettingsNavRow("备份与恢复", state.backups.size.toString() + " 份", { onOpen("settings/backup") }, divider = false)
            }
            SmallTitle("关于")
            SettingsGroup {
                SettingsNavRow("关于锤子补丁", BuildConfig.VERSION_NAME, { onOpen("settings/about") }, divider = false)
            }
        }
    }
}
