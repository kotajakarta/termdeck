#!/usr/bin/env bash
# TermDeck launcher for Linux: prepares everything on first run, then serves http://localhost:9095
#
#   ./run.sh                 setup if needed, stop whatever holds the port, start in the background, open the app window
#   ./run.sh --setup         recreate the Python virtualenv and reinstall dependencies
#   ./run.sh --rebuild       rebuild the UI even if it looks up to date
#   ./run.sh --no-push       skip auto-push to GitHub repository
#   ./run.sh --foreground    stay attached to this terminal (Ctrl+C stops it)
#   ./run.sh --no-app        open a normal browser tab instead of the app window (+ tray icon on WSL)
#   ./run.sh --no-browser    other options go to termdeck.py (--no-browser, --port N)
# Stop the background server with ./stop.sh (same --port if one was given).
set -euo pipefail
cd "$(dirname "$0")"
. scripts/common.sh

VENV=backend/.venv
PY=$VENV/bin/python
CHECK=scripts/setup_check.py

force_setup=0
force_build=0
foreground=0
no_app=0
no_browser=0
no_push=0
app_args=()
for arg in "$@"; do
  case "$arg" in
    --setup) force_setup=1 ;;
    --rebuild) force_build=1 ;;
    --foreground) foreground=1 ;;
    --no-app) no_app=1 ;;
    --no-push) no_push=1 ;;
    --no-browser)
      no_browser=1
      app_args+=("$arg")
      ;;
    -h | --help)
      sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) app_args+=("$arg") ;;
  esac
done

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

# 4. Push ke GitHub repository ----------------------------------------------
if [ "$no_push" = 0 ] && command -v git >/dev/null 2>&1 && [ -d .git ]; then
  repo_url="https://github.com/kotajakarta/termdeck.git"
  step "Push kode ke GitHub ($repo_url)"
  if ! git remote get-url origin >/dev/null 2>&1; then
    git remote add origin "$repo_url" 2>/dev/null || true
  else
    git remote set-url origin "$repo_url" 2>/dev/null || true
  fi
  current_branch=$(git branch --show-current 2>/dev/null || echo "master")
  if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Auto-commit: $(date '+%Y-%m-%d %H:%M:%S')" || true
  fi
  git push -u origin "$current_branch" || echo "Peringatan: Gagal push ke GitHub (cek otentikasi/koneksi)."
fi

# 5. Free the port: it is reserved for TermDeck, so an older instance is stopped -
port=$(parse_port ${app_args[@]+"${app_args[@]}"})
unit="termdeck-$port"
if command -v systemctl >/dev/null 2>&1; then
  systemctl --user stop "$unit.service" 2>/dev/null || true
  systemctl --user reset-failed "$unit.service" 2>/dev/null || true
fi
free_port "$port"

# App mode (like the Android app): the server opens nothing itself, desktop_app.py opens the
# app window afterwards (Chrome/Edge --app + Start Menu shortcut + tray icon on WSL).
app_mode=0
if [ "$no_app" = 0 ] && [ "$no_browser" = 0 ]; then
  app_mode=1
  app_args+=(--no-browser)
fi

if [ "$foreground" = 1 ]; then
  step "Menjalankan TermDeck (Ctrl+C untuk berhenti)"
  if [ "$app_mode" = 1 ]; then
    "$PY" scripts/desktop_app.py open --port "$port" --wait &
  fi
  exec "$PY" termdeck.py ${app_args[@]+"${app_args[@]}"}
fi

# 6. Start detached: independent background process so closing this terminal does not stop it --
log_dir=$(data_dir)
mkdir -p "$log_dir"
log="$log_dir/termdeck-$port.log"

use_systemd=0
if command -v systemd-run >/dev/null 2>&1 && systemd-run --user --quiet true 2>/dev/null; then
  use_systemd=1
fi

if [ "$use_systemd" = 1 ]; then
  step "Menjalankan TermDeck di latar belakang (systemd user unit)"
  # systemd user unit isolates the process from this login session (session-XX.scope),
  # ensuring that closing the terminal or SSH session will NOT terminate TermDeck.
  systemd-run --user \
    --unit="$unit" \
    --working-directory="$PWD" \
    --setenv=PATH="$PATH" \
    ${DISPLAY+--setenv=DISPLAY="$DISPLAY"} \
    ${WAYLAND_DISPLAY+--setenv=WAYLAND_DISPLAY="$WAYLAND_DISPLAY"} \
    --property="StandardOutput=append:$log" \
    --property="StandardError=append:$log" \
    "$PWD/$PY" "$PWD/termdeck.py" ${app_args[@]+"${app_args[@]}"} >/dev/null
else
  step "Menjalankan TermDeck di latar belakang (setsid)"
  nohup setsid "$PY" termdeck.py ${app_args[@]+"${app_args[@]}"} >"$log" 2>&1 </dev/null &
  pid_bg=$!
  disown -a 2>/dev/null || true
fi

pid=""
for _ in $(seq 1 150); do
  pid=$(port_pids "$port" | head -n 1)
  [ -n "$pid" ] && break
  if [ "$use_systemd" = 1 ]; then
    if ! systemctl --user is-active --quiet "$unit.service"; then
      tail -n 20 "$log" >&2
      die "TermDeck berhenti saat start (log lengkap: $log)"
    fi
  else
    if ! kill -0 "$pid_bg" 2>/dev/null; then
      tail -n 20 "$log" >&2
      die "TermDeck berhenti saat start (log lengkap: $log)"
    fi
  fi
  sleep 0.1
done
[ -n "$pid" ] || die "TermDeck belum siap di port $port setelah 15 detik (log: $log)"

stop_cmd="./stop.sh"
[ "$port" = "$DEFAULT_PORT" ] || stop_cmd="./stop.sh --port $port"
echo "TermDeck berjalan di http://localhost:$port (PID $pid)"
echo "Log: $log"
echo "Terminal ini boleh ditutup. Untuk menghentikan: $stop_cmd"

if [ "$app_mode" = 1 ]; then
  step "Membuka jendela aplikasi TermDeck"
  "$PY" scripts/desktop_app.py open --port "$port" ||
    echo "Jendela aplikasi gagal dibuka; buka http://localhost:$port di browser, atau jalankan dengan --no-app."
fi
