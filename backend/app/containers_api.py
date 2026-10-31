import json
import logging
import re
import shlex
from typing import Any, Callable

import asyncssh
from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from .db import Store
from .ssh import ConnectionPool, ServerNotFound

logger = logging.getLogger(__name__)


def _ssh_error_message(exc: BaseException) -> str:
    if isinstance(exc, asyncssh.PermissionDenied):
        return "Autentikasi gagal: username / password / private key salah."
    if isinstance(exc, asyncssh.DisconnectError):
        return f"Koneksi SSH terputus: {exc.reason}"
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return "Koneksi timeout. Periksa host/port dan firewall."
    return f"{type(exc).__name__}: {exc}"


class ContainerActionIn(BaseModel):
    server_id: int
    container_id: str
    action: str = Field(pattern="^(start|stop|restart|pause|unpause|delete)$")
    force: bool = False


class ImagePullIn(BaseModel):
    server_id: int
    image: str


class ContainerRunIn(BaseModel):
    server_id: int
    image: str
    name: str | None = None
    ports: list[str] = Field(default_factory=list)
    env: list[str] = Field(default_factory=list)
    volumes: list[str] = Field(default_factory=list)
    command: str | None = None
    restart: str | None = None


async def _run_remote_cmd(
    pool: ConnectionPool,
    server_id: int,
    command: str,
    timeout: float = 30.0,
) -> tuple[int, str, str]:
    """Execute a shell command on remote server via SSH pool."""
    conn = await pool.acquire(server_id)
    try:
        res = await asyncio.wait_for(conn.run(command, check=False), timeout=timeout)
        stdout = res.stdout if isinstance(res.stdout, str) else (res.stdout or b"").decode("utf-8", "replace")
        stderr = res.stderr if isinstance(res.stderr, str) else (res.stderr or b"").decode("utf-8", "replace")
        return res.returncode or 0, stdout, stderr
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail=f"Perintah remote melebihi batas waktu {int(timeout)} detik",
        )
    except (ServerNotFound, *asyncssh.SSHClientConnection.__subclasses__()) as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Koneksi SSH gagal: {_ssh_error_message(exc)}",
        )


import asyncio


async def detect_engine(pool: ConnectionPool, server_id: int) -> dict[str, Any]:
    """Detect podman first (RHEL/CentOS default), then fallback to docker."""
    # 1. Check podman
    code, out, _ = await _run_remote_cmd(pool, server_id, "podman --version 2>/dev/null", timeout=10.0)
    if code == 0 and "podman" in out.lower():
        ver_match = re.search(r"podman\s+version\s+([^\s,]+)", out, re.IGNORECASE)
        version = ver_match.group(1) if ver_match else out.strip()
        # Check rootless: podman info
        _, info_out, _ = await _run_remote_cmd(
            pool, server_id, "podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null", timeout=10.0
        )
        is_rootless = "true" in info_out.lower()
        return {
            "engine": "podman",
            "version": version,
            "rootless": is_rootless,
            "error": None,
        }

    # 2. Check docker
    code, out, _ = await _run_remote_cmd(pool, server_id, "docker --version 2>/dev/null", timeout=10.0)
    if code == 0 and "docker" in out.lower():
        ver_match = re.search(r"Docker\s+version\s+([^\s,]+)", out, re.IGNORECASE)
        version = ver_match.group(1) if ver_match else out.strip()
        # Check rootless
        _, info_out, _ = await _run_remote_cmd(
            pool, server_id, "docker info --format '{{.SecurityOptions}}' 2>/dev/null", timeout=10.0
        )
        is_rootless = "rootless" in info_out.lower()
        return {
            "engine": "docker",
            "version": version,
            "rootless": is_rootless,
            "error": None,
        }

    return {
        "engine": "none",
        "version": "",
        "rootless": False,
        "error": "Podman atau Docker tidak terdeteksi pada server remote ini.",
    }


