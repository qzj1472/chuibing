#!/system/bin/sh
MODDIR=${0%/*}
PIDF=/data/local/tmp/chuibing_apatch.pid
CFG=/data/adb/ap/package_config
PIN=/data/adb/ap/chuibing_pin
PIN2=$MODDIR/package_config.pin
OUT=/data/local/tmp/chuibing_apatch.cfg
LOG=/data/local/tmp/chuibing_apatch.log
SCTX=u:r:magisk:s0
HEADER=pkg,exclude,allow,uid,to_uid,sctx

log() {
  echo "$(date '+%F %T') $*" >> "$LOG"
}

pin_file() {
  if [ -s "$PIN" ]; then
    echo "$PIN"
  elif [ -s "$PIN2" ]; then
    echo "$PIN2"
  fi
}

pin_line() {
  want=$1
  src=$2
  while IFS= read -r pl || [ -n "$pl" ]; do
    pl=$(printf '%s' "$pl" | tr -d '\r')
    p=${pl%%,*}
    [ -z "$p" ] && continue
    [ "$p" = "pkg" ] && continue
    if [ "$p" = "$want" ]; then
      printf '%s\n' "$pl"
      return 0
    fi
  done < "$src"
  return 1
}

force_line() {
  pkg=$1
  live=$2
  pin=$3
  uid=$(echo "$live" | cut -d, -f4)
  [ -z "$uid" ] && uid=$(echo "$pin" | cut -d, -f4)
  to=$(echo "$pin" | cut -d, -f5)
  [ -z "$to" ] && to=$(echo "$live" | cut -d, -f5)
  [ -z "$to" ] && to=0
  sctx=$(echo "$pin" | cut -d, -f6)
  [ -z "$sctx" ] && sctx=$(echo "$live" | cut -d, -f6)
  [ -z "$sctx" ] && sctx=$SCTX
  echo "$pkg,0,1,$uid,$to,$sctx"
}

write_keep() {
  src=$1
  dst=$2
  touch "$dst"
  cat "$src" > "$dst"
  chmod 600 "$dst"
}

merge() {
  src=$(pin_file)
  [ -n "$src" ] || return 0
  mkdir -p /data/adb/ap
  if [ ! -s "$CFG" ]; then
    write_keep "$src" "$CFG"
    log seed
    return 0
  fi
  echo "$HEADER" > "$OUT"
  seen="|"
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(printf '%s' "$line" | tr -d '\r')
    pkg=${line%%,*}
    [ -z "$pkg" ] && continue
    [ "$pkg" = "pkg" ] && continue
    pin=$(pin_line "$pkg" "$src")
    if [ -n "$pin" ]; then
      force_line "$pkg" "$line" "$pin" >> "$OUT"
      seen="$seen$pkg|"
    else
      echo "$line" >> "$OUT"
    fi
  done < "$CFG"
  while IFS= read -r pin || [ -n "$pin" ]; do
    pin=$(printf '%s' "$pin" | tr -d '\r')
    pkg=${pin%%,*}
    [ -z "$pkg" ] && continue
    [ "$pkg" = "pkg" ] && continue
    case "$seen" in
      *"|$pkg|"*) continue ;;
    esac
    force_line "$pkg" "" "$pin" >> "$OUT"
    seen="$seen$pkg|"
  done < "$src"
  if ! cmp -s "$OUT" "$CFG"; then
    write_keep "$OUT" "$CFG"
    log restore
  fi
  rm -f "$OUT"
}

if [ -f "$MODDIR/disable" ]; then
  old=$(cat "$PIDF" 2>/dev/null)
  [ -n "$old" ] && kill "$old" 2>/dev/null
  rm -f "$PIDF"
  exit 0
}

if [ -z "$CHUIBING_APATCH_DAEMON" ]; then
  old=$(cat "$PIDF" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    exit 0
  fi
  export CHUIBING_APATCH_DAEMON=1
  if command -v nohup >/dev/null 2>&1 && command -v setsid >/dev/null 2>&1; then
    nohup setsid sh "$0" >/dev/null 2>&1 &
  else
    trap '' HUP
    sh "$0" >/dev/null 2>&1 &
  fi
  exit 0
fi

echo $$ > "$PIDF"
log "start pid=$$"
while [ ! -f "$MODDIR/disable" ]; do
  merge
  sleep 2
done
rm -f "$PIDF"
log stop
