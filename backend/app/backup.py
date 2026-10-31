from __future__ import annotations

import hashlib
import json
import os
from typing import Any

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .db import Store
from .schemas import BookmarkIn, ServerIn, SnippetIn

BACKUP_MAGIC = "TERMDECK_BACKUP"
BACKUP_VERSION = 1
PBKDF2_ITERATIONS = 100_000


class InvalidBackupError(Exception):
    pass


class InvalidBackupPinError(Exception):
    pass


def _derive_key(pin: str, salt: bytes) -> bytes:
    if not pin:
        raise InvalidBackupPinError("PIN tidak boleh kosong")
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, PBKDF2_ITERATIONS, dklen=32)


def encrypt_config(payload: dict[str, Any], pin: str) -> dict[str, Any]:
    salt = os.urandom(16)
    key = _derive_key(pin, salt)
    iv = os.urandom(12)
    aesgcm = AESGCM(key)
    plaintext_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    ciphertext = aesgcm.encrypt(iv, plaintext_bytes, None)

    return {
        "magic": BACKUP_MAGIC,
        "version": BACKUP_VERSION,
        "kdf": {
            "algorithm": "PBKDF2-HMAC-SHA256",
            "iterations": PBKDF2_ITERATIONS,
            "salt_hex": salt.hex(),
        },
        "cipher": "AES-256-GCM",
        "iv_hex": iv.hex(),
        "ciphertext_hex": ciphertext.hex(),
    }


def decrypt_config(envelope: dict[str, Any], pin: str) -> dict[str, Any]:
    if not isinstance(envelope, dict) or envelope.get("magic") != BACKUP_MAGIC:
        raise InvalidBackupError("Format file backup tidak valid (magic header salah)")
    version = envelope.get("version", 0)
    if version != BACKUP_VERSION:
        raise InvalidBackupError(f"Versi backup {version} tidak didukung")

    kdf = envelope.get("kdf", {})
    salt_hex = kdf.get("salt_hex", "")
    iv_hex = envelope.get("iv_hex", "")
    ct_hex = envelope.get("ciphertext_hex", "")

    if not salt_hex or not iv_hex or not ct_hex:
        raise InvalidBackupError("Komponen enkripsi backup tidak lengkap")

    try:
        salt = bytes.fromhex(salt_hex)
        iv = bytes.fromhex(iv_hex)
        ciphertext = bytes.fromhex(ct_hex)
    except ValueError as exc:
        raise InvalidBackupError("Data enkripsi backup rusak") from exc

    key = _derive_key(pin, salt)
    aesgcm = AESGCM(key)
    try:
        plaintext = aesgcm.decrypt(iv, ciphertext, None)
    except InvalidTag as exc:
        raise InvalidBackupPinError("PIN backup salah atau file telah dimodifikasi") from exc

    try:
        data = json.loads(plaintext.decode("utf-8"))
        if not isinstance(data, dict):
            raise InvalidBackupError("Isi data backup bukan objek JSON")
        return data
    except Exception as exc:
        raise InvalidBackupError(f"Gagal membaca payload backup: {exc}") from exc


def collect_store_backup(store: Store) -> dict[str, Any]:
    servers_out = store.list_servers()
    exported_servers = []
    for srv in servers_out:
        secrets = store.get_server_secrets(srv.id)
        bookmarks = [
            {
                "name": bm.name,
                "path": bm.path,
                "startup_cmd": bm.startup_cmd,
            }
            for bm in store.list_bookmarks(srv.id)
        ]
        exported_servers.append({
            "name": srv.name,
            "host": srv.host,
            "port": srv.port,
            "username": srv.username,
            "auth_type": srv.auth_type,
            "password": secrets.password if secrets else None,
            "private_key": secrets.private_key if secrets else None,
            "passphrase": secrets.passphrase if secrets else None,
            "group_name": srv.group_name,
            "color": srv.color,
            "bookmarks": bookmarks,
        })

    snippets = [
        {
            "name": sn.name,
            "command": sn.command,
        }
        for sn in store.list_snippets()
    ]

    return {
        "version": 1,
        "servers": exported_servers,
        "snippets": snippets,
    }


def merge_backup_into_store(store: Store, payload: dict[str, Any]) -> dict[str, int]:
    stats = {
        "servers_added": 0,
        "servers_updated": 0,
        "bookmarks_added": 0,
        "snippets_added": 0,
    }
    existing_servers = store.list_servers()
    server_map = {
        (s.host.lower(), s.port, s.username.lower()): s
        for s in existing_servers
    }

    for s_dict in payload.get("servers", []):
        host = s_dict.get("host", "").strip()
        port = int(s_dict.get("port", 22))
        username = s_dict.get("username", "").strip()
        if not host or not username:
            continue
        key = (host.lower(), port, username.lower())
        existing = server_map.get(key)
        if existing is not None:
            srv_id = existing.id
            current_secrets = store.get_server_secrets(srv_id)
            new_password = s_dict.get("password")
            new_key = s_dict.get("private_key")
            new_passphrase = s_dict.get("passphrase")

            # Update credentials if existing has none but backup has
            has_cur_pw = bool(current_secrets and current_secrets.password)
            has_cur_key = bool(current_secrets and current_secrets.private_key)
            if (not has_cur_pw and new_password) or (not has_cur_key and new_key):
                store.update_server(
                    srv_id,
                    ServerIn(
                        name=existing.name,
                        host=existing.host,
                        port=existing.port,
                        username=existing.username,
                        auth_type=s_dict.get("auth_type", existing.auth_type),
                        password=new_password or (current_secrets.password if current_secrets else None),
                        private_key=new_key or (current_secrets.private_key if current_secrets else None),
                        passphrase=new_passphrase or (current_secrets.passphrase if current_secrets else None),
                        group_name=existing.group_name or s_dict.get("group_name", ""),
                        color=existing.color or s_dict.get("color", ""),
                    ),
                )
                stats["servers_updated"] += 1
        else:
            created = store.create_server(
                ServerIn(
                    name=s_dict.get("name") or host,
                    host=host,
                    port=port,
                    username=username,
                    auth_type=s_dict.get("auth_type", "password"),
                    password=s_dict.get("password"),
                    private_key=s_dict.get("private_key"),
                    passphrase=s_dict.get("passphrase"),
                    group_name=s_dict.get("group_name", ""),
                    color=s_dict.get("color", ""),
                )
            )
            srv_id = created.id
            server_map[key] = created
            stats["servers_added"] += 1

        existing_bms = {b.path: b for b in store.list_bookmarks(srv_id)}
        for bm_dict in s_dict.get("bookmarks", []):
            bm_path = bm_dict.get("path", "").strip()
            if bm_path and bm_path not in existing_bms:
                store.create_bookmark(
                    BookmarkIn(
                        server_id=srv_id,
                        name=bm_dict.get("name") or bm_path,
                        path=bm_path,
                        startup_cmd=bm_dict.get("startup_cmd", ""),
                    )
                )
                stats["bookmarks_added"] += 1

    existing_snippets = {sn.name: sn for sn in store.list_snippets()}
    for sn_dict in payload.get("snippets", []):
        sn_name = sn_dict.get("name", "").strip()
        if sn_name and sn_name not in existing_snippets:
            store.create_snippet(
                SnippetIn(
                    name=sn_name,
                    command=sn_dict.get("command", ""),
                )
            )
            stats["snippets_added"] += 1

    return stats
