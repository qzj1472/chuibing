package id.tntwindow.editor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import id.tntwindow.editor.BuildConfig
import id.tntwindow.editor.EditorState
import id.tntwindow.editor.ui.components.IssuesDialog
import id.tntwindow.editor.ui.components.PrimaryButton
import id.tntwindow.editor.ui.components.SettingsGroup
import id.tntwindow.editor.ui.components.SettingsInfoRow
import id.tntwindow.editor.ui.components.SmallTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: EditorState,
    onSave: () -> Unit,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    onSetMode: (Int) -> Unit,
    onTouchpad: () -> Unit,
) {
    val st = state.status
    val scheme = MaterialTheme.colorScheme
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
    val showTouchpad = st.pcMode == 1 || st.pcMode == 2
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("锤柄  " + BuildConfig.VERSION_NAME) },
                actions = {
                    IconButton(onClick = onSave, enabled = st.editingAllowed) {
                        Icon(Icons.Outlined.Save, contentDescription = "保存")
                    }
                    IconButton(onClick = onRestart, enabled = st.rootOk) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "热切换 TNT")
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
                SettingsInfoRow("配置文件", if (st.fileOk) "已找到" else "缺失", divider = true)
                SettingsInfoRow("配置数量", st.appCount.toString(), divider = false)
            }
            SmallTitle("TNT")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("镜像", st.pcMode == 0, st.rootOk, { onSetMode(0) }, Modifier.weight(1f))
                ModeButton("桌面", st.pcMode == 1, st.rootOk, { onSetMode(1) }, Modifier.weight(1f))
                ModeButton("分享", st.pcMode == 2, st.rootOk, { onSetMode(2) }, Modifier.weight(1f))
            }
            if (showTouchpad) {
                PrimaryButton("虚拟触摸板", onClick = onTouchpad, modifier = Modifier.fillMaxWidth(), enabled = st.rootOk)
            }
            SmallTitle("补丁")
            SettingsGroup {
                SettingsInfoRow("APatch 守护", if (state.apatchProtect) "开" else "关", divider = true)
                SettingsInfoRow("语音", if (state.moduleActive) "已启用" else "未启用", divider = true)
                SettingsInfoRow("WebView", webVer, divider = true, subtitleMaxLines = 1)
                SettingsInfoRow("锁桌面", if (state.homeLockEnabled) "开" else "关", divider = false)
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

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) scheme.primary else scheme.surface,
            contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
            disabledContainerColor = scheme.surfaceVariant,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
    ) { Text(text) }
}


