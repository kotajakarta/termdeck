from typing import Any, Literal

from pydantic import BaseModel, Field

AuthType = Literal["password", "key"]


class ServerIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    host: str = Field(min_length=1, max_length=255)
    port: int = Field(default=22, ge=1, le=65535)
    username: str = Field(min_length=1, max_length=100)
    auth_type: AuthType = "password"
    # None means "keep the stored value" on update.
    password: str | None = None
    private_key: str | None = None
    passphrase: str | None = None
    group_name: str = Field(default="", max_length=100)
    color: str = Field(default="", max_length=20)


class ServerOut(BaseModel):
    id: int
    name: str
    host: str
    port: int
    username: str
    auth_type: AuthType
    group_name: str
    color: str
    has_password: bool
    has_private_key: bool
    host_key_fingerprint: str | None


class ServerSecrets(BaseModel):
    id: int
    host: str
    port: int
    username: str
    auth_type: AuthType
    password: str | None
    private_key: str | None
    passphrase: str | None
    host_key: str | None


class BookmarkIn(BaseModel):
    server_id: int
    name: str = Field(min_length=1, max_length=100)
    path: str = Field(min_length=1, max_length=1024)
    startup_cmd: str = Field(default="", max_length=1024)


class BookmarkOut(BookmarkIn):
    id: int


class SnippetIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    command: str = Field(min_length=1, max_length=4096)


class SnippetOut(SnippetIn):
    id: int


class DirEntry(BaseModel):
    name: str
    path: str
    is_dir: bool
    size: int = 0
    mtime: int | None = None
    mode: str = ""
    is_symlink: bool = False


class DirListing(BaseModel):
    path: str
    entries: list[DirEntry]


class FileContentOut(BaseModel):
    path: str
    content: str
    size: int
    mtime: int | None = None
    mode: str = "0644"


class FileWriteIn(BaseModel):
    path: str = Field(min_length=1, max_length=2048)
    content: str


class FilePathIn(BaseModel):
    path: str = Field(min_length=1, max_length=2048)


class FileRenameIn(BaseModel):
    old_path: str = Field(min_length=1, max_length=2048)
    new_path: str = Field(min_length=1, max_length=2048)


class FileChmodIn(BaseModel):
    path: str = Field(min_length=1, max_length=2048)
    mode: str = Field(min_length=3, max_length=4)


class FileActionOut(BaseModel):
    path: str
    message: str


class ServerStats(BaseModel):
    hostname: str = ""
    os: str = ""
    kernel: str = ""
    uptime_seconds: float | None = None
    load: list[float] = []
    cpu_count: int | None = None
    mem_total: int | None = None
    mem_used: int | None = None
    disk_total: int | None = None
    disk_used: int | None = None


class ConnectionCheck(BaseModel):
    ok: bool
    message: str


class PastedImage(BaseModel):
    path: str


class BackupExportIn(BaseModel):
    pin: str = Field(min_length=1, max_length=128)


class BackupImportIn(BaseModel):
    pin: str = Field(min_length=1, max_length=128)
    backup: dict[str, Any]


class BackupStatsOut(BaseModel):
    servers_added: int
    servers_updated: int
    bookmarks_added: int
    snippets_added: int


class CFAccountIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    token: str = Field(min_length=10, max_length=255)


class CFAccountUpdateIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    token: str | None = Field(default=None, max_length=255)


class CFAccountVerifyIn(BaseModel):
    token: str = Field(min_length=10, max_length=255)


class CFAccountOut(BaseModel):
    id: int
    name: str
    token_masked: str
    account_id: str
    account_name: str
    created_at: str


class CFRouteIn(BaseModel):
    hostname: str = Field(min_length=3, max_length=255)
    service: str = Field(min_length=1, max_length=255)
    zone_id: str | None = Field(default=None, max_length=64)

