import asyncio
import os
import stat
import time
from collections.abc import AsyncIterator
from datetime import datetime
from pathlib import Path

import asyncssh
import pytest

from app.db import Store
from app.schemas import ServerIn
from app.ssh import (
    PASTE_DIR,
    PASTE_KEEP_SECONDS,
    ConnectionPool,
    HostKeyMismatch,
    RemotePathError,
    build_cd_command,
    parse_stats,
    paste_image_name,
    sniff_image_type,
)


class _TestServer(asyncssh.SSHServer):
    def begin_auth(self, username: str) -> bool:
        return True

    def password_auth_supported(self) -> bool:
        return True

    def validate_password(self, username: str, password: str) -> bool:
        return username == "alice" and password == "wonderland"


async def _handle(process: asyncssh.SSHServerProcess[str]) -> None:
    process.stdout.write("hello from test server\n")
    process.exit(0)


async def _start_server(host_key: asyncssh.SSHKey) -> tuple[asyncssh.SSHAcceptor, int]:
    acceptor = await asyncssh.create_server(
        _TestServer,
        "127.0.0.1",
        0,
        server_host_keys=[host_key],
        process_factory=_handle,
        sftp_factory=True,
    )
    port = acceptor.sockets[0].getsockname()[1]
    return acceptor, port


@pytest.fixture()
def store(tmp_path: Path) -> Store:
    return Store(tmp_path / "data.db", bytes(32))


@pytest.fixture()
async def ssh_server() -> AsyncIterator[tuple[asyncssh.SSHKey, int]]:
    key = asyncssh.generate_private_key("ssh-ed25519")
    acceptor, port = await _start_server(key)
    yield key, port
    acceptor.close()
    await acceptor.wait_closed()


def _add_server(store: Store, port: int, password: str = "wonderland") -> int:
    srv = store.create_server(
        ServerIn(name="test", host="127.0.0.1", port=port, username="alice", password=password)
    )
    return srv.id


def test_build_cd_command_quotes() -> None:
    assert build_cd_command("/var/www/my app", None) == "cd -- '/var/www/my app' && clear"
    assert build_cd_command("/srv/it's", "") == "cd -- '/srv/it'\"'\"'s' && clear"
    assert build_cd_command("~", None) == "cd -- ~ && clear"
    assert build_cd_command("~/proj a", "git status") == "cd -- ~/'proj a' && clear && git status"


def test_parse_stats() -> None:
    text = "\n".join(
        [
            "@@hostname",
            "web-1",
            "@@os",
            "Ubuntu 24.04.1 LTS",
            "@@kernel",
            "Linux 6.8.0-45-generic",
            "@@uptime",
            "12345.67 23456.78",
            "@@load",
            "0.15 0.20 0.30 1/234 5678",
            "@@cpu",
            "4",
            "@@mem",
            "MemTotal:        8000000 kB",
            "MemFree:         1000000 kB",
            "MemAvailable:    6000000 kB",
            "@@disk",
            "/dev/sda1 100000 40000 60000 40% /",
        ]
    )
    stats = parse_stats(text)
    assert stats.hostname == "web-1"
    assert stats.os == "Ubuntu 24.04.1 LTS"
    assert stats.kernel == "Linux 6.8.0-45-generic"
    assert stats.uptime_seconds == pytest.approx(12345.67)
    assert stats.load == [0.15, 0.20, 0.30]
    assert stats.cpu_count == 4
    assert stats.mem_total == 8000000 * 1024
    assert stats.mem_used == 2000000 * 1024
    assert stats.disk_total == 100000 * 1024
    assert stats.disk_used == 40000 * 1024


def test_parse_stats_tolerates_missing_sections() -> None:
    stats = parse_stats("@@hostname\nbox\n@@load\n\n@@cpu\nnot-a-number\n")
    assert stats.hostname == "box"
    assert stats.load == []
    assert stats.cpu_count is None
    assert stats.mem_total is None


async def test_tofu_stores_host_key(store: Store, ssh_server: tuple[asyncssh.SSHKey, int]) -> None:
    key, port = ssh_server
    server_id = _add_server(store, port)
    pool = ConnectionPool(store)
    try:
        conn = await pool.acquire(server_id)
        result = await conn.run("anything", check=False)
        assert "hello from test server" in str(result.stdout)
        await pool.release(server_id)
    finally:
        await pool.close_all()
    out = store.get_server(server_id)
    assert out is not None and out.host_key_fingerprint == key.get_fingerprint()


async def test_hostkey_mismatch_rejected(store: Store, ssh_server: tuple[asyncssh.SSHKey, int]) -> None:
    _, port = ssh_server
    server_id = _add_server(store, port)
    other = asyncssh.generate_private_key("ssh-ed25519")
    store.set_host_key(server_id, other.export_public_key().decode(), other.get_fingerprint())
    pool = ConnectionPool(store)
    try:
        with pytest.raises(HostKeyMismatch) as info:
            await pool.acquire(server_id)
        assert other.get_fingerprint() in str(info.value)
    finally:
        await pool.close_all()


async def test_wrong_password_raises(store: Store, ssh_server: tuple[asyncssh.SSHKey, int]) -> None:
    _, port = ssh_server
    server_id = _add_server(store, port, password="nope")
    pool = ConnectionPool(store)
    try:
        with pytest.raises(asyncssh.PermissionDenied):
            await pool.acquire(server_id)
    finally:
        await pool.close_all()


