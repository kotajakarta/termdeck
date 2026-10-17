import asyncio
import posixpath
import shlex
import stat
from dataclasses import dataclass, field

import asyncssh

from .db import Store
from .schemas import DirEntry, DirListing, ServerSecrets, ServerStats

# Idle connections (no open terminal) are kept this long so sidebar polling
# and path browsing do not reconnect on every request.
IDLE_CLOSE_SECONDS = 90.0
CONNECT_TIMEOUT_SECONDS = 15.0

STATS_SCRIPT = """\
echo '@@hostname'; hostname 2>/dev/null || cat /etc/hostname 2>/dev/null
echo '@@os'; ( . /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" ) || uname -s
echo '@@kernel'; uname -sr 2>/dev/null
echo '@@uptime'; cat /proc/uptime 2>/dev/null
echo '@@load'; cat /proc/loadavg 2>/dev/null
echo '@@cpu'; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null
echo '@@mem'; head -5 /proc/meminfo 2>/dev/null
echo '@@disk'; df -Pk / 2>/dev/null | tail -1
"""


class HostKeyMismatch(Exception):
    """The server presented a different host key than the one trusted before."""

    def __init__(self, expected_fp: str, actual_fp: str) -> None:
        super().__init__(
            "Host key server BERUBAH! Tersimpan: "
            f"{expected_fp}, diterima: {actual_fp}. Kemungkinan server diinstal ulang "
            "atau ada serangan man-in-the-middle. Reset host key di pengaturan server jika Anda yakin aman."
        )
        self.expected_fp = expected_fp
        self.actual_fp = actual_fp


class ServerNotFound(Exception):
    pass


class RemotePathError(Exception):
    """A remote directory could not be read (missing, no permission, ...)."""


def quote_remote_path(path: str) -> str:
    """Quote a path for a POSIX shell while keeping a leading ~ expandable."""
    if path in ("", "~"):
        return "~"
    if path.startswith("~/"):
        return "~/" + shlex.quote(path[2:])
    return shlex.quote(path)


def build_cd_command(path: str, startup: str | None) -> str:
    """Shell command typed into a fresh session to jump to a saved path."""
    command = f"cd -- {quote_remote_path(path)} && clear"
    if startup and startup.strip():
        command += f" && {startup.strip()}"
    return command


def _to_int(value: str) -> int | None:
    try:
        return int(value)
    except ValueError:
        return None


def parse_stats(text: str) -> ServerStats:
    sections: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("@@"):
            current = sections.setdefault(line[2:].strip(), [])
        elif current is not None and line.strip():
            current.append(line.strip())

    def first(name: str) -> str:
        lines = sections.get(name) or [""]
        return lines[0]

    stats = ServerStats(hostname=first("hostname"), os=first("os"), kernel=first("kernel"))

    uptime = first("uptime").split()
    if uptime:
        try:
            stats.uptime_seconds = float(uptime[0])
        except ValueError:
            pass

    load_parts = first("load").split()[:3]
    try:
        stats.load = [float(p) for p in load_parts]
    except ValueError:
        stats.load = []

    stats.cpu_count = _to_int(first("cpu"))

    meminfo: dict[str, int] = {}
    for line in sections.get("mem", []):
        key, _, rest = line.partition(":")
        parts = rest.split()
        if parts and (num := _to_int(parts[0])) is not None:
            meminfo[key.strip()] = num * 1024
    if "MemTotal" in meminfo:
        stats.mem_total = meminfo["MemTotal"]
        available = meminfo.get("MemAvailable", meminfo.get("MemFree"))
        if available is not None:
            stats.mem_used = stats.mem_total - available

    disk = first("disk").split()
    if len(disk) >= 3:
        total, used = _to_int(disk[1]), _to_int(disk[2])
        if total is not None and used is not None:
            stats.disk_total = total * 1024
            stats.disk_used = used * 1024

    return stats


