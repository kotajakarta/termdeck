import pytest
from pathlib import Path
from app.updater import DesktopUpdater

def test_get_current_version_returns_sha():
    updater = DesktopUpdater(repo_dir=Path(__file__).parents[2])
    version = updater.get_current_version()
    assert isinstance(version, str)
    assert len(version) >= 7

@pytest.mark.asyncio
async def test_check_update_has_update(monkeypatch):
    updater = DesktopUpdater(repo_dir=Path(__file__).parents[2])
    monkeypatch.setattr(updater, "_fetch_remote_commit", lambda: ("abcdef1", "feat: new cool feature"))
    monkeypatch.setattr(updater, "get_current_version", lambda: "1234567")
    res = await updater.check_update()
    assert res["platform"] == "desktop"
    assert res["current_version"] == "1234567"
    assert res["latest_version"] == "abcdef1"
    assert res["has_update"] is True
    assert res["release_title"] == "feat: new cool feature"
    assert res["download_url"] is None

@pytest.mark.asyncio
async def test_check_update_up_to_date(monkeypatch):
    updater = DesktopUpdater(repo_dir=Path(__file__).parents[2])
    monkeypatch.setattr(updater, "_fetch_remote_commit", lambda: ("1234567", "fix: already current"))
    monkeypatch.setattr(updater, "get_current_version", lambda: "1234567")
    res = await updater.check_update()
    assert res["platform"] == "desktop"
    assert res["has_update"] is False

@pytest.mark.asyncio
async def test_apply_update_success(monkeypatch):
    updater = DesktopUpdater(repo_dir=Path(__file__).parents[2])
    monkeypatch.setattr(updater, "_run_git_pull", lambda: (True, "Already up to date."))
    monkeypatch.setattr(updater, "_run_frontend_build", lambda: (True, "Build succeeded"))
    
    res = await updater.apply_update()
    assert res["started"] is True
    assert "selesai" in res["message"].lower() or "berhasil" in res["message"].lower()

    progress = updater.get_progress()
    assert progress["state"] == "completed"
    assert progress["progress_percent"] == 100

@pytest.mark.asyncio
async def test_apply_update_git_failure(monkeypatch):
    updater = DesktopUpdater(repo_dir=Path(__file__).parents[2])
    monkeypatch.setattr(updater, "_run_git_pull", lambda: (False, "fatal: Not possible to fast-forward"))
    
    with pytest.raises(RuntimeError, match="fast-forward"):
        await updater.apply_update()

    progress = updater.get_progress()
    assert progress["state"] == "error"
