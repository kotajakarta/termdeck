import asyncio
import posixpath
import secrets
import shlex
import stat
from dataclasses import dataclass, field
from datetime import datetime

import asyncssh

from .db import Store
from .schemas import DirEntry, DirListing, FileActionOut, FileContentOut, ServerSecrets, ServerStats

# Idle connections (no open terminal) are kept this long so sidebar polling
# and path browsing do not reconnect on every request.
IDLE_CLOSE_SECONDS = 90.0
CONNECT_TIMEOUT_SECONDS = 15.0

# Images pasted into a terminal are uploaded here (relative to the remote home)
# so CLI tools running on the server can read them by path.
PASTE_DIR = ".cache/termdeck/paste"
MAX_PASTE_BYTES = 20 * 1024 * 1024
MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024
MAX_UPLOAD_FILE_BYTES = 50 * 1024 * 1024
# Pastes are single-use: older ones are deleted on the next paste. The grace
# period keeps every image of a prompt that is still being written, since some
# CLIs only read @path references when the prompt is submitted.
PASTE_KEEP_SECONDS = 10 * 60

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


def sniff_image_type(data: bytes) -> str | None:
    """File extension for the image formats AI CLIs accept, detected from magic bytes."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def paste_image_name(ext: str, now: datetime | None = None) -> str:
    stamp = (now or datetime.now()).strftime("%Y%m%d-%H%M%S")
    return f"paste-{stamp}-{secrets.token_hex(3)}.{ext}"


def format_octal_perm(permissions: int | None) -> str:
    if permissions is None:
        return ""
    perm = permissions & 0o777
    return f"0{perm:03o}"


def parse_octal_mode(mode: str) -> int:
    trimmed = mode.strip()
    if not (3 <= len(trimmed) <= 4 and all(ch in "01234567" for ch in trimmed)):
        raise ValueError("Mode izin harus angka oktal 3-4 digit (contoh: 0755 atau 644).")
    return int(trimmed, 8)


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

    async def upload_paste(self, server_id: int, data: bytes, ext: str) -> str:
        """Store a pasted image on the server and return its absolute path."""
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    folder = posixpath.join(await sftp.realpath("."), PASTE_DIR)
                    await sftp.makedirs(folder, attrs=asyncssh.SFTPAttrs(permissions=0o700), exist_ok=True)
                    path = posixpath.join(folder, paste_image_name(ext))
                    async with sftp.open(path, "xb", attrs=asyncssh.SFTPAttrs(permissions=0o600)) as file:
                        await file.write(data)
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal menyimpan gambar di server: {exc.reason}") from exc
                await self._remove_old_pastes(sftp, folder, path)
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini, gambar tidak bisa dikirim.") from exc
        finally:
            await self.release(server_id)
        return path

    @staticmethod
    async def _resolve_sftp_path(sftp: asyncssh.SFTPClient, raw_path: str) -> str:
        trimmed = raw_path.strip()
        if not trimmed:
            raise ValueError("Path wajib diisi.")
        if trimmed in ("~",) or trimmed.startswith("~/"):
            home = str(await sftp.realpath("."))
            return home if trimmed == "~" else posixpath.join(home, trimmed[2:])
        return trimmed

    async def read_file(self, server_id: int, path: str) -> FileContentOut:
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, path)
                    attrs = await sftp.stat(target)
                    if attrs.type == asyncssh.FILEXFER_TYPE_DIRECTORY or (
                        attrs.permissions is not None and stat.S_ISDIR(attrs.permissions)
                    ):
                        raise RemotePathError("Tidak bisa membaca folder sebagai file teks.")
                    size = int(attrs.size or 0)
                    if size > MAX_TEXT_FILE_BYTES:
                        raise RemotePathError("Ukuran file terlalu besar untuk editor teks (maks 2 MB).")
                    async with sftp.open(target, "rb") as file:
                        data = await file.read()
                    content = data.decode("utf-8", errors="replace")
                    return FileContentOut(
                        path=str(target),
                        content=content,
                        size=size,
                        mtime=int(attrs.mtime) if attrs.mtime is not None else None,
                        mode=format_octal_perm(attrs.permissions) or "0644",
                    )
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal membaca file {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def write_file(self, server_id: int, path: str, content: str) -> FileActionOut:
        raw = content.encode("utf-8")
        if len(raw) > MAX_TEXT_FILE_BYTES:
            raise ValueError("Konten terlalu besar (maks 2 MB).")
        res = await self.upload_file(server_id, path, raw)
        return FileActionOut(path=res.path, message="File berhasil disimpan")

    async def mkdir(self, server_id: int, path: str) -> FileActionOut:
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, path)
                    await sftp.makedirs(target, exist_ok=True)
                    return FileActionOut(path=str(target), message="Folder berhasil dibuat")
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal membuat folder {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def rename_file(self, server_id: int, old_path: str, new_path: str) -> FileActionOut:
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    src = await self._resolve_sftp_path(sftp, old_path)
                    dst = await self._resolve_sftp_path(sftp, new_path)
                    await sftp.rename(src, dst)
                    return FileActionOut(path=str(dst), message="Berhasil mengubah nama / memindahkan")
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal memindahkan {old_path} ke {new_path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def chmod_file(self, server_id: int, path: str, mode: str) -> FileActionOut:
        perms = parse_octal_mode(mode)
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, path)
                    await sftp.chmod(target, perms)
                    return FileActionOut(path=str(target), message=f"Izin file diubah ke {format_octal_perm(perms)}")
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal mengubah izin {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def delete_file(self, server_id: int, path: str) -> None:
        trimmed = path.strip()
        if not trimmed or trimmed == "/":
            raise ValueError("Tidak diizinkan menghapus root directory.")
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, trimmed)
                    if target == "/":
                        raise ValueError("Tidak diizinkan menghapus root directory.")
                    if await sftp.isdir(target) and not await sftp.islink(target):
                        await sftp.rmtree(target)
                    else:
                        await sftp.remove(target)
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal menghapus {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def upload_file(self, server_id: int, path: str, data: bytes) -> FileActionOut:
        if len(data) > MAX_UPLOAD_FILE_BYTES:
            raise ValueError("Ukuran file melebihi batas 50 MB.")
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, path)
                    async with sftp.open(target, "wb") as file:
                        await file.write(data)
                    return FileActionOut(path=str(target), message="File berhasil diunggah")
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal menulis file {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    async def download_file(self, server_id: int, path: str) -> bytes:
        conn = await self.acquire(server_id)
        try:
            async with conn.start_sftp_client() as sftp:
                try:
                    target = await self._resolve_sftp_path(sftp, path)
                    attrs = await sftp.stat(target)
                    if attrs.type == asyncssh.FILEXFER_TYPE_DIRECTORY or (
                        attrs.permissions is not None and stat.S_ISDIR(attrs.permissions)
                    ):
                        raise RemotePathError("Tidak bisa mengunduh folder secara langsung.")
                    if (attrs.size or 0) > MAX_UPLOAD_FILE_BYTES:
                        raise RemotePathError("Ukuran file terlalu besar untuk diunduh langsung (maks 50 MB).")
                    async with sftp.open(target, "rb") as file:
                        return await file.read()
                except asyncssh.SFTPError as exc:
                    raise RemotePathError(f"Gagal mengunduh {path}: {exc.reason}") from exc
        except asyncssh.ChannelOpenError as exc:
            raise RemotePathError("SFTP tidak aktif di server ini.") from exc
        finally:
            await self.release(server_id)

    @staticmethod
    async def _remove_old_pastes(sftp: asyncssh.SFTPClient, folder: str, newest: str) -> None:
        """Best effort; the age is measured against the new file so client/server clock skew does not matter."""
        try:
            now = (await sftp.stat(newest)).mtime
            if now is None:
                return
            for item in await sftp.readdir(folder):
                name = str(item.filename)
                mtime = item.attrs.mtime
                if name.startswith("paste-") and mtime is not None and now - mtime > PASTE_KEEP_SECONDS:
                    await sftp.remove(posixpath.join(folder, name))
        except asyncssh.SFTPError:
            pass

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
                is_symlink = item.attrs.type == asyncssh.FILEXFER_TYPE_SYMLINK
                is_dir = item.attrs.type == asyncssh.FILEXFER_TYPE_DIRECTORY or (
                    item.attrs.permissions is not None and stat.S_ISDIR(item.attrs.permissions)
                )
                if is_symlink:
                    try:
                        is_dir = await sftp.isdir(full)
                    except (asyncssh.SFTPError, OSError):
                        is_dir = False
                entries.append(
                    DirEntry(
                        name=name,
                        path=full,
                        is_dir=is_dir,
                        size=int(item.attrs.size or 0),
                        mtime=int(item.attrs.mtime) if item.attrs.mtime is not None else None,
                        mode=format_octal_perm(item.attrs.permissions),
                        is_symlink=is_symlink,
                    )
                )
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
