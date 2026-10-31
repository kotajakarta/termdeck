# Perbaikan Arrow Key Terminal & Fitur Export/Import Konfigurasi Terenkripsi PIN

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Memperbaiki bug popup paste/floating bubble saat klik tombol panah mobile di terminal, serta menambahkan fitur Export & Import konfigurasi terenkripsi PIN (servers, secrets, bookmarks/sidebar paths, snippets) dengan mode Merge antar perangkat Android.

**Architecture:**
1. **Terminal Arrow Fix:**
   - Di `TerminalView.tsx`, hilangkan paksaan `term.focus()` berulang saat eksekusi tombol panah navigasi (`Up`, `Down`, `Left`, `Right`) dari `sendSpecial`.
   - Di `MobileKeyBar.tsx`, tambahkan `onPointerDown={(e) => e.preventDefault()}` agar tap tombol bantu tidak memicu defocus/focus ulang yang memicu IME selection controller.
   - Di `MainActivity.kt`, override `startActionMode` dan `startActionModeForChild` di `WebView` untuk mengabaikan `ActionMode.TYPE_FLOATING` (bubble popup "Tempel" native Android yang mengambang dan tersangkut di atas terminal).
2. **Encrypted Config Export/Import (Backend & Android Server):**
   - Di `android/.../TermDeckServer.kt` dan `backend/app/api.py`, buat endpoint `/api/config/export` dan `/api/config/import`.
   - Menggunakan enkripsi standar industri: KDF `PBKDF2-HMAC-SHA256` (100.000 iterasi, salt 16-byte acak) + `AES-256-GCM` (IV 12-byte acak + authentication tag 16-byte).
   - Mode Import **Merge**: mencocokkan server eksisting via `(host, port, username)`. Jika belum ada, server dibuat baru beserta semua path bookmark-nya. Jika sudah ada, tambahkan path bookmark baru yang belum tercatat. Snippet baru juga di-merge.
3. **Frontend UI:**
   - Tambahkan API methods di `frontend/src/api.ts` untuk `exportConfig(pin)` dan `importConfig(pin, file)`.
   - Buat dialog `ConfigBackupDialog.tsx` (Export & Import) yang dapat dibuka dari `Sidebar.tsx` / `CommandPalette`.
   - Rebuild APK Android dan jalankan suite testing menyeluruh.

**Tech Stack:** Kotlin, Android WebView ActionMode, Python Cryptography, React/TypeScript, Web MediaStore / File Download.

---

### Task 1: Perbaiki Bug Arrow Key & Hilangkan Floating Paste Bubble di Terminal

**Files:**
- Modify: `frontend/src/components/TerminalView.tsx:471-477`
- Modify: `frontend/src/components/MobileKeyBar.tsx:143-178`
- Modify: `android/app/src/main/java/id/termdeck/app/MainActivity.kt:322-382`
- Test: `frontend/tests/mobileKeys.test.ts`

- [ ] Di `frontend/src/components/TerminalView.tsx`:
  - Ubah `sendSpecial`: jangan panggil `term.focus()` jika tombol adalah tombol panah navigasi (`Up`, `Down`, `Left`, `Right`) untuk menghindari pemicuan ulang text selection controller Android.
- [ ] Di `frontend/src/components/MobileKeyBar.tsx`:
  - Pasang `onPointerDown={(e) => e.preventDefault()}` pada semua tombol `KEY_BUTTONS` agar touch tidak mencuri fokus atau memicu focus bouncing di Android WebView.
- [ ] Di `android/app/src/main/java/id/termdeck/app/MainActivity.kt`:
  - Override `startActionMode(callback, type)` dan `startActionModeForChild(child, callback, type)` pada instans `WebView`.
  - Jika `type == ActionMode.TYPE_FLOATING`, return `null` untuk menekan floating paste bubble native yang mengganggu di terminal, sekaligus mempertahankan paste manual via tombol "Tempel" di toolbar.
- [ ] Jalankan unit test frontend untuk memastikan tidak ada regresi.

---

### Task 2: Implementasi Endpoint Export & Import Terenkripsi di Python Backend

**Files:**
- Modify: `backend/app/api.py`
- Modify: `backend/app/schemas.py`
- Create: `backend/app/backup.py`
- Test: `backend/tests/test_backup.py`

