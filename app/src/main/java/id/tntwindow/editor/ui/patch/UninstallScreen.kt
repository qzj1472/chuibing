package id.tntwindow.editor.ui.patch

import androidx.compose.runtime.Composable
import id.tntwindow.editor.TntViewModel

@Composable
fun UninstallScreen(vm: TntViewModel, onBack: () -> Unit) {
    UninstallPickScreen(vm, onBack)
}
