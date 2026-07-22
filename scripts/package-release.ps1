param(
    [string]$Version = "",
    [string]$OutputDir = "dist",
    [switch]$SkipBuild,
    [switch]$AllowDirty
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$PomPath = Join-Path $RepoRoot "pom.xml"
$JarPath = Join-Path $RepoRoot "atlas-cli\target\atlas.jar"
$SbomPath = Join-Path $RepoRoot "target\bom.json"
$DistPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $OutputDir))
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

function Get-ProjectVersion {
    [xml]$pom = Get-Content -Path $PomPath
    $raw = $pom.project.version
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "Could not read project version from pom.xml"
    }
    return ($raw -replace "-SNAPSHOT$", "")
}

function Get-GitValue([string[]]$Arguments) {
    $value = & git -C $RepoRoot @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git failed while collecting release provenance"
    }
    return ($value | Out-String).Trim()
}

function Get-NativeFirstLine([string]$Program, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Program @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "$Program failed while collecting build metadata"
        }
        return ($output | Select-Object -First 1).ToString()
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Write-Checksum([string]$Path) {
    $hash = Get-FileHash -Path $Path -Algorithm SHA256
    $line = "$($hash.Hash.ToLowerInvariant())  $(Split-Path -Leaf $Path)"
    [System.IO.File]::WriteAllText("$Path.sha256", "$line`n", [System.Text.ASCIIEncoding]::new())
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Get-ProjectVersion
}

$SourceCommit = Get-GitValue @("rev-parse", "HEAD")
$SourceTimestamp = Get-GitValue @("show", "-s", "--format=%cI", "HEAD")
$Dirty = -not [string]::IsNullOrWhiteSpace((Get-GitValue @("status", "--porcelain")))
if ($Dirty -and -not $AllowDirty) {
    throw "Release packaging requires a clean worktree. Commit the intended release or use -AllowDirty for local testing."
}
$OutputTimestamp = [DateTimeOffset]::Parse($SourceTimestamp).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

if (-not $SkipBuild) {
    Push-Location $RepoRoot
    try {
        Invoke-Checked "mvn" @("-B", "-ntp", "clean", "verify",
                "-Dproject.build.outputTimestamp=$OutputTimestamp")
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $JarPath)) {
    throw "Runnable jar not found: $JarPath"
}
if (-not (Test-Path $SbomPath)) {
    throw "CycloneDX SBOM not found: $SbomPath"
}

Remove-Item -LiteralPath $DistPath -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $DistPath | Out-Null

$ReleaseSbom = Join-Path $DistPath "code-atlas-$Version.cdx.json"
Copy-Item -LiteralPath $SbomPath -Destination $ReleaseSbom
Write-Checksum $ReleaseSbom

$PackageRootName = "code-atlas-$Version"
$Targets = @(
    @{ Name = "rhel-linux"; Type = "tar" },
    @{ Name = "ubuntu-linux"; Type = "tar" },
    @{ Name = "debian-linux"; Type = "tar" },
    @{ Name = "windows10"; Type = "zip" }
)

$JavaVersion = Get-NativeFirstLine "java" @("-version")
$MavenVersion = Get-NativeFirstLine "mvn" @("-version")

foreach ($target in $Targets) {
    $StageRoot = Join-Path $DistPath "stage-$($target.Name)"
    $PackageRoot = Join-Path $StageRoot $PackageRootName
    $PackagedJarDir = Join-Path $PackageRoot "atlas-cli\target"
    New-Item -ItemType Directory -Force -Path $PackagedJarDir | Out-Null

    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $PackageRoot "atlas.jar")
    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $PackagedJarDir "atlas.jar")
    Copy-Item -LiteralPath $ReleaseSbom -Destination (Join-Path $PackageRoot "bom.cdx.json")
    Copy-Item -LiteralPath (Join-Path $RepoRoot "docs\RELEASE.md") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "LICENSE") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "SECURITY.md") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "THIRD_PARTY_NOTICES.txt") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "third-party-licenses") -Destination $PackageRoot -Recurse
    Copy-Item -LiteralPath (Join-Path $RepoRoot "atlas.sh") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "atlas.ps1") -Destination $PackageRoot
    Copy-Item -LiteralPath (Join-Path $RepoRoot "assurance") -Destination $PackageRoot -Recurse

    $ReleaseNotes = Join-Path $RepoRoot "docs\RELEASE_NOTES_$Version.md"
    if (Test-Path $ReleaseNotes) {
        Copy-Item -LiteralPath $ReleaseNotes -Destination $PackageRoot
    }

    $ImageSource = Join-Path $RepoRoot "docs\images"
    $ImageDestination = Join-Path $PackageRoot "docs\images"
    if (-not (Test-Path $ImageSource)) {
        throw "Release screenshots missing: $ImageSource"
    }
    New-Item -ItemType Directory -Force -Path $ImageDestination | Out-Null
    Copy-Item -Path (Join-Path $ImageSource "*") -Destination $ImageDestination

    $Docs = @("README.md", "KNOWN_LIMITATIONS.md", "CURRENT_STATE.md", "EVIDENCE_MODEL.md",
              "STABLE_IDENTIFIERS.md", "DATA_LINEAGE.md", "ONBOARDING.md", "AGENTS.md",
              "PERSISTENCE.md", "INCREMENTAL_ANALYSIS.md")
    foreach ($doc in $Docs) {
        $source = Join-Path $RepoRoot $doc
        if (-not (Test-Path $source)) {
            throw "Release doc missing: $doc"
        }
        Copy-Item -LiteralPath $source -Destination $PackageRoot
    }

    $BuildInfo = [ordered]@{
        schemaVersion = 1
        product = "Code Atlas"
        version = $Version
        sourceRepository = "https://github.com/sekacorn/Code-Atlas"
        sourceCommit = $SourceCommit
        sourceCommitTimestamp = $SourceTimestamp
        sourceDirty = $Dirty
        java = $JavaVersion
        maven = $MavenVersion
        sbom = "bom.cdx.json"
    }
    [System.IO.File]::WriteAllText((Join-Path $PackageRoot "BUILD-INFO.json"),
            ($BuildInfo | ConvertTo-Json -Depth 5) + "`n", $Utf8NoBom)

    if ($target.Type -eq "zip") {
        $Archive = Join-Path $DistPath "code-atlas-$Version-$($target.Name).zip"
        Compress-Archive -Path $PackageRoot -DestinationPath $Archive -Force
    } else {
        $Archive = Join-Path $DistPath "code-atlas-$Version-$($target.Name).tar.gz"
        Push-Location $StageRoot
        try {
            Invoke-Checked "tar" @("-czf", $Archive, $PackageRootName)
        } finally {
            Pop-Location
        }
    }
    Write-Checksum $Archive
}

Get-ChildItem -LiteralPath $DistPath -Directory -Filter "stage-*" |
    Remove-Item -Recurse -Force

& (Join-Path $PSScriptRoot "generate-release-manifest.ps1") `
        -Version $Version -DistPath $DistPath -SourceCommit $SourceCommit `
        -SourceTimestamp $SourceTimestamp -SourceDirty:$Dirty
Write-Checksum (Join-Path $DistPath "release-manifest.json")

Write-Host "Release artifacts written to $DistPath"