- [ ] Di `backend/app/backup.py`:
  - Buat fungsi `encrypt_config(payload_dict, pin: str) -> dict`:
    - Derive 256-bit key via `PBKDF2HMAC(SHA256, 100_000 iterations, 16-byte random salt)`.
    - Enkripsi dengan `AESGCM(key)` (12-byte nonce).
    - Return struktur JSON ber-envelope `{ "magic": "TERMDECK_BACKUP", "version": 1, "kdf": {...}, "iv": "...", "ciphertext": "..." }`.
  - Buat fungsi `decrypt_config(backup_data: dict, pin: str) -> dict`:
    - Validasi magic dan versi.
    - Derive key dengan salt & iterations dari envelope.
    - Decrypt ciphertext; jika pin salah, raise `InvalidPinError`.
  - Buat fungsi `merge_backup_into_store(store: Store, data: dict) -> dict`:
    - Merge servers, secrets, bookmarks, snippets ke database.
    - Return statistik merge: `{"servers_added": x, "servers_updated": y, "bookmarks_added": z, "snippets_added": w}`.
- [ ] Di `backend/app/api.py`:
  - Tambahkan rute `POST /api/config/export` (menerima body `{ "pin": "..." }`, mengembalikan backup terenkripsi).
  - Tambahkan rute `POST /api/config/import` (menerima body `{ "pin": "...", "backup": {...} }`, menjalankan merge dan mengembalikan statistik).
- [ ] Tulis test di `backend/tests/test_backup.py` (test encrypt, decrypt salah PIN, decrypt benar PIN, merge data server & bookmark).
- [ ] Jalankan `backend/.venv/bin/pytest backend/tests/test_backup.py` dan pastikan 100% lulus.

---

### Task 3: Porting Enkripsi & Merge Endpoint ke Android Ktor Server

**Files:**
- Create: `android/app/src/main/java/id/termdeck/app/data/BackupCrypto.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/server/TermDeckServer.kt`
- Test: `android/app/src/test/java/id/termdeck/app/BackupCryptoTest.kt`

- [ ] Di `android/.../BackupCrypto.kt`:
  - Implementasikan fungsi `encryptConfig(dataJson: String, pin: String): String` menggunakan `javax.crypto.Cipher` (`AES/GCM/NoPadding`) dan `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.
  - Implementasikan fungsi `decryptConfig(envelopeJson: String, pin: String): String` yang kompatibel 100% dengan envelope Python.
  - Implementasikan `mergeConfigIntoStore(store: StoreBackend, dataJson: String): MergeStats`.
- [ ] Di `android/.../TermDeckServer.kt`:
  - Daftarkan endpoint `post("/api/config/export")` dan `post("/api/config/import")`.
- [ ] Tulis unit test di `BackupCryptoTest.kt` yang menguji interoperabilitas enkripsi, dekripsi, dan merge ke InMemoryStore.
- [ ] Jalankan `./gradlew testDebugUnitTest` di folder `android/`.

---

### Task 4: Implementasi UI Frontend (Modal Backup, Export & Import)

**Files:**
- Modify: `frontend/src/api.ts`
- Create: `frontend/src/components/ConfigBackupDialog.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`
- Test: `frontend/tests/backup.test.ts`

- [ ] Di `frontend/src/api.ts`:
  - Tambahkan fungsi `exportConfig(pin: string)` dan `importConfig(pin: string, backup: object)`.
- [ ] Buat `ConfigBackupDialog.tsx`:
  - Tab 1: **Export Konfigurasi**: Input PIN (minimal 4 karakter), tombol "Unduh Backup (.tdk)". Otomatis trigger download file JSON/TDK terenkripsi.
  - Tab 2: **Import Konfigurasi**: Tombol pilih file `.tdk` / `.json`, input PIN pembuka, tombol "Import & Gabungkan".
  - Tampilkan pesan sukses dengan ringkasan data yang berhasil di-merge.
- [ ] Di `Sidebar.tsx`:
  - Tambahkan tombol menu / icon cadangan "Backup / Restore" di header samping pencarian server.
- [ ] Di `App.tsx`:
  - Hubungkan dialog backup dengan state dan trigger `reload()` server list setelah import sukses.
- [ ] Verifikasi tipe dengan `(cd frontend && npx tsc --noEmit)` dan run tests `(cd frontend && npm test)`.

---

### Task 5: Rebuild APK Android & Verifikasi Akhir

**Files:**
- Build output: `dist/termdeck-android.apk`

- [ ] Jalankan `./build-android.sh`.
- [ ] Verifikasi bahwa APK baru berhasil dibuat di `dist/termdeck-android.apk`.
- [ ] Verifikasi tes unit frontend, Android, dan backend lulus tanpa error.
