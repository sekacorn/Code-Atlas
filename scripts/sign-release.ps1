param(
    [string]$DistPath = "dist",
    [string]$SigningKey = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DistPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $DistPath))

if (-not (Get-Command gpg -ErrorAction SilentlyContinue)) {
    throw "GPG is not installed or is not on PATH"
}

$KeyArguments = @()
if (-not [string]::IsNullOrWhiteSpace($SigningKey)) {
    $KeyArguments = @("--local-user", $SigningKey)
}

& gpg --batch @KeyArguments --list-secret-keys
if ($LASTEXITCODE -ne 0) {
    throw "No usable secret signing key was found. Import an independently trusted maintainer key first."
}

$Files = Get-ChildItem -LiteralPath $DistPath -File |
    Where-Object { $_.Extension -ne ".asc" } |
    Sort-Object Name
foreach ($file in $Files) {
    & gpg --batch --yes --armor @KeyArguments --output "$($file.FullName).asc" `
        --detach-sign $file.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "GPG signing failed for $($file.Name)"
    }
}

Write-Host "Detached signatures written beside release artifacts in $DistPath"
