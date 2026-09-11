#!/system/bin/sh
# Launcher script for volume_bridge on TV & system performance tuning

# Optimize memory for smooth switching between Stremio, TiviMate, and SmartTube (2GB RAM device)
device_config put activity_manager max_cached_processes 3
settings put global background_process_limit 3

pkill -9 -f volume_bridge
nohup /data/local/tmp/volume_bridge > /data/local/tmp/volume_bridge.log 2>&1 &
echo "volume_bridge started with low-RAM optimizations."

