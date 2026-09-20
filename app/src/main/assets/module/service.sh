#!/system/bin/sh
MODDIR=${0%/*}
PIDF=/data/local/tmp/chuibing_apatch.pid
CFG=/data/adb/ap/package_config
PIN=/data/adb/ap/chuibing_pin
PIN2=$MODDIR/package_config.pin
OUT=/data/adb/ap/chuibing_apatch.cfg.tmp
LOCK=/data/adb/ap/chuibing_cfg.lock
LOG=/data/local/tmp/chuibing_apatch.log
SCTX=u:r:magisk:s0
HEADER=pkg,exclude,allow,uid,to_uid,sctx
SELF=id.tntwindow.editor
FLAG=/data/adb/ap/chuibing_selinux

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
  [ -n "$src" ] || return 1
  [ -s "$src" ] || return 1
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
  tmp=$dst.tmp.$$
  cat "$src" > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$dst"
}

self_uid() {
  uid=$(stat -c %u /data/data/$SELF 2>/dev/null)
  if [ -z "$uid" ]; then
    src=$(pin_file)
    [ -n "$src" ] && uid=$(pin_line "$SELF" "$src" | cut -d, -f4)
  fi
  echo "$uid"
}

valid_line() {
  line=$1
  pkg=${line%%,*}
  case "$pkg" in
    *.*) ;;
    *) return 1 ;;
  esac
  case "$line" in
    *,*,*,*,*,*) ;;
    *) return 1 ;;
  esac
  allow=$(echo "$line" | cut -d, -f3)
  case "$allow" in
    0|1) ;;
    *) return 1 ;;
  esac
  return 0
}

need_restore() {
  src=$(pin_file)
  [ -s "$CFG" ] || return 0
  seen="|"
  allows="|"
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(printf '%s' "$line" | tr -d '\r')
    pkg=${line%%,*}
    [ -z "$pkg" ] && continue
    [ "$pkg" = "pkg" ] && continue
    valid_line "$line" || return 0
    case "$seen" in
      *"|$pkg|"*) return 0 ;;
    esac
    seen="$seen$pkg|"
    allow=$(echo "$line" | cut -d, -f3)
    allows="$allows$pkg=$allow|"
  done < "$CFG"
  if [ -n "$src" ]; then
    while IFS= read -r pin || [ -n "$pin" ]; do
      pin=$(printf '%s' "$pin" | tr -d '\r')
      pkg=${pin%%,*}
      [ -z "$pkg" ] && continue
      [ "$pkg" = "pkg" ] && continue
      case "$allows" in
        *"|$pkg=1|"*) ;;
        *) return 0 ;;
      esac
    done < "$src"
  fi
  case "$allows" in
    *"|$SELF=1|"*) ;;
    *) return 0 ;;
  esac
  return 1
}

build_merged() {
  src=$(pin_file)
  suid=$(self_uid)
  echo "$HEADER" > "$OUT"
  seen="|"
  if [ -s "$CFG" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line=$(printf '%s' "$line" | tr -d '\r')
      pkg=${line%%,*}
      [ -z "$pkg" ] && continue
      [ "$pkg" = "pkg" ] && continue
      valid_line "$line" || continue
      case "$seen" in
        *"|$pkg|"*) continue ;;
      esac
      pin=$(pin_line "$pkg" "$src")
      if [ "$pkg" = "$SELF" ]; then
        force_line "$pkg" "$line" "$SELF,0,1,$suid,0,$SCTX" >> "$OUT"
      elif [ -n "$pin" ]; then
        force_line "$pkg" "$line" "$pin" >> "$OUT"
      else
        echo "$line" >> "$OUT"
      fi
      seen="$seen$pkg|"
    done < "$CFG"
  fi
  if [ -n "$src" ]; then
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
  fi
  case "$seen" in
    *"|$SELF|"*) ;;
    *)
      if [ -n "$suid" ]; then
        echo "$SELF,0,1,$suid,0,$SCTX" >> "$OUT"
      fi
      ;;
  esac
}

merge_inner() {
  mkdir -p /data/adb/ap
  if ! need_restore; then
    rm -f "$OUT"
    return 0
  fi
  build_merged
  write_keep "$OUT" "$CFG"
  rm -f "$OUT"
  log restore
}

merge() {
  mkdir -p /data/adb/ap
  (
    flock 9 || exit 0
    merge_inner
  ) 9>"$LOCK"
}

selinux_apply() {
  if [ -f "$FLAG" ]; then
    cur=$(cat /sys/fs/selinux/enforce 2>/dev/null)
    if [ "$cur" != "0" ]; then
      setenforce 0 2>/dev/null
      echo 0 > /sys/fs/selinux/enforce 2>/dev/null
      log selinux0
    fi
  fi
}

if [ "$1" = "merge" ]; then
  merge
  selinux_apply
  exit 0
fi

if [ -f "$MODDIR/disable" ]; then
  old=$(cat "$PIDF" 2>/dev/null)
  [ -n "$old" ] && kill "$old" 2>/dev/null
  rm -f "$PIDF"
  exit 0
fi

old=$(cat "$PIDF" 2>/dev/null)
if [ -n "$old" ] && [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null; then
  merge
  selinux_apply
  exit 0
fi

trap '' HUP
echo $$ > "$PIDF"
log "start pid=$$"
while [ ! -f "$MODDIR/disable" ]; do
  merge
  selinux_apply
  sleep 5
done
rm -f "$PIDF"
log stop
