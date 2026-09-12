param(
    [string]$DestinationRoot = "E:\MyVPN-Backups",
    [string]$SshAlias = "myvpn-vps"
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$destination = Join-Path $DestinationRoot $stamp

Write-Host "Creating destination:"
Write-Host $destination

New-Item -ItemType Directory -Force -Path $destination | Out-Null

function Copy-RemoteBackup {
    param(
        [string]$RemotePath
    )

    Write-Host ""
    Write-Host "Downloading $RemotePath ..."

    scp -r "${SshAlias}:${RemotePath}" "$destination"

    if ($LASTEXITCODE -ne 0) {
        throw "Download failed: $RemotePath. scp exit code: $LASTEXITCODE"
    }
}

Copy-RemoteBackup "/opt/myvpn/backups/postgres"
Copy-RemoteBackup "/opt/myvpn/backups/x-ui"

$postgresBackup = Get-ChildItem `
    "$destination\postgres\myvpn-*.sql.gz" `
    -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1

$xuiBackup = Get-ChildItem `
    "$destination\x-ui\x-ui-*.db" `
    -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $postgresBackup) {
    throw "PostgreSQL backup not found after download."
}

if (-not $xuiBackup) {
    throw "3x-ui backup not found after download."
}

Write-Host ""
Write-Host "Backup downloaded successfully."
Write-Host "Destination: $destination"

Write-Host ""
Write-Host "Latest PostgreSQL backup:"
Write-Host "  $($postgresBackup.Name)"
Write-Host "  $([math]::Round($postgresBackup.Length / 1KB, 2)) KB"

Write-Host ""
Write-Host "Latest 3x-ui backup:"
Write-Host "  $($xuiBackup.Name)"
Write-Host "  $([math]::Round($xuiBackup.Length / 1KB, 2)) KB"

Write-Host ""
Write-Host "Done."