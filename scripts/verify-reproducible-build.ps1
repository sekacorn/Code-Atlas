param()

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$JarPath = Join-Path $RepoRoot "atlas-cli\target\atlas.jar"
$SourceTimestamp = (& git -C $RepoRoot show -s --format=%cI HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot read the source commit timestamp" }
$OutputTimestamp = [DateTimeOffset]::Parse($SourceTimestamp).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

function Build-And-Hash {
    Push-Location $RepoRoot
    try {
        & mvn -B -ntp clean package -DskipTests "-Dproject.build.outputTimestamp=$OutputTimestamp" | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Maven package failed" }
    } finally {
        Pop-Location
    }
    return (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

$First = Build-And-Hash
$Second = Build-And-Hash
if ($First -ne $Second) {
    throw "Reproducible build check failed: $First does not match $Second"
}

Write-Host "Reproducible shaded JAR SHA-256: $First"
