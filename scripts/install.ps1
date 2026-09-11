param (
    [string]$TvIp = "192.168.1.17:5555"
)

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " Android TV Volume Bridge & Overlay Installer" -ForegroundColor Cyan
Write-Host " Target TV: $TvIp" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Check ADB connection
Write-Host "`n[1/5] Connecting to TV via ADB..." -ForegroundColor Yellow
adb connect $TvIp
$devs = adb devices
if ($devs -notmatch $TvIp) {
    Write-Error "Could not connect to $TvIp. Ensure ADB debugging is enabled on TV."
    exit 1
}

$rootPath = Split-Path -Parent $PSScriptRoot
$daemonBin = Join-Path $rootPath "daemon\volume_bridge"
$overlayApk = Join-Path $rootPath "overlay-app\TvVolumeOverlay.apk"

# 2. Deploy Native Daemon
Write-Host "`n[2/5] Deploying Native volume_bridge Daemon..." -ForegroundColor Yellow
adb -s $TvIp shell "pkill -9 -f volume_bridge" | Out-Null
adb -s $TvIp push $daemonBin /data/local/tmp/volume_bridge
adb -s $TvIp shell "chmod 755 /data/local/tmp/volume_bridge"

# 3. Install Overlay APK
Write-Host "`n[3/5] Installing TV Volume OSD Overlay APK..." -ForegroundColor Yellow
adb -s $TvIp shell "settings put global verifier_verify_adb_installs 0; settings put global package_verifier_enable 0" | Out-Null
adb -s $TvIp push $overlayApk /data/local/tmp/TvVolumeOverlay.apk | Out-Null
adb -s $TvIp shell "pm install -r -d -g /data/local/tmp/TvVolumeOverlay.apk"

# 4. Grant Permissions & Start Overlay
Write-Host "`n[4/5] Granting SYSTEM_ALERT_WINDOW and Starting Overlay Service..." -ForegroundColor Yellow
adb -s $TvIp shell "appops set com.antigravity.tvvolume SYSTEM_ALERT_WINDOW allow"
adb -s $TvIp shell "am force-stop com.antigravity.tvvolume"
adb -s $TvIp shell "am start-foreground-service com.antigravity.tvvolume/.TvVolumeService"

# 5. Apply OS Memory Optimizations & Launch Daemon
Write-Host "`n[5/5] Applying low-RAM optimizations & Launching Daemon..." -ForegroundColor Yellow
adb -s $TvIp shell "device_config put activity_manager max_cached_processes 3; settings put global background_process_limit 3"
adb -s $TvIp shell "nohup /data/local/tmp/volume_bridge > /data/local/tmp/volume_bridge.log 2>&1 &"
Start-Sleep -Seconds 2

# Verification
$proc = adb -s $TvIp shell "ps -ef | grep volume_bridge | grep -v grep"
if ($proc) {
    Write-Host "`n=============================================" -ForegroundColor Green
    Write-Host " INSTALLATION COMPLETE & ACTIVE!" -ForegroundColor Green
    Write-Host " Daemon PID: $proc" -ForegroundColor Green
    Write-Host " The volume overlay will now appear on your TV screen." -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
} else {
    Write-Error "Daemon failed to start. Check: adb shell cat /data/local/tmp/volume_bridge.log"
}