def _parse_port_entry(port_spec: Any) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    if isinstance(port_spec, list):
        for item in port_spec:
            results.extend(_parse_port_entry(item))
        return results
    if isinstance(port_spec, dict):
        results.append({
            "host_ip": port_spec.get("hostIP") or port_spec.get("host_ip") or "",
            "host_port": port_spec.get("hostPort") or port_spec.get("host_port") or 0,
            "container_port": port_spec.get("containerPort") or port_spec.get("container_port") or 0,
            "protocol": port_spec.get("protocol") or "tcp",
        })
        return results
    if isinstance(port_spec, str) and port_spec.strip():
        # Example strings: "0.0.0.0:80->80/tcp, :::80->80/tcp", "80/tcp"
        for part in port_spec.split(","):
            part = part.strip()
            if not part:
                continue
            if "->" in part:
                host_part, cont_part = part.split("->", 1)
                proto = "tcp"
                if "/" in cont_part:
                    c_port_str, proto = cont_part.split("/", 1)
                else:
                    c_port_str = cont_part
                host_ip = ""
                host_port = 0
                if ":" in host_part:
                    host_ip, h_port_str = host_part.rsplit(":", 1)
                    host_port = int(h_port_str) if h_port_str.isdigit() else 0
                elif host_part.isdigit():
                    host_port = int(host_part)
                results.append({
                    "host_ip": host_ip,
                    "host_port": host_port,
                    "container_port": int(c_port_str) if c_port_str.isdigit() else 0,
                    "protocol": proto,
                })
            elif "/" in part:
                c_port_str, proto = part.split("/", 1)
                results.append({
                    "host_ip": "",
                    "host_port": 0,
                    "container_port": int(c_port_str) if c_port_str.isdigit() else 0,
                    "protocol": proto,
                })
    return results


def _normalize_container_item(raw: dict[str, Any], engine: str) -> dict[str, Any]:
    cid = str(raw.get("Id") or raw.get("ID") or raw.get("id") or "")
    if len(cid) > 12 and not "-" in cid:
        cid_short = cid[:12]
    else:
        cid_short = cid

    # Names: can be string (Docker) or list of strings (Podman)
    raw_names = raw.get("Names") or raw.get("names") or []
    if isinstance(raw_names, str):
        names = [n.strip().lstrip("/") for n in raw_names.split(",") if n.strip()]
    elif isinstance(raw_names, list):
        names = [str(n).lstrip("/") for n in raw_names if str(n).strip()]
    else:
        names = []
    if not names:
        names = [cid_short]

    image = str(raw.get("Image") or raw.get("ImageName") or raw.get("image") or "")
    
    # State
    raw_state = str(raw.get("State") or raw.get("state") or "").lower()
    raw_status = str(raw.get("Status") or raw.get("status") or "")
    if "up" in raw_status.lower() or "running" in raw_state:
        state = "running"
    elif "paused" in raw_state or "paused" in raw_status.lower():
        state = "paused"
    elif "exited" in raw_state or "exited" in raw_status.lower() or "stopped" in raw_state:
        state = "exited"
    elif "created" in raw_state:
        state = "created"
    elif "restarting" in raw_state:
        state = "restarting"
    elif "dead" in raw_state:
        state = "dead"
    else:
        state = raw_state or "unknown"

    ports = _parse_port_entry(raw.get("Ports") or raw.get("ports"))
    created = raw.get("CreatedAt") or raw.get("Created") or raw.get("created") or ""
    command = str(raw.get("Command") or raw.get("command") or "")

    return {
        "id": cid_short,
        "names": names,
        "image": image,
        "state": state,
        "status": raw_status or state.capitalize(),
        "created": created,
        "ports": ports,
        "command": command,
    }


