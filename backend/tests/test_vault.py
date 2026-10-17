import sqlite3
from pathlib import Path

import pytest

from app.crypto import SecretBox
from app.vault import InvalidPin, TooManyAttempts, Vault

PIN = "7821724"


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture()
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture()
def vault(tmp_path: Path, clock: FakeClock) -> Vault:
    return Vault(tmp_path, initial_pin=PIN, clock=clock)


def test_first_unlock_requires_initial_pin(vault: Vault, tmp_path: Path) -> None:
    assert not vault.unlocked
    with pytest.raises(InvalidPin):
        vault.unlock("0000")
    assert not (tmp_path / "termdeck.vault").exists()
    session = vault.unlock(PIN)
    assert vault.unlocked and vault.check_session(session)
    assert (tmp_path / "termdeck.vault").exists()


async def test_lock_then_unlock_reopens_data(vault: Vault, tmp_path: Path, clock: FakeClock) -> None:
    from app.schemas import ServerIn

    session = vault.unlock(PIN)
    assert vault.store is not None
    vault.store.create_server(ServerIn(name="a", host="h", username="u"))
    await vault.lock()
    assert not vault.unlocked and not vault.check_session(session)

    fresh = Vault(tmp_path, initial_pin="ignored-after-creation", clock=clock)
    with pytest.raises(InvalidPin):
        fresh.unlock("ignored-after-creation")
    fresh.unlock(PIN)
    assert fresh.store is not None and [s.name for s in fresh.store.list_servers()] == ["a"]


def test_wrong_pin_while_unlocked_does_not_create_session(vault: Vault) -> None:
    vault.unlock(PIN)
    with pytest.raises(InvalidPin):
        vault.unlock("1111")
    second = vault.unlock(PIN)
    assert vault.check_session(second)
    assert not vault.check_session("forged")


def test_throttles_after_five_failures(vault: Vault, clock: FakeClock) -> None:
    vault.unlock(PIN)
    for _ in range(5):
        with pytest.raises(InvalidPin):
            vault.unlock("9999")
    with pytest.raises(TooManyAttempts) as info:
        vault.unlock(PIN)
    assert info.value.retry_after == pytest.approx(30)
    clock.now += 31
    assert vault.check_session(vault.unlock(PIN))


async def test_change_pin(vault: Vault, tmp_path: Path, clock: FakeClock) -> None:
    vault.unlock(PIN)
    with pytest.raises(InvalidPin):
        vault.change_pin("0000", "123456")
    with pytest.raises(ValueError):
        vault.change_pin(PIN, "12")
    vault.change_pin(PIN, "123456")
    await vault.lock()
    fresh = Vault(tmp_path, initial_pin=PIN, clock=clock)
    with pytest.raises(InvalidPin):
        fresh.unlock(PIN)
    fresh.unlock("123456")


def test_migrates_legacy_plaintext_database(tmp_path: Path, vault: Vault) -> None:
    box = SecretBox(tmp_path / "secret.key")
    con = sqlite3.connect(tmp_path / "termdeck.db")
    con.executescript(
        """
        CREATE TABLE servers (id INTEGER PRIMARY KEY, name TEXT, host TEXT, port INTEGER, username TEXT,
            auth_type TEXT, password_enc TEXT, private_key_enc TEXT, passphrase_enc TEXT,
            group_name TEXT, color TEXT, host_key TEXT, host_key_fp TEXT, created_at TEXT);
        CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, server_id INTEGER, name TEXT, path TEXT,
            startup_cmd TEXT, created_at TEXT);
        CREATE TABLE snippets (id INTEGER PRIMARY KEY, name TEXT, command TEXT, created_at TEXT);
        """
    )
    con.execute(
        "INSERT INTO servers VALUES (7, 'wsl-rocky', '100.1.2.3', 22, 'me', 'password', ?, NULL, NULL,"
        " 'grp', '#e2574c', 'ssh-ed25519 AAAA', 'SHA256:x', '')",
        (box.encrypt("pw"),),
    )
    con.execute("INSERT INTO bookmarks VALUES (3, 7, 'app', '/srv/app', 'ls', '')")
    con.execute("INSERT INTO snippets VALUES (1, 'pull', 'git pull', '')")
    con.commit()
    con.close()

    vault.unlock(PIN)
    store = vault.store
    assert store is not None
    [server] = store.list_servers()
    assert (server.name, server.group_name, server.host_key_fingerprint) == ("wsl-rocky", "grp", "SHA256:x")
    secrets = store.get_server_secrets(server.id)
    assert secrets is not None and secrets.password == "pw" and secrets.host_key == "ssh-ed25519 AAAA"
    [bookmark] = store.list_bookmarks(server.id)
    assert (bookmark.path, bookmark.startup_cmd) == ("/srv/app", "ls")
    assert [s.command for s in store.list_snippets()] == ["git pull"]
    assert not (tmp_path / "termdeck.db").exists()
    assert (tmp_path / "termdeck.db.migrated").exists()
