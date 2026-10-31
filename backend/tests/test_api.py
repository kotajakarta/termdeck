import asyncio
import json
import threading
from collections.abc import Iterator
from pathlib import Path

import asyncssh
import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from app.main import create_app
from app.vault import Vault

TOKEN = "test-token"
PIN = "7821724"


def _login(client: TestClient, pin: str = PIN) -> str:
    res = client.post("/api/auth/login", json={"pin": pin})
    assert res.status_code == 200, res.text
    session: str = res.json()["session"]
    return session


@pytest.fixture()
def client(tmp_path: Path) -> TestClient:
    app = create_app(Vault(tmp_path, initial_pin=PIN), TOKEN, static_dir=None)
    client = TestClient(app, headers={"X-Token": TOKEN})
    client.headers["X-Session"] = _login(client)
    return client


SERVER = {"name": "web-1", "host": "10.0.0.5", "username": "deploy", "password": "pw"}


def test_requires_token(client: TestClient) -> None:
    assert client.get("/api/servers", headers={"X-Token": "wrong"}).status_code == 401
    assert client.get("/api/servers", headers={"X-Token": ""}).status_code == 401
    assert client.post("/api/auth/login", json={"pin": PIN}, headers={"X-Token": ""}).status_code == 401
    assert client.get("/api/servers").status_code == 200


def test_requires_pin_session(client: TestClient) -> None:
    assert client.get("/api/servers", headers={"X-Session": ""}).status_code == 401
    assert client.get("/api/servers", headers={"X-Session": "forged"}).status_code == 401
    status = client.get("/api/auth/status").json()
    assert status == {"unlocked": True, "session_valid": True}


def test_wrong_pin_rejected(client: TestClient) -> None:
    res = client.post("/api/auth/login", json={"pin": "0000000"})
    assert res.status_code == 403 and res.json()["detail"] == "PIN salah."


def test_lock_ends_all_sessions(client: TestClient) -> None:
    other = _login(client)
    assert client.post("/api/auth/lock").status_code == 204
    assert client.get("/api/servers").status_code == 401
    assert client.get("/api/servers", headers={"X-Session": other}).status_code == 401
    assert client.get("/api/auth/status").json() == {"unlocked": False, "session_valid": False}
    client.headers["X-Session"] = _login(client)
    assert client.get("/api/servers").status_code == 200


def test_lock_all_with_token_only(client: TestClient) -> None:
    """The desktop tray icon locks TermDeck without a PIN session (like Android's notification)."""
    assert client.post("/api/auth/lock-all", headers={"X-Token": "wrong", "X-Session": ""}).status_code == 401
    assert client.get("/api/servers").status_code == 200
    assert client.post("/api/auth/lock-all", headers={"X-Session": ""}).status_code == 204
    assert client.get("/api/servers").status_code == 401
    assert client.get("/api/auth/status").json() == {"unlocked": False, "session_valid": False}
    # Locking an already locked vault is harmless.
    assert client.post("/api/auth/lock-all", headers={"X-Session": ""}).status_code == 204
    client.headers["X-Session"] = _login(client)
    assert client.get("/api/servers").status_code == 200


def test_change_pin_via_api(client: TestClient) -> None:
    bad = client.post("/api/auth/change-pin", json={"old_pin": "1111", "new_pin": "123456"})
    assert bad.status_code == 403
    short = client.post("/api/auth/change-pin", json={"old_pin": PIN, "new_pin": "12"})
    assert short.status_code == 422
    ok = client.post("/api/auth/change-pin", json={"old_pin": PIN, "new_pin": "123456"})
    assert ok.status_code == 204
    client.post("/api/auth/lock")
    assert client.post("/api/auth/login", json={"pin": PIN}).status_code == 403
    _login(client, "123456")


def test_server_crud_via_api(client: TestClient) -> None:
    created = client.post("/api/servers", json=SERVER).json()
    assert created["has_password"] is True and "password" not in created
    assert created["port"] == 22

    updated = client.put(f"/api/servers/{created['id']}", json={**SERVER, "name": "web-2", "password": None})
    assert updated.status_code == 200 and updated.json()["name"] == "web-2"
    assert updated.json()["has_password"] is True

    assert client.put("/api/servers/999", json=SERVER).status_code == 404
    assert client.post("/api/servers", json={**SERVER, "port": 70000}).status_code == 422

    assert client.delete(f"/api/servers/{created['id']}").status_code == 204
    assert client.get("/api/servers").json() == []


