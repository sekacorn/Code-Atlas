<#
  Code Atlas launcher (Windows PowerShell).

  Usage:
    .\atlas.ps1                     open the interactive menu
    .\atlas.ps1 build               compile the runnable jar (mvn clean install)
    .\atlas.ps1 scan <repo>         scan a repository and write the HTML report
    .\atlas.ps1 start               serve the report at http://127.0.0.1:<port> (loopback only)
    .\atlas.ps1 stop                stop the report server
    .\atlas.ps1 status              show build / scan / server status
    .\atlas.ps1 orient|lineage|graph|onboard [arg]   query/onboard the last-scanned repo

  Requires JDK 21 on PATH (and Maven for "build"). Serves via the JDK's built-in
  jwebserver (falls back to python). Fully offline; binds only to 127.0.0.1.
#>
param(
    [string]$Action = "menu",
    [string]$Arg = ""
)

$AtlasHome = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar       = Join-Path $AtlasHome "atlas-cli\target\atlas.jar"
$ReportDir = Join-Path $AtlasHome "atlas-report"
$StateDir  = Join-Path $AtlasHome ".atlas-run"
$PidFile   = Join-Path $StateDir "server.pid"
$RepoFile  = Join-Path $StateDir "last-repo"
if ($env:ATLAS_PORT) { $Port = $env:ATLAS_PORT } else { $Port = "8137" }
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

function Have($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "  [x]  $m" -ForegroundColor Red }

function Do-Build {
    if (-not (Have "java")) { Fail "Java 21+ not found on PATH."; return $false }
    if (-not (Have "mvn"))  { Fail "Maven (mvn) not found - needed only to build."; return $false }
    Write-Host "Building Code Atlas (mvn clean install)..."
    Push-Location $AtlasHome
    try { & mvn -q clean install; $code = $LASTEXITCODE } finally { Pop-Location }
    if ($code -eq 0) { Ok "Built: $Jar"; return $true } else { Fail "Build failed."; return $false }
}

function Ensure-Jar {
    if (Test-Path $Jar) { return $true }
    Warn "The runnable jar is not built yet - building now."
    return (Do-Build)
}

function Save-Repo($p) { [System.IO.File]::WriteAllText($RepoFile, $p) }
function Last-Repo {
    if (Test-Path $RepoFile) { return ([System.IO.File]::ReadAllText($RepoFile)).Trim() } else { return "" }
}
function Require-Repo {
    $r = Last-Repo
    if ([string]::IsNullOrWhiteSpace($r)) { Fail "No repository scanned yet - run a scan first."; return $null }
    return $r
}

function Do-Scan($repo) {
    if (-not (Ensure-Jar)) { return }
    if ([string]::IsNullOrWhiteSpace($repo)) { $repo = Read-Host "  Path to the repository to scan" }
    if (-not (Test-Path $repo -PathType Container)) { Fail "Not a directory: $repo"; return }
    $repo = (Resolve-Path $repo).Path
    Write-Host "Scanning $repo ..."
    & java -jar $Jar scan $repo --out $ReportDir
    if ($LASTEXITCODE -eq 0) {
        Save-Repo $repo
        Ok "Report written: $ReportDir\report.html"
        Ok "Use option 3 (Start) to view it in a browser."
    } else { Fail "Scan failed." }
}

function Server-Running {
    if (-not (Test-Path $PidFile)) { return $false }
    try { $procId = [int]([System.IO.File]::ReadAllText($PidFile).Trim()) } catch { return $false }
    return [bool](Get-Process -Id $procId -ErrorAction SilentlyContinue)
}

function Do-Start {
    if (-not (Test-Path (Join-Path $ReportDir "report.html"))) { Fail "No report yet - run a scan first."; return }
    $url = "http://127.0.0.1:$Port/report.html"
    if (Server-Running) { Ok "Already serving: $url"; Start-Process $url; return }
    $log = Join-Path $StateDir "server.log"
    $errlog = Join-Path $StateDir "server.err"
    if (Have "jwebserver") {
        $p = Start-Process -FilePath "jwebserver" `
            -ArgumentList @("-b","127.0.0.1","-p","$Port","-d","$ReportDir") `
            -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError $errlog
    } elseif (Have "python") {
        $p = Start-Process -FilePath "python" `
            -ArgumentList @("-m","http.server","$Port","--bind","127.0.0.1","--directory","$ReportDir") `
            -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError $errlog
    } else {
        Fail "No jwebserver (JDK) or python to serve. Open $ReportDir\report.html directly."; return
    }
    [System.IO.File]::WriteAllText($PidFile, "$($p.Id)")
    Start-Sleep -Seconds 1
    if (Server-Running) { Ok "Report server started (loopback only): $url"; Start-Process $url }
    else { Fail "Server did not start; see $log"; Remove-Item $PidFile -ErrorAction SilentlyContinue }
}

