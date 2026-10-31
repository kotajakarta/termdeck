import asyncio
import json
import mimetypes
import posixpath
import shlex
import stat
import struct
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, AsyncIterator, Callable, Literal
from urllib.parse import quote

import asyncssh
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .db import Store
from .schemas import BookmarkIn
from .ssh import MAX_UPLOAD_FILE_BYTES, ConnectionPool

_AGENT_SOURCE = (Path(__file__).resolve().parent / "files_agent.py").read_text(encoding="utf-8")
_AGENT_CMD = f"python3 -c {shlex.quote(_AGENT_SOURCE)}"

PROTECTED = {
    "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/opt", "/proc", "/root",
    "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var",
}

_VIEW_TYPES: dict[str, str] = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "gif": "image/gif",
    "webp": "image/webp",
    "bmp": "image/bmp",
    "ico": "image/x-icon",
    "avif": "image/avif",
    "svg": "image/svg+xml",
    "mp4": "video/mp4",
    "m4v": "video/mp4",
    "webm": "video/webm",
    "ogv": "video/ogg",
    "mov": "video/quicktime",
    "mkv": "video/webm",
    "mp3": "audio/mpeg",
    "m4a": "audio/mp4",
    "ogg": "audio/ogg",
    "oga": "audio/ogg",
    "opus": "audio/ogg",
    "wav": "audio/wav",
    "flac": "audio/flac",
    "pdf": "application/pdf",
}

_MEDIA_CSP = "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox"
_PDF_CSP = "default-src 'none'; frame-ancestors 'self'"


def _abs_path(path: str) -> str:
    if not path or not path.startswith("/") or "\x00" in path:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path must be absolute")
    return posixpath.normpath(path) if path != "/" else "/"


def _file_name(name: str) -> str:
    if not name or name in (".", "..") or "/" in name or "\x00" in name or len(name.encode("utf-8")) > 255:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid file name")
    return name


def _sftp_entry_dict(path: str, name: str, attrs: asyncssh.SFTPAttrs, target: str | None = None, target_type: str | None = None) -> dict[str, Any]:
    mode = int(attrs.permissions) if attrs.permissions is not None else 0o644
    if stat.S_ISDIR(mode):
        entry_type = "directory"
    elif stat.S_ISLNK(mode):
        entry_type = "link"
    elif stat.S_ISREG(mode):
        entry_type = "file"
    else:
        entry_type = "other"
    out: dict[str, Any] = {
        "name": name,
        "path": path,
        "type": entry_type,
        "size": int(attrs.size or 0),
        "mtime": float(attrs.mtime or 0),
        "mode": stat.S_IMODE(mode),
        "perms": stat.filemode(mode),
        "owner": str(attrs.uid if attrs.uid is not None else "root"),
        "group": str(attrs.gid if attrs.gid is not None else "root"),
    }
    if entry_type == "link":
        out["target"] = target or ""
        out["target_type"] = target_type or "file"
    return out


async def run_agent(pool: ConnectionPool, server_id: int, op: str, **params: Any) -> Any:
    payload = json.dumps({"op": op, **params}).encode("utf-8")
    conn = await pool.acquire(server_id)
    try:
        res = await asyncio.wait_for(
            conn.run(_AGENT_CMD, input=payload, encoding=None, check=False),
            timeout=120.0,
        )
        stdout = bytes(res.stdout or b"").decode("utf-8", errors="replace")
        stderr = bytes(res.stderr or b"").decode("utf-8", errors="replace")
        try:
            parsed = json.loads(stdout)
        except ValueError:
            detail = (stderr or stdout or "no output").strip()[:500]
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=f"File agent failed: {detail}") from None
        if not parsed.get("ok"):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=parsed.get("error", "Operation failed"))
        return parsed["result"]
    finally:
        await pool.release(server_id)


async def stream_ssh_cmd(pool: ConnectionPool, server_id: int, cmd: str, chunk_size: int = 256 * 1024) -> AsyncIterator[bytes]:
    conn = await pool.acquire(server_id)
    try:
        async with conn.create_process(cmd, encoding=None) as proc:
            while True:
                chunk = await proc.stdout.read(chunk_size)
                if not chunk:
                    break
                yield bytes(chunk)
    finally:
        await pool.release(server_id)


