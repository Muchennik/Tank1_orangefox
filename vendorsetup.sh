#
# OrangeFox build variables for OSCAL TANK 1
# (sourced automatically by build/envsetup.sh)
#
FDEVICE="TANK_1"

fox_get_target_device() {
    local chkdev=$(echo "$BASH_SOURCE" | grep -w "$FDEVICE")
    if [ -n "$chkdev" ]; then
        FOX_BUILD_DEVICE="$FDEVICE"
    else
        chkdev=$(set | grep BASH_ARGV | grep -w "$FDEVICE")
        [ -n "$chkdev" ] && FOX_BUILD_DEVICE="$FDEVICE"
    fi
}

if [ -z "$1" -a -z "$FOX_BUILD_DEVICE" ]; then
    fox_get_target_device
fi

if [ "$1" = "$FDEVICE" -o "$FOX_BUILD_DEVICE" = "$FDEVICE" ]; then
    export TW_DEFAULT_LANGUAGE="en"
    export LC_ALL="C"
    export ALLOW_MISSING_DEPENDENCIES=true

    # Identity
    export FOX_VARIANT="A15"
    export OF_MAINTAINER="Muchen’nik"
    export FOX_MAINTAINER_PATCH_VERSION="1"
    export OF_MAINTAINER_AVATAR="$(pwd)/device/oscal/$FDEVICE/maintainer.png"
    export FOX_BUILD_TYPE="Unofficial"

    # Recovery lives in vendor_boot (header v4)
    export FOX_VENDOR_BOOT_RECOVERY=1
    export FOX_VIRTUAL_AB_DEVICE=1
    export FOX_AB_DEVICE=1
    export FOX_RECOVERY_INSTALL_PARTITION="/dev/block/by-name/vendor_boot"
    export FOX_RECOVERY_BOOT_PARTITION="/dev/block/by-name/boot"
    export OF_NO_TREBLE_COMPATIBILITY_CHECK=1

    # Never let OrangeFox reflash / patch the boot or vbmeta images on its own
    export OF_DONT_PATCH_ENCRYPTED_DEVICE=1
    export OF_NO_RELOAD_AFTER_DECRYPTION=1

    # Screen (1080x2460, punch hole top-center)
    export OF_SCREEN_H=2460
    export OF_STATUS_H=110
    export OF_STATUS_INDENT_LEFT=48
    export OF_STATUS_INDENT_RIGHT=48
    export OF_HIDE_NOTCH=1
    export OF_CLOCK_POS=1
    export OF_ALLOW_DISABLE_NAVBAR=0
    export OF_USE_GREEN_LED=0

    # Tools
    export FOX_USE_BASH_SHELL=1
    export FOX_ASH_IS_BASH=1
    export FOX_USE_TAR_BINARY=1
    export FOX_USE_SED_BINARY=1
    export FOX_USE_XZ_UTILS=1
    export FOX_USE_NANO_EDITOR=1
    export FOX_USE_LZ4_BINARY=1
    export FOX_USE_ZSTD_BINARY=1
    export OF_ENABLE_LPTOOLS=1
    export FOX_ENABLE_APP_MANAGER=1
    export FOX_DELETE_AROMAFM=1

    # Flashlight (top torches) — exposed via OrangeFox "Flashlight" toggle
    export OF_FL_PATH1="/sys/class/leds/ocp81375_ch0_torch"
    export OF_FL_PATH2="/sys/class/leds/ocp81375_ch1_torch"
    export OF_FLASHLIGHT_ENABLE=1

    # Misc
    export OF_QUICK_BACKUP_LIST="/boot;/init_boot;/vendor_boot;/dtbo;"
    export OF_USE_SYSTEM_FINGERPRINT=1
    echo "OrangeFox vars for $FDEVICE loaded"

fi