def test_reorder_servers_via_api(client: TestClient) -> None:
    ids = [client.post("/api/servers", json={**SERVER, "name": n}).json()["id"] for n in ("a", "b", "c")]
    res = client.put("/api/servers/order", json={"ids": [ids[1], ids[2], ids[0]]})
    assert res.status_code == 200
    assert [s["name"] for s in res.json()] == ["b", "c", "a"]
    assert [s["name"] for s in client.get("/api/servers").json()] == ["b", "c", "a"]
    assert client.put("/api/servers/order", json={"ids": [ids[0]]}).status_code == 422


def test_bookmark_crud_via_api(client: TestClient) -> None:
    srv = client.post("/api/servers", json=SERVER).json()
    bm = client.post("/api/bookmarks", json={"server_id": srv["id"], "name": "app", "path": "/var/www"})
    assert bm.status_code == 201
    assert client.post("/api/bookmarks", json={"server_id": 999, "name": "x", "path": "/"}).status_code == 404

    listed = client.get("/api/bookmarks", params={"server_id": srv["id"]}).json()
    assert [b["name"] for b in listed] == ["app"]

    bm_id = bm.json()["id"]
    upd = client.put(f"/api/bookmarks/{bm_id}", json={"server_id": srv["id"], "name": "app2", "path": "/srv"})
    assert upd.json()["path"] == "/srv"
    assert client.delete(f"/api/bookmarks/{bm_id}").status_code == 204
    assert client.delete(f"/api/bookmarks/{bm_id}").status_code == 404


def test_snippet_crud_via_api(client: TestClient) -> None:
    sn = client.post("/api/snippets", json={"name": "pull", "command": "git pull"}).json()
    assert client.get("/api/snippets").json() == [sn]
    assert client.delete(f"/api/snippets/{sn['id']}").status_code == 204


def test_paste_image_validation(client: TestClient) -> None:
    srv = client.post("/api/servers", json=SERVER).json()
    url = f"/api/servers/{srv['id']}/paste-image"
    headers = {"Content-Type": "image/png"}
    not_image = client.post(url, content=b"just text", headers=headers)
    assert not_image.status_code == 415
    huge = client.post(url, content=b"\x89PNG\r\n\x1a\n" + bytes(20 * 1024 * 1024), headers=headers)
    assert huge.status_code == 413
    assert client.post("/api/servers/999/paste-image", content=b"x", headers=headers).status_code == 404
    assert client.post(url, content=b"x", headers={**headers, "X-Session": ""}).status_code == 401


def test_ws_rejects_bad_token(client: TestClient) -> None:
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/terminal?server_id=1&session=wrong") as ws:
            ws.receive_text()


class _EchoSSHServer(asyncssh.SSHServer):
    def begin_auth(self, username: str) -> bool:
        return True

    def password_auth_supported(self) -> bool:
        return True

    def validate_password(self, username: str, password: str) -> bool:
        return password == "pw"


async def _echo(process: asyncssh.SSHServerProcess[str]) -> None:
    cols, rows = process.term_size[0], process.term_size[1]
    process.stdout.write(f"size={cols}x{rows}\r\n")
    async for line in process.stdin:
        process.stdout.write("ECHO:" + line.rstrip("\n") + "\r\n")
        if line.strip() == "exit":
            break
    process.exit(0)


@pytest.fixture()
def echo_server() -> Iterator[int]:
    loop = asyncio.new_event_loop()
    thread = threading.Thread(target=loop.run_forever, daemon=True)
    thread.start()

    async def start() -> asyncssh.SSHAcceptor:
        key = asyncssh.generate_private_key("ssh-ed25519")
        return await asyncssh.create_server(
            _EchoSSHServer, "127.0.0.1", 0, server_host_keys=[key], process_factory=_echo
        )

    acceptor = asyncio.run_coroutine_threadsafe(start(), loop).result(10)
    yield acceptor.sockets[0].getsockname()[1]
    acceptor.close()
    loop.call_soon_threadsafe(loop.stop)
    thread.join(5)


def _read_until(ws: WebSocketTestSession, needle: str) -> str:
    seen = ""
    for _ in range(50):
        msg = ws.receive()
        if msg.get("bytes"):
            seen += msg["bytes"].decode("utf-8", "replace")
        elif msg.get("text"):
            seen += msg["text"]
        if needle in seen:
            return seen
    raise AssertionError(f"{needle!r} not found in {seen!r}")


