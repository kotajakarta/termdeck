# Cloudflare Tunnel Integration & Collapsible Mini Sidebar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan manajemen Cloudflare Zero Trust Tunnel multi-akun di sidebar TermDeck untuk mengarahkan domain publik ke port/aplikasi tanpa membuka dashboard Cloudflare, serta menambahkan mode collapsible mini sidebar.

**Architecture:** Backend FastAPI menyediakan REST endpoint `/api/cf/...` yang mengelola enkripsi token di SQLite SQLCipher dan berkomunikasi langsung dengan Cloudflare REST API v4 (Accounts, Tunnels, Ingress Config, Zones, DNS Records). Frontend React menyajikan panel Cloudflare di sidebar dengan dropdown multi-akun, status tunnel, dan dialog tambah/hapus rute domain, plus tombol toggle sidebar mini (~52px icon-only).

**Tech Stack:** Python 3 (FastAPI, SQLCipher, urllib/httpx, pytest), React 18 (TypeScript, Vite, CSS).

**Spec:** `docs/superpowers/specs/2026-10-31-cloudflare-tunnel-sidebar-design.md`

## Global Constraints
- Token Cloudflare format `cfat_...` (Account API Token) atau legacy token diverifikasi via endpoint `/accounts` (bukan `/user/tokens/verify` karena `cfat_` adalah account-scoped).
- Kredensial akun Cloudflare (name, token, account_id, account_name) wajib disimpan di SQLite vault terenkripsi via SQLCipher.
- Ingress rule remote configuration wajib selalu mempertahankan fallback catch-all `{"service": "http_status:404"}` di posisi akhir.
- Sidebar mini harus menyimpan preferensi di `localStorage` ("termdeck.sidebarMini") dan menjaga responsivitas terminal / multi-pane layout.

## Review Focus
1. Input token salah atau tanpa hak akses: API harus mengembalikan pesan error yang jelas tanpa crashing.
2. Akun memiliki banyak zone/domain: Dropdown zone harus memuat seluruh nama domain akun dengan benar.
3. Subdomain CNAME sync: Saat rute `sub.domain.com` ditambahkan, sistem membuat CNAME ke `<tunnel_id>.cfargotunnel.com`; saat rute dihapus, CNAME record terkait juga dibersihkan.
4. Token mask: Endpoint `GET /api/cf/accounts` tidak boleh mengembalikan token utuh, hanya prefix + suffix (misal: `cfat_...4860`).
5. Transisi mini sidebar: Terminal resize observer / xterm `fit()` harus terpanggil lancar saat sidebar menyusut atau melebar.

---

### Task 1: Database Migration & Schemas untuk Akun Cloudflare

**Files:**
- Modify: `backend/app/schemas.py`
- Modify: `backend/app/db.py`
- Test: `backend/tests/test_cf_db.py`

**Interfaces:**
- Produces:
  - `CFAccountIn(name: str, token: str)`
  - `CFAccountOut(id: int, name: str, token_masked: str, account_id: str, account_name: str, created_at: str)`
  - `Store.list_cf_accounts() -> list[dict]`
  - `Store.get_cf_account(id: int) -> dict | None`
  - `Store.create_cf_account(name: str, token: str, account_id: str, account_name: str) -> int`
  - `Store.delete_cf_account(id: int) -> bool`

- [ ] **Step 1: Write test for Store cf_accounts operations**
Create `backend/tests/test_cf_db.py` testing create, list, get, and delete cf_account with SQLCipher in-memory / temp db.

- [ ] **Step 2: Run test to verify it fails**
Run: `pytest backend/tests/test_cf_db.py -v`

- [ ] **Step 3: Implement schemas and Store methods**
Tambahkan tabel `cf_accounts` di `SCHEMA` dan migration `_migrate()` di `backend/app/db.py`, serta Pydantic models di `backend/app/schemas.py`.

- [ ] **Step 4: Run test to verify it passes**
Run: `pytest backend/tests/test_cf_db.py -v`

---

### Task 2: Backend Cloudflare API Client & Routes

**Files:**
- Create: `backend/app/cloudflare_api.py`
- Test: `backend/tests/test_cloudflare_api.py`

