from pathlib import Path
import pytest
from fastapi.testclient import TestClient

from app.backup import (
    InvalidBackupError,
    InvalidBackupPinError,
    decrypt_config,
    encrypt_config,
)
from app.main import create_app
from app.vault import Vault

TOKEN = "test-token"
PIN = "7821724"


def _login(client: TestClient, pin: str = PIN) -> str:
    res = client.post("/api/auth/login", json={"pin": pin})
    assert res.status_code == 200, res.text
    return res.json()["session"]


@pytest.fixture()
def client(tmp_path: Path) -> TestClient:
    app = create_app(Vault(tmp_path, initial_pin=PIN), TOKEN, static_dir=None)
    client = TestClient(app, headers={"X-Token": TOKEN})
    client.headers["X-Session"] = _login(client)
    return client


def test_crypto_roundtrip() -> None:
    data = {"servers": [{"name": "srv1", "host": "1.2.3.4"}]}
    pin = "secret-pin-1234"
    envelope = encrypt_config(data, pin)
    assert envelope["magic"] == "TERMDECK_BACKUP"
    assert envelope["version"] == 1
    assert "ciphertext_hex" in envelope

    decrypted = decrypt_config(envelope, pin)
    assert decrypted == data

    with pytest.raises(InvalidBackupPinError):
        decrypt_config(envelope, "wrong-pin")

    with pytest.raises(InvalidBackupError):
        decrypt_config({"magic": "INVALID"}, pin)


def test_api_export_and_import_merge(client: TestClient, tmp_path: Path) -> None:
    # 1. Create a server, bookmark, and snippet
    res_srv = client.post("/api/servers", json={
        "name": "Prod Server",
        "host": "prod.example.com",
        "port": 2222,
        "username": "admin",
        "auth_type": "password",
        "password": "super-secret-password",
        "group_name": "Production",
        "color": "emerald",
    })
    assert res_srv.status_code == 201
    srv_id = res_srv.json()["id"]

    res_bm = client.post("/api/bookmarks", json={
        "server_id": srv_id,
        "name": "Logs Folder",
        "path": "/var/log/nginx",
        "startup_cmd": "tail -f access.log",
    })
    assert res_bm.status_code == 201

    res_snip = client.post("/api/snippets", json={
        "name": "Check Uptime",
        "command": "uptime -p",
    })
    assert res_snip.status_code == 201

    # 2. Export config
    backup_pin = "889900"
    res_export = client.post("/api/config/export", json={"pin": backup_pin})
    assert res_export.status_code == 200, res_export.text
    envelope = res_export.json()
    assert envelope["magic"] == "TERMDECK_BACKUP"

    # 3. Create a fresh second client (representing another device)
    client2_dir = tmp_path / "second_device"
    client2_dir.mkdir()
    app2 = create_app(Vault(client2_dir, initial_pin=PIN), TOKEN, static_dir=None)
    client2 = TestClient(app2, headers={"X-Token": TOKEN})
    client2.headers["X-Session"] = _login(client2)

    # Initially empty
    assert len(client2.get("/api/servers").json()) == 0
    assert len(client2.get("/api/snippets").json()) == 0

    # Test wrong PIN import
    res_bad = client2.post("/api/config/import", json={"pin": "wrong", "backup": envelope})
    assert res_bad.status_code == 400

    # Test correct PIN import
    res_import = client2.post("/api/config/import", json={"pin": backup_pin, "backup": envelope})
    assert res_import.status_code == 200, res_import.text
    stats = res_import.json()
    assert stats["servers_added"] == 1
    assert stats["bookmarks_added"] == 1
    assert stats["snippets_added"] == 1

    # Verify data in client2
    srvs2 = client2.get("/api/servers").json()
    assert len(srvs2) == 1
    assert srvs2[0]["name"] == "Prod Server"
    assert srvs2[0]["host"] == "prod.example.com"
    assert srvs2[0]["port"] == 2222
    assert srvs2[0]["has_password"] is True

    bms2 = client2.get(f"/api/bookmarks?server_id={srvs2[0]['id']}").json()
    assert len(bms2) == 1
    assert bms2[0]["path"] == "/var/log/nginx"

    snips2 = client2.get("/api/snippets").json()
    assert len(snips2) == 1
    assert snips2[0]["name"] == "Check Uptime"

    # 4. Re-importing same backup triggers MERGE (0 new servers, 0 new bookmarks)
    res_import_again = client2.post("/api/config/import", json={"pin": backup_pin, "backup": envelope})
    assert res_import_again.status_code == 200
    stats2 = res_import_again.json()
    assert stats2["servers_added"] == 0
    assert stats2["bookmarks_added"] == 0
    assert stats2["snippets_added"] == 0
