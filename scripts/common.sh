# Helpers shared by run.sh and stop.sh (sourced, not executed).

if [ -t 2 ]; then
  YELLOW=$'\033[1;33m' RED=$'\033[1;31m' RESET=$'\033[0m'
else
  YELLOW="" RED="" RESET=""
fi
step() { printf '%s==>%s %s\n' "$YELLOW" "$RESET" "$*" >&2; }
die() {
  printf '%sGagal:%s %s\n' "$RED" "$RESET" "$*" >&2
  exit 1
}

DEFAULT_PORT=9095

# Port from "--port N" / "--port=N" among the arguments, else the default.
parse_port() {
  local port=$DEFAULT_PORT prev="" arg
  for arg in "$@"; do
    [ "$prev" = "--port" ] && port=$arg
    case "$arg" in --port=*) port=${arg#--port=} ;; esac
    prev=$arg
  done
  case "$port" in '' | *[!0-9]*) die "port tidak valid: $port" ;; esac
  echo "$port"
}

# Same folder termdeck.py keeps its data in (platformdirs user_data_dir); run.sh logs there.
data_dir() {
  echo "${TERMDECK_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/TermDeck}"
}

# PIDs listening on TCP port $1 (only processes visible to this user).
port_pids() {
  if command -v ss >/dev/null 2>&1; then
    ss -Hltnp "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u || true
  elif command -v lsof >/dev/null 2>&1; then
    lsof -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | sort -u || true
  elif command -v fuser >/dev/null 2>&1; then
    fuser -n tcp "$1" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' || true
  fi
}

# Stop every process listening on port $1.
free_port() {
  local pids pid
  pids=$(port_pids "$1")
  [ -z "$pids" ] && return 0
  for pid in $pids; do
    step "Menghentikan proses di port $1: PID $pid ($(ps -o args= -p "$pid" 2>/dev/null | cut -c1-90))"
    kill "$pid" 2>/dev/null || true
  done
  # SIGTERM lets TermDeck close its database cleanly; wait up to 5 s before forcing.
  for _ in $(seq 1 50); do
    [ -z "$(port_pids "$1")" ] && return 0
    sleep 0.1
  done
  for pid in $(port_pids "$1"); do
    step "PID $pid tidak berhenti, dipaksa (SIGKILL)"
    kill -9 "$pid" 2>/dev/null || true
  done
  sleep 0.5
  [ -z "$(port_pids "$1")" ] || die "port $1 masih dipakai dan prosesnya tidak bisa dihentikan."
}