class _HostKeyClient(asyncssh.SSHClient):
    """Validates the host key before authentication (trust on first use)."""

    def __init__(self, expected: str | None) -> None:
        self._expected = asyncssh.import_public_key(expected) if expected else None
        self.seen_key: asyncssh.SSHKey | None = None

    def validate_host_public_key(self, host: str, addr: str, port: int, key: asyncssh.SSHKey) -> bool:
        self.seen_key = key
        if self._expected is None:
            return True
        return key.public_data == self._expected.public_data


def _no_known_hosts(host: str, addr: str, port: int | None) -> tuple[list[str], list[str], list[str]]:
    # An empty (but not None) trust list forces asyncssh to call
    # validate_host_public_key on our client instead of skipping validation.
    return [], [], []


async def open_connection(secrets: ServerSecrets) -> tuple[asyncssh.SSHClientConnection, asyncssh.SSHKey]:
    """Connect and authenticate. Returns the connection and the server host key."""
    client = _HostKeyClient(secrets.host_key)
    options: dict[str, object] = {
        "username": secrets.username,
        "known_hosts": _no_known_hosts,
        "config": [],
        "agent_path": None,
        "keepalive_interval": 30,
        "connect_timeout": CONNECT_TIMEOUT_SECONDS,
    }
    if secrets.auth_type == "key":
        if not secrets.private_key:
            raise ValueError("Private key belum diisi untuk server ini.")
        options["client_keys"] = [asyncssh.import_private_key(secrets.private_key, secrets.passphrase or None)]
        options["password"] = None
    else:
        options["client_keys"] = None
        options["password"] = secrets.password or ""

    try:
        conn, _ = await asyncssh.create_connection(
            lambda: client, secrets.host, secrets.port, **options  # type: ignore[arg-type]
        )
    except asyncssh.HostKeyNotVerifiable:
        expected = asyncssh.import_public_key(secrets.host_key) if secrets.host_key else None
        if expected is not None and client.seen_key is not None:
            raise HostKeyMismatch(expected.get_fingerprint(), client.seen_key.get_fingerprint()) from None
        raise

    server_key = conn.get_server_host_key() or client.seen_key
    assert server_key is not None
    return conn, server_key


@dataclass
class _Entry:
    conn: asyncssh.SSHClientConnection
    refs: int = 0
    idle_handle: asyncio.TimerHandle | None = field(default=None)


