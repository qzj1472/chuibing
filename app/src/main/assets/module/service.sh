#!/system/bin/sh
MODDIR=${0%/*}
setprop persist.easycast.show_overlay_display 0
resetprop persist.easycast.show_overlay_display 0
settings put global overlay_display_devices ""
settings delete global overlay_display_devices
rm -f /data/system/tnt_overlay_on
settings put global package_verifier_enable 0
settings put global verifier_verify_adb_installs 0
settings put global package_verifier_user_consent -1
settings put global adb_install_need_confirm 0
CFG=/data/adb/ap/package_config
PIN=$MODDIR/package_config.pin
if [ ! -s "$CFG" ] && [ -s "$PIN" ]; then
  cp "$PIN" "$CFG"
  chmod 600 "$CFG"
fi