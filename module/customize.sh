SKIPUNZIP=0
ui_print "- DokeOS gallery -> Google Photos redirect"
touch $MODPATH/system/app/DKMimediaGallery/.replace
set_perm_recursive $MODPATH/system 0 0 0755 0644
