[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$DockerHubRepository,
    [switch]$PublishStagingAlias,
    [switch]$NoPush,
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-RepositoryName {
    param([string]$Repository)

    if ([string]::IsNullOrWhiteSpace($Repository)) {
        throw "DockerHubRepository must not be empty."
    }
    if ($Repository -match '\s|@|://|:') {
        throw "DockerHubRepository must be an untagged repository name, without spaces, URL scheme, digest, or port."
    }
    if ($Repository -notmatch '^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+$') {
        throw "DockerHubRepository has an invalid format. Use dockerhub-user/myvpn or registry.example.com/team/myvpn."
    }
}

function Get-NativeExitCode {
    param([string]$Command, [string[]]$Arguments)

    $savedErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $discardedOutput = & $Command @Arguments 2>&1
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
}

$originalLocation = Get-Location
$exitCode = 0

try {
    Assert-RepositoryName $DockerHubRepository

    foreach ($command in @("git", "docker")) {
        if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw "Required command '$command' was not found in PATH."
        }
    }

    if ((Get-NativeExitCode "docker" @("buildx", "version")) -ne 0) {
        throw "Docker Buildx is unavailable. Install or enable Docker Buildx."
    }
    if ((Get-NativeExitCode "docker" @("info")) -ne 0) {
        throw "Docker daemon is unavailable. Start Docker Desktop or the Docker service."
    }

    $repoRoot = ((& git rev-parse --show-toplevel 2>$null) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
        throw "Current location is not inside a Git repository."
    }
    Set-Location -LiteralPath $repoRoot

    foreach ($requiredFile in @("Dockerfile", ".dockerignore")) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $requiredFile) -PathType Leaf)) {
            throw "Required file '$requiredFile' is missing from the repository root."
        }
    }

    $commit = ((& git rev-parse --short HEAD 2>$null) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
        throw "Unable to determine the current Git commit."
    }

    $workingTree = @(& git status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Unable to read Git working tree status." }
    if ($workingTree.Count -gt 0 -and -not $NoPush) {
        throw "Working tree is not clean. Commit or stash changes before publishing an image."
    }
    if ($workingTree.Count -gt 0 -and $NoPush) {
        Write-Warning "Working tree is dirty; the locally built image is still tagged with commit $commit."
    }

    $immutableTag = "$DockerHubRepository`:$commit"
    $tags = @($immutableTag)
    if ($PublishStagingAlias) { $tags += "$DockerHubRepository`:staging" }

    Write-Output "Repository: $DockerHubRepository"
    Write-Output "Commit: $commit"
    Write-Output "Platform: linux/amd64"
    Write-Output "Tags:"
    $tags | ForEach-Object { Write-Output "  $_" }
    Write-Output "Push enabled: $(if ($NoPush) { 'no' } else { 'yes' })"
    if (-not $NoPush) { Write-Output "Ensure docker login has already completed before publishing." }

    $buildArguments = @("buildx", "build", "--platform", "linux/amd64", "--file", (Join-Path $repoRoot "Dockerfile"), "--tag", $immutableTag)
    if ($PublishStagingAlias) { $buildArguments += @("--tag", "$DockerHubRepository`:staging") }
    if ($NoCache) { $buildArguments += "--no-cache" }
    if ($NoPush) { $buildArguments += "--load" } else { $buildArguments += "--push" }
    $buildArguments += $repoRoot

    & docker @buildArguments
    if ($LASTEXITCODE -ne 0) {
        if ($NoPush) { throw "Local image build failed." }
        throw "Image build or registry push failed. If Docker Hub authentication failed, run 'docker login' and try again."
    }

    if ($NoPush) {
        Write-Output "Image built locally; registry push was skipped."
    } else {
        Write-Output "Published image:"
        Write-Output $immutableTag
        Write-Output ""
        Write-Output "Set in .env.staging:"
        Write-Output "MYVPN_IMAGE=$immutableTag"
    }
    if ($PublishStagingAlias) {
        Write-Output "Additional alias:"
        Write-Output "$DockerHubRepository`:staging"
    }
}
catch {
    [Console]::Error.WriteLine("Error: $($_.Exception.Message)")
    $exitCode = 1
}
finally {
    Set-Location -LiteralPath $originalLocation
}

if ($exitCode -ne 0) { exit $exitCode }
