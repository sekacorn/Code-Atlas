param(
    [string]$Version = "",
    [string]$OutputDir = "dist",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$PomPath = Join-Path $RepoRoot "pom.xml"
$JarPath = Join-Path $RepoRoot "atlas-cli\target\atlas.jar"
$DistPath = Join-Path $RepoRoot $OutputDir

function Get-ProjectVersion {
    [xml]$pom = Get-Content -Path $PomPath
    $raw = $pom.project.version
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "Could not read project version from pom.xml"
    }
    return ($raw -replace "-SNAPSHOT$", "")
}

function Write-Checksum($Path) {
    $hash = Get-FileHash -Path $Path -Algorithm SHA256
    $line = "$($hash.Hash.ToLowerInvariant())  $(Split-Path -Leaf $Path)"
    # Write a LF line ending, not the CRLF that Set-Content emits on Windows: the sha256
    # files are verified on Linux with `sha256sum -c`, which treats a trailing CR as part
    # of the filename and then cannot find the archive. WriteAllText writes exactly these
    # bytes with no platform translation.
    [System.IO.File]::WriteAllText("$Path.sha256", "$line`n", (New-Object System.Text.ASCIIEncoding))
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Get-ProjectVersion
}

if (-not $SkipBuild) {
    Push-Location $RepoRoot
    try {
        & mvn -q clean package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $JarPath)) {
    throw "Runnable jar not found: $JarPath"
}

Remove-Item -Path $DistPath -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $DistPath | Out-Null

$PackageRootName = "code-atlas-$Version"
$Targets = @(
    @{ Name = "rhel-linux"; Type = "tar" },
    @{ Name = "ubuntu-linux"; Type = "tar" },
    @{ Name = "debian-linux"; Type = "tar" },
    @{ Name = "windows10"; Type = "zip" }
)

foreach ($target in $Targets) {
    $StageRoot = Join-Path $DistPath "stage-$($target.Name)"
    $PackageRoot = Join-Path $StageRoot $PackageRootName
    $PackagedJarDir = Join-Path $PackageRoot "atlas-cli\target"
    New-Item -ItemType Directory -Force -Path $PackageRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $PackagedJarDir | Out-Null

    Copy-Item -Path $JarPath -Destination (Join-Path $PackageRoot "atlas.jar")
    Copy-Item -Path $JarPath -Destination (Join-Path $PackagedJarDir "atlas.jar")
    Copy-Item -Path (Join-Path $RepoRoot "docs\RELEASE.md") -Destination $PackageRoot
    Copy-Item -Path (Join-Path $RepoRoot "atlas.sh") -Destination $PackageRoot
    Copy-Item -Path (Join-Path $RepoRoot "atlas.ps1") -Destination $PackageRoot

    # The whole doc set ships with the archive: these environments are offline, so a
    # reader cannot follow a link back to the repository to find the limitations,
    # the evidence model or the onboarding guide.
    $Docs = @("README.md", "KNOWN_LIMITATIONS.md", "CURRENT_STATE.md", "EVIDENCE_MODEL.md",
              "STABLE_IDENTIFIERS.md", "DATA_LINEAGE.md", "ONBOARDING.md", "AGENTS.md",
              "PERSISTENCE.md", "INCREMENTAL_ANALYSIS.md")
    foreach ($doc in $Docs) {
        $source = Join-Path $RepoRoot $doc
        if (-not (Test-Path $source)) {
            throw "Release doc missing: $doc"
        }
        Copy-Item -Path $source -Destination $PackageRoot
    }

    if ($target.Type -eq "zip") {
        $Archive = Join-Path $DistPath "code-atlas-$Version-$($target.Name).zip"
        Compress-Archive -Path $PackageRoot -DestinationPath $Archive -Force
    } else {
        $Archive = Join-Path $DistPath "code-atlas-$Version-$($target.Name).tar.gz"
        Push-Location $StageRoot
        try {
            tar -czf $Archive $PackageRootName
        } finally {
            Pop-Location
        }
    }

    Write-Checksum $Archive
}

Remove-Item -Path (Join-Path $DistPath "stage-*") -Recurse -Force
Write-Host "Release artifacts written to $DistPath"
