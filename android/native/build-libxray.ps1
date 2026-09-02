param(
    [string]$AndroidSdk = "C:/Users/dargg/AppData/Local/Android/Sdk",
    [string]$AndroidNdk = ""
)

$ErrorActionPreference = "Stop"
$libXrayTag = "v26.3.27"
$libXrayCommit = "38ae3cd8914d5bc2a7f81122fc6206efe3c07ad6"
$scriptRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $scriptRoot ".native"
$sourceRoot = Join-Path $nativeRoot "libXray"

New-Item -ItemType Directory -Force -Path $nativeRoot | Out-Null
if (-not (Test-Path $sourceRoot)) {
    git clone --branch $libXrayTag --depth 1 https://github.com/XTLS/libXray.git $sourceRoot
}

$actualCommit = (git -C $sourceRoot rev-parse HEAD).Trim()
if ($actualCommit -ne $libXrayCommit) {
    throw "Unexpected libXray commit: $actualCommit"
}

$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
if (-not [string]::IsNullOrWhiteSpace($AndroidNdk)) {
    $env:ANDROID_NDK_HOME = $AndroidNdk
    $env:ANDROID_NDK_ROOT = $AndroidNdk
}
$env:Path = "C:\Program Files\Go\bin;$env:USERPROFILE\go\bin;$env:Path"
Set-Location $sourceRoot
& "C:\Users\dargg\AppData\Local\Programs\Python\Python313\python.exe" build/main.py android

$aar = Join-Path $sourceRoot "libXray.aar"
if (-not (Test-Path $aar)) {
    throw "Official libXray AAR was not created: $aar"
}