async def test_listdir(store: Store, ssh_server: tuple[asyncssh.SSHKey, int], tmp_path: Path) -> None:
    _, port = ssh_server
    (tmp_path / "proj").mkdir()
    (tmp_path / "proj" / "app").mkdir()
    (tmp_path / "proj" / "readme.md").write_text("x")
    server_id = _add_server(store, port)
    pool = ConnectionPool(store)
    try:
        listing = await pool.listdir(server_id, str(tmp_path / "proj"))
    finally:
        await pool.close_all()
    assert listing.path == str(tmp_path / "proj")
    assert [(e.name, e.is_dir) for e in listing.entries] == [("app", True), ("readme.md", False)]
    assert listing.entries[0].path == str(tmp_path / "proj" / "app")


async def _run_shell(process: asyncssh.SSHServerProcess[str]) -> None:
    """Execute the requested command with the local /bin/sh (no SFTP subsystem)."""
    proc = await asyncio.create_subprocess_shell(
        process.command or "sh",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    await process.redirect(stdin=proc.stdin, stdout=proc.stdout, stderr=proc.stderr)
    process.exit(await proc.wait())


async def test_listdir_falls_back_to_shell_without_sftp(store: Store, tmp_path: Path) -> None:
    key = asyncssh.generate_private_key("ssh-ed25519")
    acceptor = await asyncssh.create_server(
        _TestServer, "127.0.0.1", 0, server_host_keys=[key], process_factory=_run_shell
    )
    port = acceptor.sockets[0].getsockname()[1]
    (tmp_path / "my proj").mkdir()
    (tmp_path / "my proj" / "src").mkdir()
    (tmp_path / "my proj" / ".env").write_text("x")
    (tmp_path / "my proj" / "link").symlink_to(tmp_path / "my proj" / "src")
    server_id = _add_server(store, port)
    pool = ConnectionPool(store)
    try:
        listing = await pool.listdir(server_id, str(tmp_path / "my proj"))
        with pytest.raises(RemotePathError):
            await pool.listdir(server_id, str(tmp_path / "missing"))
    finally:
        await pool.close_all()
        acceptor.close()
        await acceptor.wait_closed()
    assert listing.path == str(tmp_path / "my proj")
    assert [(e.name, e.is_dir) for e in listing.entries] == [("link", True), ("src", True), (".env", False)]


PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16


def test_sniff_image_type() -> None:
    assert sniff_image_type(PNG) == "png"
    assert sniff_image_type(b"\xff\xd8\xff\xe0rest") == "jpg"
    assert sniff_image_type(b"GIF89a....") == "gif"
    assert sniff_image_type(b"RIFF\x10\x00\x00\x00WEBPVP8 ") == "webp"
    assert sniff_image_type(b"<svg xmlns=...>") is None
    assert sniff_image_type(b"") is None


def test_paste_image_name() -> None:
    name = paste_image_name("png", datetime(2026, 10, 19, 8, 5, 3))
    assert name.startswith("paste-20261019-080503-") and name.endswith(".png")
    assert paste_image_name("png") != paste_image_name("png")


async def test_upload_paste(store: Store, tmp_path: Path) -> None:
    key = asyncssh.generate_private_key("ssh-ed25519")
    root = tmp_path / "home"
    root.mkdir()
    acceptor = await asyncssh.create_server(
        _TestServer,
        "127.0.0.1",
        0,
        server_host_keys=[key],
        process_factory=_handle,
        sftp_factory=lambda chan: asyncssh.SFTPServer(chan, chroot=str(root).encode()),
    )
    port = acceptor.sockets[0].getsockname()[1]
    folder = root / PASTE_DIR
    folder.mkdir(parents=True)
    stale = folder / "paste-20260101-000000-aaaaaa.png"
    recent = folder / "paste-20260101-000000-bbbbbb.png"
    unrelated = folder / "notes.txt"
    for path in (stale, recent, unrelated):
        path.write_bytes(PNG)
    old = time.time() - PASTE_KEEP_SECONDS - 60
    os.utime(stale, (old, old))
    os.utime(unrelated, (old, old))
    server_id = _add_server(store, port)
    pool = ConnectionPool(store)
    try:
        first = await pool.upload_paste(server_id, PNG, "png")
        second = await pool.upload_paste(server_id, PNG, "png")
    finally:
        await pool.close_all()
        acceptor.close()
        await acceptor.wait_closed()
    assert first != second
    assert first.startswith(f"/{PASTE_DIR}/paste-") and first.endswith(".png")
    local = root / first.lstrip("/")
    assert local.read_bytes() == PNG
    assert stat.S_IMODE(local.stat().st_mode) == 0o600
    assert (root / second.lstrip("/")).exists()
    assert not stale.exists()
    assert recent.exists() and unrelated.exists()


async def test_upload_paste_without_sftp(store: Store) -> None:
    key = asyncssh.generate_private_key("ssh-ed25519")
    acceptor = await asyncssh.create_server(
        _TestServer, "127.0.0.1", 0, server_host_keys=[key], process_factory=_handle
    )
    port = acceptor.sockets[0].getsockname()[1]
    server_id = _add_server(store, port)
    pool = ConnectionPool(store)
    try:
        with pytest.raises(RemotePathError, match="SFTP tidak aktif"):
            await pool.upload_paste(server_id, PNG, "png")
    finally:
        await pool.close_all()
        acceptor.close()
        await acceptor.wait_closed()
