#!/system/bin/sh
# KeyMint (TrustKernel) takes the OS version / patch level from these properties. They must
# match the running Android (TANK_1_S1_260903: 16 / 2026-08-05) or the keys of /data and
# /metadata are rejected. OrangeFox itself sets ro.build.version.release=14, so take the real
# values from the vbmeta descriptors of the active slot (they follow every OTA).
LOG=/tmp/fox_sysprops.log
exec >>$LOG 2>&1
echo "fox_sysprops start"
slot=$(getprop ro.boot.slot_suffix)
desc=$(for f in /dev/block/by-name/vbmeta$slot /dev/block/by-name/vbmeta_system$slot /dev/block/by-name/vbmeta_vendor$slot; do strings -n 2 "$f"; done 2>/dev/null)
val() { echo "$desc" | grep -A1 -x "$1" | sed -n 2p; }
rel=$(val com.android.build.system.os_version)
spl=$(val com.android.build.system.security_patch)
vspl=$(val com.android.build.vendor.security_patch)
echo "vbmeta$slot: release=$rel patch=$spl vendor_patch=$vspl"
echo "$rel" | grep -qE '^[0-9]+$' || rel=16
echo "$spl" | grep -qE '^20[0-9]{2}-[0-9]{2}-[0-9]{2}$' || spl=$(getprop ro.build.version.security_patch)
echo "$vspl" | grep -qE '^20[0-9]{2}-[0-9]{2}-[0-9]{2}$' || vspl=$spl
for kv in "ro.build.version.release=$rel" "ro.build.version.release_or_codename=$rel" \
          "ro.build.version.security_patch=$spl" "ro.vendor.build.security_patch=$vspl"; do
    p=${kv%%=*}; v=${kv#*=}
    echo "$p: $(getprop $p) -> $v"; resetprop "$p" "$v"
done
setprop ctl.start vendor.keymint-3-0-trustkernel
setprop ctl.start vendor.gatekeeper
echo "fox_sysprops done"
