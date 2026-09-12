#!/system/bin/sh
MODDIR=${0%/*}
CFG=/data/adb/ap/package_config
PIN=/data/adb/ap/chuibing_pin
PIN2=$MODDIR/package_config.pin
mkdir -p /data/adb/ap
if [ ! -s "$PIN" ] && [ -s "$PIN2" ]; then
  touch "$PIN"
  cat "$PIN2" > "$PIN"
  chmod 600 "$PIN"
fi
if [ ! -s "$CFG" ]; then
  SRC=$PIN
  [ -s "$SRC" ] || SRC=$PIN2
  if [ -s "$SRC" ]; then
    touch "$CFG"
    cat "$SRC" > "$CFG"
    chmod 600 "$CFG"
  fi
fi
