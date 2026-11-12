#!/system/bin/sh
MODDIR=${0%/*}
LOG=/data/local/tmp/reartouch.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3
(
  while true; do
    [ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 200000 ] && mv -f "$LOG" "$LOG.old"
    echo "== start $(date)" >> "$LOG"
    CLASSPATH="$MODDIR/reartouch.dex" app_process /system/bin --nice-name=reartouch \
      com.muchennik.reartouch.Main >> "$LOG" 2>&1
    echo "== exited $? $(date)" >> "$LOG"
    sleep 5
  done
) &
