from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.containers_api import _normalize_container_item, _parse_port_entry, detect_engine
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


def test_parse_port_entry():
    # String format
    ports_str = "0.0.0.0:8080->80/tcp, :::8080->80/tcp, 443/tcp"
    parsed = _parse_port_entry(ports_str)
    assert len(parsed) == 3
    assert parsed[0]["host_port"] == 8080
    assert parsed[0]["container_port"] == 80
    assert parsed[0]["protocol"] == "tcp"
    assert parsed[2]["container_port"] == 443

    # Dict format (Podman json)
    podman_ports = [{"hostIP": "127.0.0.1", "hostPort": 3000, "containerPort": 3000, "protocol": "tcp"}]
    parsed_dict = _parse_port_entry(podman_ports)
    assert len(parsed_dict) == 1
    assert parsed_dict[0]["host_port"] == 3000


def test_normalize_container_item():
    podman_raw = {
        "Id": "abcdef1234567890",
        "Names": ["web-nginx"],
        "Image": "docker.io/library/nginx:latest",
        "State": "running",
        "Status": "Up 2 hours",
        "CreatedAt": "2 hours ago",
        "Ports": [{"hostPort": 80, "containerPort": 80}],
        "Command": ["nginx", "-g", "daemon off;"],
    }
    item = _normalize_container_item(podman_raw, "podman")
    assert item["id"] == "abcdef123456"
    assert item["names"] == ["web-nginx"]
    assert item["state"] == "running"
    assert item["ports"][0]["host_port"] == 80

    docker_raw = {
        "ID": "112233445566",
        "Names": "/redis-cache",
        "Image": "redis:alpine",
        "State": "exited",
        "Status": "Exited (0) 5 minutes ago",
        "CreatedAt": "2026-10-28 10:00:00",
        "Ports": "6379/tcp",
    }
    item_docker = _normalize_container_item(docker_raw, "docker")
    assert item_docker["id"] == "112233445566"
    assert item_docker["names"] == ["redis-cache"]
    assert item_docker["state"] == "exited"


def test_containers_endpoints_server_not_found(client: TestClient):
    res = client.get("/api/containers/engine?server_id=9999")
    assert res.status_code == 404

    res = client.get("/api/containers/list?server_id=9999")
    assert res.status_code == 404

    res = client.get("/api/containers/images?server_id=9999")
    assert res.status_code == 404
