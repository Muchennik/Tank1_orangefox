#
# OSCAL TANK 1 — recovery-only device configuration
#

LOCAL_PATH := device/oscal/TANK_1

# API levels
PRODUCT_SHIPPING_API_LEVEL := 31

# Dynamic partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# A/B
ENABLE_VIRTUAL_AB := true
AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=erofs \
    POSTINSTALL_OPTIONAL_system=true

PRODUCT_PACKAGES += \
    otapreopt_script \
    cppreopts.sh \
    update_engine \
    update_engine_sideload \
    update_verifier \
    checkpoint_gc

# fastbootd
PRODUCT_PACKAGES += \
    fastbootd

# Ship the copied stock blobs into the recovery ramdisk only.
# Everything under recovery/root is copied verbatim by the TWRP build.

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Properties for recovery
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.build.security_patch=2026-08-05 \
    ro.crypto.dm_default_key.options_format.version=2 \
    ro.crypto.volume.filenames_mode=aes-256-cts \
    ro.crypto.volume.metadata.method=dm-default-key \
    ro.crypto.uses_fs_ioc_add_encryption_key=true \
    ro.hardware.keystore=trustkernel \
    ro.hardware.gatekeeper=trustkernel

# Prebuilt stock kernel -> $(PRODUCT_OUT)/kernel (needed by mkbootimg / vintf checks;
# the flashed image keeps the stock kernel anyway)
PRODUCT_COPY_FILES += $(LOCAL_PATH)/prebuilt/Image.gz:kernel
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false
