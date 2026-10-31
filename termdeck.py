"""TermDeck: serves the web UI at http://localhost:9095 and opens it in the default browser.

    python termdeck.py                 start and open the browser
    python termdeck.py --no-browser    start without opening the browser
    python termdeck.py --port 9100     use another port
"""

import argparse
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "backend"))

import uvicorn  # noqa: E402

from app.config import data_dir, initial_pin  # noqa: E402
from app.main import LOCAL_HOSTS, create_app  # noqa: E402
from app.vault import Vault  # noqa: E402

STATIC_DIR = ROOT / "frontend" / "dist"
DEFAULT_PORT = 9095
DEFAULT_HOST = "127.0.0.1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="TermDeck - terminal SSH multi-server di browser")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"port (default: {DEFAULT_PORT})")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"host bind address (default: {DEFAULT_HOST})")
    parser.add_argument("--no-browser", action="store_true", help="jangan buka browser otomatis")
    parser.add_argument("--token", default="", help="token sesi tetap, untuk development (default: acak)")
    return parser.parse_args()


def port_available(port: int, host: str = DEFAULT_HOST) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        # Same option uvicorn uses: connections left over from a just-stopped
        # instance (TIME_WAIT/FIN_WAIT) must not count as "in use", only a
        # socket that is actually listening.
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((host, port))
        except OSError:
            return False
    return True


def is_wsl() -> bool:
    return "microsoft" in platform.release().lower()


def open_browser(url: str) -> None:
    """Open the URL in the user's graphical browser, never a terminal browser."""
    if is_wsl():
        # Under WSL the browser lives on the Windows side and reaches the server
        # through WSL's localhost forwarding.
        for command in (["wslview", url], ["cmd.exe", "/c", "start", "", url]):
            if shutil.which(command[0]):
                subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
        return
    if sys.platform.startswith("linux") and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        return
    webbrowser.open(url)


def open_when_ready(server: uvicorn.Server, url: str) -> None:
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    if server.started:
        open_browser(url)


def main() -> int:
    args = parse_args()
    if not (STATIC_DIR / "index.html").exists():
        print(f"UI belum di-build: {STATIC_DIR} tidak ada. Jalankan ./run.sh, atau `npm run build` di folder frontend.")
        return 1
    if not port_available(args.port, args.host):
        print(
            f"Port {args.port} sudah dipakai. Jika TermDeck sudah berjalan, buka http://localhost:{args.port}\n"
            "Atau jalankan dengan port lain, mis. --port 9100"
        )
        return 1

    folder = data_dir()
    token = args.token or secrets.token_urlsafe(32)
    # The encrypted database stays closed until the PIN is entered in the browser.
    allowed_hosts = LOCAL_HOSTS if args.host in ("127.0.0.1", "localhost") else None
    app = create_app(Vault(folder, initial_pin()), token, STATIC_DIR, allowed_hosts=allowed_hosts)
    server = uvicorn.Server(uvicorn.Config(app, host=args.host, port=args.port, log_level="warning"))

    url = f"http://localhost:{args.port}" if args.host in ("127.0.0.1", "0.0.0.0") else f"http://{args.host}:{args.port}"
    print(f"TermDeck berjalan di {url}\nData: {folder}\nTekan Ctrl+C untuk berhenti.", flush=True)
    if not args.no_browser:
        threading.Thread(target=open_when_ready, args=(server, url), daemon=True).start()
    server.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
