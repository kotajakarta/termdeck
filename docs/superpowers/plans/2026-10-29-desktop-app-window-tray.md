# Desktop App Mode Implementation Plan

**Spec:** [`2026-10-29-desktop-app-window-tray-design.md`](../specs/2026-10-29-desktop-app-window-tray-design.md)

- [ ] 1. Test gagal `test_lock_all_with_token_only` (backend/tests/test_api.py) → tambah `POST /api/auth/lock-all` di `backend/app/api.py` → pytest hijau.
- [ ] 2. Test gagal `backend/tests/test_desktop_app.py` (ICO builder, render VBS/config, deteksi browser Linux, argumen `--app`) → tulis `scripts/desktop_app.py` → pytest hijau.
- [ ] 3. Tulis `scripts/windows/termdeck-tray.ps1`; verifikasi parse dengan `powershell.exe` Parser API.
- [ ] 4. Ubah `run.sh` (`--no-app`, mode aplikasi default, foreground + background); `bash -n run.sh`.
- [ ] 5. Jalankan `desktop_app.py install` di WSL, cek file & shortcut tercipta.
- [ ] 6. README: dokumentasi mode aplikasi desktop.
- [ ] 7. Verifikasi: pytest, py_compile, bash -n, git diff.