class ConnectionPool:
    """Shares one SSH connection per server across terminals and sidebar calls."""

    def __init__(self, store: Store) -> None:
        self._store = store
        self._entries: dict[int, _Entry] = {}
        self._locks: dict[int, asyncio.Lock] = {}

    def _lock(self, server_id: int) -> asyncio.Lock:
        return self._locks.setdefault(server_id, asyncio.Lock())

    async def acquire(self, server_id: int) -> asyncssh.SSHClientConnection:
        async with self._lock(server_id):
            entry = self._entries.get(server_id)
            if entry is not None and entry.conn.is_closed():
                self._entries.pop(server_id, None)
                entry = None
            if entry is None:
                secrets = self._store.get_server_secrets(server_id)
                if secrets is None:
                    raise ServerNotFound(f"Server {server_id} tidak ditemukan")
                conn, key = await open_connection(secrets)
                if secrets.host_key is None:
                    self._store.set_host_key(
                        server_id, key.export_public_key().decode("ascii").strip(), key.get_fingerprint()
                    )
                entry = _Entry(conn=conn)
                self._entries[server_id] = entry
            if entry.idle_handle is not None:
                entry.idle_handle.cancel()
                entry.idle_handle = None
            entry.refs += 1
            return entry.conn

    async def release(self, server_id: int) -> None:
        async with self._lock(server_id):
            entry = self._entries.get(server_id)
            if entry is None:
                return
            entry.refs = max(0, entry.refs - 1)
            if entry.refs == 0 and entry.idle_handle is None:
                loop = asyncio.get_running_loop()
                entry.idle_handle = loop.call_later(IDLE_CLOSE_SECONDS, self._close_if_idle, server_id, entry)

    def _close_if_idle(self, server_id: int, entry: _Entry) -> None:
        if self._entries.get(server_id) is entry and entry.refs == 0:
            self._entries.pop(server_id, None)
            entry.conn.close()

    async def drop(self, server_id: int) -> None:
        """Close the shared connection, e.g. after credentials changed."""
        async with self._lock(server_id):
            entry = self._entries.pop(server_id, None)
        if entry is not None:
            if entry.idle_handle is not None:
                entry.idle_handle.cancel()
            entry.conn.close()

    async def close_all(self) -> None:
        for server_id in list(self._entries):
            await self.drop(server_id)

    async def stats(self, server_id: int) -> ServerStats:
        conn = await self.acquire(server_id)
        try:
            result = await asyncio.wait_for(
                conn.run("sh -s", input=STATS_SCRIPT, check=False), timeout=CONNECT_TIMEOUT_SECONDS
            )
        finally:
            await self.release(server_id)
        output = result.stdout if isinstance(result.stdout, str) else ""
        return parse_stats(output)

    async def listdir(self, server_id: int, path: str) -> DirListing:
        conn = await self.acquire(server_id)
        try:
            try:
                listing = await self._listdir_sftp(conn, path)
            except asyncssh.ChannelOpenError:
                # Some servers disable the SFTP subsystem; fall back to ls.
                listing = await self._listdir_shell(conn, path)
        finally:
            await self.release(server_id)
        listing.entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))
        return listing

    @staticmethod
    async def _listdir_sftp(conn: asyncssh.SSHClientConnection, path: str) -> DirListing:
        async with conn.start_sftp_client() as sftp:
            try:
                home = await sftp.realpath(".")
                if path in ("", "~"):
                    target = home
                elif path.startswith("~/"):
                    target = posixpath.join(home, path[2:])
                else:
                    target = path
                target = await sftp.realpath(target)
                items = await sftp.readdir(target)
            except asyncssh.SFTPError as exc:
                raise RemotePathError(f"Tidak bisa membuka {path}: {exc.reason}") from exc
            entries: list[DirEntry] = []
            for item in items:
                name = str(item.filename)
                if name in (".", ".."):
                    continue
                full = posixpath.join(target, name)
                is_dir = item.attrs.type == asyncssh.FILEXFER_TYPE_DIRECTORY or (
                    item.attrs.permissions is not None and stat.S_ISDIR(item.attrs.permissions)
                )
                if item.attrs.type == asyncssh.FILEXFER_TYPE_SYMLINK:
                    try:
                        is_dir = await sftp.isdir(full)
                    except (asyncssh.SFTPError, OSError):
                        is_dir = False
                entries.append(DirEntry(name=name, path=full, is_dir=is_dir))
        return DirListing(path=str(target), entries=entries)

    @staticmethod
    async def _listdir_shell(conn: asyncssh.SSHClientConnection, path: str) -> DirListing:
        script = f"cd -- {quote_remote_path(path)} && pwd && LC_ALL=C ls -1ApL\n"
        result = await asyncio.wait_for(conn.run("sh -s", input=script, check=False), CONNECT_TIMEOUT_SECONDS)
        stdout = result.stdout if isinstance(result.stdout, str) else ""
        lines = stdout.splitlines()
        if result.exit_status != 0 or not lines:
            stderr = result.stderr if isinstance(result.stderr, str) else ""
            reason = stderr.strip().splitlines()[-1] if stderr.strip() else "folder tidak bisa dibaca"
            raise RemotePathError(f"Tidak bisa membuka {path}: {reason}")
        target = lines[0]
        entries = [
            DirEntry(name=line.rstrip("/"), path=posixpath.join(target, line.rstrip("/")), is_dir=line.endswith("/"))
            for line in lines[1:]
            if line and line not in ("./", "../")
        ]
        return DirListing(path=target, entries=entries)
