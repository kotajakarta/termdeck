# Cloudflare Account Edit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan tombol Edit pada setiap akun Cloudflare di modal "Kelola Akun & Token API Cloudflare", memungkinkan pengguna memperbarui nama/label akun dan opsional mengganti token Cloudflare API.

**Architecture:** 
1. Database layer menambahkan method `update_cf_account` di `backend/app/db.py` untuk mengupdate `name`, dan jika token baru diberikan, update `token`, `account_id`, dan `account_name`.
2. Backend API menambahkan schema `CFAccountUpdateIn` di `backend/app/schemas.py` dan endpoint `PUT /api/cf/accounts/{account_id}` di `backend/app/cloudflare_api.py`.
3. Frontend menambahkan fungsi `cfUpdateAccount` di `frontend/src/api.ts` dan integrasi UI form edit di `frontend/src/components/CloudflareAccountModal.tsx`.

**Tech Stack:** Python 3.12, FastAPI, SQLite/SQLCipher, React 18, TypeScript, Vite.

**Spec:** Rekomendasi brainstorming: edit inline di dalam modal dengan form yang menampilkan nama akun, token baru opsional, tombol tes koneksi, batal, dan simpan.

---

### Task 1: Backend Schema & Database Layer
- [x] Tulis unit test untuk `store.update_cf_account` di `backend/tests/test_cf_db.py`.
- [x] Jalankan pytest untuk memverifikasi test gagal (TDD).
- [x] Definisikan `CFAccountUpdateIn` di `backend/app/schemas.py` (`name: str`, `token: str | None = None`).
- [x] Implementasikan `update_cf_account` di `backend/app/db.py`.
- [x] Jalankan pytest untuk memverifikasi test lulus.

### Task 2: Backend API Endpoint PUT /api/cf/accounts/{account_id}
- [x] Tulis test di `backend/tests/test_cloudflare_api.py` untuk:
  - Mengupdate nama akun saja (tanpa token).
  - Mengupdate nama akun dan token (memvalidasi token baru ke Cloudflare mock API).
  - Mengembalikan 404 jika account_id tidak ditemukan.
- [x] Jalankan pytest untuk memverifikasi test gagal.
- [x] Implementasikan endpoint `PUT /api/cf/accounts/{account_id}` di `backend/app/cloudflare_api.py`.
- [x] Jalankan pytest untuk memverifikasi seluruh test backend lulus.

### Task 3: Frontend API Client & CloudflareAccountModal UI
- [x] Tambahkan `cfUpdateAccount(accountId: number, body: { name: string; token?: string })` di `frontend/src/api.ts`.
- [x] Perbarui `frontend/src/components/CloudflareAccountModal.tsx`:
  - Tambahkan tombol "Edit" dengan `Icon name="edit"` pada tiap kartu akun (`cf-acc-card-actions`).
  - Tambahkan state `editingAccount: CFAccount | null`.
  - Ketika sedang mengedit akun, tampilkan form edit yang memuat:
    - Input nama akun (terisi nama akun yang dipilih).
    - Input token baru (opsional, placeholder: "Biarkan kosong jika tidak ingin mengubah token").
    - Tombol "Batal" (kembali ke list akun).
    - Tombol "Tes Koneksi" (aktif jika token baru diisi).
    - Tombol "Simpan Perubahan".
- [x] Lakukan verifikasi build frontend: `npx tsc --noEmit` & `npm --prefix frontend run build`.

### Task 4: Verifikasi Menyeluruh & Clean Code
- [x] Jalankan seluruh suite test backend (`pytest backend/tests/test_cf_db.py backend/tests/test_cloudflare_api.py`).
- [x] Jalankan typecheck frontend (`npx tsc --noEmit` di folder frontend).
- [x] Cek `git diff` untuk memastikan kerapian kode dan tidak ada regresi.
