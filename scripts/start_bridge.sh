#!/system/bin/sh
# Launcher script for volume_bridge on TV
pkill -9 -f volume_bridge
nohup /data/local/tmp/volume_bridge > /data/local/tmp/volume_bridge.log 2>&1 &
echo "volume_bridge started."