def register_containers_routes(router: APIRouter, unlocked_dep: Callable[..., Any]) -> None:
    def _ensure_srv(ctx: Any, server_id: int) -> None:
        if ctx.store.get_server(server_id) is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server tidak ditemukan")

    @router.get("/containers/engine")
    async def get_engine_status(server_id: int = Query(...), ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        return await detect_engine(ctx.pool, server_id)

    @router.get("/containers/list")
    async def list_containers(
        server_id: int = Query(...),
        all: bool = Query(True),
        ctx: Any = Depends(unlocked_dep),
    ) -> Any:
        _ensure_srv(ctx, server_id)
        eng_info = await detect_engine(ctx.pool, server_id)
        engine = eng_info["engine"]
        if engine == "none":
            return []

        all_flag = "-a " if all else ""
        if engine == "podman":
            cmd = f"podman ps {all_flag}--format json"
            code, out, err = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=15.0)
            if code != 0:
                raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Podman ps error: {err}")
            out_str = out.strip()
            if not out_str:
                return []
            try:
                data = json.loads(out_str)
                if not isinstance(data, list):
                    data = [data]
                return [_normalize_container_item(item, "podman") for item in data]
            except json.JSONDecodeError:
                return []
        else:
            # Docker returns line-delimited JSON objects with --format '{{json .}}'
            cmd = f"docker ps {all_flag}--format '{{{{json .}}}}'"
            code, out, err = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=15.0)
            if code != 0:
                raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Docker ps error: {err}")
            items: list[dict[str, Any]] = []
            for line in out.strip().splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                    items.append(_normalize_container_item(obj, "docker"))
                except json.JSONDecodeError:
                    continue
            return items

    @router.post("/containers/action")
    async def container_action(body: ContainerActionIn, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, body.server_id)
        eng_info = await detect_engine(ctx.pool, body.server_id)
        engine = eng_info["engine"]
        if engine == "none":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=eng_info["error"])

        cid_q = shlex.quote(body.container_id)
        if body.action == "delete":
            flag = "-f " if body.force else ""
            cmd = f"{engine} rm {flag}{cid_q}"
        else:
            cmd = f"{engine} {body.action} {cid_q}"

        code, out, err = await _run_remote_cmd(ctx.pool, body.server_id, cmd, timeout=20.0)
        if code != 0:
            detail = (err or out).strip() or f"Aksi {body.action} gagal"
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)

        return {"ok": True, "message": f"Kontainer {body.container_id} berhasil di-{body.action}"}

    @router.get("/containers/logs")
    async def container_logs(
        server_id: int = Query(...),
        container_id: str = Query(...),
        tail: int = Query(150),
        ctx: Any = Depends(unlocked_dep),
    ) -> Any:
        _ensure_srv(ctx, server_id)
        eng_info = await detect_engine(ctx.pool, server_id)
        engine = eng_info["engine"]
        if engine == "none":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=eng_info["error"])

        cid_q = shlex.quote(container_id)
        cmd = f"{engine} logs --tail {tail} {cid_q} 2>&1"
        _, out, _ = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=15.0)
        return {"logs": out}

    @router.get("/containers/images")
    async def list_images(server_id: int = Query(...), ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, server_id)
        eng_info = await detect_engine(ctx.pool, server_id)
        engine = eng_info["engine"]
        if engine == "none":
            return []

        if engine == "podman":
            cmd = "podman images --format json"
            code, out, err = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=15.0)
            if code != 0:
                raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Podman images error: {err}")
            out_str = out.strip()
            if not out_str:
                return []
            try:
                data = json.loads(out_str)
                if not isinstance(data, list):
                    data = [data]
                results = []
                for img in data:
                    iid = str(img.get("Id") or img.get("id") or "")[:12]
                    # Podman provides names in Repository/Tag or Names
                    repo = str(img.get("Repository") or "")
                    tag = str(img.get("Tag") or "")
                    if not repo and img.get("Names"):
                        full_name = img["Names"][0]
                        if ":" in full_name:
                            repo, tag = full_name.rsplit(":", 1)
                        else:
                            repo = full_name
                    size_bytes = img.get("Size") or 0
                    if isinstance(size_bytes, int):
                        size_str = f"{size_bytes / (1024 * 1024):.1f} MB"
                    else:
                        size_str = str(size_bytes)
                    results.append({
                        "id": iid,
                        "repository": repo or "<none>",
                        "tag": tag or "<none>",
                        "size": size_str,
                        "created": str(img.get("Created") or img.get("CreatedAt") or ""),
                    })
                return results
            except json.JSONDecodeError:
                return []
        else:
            cmd = "docker images --format '{{json .}}'"
            code, out, err = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=15.0)
            if code != 0:
                raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Docker images error: {err}")
            results = []
            for line in out.strip().splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                    results.append({
                        "id": str(obj.get("ID") or "")[:12],
                        "repository": str(obj.get("Repository") or "<none>"),
                        "tag": str(obj.get("Tag") or "<none>"),
                        "size": str(obj.get("Size") or ""),
                        "created": str(obj.get("CreatedAt") or obj.get("CreatedSince") or ""),
                    })
                except json.JSONDecodeError:
                    continue
            return results

    @router.post("/containers/images/pull")
    async def pull_image(body: ImagePullIn, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, body.server_id)
        eng_info = await detect_engine(ctx.pool, body.server_id)
        engine = eng_info["engine"]
        if engine == "none":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=eng_info["error"])

        image_q = shlex.quote(body.image.strip())
        cmd = f"{engine} pull {image_q}"
        code, out, err = await _run_remote_cmd(ctx.pool, body.server_id, cmd, timeout=180.0)
        if code != 0:
            detail = (err or out).strip() or "Gagal menarik image"
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)

        return {"ok": True, "message": f"Image {body.image} berhasil diunduh."}

    @router.delete("/containers/images")
    async def delete_image(
        server_id: int = Query(...),
        image_id: str = Query(...),
        force: bool = Query(False),
        ctx: Any = Depends(unlocked_dep),
    ) -> Any:
        _ensure_srv(ctx, server_id)
        eng_info = await detect_engine(ctx.pool, server_id)
        engine = eng_info["engine"]
        if engine == "none":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=eng_info["error"])

        flag = "-f " if force else ""
        img_q = shlex.quote(image_id)
        cmd = f"{engine} rmi {flag}{img_q}"
        code, out, err = await _run_remote_cmd(ctx.pool, server_id, cmd, timeout=20.0)
        if code != 0:
            detail = (err or out).strip() or "Gagal menghapus image"
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)

        return {"ok": True, "message": f"Image {image_id} berhasil dihapus."}

    @router.post("/containers/run")
    async def run_container(body: ContainerRunIn, ctx: Any = Depends(unlocked_dep)) -> Any:
        _ensure_srv(ctx, body.server_id)
        eng_info = await detect_engine(ctx.pool, body.server_id)
        engine = eng_info["engine"]
        if engine == "none":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=eng_info["error"])

        parts = [engine, "run", "-d"]
        if body.name and body.name.strip():
            parts.extend(["--name", shlex.quote(body.name.strip())])
        for p in body.ports:
            if p.strip():
                parts.extend(["-p", shlex.quote(p.strip())])
        for e in body.env:
            if e.strip():
                parts.extend(["-e", shlex.quote(e.strip())])
        for v in body.volumes:
            if v.strip():
                parts.extend(["-v", shlex.quote(v.strip())])
        if body.restart and body.restart.strip():
            parts.extend(["--restart", shlex.quote(body.restart.strip())])

        parts.append(shlex.quote(body.image.strip()))
        if body.command and body.command.strip():
            parts.append(body.command.strip())

        cmd = " ".join(parts)
        code, out, err = await _run_remote_cmd(ctx.pool, body.server_id, cmd, timeout=30.0)
        if code != 0:
            detail = (err or out).strip() or "Gagal menjalankan kontainer baru"
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)

        new_cid = out.strip()[:12]
        return {"ok": True, "id": new_cid, "message": f"Kontainer {new_cid} berhasil dijalankan"}
