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
    pat=$(echo "$m" | sed 's/[-_]/[-_]/g')     # file may use - or _ (leds-x.ko vs leds_x)
    line=$(grep -m1 -E "(^|/)$pat\.ko:" "$D/modules.dep")
    [ -n "$line" ] || { echo "$m: not in modules.dep"; return 1; }
    f=${line%%:*}; m=$(basename "$f" .ko)
    deps=${line#*:}
    rev=""
    for d in $deps; do rev="$d $rev"; done   # deepest dependency is listed last
    for d in $rev; do
        n=$(basename "$d" .ko)
        loaded "$n" || { insmod "$D/$n.ko" && echo "  dep $n ok" || echo "  dep $n FAILED"; }
    done
    insmod "$D/$m.ko" && echo "insmod $m ok" || { echo "insmod $m FAILED"; dmesg | grep -i "unknown symbol" | tail -5; }
}

# Drivers that live only in vendor_dlkm (not in the vendor_boot module list):
#   rear display: helper -> gc9307 panel -> cst816t touch (touch is loaded so fox_rear can
#     grab it away from the recovery UI)
#   torches: leds-mt6360 (camera-block flash, mt6360_flash_ch1/2) is a panel dependency;
#     leds-ocp81375 is the big front lamp (/sys/class/leds/infrared on this firmware)
mkdir -p /tmp/vdlkm
# recovery creates the by-name links for super partitions a few seconds after start
i=0; while [ ! -e /dev/block/by-name/vendor_dlkm ] && [ $i -lt 15 ]; do sleep 1; i=$((i+1)); done
for b in /dev/block/by-name/vendor_dlkm /dev/block/mapper/vendor_dlkm_b /dev/block/mapper/vendor_dlkm_a; do
    [ -e "$b" ] && mount -o ro "$b" /tmp/vdlkm && { echo "mounted $b"; break; }
done
for m in chamsion_sublcmtp_helper gc9307nf_hsd_spi hynitron_cst816t leds-mt6360 leds-ocp81375; do load_mod $m; done
umount /tmp/vdlkm 2>/dev/null
ls /sys/class/leds
wait_node 8
ls -lZ /dev/odm_sub_lcd || exit 0
chmod 0660 /dev/odm_sub_lcd
exec /system/bin/fox_rear show /system/etc/fox_rear.rgb565 10
