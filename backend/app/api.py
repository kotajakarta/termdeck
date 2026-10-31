import asyncio
import json
import mimetypes
import secrets as pysecrets
from dataclasses import dataclass
from urllib.parse import quote, urlsplit

import asyncssh
from fastapi import (
    APIRouter,
    Depends,
    Header,
    HTTPException,
    Request,
    Response,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from pydantic import BaseModel

from .containers_api import register_containers_routes
from .cloudflare_api import register_cloudflare_routes
from .backup import (
    InvalidBackupError,
    InvalidBackupPinError,
    collect_store_backup,
    decrypt_config,
    encrypt_config,
    merge_backup_into_store,
)
from .db import Store
from .files_api import register_files_routes
from .schemas import (
    BackupExportIn,
    BackupImportIn,
    BackupStatsOut,
    BookmarkIn,
    BookmarkOut,
    ConnectionCheck,
    DirListing,
    FileActionOut,
    FileChmodIn,
    FileContentOut,
    FilePathIn,
    FileRenameIn,
    FileWriteIn,
    PastedImage,
    ServerIn,
    ServerOut,
    ServerSecrets,
    ServerStats,
    SnippetIn,
    SnippetOut,
)
from .ssh import (
    MAX_PASTE_BYTES,
    MAX_UPLOAD_FILE_BYTES,
    ConnectionPool,
    HostKeyMismatch,
    RemotePathError,
    ServerNotFound,
    build_cd_command,
    open_connection,
    sniff_image_type,
)
from .vault import InvalidPin, TooManyAttempts, Vault


class LoginIn(BaseModel):
    pin: str


class LoginOut(BaseModel):
    session: str


class ChangePinIn(BaseModel):
    old_pin: str
    new_pin: str


class AuthStatus(BaseModel):
    unlocked: bool
    session_valid: bool


class ServerOrderIn(BaseModel):
    ids: list[int]


@dataclass
class Unlocked:
    store: Store
    pool: ConnectionPool


class ServerTestIn(ServerIn):
    # When set, blank secrets fall back to the stored ones and the stored host key is enforced.
    id: int | None = None


def ssh_error_message(exc: BaseException) -> str:
    if isinstance(exc, HostKeyMismatch):
        return str(exc)
    if isinstance(exc, asyncssh.PermissionDenied):
        return "Autentikasi gagal: username / password / private key salah."
    if isinstance(exc, asyncssh.KeyImportError):
        return f"Private key tidak valid atau passphrase salah: {exc}"
    if isinstance(exc, asyncssh.DisconnectError):
        return f"Koneksi SSH terputus: {exc.reason}"
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return "Koneksi timeout. Periksa host/port dan firewall."
    if isinstance(exc, OSError):
        return f"Tidak bisa terhubung: {exc.strerror or exc}"
    return f"{type(exc).__name__}: {exc}"


SSH_ERRORS = (asyncssh.Error, asyncssh.KeyImportError, HostKeyMismatch, OSError, asyncio.TimeoutError, ValueError)


def pin_error(exc: InvalidPin | TooManyAttempts) -> HTTPException:
    if isinstance(exc, TooManyAttempts):
        return HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS, str(exc), headers={"Retry-After": str(int(exc.retry_after) + 1)}
        )
    return HTTPException(status.HTTP_403_FORBIDDEN, str(exc))


