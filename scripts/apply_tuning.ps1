param (
    [string]$TvIp = "192.168.1.17:5555"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Philips Android TV (TPM191E) System Performance Tuning  " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Connect to TV
Write-Host "[1/6] Connecting to TV at $TvIp..." -ForegroundColor Yellow
& adb connect $TvIp
& adb -s $TvIp wait-for-device

# 2. Lock Cached Process Limits
Write-Host "[2/6] Locking cached process limits (max_cached=12, max_empty=6)..." -ForegroundColor Yellow
& adb -s $TvIp shell "cmd device_config set_sync_disabled_for_tests persistent"
& adb -s $TvIp shell "device_config put activity_manager max_cached_processes 12"
& adb -s $TvIp shell "device_config put activity_manager max_empty_processes 6"
& adb -s $TvIp shell "settings put global max_cached_processes 12"
& adb -s $TvIp shell "settings put global background_process_limit 3"

# 3. Zero Animation Scales
Write-Host "[3/6] Setting UI animation scales to 0.0x for instant response..." -ForegroundColor Yellow
& adb -s $TvIp shell "settings put global window_animation_scale 0.0"
& adb -s $TvIp shell "settings put global transition_animation_scale 0.0"
& adb -s $TvIp shell "settings put global animator_duration_scale 0.0"

# 4. Disable Unnecessary Daemons & Shrink Logcat
Write-Host "[4/6] Disabling bloatware daemons & shrinking log buffers..." -ForegroundColor Yellow
& adb -s $TvIp shell "pm disable-user com.android.se" 2>$null
& adb -s $TvIp shell "pm disable-user org.droidtv.dlna" 2>$null
& adb -s $TvIp shell "setprop persist.traced.enable 0"
& adb -s $TvIp shell "logcat -G 256K"

# 5. Configure SmartTube & Stremio Decoders
Write-Host "[5/6] Tuning SmartTube decoder & overlay permissions..." -ForegroundColor Yellow
& adb -s $TvIp shell "appops set org.smarttube.stable PICTURE_IN_PICTURE ignore"
& adb -s $TvIp shell "appops set org.smarttube.stable SYSTEM_ALERT_WINDOW allow"

# 6. Native AOT Compilation
Write-Host "[6/6] Compiling core apps to native ARM machine code (-m speed)..." -ForegroundColor Yellow
$apps = @(
    "ar.tvplayer.tv",
    "org.smarttube.stable",
    "com.stremio.one",
    "com.spocky.projengmenu",
    "com.antigravity.tvvolume",
    "com.android.systemui",
    "com.google.android.webview"
)

foreach ($pkg in $apps) {
    Write-Host "  -> Compiling $pkg..." -ForegroundColor Gray
    & adb -s $TvIp shell "cmd package compile -m speed -f $pkg"
}

Write-Host "`nAll performance optimizations successfully applied!" -ForegroundColor Green
