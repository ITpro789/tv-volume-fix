param (
    [string]$SdkPath = "C:\Users\sohai\AppData\Local\Android\Sdk"
)

$buildTools = "$SdkPath\build-tools\34.0.0"
$platformJar = "$SdkPath\platforms\android-34\android.jar"
$projDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $projDir
if (Test-Path bin) { Remove-Item -Recurse -Force bin }
New-Item -ItemType Directory -Force bin | Out-Null
New-Item -ItemType Directory -Force bin\classes | Out-Null

Write-Host "Compiling Java sources..." -ForegroundColor Cyan
javac -cp $platformJar -d bin\classes (Get-ChildItem src\com\antigravity\tvvolume\*.java).FullName
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "DEX compiling with d8..." -ForegroundColor Cyan
& "$buildTools\d8.bat" --output bin (Get-ChildItem bin\classes\com\antigravity\tvvolume\*.class).FullName
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "Packaging APK with aapt2..." -ForegroundColor Cyan
& "$buildTools\aapt2.exe" link -I $platformJar --manifest AndroidManifest.xml -o bin\app-unaligned.apk
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "Adding classes.dex into APK..." -ForegroundColor Cyan
Set-Location "$projDir\bin"
jar -uf app-unaligned.apk classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "Aligning APK..." -ForegroundColor Cyan
& "$buildTools\zipalign.exe" -f 4 app-unaligned.apk app-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "Generating debug keystore if needed..." -ForegroundColor Cyan
if (-not (Test-Path "$projDir\debug.keystore")) {
    keytool -genkeypair -keystore "$projDir\debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}

Write-Host "Signing APK with apksigner..." -ForegroundColor Cyan
& "$buildTools\apksigner.bat" sign --ks "$projDir\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$projDir\TvVolumeOverlay.apk" app-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "SUCCESS! Built $projDir\TvVolumeOverlay.apk" -ForegroundColor Green
