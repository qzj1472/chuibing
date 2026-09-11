#!/system/bin/sh
MODDIR=${0%/*}
CFG=/data/adb/ap/package_config
PIN=$MODDIR/package_config.pin
if [ ! -s "$CFG" ] && [ -s "$PIN" ]; then
  cp "$PIN" "$CFG"
  chmod 600 "$CFG"
fi