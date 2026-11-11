#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
# the stub must be enabled (the stock gallery may have been disabled by the user)
pm enable com.blackview.photos.gallery >/dev/null 2>&1
