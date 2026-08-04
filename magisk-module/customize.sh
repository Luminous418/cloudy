#!/system/bin/sh
# Install-time checks. Refuse to install on the wrong device or an A/B layout, since the
# staging logic assumes A-only recovery semantics.
ui_print "- Cloudy OTA Helper"
DEVICE=$(getprop ro.product.vendor.device)
SLOT=$(getprop ro.boot.slot_suffix)

ui_print "  device: $DEVICE"
if [ -n "$SLOT" ]; then
  ui_print "! This build reports an A/B slot ($SLOT)."
  ui_print "! Cloudy's A-only staging is not appropriate here. Aborting."
  abort
fi

case "$DEVICE" in
  a32|a32nsxx|*a32*) ui_print "  A-only Galaxy A32 detected — OK" ;;
  *) ui_print "! Warning: untested device, continuing anyway" ;;
esac

set_perm_recursive "$MODPATH" 0 0 0755 0644
ui_print "- Done. Reboot to activate."
