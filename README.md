# TermDeck

Terminal SSH multi-server ala Termius yang berjalan di browser, di **http://localhost:9095**. Backend-nya Python (FastAPI + asyncssh) dan UI-nya React + xterm.js. Berjalan di Linux, termasuk WSL.

## Fitur

- **Server via kredensial SSH**: login dengan password atau private key (+ passphrase). Ada tombol *Tes koneksi*.
- **Path tersimpan (proyek)**: server + folder. Sekali klik, tab baru terbuka, login otomatis, lalu `cd` ke folder tersebut. Bisa diberi *perintah setelah masuk* (mis. `source venv/bin/activate`).
- **Sidebar**: daftar server per grup beserta path-nya, plus info node live (OS, uptime, load, CPU, RAM, disk) yang diperbarui tiap 20 detik.
- **Multi-tab**: banyak terminal sekaligus. Tab tetap hidup saat kamu berpindah tab. Satu koneksi SSH dipakai bersama oleh semua tab ke server yang sama.
- **Multi terminal (split)**: tombol layout di kanan atas menampilkan 1 panel, 2 kolom, 2 baris, atau grid 2×2.
  - Klik sebuah panel untuk memilihnya. Panel terpilih diberi garis amber, dan tab atau path yang kamu buka akan tampil di panel itu.
  - Setiap panel punya header sendiri: warna server, path, tombol *Pindah path*, dan tombol tutup.
- **Lompat antar path**:
  - Klik path di sidebar akan **lompat** ke terminal yang sudah membuka path itu. Titik hijau menandai path yang sedang terbuka.
  - Ikon "tab baru" di baris path selalu membuka terminal baru.
  - *Pindah path* (`Alt+J`) memindahkan terminal yang sedang dipakai ke path tersimpan lain di server yang sama, tanpa koneksi baru.
- **Urutan server bisa diatur**: seret server ke atas atau ke bawah (pegangan ⋮⋮ muncul saat kursor di atas baris server), atau pilih server lalu tekan `Alt+↑` / `Alt+↓`. Pemindahan berlaku di dalam grupnya; untuk pindah grup, ubah *Grup* di form server. Urutan tersimpan di database.
- **Panel bisa dilipat**: detail server dan snippets bisa diminimize supaya daftar server dan path dapat ruang penuh. Status lipatnya diingat. Saat dilipat, detail server berhenti mengambil statistik.

Rekomendasi tambahan yang sudah ikut dibuat:

| Fitur | Kegunaan |
|---|---|
| Warna penanda server | Strip warna di tab dan panel terminal, supaya server production (merah) langsung terlihat sebelum mengetik perintah |
| `Alt+P` lompat ke proyek | Ketik sebagian nama server/proyek/path, lalu Enter |
| Jelajahi folder remote | Pilih path lewat SFTP saat menyimpan; otomatis fallback ke `ls` kalau SFTP dimatikan |
| Snippets | Perintah yang sering dipakai, dikirim ke tab aktif sekali klik |
| Login PIN + database terenkripsi | Seluruh data (server, path, kredensial, snippet) ada di SQLite terenkripsi AES-256 (SQLCipher), hanya bisa dibuka dengan PIN |
| Verifikasi host key | Trust-on-first-use. Kalau host key berubah, koneksi ditolak **sebelum** password dikirim |
| Sambung ulang | Tekan Enter di tab yang terputus |
| Konfirmasi tutup tab browser | Mencegah sesi hilang karena `Ctrl+W` |

## Menjalankan

Kebutuhan: Linux dengan Python 3.10+ dan Node.js 18+ (Node hanya untuk build UI).

```bash
./run.sh
```

