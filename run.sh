#!/usr/bin/env bash
# TermDeck launcher for Linux: prepares everything on first run, then serves http://localhost:9095
#
#   ./run.sh                 setup if needed, stop whatever holds the port, start, open the browser
#   ./run.sh --setup         recreate the Python virtualenv and reinstall dependencies
#   ./run.sh --rebuild       rebuild the UI even if it looks up to date
#   ./run.sh --no-browser    other options go to termdeck.py (--no-browser, --port N)
set -euo pipefail
cd "$(dirname "$0")"

VENV=backend/.venv
PY=$VENV/bin/python
CHECK=scripts/setup_check.py

force_setup=0
force_build=0
app_args=()
for arg in "$@"; do
  case "$arg" in
    --setup) force_setup=1 ;;
    --rebuild) force_build=1 ;;
    -h | --help)
      sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) app_args+=("$arg") ;;
  esac
done

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

find_python() {
  local candidate
  for candidate in python3.13 python3.12 python3.11 python3.10 python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 &&
      "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

# 1. Python virtualenv ------------------------------------------------------
if [ "$force_setup" = 1 ] || [ ! -x "$PY" ]; then
  base=$(find_python) || die "Python 3.10+ tidak ditemukan. Pasang Python 3.10 atau lebih baru."
  step "Membuat virtualenv dengan $("$base" --version)"
  rm -rf "$VENV"
  "$base" -m venv "$VENV" || die "virtualenv gagal dibuat. Di Debian/Ubuntu: sudo apt install python3-venv"
fi

# 2. Python dependencies (reinstalled whenever requirements.txt changes) -----
if ! "$PY" "$CHECK" deps; then
  step "Memasang dependency Python"
  "$PY" -m pip install --quiet --upgrade pip || die "pip gagal diperbarui"
  "$PY" -m pip install -r backend/requirements.txt || die "dependency Python gagal dipasang (cek koneksi internet)"
  "$PY" "$CHECK" deps --write
fi

# 3. User interface (rebuilt whenever frontend sources change) ---------------
if [ "$force_build" = 1 ] || ! "$PY" "$CHECK" ui; then
  command -v npm >/dev/null 2>&1 || die "Node.js 18+ (npm) diperlukan untuk build UI. Pasang dari https://nodejs.org"
  if ! "$PY" "$CHECK" npm; then
    step "Memasang dependency UI (npm install)"
    (cd frontend && npm install --no-audit --no-fund) || die "npm install gagal"
  fi
  step "Build UI"
  (cd frontend && npm run build) || die "build UI gagal"
fi

# 4. Free the port: it is reserved for TermDeck, so an older instance is stopped -
port=9095
prev=""
for arg in ${app_args[@]+"${app_args[@]}"}; do
  [ "$prev" = "--port" ] && port=$arg
  case "$arg" in --port=*) port=${arg#--port=} ;; esac
  prev=$arg
done
case "$port" in '' | *[!0-9]*) die "port tidak valid: $port" ;; esac

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

free_port "$port"

step "Menjalankan TermDeck"
exec "$PY" termdeck.py ${app_args[@]+"${app_args[@]}"}
