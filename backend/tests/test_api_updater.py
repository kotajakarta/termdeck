from pathlib import Path
import pytest
from starlette.testclient import TestClient

from app.main import create_app
from app.vault import Vault

TOKEN = "test-token-updater-xyz"
PIN = "123456"


def _login(client: TestClient, pin: str = PIN) -> str:
    status_resp = client.get("/api/auth/status", headers={"X-Token": TOKEN}).json()
    if not status_resp.get("initialized"):
        res = client.post("/api/auth/setup-pin", json={"pin": pin}, headers={"X-Token": TOKEN})
        assert res.status_code == 200, res.text
        return res.json()["session"]
    res = client.post("/api/auth/login", json={"pin": pin}, headers={"X-Token": TOKEN})
    assert res.status_code == 200, res.text
    return res.json()["session"]


@pytest.fixture()
def client(tmp_path: Path) -> TestClient:
    app = create_app(Vault(tmp_path, initial_pin=PIN), TOKEN, static_dir=None)
    client = TestClient(app, headers={"X-Token": TOKEN})
    client.headers["X-Session"] = _login(client)
    return client


def test_updater_endpoints_require_unlocked(client: TestClient):
    # Missing session returns 401
    assert client.get("/api/system/update-check", headers={"X-Session": ""}).status_code == 401
    assert client.post("/api/system/update-apply", headers={"X-Session": ""}).status_code == 401
    assert client.get("/api/system/update-progress", headers={"X-Session": ""}).status_code == 401

    # Bad token returns 401
    assert client.get("/api/system/update-check", headers={"X-Token": "bad"}).status_code == 401


def test_updater_check_endpoint(client: TestClient):
    resp = client.get("/api/system/update-check")
    assert resp.status_code == 200
    data = resp.json()
    assert data["platform"] == "desktop"
    assert "current_version" in data
    assert "latest_version" in data
    assert "has_update" in data


def test_updater_progress_endpoint(client: TestClient):
    resp = client.get("/api/system/update-progress")
    assert resp.status_code == 200
    data = resp.json()
    assert data["state"] in ["idle", "downloading", "ready", "completed", "error"]
    assert "progress_percent" in data
    assert "message" in data


def test_updater_apply_endpoint(client: TestClient, monkeypatch):
    from app.updater import DesktopUpdater

    monkeypatch.setattr(DesktopUpdater, "_run_git_pull", lambda self: (True, "Up to date"))
    monkeypatch.setattr(DesktopUpdater, "_run_frontend_build", lambda self: (True, "Build ok"))

    resp = client.post("/api/system/update-apply")
    assert resp.status_code == 200
    data = resp.json()
    assert data["started"] is True
    assert "message" in data
