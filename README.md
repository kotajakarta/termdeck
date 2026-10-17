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
5. Menjalankan server dan membuka browser ke http://localhost:9095.

Untuk berhenti, tekan `Ctrl+C` di terminal.

| Opsi | Fungsi |
|---|---|
| `--no-browser` | Jalankan server tanpa membuka browser |
| `--port 9100` | Pakai port lain |
| `--setup` | Buat ulang virtualenv dan pasang ulang dependency |
| `--rebuild` | Paksa build ulang UI |

Contoh: `./run.sh --no-browser` atau `./run.sh --port 9100`.

Di WSL, browser Windows dibuka otomatis dan mengakses `localhost:9095` lewat localhost forwarding WSL.

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
