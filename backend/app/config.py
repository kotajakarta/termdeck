import os
from pathlib import Path

from platformdirs import user_data_dir

APP_NAME = "TermDeck"


def data_dir() -> Path:
    """Directory holding the database and encryption key."""
    override = os.environ.get("TERMDECK_DATA_DIR")
    path = Path(override) if override else Path(user_data_dir(APP_NAME, appauthor=False))
    path.mkdir(parents=True, exist_ok=True)
    return path


def initial_pin() -> str:
    """Optional PIN to initialize the vault non-interactively (e.g. CI or headless containers)."""
    return os.environ.get("TERMDECK_PIN", "")
