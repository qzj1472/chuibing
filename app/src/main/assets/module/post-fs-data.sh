#!/system/bin/sh
MODDIR=${0%/*}
PIN=/data/adb/ap/chuibing_pin
PIN2=$MODDIR/package_config.pin
FLAG=/data/adb/ap/chuibing_selinux
mkdir -p /data/adb/ap
if [ -f "$FLAG" ]; then
  setenforce 0 2>/dev/null
  echo 0 > /sys/fs/selinux/enforce 2>/dev/null
fi
if [ ! -s "$PIN" ] && [ -s "$PIN2" ]; then
  touch "$PIN"
  cat "$PIN2" > "$PIN"
  chmod 600 "$PIN"
fi
sh "$MODDIR/service.sh" merge