def _parse_range(header: str | None, size: int, max_chunk: int | None = 16 * 1024 * 1024) -> tuple[int, int] | None:
    if not header or not header.startswith("bytes=") or "," in header:
        return None
    first, _, last = header[6:].strip().partition("-")
    try:
        if first:
            start = int(first)
            if last:
                end = int(last)
                if max_chunk and end - start + 1 > max_chunk:
                    end = start + max_chunk - 1
            else:
                end = min(start + max_chunk - 1, size - 1) if max_chunk else size - 1
        else:
            requested = int(last)
            if max_chunk and requested > max_chunk:
                requested = max_chunk
            start = max(size - requested, 0)
            end = size - 1
    except ValueError:
        return None
    if start >= size or start > end:
        raise HTTPException(
            status_code=416,
            detail="Range not satisfiable",
            headers={"Content-Range": f"bytes */{size}"},
        )
    return start, min(end, size - 1)


class FileActionIn(BaseModel):
    op: Literal["write", "mkdir", "symlink", "rename", "paste", "delete", "chmod"]
    path: str | None = None
    paths: list[str] | None = Field(default=None, max_length=10000)
    new_path: str | None = None
    dest_dir: str | None = None
    mode: Any | None = None
    content: str | None = Field(default=None, max_length=2 * 1024 * 1024)
    create: bool | None = None
    expected_mtime: float | None = None
    target: str | None = Field(default=None, max_length=4096)
    owner: str | None = Field(default=None, max_length=64)
    group: str | None = Field(default=None, max_length=64)
    recursive: bool | None = None


class BookmarkToggleIn(BaseModel):
    server_id: int
    path: str


class BookmarkSyncIn(BaseModel):
    server_id: int
    paths: list[str]


class FileRemoteIn(BaseModel):
    name: str
    local_server_id: int
    local_path: str
    remote_server_id: int
    remote_path: str
    ignore: list[str] = Field(default_factory=list)


class TransferIn(BaseModel):
    direction: Literal["upload", "download"]
    paths: list[str] = Field(..., min_length=1, max_length=10000)


class DirectTransferScanIn(BaseModel):
    source_server_id: int
    dest_server_id: int
    source_dir: str
    dest_dir: str
    paths: list[str] = Field(..., min_length=1, max_length=10000)
    ignore: list[str] | None = None
    use_gitignore: bool = True


class DirectTransferStartIn(BaseModel):
    source_server_id: int
    dest_server_id: int
    source_dir: str
    dest_dir: str
    paths: list[str] = Field(..., min_length=1, max_length=10000)
    mode: Literal["copy", "move"] = "copy"
    ignore: list[str] | None = None
    use_gitignore: bool = True


@dataclass
class TransferJobState:
    remote_name: str
    direction: str
    source_server_id: int
    source_root: str
    dest_server_id: int
    dest_root: str
    rels: list[str]
    ignore: list[str]
    total_bytes: int
    total_files: int
    remote_id: int | None = None
    read_gitignore: bool = True
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:16])
    state: str = "running"
    sent_bytes: int = 0
    result: dict[str, Any] | None = None
    error: str | None = None
    started_at: float = field(default_factory=time.time)
    finished_at: float | None = None
    task: asyncio.Task[None] | None = field(default=None, repr=False)

    def public(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "remote_id": self.remote_id,
            "remote_name": self.remote_name,
            "direction": self.direction,
            "state": self.state,
            "source_server_id": self.source_server_id,
            "source_root": self.source_root,
            "dest_server_id": self.dest_server_id,
            "dest_root": self.dest_root,
            "rels": self.rels,
            "read_gitignore": self.read_gitignore,
            "sent_bytes": self.sent_bytes,
            "total_bytes": self.total_bytes,
            "total_files": self.total_files,
            "result": self.result,
            "error": self.error,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
        }


_remotes: dict[int, dict[str, Any]] = {}
_next_remote_id = 1
_jobs: dict[str, TransferJobState] = {}


