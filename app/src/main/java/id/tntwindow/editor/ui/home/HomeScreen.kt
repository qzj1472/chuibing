package id.tntwindow.editor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.EditorState
import id.tntwindow.editor.ui.components.IssuesDialog
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SmallTitle
import id.tntwindow.editor.ui.settings.installedVersionName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: EditorState,
    onRetry: () -> Unit,
) {
    val st = state.status
    val scheme = MaterialTheme.colorScheme
    val version = installedVersionName(LocalContext.current)
    var issuesOpen by remember { mutableStateOf(false) }
    val env = when {
        !st.rootOk -> "Root 未开启"
        state.moduleActive -> "已就绪"
        state.lsposed -> "模块未注入"
        else -> "LSPosed 未开启"
    }
    val webVer = state.webView.currentVersion.ifBlank {
        state.webView.providers.firstOrNull { it.current }?.versionName
            ?: state.webView.currentPackage.ifBlank { "未读到" }
    }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("锤柄  " + version) },
                actions = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallTitle("状态")
            SettingsGroup {
                SettingsInfoRow("环境", env, divider = true)
                SettingsInfoRow("Root", if (st.rootOk) "已授权" else "未授权", divider = true)
                SettingsInfoRow("模块", if (state.moduleActive) "已注入" else "未注入", divider = false)
            }
            SmallTitle("功能")
            SettingsGroup {
                SettingsInfoRow("全面屏手势", if (state.gesture.enabled) state.gesture.enabledCount().toString() + " 项" else "关", divider = true)
                SettingsInfoRow("按键映射", if (state.keyMap.enabled) state.keyMap.keys.size.toString() + " 键" else "关", divider = true)
                SettingsInfoRow("小窗", if (state.freeform.sidebar) state.freeform.apps.size.toString() + " 个应用" else "关", divider = true)
                SettingsInfoRow("核心破解", state.corePatch.enabledCount().toString() + " 项已开", divider = true)
                SettingsInfoRow("语音", if (state.moduleActive) "已启用" else "未启用", divider = true)
                SettingsInfoRow("WebView", webVer, divider = false, subtitleMaxLines = 1)
            }
            SmallTitle("系统")
            SettingsGroup {
                SettingsInfoRow("APatch 守护", if (state.apatchProtect) "开" else "关", divider = true)
                SettingsInfoRow("SELinux", if (state.selinuxOff) "关闭" else "强制", divider = false)
            }
            if (st.issues.isNotEmpty()) {
                SettingsGroup {
                    SettingsInfoRow(
                        "需要处理",
                        st.issues.size.toString() + " 项",
                        onClick = { issuesOpen = true },
                        trailing = "›",
                        divider = false,
                    )
                }
            }
        }
    }
    if (issuesOpen) {
        IssuesDialog(st.issues, onDismiss = { issuesOpen = false }, onRetry = onRetry, showRetry = !st.editingAllowed)
    }
}
