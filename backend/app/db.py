import threading
from pathlib import Path
from typing import Any

from sqlcipher3 import dbapi2 as sqlcipher

from .schemas import (
    BookmarkIn,
    BookmarkOut,
    ServerIn,
    ServerOut,
    ServerSecrets,
    SnippetIn,
    SnippetOut,
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS servers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL DEFAULT 22,
    username TEXT NOT NULL,
    auth_type TEXT NOT NULL,
    password TEXT,
    private_key TEXT,
    passphrase TEXT,
    group_name TEXT NOT NULL DEFAULT '',
    color TEXT NOT NULL DEFAULT '',
    host_key TEXT,
    host_key_fp TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS bookmarks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    path TEXT NOT NULL,
    startup_cmd TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS snippets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    command TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS cf_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    token TEXT NOT NULL,
    account_id TEXT NOT NULL,
    account_name TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"""

Row = sqlcipher.Row


class WrongKey(Exception):
    """The database could not be decrypted with the given key."""


def _key_pragma(name: str, key: bytes) -> str:
    if len(key) != 32:
        raise ValueError("Database key must be 32 bytes")
    # Raw 256-bit key (x'..'): SQLCipher skips its own KDF; the caller already ran scrypt.
    return f"PRAGMA {name} = \"x'{key.hex()}'\""


class Store:
    """SQLite persistence encrypted as a whole with SQLCipher (AES-256)."""

    def __init__(self, db_path: Path, key: bytes) -> None:
        self._lock = threading.Lock()
        con = sqlcipher.connect(str(db_path), check_same_thread=False)
        try:
            con.execute("PRAGMA cipher_log_level = NONE")
            con.execute(_key_pragma("key", key))
            # Reading the schema is the first access that needs decryption.
            con.execute("SELECT count(*) FROM sqlite_master").fetchone()
        except sqlcipher.DatabaseError as exc:
            con.close()
            raise WrongKey(str(exc)) from exc
        con.row_factory = Row
        con.execute("PRAGMA journal_mode = WAL")
        con.execute("PRAGMA synchronous = NORMAL")
        con.execute("PRAGMA foreign_keys = ON")
        con.executescript(SCHEMA)
        self._migrate(con)
        con.commit()
        self._con = con

    @staticmethod
    def _migrate(con: Any) -> None:
        columns = {row["name"] for row in con.execute("PRAGMA table_info(servers)")}
        if "sort_order" not in columns:
            # Vaults created before servers could be reordered: start from the
            # order the sidebar used to show (group, then name).
            con.execute("ALTER TABLE servers ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            rows = con.execute(
                "SELECT id FROM servers ORDER BY group_name COLLATE NOCASE, name COLLATE NOCASE"
            ).fetchall()
            con.executemany(
                "UPDATE servers SET sort_order = ? WHERE id = ?",
                [(index, row["id"]) for index, row in enumerate(rows)],
            )

    def rekey(self, key: bytes) -> None:
        with self._lock:
            self._con.execute(_key_pragma("rekey", key))
            self._con.commit()

    def close(self) -> None:
        with self._lock:
            self._con.close()

    def _query(self, sql: str, params: tuple[Any, ...] = ()) -> list[Row]:
        with self._lock:
            return self._con.execute(sql, params).fetchall()

    def _exec(self, sql: str, params: tuple[Any, ...] = ()) -> sqlcipher.Cursor:
        with self._lock:
            cur = self._con.execute(sql, params)
            self._con.commit()
            return cur

    # ---- servers -------------------------------------------------------

    @staticmethod
    def _server_out(row: Row) -> ServerOut:
        return ServerOut(
            id=row["id"],
            name=row["name"],
            host=row["host"],
            port=row["port"],
            username=row["username"],
            auth_type=row["auth_type"],
            group_name=row["group_name"],
            color=row["color"],
            has_password=row["password"] is not None,
            has_private_key=row["private_key"] is not None,
            host_key_fingerprint=row["host_key_fp"],
        )

    def list_servers(self) -> list[ServerOut]:
        rows = self._query("SELECT * FROM servers ORDER BY sort_order, id")
        return [self._server_out(r) for r in rows]

    def get_server(self, server_id: int) -> ServerOut | None:
        rows = self._query("SELECT * FROM servers WHERE id = ?", (server_id,))
        return self._server_out(rows[0]) if rows else None

    def get_server_secrets(self, server_id: int) -> ServerSecrets | None:
        rows = self._query("SELECT * FROM servers WHERE id = ?", (server_id,))
        if not rows:
            return None
        row = rows[0]
        return ServerSecrets(
            id=row["id"],
            host=row["host"],
            port=row["port"],
            username=row["username"],
            auth_type=row["auth_type"],
            password=row["password"],
            private_key=row["private_key"],
            passphrase=row["passphrase"],
            host_key=row["host_key"],
        )

    def create_server(self, data: ServerIn) -> ServerOut:
        cur = self._exec(
            """INSERT INTO servers (name, host, port, username, auth_type, password,
                   private_key, passphrase, group_name, color, sort_order)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM servers))""",
            (
                data.name,
                data.host,
                data.port,
                data.username,
                data.auth_type,
                data.password or None,
                data.private_key or None,
                data.passphrase or None,
                data.group_name,
                data.color,
            ),
        )
        created = self.get_server(int(cur.lastrowid or 0))
        assert created is not None
        return created

    def update_server(self, server_id: int, data: ServerIn) -> ServerOut | None:
        rows = self._query("SELECT * FROM servers WHERE id = ?", (server_id,))
        if not rows:
            return None
        old = rows[0]

        def merge(new_value: str | None, column: str) -> str | None:
            # None keeps the old secret, empty string clears it.
            if new_value is None:
                return old[column]
            return new_value or None

        endpoint_changed = old["host"] != data.host or old["port"] != data.port
        self._exec(
            """UPDATE servers SET name = ?, host = ?, port = ?, username = ?, auth_type = ?,
                   password = ?, private_key = ?, passphrase = ?,
                   group_name = ?, color = ?, host_key = ?, host_key_fp = ?
               WHERE id = ?""",
            (
                data.name,
                data.host,
                data.port,
                data.username,
                data.auth_type,
                merge(data.password, "password"),
                merge(data.private_key, "private_key"),
                merge(data.passphrase, "passphrase"),
                data.group_name,
                data.color,
                None if endpoint_changed else old["host_key"],
                None if endpoint_changed else old["host_key_fp"],
                server_id,
            ),
        )
        return self.get_server(server_id)

    def reorder_servers(self, ids: list[int]) -> None:
        """Set the display order; `ids` must list every server exactly once."""
        with self._lock:
            existing = {row["id"] for row in self._con.execute("SELECT id FROM servers")}
            if len(ids) != len(set(ids)) or set(ids) != existing:
                raise ValueError("Urutan harus berisi setiap server tepat satu kali.")
            self._con.executemany(
                "UPDATE servers SET sort_order = ? WHERE id = ?", [(index, sid) for index, sid in enumerate(ids)]
            )
            self._con.commit()

    def delete_server(self, server_id: int) -> bool:
        return self._exec("DELETE FROM servers WHERE id = ?", (server_id,)).rowcount > 0

    def set_host_key(self, server_id: int, key: str | None, fingerprint: str | None = None) -> None:
        self._exec(
            "UPDATE servers SET host_key = ?, host_key_fp = ? WHERE id = ?",
            (key, fingerprint, server_id),
        )

    # ---- bookmarks -----------------------------------------------------

    @staticmethod
    def _bookmark_out(row: Row) -> BookmarkOut:
        return BookmarkOut(
            id=row["id"],
            server_id=row["server_id"],
            name=row["name"],
            path=row["path"],
            startup_cmd=row["startup_cmd"],
        )

    def list_bookmarks(self, server_id: int | None) -> list[BookmarkOut]:
        if server_id is None:
            rows = self._query("SELECT * FROM bookmarks ORDER BY name COLLATE NOCASE")
        else:
            rows = self._query(
                "SELECT * FROM bookmarks WHERE server_id = ? ORDER BY name COLLATE NOCASE",
                (server_id,),
            )
        return [self._bookmark_out(r) for r in rows]

    def get_bookmark(self, bookmark_id: int) -> BookmarkOut | None:
        rows = self._query("SELECT * FROM bookmarks WHERE id = ?", (bookmark_id,))
        return self._bookmark_out(rows[0]) if rows else None

    def create_bookmark(self, data: BookmarkIn) -> BookmarkOut:
        cur = self._exec(
            "INSERT INTO bookmarks (server_id, name, path, startup_cmd) VALUES (?, ?, ?, ?)",
            (data.server_id, data.name, data.path, data.startup_cmd),
        )
        created = self.get_bookmark(int(cur.lastrowid or 0))
        assert created is not None
        return created

    def update_bookmark(self, bookmark_id: int, data: BookmarkIn) -> BookmarkOut | None:
        cur = self._exec(
            "UPDATE bookmarks SET server_id = ?, name = ?, path = ?, startup_cmd = ? WHERE id = ?",
            (data.server_id, data.name, data.path, data.startup_cmd, bookmark_id),
        )
        return self.get_bookmark(bookmark_id) if cur.rowcount else None

    def delete_bookmark(self, bookmark_id: int) -> bool:
        return self._exec("DELETE FROM bookmarks WHERE id = ?", (bookmark_id,)).rowcount > 0

    # ---- snippets ------------------------------------------------------

    @staticmethod
    def _snippet_out(row: Row) -> SnippetOut:
        return SnippetOut(id=row["id"], name=row["name"], command=row["command"])

    def list_snippets(self) -> list[SnippetOut]:
        return [self._snippet_out(r) for r in self._query("SELECT * FROM snippets ORDER BY name COLLATE NOCASE")]

    def get_snippet(self, snippet_id: int) -> SnippetOut | None:
        rows = self._query("SELECT * FROM snippets WHERE id = ?", (snippet_id,))
        return self._snippet_out(rows[0]) if rows else None

    def create_snippet(self, data: SnippetIn) -> SnippetOut:
        cur = self._exec("INSERT INTO snippets (name, command) VALUES (?, ?)", (data.name, data.command))
        created = self.get_snippet(int(cur.lastrowid or 0))
        assert created is not None
        return created

    def update_snippet(self, snippet_id: int, data: SnippetIn) -> SnippetOut | None:
        cur = self._exec(
            "UPDATE snippets SET name = ?, command = ? WHERE id = ?", (data.name, data.command, snippet_id)
        )
        return self.get_snippet(snippet_id) if cur.rowcount else None

    def delete_snippet(self, snippet_id: int) -> bool:
        return self._exec("DELETE FROM snippets WHERE id = ?", (snippet_id,)).rowcount > 0

    # ---- cf_accounts (Cloudflare) --------------------------------------

    @staticmethod
    def _mask_token(token: str) -> str:
        if len(token) <= 10:
            return "******"
        return f"{token[:5]}...{token[-4:]}"

    def list_cf_accounts(self) -> list[dict[str, Any]]:
        rows = self._query("SELECT id, name, token, account_id, account_name, created_at FROM cf_accounts ORDER BY id ASC")
        return [
            {
                "id": r["id"],
                "name": r["name"],
                "token_masked": self._mask_token(r["token"]),
                "account_id": r["account_id"],
                "account_name": r["account_name"],
                "created_at": r["created_at"],
            }
            for r in rows
        ]

    def get_cf_account(self, account_id: int) -> dict[str, Any] | None:
        rows = self._query("SELECT id, name, token, account_id, account_name, created_at FROM cf_accounts WHERE id = ?", (account_id,))
        if not rows:
            return None
        r = rows[0]
        return {
            "id": r["id"],
            "name": r["name"],
            "token": r["token"],
            "token_masked": self._mask_token(r["token"]),
            "account_id": r["account_id"],
            "account_name": r["account_name"],
            "created_at": r["created_at"],
        }

    def create_cf_account(self, name: str, token: str, account_id: str, account_name: str = "") -> dict[str, Any]:
        cur = self._exec(
            "INSERT INTO cf_accounts (name, token, account_id, account_name) VALUES (?, ?, ?, ?)",
            (name, token, account_id, account_name),
        )
        created = self.get_cf_account(int(cur.lastrowid or 0))
        assert created is not None
        return created

    def update_cf_account(
        self,
        id: int,
        name: str,
        token: str | None = None,
        account_id: str | None = None,
        account_name: str | None = None,
    ) -> dict[str, Any] | None:
        existing = self.get_cf_account(id)
        if not existing:
            return None

        clean_token = token.strip() if token is not None else ""
        if clean_token:
            self._exec(
                "UPDATE cf_accounts SET name = ?, token = ?, account_id = ?, account_name = ? WHERE id = ?",
                (
                    name.strip(),
                    clean_token,
                    account_id.strip() if account_id else existing["account_id"],
                    account_name.strip() if account_name is not None else existing["account_name"],
                    id,
                ),
            )
        else:
            self._exec(
                "UPDATE cf_accounts SET name = ? WHERE id = ?",
                (name.strip(), id),
            )
        return self.get_cf_account(id)

    def delete_cf_account(self, account_id: int) -> bool:
        return self._exec("DELETE FROM cf_accounts WHERE id = ?", (account_id,)).rowcount > 0