async def _relay_transfer(pool: ConnectionPool, job: TransferJobState) -> None:
    try:
        src_conn = await pool.acquire(job.source_server_id)
        try:
            dst_conn = await pool.acquire(job.dest_server_id)
            try:
                async with src_conn.create_process(_AGENT_CMD, encoding=None) as src_proc:
                    async with dst_conn.create_process(_AGENT_CMD, encoding=None) as dst_proc:
                        pack_req = {
                            "op": "pack",
                            "root": job.source_root,
                            "rels": job.rels,
                            "ignore": job.ignore,
                            "read_gitignore": job.read_gitignore,
                        }
                        src_proc.stdin.write(json.dumps(pack_req).encode("utf-8") + b"\n")
                        src_proc.stdin.write_eof()

                        dst_proc.stdin.write(json.dumps({"op": "unpack", "root": job.dest_root}).encode("utf-8") + b"\n")

                        while True:
                            chunk = await src_proc.stdout.read(256 * 1024)
                            if not chunk:
                                break
                            dst_proc.stdin.write(chunk)
                            await dst_proc.stdin.drain()
                            job.sent_bytes += len(chunk)

                        dst_proc.stdin.write_eof()
                        await src_proc.wait()
                        dst_res = await dst_proc.wait()
                        out_text = bytes(dst_res.stdout or b"").decode("utf-8", errors="replace")
                        parsed = json.loads(out_text)
                        if not parsed.get("ok"):
                            raise RuntimeError(parsed.get("error", "Unpack failed"))
                        job.result = parsed["result"]
                        job.state = "done"
            finally:
                await pool.release(job.dest_server_id)
        finally:
            await pool.release(job.source_server_id)

        if job.direction == "direct-move" and job.state == "done":
            delete_paths = [posixpath.join(job.source_root, r) for r in job.rels if r != "."]
            if delete_paths:
                await run_agent(pool, job.source_server_id, "delete", paths=delete_paths)
    except asyncio.CancelledError:
        job.state = "cancelled"
        job.error = "Cancelled"
    except Exception as exc:
        job.state = "error"
        job.error = str(exc) or type(exc).__name__
    finally:
        job.finished_at = time.time()


