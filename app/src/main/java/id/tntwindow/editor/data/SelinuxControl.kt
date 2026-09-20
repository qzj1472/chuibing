package id.tntwindow.editor.data

object SelinuxControl {
    const val FLAG = "/data/adb/ap/chuibing_selinux"

    fun apply(on: Boolean): Boolean {
        val cmd = if (on) {
            "mkdir -p /data/adb/ap; echo 1 > '" + FLAG + "'; chmod 600 '" + FLAG + "'; setenforce 0; echo 0 > /sys/fs/selinux/enforce"
        } else {
            "rm -f '" + FLAG + "'; setenforce 1"
        }
        RootAccess.su(cmd)
        return permissive() == on
    }

    fun permissive(): Boolean {
        val t = RootAccess.su("getenforce").out.trim()
        return t.equals("Permissive", ignoreCase = true) || t == "0"
    }
}
