# TermDeck Android App Specification

## Overview
Mengubah **TermDeck** menjadi aplikasi Android mandiri (*standalone*) yang ringan dan aman tanpa memerlukan server backend eksternal. Aplikasi ini membawa 100% fitur TermDeck desktop (manajemen grup server, kredensial password & private key, *path* tersimpan + perintah awal, *multi-tab*, *split layout* & *Windows Snap Layouts*, lompat *path* `Alt+P`/`Alt+J`, *snippets*, jelajah folder remote via SFTP + *fallback* `ls`, unggah/tempel gambar ke server, statistik *live* server, verifikasi *Host Key* TOFU, dan *vault* terenkripsi PIN) ke perangkat Android, dilengkapi adaptasi layar sentuh berupa *drawer sidebar* dan bilah tombol khusus terminal (*Mobile Key Bar*).

---

## 1. Arsitektur Sistem

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        APK Android (id.termdeck.app)                    │
│                                                                         │
│  ┌───────────────────────────────┐    HTTP / WebSocket (127.0.0.1:PORT) │
│  │   Android WebView (UI Layer)  │ ◄──────────────────────────────────► │
│  │  • React 18 + xterm.js        │    Header X-Token & X-Session        │
│  │  • Mobile Drawer Sidebar      │                                      │
│  │  • Touch Terminal Key Bar     │  ┌─────────────────────────────────┐ │
│  └───────────────────────────────┘  │   Embedded Ktor Backend (CIO)   │ │
│                                     │  • Vault (scrypt + SQLCipher)   │ │
│                                     │  • Store (SQLite AES-256)       │ │
│                                     │  • ConnectionPool (SSHJ)        │ │
│                                     │  • PTY, SFTP, Shell ls, Stats   │ │
│                                     └────────────────┬────────────────┘ │
└──────────────────────────────────────────────────────┼──────────────────┘
                                                       │ SSH / SFTP (Port 22+)
                                                       ▼
                                            Remote Linux/POSIX Servers