def test_ws_terminal_end_to_end(client: TestClient, echo_server: int) -> None:
    srv = client.post("/api/servers", json={**SERVER, "host": "127.0.0.1", "port": echo_server}).json()
    bm = client.post(
        "/api/bookmarks",
        json={"server_id": srv["id"], "name": "app", "path": "/srv/my app", "startup_cmd": "ls"},
    ).json()
    session = client.headers["X-Session"]
    url = f"/ws/terminal?session={session}&server_id={srv['id']}&bookmark_id={bm['id']}&cols=120&rows=40"
    with client.websocket_connect(url) as ws:
        _read_until(ws, '"connected"')
        _read_until(ws, "size=120x40")
        _read_until(ws, "ECHO:cd -- '/srv/my app' && clear && ls")
        ws.send_text(json.dumps({"type": "input", "data": "whoami\r"}))
        _read_until(ws, "ECHO:whoami")
        ws.send_text(json.dumps({"type": "input", "data": "exit\r"}))
        _read_until(ws, '"exit"')
    assert client.get("/api/servers").json()[0]["host_key_fingerprint"].startswith("SHA256:")


def test_ws_reports_auth_error(client: TestClient, echo_server: int) -> None:
    srv = client.post(
        "/api/servers", json={**SERVER, "host": "127.0.0.1", "port": echo_server, "password": "bad"}
    ).json()
    with client.websocket_connect(f"/ws/terminal?session={client.headers['X-Session']}&server_id={srv['id']}") as ws:
        text = _read_until(ws, '"error"')
    assert "Autentikasi gagal" in text


def test_connection_check(client: TestClient, echo_server: int) -> None:
    ok = client.post("/api/servers/test", json={**SERVER, "host": "127.0.0.1", "port": echo_server}).json()
    assert ok["ok"] is True and "SHA256:" in ok["message"]
    bad = client.post("/api/servers/test", json={**SERVER, "host": "127.0.0.1", "port": 1}).json()
    assert bad["ok"] is False


def _app_with_ui(tmp_path: Path) -> TestClient:
    static = tmp_path / "dist"
    static.mkdir()
    (static / "index.html").write_text("<!doctype html><html><head><title>t</title></head><body></body></html>")
    (static / "app.js").write_text("console.log(1)")
    app = create_app(Vault(tmp_path, initial_pin=PIN), TOKEN, static_dir=static, allowed_hosts=["localhost", "127.0.0.1"])
    ui = TestClient(app, base_url="http://localhost:9095", headers={"X-Token": TOKEN})
    ui.headers["X-Session"] = _login(ui)
    return ui


def test_index_embeds_token(tmp_path: Path) -> None:
    ui = _app_with_ui(tmp_path)
    for path in ("/", "/index.html"):
        res = ui.get(path)
        assert res.status_code == 200
        assert f'<meta name="termdeck-token" content="{TOKEN}">' in res.text
        assert res.headers["cache-control"] == "no-store"
    assert ui.get("/app.js").text == "console.log(1)"


def test_foreign_host_rejected(tmp_path: Path) -> None:
    ui = _app_with_ui(tmp_path)
    assert ui.get("/", headers={"Host": "evil.example:9095"}).status_code == 400
    assert ui.get("/api/servers", headers={"Host": "evil.example"}).status_code == 400
    assert ui.get("/api/servers", headers={"Host": "127.0.0.1:9095"}).status_code == 200


def test_ws_foreign_origin_rejected(tmp_path: Path) -> None:
    from starlette.websockets import WebSocketDisconnect

    ui = _app_with_ui(tmp_path)
    with pytest.raises(WebSocketDisconnect) as info:
        with ui.websocket_connect(
            f"ws://localhost:9095/ws/terminal?server_id=1&session={ui.headers['X-Session']}",
            headers={"Origin": "https://evil.example"},
        ) as ws:
            ws.receive_text()
    assert info.value.code == 4401
    # Same-origin browser connection passes the gate (and then reports the unknown server).
    with ui.websocket_connect(
        f"ws://localhost:9095/ws/terminal?server_id=999&session={ui.headers['X-Session']}",
        headers={"Origin": "http://localhost:9095"},
    ) as ws:
        assert "connecting" in ws.receive_text()
        assert "tidak ditemukan" in ws.receive_text()


def test_index_follows_rebuilt_ui(tmp_path: Path) -> None:
    ui = _app_with_ui(tmp_path)
    (tmp_path / "dist" / "index.html").write_text("<html><head><title>v2</title></head></html>")
    res = ui.get("/")
    assert "<title>v2</title>" in res.text and "termdeck-token" in res.text
