#!/system/bin/sh
MOD=/data/adb/modules/chuibing_apatch
FLAG=/data/adb/ap/chuibing_selinux
if [ -f "$FLAG" ]; then
  setenforce 0 2>/dev/null
  echo 0 > /sys/fs/selinux/enforce 2>/dev/null
fi
[ -f "$MOD/disable" ] && exit 0
[ -f "$MOD/service.sh" ] || exit 0
exec sh "$MOD/service.sh"
