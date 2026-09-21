SKIPUNZIP=0

ui_print "**********************************************"
ui_print "* HyperOS Status Bar Battery Monitor (Zygisk) *"
ui_print "* No LSPosed • No APK • Live Power & Temp    *"
ui_print "**********************************************"

if [ "$ARCH" != "arm64" ]; then
    abort "! Only arm64-v8a devices are supported"
fi

ui_print "- Installing Zygisk arm64-v8a library..."
mkdir -p "$MODPATH/zygisk"
cp -f "$ZIPFILE_EXTRACT_DIR/zygisk/arm64-v8a.so" "$MODPATH/zygisk/arm64-v8a.so" 2>/dev/null || true

ui_print "- Installing KernelSU WebUI..."
mkdir -p "$MODPATH/webroot"
cp -rf "$ZIPFILE_EXTRACT_DIR/webroot/"* "$MODPATH/webroot/" 2>/dev/null || true

ui_print "- Initializing runtime configuration..."
mkdir -p /data/local/tmp/battmon
chmod 755 /data/local/tmp/battmon

if [ ! -f /data/local/tmp/battmon/config.json ]; then
    cat << 'EOF' > /data/local/tmp/battmon/config.json
{
  "enabled": true,
  "layout_mode": "dual_line",
  "content_mode": 1,
  "refresh_ms": 1000,
  "temp_unit": "°C",
  "power_unit": "W",
  "font_size_sp": 8.5,
  "bold_font": true,
  "charging_only": false
}
EOF
fi
chmod 644 /data/local/tmp/battmon/config.json

# Copy default config beside module for backup
cp -f /data/local/tmp/battmon/config.json "$MODPATH/config.json" 2>/dev/null || true

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0755

ui_print "- Done! Reboot to activate, or control via KernelSU WebUI."
