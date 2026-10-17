import os
from pathlib import Path

from cryptography.fernet import Fernet


class SecretBox:
    """Fernet encryption used by the pre-SQLCipher format; kept to migrate old data."""

    def __init__(self, key_path: Path) -> None:
        self._fernet = Fernet(self._load_or_create_key(key_path))

    @staticmethod
    def _load_or_create_key(key_path: Path) -> bytes:
        if key_path.exists():
            return key_path.read_bytes().strip()
        key = Fernet.generate_key()
        key_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(key)
        return key

    def encrypt(self, value: str | None) -> str | None:
        if value is None:
            return None
        return self._fernet.encrypt(value.encode("utf-8")).decode("ascii")

    def decrypt(self, token: str | None) -> str | None:
        if token is None:
            return None
        return self._fernet.decrypt(token.encode("ascii")).decode("utf-8")