```

### 1.1 Mengapa Arsitektur Ini?
1. **Ringan & Cepat:** Tanpa membundel runtime Python ke dalam APK, ukuran aplikasi tetap ramping, waktu buka instan, dan penggunaan RAM/baterai minimal.
2. **Kompatibilitas 100% dengan UI Eksisting:** Seluruh kode `frontend/` dipakai bersama antara versi desktop dan Android tanpa duplikasi logika.
3. **Keamanan Setara Desktop:** Server lokal hanya mendengarkan di `127.0.0.1` dengan token sesi acak 256-bit yang disuntikkan ke `<meta name="termdeck-token">`, memvalidasi header `Host`/`Origin`/`X-Token`/`X-Session`, dan menyimpan seluruh data di dalam database SQLCipher (AES-256) di direktori internal privat Android (`context.filesDir`).

---

## 2. Adaptasi UI Layar Sentuh (`frontend/`)

### 2.1 Helper Tombol Terminal Sentuh (`frontend/src/mobileKeys.ts`)
- Mengelola status modifier lengket (*sticky modifiers*): `ctrl` dan `alt`.
- `applyModifiers(key: string, mods: { ctrl: boolean; alt: boolean }): { sequence: string; resetMods: boolean }`:
  - Mengubah huruf dengan `ctrl: true` menjadi karakter kontrol ASCII (`a`–`z` → `\x01`–`\x1a`, `[` → `\x1b`, `\` → `\x1c`, `]` → `\x1d`, `_` → `\x1f`, `/` → `\x1f`).
  - Mengubah input dengan `alt: true` menjadi urutan Escape (`\x1b` + input).
- `specialKeySequence(key: SpecialKey, appCursor?: boolean): string`:
  - Mendukung: `Esc` (`\x1b`), `Tab` (`\t`), `Up`, `Down`, `Left`, `Right` (memperhatikan mode `applicationCursorKeysMode` xterm), `Home`, `End`, `PgUp`, `PgDn`, `CtrlC` (`\x03`), `CtrlD` (`\x04`), `CtrlZ` (`\x1a`), `CtrlL` (`\x0c`), `-`, `/`, `|`, `~`.

### 2.2 Komponen `MobileKeyBar.tsx`
- Ditampilkan di bagian bawah workspace saat ada tab terminal yang terbuka.
- Menyediakan tombol sentuh cepat:
  - Tombol modifier: `Ctrl`, `Alt` (dengan indikator aktif).
  - Tombol navigasi & kontrol: `Esc`, `Tab`, `↑`, `↓`, `←`, `→`, `Home`, `End`, `PgUp`, `PgDn`.
  - Simbol yang sulit dijangkau di keyboard HP: `-`, `/`, `|`, `~`, `^C`.
  - Tombol aksi cepat: `Alt+P` (Lompat proyek), `Alt+J` (Pindah path), `Tempel` (teks clipboard), dan `Gambar` (membuka pemilih file gambar di HP untuk diunggah ke server dan ditempelkan path-nya ke terminal).

### 2.3 Sidebar Drawer Responsif (`App.tsx`, `TabBar.tsx`, `styles.css`)
- Pada layar `<= 760px`:
  - Sidebar menjadi panel *drawer* geser di sisi kiri dengan latar redup (`.sidebar-backdrop`).
  - Tombol menu (`☰`) muncul di sisi kiri `TabBar` untuk membuka/menutup sidebar.
  - Memilih server/path untuk membuka terminal otomatis menutup *drawer* agar terminal langsung tampil penuh.
  - Tombol aksi baris (`.row-actions` pada server, path, dan snippet) selalu tampil pada perangkat sentuh (`@media (hover: none), (max-width: 760px)`).

---

## 3. Spesifikasi Backend Native Android (`android/`)

### 3.1 Struktur Paket (`id.termdeck.app`)
- `MainActivity.kt`: Menginisialisasi `TermDeckServer`, mempertahankan sesi melalui `TermDeckService` saat ada koneksi aktif, mengonfigurasi `WebView` (termasuk `onShowFileChooser` untuk pilih private key & gambar, serta clipboard), dan menangani tombol *Back* Android (menutup drawer/modal terlebih dahulu sebelum keluar).
- `TermDeckService.kt`: *Foreground service* ringan agar koneksi SSH tidak langsung diputus oleh OS Android saat pengguna berpindah aplikasi sebentar (mis. menyalin OTP/password).
- `server/TermDeckServer.kt`: Ktor CIO HTTP & WebSocket server di `127.0.0.1` (port dinamis), melayani file statis dari `assets/webapp/` (dengan injeksi token sesi ke `index.html`) serta seluruh endpoint `/api/*` dan `/ws/terminal`.
- `data/Models.kt`: Model serialisasi JSON identik dengan `backend/app/schemas.py`.
- `data/Vault.kt`: Manajemen PIN (`^\d{4,12}$`, PIN awal `7821724`), derivasi kunci `scrypt` (`N=65536, r=8, p=1, dkLen=32`) menggunakan salt 32-byte di `vault.key`, pembatasan percobaan gagal (5 percobaan gratis, blokir awal 30 detik berlipat ganda hingga 900 detik), pembuatan token sesi, penguncian (`lock`), dan ganti PIN (`rekey`).
- `data/Store.kt`: Penyimpanan SQLCipher (`termdeck.vault`) dengan tabel `servers`, `bookmarks`, dan `snippets`, mendukung CRUD lengkap, pengurutan server (`reorderServers`), dan penyimpanan *Host Key* TOFU.
- `ssh/SshOperations.kt` & `ssh/ConnectionPool.kt`:
  - Implementasi murni pembuatan perintah `buildCdCommand`, *quoting* path POSIX `quoteRemotePath`, deteksi gambar `sniffImageType`, penamaan file tempel `pasteImageName`, dan parser statistik `parseStats` (identik dengan `backend/app/ssh.py`).
  - Koneksi SSH via SSHJ + BouncyCastle + EdDSA: mendukung autentikasi `password` dan `key` (beserta `passphrase`), *Host Key* TOFU sebelum autentikasi, *shared connection pool* per server dengan *idle timeout* 90 detik, sesi PTY `xterm-256color`, SFTP `listdir` + fallback shell `ls`, unggah gambar tempel ke `.cache/termdeck/paste`, dan pengambilan statistik server.

---

## 4. Rencana Verifikasi & Build (`build-android.sh`)

1. **Unit Tests Frontend:** `npm test --prefix frontend` (menguji `layout.test.ts`, `shell.test.ts`, `order.test.ts`, `lineEdit.test.ts`, `paste.test.ts`, dan `mobileKeys.test.ts`).
2. **Typecheck & Build Frontend:** `npx tsc --noEmit --project frontend/tsconfig.json` dan `npm run build --prefix frontend`.
3. **Unit Tests Android (JUnit):** `./gradlew testDebugUnitTest` di dalam `android/` (menguji `SshOperationsTest`, `VaultThrottleTest`, dan logika format/protokol).
4. **Build APK:** `./build-android.sh` melakukan kompilasi penuh dan menghasilkan `dist/termdeck-android.apk`.