def register_files_routes(router: APIRouter, unlocked_dep: Callable[..., Any]) -> None:
    def _ensure_srv(ctx: Any, server_id: int) -> None:
        if ctx.store.get_server(server_id) is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server tidak ditemukan")

    @router.get("/files/bookmarks")
    async def get_file_bookmarks(server_id: int | None = Query(None), ctx: Any = Depends(unlocked_dep)) -> Any:
        store: Store = ctx.store
        if server_id is not None:
            return [b.path for b in store.list_bookmarks(server_id)]
        out: dict[str, list[str]] = {}
        for b in store.list_bookmarks(None):
            out.setdefault(str(b.server_id), []).append(b.path)
        return out

    @router.post("/files/bookmarks/toggle")
    async def toggle_file_bookmark(body: BookmarkToggleIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        store: Store = ctx.store
        clean = _abs_path(body.path)
        existing = store.list_bookmarks(body.server_id)
        match = next((b for b in existing if b.path == clean), None)
        if match is not None:
            store.delete_bookmark(match.id)
            bookmarked = False
        else:
            name = posixpath.basename(clean) or "/"
            store.create_bookmark(BookmarkIn(server_id=body.server_id, name=name, path=clean, startup_cmd=""))
            bookmarked = True
        updated = [b.path for b in store.list_bookmarks(body.server_id)]
        return {"bookmarked": bookmarked, "bookmarks": updated}

    @router.post("/files/bookmarks/sync")
    async def sync_file_bookmarks(body: BookmarkSyncIn, ctx: Any = Depends(unlocked_dep)) -> list[str]:
        store: Store = ctx.store
        existing = {b.path for b in store.list_bookmarks(body.server_id)}
        for raw in body.paths:
            try:
                clean = _abs_path(raw)
            except HTTPException:
                continue
            if clean not in existing:
                name = posixpath.basename(clean) or "/"
                store.create_bookmark(BookmarkIn(server_id=body.server_id, name=name, path=clean, startup_cmd=""))
                existing.add(clean)
        return [b.path for b in store.list_bookmarks(body.server_id)]

    @router.get("/files/remotes")
    async def list_remotes(_: Any = Depends(unlocked_dep)) -> list[dict[str, Any]]:
        return sorted(_remotes.values(), key=lambda r: str(r["name"]))

    @router.post("/files/remotes", status_code=status.HTTP_201_CREATED)
    async def create_remote(body: FileRemoteIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        global _next_remote_id
        _ensure_srv(ctx, body.local_server_id)
        _ensure_srv(ctx, body.remote_server_id)
        local_path = _abs_path(body.local_path)
        remote_path = _abs_path(body.remote_path)
        if local_path in PROTECTED or remote_path in PROTECTED:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Choose a project folder, not a system directory")
        rid = _next_remote_id
        _next_remote_id += 1
        rec = {
            "id": rid,
            "name": body.name.strip(),
            "local_server_id": body.local_server_id,
            "local_path": local_path,
            "remote_server_id": body.remote_server_id,
            "remote_path": remote_path,
            "ignore": body.ignore,
        }
        _remotes[rid] = rec
        return rec

    @router.put("/files/remotes/{remote_id}")
    async def update_remote(remote_id: int, body: FileRemoteIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        if remote_id not in _remotes:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Remote not found")
        _ensure_srv(ctx, body.local_server_id)
        _ensure_srv(ctx, body.remote_server_id)
        rec = {
            "id": remote_id,
            "name": body.name.strip(),
            "local_server_id": body.local_server_id,
            "local_path": _abs_path(body.local_path),
            "remote_server_id": body.remote_server_id,
            "remote_path": _abs_path(body.remote_path),
            "ignore": body.ignore,
        }
        _remotes[remote_id] = rec
        return rec

    @router.delete("/files/remotes/{remote_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_remote(remote_id: int, _: Any = Depends(unlocked_dep)) -> Response:
        _remotes.pop(remote_id, None)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @router.post("/files/remotes/{remote_id}/scan")
    async def scan_remote_transfer(remote_id: int, body: TransferIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        remote = _remotes.get(remote_id)
        if not remote:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Remote not found")
        root = str(remote["local_path"]).rstrip("/")
        rels = ["." if _abs_path(p) == remote["local_path"] else _abs_path(p)[len(root) + 1 :] for p in body.paths]
        if body.direction == "upload":
            src_id, src_root, dst_id, dst_root = remote["local_server_id"], remote["local_path"], remote["remote_server_id"], remote["remote_path"]
        else:
            src_id, src_root, dst_id, dst_root = remote["remote_server_id"], remote["remote_path"], remote["local_server_id"], remote["local_path"]
        counts = await run_agent(ctx.pool, int(src_id), "scan", root=src_root, rels=rels, ignore=remote["ignore"])
        return {**counts, "source_server_id": src_id, "source_root": src_root, "dest_server_id": dst_id, "dest_root": dst_root, "rels": rels}

    @router.post("/files/remotes/{remote_id}/transfer")
    async def start_remote_transfer(remote_id: int, body: TransferIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        scan = await scan_remote_transfer(remote_id, body, ctx)
        remote = _remotes[remote_id]
        job = TransferJobState(
            remote_id=remote_id,
            remote_name=str(remote["name"]),
            direction=body.direction,
            source_server_id=int(scan["source_server_id"]),
            source_root=str(scan["source_root"]),
            dest_server_id=int(scan["dest_server_id"]),
            dest_root=str(scan["dest_root"]),
            rels=[r for r in scan["rels"] if r not in scan.get("skipped", [])],
            ignore=list(remote["ignore"]),
            total_bytes=int(scan.get("tar_bytes", 1024)),
            total_files=int(scan.get("files", 0)),
        )
        _jobs[job.id] = job
        job.task = asyncio.create_task(_relay_transfer(ctx.pool, job))
        return job.public()

    def _plan_direct(source_dir: str, dest_dir: str, raw_paths: list[str]) -> tuple[str, str, list[str]]:
        src_root = _abs_path(source_dir).rstrip("/") or "/"
        dst_root = _abs_path(dest_dir)
        if dst_root in PROTECTED:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Destination cannot be system directory {dst_root}")
        rels: list[str] = []
        prefix = src_root if src_root != "/" else ""
        for raw in raw_paths:
            p = _abs_path(raw)
            if p == src_root:
                rels.append(".")
            elif p.startswith(prefix + "/"):
                rels.append(p[len(prefix) + 1 :])
            else:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Path {p} is outside source directory {src_root}")
        return src_root, dst_root, rels

    @router.post("/files/direct-transfer/scan")
    async def scan_direct(body: DirectTransferScanIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        _ensure_srv(ctx, body.source_server_id)
        _ensure_srv(ctx, body.dest_server_id)
        src_root, dst_root, rels = _plan_direct(body.source_dir, body.dest_dir, body.paths)
        counts = await run_agent(
            ctx.pool,
            body.source_server_id,
            "scan",
            root=src_root,
            rels=rels,
            ignore=body.ignore or [],
            read_gitignore=body.use_gitignore,
        )
        return {
            **counts,
            "source_server_id": body.source_server_id,
            "source_root": src_root,
            "dest_server_id": body.dest_server_id,
            "dest_root": dst_root,
            "rels": rels,
            "use_gitignore": body.use_gitignore,
        }

    @router.post("/files/direct-transfer/start")
    async def start_direct(body: DirectTransferStartIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        _ensure_srv(ctx, body.source_server_id)
        _ensure_srv(ctx, body.dest_server_id)
        src_root, dst_root, rels = _plan_direct(body.source_dir, body.dest_dir, body.paths)
        counts = await run_agent(
            ctx.pool,
            body.source_server_id,
            "scan",
            root=src_root,
            rels=rels,
            ignore=body.ignore or [],
            read_gitignore=body.use_gitignore,
        )
        job = TransferJobState(
            remote_name=f"{body.source_server_id} -> {body.dest_server_id}",
            direction=f"direct-{body.mode}",
            source_server_id=body.source_server_id,
            source_root=src_root,
            dest_server_id=body.dest_server_id,
            dest_root=dst_root,
            rels=[r for r in rels if r not in counts.get("skipped", [])],
            ignore=body.ignore or [],
            read_gitignore=body.use_gitignore,
            total_bytes=int(counts.get("tar_bytes", 1024)),
            total_files=int(counts.get("files", 0)),
        )
        _jobs[job.id] = job
        job.task = asyncio.create_task(_relay_transfer(ctx.pool, job))
        return job.public()

    @router.get("/files/transfers/{job_id}")
    async def get_transfer_status(job_id: str, _: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transfer not found")
        return job.public()

    @router.post("/files/transfers/{job_id}/cancel")
    async def cancel_transfer_job(job_id: str, _: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transfer not found")
        if job.task and not job.task.done():
            job.task.cancel()
        return job.public()

    @router.get("/files/{server_id}/list")
    async def list_files(server_id: int, path: str = Query("/"), show_hidden: bool = False, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        return await run_agent(ctx.pool, server_id, "list", path=_abs_path(path), show_hidden=show_hidden)

    @router.get("/files/{server_id}/principals")
    async def list_principals(server_id: int, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        return await run_agent(ctx.pool, server_id, "principals")

    @router.get("/files/{server_id}/read")
    async def read_text_file(server_id: int, path: str = Query(...), ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        return await run_agent(ctx.pool, server_id, "read", path=_abs_path(path))

    @router.post("/files/{server_id}/action")
    async def execute_file_action(server_id: int, body: FileActionIn, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        params: dict[str, Any] = body.model_dump(exclude_none=True, exclude={"op"})
        for key in ("path", "new_path", "dest_dir"):
            if key in params:
                params[key] = _abs_path(params[key])
        if "paths" in params:
            params["paths"] = [_abs_path(p) for p in params["paths"]]
            if not params["paths"]:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No paths given")
        if body.op == "chmod" and body.mode is not None:
            try:
                params["mode"] = int(body.mode)
            except (TypeError, ValueError):
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid mode")
        return await run_agent(ctx.pool, server_id, body.op, **params)

    @router.post("/files/{server_id}/upload")
    async def upload_file_stream(
        server_id: int,
        request: Request,
        dir: str = Query(...),
        name: str = Query(...),
        overwrite: bool = False,
        ctx: Any = Depends(unlocked_dep),
    ) -> Any:
        _ensure_srv(ctx, server_id)
        directory = _abs_path(dir)
        clean_name = _file_name(name)
        received = 0
        conn = await ctx.pool.acquire(server_id)
        try:
            async with conn.create_process(_AGENT_CMD, encoding=None) as proc:
                header = json.dumps({"op": "upload", "dir": directory, "name": clean_name, "overwrite": overwrite}).encode("utf-8") + b"\n"
                proc.stdin.write(header)
                agent_gone = False
                async for chunk in request.stream():
                    received += len(chunk)
                    if received > MAX_UPLOAD_FILE_BYTES:
                        raise HTTPException(status_code=413, detail="Upload exceeds 50 MB")
                    if chunk and not agent_gone:
                        try:
                            proc.stdin.write(struct.pack(">I", len(chunk)) + chunk)
                            await proc.stdin.drain()
                        except Exception:
                            agent_gone = True
                if not agent_gone:
                    try:
                        proc.stdin.write(struct.pack(">I", 0))
                        proc.stdin.write_eof()
                    except Exception:
                        pass
                res = await proc.wait()
                out_text = bytes(res.stdout or b"").decode("utf-8", errors="replace")
                err_text = bytes(res.stderr or b"").decode("utf-8", errors="replace")
                try:
                    parsed = json.loads(out_text)
                except ValueError:
                    detail = (err_text or out_text or "no output").strip()[:500]
                    raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=f"File agent failed: {detail}") from None
                if not parsed.get("ok"):
                    code = status.HTTP_409_CONFLICT if parsed.get("code") == "exists" else status.HTTP_400_BAD_REQUEST
                    raise HTTPException(status_code=code, detail=parsed.get("error", "Upload failed"))
                return parsed["result"]
        finally:
            await ctx.pool.release(server_id)

    async def _regular_file_size(ctx: Any, server_id: int, path: str, verb: str) -> int:
        info = await run_agent(ctx.pool, server_id, "stat", path=path)
        if info["type"] == "link":
            info = await run_agent(ctx.pool, server_id, "stat", path=posixpath.join(posixpath.dirname(path), info["target"]))
        if info["type"] != "file":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Only regular files can be {verb}")
        return int(info["size"])

    @router.get("/files/{server_id}/download")
    async def download_file_stream(server_id: int, path: str = Query(...), ctx: Any = Depends(unlocked_dep)) -> StreamingResponse:
        _ensure_srv(ctx, server_id)
        clean_path = _abs_path(path)
        size = await _regular_file_size(ctx, server_id, clean_path, "downloaded")
        filename = posixpath.basename(clean_path)
        safe_ascii = filename.replace('"', "_").replace("\\", "_")
        encoded = quote(filename)
        media_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        return StreamingResponse(
            stream_ssh_cmd(ctx.pool, server_id, f"cat -- {shlex.quote(clean_path)}"),
            media_type=media_type,
            headers={
                "Content-Disposition": f'attachment; filename="{safe_ascii}"; filename*=UTF-8\'\'{encoded}',
                "Content-Length": str(size),
            },
        )

    async def _media_response(ctx: Any, server_id: int, request: Request, path: str, media_type: str) -> StreamingResponse:
        size = await _regular_file_size(ctx, server_id, path, "previewed")
        byte_range = _parse_range(request.headers.get("range"), size)
        start, end = byte_range or (0, max(size - 1, 0))
        q_path = shlex.quote(path)
        if size == 0 or (start == 0 and end == size - 1):
            cmd = f"cat -- {q_path}"
        elif start == 0:
            cmd = f"head -c {end + 1} -- {q_path}"
        else:
            cmd = f"tail -c +{start + 1} -- {q_path} | head -c {end - start + 1}"

        headers = {
            "Content-Disposition": f"inline; filename*=UTF-8''{quote(posixpath.basename(path))}",
            "Content-Length": str(max(end - start + 1, 0) if size > 0 else 0),
            "Accept-Ranges": "bytes",
            "Cache-Control": "private, max-age=3600",
            "Content-Security-Policy": _PDF_CSP if media_type == "application/pdf" else _MEDIA_CSP,
            "X-Frame-Options": "SAMEORIGIN",
            "Cross-Origin-Resource-Policy": "same-origin",
        }
        if byte_range:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        return StreamingResponse(
            stream_ssh_cmd(ctx.pool, server_id, cmd),
            status_code=status.HTTP_206_PARTIAL_CONTENT if byte_range else status.HTTP_200_OK,
            media_type=media_type,
            headers=headers,
        )

    @router.get("/files/{server_id}/view")
    async def view_media(server_id: int, request: Request, path: str = Query(...), ctx: Any = Depends(unlocked_dep)) -> StreamingResponse:
        _ensure_srv(ctx, server_id)
        clean_path = _abs_path(path)
        ext = posixpath.splitext(clean_path)[1].lstrip(".").lower()
        media_type = _VIEW_TYPES.get(ext)
        if not media_type:
            raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="This file type cannot be previewed")
        return await _media_response(ctx, server_id, request, clean_path, media_type)

    @router.get("/files/{server_id}/thumb")
    async def view_thumb(server_id: int, request: Request, path: str = Query(...), v: str = Query(""), ctx: Any = Depends(unlocked_dep)) -> StreamingResponse:
        _ensure_srv(ctx, server_id)
        clean_path = _abs_path(path)
        ext = posixpath.splitext(clean_path)[1].lstrip(".").lower()
        media_type = _VIEW_TYPES.get(ext, "")
        if not (media_type.startswith("image/") or media_type.startswith("video/")):
            raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="No thumbnail for this file type")
        return await _media_response(ctx, server_id, request, clean_path, media_type)
