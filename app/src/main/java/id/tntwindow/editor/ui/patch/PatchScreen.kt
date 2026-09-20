package id.tntwindow.editor.ui.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.EditorState
import id.tntwindow.editor.TntViewModel
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.components.SettingsNavRow
import id.tntwindow.editor.ui.components.SettingsSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(
    state: EditorState,
    vm: TntViewModel,
    onOpen: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val web = state.webView
    val webSub = if (web.currentPackage.isBlank()) {
        "系统 WebView"
    } else {
        val name = web.providers.firstOrNull { it.current }?.label ?: web.currentPackage
        name + "  " + web.currentVersion
    }
    val voiceSub = state.voiceCommands.size.toString() + " 条指令"
    val coreSub = state.corePatch.enabledCount().toString() + " 项已开"
    val keySub = if (state.keyMap.enabled) {
        state.keyMap.keys.size.toString() + " 键 · " + state.keyMap.combos.size + " 组合"
    } else {
        "关闭"
    }
    val gestureSub = if (state.gesture.enabled) {
        state.gesture.enabledCount().toString() + " 项"
    } else {
        "关闭"
    }
    val freeformSub = if (state.freeform.sidebar) {
        state.freeform.apps.size.toString() + " 个应用"
    } else {
        "侧边栏关闭"
    }
    val takeoverSub = if (state.install.enabled) {
        val name = if (state.install.pkg.isBlank()) "已开" else state.install.pkg
        if (state.install.shareUninstall) name else {
            val u = if (state.uninstall.enabled) {
                if (state.uninstall.pkg.isBlank()) "卸载已开" else "卸载 " + state.uninstall.pkg
            } else "卸载关闭"
            name + " · " + u
        }
    } else if (!state.install.shareUninstall && state.uninstall.enabled) {
        if (state.uninstall.pkg.isBlank()) "卸载已开" else "卸载 " + state.uninstall.pkg
    } else {
        "关闭"
    }
    val rotationSub = if (!state.rotation.enabled) {
        "关闭"
    } else if (state.rotation.locked) {
        when (state.rotation.rotation) {
            1 -> "锁定横屏"
            2 -> "锁定倒置竖屏"
            3 -> "锁定倒置横屏"
            else -> "锁定竖屏"
        }
    } else {
        "跟随传感器"
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(title = { Text("补丁") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background))
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
            SmallTitle("窗口")
            SettingsGroup {
                SettingsNavRow("TNT 窗口", state.collections.size.toString() + " 个合集", { onOpen("collections") })
                SettingsNavRow("视频特例", state.videos.size.toString() + " 条", { onOpen("videos") })
                SettingsNavRow("小窗", freeformSub, { onOpen("patch/freeform") }, divider = false)
            }
            SmallTitle("交互")
            SettingsGroup {
                SettingsNavRow("按键映射", keySub, { onOpen("patch/keys") })
                SettingsNavRow("全面屏手势", gestureSub, { onOpen("patch/gesture") })
                SettingsNavRow("语音模块", voiceSub, { onOpen("voice") }, divider = false)
            }
            SmallTitle("显示")
            SettingsGroup {
                SettingsNavRow("屏幕方向", rotationSub, { onOpen("patch/rotation") })
                SettingsSwitchRow("任务栏反色", state.barTint.nav, { vm.setBarTintNav(it) }, subtitle = "图标随底色变化")
                SettingsNavRow("系统 WebView", webSub, { onOpen("settings/webview") }, divider = false)
            }
            SmallTitle("安装")
            SettingsGroup {
                SettingsNavRow("安装卸载接管", takeoverSub, { onOpen("patch/install") }, divider = false)
            }
            SmallTitle("权限")
            SettingsGroup {
                SettingsSwitchRow("APatch 守护", state.apatchProtect, { vm.setApatchProtect(it) })
                SettingsSwitchRow("关闭 SELinux", state.selinuxOff, { vm.setSelinuxOff(it) })
                SettingsNavRow("核心破解", coreSub, { onOpen("patch/corepatch") }, divider = false)
            }
        }
    }
}