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
            SmallTitle("系统能力")
            SettingsGroup {
                SettingsNavRow("语音模块", voiceSub, { onOpen("voice") })
                SettingsNavRow("系统 WebView", webSub, { onOpen("settings/webview") }, divider = false)
            }
        }
    }
}

