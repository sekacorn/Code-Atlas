#!/usr/bin/env bash
#
# Code Atlas launcher — build, run and serve the tool from a simple menu.
#
#   ./atlas.sh                 open the interactive menu
#   ./atlas.sh build           compile the runnable jar (mvn clean install)
#   ./atlas.sh scan <repo>     scan a repository and write the HTML report
#   ./atlas.sh start           serve the report at http://127.0.0.1:<port> (loopback only)
#   ./atlas.sh stop            stop the report server
#   ./atlas.sh status          show build / scan / server status
#   ./atlas.sh orient|lineage|graph   run a query against the last-scanned repo
#
# Requires JDK 21 on PATH (and Maven for "build"). Serves via the JDK's built-in
# jwebserver (falls back to python). Fully offline; nothing binds beyond 127.0.0.1.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ATLAS_HOME="$SCRIPT_DIR"
JAR="$ATLAS_HOME/atlas-cli/target/atlas.jar"
REPORT_DIR="$ATLAS_HOME/atlas-report"
STATE_DIR="$ATLAS_HOME/.atlas-run"
PID_FILE="$STATE_DIR/server.pid"
REPO_FILE="$STATE_DIR/last-repo"
PORT="${ATLAS_PORT:-8137}"
mkdir -p "$STATE_DIR"

log()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

have() { command -v "$1" >/dev/null 2>&1; }

need_java() { have java || { err "Java 21+ not found on PATH."; return 1; }; }

# --- build -----------------------------------------------------------------
do_build() {
  need_java || return 1
  have mvn || { err "Maven (mvn) not found — needed only to build."; return 1; }
  log "Building Code Atlas (mvn clean install)…"
  if ( cd "$ATLAS_HOME" && mvn -q clean install ); then
    ok "Built: $JAR"
  else
    err "Build failed."; return 1
  fi
}

ensure_jar() {
  [[ -f "$JAR" ]] && return 0
  warn "The runnable jar is not built yet — building now."
  do_build
}

# --- scan ------------------------------------------------------------------
save_repo() { printf '%s' "$1" > "$REPO_FILE"; }
last_repo() { [[ -f "$REPO_FILE" ]] && cat "$REPO_FILE" || printf ''; }

do_scan() {
  ensure_jar || return 1
  local repo="${1:-}"
  if [[ -z "$repo" ]]; then read -rp "  Path to the repository to scan: " repo; fi
  [[ -d "$repo" ]] || { err "Not a directory: $repo"; return 1; }
  repo="$(cd "$repo" && pwd)"
  log "Scanning $repo …"
  if java -jar "$JAR" scan "$repo" --out "$REPORT_DIR"; then
    save_repo "$repo"
    ok "Report written: $REPORT_DIR/report.html"
    ok "Use option 3 (Start) to view it in a browser."
  else
    err "Scan failed."; return 1
  fi
}

require_repo() {
  local repo; repo="$(last_repo)"
  [[ -n "$repo" ]] || { err "No repository scanned yet — run a scan first."; return 1; }
  printf '%s' "$repo"
}

# --- report server (start / stop) ------------------------------------------
server_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid; pid="$(cat "$PID_FILE" 2>/dev/null)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

open_url() {
  local url="$1"
  if have cmd.exe;  then cmd.exe /c start "" "$url" >/dev/null 2>&1 &
  elif have xdg-open; then xdg-open "$url" >/dev/null 2>&1 &
  elif have open;     then open "$url" >/dev/null 2>&1 &
  fi
}

