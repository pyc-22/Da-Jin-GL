$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = 'D:\dajin-android-tools\java\jdk-21.0.12.1+1'
$env:VITE_API_BASE = 'http://192.168.1.221:8080'
$env:VITE_WS_URL = 'ws://192.168.1.221:8080'

# Vite 读取显式的 local mode 配置；移动端真机和电脑需处于同一局域网。
pnpm exec vite build --mode android --config vite.local.config.js
pnpm exec cap sync android
Set-Location android
& .\gradlew.bat :app:assembleDebug --console=plain
Write-Host "本地测试 APK: $root\android\app\build\outputs\apk\debug\app-debug.apk"
