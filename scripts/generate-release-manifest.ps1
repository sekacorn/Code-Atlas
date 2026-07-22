param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$DistPath,
    [Parameter(Mandatory = $true)][string]$SourceCommit,
    [Parameter(Mandatory = $true)][string]$SourceTimestamp,
    [switch]$SourceDirty
)

$ErrorActionPreference = "Stop"
$DistPath = [System.IO.Path]::GetFullPath($DistPath)
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Get-MediaType([string]$Name) {
    if ($Name.EndsWith(".tar.gz")) { return "application/gzip" }
    if ($Name.EndsWith(".zip")) { return "application/zip" }
    if ($Name.EndsWith(".cdx.json")) { return "application/vnd.cyclonedx+json" }
    return "application/octet-stream"
}

function Get-NativeFirstLine([string]$Program, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Program @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw "$Program failed while collecting build metadata" }
        return ($output | Select-Object -First 1).ToString()
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$Artifacts = Get-ChildItem -LiteralPath $DistPath -File |
    Where-Object { $_.Name -match '\.(zip|tar\.gz|cdx\.json)$' } |
    Sort-Object Name |
    ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [ordered]@{
            file = $_.Name
            bytes = $_.Length
            sha256 = $hash.Hash.ToLowerInvariant()
            mediaType = Get-MediaType $_.Name
        }
    }

if ($Artifacts.Count -lt 5) {
    throw "Expected four platform archives and one CycloneDX SBOM in $DistPath"
}

$Manifest = [ordered]@{
    schemaVersion = 1
    product = "Code Atlas"
    version = $Version
    source = [ordered]@{
        repository = "https://github.com/sekacorn/Code-Atlas"
        commit = $SourceCommit
        commitTimestamp = $SourceTimestamp
        dirty = [bool]$SourceDirty
    }
    build = [ordered]@{
        java = Get-NativeFirstLine "java" @("-version")
        maven = Get-NativeFirstLine "mvn" @("-version")
        operatingSystem = [System.Environment]::OSVersion.VersionString
    }
    artifacts = @($Artifacts)
}

[System.IO.File]::WriteAllText((Join-Path $DistPath "release-manifest.json"),
        ($Manifest | ConvertTo-Json -Depth 8) + "`n", $Utf8NoBom)
