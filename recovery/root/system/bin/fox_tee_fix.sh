#!/system/bin/sh
# The TEE writes secure-storage files while we are in recovery (Gatekeeper throttle records
# when the pattern is checked, KeyMint data). The recovery SELinux policy does not know the
# vendor types, so new files get "unlabeled" and Android's teed (domain tee, user system) can
# no longer open them: Gatekeeper fails and Android rejects the correct PIN/pattern (seen on
# BV7100). Restore the stock owner and labels (taken from TANK_1_S1_260903, ls -Z).
# usage: fox_tee_fix.sh [once]
LOG=/tmp/fox_tee_fix.log
fix() { # dir label
    [ -d "$1" ] || return 0
    chown -R 1000:1000 "$1" 2>>$LOG
    chcon -R "$2" "$1" 2>>$LOG
}
run() {
    fix /mnt/vendor/persist/t6    u:object_r:tkcore_protect_data_file:s0
    fix /mnt/vendor/protect_f/tee u:object_r:tkcore_protect_data_file:s0
    # only the real (decrypted) /data, not the tmpfs/rootfs directory used before decryption
    if mountpoint -q /data && [ -d /data/vendor/t6 ] && [ "$(stat -c %d /data)" != "$(stat -c %d /)" ]; then
        fix /data/vendor/t6     u:object_r:tkcore_data_file:s0
        fix /data/vendor/t6/app u:object_r:tkcore_spta_file:s0
        [ -f /data/vendor/t6/tkcore.log ] && chcon u:object_r:tkcore_log_file:s0 /data/vendor/t6/tkcore.log 2>>$LOG
    fi
}
if [ "$1" = once ]; then run; sync; echo "once done" >>$LOG; exit 0; fi
echo "fox_tee_fix loop start" >>$LOG
while true; do run; sleep 5; done