**Interfaces:**
- Consumes: `Store` dari `backend/app/db.py`
- Produces:
  - `POST /api/cf/accounts/verify` (body: `{ token: str }`) -> `{ account_id, account_name }`
  - `GET /api/cf/accounts` -> `list[CFAccountOut]`
  - `POST /api/cf/accounts` -> `CFAccountOut`
  - `DELETE /api/cf/accounts/{id}` -> `{ status: "deleted" }`
  - `GET /api/cf/accounts/{id}/tunnels` -> list tunnel
  - `GET /api/cf/accounts/{id}/zones` -> list zones (domain names & IDs)
  - `GET /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> list ingress rules
  - `POST /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> add ingress rule & create DNS CNAME
  - `DELETE /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> delete ingress rule & delete DNS CNAME
  - `GET /api/cf/accounts/{id}/tunnels/{tunnel_id}/token` -> get tunnel run token

- [ ] **Step 1: Write mock tests for Cloudflare client endpoints**
Create `backend/tests/test_cloudflare_api.py` testing verify token, list accounts, fetch tunnels, fetch zones, and route add/delete with mocked Cloudflare responses.

- [ ] **Step 2: Run test to verify it fails**
Run: `pytest backend/tests/test_cloudflare_api.py -v`

- [ ] **Step 3: Implement `backend/app/cloudflare_api.py`**
Implementasikan fungsi komunikasi ke Cloudflare REST API v4 dan FastAPI routes.

- [ ] **Step 4: Run test to verify it passes**
Run: `pytest backend/tests/test_cloudflare_api.py -v`

---

### Task 3: Mount Router Cloudflare di API Utama

**Files:**
- Modify: `backend/app/api.py`
- Test: `backend/tests/test_api.py`

- [ ] **Step 1: Mount router Cloudflare di `backend/app/api.py`**
Mount router `/api/cf` dan inject instance `Store`.

- [ ] **Step 2: Jalankan pytest keseluruhan backend**
Run: `pytest backend/tests -v`

---

### Task 4: Frontend Types & API Client

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api.ts`

**Interfaces:**
- Produces:
  - Types: `CFAccount`, `CFTunnel`, `CFZone`, `CFRoute`, `CFRouteIn`
  - API functions: `listCFAccounts`, `verifyCFToken`, `createCFAccount`, `deleteCFAccount`, `listCFTunnels`, `listCFZones`, `listCFRoutes`, `createCFRoute`, `deleteCFRoute`, `getCFTunnelToken`

- [ ] **Step 1: Tambahkan types di `frontend/src/types.ts`**
- [ ] **Step 2: Tambahkan fungsi API calls di `frontend/src/api.ts`**
- [ ] **Step 3: Verifikasi TypeScript build**
Run: `npx tsc --noEmit` di folder `frontend`

---

### Task 5: Modal Dialogs (Akun Cloudflare & Tambah Rute)

**Files:**
- Create: `frontend/src/components/CloudflareAccountModal.tsx`
- Create: `frontend/src/components/CloudflareRouteModal.tsx`

- [ ] **Step 1: Implementasi `CloudflareAccountModal.tsx`**
Form input Nama Akun, Input Token (`testokencf.txt`), Tombol "Tes Koneksi", dan Tombol "Simpan". Menampilkan daftar akun yang tersimpan dan tombol hapus akun.

- [ ] **Step 2: Implementasi `CloudflareRouteModal.tsx`**
Form input Subdomain, Dropdown Domain Zone (otomatis diambil dari API), Dropdown Service (HTTP/HTTPS/SSH), Host/Port target (misal `localhost:9095`), dan tombol "Buat Rute & DNS".

- [ ] **Step 3: Verifikasi TypeScript build**
Run: `npx tsc --noEmit` di folder `frontend`

---

### Task 6: Panel Cloudflare di Sidebar

**Files:**
- Create: `frontend/src/components/CloudflarePanel.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`

- [ ] **Step 1: Implementasi `CloudflarePanel.tsx`**
Panel collapsible di sidebar yang memuat:
- Dropdown pemilih akun Cloudflare + tombol `⚙ / +` untuk membuka `CloudflareAccountModal`.
- Dropdown pemilih Tunnel (menampilkan status Healthy dengan titik hijau).
- Daftar rute aktif (Hostname -> Service) dengan tombol hapus (tempat sampah).
- Tombol "+ Rute Domain Baru" (membuka `CloudflareRouteModal`).
- Tombol salin perintah run cloudflared.

- [ ] **Step 2: Integrasikan `CloudflarePanel` ke dalam Sidebar / Workspace**
- [ ] **Step 3: Verifikasi TypeScript build**
Run: `npx tsc --noEmit` di folder `frontend`

---

### Task 7: Collapsible Mini Sidebar

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: Tambahkan state `sidebarMini` di `App.tsx`**
Persist di `localStorage.getItem("termdeck.sidebarMini")`.
Tambahkan tombol toggle di header sidebar (ikon chevron atau collapse sidebar).

- [ ] **Step 2: Styling CSS untuk mode mini (`.sidebar-mini`)**
Lebar sidebar menyusut menjadi ~52px, teks disembunyikan dengan overflow hidden, ikon tetap terlihat di tengah dengan tooltip. Ruang workspace / terminal membesar secara otomatis.

- [ ] **Step 3: Verifikasi TypeScript dan build UI**
Run: `npm run build` di folder `frontend`

---

### Task 8: Verifikasi End-to-End dengan Token Nyata (`testokencf.txt`)

- [ ] **Step 1: Jalankan seluruh test suite backend (`pytest`)**
- [ ] **Step 2: Jalankan full build frontend (`npm run build`)**
- [ ] **Step 3: Tes endpoint `/api/cf` menggunakan token dari `testokencf.txt` untuk memastikan data akun, zones, dan tunnel tampil nyata.**