function Do-Stop {
    if (Server-Running) {
        $procId = [int]([System.IO.File]::ReadAllText($PidFile).Trim())
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Remove-Item $PidFile -ErrorAction SilentlyContinue
        Ok "Report server stopped."
    } else {
        Remove-Item $PidFile -ErrorAction SilentlyContinue
        Write-Host "  No report server is running."
    }
}

function Do-Orient {
    if (-not (Ensure-Jar)) { return }
    $r = Require-Repo; if ($r) { & java -jar $Jar orient --repo $r }
}

function Do-Lineage($start) {
    if (-not (Ensure-Jar)) { return }
    $r = Require-Repo; if (-not $r) { return }
    if ([string]::IsNullOrWhiteSpace($start)) { $start = Read-Host '  Start (e.g. "POST /customers" or a stable id)' }
    & java -jar $Jar lineage $start --downstream --repo $r
}

function Do-Graph($type) {
    if (-not (Ensure-Jar)) { return }
    $r = Require-Repo; if (-not $r) { return }
    if ([string]::IsNullOrWhiteSpace($type)) { $type = Read-Host "  Graph [dependency|call|dead-code|architecture]" }
    $out = Join-Path $ReportDir "graph-$type.svg"
    & java -jar $Jar graph --type $type --format svg --repo $r -o $out
    if ($LASTEXITCODE -eq 0) { Ok "Graph written: $out (view via the report server or open directly)" }
}

function Do-Onboard {
    if (-not (Ensure-Jar)) { return }
    $r = Require-Repo; if (-not $r) { return }
    $out = Join-Path $AtlasHome "atlas-onboarding-report"
    & java -jar $Jar onboard $r --output $out
    if ($LASTEXITCODE -eq 0) { Ok "Onboarding package written: $out\onboarding-report.html" }
}

function Do-Status {
    Write-Host "  Code Atlas status"
    if (Test-Path $Jar) { Ok "jar built: $Jar" } else { Warn "jar not built (option 1)" }
    $r = Last-Repo
    if (-not [string]::IsNullOrWhiteSpace($r)) { Ok "last scan: $r" } else { Warn "no repository scanned yet (option 2)" }
    if (Server-Running) { Ok "report server: http://127.0.0.1:$Port/report.html (running)" }
    else { Write-Host "  report server: stopped" }
}

function Show-Menu {
    while ($true) {
        Write-Host ""
        Write-Host "==================  Code Atlas  ==================" -ForegroundColor Cyan
        Write-Host "  1) Build            compile the runnable jar"
        Write-Host "  2) Scan a repository"
        Write-Host "  3) Start report server   (view the HTML report)"
        Write-Host "  4) Stop report server"
        Write-Host "  5) Orient            'where do I start?'"
        Write-Host "  6) Lineage           trace where data flows"
        Write-Host "  7) Export a graph    (SVG)"
        Write-Host "  8) Onboard           guided onboarding package"
        Write-Host "  9) Status"
        Write-Host "  0) Quit"
        $choice = Read-Host "  Choose"
        switch ($choice) {
            "1" { Do-Build | Out-Null }
            "2" { Do-Scan "" }
            "3" { Do-Start }
            "4" { Do-Stop }
            "5" { Do-Orient }
            "6" { Do-Lineage "" }
            "7" { Do-Graph "" }
            "8" { Do-Onboard }
            "9" { Do-Status }
            "0" { Do-Stop; Write-Host "  Goodbye."; return }
            default { Fail "Unknown option: $choice" }
        }
    }
}

switch ($Action.ToLower()) {
    "build"   { Do-Build | Out-Null }
    "scan"    { Do-Scan $Arg }
    "start"   { Do-Start }
    "stop"    { Do-Stop }
    "status"  { Do-Status }
    "orient"  { Do-Orient }
    "lineage" { Do-Lineage $Arg }
    "graph"   { Do-Graph $Arg }
    "onboard" { Do-Onboard }
    "menu"    { Show-Menu }
    default   { Fail "Unknown action: $Action (try: build scan start stop status orient lineage graph onboard)"; exit 2 }
}
