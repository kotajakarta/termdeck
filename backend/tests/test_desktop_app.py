"""Tests for scripts/desktop_app.py (desktop app window, Start Menu shortcut, tray installer)."""

import json
import socket
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import desktop_app  # noqa: E402

FAVICON = ROOT / "frontend" / "public" / "favicon-32.png"
TOUCH_ICON = ROOT / "frontend" / "public" / "apple-touch-icon.png"


def test_png_size_reads_ihdr() -> None:
    assert desktop_app.png_size(FAVICON.read_bytes()) == (32, 32)
    assert desktop_app.png_size(TOUCH_ICON.read_bytes()) == (180, 180)


def test_build_ico_embeds_pngs() -> None:
    small, big = FAVICON.read_bytes(), TOUCH_ICON.read_bytes()
    ico = desktop_app.build_ico([small, big])
    reserved, kind, count = struct.unpack_from("<HHH", ico, 0)
    assert (reserved, kind, count) == (0, 1, 2)
    entries = [struct.unpack_from("<BBBBHHII", ico, 6 + 16 * i) for i in range(count)]
    assert [(e[0], e[1]) for e in entries] == [(32, 32), (180, 180)]
    for entry, data in zip(entries, (small, big)):
        size, offset = entry[6], entry[7]
        assert ico[offset : offset + size] == data


def test_build_ico_uses_zero_for_256() -> None:
    fake = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 256, 256) + b"\x08\x06\x00\x00\x00"
    ico = desktop_app.build_ico([fake])
    assert struct.unpack_from("<BB", ico, 6) == (0, 0)


def test_render_launcher_vbs_runs_hidden_powershell() -> None:
    vbs = desktop_app.render_launcher_vbs(r"C:\Users\me\AppData\Local\TermDeck\termdeck-tray.ps1")
    assert "WScript.Shell" in vbs
    assert '""C:\\Users\\me\\AppData\\Local\\TermDeck\\termdeck-tray.ps1""' in vbs
    assert "-ExecutionPolicy Bypass" in vbs and "-WindowStyle Hidden" in vbs
    assert vbs.rstrip().endswith(", 0, False")


def test_render_config_is_json() -> None:
    data = json.loads(desktop_app.render_config("Rocky", "/home/me/xterm", 9100))
    assert data == {"distro": "Rocky", "repo": "/home/me/xterm", "port": 9100}


def test_is_wsl() -> None:
    assert desktop_app.is_wsl("5.15.167.4-microsoft-standard-WSL2") is True
    assert desktop_app.is_wsl("6.8.0-45-generic") is False


def test_find_linux_browser_prefers_chromium_family() -> None:
    available = {"firefox": "/usr/bin/firefox", "chromium": "/usr/bin/chromium"}
    assert desktop_app.find_linux_browser(available.get) == "/usr/bin/chromium"
    assert desktop_app.find_linux_browser({"firefox": "/usr/bin/firefox"}.get) is None


def test_linux_app_command(tmp_path: Path) -> None:
    cmd = desktop_app.linux_app_command("/usr/bin/chromium", "http://localhost:9095/", tmp_path)
    assert cmd[0] == "/usr/bin/chromium"
    assert "--app=http://localhost:9095/" in cmd
    assert f"--user-data-dir={tmp_path}" in cmd


def test_wait_for_port() -> None:
    with socket.socket() as srv:
        srv.bind(("127.0.0.1", 0))
        srv.listen()
        port = srv.getsockname()[1]
        assert desktop_app.wait_for_port(port, timeout=1) is True
    assert desktop_app.wait_for_port(port, timeout=0.3) is False


def test_tray_script_shipped() -> None:
    script = (ROOT / "scripts" / "windows" / "termdeck-tray.ps1").read_text(encoding="utf-8")
    assert script.isascii(), "Windows PowerShell 5.1 reads BOM-less files as ANSI"
    assert "'api/auth/lock-all'" in script
