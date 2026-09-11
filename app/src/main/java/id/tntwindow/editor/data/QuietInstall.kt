package id.tntwindow.editor.data

object QuietInstall {
    fun apply(): ShellResult {
        val cmds = listOf(
            "settings put global package_verifier_enable 0",
            "settings put global verifier_verify_adb_installs 0",
            "settings put global package_verifier_user_consent -1",
            "settings put global adb_install_need_confirm 0",
        )
        var last = ShellResult(0, "", "")
        for (c in cmds) last = RootAccess.su(c)
        return last
    }

    fun enabled(): Boolean {
        val r = RootAccess.su("settings get global package_verifier_enable")
        return r.out.trim() == "0"
    }
}