param(
    [string]$DistPath = "dist",
    [switch]$RequireSignatures
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DistPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $DistPath))
$ManifestPath = Join-Path $DistPath "release-manifest.json"

if (-not (Test-Path $ManifestPath)) {
    throw "Release manifest not found: $ManifestPath"
}
$Manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json

$SbomArtifacts = @($Manifest.artifacts | Where-Object { $_.file -match '\.cdx\.json$' })
if ($SbomArtifacts.Count -ne 1) {
    throw "Release manifest must contain exactly one CycloneDX SBOM"
}
$SbomPath = Join-Path $DistPath $SbomArtifacts[0].file
$Sbom = Get-Content -LiteralPath $SbomPath -Raw | ConvertFrom-Json
if ($Sbom.bomFormat -ne "CycloneDX" -or $Sbom.specVersion -ne "1.6") {
    throw "Release SBOM must be CycloneDX 1.6 JSON"
}
if ($Sbom.serialNumber -notmatch '^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
    throw "Release SBOM is missing a valid deterministic serial number"
}
if ($Sbom.metadata.component.version -ne $Manifest.version) {
    throw "Release SBOM component version does not match the manifest"
}

foreach ($artifact in $Manifest.artifacts) {
    $path = Join-Path $DistPath $artifact.file
    if (-not (Test-Path $path)) {
        throw "Manifest artifact is missing: $($artifact.file)"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $artifact.sha256) {
        throw "SHA-256 mismatch for $($artifact.file)"
    }
    $sidecar = "$path.sha256"
    if (-not (Test-Path $sidecar)) {
        throw "Checksum sidecar is missing: $(Split-Path -Leaf $sidecar)"
    }
    $expectedLine = "$actual  $($artifact.file)"
    if ((Get-Content -LiteralPath $sidecar -Raw).Trim() -ne $expectedLine) {
        throw "Checksum sidecar does not match $($artifact.file)"
    }
}

$Required = @("LICENSE", "SECURITY.md", "THIRD_PARTY_NOTICES.txt", "BUILD-INFO.json",
        "bom.cdx.json", "assurance/README.md", "assurance/THREAT_MODEL.md",
        "third-party-licenses/H2-LICENSE.txt")
$Archives = $Manifest.artifacts | Where-Object { $_.file -match '\.(zip|tar\.gz)$' }
foreach ($artifact in $Archives) {
    $path = Join-Path $DistPath $artifact.file
    if ($artifact.file.EndsWith(".zip")) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
        try {
            $entries = @($zip.Entries | ForEach-Object { $_.FullName.Replace("\", "/") })
        } finally {
            $zip.Dispose()
        }
    } else {
        $entries = @(& tar -tzf $path)
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $($artifact.file)" }
    }
    foreach ($required in $Required) {
        if (-not ($entries | Where-Object { $_.TrimEnd("/").EndsWith("/$required") })) {
            throw "$($artifact.file) is missing $required"
        }
    }
}

if ($RequireSignatures) {
    if (-not (Get-Command gpg -ErrorAction SilentlyContinue)) {
        throw "GPG is required to verify detached signatures"
    }
    $SignedFiles = @($ManifestPath) + @($Manifest.artifacts | ForEach-Object { Join-Path $DistPath $_.file })
    foreach ($file in $SignedFiles) {
        if (-not (Test-Path "$file.asc")) {
            throw "Detached signature is missing for $(Split-Path -Leaf $file)"
        }
        & gpg --verify "$file.asc" $file
        if ($LASTEXITCODE -ne 0) { throw "Signature verification failed for $file" }
    }
}

Write-Host "Verified $($Manifest.artifacts.Count) artifacts and $($Archives.Count) platform archives."
