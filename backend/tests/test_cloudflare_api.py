from pathlib import Path
from unittest.mock import patch
from dataclasses import dataclass
import pytest
from fastapi.testclient import TestClient
from fastapi import APIRouter, FastAPI

from app.db import Store
from app.cloudflare_api import register_cloudflare_routes

KEY = bytes(range(32))

@dataclass
class DummyCtx:
    store: Store

@pytest.fixture()
def app_client(tmp_path: Path):
    store = Store(tmp_path / "data.db", KEY)
    app = FastAPI()
    router = APIRouter(prefix="/api")
    register_cloudflare_routes(router, lambda: DummyCtx(store=store))
    app.include_router(router)
    client = TestClient(app)
    return client, store

def test_verify_and_create_account(app_client):
    client, store = app_client

    mock_cf_res = {
        "success": True,
        "errors": [],
        "result": [{"id": "acc-123", "name": "Test Account"}]
    }

    with patch("app.cloudflare_api.cf_request", return_value=mock_cf_res):
        resp = client.post("/api/cf/accounts/verify", json={"token": "cfat_testtoken1234567890"})
        assert resp.status_code == 200
        data = resp.json()
        assert data["account_id"] == "acc-123"
        assert data["account_name"] == "Test Account"

        create_resp = client.post("/api/cf/accounts", json={
            "name": "My CF Account",
            "token": "cfat_testtoken1234567890"
        })
        assert create_resp.status_code == 200
        acc_data = create_resp.json()
        assert acc_data["name"] == "My CF Account"
        assert acc_data["account_id"] == "acc-123"

        list_resp = client.get("/api/cf/accounts")
        assert list_resp.status_code == 200
        accounts = list_resp.json()
        assert len(accounts) == 1
        assert accounts[0]["id"] == acc_data["id"]

def test_list_tunnels_and_zones(app_client):
    client, store = app_client
    acc = store.create_cf_account("Acc 1", "cfat_secret123456789", "acc-123", "Test Account")

    mock_tunnels = {
        "success": True,
        "result": [
            {
                "id": "tun-abc",
                "name": "my-tunnel",
                "status": "healthy",
                "connections": [{"colo_name": "cgk01"}],
                "created_at": "2026-01-01T00:00:00Z",
                "remote_config": True
            }
        ]
    }
    mock_zones = {
        "success": True,
        "result": [
            {"id": "zone-xyz", "name": "example.com", "status": "active"}
        ]
    }

    def cf_mock(method, path, token, data=None):
        if "cfd_tunnel" in path and not path.endswith("configurations"):
            return mock_tunnels
        if "zones" in path:
            return mock_zones
        return {"success": True, "result": []}

    with patch("app.cloudflare_api.cf_request", side_effect=cf_mock):
        tun_resp = client.get(f"/api/cf/accounts/{acc['id']}/tunnels")
        assert tun_resp.status_code == 200
        assert len(tun_resp.json()) == 1
        assert tun_resp.json()[0]["name"] == "my-tunnel"

        zone_resp = client.get(f"/api/cf/accounts/{acc['id']}/zones")
        assert zone_resp.status_code == 200
        assert len(zone_resp.json()) == 1
        assert zone_resp.json()[0]["name"] == "example.com"


def test_update_account(app_client):
    client, store = app_client
    acc = store.create_cf_account("Acc 1", "cfat_secret123456789", "acc-123", "Old Org")

    # 1. Update name only (no token)
    resp = client.put(f"/api/cf/accounts/{acc['id']}", json={"name": "Acc Renamed"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == acc["id"]
    assert data["name"] == "Acc Renamed"
    assert data["account_id"] == "acc-123"

    # 2. Update name and token (mock cf verification)
    mock_cf_res = {
        "success": True,
        "result": [{"id": "acc-new-456", "name": "New Org"}]
    }
    with patch("app.cloudflare_api.cf_request", return_value=mock_cf_res):
        resp2 = client.put(f"/api/cf/accounts/{acc['id']}", json={
            "name": "Acc With New Token",
            "token": "cfat_newsecret9876543210"
        })
        assert resp2.status_code == 200
        data2 = resp2.json()
        assert data2["name"] == "Acc With New Token"
        assert data2["account_id"] == "acc-new-456"
        assert data2["account_name"] == "New Org"

    # 3. Update non-existent account returns 404
    resp404 = client.put("/api/cf/accounts/99999", json={"name": "Ghost"})
    assert resp404.status_code == 404