def create_api_router(vault: Vault, token: str) -> APIRouter:
    def require_token(request: Request, x_token: str = Header(default="")) -> None:
        candidate = x_token or request.query_params.get("token", "")
        if not pysecrets.compare_digest(candidate.encode(), token.encode()):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token tidak valid")

    def unlocked(request: Request, x_session: str = Header(default="")) -> Unlocked:
        candidate_session = x_session or request.query_params.get("session", "")
        store, pool = vault.store, vault.pool
        if not vault.check_session(candidate_session) or store is None or pool is None:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Sesi berakhir. Masukkan PIN lagi.")
        return Unlocked(store=store, pool=pool)

    router = APIRouter(prefix="/api", dependencies=[Depends(require_token)])

    def ensure_server(ctx: Unlocked, server_id: int) -> ServerOut:
        server = ctx.store.get_server(server_id)
        if server is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Server tidak ditemukan")
        return server

    # ---- auth ----------------------------------------------------------

    @router.get("/auth/status")
    async def auth_status(x_session: str = Header(default="")) -> AuthStatus:
        return AuthStatus(unlocked=vault.unlocked, session_valid=vault.check_session(x_session))

    @router.post("/auth/login")
    async def login(data: LoginIn) -> LoginOut:
        try:
            # scrypt + opening the database take ~150 ms: keep the event loop free.
            session = await asyncio.to_thread(vault.unlock, data.pin)
        except (InvalidPin, TooManyAttempts) as exc:
            raise pin_error(exc) from exc
        return LoginOut(session=session)

    @router.post("/auth/lock", status_code=status.HTTP_204_NO_CONTENT)
    async def lock(_: Unlocked = Depends(unlocked)) -> Response:
        await vault.lock()
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @router.post("/auth/lock-all", status_code=status.HTTP_204_NO_CONTENT)
    async def lock_all() -> Response:
        # Used by the desktop tray icon (the Android notification's "Kunci & Putuskan"): it has the
        # page token but no PIN session. Locking only removes access, it never reveals data.
        await vault.lock()
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @router.post("/auth/change-pin", status_code=status.HTTP_204_NO_CONTENT)
    async def change_pin(data: ChangePinIn, _: Unlocked = Depends(unlocked)) -> Response:
        try:
            await asyncio.to_thread(vault.change_pin, data.old_pin, data.new_pin)
        except (InvalidPin, TooManyAttempts) as exc:
            raise pin_error(exc) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    # ---- servers -------------------------------------------------------

    @router.get("/servers")
    async def list_servers(ctx: Unlocked = Depends(unlocked)) -> list[ServerOut]:
        return ctx.store.list_servers()

    @router.post("/servers", status_code=status.HTTP_201_CREATED)
    async def create_server(data: ServerIn, ctx: Unlocked = Depends(unlocked)) -> ServerOut:
        return ctx.store.create_server(data)

    # Registered before /servers/{server_id} so "order" is not parsed as an id.
    @router.put("/servers/order")
    async def reorder_servers(data: ServerOrderIn, ctx: Unlocked = Depends(unlocked)) -> list[ServerOut]:
        try:
            ctx.store.reorder_servers(data.ids)
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        return ctx.store.list_servers()

    @router.put("/servers/{server_id}")
    async def update_server(server_id: int, data: ServerIn, ctx: Unlocked = Depends(unlocked)) -> ServerOut:
        before = ctx.store.get_server_secrets(server_id)
        updated = ctx.store.update_server(server_id, data)
        if updated is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Server tidak ditemukan")
        # Only reconnect when something that affects the SSH session changed,
        # so renaming or recolouring keeps open terminals alive.
        if before != ctx.store.get_server_secrets(server_id):
            await ctx.pool.drop(server_id)
        return updated

    @router.delete("/servers/{server_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_server(server_id: int, ctx: Unlocked = Depends(unlocked)) -> Response:
        if not ctx.store.delete_server(server_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Server tidak ditemukan")
        await ctx.pool.drop(server_id)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @router.post("/servers/{server_id}/reset-hostkey")
    async def reset_host_key(server_id: int, ctx: Unlocked = Depends(unlocked)) -> ServerOut:
        ensure_server(ctx, server_id)
        ctx.store.set_host_key(server_id, None, None)
        await ctx.pool.drop(server_id)
        return ensure_server(ctx, server_id)

    @router.post("/servers/test")
    async def test_server(data: ServerTestIn, ctx: Unlocked = Depends(unlocked)) -> ConnectionCheck:
        stored = ctx.store.get_server_secrets(data.id) if data.id is not None else None
        same_endpoint = stored is not None and stored.host == data.host and stored.port == data.port
        candidate = ServerSecrets(
            id=data.id or 0,
            host=data.host,
            port=data.port,
            username=data.username,
            auth_type=data.auth_type,
            password=data.password if data.password is not None else (stored.password if stored else None),
            private_key=data.private_key
            if data.private_key is not None
            else (stored.private_key if stored else None),
            passphrase=data.passphrase if data.passphrase is not None else (stored.passphrase if stored else None),
            host_key=stored.host_key if stored is not None and same_endpoint else None,
        )
        try:
            conn, key = await open_connection(candidate)
        except SSH_ERRORS as exc:
            return ConnectionCheck(ok=False, message=ssh_error_message(exc))
        conn.close()
        return ConnectionCheck(ok=True, message=f"Berhasil terhubung. Host key: {key.get_fingerprint()}")

    @router.get("/servers/{server_id}/stats")
    async def server_stats(server_id: int, ctx: Unlocked = Depends(unlocked)) -> ServerStats:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.stats(server_id)
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.get("/servers/{server_id}/ls")
    async def list_dir(server_id: int, path: str = "~", ctx: Unlocked = Depends(unlocked)) -> DirListing:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.listdir(server_id, path)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.post("/servers/{server_id}/paste-image")
    async def paste_image(server_id: int, request: Request, ctx: Unlocked = Depends(unlocked)) -> PastedImage:
        """Raw image body (not multipart); saved on the server so terminal tools can open it by path."""
        ensure_server(ctx, server_id)
        too_large = HTTPException(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, f"Gambar maksimal {MAX_PASTE_BYTES // (1024 * 1024)} MB."
        )
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > MAX_PASTE_BYTES:
                raise too_large
        data = bytes(body)
        ext = sniff_image_type(data)
        if ext is None:
            raise HTTPException(
                status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Format gambar tidak didukung (PNG, JPEG, GIF, WebP)."
            )
        try:
            return PastedImage(path=await ctx.pool.upload_paste(server_id, data, ext))
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.get("/servers/{server_id}/files/read")
    async def read_file(server_id: int, path: str, ctx: Unlocked = Depends(unlocked)) -> FileContentOut:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.read_file(server_id, path)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.put("/servers/{server_id}/files/write")
    async def write_file(server_id: int, data: FileWriteIn, ctx: Unlocked = Depends(unlocked)) -> FileActionOut:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.write_file(server_id, data.path, data.content)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.post("/servers/{server_id}/files/mkdir")
    async def mkdir_file(server_id: int, data: FilePathIn, ctx: Unlocked = Depends(unlocked)) -> FileActionOut:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.mkdir(server_id, data.path)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.post("/servers/{server_id}/files/rename")
    async def rename_file(server_id: int, data: FileRenameIn, ctx: Unlocked = Depends(unlocked)) -> FileActionOut:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.rename_file(server_id, data.old_path, data.new_path)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.post("/servers/{server_id}/files/chmod")
    async def chmod_file(server_id: int, data: FileChmodIn, ctx: Unlocked = Depends(unlocked)) -> FileActionOut:
        ensure_server(ctx, server_id)
        try:
            return await ctx.pool.chmod_file(server_id, data.path, data.mode)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.delete("/servers/{server_id}/files", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_file(server_id: int, path: str, ctx: Unlocked = Depends(unlocked)) -> Response:
        ensure_server(ctx, server_id)
        try:
            await ctx.pool.delete_file(server_id, path)
            return Response(status_code=status.HTTP_204_NO_CONTENT)
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.post("/servers/{server_id}/files/upload")
    async def upload_file(
        server_id: int, path: str, request: Request, ctx: Unlocked = Depends(unlocked)
    ) -> FileActionOut:
        ensure_server(ctx, server_id)
        too_large = HTTPException(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            f"Ukuran file maksimal {MAX_UPLOAD_FILE_BYTES // (1024 * 1024)} MB.",
        )
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > MAX_UPLOAD_FILE_BYTES:
                raise too_large
        try:
            return await ctx.pool.upload_file(server_id, path, bytes(body))
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    @router.get("/servers/{server_id}/files/download")
    async def download_file(server_id: int, path: str, ctx: Unlocked = Depends(unlocked)) -> Response:
        ensure_server(ctx, server_id)
        filename = path.rstrip("/").rsplit("/", 1)[-1] or "download"
        safe_name = filename.replace('"', "_").replace("\\", "_")
        encoded = quote(filename)
        media_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        try:
            data = await ctx.pool.download_file(server_id, path)
            return Response(
                content=data,
                media_type=media_type,
                headers={"Content-Disposition": f'attachment; filename="{safe_name}"; filename*=UTF-8\'\'{encoded}'},
            )
        except RemotePathError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
        except SSH_ERRORS as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, ssh_error_message(exc)) from exc

    # ---- bookmarks -----------------------------------------------------

    @router.get("/bookmarks")
    async def list_bookmarks(server_id: int | None = None, ctx: Unlocked = Depends(unlocked)) -> list[BookmarkOut]:
        return ctx.store.list_bookmarks(server_id)

    @router.post("/bookmarks", status_code=status.HTTP_201_CREATED)
    async def create_bookmark(data: BookmarkIn, ctx: Unlocked = Depends(unlocked)) -> BookmarkOut:
        ensure_server(ctx, data.server_id)
        return ctx.store.create_bookmark(data)

    @router.put("/bookmarks/{bookmark_id}")
    async def update_bookmark(bookmark_id: int, data: BookmarkIn, ctx: Unlocked = Depends(unlocked)) -> BookmarkOut:
        ensure_server(ctx, data.server_id)
        updated = ctx.store.update_bookmark(bookmark_id, data)
        if updated is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Path tidak ditemukan")
        return updated

    @router.delete("/bookmarks/{bookmark_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_bookmark(bookmark_id: int, ctx: Unlocked = Depends(unlocked)) -> Response:
        if not ctx.store.delete_bookmark(bookmark_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Path tidak ditemukan")
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    # ---- snippets ------------------------------------------------------

    @router.get("/snippets")
    async def list_snippets(ctx: Unlocked = Depends(unlocked)) -> list[SnippetOut]:
        return ctx.store.list_snippets()

    @router.post("/snippets", status_code=status.HTTP_201_CREATED)
    async def create_snippet(data: SnippetIn, ctx: Unlocked = Depends(unlocked)) -> SnippetOut:
        return ctx.store.create_snippet(data)

    @router.put("/snippets/{snippet_id}")
    async def update_snippet(snippet_id: int, data: SnippetIn, ctx: Unlocked = Depends(unlocked)) -> SnippetOut:
        updated = ctx.store.update_snippet(snippet_id, data)
        if updated is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Snippet tidak ditemukan")
        return updated

    @router.delete("/snippets/{snippet_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_snippet(snippet_id: int, ctx: Unlocked = Depends(unlocked)) -> Response:
        if not ctx.store.delete_snippet(snippet_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Snippet tidak ditemukan")
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    # ---- config backup -------------------------------------------------

    @router.post("/config/export")
    async def export_config(data: BackupExportIn, ctx: Unlocked = Depends(unlocked)) -> dict:
        try:
            payload = await asyncio.to_thread(collect_store_backup, ctx.store)
            envelope = await asyncio.to_thread(encrypt_config, payload, data.pin)
            return envelope
        except InvalidBackupPinError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc

    @router.post("/config/import")
    async def import_config(data: BackupImportIn, ctx: Unlocked = Depends(unlocked)) -> BackupStatsOut:
        try:
            payload = await asyncio.to_thread(decrypt_config, data.backup, data.pin)
            stats = await asyncio.to_thread(merge_backup_into_store, ctx.store, payload)
            return BackupStatsOut(**stats)
        except InvalidBackupPinError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
        except InvalidBackupError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc

    register_files_routes(router, unlocked)
    register_containers_routes(router, unlocked)
    register_cloudflare_routes(router, unlocked)

    return router


def origin_allowed(origin: str | None, allowed_hosts: list[str] | None) -> bool:
    """Browsers always send Origin on WebSocket upgrades; reject foreign sites."""
    if allowed_hosts is None or origin is None:
        return True
    return urlsplit(origin).hostname in allowed_hosts


def create_ws_router(vault: Vault, allowed_hosts: list[str] | None = None) -> APIRouter:
    router = APIRouter()

    @router.websocket("/ws/terminal")
    async def terminal(
        ws: WebSocket,
        server_id: int,
        bookmark_id: int | None = None,
        cols: int = 80,
        rows: int = 24,
    ) -> None:
        store, pool = vault.store, vault.pool
        session = ws.query_params.get("session", "")
        if (
            not vault.check_session(session)
            or store is None
            or pool is None
            or not origin_allowed(ws.headers.get("origin"), allowed_hosts)
        ):
            await ws.close(code=4401)
            return
        await ws.accept()

        async def send_json(payload: dict[str, object]) -> None:
            await ws.send_text(json.dumps(payload))

        bookmark = store.get_bookmark(bookmark_id) if bookmark_id is not None else None
        await send_json({"type": "status", "status": "connecting"})
        try:
            conn = await pool.acquire(server_id)
        except (ServerNotFound, *SSH_ERRORS) as exc:
            await send_json({"type": "error", "message": ssh_error_message(exc)})
            await ws.close()
            return

        process: asyncssh.SSHClientProcess[bytes] | None = None
        try:
            process = await conn.create_process(
                term_type="xterm-256color",
                term_size=(max(cols, 10), max(rows, 5)),
                encoding=None,
            )
            await send_json({"type": "status", "status": "connected"})
            if bookmark is not None and bookmark.server_id == server_id:
                process.stdin.write((build_cd_command(bookmark.path, bookmark.startup_cmd) + "\n").encode())

            async def pump_output() -> None:
                assert process is not None
                while True:
                    chunk = await process.stdout.read(65536)
                    if not chunk:
                        break
                    await ws.send_bytes(chunk)

            async def pump_input() -> None:
                assert process is not None
                while True:
                    raw = await ws.receive_text()
                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(msg, dict):
                        continue
                    if msg.get("type") == "input" and isinstance(msg.get("data"), str):
                        process.stdin.write(msg["data"].encode("utf-8"))
                    elif msg.get("type") == "resize":
                        c, r = msg.get("cols"), msg.get("rows")
                        if isinstance(c, int) and isinstance(r, int) and c > 0 and r > 0:
                            process.change_terminal_size(c, r)

            out_task = asyncio.create_task(pump_output())
            in_task = asyncio.create_task(pump_input())
            done, pending = await asyncio.wait({out_task, in_task}, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                exc = task.exception()
                if exc is not None and not isinstance(exc, WebSocketDisconnect):
                    raise exc
            if out_task in done:
                await send_json({"type": "exit", "code": process.exit_status})
                await ws.close()
        except WebSocketDisconnect:
            pass
        except SSH_ERRORS as exc:
            try:
                await send_json({"type": "error", "message": ssh_error_message(exc)})
                await ws.close()
            except (RuntimeError, WebSocketDisconnect):
                pass
        finally:
            if process is not None:
                process.close()
            await pool.release(server_id)

    return router