do_start() {
  [[ -f "$REPORT_DIR/report.html" ]] || { err "No report yet — run a scan first."; return 1; }
  local url="http://127.0.0.1:$PORT/report.html"
  if server_running; then ok "Already serving: $url"; open_url "$url"; return 0; fi
  if have jwebserver; then
    jwebserver -b 127.0.0.1 -p "$PORT" -d "$REPORT_DIR" >"$STATE_DIR/server.log" 2>&1 &
  elif have python; then
    python -m http.server "$PORT" --bind 127.0.0.1 --directory "$REPORT_DIR" >"$STATE_DIR/server.log" 2>&1 &
  else
    err "No jwebserver (JDK) or python to serve. Open $REPORT_DIR/report.html directly."; return 1
  fi
  echo "$!" > "$PID_FILE"
  sleep 1
  if server_running; then ok "Report server started (loopback only): $url"; open_url "$url"
  else err "Server did not start; see $STATE_DIR/server.log"; rm -f "$PID_FILE"; return 1; fi
}

do_stop() {
  if server_running; then
    local pid; pid="$(cat "$PID_FILE")"
    kill "$pid" 2>/dev/null; sleep 1; kill -9 "$pid" 2>/dev/null
    rm -f "$PID_FILE"; ok "Report server stopped."
  else
    rm -f "$PID_FILE"; log "  No report server is running."
  fi
}

# --- queries ---------------------------------------------------------------
do_orient()  { ensure_jar || return 1; local r; r="$(require_repo)" || return 1; java -jar "$JAR" orient --repo "$r"; }

do_lineage() {
  ensure_jar || return 1; local r; r="$(require_repo)" || return 1
  local start="${1:-}"
  if [[ -z "$start" ]]; then read -rp "  Start (e.g. \"POST /customers\" or a stable id): " start; fi
  java -jar "$JAR" lineage "$start" --downstream --repo "$r"
}

do_graph() {
  ensure_jar || return 1; local r; r="$(require_repo)" || return 1
  local type="${1:-}"
  if [[ -z "$type" ]]; then read -rp "  Graph [dependency|call|dead-code|architecture]: " type; fi
  local outfile="$REPORT_DIR/graph-$type.svg"
  if java -jar "$JAR" graph --type "$type" --format svg --repo "$r" -o "$outfile"; then
    ok "Graph written: $outfile (view via the report server or open directly)"
  fi
}

# --- status ----------------------------------------------------------------
do_status() {
  log "  Code Atlas status"
  [[ -f "$JAR" ]] && ok "jar built: $JAR" || warn "jar not built (option 1)"
  local r; r="$(last_repo)"
  [[ -n "$r" ]] && ok "last scan: $r" || warn "no repository scanned yet (option 2)"
  if server_running; then ok "report server: http://127.0.0.1:$PORT/report.html (running)"
  else log "  report server: stopped"; fi
}

# --- menu ------------------------------------------------------------------
menu() {
  while true; do
    printf '\n\033[1m==================  Code Atlas  ==================\033[0m\n'
    printf '  1) Build            compile the runnable jar\n'
    printf '  2) Scan a repository\n'
    printf '  3) Start report server   (view the HTML report)\n'
    printf '  4) Stop report server\n'
    printf '  5) Orient            "where do I start?"\n'
    printf '  6) Lineage           trace where data flows\n'
    printf '  7) Export a graph    (SVG)\n'
    printf '  8) Status\n'
    printf '  0) Quit\n'
    read -rp "  Choose: " choice
    case "$choice" in
      1) do_build;;
      2) do_scan;;
      3) do_start;;
      4) do_stop;;
      5) do_orient;;
      6) do_lineage;;
      7) do_graph;;
      8) do_status;;
      0) do_stop; log "  Goodbye."; exit 0;;
      *) err "Unknown option: $choice";;
    esac
  done
}

case "${1:-menu}" in
  build)   do_build;;
  scan)    shift; do_scan "${1:-}";;
  start)   do_start;;
  stop)    do_stop;;
  status)  do_status;;
  orient)  do_orient;;
  lineage) shift; do_lineage "${1:-}";;
  graph)   shift; do_graph "${1:-}";;
  menu|"") menu;;
  -h|--help|help)
    sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//';;
  *) err "Unknown action: $1 (try: build scan start stop status orient lineage graph)"; exit 2;;
esac
