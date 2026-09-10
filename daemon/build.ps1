param (
    [string]$NdkPath = "C:\Users\sohai\AppData\Local\Android\Sdk\ndk\26.1.10909125"
)

$clang = "$NdkPath\toolchains\llvm\prebuilt\windows-x86_64\bin\armv7a-linux-androideabi32-clang.cmd"
if (-not (Test-Path $clang)) {
    Write-Error "Clang compiler not found at: $clang. Please specify -NdkPath"
    exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $scriptDir "volume_bridge.c"
$bin = Join-Path $scriptDir "volume_bridge"

Write-Host "Compiling volume_bridge.c with ARMv7 Clang..." -ForegroundColor Cyan
& $clang -O2 -pie -pthread $src -o $bin

if ($LASTEXITCODE -eq 0) {
    Write-Host "Successfully generated: $bin" -ForegroundColor Green
} else {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}