Script ini mengurus semuanya:
1. Membuat virtualenv `backend/.venv`.
2. Memasang dependency Python, dan memasangnya ulang otomatis kalau `requirements.txt` berubah.
3. Build UI ke `frontend/dist`, dan build ulang otomatis kalau kode UI berubah.
4. Menghentikan proses yang masih memakai port 9095 (port ini khusus TermDeck), misalnya instance lama yang lupa ditutup. Proses diberi SIGTERM dulu agar database tertutup bersih, lalu SIGKILL kalau tidak berhenti dalam 5 detik.
5. Menjalankan server di latar belakang dan membuka TermDeck sebagai **jendela aplikasi** (lihat [Mode aplikasi desktop](#mode-aplikasi-desktop)). Server berjalan di session sendiri, jadi terminal boleh ditutup. Output server ditulis ke `termdeck-<port>.log` di folder data (default `~/.local/share/TermDeck`).

Untuk berhenti:

```bash
./stop.sh               # port 9095
./stop.sh --port 9100   # kalau dijalankan dengan --port
```

`stop.sh` hanya menghentikan proses TermDeck. Kalau port dipakai program lain, script menolak dan tidak menghentikan apa pun.

| Opsi | Fungsi |
|---|---|
| `--no-browser` | Jalankan server tanpa membuka apa pun |
| `--no-app` | Buka tab browser biasa, bukan jendela aplikasi |
| `--port 9100` | Pakai port lain |
| `--foreground` | Tetap terikat ke terminal (berhenti dengan `Ctrl+C` atau saat terminal ditutup) |
| `--setup` | Buat ulang virtualenv dan pasang ulang dependency |
| `--rebuild` | Paksa build ulang UI |

Contoh: `./run.sh --no-browser` atau `./run.sh --port 9100`.

Di WSL, aplikasi dibuka di sisi Windows dan mengakses `localhost:9095` lewat localhost forwarding WSL.

### Mode aplikasi desktop

Seperti aplikasi Android, versi desktop tampil di jendela sendiri tanpa address bar dan tab browser.

**WSL (Windows):** `./run.sh` memasang komponen berikut ke `%LOCALAPPDATA%\TermDeck` (dipasang ulang otomatis kalau ada perubahan):

- **Shortcut Start Menu "TermDeck"**: klik untuk membuka. Kalau server belum jalan, TermDeck menyalakannya sendiri (`./run.sh --no-browser` di distro WSL yang sama), jadi setelah setup pertama kamu tidak perlu membuka terminal lagi.
- **Jendela Chrome/Edge mode aplikasi** dengan profil khusus (`%LOCALAPPDATA%\TermDeck\browser`), terpisah dari browser biasa. Kalau jendelanya sudah terbuka, jendela itu yang difokuskan. Jendela dari server lama (token sudah berganti) otomatis diganti.
- **Ikon tray** (padanan notifikasi Android):
  - klik kiri atau *Buka TermDeck*: buka atau fokuskan jendela
  - *Kunci dan Putuskan*: kunci vault dan putuskan semua SSH (`POST /api/auth/lock-all`). Jendela akan meminta PIN lagi.
  - *Hentikan TermDeck dan keluar*: tutup jendela, jalankan `./stop.sh`, lalu keluar dari tray
- **Sesi tetap hidup**: selama tray berjalan, WSL dijaga tetap hidup supaya koneksi di latar belakang tidak terputus saat jendela ditutup.

Komponen ini butuh Chrome atau Edge (kalau keduanya tidak ada, browser default yang dipakai). Untuk menghapusnya, hapus folder `%LOCALAPPDATA%\TermDeck` dan `TermDeck.lnk` di `%APPDATA%\Microsoft\Windows\Start Menu\Programs`.

**Linux desktop (non-WSL):** `./run.sh` membuka jendela `--app` kalau Chromium, Chrome, Brave, atau Edge terpasang. Kalau tidak ada, yang dibuka tab browser default. Versi ini tidak memakai tray atau shortcut menu.

## Aplikasi Android (Standalone APK)

TermDeck juga dapat di-build menjadi aplikasi Android mandiri (`id.termdeck.app`) yang berjalan sepenuhnya di HP/tablet tanpa memerlukan server backend eksternal:
- Membawa **embedded Ktor HTTP/WebSocket server** di `127.0.0.1` dengan database terenkripsi **SQLCipher (AES-256)** + **scrypt** dan klien SSH native (**SSHJ** + **BouncyCastle** + **EdDSA**).
- Dilengkapi **drawer sidebar responsif** dan **Mobile Terminal Key Bar** (`Esc`, `Tab`, sticky `Ctrl` & `Alt`, tombol panah `↑↓←→` yang mendukung `DECCKM`, `Home`, `End`, `PgUp`, `PgDn`, simbol cepat, lompat path `Alt+P`/`Alt+J`, tempel clipboard, dan unggah gambar langsung dari galeri HP ke server).

Untuk mem-build file APK:

```bash
./build-android.sh
```

Script ini otomatis menyiapkan frontend, mengunduh JDK 17 & Android SDK lokal jika belum tersedia, menjalankan unit test, dan menghasilkan file instalasi di `dist/termdeck-android.apk`.

## Aplikasi Android (Mandiri)

TermDeck juga bisa dibangun menjadi aplikasi Android mandiri (`dist/termdeck-android.apk`) yang melakukan koneksi SSH langsung dari HP tanpa butuh server backend eksternal:

```bash
./build-android.sh
```

Script `build-android.sh` mengurus seluruh proses secara otomatis:
1. Memastikan OpenJDK 17, Gradle, dan Android SDK Platform 34 tersedia (diunduh otomatis ke `~/.local/share/termdeck-android-sdk` bila belum terpasang).
2. Membangun UI `frontend/dist` dan menyematkannya ke aset aplikasi Android.
3. Menjalankan unit test JUnit Android (`testDebugUnitTest`).
4. Menghasilkan file APK siap pasang di `dist/termdeck-android.apk`.

Fitur khusus versi Android:
- **Koneksi SSH & SFTP Native Mandiri**: memakai SSHJ + BouncyCastle + EdDSA langsung dari perangkat Android (mendukung password, private key RSA/Ed25519/ECDSA + passphrase, verifikasi *Host Key* TOFU, *connection pooling*, PTY `xterm-256color`, jelajah folder SFTP + *fallback* `ls`, tempel gambar dari galeri HP ke server, dan statistik *live*).
- **Vault SQLCipher AES-256**: database terenkripsi penuh dengan kunci turunan `scrypt` dari PIN (PIN awal `7821724`) di penyimpanan internal privat aplikasi.
- **Layar Sentuh & Bilah Tombol Terminal**: sidebar otomatis menjadi *drawer* buka-tutup di layar HP, dilengkapi bilah tombol terminal (`Ctrl`, `Alt` *sticky*, `Esc`, `Tab`, panah `↑↓←→`, `Home`, `End`, `PgUp`, `PgDn`, `-`, `/`, `|`, `~`, `^C`, tombol cepat `Tempel`, `Gambar`, `Path`, dan `Proyek`).
- **Sesi Tetap Hidup**: layanan latar depan (*foreground service*) menjaga koneksi terminal tetap hidup saat Anda berpindah aplikasi sebentar (misalnya untuk menyalin OTP atau password).

## Login PIN

Saat dibuka, TermDeck meminta PIN. **PIN awal: `7821724`**. Segera ganti lewat ikon kunci (🔑) di sidebar.

- PIN tidak disimpan di mana pun. Kunci database diturunkan dari PIN dengan scrypt, sehingga PIN yang benar adalah PIN yang bisa membuka database.
- Setelah 5 PIN salah, login diblokir 30 detik. Blokirnya berlipat ganda setiap kali salah lagi, maksimal 15 menit.
- Ikon gembok di sidebar **mengunci** TermDeck: semua terminal ditutup dan database ditutup sampai PIN dimasukkan lagi.
- Sesi berlaku per tab browser. Reload tetap masuk, tapi tab baru atau browser yang ditutup lalu dibuka lagi akan meminta PIN.
- Server baru (tanpa database) bisa diberi PIN awal lain lewat env `TERMDECK_PIN` saat start pertama.
- **Lupa PIN berarti data tidak bisa dibuka.** Satu-satunya jalan adalah menghapus `termdeck.vault` dan mulai dari awal.

## Shortcut

Browser memakai banyak shortcut `Ctrl` (`Ctrl+W`, `Ctrl+T`, `Ctrl+Tab`), jadi shortcut aplikasi memakai `Alt`.

| Tombol | Aksi |
|---|---|
| `Alt+P` (atau `Ctrl+Shift+P`) | Lompat ke proyek/server |
| `Alt+J` | Pindah path di terminal yang sedang dipakai |
| `Alt+Shift+→` / `Alt+Shift+←` | Tab berikutnya / sebelumnya |
| `Alt+Shift+↓` / `Alt+Shift+↑` | Panel berikutnya / sebelumnya (mode split) |
| `Alt+↑` / `Alt+↓` (fokus di nama server) | Pindah posisi server di sidebar |
| `Alt+T` | Duplikat tab aktif |
| `Alt+W` | Tutup tab |
| `Ctrl+C` dengan teks terseleksi | Salin (tanpa seleksi, `Ctrl+C` tetap mengirim interrupt) |
| `Ctrl+V` | Tempel |
| Klik tengah di tab | Tutup tab |

`Ctrl+W` (hapus kata di bash) tetap akan menutup tab browser, karena browser tidak mengizinkan halaman mengambil alih kombinasi ini. Selama ada sesi terbuka, TermDeck akan meminta konfirmasi dulu.

## Data & keamanan

- Lokasi data: `~/.local/share/TermDeck` (bisa diganti dengan env `TERMDECK_DATA_DIR`).
- File data:

| File | Isi |
|---|---|
| `termdeck.vault` (+ `-wal`, `-shm`) | Database SQLite yang dienkripsi penuh dengan SQLCipher (AES-256), mode WAL agar cepat |
| `vault.key` | Salt acak per instalasi untuk derivasi kunci. Tanpa file ini, database tidak bisa dibuka walaupun PIN-nya benar |

- Backup `termdeck.vault*` dan `vault.key` bersama-sama.
- **Migrasi dari versi lama:** kalau ada `termdeck.db` (format lama yang belum terenkripsi), isinya dipindah otomatis ke `termdeck.vault` saat login PIN pertama. File lama diganti nama menjadi `termdeck.db.migrated`. Setelah memastikan datanya pindah, hapus `termdeck.db.migrated` dan `secret.key`.
- Server hanya listen di `127.0.0.1`, jadi tidak bisa diakses dari komputer lain di jaringan.
- Perlindungan dari website lain yang terbuka di browser yang sama:
  - Token halaman wajib untuk login.
  - Sesi PIN wajib untuk semua data dan terminal.
  - Header `Host` hanya `localhost` atau `127.0.0.1`, untuk menangkal DNS rebinding.
  - WebSocket menolak `Origin` dari situs lain.
- API tidak pernah mengembalikan password atau private key ke UI.
- PIN 7 digit tetap pendek. Dengan scrypt (±150 ms per percobaan), brute force offline butuh berhari-hari, tapi hanya kalau penyerang sudah memegang `termdeck.vault` **dan** `vault.key`. Pakai PIN lebih panjang (sampai 12 digit) untuk keamanan lebih.

## Arsitektur

```
browser ── http://localhost:9095 ── termdeck.py (uvicorn, 127.0.0.1:9095) ── FastAPI
                                                  ├─ /                 index.html + token sesi
                                                  ├─ /assets/*         build React
                                                  ├─ /api/*            REST (servers, bookmarks, snippets, stats, ls)
                                                  └─ /ws/terminal      WebSocket ⇄ PTY asyncssh
```

```
termdeck.py           entry point (port, buka browser)
scripts/setup_check.py  cek apakah dependency / build UI perlu diperbarui (dipakai run.sh)
scripts/common.sh       helper bersama run.sh dan stop.sh (port, folder data, hentikan proses)
scripts/desktop_app.py  mode aplikasi desktop: jendela --app, shortcut Start Menu + tray (WSL)
scripts/windows/termdeck-tray.ps1  ikon tray Windows (buka, kunci, hentikan)
backend/app/
  config.py   lokasi data
  crypto.py   dekripsi format lama (untuk migrasi)
  db.py       SQLCipher store (database terenkripsi)
  vault.py    PIN → kunci database, sesi, batas percobaan, ganti PIN, migrasi
  ssh.py      pool koneksi, host key TOFU, stats, listing folder
  api.py      REST + WebSocket terminal
  main.py     app factory, cek Host/Origin, penyisipan token
frontend/src/
  App.tsx, api.ts, types.ts, components/*
  layout.ts   aturan panel split (tab mana tampil di panel mana)
  shell.ts    perintah cd untuk pindah path (sama persis dengan backend)
  order.ts    aturan memindah posisi server (di dalam grup)
```

## Development

```bash
# test backend
backend/.venv/bin/pip install -r backend/requirements-dev.txt
cd backend && .venv/bin/python -m pytest

# test frontend (logika layout split & quoting path)
cd frontend && npm test

# UI dengan hot reload
backend/.venv/bin/python termdeck.py --no-browser --token dev
cd frontend && npm run dev     # buka http://localhost:5173/?token=dev
```

## Ide lanjutan

- Jump host / bastion (ProxyJump) dan port forwarding
- Split pane dan broadcast input ke beberapa tab
- SFTP file manager (upload/download)
- Kunci otomatis setelah tidak aktif (dengan pengecualian terminal yang sedang menjalankan proses)
- Install sebagai PWA supaya tampil di jendela sendiri
