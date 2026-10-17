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
    """PIN that creates the encrypted database on first start (change it in the app afterwards)."""
    return os.environ.get("TERMDECK_PIN", "7821724")
