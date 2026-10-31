from pathlib import Path
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

def test_live_cloudflare_token(tmp_path: Path):
    token_file = Path(__file__).resolve().parent.parent.parent / "testokencf.txt"
    if not token_file.exists():
        pytest.skip("testokencf.txt tidak ditemukan")
    token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        pytest.skip("token kosong")

    store = Store(tmp_path / "live.db", KEY)
    app = FastAPI()
    router = APIRouter(prefix="/api")
    register_cloudflare_routes(router, lambda: DummyCtx(store=store))
    app.include_router(router)
    client = TestClient(app)

    # 1. Verify token live
    verify_resp = client.post("/api/cf/accounts/verify", json={"token": token})
    assert verify_resp.status_code == 200, f"Verify gagal: {verify_resp.text}"
    v_data = verify_resp.json()
    assert v_data["account_id"] == "c5a58505f8fc830979277c99db4be0c8"
    assert "Aithendy" in v_data["account_name"]

    # 2. Simpan akun ke database vault
    create_resp = client.post("/api/cf/accounts", json={"name": "Live Test Account", "token": token})
    assert create_resp.status_code == 200
    acc_id = create_resp.json()["id"]

    # 3. List tunnels live
    tunnels_resp = client.get(f"/api/cf/accounts/{acc_id}/tunnels")
    assert tunnels_resp.status_code == 200
    tunnels = tunnels_resp.json()
    assert len(tunnels) >= 5
    tunnel_names = {t["name"] for t in tunnels}
    assert "cockpit" in tunnel_names
    assert "ollama" in tunnel_names

    # 4. List zones live
    zones_resp = client.get(f"/api/cf/accounts/{acc_id}/zones")
    assert zones_resp.status_code == 200
    zones = zones_resp.json()
    assert len(zones) >= 5
    zone_names = {z["name"] for z in zones}
    assert "aithendi.my.id" in zone_names
