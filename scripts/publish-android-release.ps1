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

function Wait-ForHealth {
    param([string]$Uri, [int]$Attempts = 30)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $Uri -Method Get
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

    foreach ($command in @("ssh", "scp")) {
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
    if ($currentVersionCode -eq [int]::MaxValue) {
        throw "The existing versionCode cannot be incremented safely."
    }
    $newVersionCode = $currentVersionCode + 1
    if ($newVersionCode -le $currentVersionCode) {
        throw "New versionCode must be strictly greater than the current versionCode."
    }
    $currentVersionName = $versionNameMatches[0].Groups["name"].Value

    $apkName = "myvpn-$VersionName.apk"
    $sourceApk = Join-Path $androidRoot "app/build/outputs/apk/release/app-release.apk"
    $releaseDirectory = Join-Path $androidRoot "releases"
    $releaseApk = Join-Path $releaseDirectory $apkName
    $versionedApkUrl = "$PublicBaseUrl/downloads/$apkName"
    $remote = "$VpsUser@$VpsHost"

    if ($DryRun) {
        Write-Output "Dry run: no files, Gradle configuration, APKs, or VPS state will be changed."
        Write-Output "Current version code: $currentVersionCode"
        Write-Output "New version code: $newVersionCode"
        Write-Output "Current version name: $currentVersionName"
        Write-Output "New version name: $VersionName"
        Write-Output "Would set versionCode = $newVersionCode and versionName = `"$VersionName`" in android/app/build.gradle.kts."
        Write-Output "Would build $sourceApk and copy it to $releaseApk."
        Write-Output "Would upload $apkName to /var/www/myvpn/downloads/ on $remote."
        Write-Output "Would point myvpn-latest.apk to $apkName and set Android staging metadata to the stable latest URL."
        exit 0
    }

    $updatedGradleContent = [regex]::Replace($gradleContent, $versionCodePattern, "        versionCode = $newVersionCode")
    $updatedGradleContent = [regex]::Replace($updatedGradleContent, $versionNamePattern, "        versionName = `"$VersionName`"")
    Set-Content -LiteralPath $gradleFile -Value $updatedGradleContent -NoNewline -Encoding utf8

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

    Set-Location -LiteralPath $androidRoot
    & .\gradlew.bat --no-daemon clean assembleRelease
    Assert-NativeSuccess "Android release build"

    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf) -or (Get-Item -LiteralPath $sourceApk).Length -le 0) {
        throw "Release APK was not produced or is empty: $sourceApk"
    }
    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceApk -Destination $releaseApk -Force

    & scp $releaseApk "${remote}:/var/www/myvpn/downloads/$apkName"
    Assert-NativeSuccess "APK upload"
    Invoke-Remote $remote "test -s '/var/www/myvpn/downloads/$apkName'" "Remote APK existence check"
    Invoke-Remote $remote "cd /var/www/myvpn/downloads && ln -sfn '$apkName' myvpn-latest.apk" "Latest symlink update"
    Invoke-Remote $remote "test `"`$(readlink -f /var/www/myvpn/downloads/myvpn-latest.apk)`" = '/var/www/myvpn/downloads/$apkName'" "Latest symlink validation"

    Assert-ApkHttpResponse $versionedApkUrl
    Assert-ApkHttpResponse $LatestApkUrl

    $safeRemoteArguments = "'$newVersionCode' '$VersionName' '$LatestApkUrl'"
    $environmentUpdateScript = @'
set -eu
version_code="$1"
version_name="$2"
apk_url="$3"
env_file="/opt/myvpn/myvpn/.env.staging"
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
'@
    Invoke-RemoteScript $remote $environmentUpdateScript $safeRemoteArguments "Staging metadata update"

    $recreateScript = @'
set -eu
cd /opt/myvpn/myvpn
docker compose -f compose.server.yaml --env-file .env.staging config --quiet
docker compose -f compose.server.yaml --env-file .env.staging up -d --force-recreate app
'@
    Invoke-RemoteScript $remote $recreateScript "" "Staging app recreate"

    Wait-ForHealth "$PublicBaseUrl/actuator/health"
    $metadata = Invoke-RestMethod -Uri "$PublicBaseUrl/api/v1/app/version" -Method Get
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
    [Console]::Error.WriteLine("Error: $($_.Exception.Message)")
    $exitCode = 1
}
finally {
    Set-Location -LiteralPath $originalLocation
}

if ($exitCode -ne 0) { exit $exitCode }
