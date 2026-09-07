[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$VersionName,

    [string]$VpsHost = "78.17.39.236",

    [string]$VpsUser = "myvpn-deploy",

    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$LatestApkUrl = "https://api.myvpn05.ru/downloads/myvpn-latest.apk"
$PublicBaseUrl = "https://api.myvpn05.ru"
$ExpectedApkContentType = "application/vnd.android.package-archive"
$RemoteProjectDirectory = "/opt/myvpn/myvpn"
$RemoteDownloadsDirectory = "/var/www/myvpn/downloads"

function Write-Stage {
    param([int]$Number, [string]$Name)
    Write-Output "[$Number/7] $Name"
}

function Assert-NativeSuccess {
    param([string]$Description)
    if ($LASTEXITCODE -ne 0) { throw "$Description failed with exit code $LASTEXITCODE." }
}

function Get-RequiredProperty {
    param([hashtable]$Properties, [string]$Name)
    $value = $Properties[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Release signing property '$Name' is missing or empty."
    }
    return $value
}

function Read-JavaProperties {
    param([string]$Path)
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?:#|!|$)') { continue }
        if ($line -match '^\s*([^=:#\s]+)\s*[=:]\s*(.*)\s*$') {
            $properties[$matches[1]] = $matches[2]
        }
    }
    return $properties
}

function Invoke-Remote {
    param([string]$Remote, [string]$Command, [string]$Description)
    & ssh $Remote $Command
    Assert-NativeSuccess $Description
}

function Invoke-RemoteScript {
    param([string]$Remote, [string]$Script, [string]$Arguments, [string]$Description)
    $Script | & ssh $Remote "bash -s -- $Arguments"
    Assert-NativeSuccess $Description
}

function Assert-ApkHttpResponse {
    param([string]$Uri)
    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Head -UseBasicParsing
    } catch {
        throw "APK HTTP validation failed for ${Uri}: $($_.Exception.Message)"
    }
    if ($response.StatusCode -ne 200) { throw "APK URL $Uri returned HTTP $($response.StatusCode), expected 200." }
    $contentType = [string]$response.Headers["Content-Type"]
    if ($contentType -notlike "$ExpectedApkContentType*") {
        throw "APK URL $Uri returned Content-Type '$contentType', expected '$ExpectedApkContentType'."
    }
}

function Invoke-CurlJson {
    param([string]$Uri, [string]$Description)
    $body = & curl.exe -fsS --connect-timeout 10 --max-time 20 $Uri
    Assert-NativeSuccess $Description
    try {
        return $body | ConvertFrom-Json
    } catch {
        throw "$Description returned invalid JSON."
    }
}

function Wait-ForHealth {
    param([string]$Uri, [int]$Attempts = 30)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $health = Invoke-CurlJson $Uri "Health request"
            if ($health.status -eq "UP") { return }
        } catch {
            # The app can be unavailable while Docker recreates it.
        }
        Start-Sleep -Seconds 2
    }
    throw "Health endpoint did not report UP after $Attempts attempts."
}

$originalLocation = Get-Location
$exitCode = 0
$currentStage = "Validation"
$versionPersisted = $false
$remoteApkUploaded = $false
$latestSymlinkSwitched = $false
$recoveryCommand = $null

