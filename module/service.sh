#!/system/bin/sh
MODDIR=${0%/*}

# Ensure runtime config directory and permissions on boot
mkdir -p /data/local/tmp/battmon
chmod 755 /data/local/tmp/battmon

if [ ! -f /data/local/tmp/battmon/config.json ] && [ -f "$MODDIR/config.json" ]; then
    cp -f "$MODDIR/config.json" /data/local/tmp/battmon/config.json
fi

if [ -f /data/local/tmp/battmon/config.json ]; then
    chmod 644 /data/local/tmp/battmon/config.json
    chcon u:object_r:system_file:s0 /data/local/tmp/battmon/config.json
fi
