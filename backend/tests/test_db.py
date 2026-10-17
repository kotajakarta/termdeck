import sqlite3
from pathlib import Path

import pytest

from app.db import Store, WrongKey
from app.schemas import BookmarkIn, ServerIn, SnippetIn

KEY = bytes(range(32))
OTHER_KEY = bytes(range(1, 33))


@pytest.fixture()
def store(tmp_path: Path) -> Store:
    return Store(tmp_path / "data.db", KEY)


def make_server(**over: object) -> ServerIn:
    data: dict[str, object] = {
        "name": "web-1",
        "host": "10.0.0.5",
        "port": 22,
        "username": "deploy",
        "auth_type": "password",
        "password": "s3cret",
    }
    data.update(over)
    return ServerIn.model_validate(data)


def test_create_and_list_server_hides_secret(store: Store) -> None:
    created = store.create_server(make_server())
    listed = store.list_servers()
    assert [s.id for s in listed] == [created.id]
    dumped = listed[0].model_dump()
    assert "password" not in dumped
    assert dumped["has_password"] is True
    secrets = store.get_server_secrets(created.id)
    assert secrets is not None and secrets.password == "s3cret"


def test_database_file_is_encrypted(tmp_path: Path) -> None:
    store = Store(tmp_path / "data.db", KEY)
    store.create_server(make_server(host="very-secret-host.example", password="plain-pass-123"))
    store.create_bookmark(BookmarkIn(server_id=1, name="proj", path="/srv/hidden-project"))
    store.close()
    raw = b"".join(p.read_bytes() for p in tmp_path.iterdir() if p.name.startswith("data.db"))
    for needle in (b"very-secret-host", b"plain-pass-123", b"hidden-project", b"CREATE TABLE"):
        assert needle not in raw
    with pytest.raises(sqlite3.DatabaseError):
        sqlite3.connect(tmp_path / "data.db").execute("SELECT * FROM servers").fetchall()


def test_wrong_key_rejected(tmp_path: Path) -> None:
    Store(tmp_path / "data.db", KEY).close()
    with pytest.raises(WrongKey):
        Store(tmp_path / "data.db", OTHER_KEY)


def test_rekey(tmp_path: Path) -> None:
    store = Store(tmp_path / "data.db", KEY)
    store.create_server(make_server())
    store.rekey(OTHER_KEY)
    store.close()
    with pytest.raises(WrongKey):
        Store(tmp_path / "data.db", KEY)
    reopened = Store(tmp_path / "data.db", OTHER_KEY)
    assert [s.name for s in reopened.list_servers()] == ["web-1"]


def test_update_keeps_secret(store: Store) -> None:
    created = store.create_server(make_server())
    updated = store.update_server(created.id, make_server(name="web-renamed", password=None))
    assert updated is not None and updated.name == "web-renamed"
    secrets = store.get_server_secrets(created.id)
    assert secrets is not None and secrets.password == "s3cret"


def test_update_with_empty_secret_clears_it(store: Store) -> None:
    created = store.create_server(make_server())
    store.update_server(created.id, make_server(password=""))
    secrets = store.get_server_secrets(created.id)
    assert secrets is not None and secrets.password is None


def test_update_host_change_resets_host_key(store: Store) -> None:
    created = store.create_server(make_server())
    store.set_host_key(created.id, "ssh-ed25519 AAAA")
    store.update_server(created.id, make_server(host="10.0.0.6", password=None))
    secrets = store.get_server_secrets(created.id)
    assert secrets is not None and secrets.host_key is None


def test_delete_server_cascades(store: Store) -> None:
    created = store.create_server(make_server())
    store.create_bookmark(BookmarkIn(server_id=created.id, name="app", path="/var/www/app"))
    assert len(store.list_bookmarks(None)) == 1
    assert store.delete_server(created.id) is True
    assert store.list_bookmarks(None) == []
    assert store.delete_server(created.id) is False


def test_bookmark_and_snippet_crud(store: Store) -> None:
    srv = store.create_server(make_server())
    bm = store.create_bookmark(
        BookmarkIn(server_id=srv.id, name="api", path="~/api", startup_cmd="git status")
    )
    upd = store.update_bookmark(bm.id, BookmarkIn(server_id=srv.id, name="api2", path="~/api"))
    assert upd is not None and upd.name == "api2" and upd.startup_cmd == ""
    assert [b.id for b in store.list_bookmarks(srv.id)] == [bm.id]
    assert store.delete_bookmark(bm.id) is True

    sn = store.create_snippet(SnippetIn(name="pull", command="git pull"))
    assert [s.command for s in store.list_snippets()] == ["git pull"]
    assert store.delete_snippet(sn.id) is True


def test_new_servers_are_listed_in_creation_order(store: Store) -> None:
    for name in ("zeta", "alpha", "mid"):
        store.create_server(make_server(name=name))
    assert [s.name for s in store.list_servers()] == ["zeta", "alpha", "mid"]


def test_reorder_servers(store: Store) -> None:
    ids = [store.create_server(make_server(name=n)).id for n in ("a", "b", "c")]
    store.reorder_servers([ids[2], ids[0], ids[1]])
    assert [s.name for s in store.list_servers()] == ["c", "a", "b"]
    # A server created afterwards goes to the bottom.
    store.create_server(make_server(name="d"))
    assert [s.name for s in store.list_servers()] == ["c", "a", "b", "d"]


def test_reorder_rejects_unknown_or_missing_ids(store: Store) -> None:
    ids = [store.create_server(make_server(name=n)).id for n in ("a", "b")]
    with pytest.raises(ValueError):
        store.reorder_servers([ids[0]])
    with pytest.raises(ValueError):
        store.reorder_servers([ids[0], ids[1], 999])
    with pytest.raises(ValueError):
        store.reorder_servers([ids[0], ids[0]])


def test_adds_sort_order_to_existing_vault_keeping_visible_order(tmp_path: Path) -> None:
    from sqlcipher3 import dbapi2 as sqlcipher

    con = sqlcipher.connect(str(tmp_path / "old.db"))
    con.execute(f"PRAGMA key = \"x'{KEY.hex()}'\"")
    con.executescript(
        """
        CREATE TABLE servers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, host TEXT NOT NULL,
            port INTEGER NOT NULL DEFAULT 22, username TEXT NOT NULL, auth_type TEXT NOT NULL, password TEXT,
            private_key TEXT, passphrase TEXT, group_name TEXT NOT NULL DEFAULT '', color TEXT NOT NULL DEFAULT '',
            host_key TEXT, host_key_fp TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
        INSERT INTO servers (name, host, username, auth_type, group_name) VALUES
            ('web', 'h', 'u', 'password', 'B'), ('db', 'h', 'u', 'password', 'A'), ('api', 'h', 'u', 'password', 'A');
        """
    )
    con.commit()
    con.close()
    store = Store(tmp_path / "old.db", KEY)
    # Same order the sidebar showed before (group, then name).
    assert [s.name for s in store.list_servers()] == ["api", "db", "web"]
    store.create_server(make_server(name="new"))
    assert [s.name for s in store.list_servers()][-1] == "new"
