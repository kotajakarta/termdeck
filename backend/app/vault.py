"""PIN-protected access to the encrypted database.

The PIN is never stored. The SQLCipher key is scrypt(PIN, salt=vault.key), so a
correct PIN is exactly a PIN that decrypts the database. vault.key is a random
per-installation salt; copying the database without it is useless.
"""

import hashlib
import logging
import os
import re
import secrets
import sqlite3
import threading
import time
from collections.abc import Callable
from pathlib import Path

from cryptography.fernet import InvalidToken

from .crypto import SecretBox
from .db import Store, WrongKey
from .schemas import BookmarkIn, ServerIn, SnippetIn
from .ssh import ConnectionPool

log = logging.getLogger(__name__)

DB_FILE = "termdeck.vault"
SALT_FILE = "vault.key"
LEGACY_DB_FILE = "termdeck.db"
LEGACY_KEY_FILE = "secret.key"
LEGACY_DEFAULT_PIN = "7821724"

PIN_PATTERN = re.compile(r"^\d{4,12}$")
# ~150 ms per guess and 64 MiB of memory: slows down offline brute force of a short PIN.
SCRYPT_N = 2**16
SCRYPT_R = 8
SCRYPT_P = 1

FREE_ATTEMPTS = 5
BASE_BLOCK_SECONDS = 30.0
MAX_BLOCK_SECONDS = 900.0


class InvalidPin(Exception):
    pass


class TooManyAttempts(Exception):
    def __init__(self, retry_after: float) -> None:
        super().__init__(f"Terlalu banyak PIN salah. Coba lagi dalam {int(retry_after) + 1} detik.")
        self.retry_after = retry_after


def validate_pin(pin: str) -> None:
    if not PIN_PATTERN.match(pin):
        raise ValueError("PIN harus 4-12 digit angka.")


