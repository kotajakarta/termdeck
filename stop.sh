#!/usr/bin/env bash
# Stops the TermDeck server started in the background by run.sh.
#
#   ./stop.sh              stop TermDeck on port 9095
#   ./stop.sh --port 9100  stop TermDeck on another port
set -euo pipefail
cd "$(dirname "$0")"
. scripts/common.sh

for arg in "$@"; do
  case "$arg" in
    -h | --help)
      sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
  esac
done

port=$(parse_port "$@")
pids=$(port_pids "$port")
unit="termdeck-$port"
unit_active=0
if command -v systemctl >/dev/null 2>&1 && systemctl --user is-active --quiet "$unit.service" 2>/dev/null; then
  unit_active=1
fi

if [ -z "$pids" ] && [ "$unit_active" = 0 ]; then
  echo "TermDeck tidak berjalan di port $port."
  exit 0
fi

# Unlike run.sh, only TermDeck itself is stopped: another program on the port is left alone.
for pid in $pids; do
  kill -0 "$pid" 2>/dev/null || continue
  args=$(ps -o args= -p "$pid" 2>/dev/null || true)
  case "$args" in
    *termdeck.py*) ;;
    *) die "port $port dipakai proses lain, bukan TermDeck (PID $pid: ${args:0:90}). Tidak dihentikan." ;;
  esac
done

if [ "$unit_active" = 1 ]; then
  systemctl --user stop "$unit.service" 2>/dev/null || true
  systemctl --user reset-failed "$unit.service" 2>/dev/null || true
fi

free_port "$port"
echo "TermDeck di port $port sudah dihentikan."