try {
    if ([string]::IsNullOrWhiteSpace($VersionName)) { throw "VersionName must not be empty." }
    if ($VersionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
        throw "VersionName may contain only letters, digits, '.', '_' and '-'; it must be safe for a filename."
    }
    if ([string]::IsNullOrWhiteSpace($VpsHost) -or [string]::IsNullOrWhiteSpace($VpsUser)) {
        throw "VpsHost and VpsUser must not be empty."
    }
    if ($VpsHost -notmatch '^[A-Za-z0-9.-]+$') { throw "VpsHost has an invalid format." }
    if ($VpsUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') { throw "VpsUser has an invalid format." }

    foreach ($command in @("ssh", "scp", "curl.exe")) {
        if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw "Required command '$command' was not found in PATH."
        }
    }

    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
    $androidRoot = Join-Path $repoRoot "android"
    $gradleFile = Join-Path $androidRoot "app/build.gradle.kts"
    $signingPropertiesFile = Join-Path $androidRoot "keystore.properties"
    foreach ($path in @($androidRoot, $gradleFile, $signingPropertiesFile)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Required Android release path is missing: $path" }
    }

    $signingProperties = Read-JavaProperties $signingPropertiesFile
    $storeFile = Get-RequiredProperty $signingProperties "storeFile"
    Get-RequiredProperty $signingProperties "storePassword" | Out-Null
    Get-RequiredProperty $signingProperties "keyAlias" | Out-Null
    Get-RequiredProperty $signingProperties "keyPassword" | Out-Null
    $storeFilePath = $storeFile -replace '\\\\', '\'
    if (-not (Test-Path -LiteralPath $storeFilePath -PathType Leaf)) {
        throw "Configured release keystore file is unavailable."
    }

    $gradleContent = Get-Content -LiteralPath $gradleFile -Raw
    $versionCodePattern = '(?m)^\s*versionCode\s*=\s*(?<code>\d+)\s*$'
    $versionNamePattern = '(?m)^\s*versionName\s*=\s*"(?<name>[^"]*)"\s*$'
    $versionCodeMatches = [regex]::Matches($gradleContent, $versionCodePattern)
    if ($versionCodeMatches.Count -ne 1) {
        throw "Expected exactly one defaultConfig versionCode assignment in android/app/build.gradle.kts."
    }
    $versionNameMatches = [regex]::Matches($gradleContent, $versionNamePattern)
    if ($versionNameMatches.Count -ne 1) {
        throw "Expected exactly one defaultConfig versionName assignment in android/app/build.gradle.kts."
    }
    [int]$currentVersionCode = 0
    $currentVersionCodeText = $versionCodeMatches[0].Groups["code"].Value
    if (-not [int]::TryParse($currentVersionCodeText, [ref]$currentVersionCode) -or $currentVersionCode -le 0) {
        throw "The existing versionCode must be a positive integer."
    }
    $currentVersionName = $versionNameMatches[0].Groups["name"].Value
    # Re-running the same version is a recovery operation after a partial release.
    # Reuse the versionCode already persisted by the first attempt.
    $isResume = $currentVersionName -eq $VersionName
    if ($isResume) {
        $newVersionCode = $currentVersionCode
    } else {
        if ($currentVersionCode -eq [int]::MaxValue) {
            throw "The existing versionCode cannot be incremented safely."
        }
        $newVersionCode = $currentVersionCode + 1
        if ($newVersionCode -le $currentVersionCode) {
            throw "New versionCode must be strictly greater than the current versionCode."
        }
    }

    $apkName = "myvpn-$VersionName.apk"
    $sourceApk = Join-Path $androidRoot "app/build/outputs/apk/release/app-release.apk"
    $releaseDirectory = Join-Path $androidRoot "releases"
    $releaseApk = Join-Path $releaseDirectory $apkName
    $versionedApkUrl = "$PublicBaseUrl/downloads/$apkName"
    $remote = "$VpsUser@$VpsHost"
    $recoveryCommand = ".\scripts\publish-android-release.ps1 -VersionName `"$VersionName`" -VpsHost `"$VpsHost`" -VpsUser `"$VpsUser`""

    if ($DryRun) {
        Write-Output "Dry run: no files, Gradle configuration, APKs, or VPS state will be changed."
        Write-Output "Current version code: $currentVersionCode"
        Write-Output "New version code: $newVersionCode"
        Write-Output "Current version name: $currentVersionName"
        Write-Output "New version name: $VersionName"
        if ($isResume) { Write-Output "Mode: resume existing version without another versionCode increment." }
        Write-Stage 1 "Version update"
        Write-Output ("Would persist versionCode = {0} and versionName = {1} in android/app/build.gradle.kts." -f $newVersionCode, $VersionName)
        Write-Stage 2 "Build"
        Write-Output "Would run in android: .\gradlew.bat --no-daemon clean assembleRelease"
        Write-Output ("Would copy {0} to {1}." -f $sourceApk, $releaseApk)
        Write-Stage 3 "Upload"
        Write-Output ("scp {0} {1}:{2}/{3}" -f $releaseApk, $remote, $RemoteDownloadsDirectory, $apkName)
        Write-Stage 4 "Symlink"
        Write-Output ("ssh {0}: cd {1}; test APK; ln -sfn {2} myvpn-latest.apk; readlink -f myvpn-latest.apk" -f $remote, $RemoteDownloadsDirectory, $apkName)
        Write-Stage 5 "Metadata"
        Write-Output ("ssh {0}: cd {1}; atomically update only ANDROID_LATEST_VERSION_CODE={2}, ANDROID_LATEST_VERSION_NAME={3}, ANDROID_APK_URL={4} in .env.staging" -f $remote, $RemoteProjectDirectory, $newVersionCode, $VersionName, $LatestApkUrl)
        Write-Stage 6 "Backend recreate"
        Write-Output ("ssh {0}: cd {1}; docker compose -f compose.server.yaml --env-file .env.staging config --quiet; docker compose -f compose.server.yaml --env-file .env.staging up -d --force-recreate app" -f $remote, $RemoteProjectDirectory)
        Write-Stage 7 "Validation"
        Write-Output ("ssh {0}: readlink -f {1}/myvpn-latest.apk" -f $remote, $RemoteDownloadsDirectory)
        Write-Output "curl -fsS $PublicBaseUrl/actuator/health"
        Write-Output "curl -fsS $PublicBaseUrl/api/v1/app/version"
        exit 0
    }

    Write-Stage 1 "Version update"
    $currentStage = "[1/7] Version update"
    if (-not $isResume) {
        $updatedGradleContent = [regex]::Replace($gradleContent, $versionCodePattern, "        versionCode = $newVersionCode")
        $updatedGradleContent = [regex]::Replace($updatedGradleContent, $versionNamePattern, "        versionName = `"$VersionName`"")
        Set-Content -LiteralPath $gradleFile -Value $updatedGradleContent -NoNewline -Encoding utf8
    } else {
        Write-Output "Resuming version $VersionName with existing versionCode $newVersionCode."
    }

    $persistedGradleContent = Get-Content -LiteralPath $gradleFile -Raw
    $persistedVersionCodeMatches = [regex]::Matches($persistedGradleContent, $versionCodePattern)
    $persistedVersionNameMatches = [regex]::Matches($persistedGradleContent, $versionNamePattern)
    if ($persistedVersionCodeMatches.Count -ne 1 -or $persistedVersionNameMatches.Count -ne 1) {
        throw "Version update was not persisted in the expected Gradle structure."
    }
    [int]$persistedVersionCode = 0
    $persistedVersionCodeText = $persistedVersionCodeMatches[0].Groups["code"].Value
    $persistedVersionName = $persistedVersionNameMatches[0].Groups["name"].Value
    $persistedCodeIsExpected = [int]::TryParse($persistedVersionCodeText, [ref]$persistedVersionCode) -and $persistedVersionCode -eq $newVersionCode
    if (-not $persistedCodeIsExpected -or $persistedVersionName -ne $VersionName) {
        throw "Version update was not persisted with the requested versionCode and versionName."
    }
    $versionPersisted = $true

    Write-Stage 2 "Build"
    $currentStage = "[2/7] Build"
    Set-Location -LiteralPath $androidRoot
    & .\gradlew.bat --no-daemon clean assembleRelease
    Assert-NativeSuccess "Android release build"

    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf) -or (Get-Item -LiteralPath $sourceApk).Length -le 0) {
        throw "Release APK was not produced or is empty: $sourceApk"
    }
    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceApk -Destination $releaseApk -Force

    Write-Stage 3 "Upload"
    $currentStage = "[3/7] Upload"
    & scp $releaseApk "${remote}:$RemoteDownloadsDirectory/$apkName"
    Assert-NativeSuccess "APK upload"
    $remoteApkUploaded = $true

    Write-Stage 4 "Symlink"
    $currentStage = "[4/7] Symlink"
    Invoke-Remote $remote "cd '$RemoteDownloadsDirectory' && test -s '$apkName' && ln -sfn '$apkName' myvpn-latest.apk" "Latest symlink update"
    Invoke-Remote $remote "test `"`$(readlink -f '$RemoteDownloadsDirectory/myvpn-latest.apk')`" = '$RemoteDownloadsDirectory/$apkName'" "Latest symlink validation"
    $latestSymlinkSwitched = $true

    Write-Stage 5 "Metadata"
    $currentStage = "[5/7] Metadata"
    $safeRemoteArguments = "'$newVersionCode' '$VersionName' '$LatestApkUrl'"
    $environmentUpdateScript = @'
set -eu
version_code="$1"
version_name="$2"
apk_url="$3"
cd /opt/myvpn/myvpn
env_file=".env.staging"
test -f "$env_file"
temp_file="$(mktemp "${env_file}.tmp.XXXXXX")"
trap 'rm -f "$temp_file"' EXIT
awk -v code="$version_code" -v name="$version_name" -v url="$apk_url" '
  /^ANDROID_LATEST_VERSION_CODE=/ { print "ANDROID_LATEST_VERSION_CODE=" code; seen_code=1; next }
  /^ANDROID_LATEST_VERSION_NAME=/ { print "ANDROID_LATEST_VERSION_NAME=" name; seen_name=1; next }
  /^ANDROID_APK_URL=/ { print "ANDROID_APK_URL=" url; seen_url=1; next }
  { print }
  END {
    if (!seen_code) print "ANDROID_LATEST_VERSION_CODE=" code
    if (!seen_name) print "ANDROID_LATEST_VERSION_NAME=" name
    if (!seen_url) print "ANDROID_APK_URL=" url
  }
' "$env_file" > "$temp_file"
chmod --reference="$env_file" "$temp_file"
mv "$temp_file" "$env_file"
trap - EXIT
grep -Fqx "ANDROID_LATEST_VERSION_CODE=$version_code" "$env_file"
grep -Fqx "ANDROID_LATEST_VERSION_NAME=$version_name" "$env_file"
grep -Fqx "ANDROID_APK_URL=$apk_url" "$env_file"
'@
    Invoke-RemoteScript $remote $environmentUpdateScript $safeRemoteArguments "Staging metadata update"

    Write-Stage 6 "Backend recreate"
    $currentStage = "[6/7] Backend recreate"
    $recreateScript = @'
set -euo pipefail
cd /opt/myvpn/myvpn
test -f compose.server.yaml
test -f .env.staging
docker compose -f compose.server.yaml --env-file .env.staging config --quiet
rendered_config="$(docker compose -f compose.server.yaml --env-file .env.staging config)"
grep -q 'ANDROID_LATEST_VERSION_CODE:' <<< "$rendered_config"
grep -q 'ANDROID_LATEST_VERSION_NAME:' <<< "$rendered_config"
grep -q 'ANDROID_APK_URL:' <<< "$rendered_config"
docker compose -f compose.server.yaml --env-file .env.staging config --services | grep -Fx app >/dev/null
docker compose -f compose.server.yaml --env-file .env.staging up -d --force-recreate app
'@
    Invoke-RemoteScript $remote $recreateScript "" "Staging app recreate"

    Write-Stage 7 "Validation"
    $currentStage = "[7/7] Validation"
    Invoke-Remote $remote "test `"`$(readlink -f '$RemoteDownloadsDirectory/myvpn-latest.apk')`" = '$RemoteDownloadsDirectory/$apkName'" "Final latest symlink validation"
    Assert-ApkHttpResponse $versionedApkUrl
    Assert-ApkHttpResponse $LatestApkUrl
    Wait-ForHealth "$PublicBaseUrl/actuator/health"
    $metadata = Invoke-CurlJson "$PublicBaseUrl/api/v1/app/version" "App-version metadata request"
    if ($metadata.latestVersionCode -ne $newVersionCode -or $metadata.latestVersionName -ne $VersionName -or $metadata.apkUrl -ne $LatestApkUrl) {
        throw "Staging app-version metadata does not match the published Android release."
    }

    Write-Output "Android release published"
    Write-Output "Version name: $VersionName"
    Write-Output "Version code: $newVersionCode"
    Write-Output "APK: $versionedApkUrl"
    Write-Output "Latest: $LatestApkUrl"
    Write-Output "Backend metadata updated: yes"
    Write-Output "Health: UP"
}
catch {
    [Console]::Error.WriteLine("Release failed at ${currentStage}: $($_.Exception.Message)")
    if ($versionPersisted) {
        [Console]::Error.WriteLine("The local Gradle version remains versionCode=$newVersionCode, versionName=$VersionName.")
        [Console]::Error.WriteLine("Recovery command (the same VersionName reuses this versionCode):")
        [Console]::Error.WriteLine($recoveryCommand)
    }
    if ($remoteApkUploaded) {
        [Console]::Error.WriteLine("Partial release: the versioned APK was uploaded to $RemoteDownloadsDirectory/$apkName.")
    }
    if ($latestSymlinkSwitched) {
        [Console]::Error.WriteLine("Partial release: myvpn-latest.apk already points to $apkName; backend metadata may still require recovery.")
    }
    $exitCode = 1
}
finally {
    Set-Location -LiteralPath $originalLocation
}

if ($exitCode -ne 0) { exit $exitCode }