class Vault:
    def __init__(self, folder: Path, initial_pin: str = "", clock: Callable[[], float] = time.monotonic) -> None:
        self.folder = folder
        self.db_path = folder / DB_FILE
        self._initial_pin = initial_pin
        self._clock = clock
        self._lock = threading.Lock()
        self._key: bytes | None = None
        self._sessions: set[str] = set()
        self._sessions_needing_pin_change: set[str] = set()
        self._failures = 0
        self._blocked_until = 0.0
        self.store: Store | None = None
        self.pool: ConnectionPool | None = None

    @property
    def unlocked(self) -> bool:
        return self.store is not None

    @property
    def is_initialized(self) -> bool:
        return self.db_path.exists()

    # ---- key handling --------------------------------------------------

    def _salt(self) -> bytes:
        path = self.folder / SALT_FILE
        if path.exists():
            return path.read_bytes()
        salt = os.urandom(32)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(salt)
        return salt

    def _derive(self, pin: str) -> bytes:
        return hashlib.scrypt(
            pin.encode("utf-8"),
            salt=self._salt(),
            n=SCRYPT_N,
            r=SCRYPT_R,
            p=SCRYPT_P,
            maxmem=128 * 1024 * 1024,
            dklen=32,
        )

    # ---- throttling ----------------------------------------------------

    def _check_throttle(self) -> None:
        remaining = self._blocked_until - self._clock()
        if remaining > 0:
            raise TooManyAttempts(remaining)

    def _record_failure(self) -> None:
        self._failures += 1
        if self._failures >= FREE_ATTEMPTS:
            delay = min(BASE_BLOCK_SECONDS * 2 ** (self._failures - FREE_ATTEMPTS), MAX_BLOCK_SECONDS)
            self._blocked_until = self._clock() + delay

    # ---- public API ----------------------------------------------------

    def setup_pin(self, pin: str) -> str:
        """Create the encrypted database with a fresh user-provided PIN.

        Blocking (scrypt + file I/O).
        """
        with self._lock:
            if self.is_initialized:
                raise RuntimeError("Vault sudah diinisialisasi.")
            validate_pin(pin)
            if pin == LEGACY_DEFAULT_PIN:
                raise ValueError("PIN tidak boleh menggunakan PIN default lama.")
            key = self._derive(pin)
            self._open(key)
            self._migrate_legacy()
            session = secrets.token_urlsafe(32)
            self._sessions.add(session)
            return session

    def unlock(self, pin: str) -> str:
        """Verify the PIN, open the database if needed, and return a new session token.

        Blocking (scrypt + file I/O): call it from a worker thread in async code.
        """
        with self._lock:
            self._check_throttle()
            if not self.db_path.exists():
                # Allow non-interactive auto-init only if explicit non-default initial_pin was supplied
                if self._initial_pin and self._initial_pin != LEGACY_DEFAULT_PIN and secrets.compare_digest(pin.encode(), self._initial_pin.encode()):
                    key = self._derive(pin)
                    self._open(key)
                    self._migrate_legacy()
                else:
                    raise InvalidPin("Vault belum diinisialisasi.")
            key = self._derive(pin)
            if self._key is not None:
                ok = secrets.compare_digest(key, self._key)
            else:
                try:
                    self._open(key)
                    ok = True
                except WrongKey:
                    ok = False
            if not ok:
                self._record_failure()
                raise InvalidPin("PIN salah.")
            self._failures = 0
            self._blocked_until = 0.0
            session = secrets.token_urlsafe(32)
            self._sessions.add(session)
            if pin == LEGACY_DEFAULT_PIN:
                self._sessions_needing_pin_change.add(session)
            return session

    def needs_pin_change(self, session: str) -> bool:
        if not session:
            return False
        return session in self._sessions_needing_pin_change

    def _open(self, key: bytes) -> None:
        store = Store(self.db_path, key)
        self.store = store
        self.pool = ConnectionPool(store)
        self._key = key

    def check_session(self, session: str) -> bool:
        if not session or self.store is None:
            return False
        return any(secrets.compare_digest(session, known) for known in self._sessions)

    async def lock(self) -> None:
        """End every session and close the database; the PIN is needed again."""
        with self._lock:
            pool, store = self.pool, self.store
            self._sessions.clear()
            self._sessions_needing_pin_change.clear()
            self._key = None
            self.store = None
            self.pool = None
        if pool is not None:
            await pool.close_all()
        if store is not None:
            store.close()

    def change_pin(self, old_pin: str, new_pin: str, current_session: str | None = None) -> None:
        """Re-encrypt the database under a new PIN. Blocking, like unlock()."""
        with self._lock:
            self._check_throttle()
            if self.store is None or self._key is None:
                raise InvalidPin("Aplikasi terkunci.")
            if not secrets.compare_digest(self._derive(old_pin), self._key):
                self._record_failure()
                raise InvalidPin("PIN lama salah.")
            validate_pin(new_pin)
            if new_pin == LEGACY_DEFAULT_PIN:
                raise ValueError("PIN tidak boleh menggunakan PIN default lama.")
            new_key = self._derive(new_pin)
            self.store.rekey(new_key)
            self._key = new_key
            # Revoke all other sessions (OWASP session revocation)
            if current_session and current_session in self._sessions:
                self._sessions = {current_session}
            if current_session:
                self._sessions_needing_pin_change.discard(current_session)
            else:
                self._sessions_needing_pin_change.clear()

    # ---- migration from the pre-encryption format ------------------------

    def _migrate_legacy(self) -> None:
        legacy_db = self.folder / LEGACY_DB_FILE
        legacy_key = self.folder / LEGACY_KEY_FILE
        if self.store is None or not legacy_db.exists() or not legacy_key.exists():
            return
        box = SecretBox(legacy_key)

        def secret(token: str | None) -> str | None:
            try:
                return box.decrypt(token)
            except InvalidToken:
                log.warning("Legacy secret could not be decrypted and was skipped")
                return None

        con = sqlite3.connect(legacy_db)
        con.row_factory = sqlite3.Row
        try:
            servers = con.execute("SELECT * FROM servers ORDER BY id").fetchall()
            bookmarks = con.execute("SELECT * FROM bookmarks ORDER BY id").fetchall()
            snippets = con.execute("SELECT * FROM snippets ORDER BY id").fetchall()
        finally:
            con.close()

        id_map: dict[int, int] = {}
        for row in servers:
            created = self.store.create_server(
                ServerIn(
                    name=row["name"],
                    host=row["host"],
                    port=row["port"],
                    username=row["username"],
                    auth_type=row["auth_type"],
                    password=secret(row["password_enc"]),
                    private_key=secret(row["private_key_enc"]),
                    passphrase=secret(row["passphrase_enc"]),
                    group_name=row["group_name"],
                    color=row["color"],
                )
            )
            if row["host_key"]:
                self.store.set_host_key(created.id, row["host_key"], row["host_key_fp"])
            id_map[row["id"]] = created.id
        for row in bookmarks:
            if row["server_id"] in id_map:
                self.store.create_bookmark(
                    BookmarkIn(
                        server_id=id_map[row["server_id"]],
                        name=row["name"],
                        path=row["path"],
                        startup_cmd=row["startup_cmd"],
                    )
                )
        for row in snippets:
            self.store.create_snippet(SnippetIn(name=row["name"], command=row["command"]))
        # Kept (not deleted) so the user can verify the migration before removing it.
        legacy_db.rename(legacy_db.with_name(LEGACY_DB_FILE + ".migrated"))
        log.info("Migrated %d servers from the unencrypted database", len(servers))
