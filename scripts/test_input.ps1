param (
    [string]$TvIp = "192.168.1.17:5555",
    [string]$OutputFile = "./tv_screen.png"
)

Write-Host "Connecting to TV at $TvIp..." -ForegroundColor Cyan
& adb connect $TvIp

Write-Host "Capturing TV screen buffer..." -ForegroundColor Cyan
& adb -s $TvIp shell "screencap -p /data/local/tmp/snap.png"
& adb -s $TvIp pull /data/local/tmp/snap.png $OutputFile
& adb -s $TvIp shell "rm /data/local/tmp/snap.png"

Write-Host "Screenshot saved to $OutputFile" -ForegroundColor Green

Write-Host "`nActive Input Devices:" -ForegroundColor Cyan
& adb -s $TvIp shell "dumpsys input | grep -E 'Device [0-9]+:'"

Write-Host "`nFocused Window:" -ForegroundColor Cyan
& adb -s $TvIp shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
