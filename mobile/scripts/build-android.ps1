param([switch]$InitializeSigning)
$ErrorActionPreference = 'Stop'
$mobileRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Set JAVA_HOME (JDK 21) and ANDROID_HOME (Android SDK) first.' }
Push-Location $mobileRoot
try {
    & pnpm.cmd run build:android
    if ($LASTEXITCODE -ne 0) { throw 'Web build or Capacitor sync failed' }
    Push-Location (Join-Path $mobileRoot 'android')
    try {
        if ($env:DAJIN_GRADLE) { & $env:DAJIN_GRADLE assembleRelease --console=plain }
        else { & .\gradlew.bat assembleRelease --console=plain }
        if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    } finally { Pop-Location }
    if ($InitializeSigning) { & node scripts/sign-android.mjs --init-signing }
    else { & node scripts/sign-android.mjs }
    if ($LASTEXITCODE -ne 0) { throw 'Android signing failed' }
} finally { Pop-Location }
