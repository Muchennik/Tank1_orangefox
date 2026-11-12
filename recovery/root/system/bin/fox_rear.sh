#!/system/bin/sh
# OrangeFox logo on the TANK 1 rear display. Log: /tmp/fox_rear.log
exec >> /tmp/fox_rear.log 2>&1
echo "== fox_rear $(date)"
wait_node() { i=0; while [ ! -e /dev/odm_sub_lcd ] && [ $i -lt $1 ]; do sleep 1; i=$((i+1)); done; }

D=/tmp/vdlkm/lib/modules
loaded() { [ -d "/sys/module/$(echo "$1" | tr - _)" ]; }
# insmod one module after its dependencies, from vendor_dlkm's modules.dep.
# (toolbox modprobe -d cannot be used: the dep file lists /vendor/lib/modules/... paths
#  and its alias/softdep syntax is rejected)
load_mod() {
    m=$1
    loaded "$m" && return 0
    line=$(grep -m1 -E "(^|/)$m\.ko:" "$D/modules.dep")
    deps=${line#*:}
    rev=""
    for d in $deps; do rev="$d $rev"; done   # deepest dependency is listed last
    for d in $rev; do
        n=$(basename "$d" .ko)
        loaded "$n" || { insmod "$D/$n.ko" && echo "  dep $n ok" || echo "  dep $n FAILED"; }
    done
    insmod "$D/$m.ko" && echo "insmod $m ok" || { echo "insmod $m FAILED"; dmesg | grep -i "unknown symbol" | tail -5; }
}

wait_node 8
if [ ! -e /dev/odm_sub_lcd ]; then
    # rear LCD + touch drivers live in vendor_dlkm, not in the vendor_boot module list
    echo "no /dev/odm_sub_lcd, loading rear display modules from vendor_dlkm"
    mkdir -p /tmp/vdlkm
    for b in /dev/block/by-name/vendor_dlkm /dev/block/mapper/vendor_dlkm_b /dev/block/mapper/vendor_dlkm_a; do
        [ -e "$b" ] && mount -o ro "$b" /tmp/vdlkm && { echo "mounted $b"; break; }
    done
    # helper -> panel -> touch (touch is loaded so fox_rear can grab it away from the UI)
    for m in chamsion_sublcmtp_helper gc9307nf_hsd_spi hynitron_cst816t; do load_mod $m; done
    wait_node 5
    umount /tmp/vdlkm 2>/dev/null
fi
ls -lZ /dev/odm_sub_lcd || exit 0
chmod 0660 /dev/odm_sub_lcd
exec /system/bin/fox_rear show /system/etc/fox_rear.rgb565 10
