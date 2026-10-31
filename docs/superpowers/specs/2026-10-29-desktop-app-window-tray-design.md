# Desktop App Mode (jendela sendiri + Start Menu + tray) — Design

## Intent
Versi desktop (`./run.sh`) harus terasa seperti aplikasi Android: dibuka dari ikon, tampil di jendela
sendiri tanpa UI browser, sesi tetap hidup di latar belakang, dan bisa dikunci/dihentikan dari ikon
sistem (padanan notifikasi foreground service "Kunci & Putuskan" di Android). UI dan API sudah identik
(build frontend sama), jadi yang berubah hanya cara aplikasi dibuka dan dikelola.

## Goals
- WSL (lingkungan pengguna): `run.sh` membuka TermDeck di **jendela Chrome app-mode** (`--app=`), profil
  browser khusus `%LOCALAPPDATA%\TermDeck\browser`.
- Shortcut **Start Menu Windows** "TermDeck": menyalakan server bila belum jalan, lalu membuka jendela.
- **Ikon tray Windows**: Buka TermDeck (juga double-click), Kunci & Putuskan, Hentikan & keluar.
- Tray menjaga WSL tetap hidup (proses `wsl.exe` penjaga) selama tray berjalan — padanan foreground service.
- Linux native: `run.sh` membuka jendela `--app` bila Chromium/Chrome/Brave/Edge tersedia, selain itu tab
  browser biasa (perilaku lama).

## Non-goals
- Tray dan entri menu aplikasi untuk Linux native (run.sh tetap cara menjalankannya).
- Electron/Tauri/pywebview, installer MSI, autostart saat login Windows.

## Approaches
| | Pilihan | Trade-off |
|---|---|---|
| **A (dipilih)** | Chrome `--app` di Windows + shortcut Start Menu + tray PowerShell/WinForms | Rendering/clipboard/IME Chrome asli, tanpa dependency Linux baru; tray khusus Windows |
| B | pywebview + WebKitGTK lewat WSLg | Butuh `sudo dnf`, WSLg tanpa system tray, rendering lambat |
| C | Electron/Tauri | Berat, toolchain baru |

Pengguna memilih A.

## Desain
- `scripts/desktop_app.py` (stdlib saja):
  - `install` (WSL): salin `scripts/windows/termdeck-tray.ps1` ke `%LOCALAPPDATA%\TermDeck\`, tulis
    `config.json` (distro, repo, port), `termdeck.ico` (dibuat dari PNG favicon, format ICO berisi PNG),
    `launcher.vbs` (menjalankan PowerShell tersembunyi tanpa kedipan konsol), dan shortcut
    `TermDeck.lnk` di Start Menu.
  - `open --port N [--wait]`: WSL → install lalu jalankan launcher (tray membuka jendela). Linux →
    browser Chromium `--app`, fallback `webbrowser`.
- `termdeck-tray.ps1`: mutex satu instance (instance kedua hanya membuka/memfokuskan jendela), cek
  server (`GET /` berisi meta `termdeck-token`), bila mati jalankan `wsl.exe -d <distro> --cd <repo> --
  ./run.sh --no-browser` dan tunggu ≤180 dtk, fokuskan jendela yang ada atau buka Chrome/Edge `--app`.
- Backend: `POST /api/auth/lock-all` — hanya butuh token (tanpa sesi PIN), mengunci vault. Dipakai tray
  ("Kunci & Putuskan"). Token sudah dapat dibaca proses lokal mana pun lewat `GET /`, dan mengunci hanya
  menutup akses (tidak membuka data), jadi tidak menambah permukaan serangan.
- `run.sh`: default memakai mode aplikasi (`termdeck.py --no-browser` + `desktop_app.py open`). Opsi baru
  `--no-app` = perilaku lama (tab browser). `--no-browser` tetap berarti tidak membuka apa pun.

## Diserahkan ke builder
Teks menu, urutan pencarian browser, timeout.
