from typing import Literal

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


class DirListing(BaseModel):
    path: str
    entries: list[DirEntry]


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
