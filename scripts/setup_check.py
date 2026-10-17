"""Setup checks used by run.sh.

    python scripts/setup_check.py deps [--write]   exit 1 if Python deps must be (re)installed
    python scripts/setup_check.py npm              exit 1 if `npm install` is needed
    python scripts/setup_check.py ui               exit 1 if the frontend must be (re)built

Run it with the virtualenv's interpreter: the deps stamp lives inside that virtualenv.
"""

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIREMENTS = ROOT / "backend" / "requirements.txt"
STAMP = Path(sys.prefix) / ".requirements.sha256"
FRONTEND = ROOT / "frontend"
UI_INPUTS = ["src", "index.html", "package.json", "package-lock.json", "vite.config.ts", "tsconfig.json"]


def requirements_hash() -> str:
    return hashlib.sha256(REQUIREMENTS.read_bytes()).hexdigest()


def deps_ok(write: bool) -> bool:
    current = requirements_hash()
    if write:
        STAMP.write_text(current, encoding="ascii")
        return True
    try:
        return STAMP.read_text(encoding="ascii").strip() == current
    except FileNotFoundError:
        return False


def newest_mtime(paths: list[Path]) -> float:
    newest = 0.0
    for path in paths:
        if path.is_dir():
            for child in path.rglob("*"):
                if child.is_file():
                    newest = max(newest, child.stat().st_mtime)
        elif path.is_file():
            newest = max(newest, path.stat().st_mtime)
    return newest


def npm_ok() -> bool:
    # npm writes node_modules/.package-lock.json after every successful install.
    installed = FRONTEND / "node_modules" / ".package-lock.json"
    if not installed.is_file():
        return False
    return newest_mtime([FRONTEND / "package.json", FRONTEND / "package-lock.json"]) <= installed.stat().st_mtime


def ui_ok() -> bool:
    built = FRONTEND / "dist" / "index.html"
    if not built.is_file():
        return False
    return newest_mtime([FRONTEND / name for name in UI_INPUTS]) <= built.stat().st_mtime


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    command = argv[0]
    if command == "deps":
        return 0 if deps_ok(write="--write" in argv[1:]) else 1
    if command == "npm":
        return 0 if npm_ok() else 1
    if command == "ui":
        return 0 if ui_ok() else 1
    print(f"Perintah tidak dikenal: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
