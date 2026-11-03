import asyncio
import json
import logging
import subprocess
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

GITHUB_REPO = "kotajakarta/termdeck"
GITHUB_COMMITS_URL = f"https://api.github.com/repos/{GITHUB_REPO}/commits/master"


class DesktopUpdater:
    def __init__(self, repo_dir: Path | None = None) -> None:
        if repo_dir is None:
            # backend/app/updater.py -> backend/app -> backend -> repo root
            self.repo_dir = Path(__file__).resolve().parents[2]
        else:
            self.repo_dir = repo_dir

        self._state: str = "idle"  # idle | downloading | ready | completed | error
        self._progress_percent: int = 0
        self._message: str = ""

    def get_current_version(self) -> str:
        try:
            res = subprocess.run(
                ["git", "rev-parse", "--short", "HEAD"],
                cwd=self.repo_dir,
                capture_output=True,
                text=True,
                check=True,
            )
            return res.stdout.strip()
        except Exception as exc:
            logger.warning("Gagal membaca versi git lokal: %s", exc)
            return "unknown"

    def _fetch_remote_commit(self) -> tuple[str, str]:
        """Returns (commit_hash, commit_message)."""
        # Try GitHub API first for commit message
        try:
            req = urllib.request.Request(
                GITHUB_COMMITS_URL,
                headers={
                    "User-Agent": "TermDeck-Updater",
                    "Accept": "application/vnd.github.v3+json",
                },
            )
            with urllib.request.urlopen(req, timeout=5) as response:
                if response.status == 200:
                    data = json.loads(response.read().decode("utf-8"))
                    sha = data.get("sha", "")[:7]
                    msg = data.get("commit", {}).get("message", "").split("\n")[0]
                    return sha, msg
        except Exception as exc:
            logger.debug("GitHub API fetch commit failed: %s; falling back to git ls-remote", exc)

        # Fallback to git ls-remote
        try:
            res = subprocess.run(
                ["git", "ls-remote", "origin", "master"],
                cwd=self.repo_dir,
                capture_output=True,
                text=True,
                timeout=10,
                check=True,
            )
            line = res.stdout.strip().split()
            if line:
                sha = line[0][:7]
                return sha, f"Commit {sha} di origin/master"
        except Exception as exc:
            logger.warning("git ls-remote failed: %s", exc)

        cur = self.get_current_version()
        return cur, "Tidak dapat memeriksa commit remote"

    async def check_update(self) -> dict[str, Any]:
        cur_version = self.get_current_version()
        remote_sha, commit_msg = await asyncio.to_thread(self._fetch_remote_commit)
        has_update = (remote_sha != cur_version) and (remote_sha != "unknown")

        return {
            "platform": "desktop",
            "current_version": cur_version,
            "latest_version": remote_sha,
            "has_update": has_update,
            "release_title": commit_msg,
            "release_notes": f"Pembaruan terbaru dari branch master ({remote_sha}):\n{commit_msg}",
            "download_url": None,
        }

    def _run_git_pull(self) -> tuple[bool, str]:
        try:
            res = subprocess.run(
                ["git", "pull", "--ff-only", "origin", "master"],
                cwd=self.repo_dir,
                capture_output=True,
                text=True,
                check=True,
            )
            return True, res.stdout.strip()
        except subprocess.CalledProcessError as exc:
            err = (exc.stderr or exc.stdout or str(exc)).strip()
            return False, err
        except Exception as exc:
            return False, str(exc)

    def _run_frontend_build(self) -> tuple[bool, str]:
        frontend_dir = self.repo_dir / "frontend"
        if not (frontend_dir / "package.json").exists():
            return True, "Frontend source not found, skipping build"

        try:
            res = subprocess.run(
                ["npm", "run", "build"],
                cwd=frontend_dir,
                capture_output=True,
                text=True,
                check=True,
            )
            return True, res.stdout.strip()
        except subprocess.CalledProcessError as exc:
            err = (exc.stderr or exc.stdout or str(exc)).strip()
            return False, err
        except Exception as exc:
            return False, str(exc)

    def get_progress(self) -> dict[str, Any]:
        return {
            "state": self._state,
            "progress_percent": self._progress_percent,
            "downloaded_bytes": 0,
            "total_bytes": 0,
            "message": self._message,
        }

    async def apply_update(self) -> dict[str, Any]:
        self._state = "downloading"
        self._progress_percent = 20
        self._message = "Menarik pembaruan dari git origin/master..."

        ok, pull_msg = await asyncio.to_thread(self._run_git_pull)
        if not ok:
            self._state = "error"
            self._message = f"Gagal git pull: {pull_msg}"
            raise RuntimeError(self._message)

        self._progress_percent = 60
        self._message = "Mengompilasi asset frontend (npm run build)..."

        ok_build, build_msg = await asyncio.to_thread(self._run_frontend_build)
        if not ok_build:
            self._state = "error"
            self._message = f"Gagal build frontend: {build_msg}"
            raise RuntimeError(self._message)

        self._state = "completed"
        self._progress_percent = 100
        self._message = "Pembaruan berhasil diterapkan. Silakan restart TermDeck (./run.sh) untuk memuat perubahan backend."

        return {
            "started": True,
            "message": self._message,
        }
