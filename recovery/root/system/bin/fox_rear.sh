#!/system/bin/sh
# OrangeFox logo on the TANK 1 rear display. Log: /tmp/fox_rear.log
exec >> /tmp/fox_rear.log 2>&1
echo "== fox_rear $(date)"
wait_node() { i=0; while [ ! -e /dev/odm_sub_lcd ] && [ $i -lt $1 ]; do sleep 1; i=$((i+1)); done; }
wait_node 10
if [ ! -e /dev/odm_sub_lcd ]; then
    # rear LCD driver is not in the vendor_boot module list: load it from vendor_dlkm
    echo "no /dev/odm_sub_lcd, trying vendor_dlkm modules"
    mkdir -p /tmp/vdlkm
    for b in /dev/block/by-name/vendor_dlkm /dev/block/mapper/vendor_dlkm_a /dev/block/mapper/vendor_dlkm_b /dev/block/mapper/vendor_dlkm; do
        [ -e "$b" ] && mount -o ro "$b" /tmp/vdlkm && { echo "mounted $b"; break; }
    done
    D=/tmp/vdlkm/lib/modules
    for ko in $(grep -l -E "odm_sub_lcd|sublcm" $D/*.ko 2>/dev/null); do
        m=$(basename "$ko" .ko)
        echo "modprobe $m"
        modprobe -d "$D" "$m" || insmod "$ko"
    done
    wait_node 5
    umount /tmp/vdlkm 2>/dev/null
fi
ls -lZ /dev/odm_sub_lcd || exit 0
chmod 0660 /dev/odm_sub_lcd
exec /system/bin/fox_rear show /system/etc/fox_rear.rgb565 10
