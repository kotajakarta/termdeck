# Design Document: Cloudflare Tunnel Integration & Collapsible Mini Sidebar

## 1. Intent and The Why
TermDeck digunakan sebagai terminal manager dan remote workspace. Pengguna sering kali ingin mengekspos aplikasi web lokal/remote (seperti TermDeck port 9095, web service port 8080/3000, atau SSH) ke domain publik melalui Cloudflare Zero Trust Tunnels tanpa harus membuka Cloudflare Dashboard di browser.
Selain itu, saat pengguna sedang fokus di terminal, sidebar yang lebar memakan ruang layar horizontal. Fitur collapsible mini sidebar memungkinkan pengguna meminimalkan sidebar menjadi ikon saja, memaksimalkan area terminal/workspace.

## 2. Goals, Non-Goals, & Anti-Goals

### Goals
- **Multi-Akun Cloudflare Token Management:**
  - Menyimpan token Cloudflare (Account API Token `cfat_...` atau User Token) secara aman di SQLite vault terenkripsi (SQLCipher).
  - Mendukung banyak akun dengan nama/label kustom (misal *"Akun Pribadi"*, *"Akun Kantor"*).
  - Form verifikasi / tes koneksi token langsung sebelum disimpan (mengecek akun Cloudflare dan hak akses).
  - Dropdown pemilih akun aktif di sidebar.
- **Manajemen Tunnel & Routing (Public Hostname):**
  - Menampilkan daftar tunnel yang tersedia pada akun yang dipilih beserta status kesehatannya (*healthy*, *inactive*, jumlah konektor).
  - Menampilkan rute ingress / public hostname yang aktif pada tunnel yang dipilih.
  - **Tambah Rute Baru (Public Hostname):**
    - Subdomain input + dropdown nama domain root (diambil otomatis dari Cloudflare Zones akun).
    - Service Type (`http`, `https`, `ssh`, dll) dan origin URL (misal: `http://localhost:9095`).
    - Otomatis memperbarui konfigurasi remote tunnel (ingress rule) di Cloudflare API.
    - Otomatis membuat record DNS CNAME (`subdomain.domain` -> `<tunnel_id>.cfargotunnel.com`, proxied: true) di Cloudflare DNS.
  - **Hapus Rute:** Menghapus ingress rule dari tunnel dan menghapus record DNS CNAME terkait.
  - Menyediakan token instalasi / perintah eksekusi `cloudflared tunnel run --token <token>` untuk kemudahan copy-paste ke server node.
- **Collapsible Mini Sidebar:**
  - Tombol toggle collapse di header sidebar.
  - Mode mini menyusutkan sidebar menjadi bar ikon vertikal (~52px) dengan tooltip aksi cepat (Server, Cloudflare, Snippets, Lock, Pin).
  - Transisi halus dan lebar workspace/terminal otomatis menyesuaikan.
  - Status collapse (mini vs normal) disimpan di `localStorage`.

### Non-Goals
- Tidak mengotomasi instalasi daemon `cloudflared` di host OS secara paksa tanpa persetujuan user.
- Tidak mengelola firewall rules / WAF / Cloudflare Access policies yang kompleks (fokus pada Tunnel Public Hostnames & DNS mapping).

### Anti-Goals
- Tidak menyimpan token Cloudflare dalam bentuk plaintext yang terekspos ke unauthenticated clients.
- Tidak menghapus rute default catch-all (`service: http_status:404`) pada remote ingress configuration.

## 3. Constraints
- Backend: Python FastAPI + SQLCipher (`backend/app/cloudflare_api.py`, `backend/app/db.py`, `backend/app/api.py`).
- Frontend: React + TypeScript + Vite (`frontend/src/components/CloudflarePanel.tsx`, `frontend/src/components/CloudflareAccountModal.tsx`, `frontend/src/components/Sidebar.tsx`, `frontend/src/styles.css`).
- Kompatibilitas Cloudflare API Token: Mendukung format baru `cfat_` (Account API Token) dengan deteksi capabilities via `/accounts` dan `/zones`.

## 4. Approaches Considered
1. **Approach 1: Cloudflare v4 REST API Direct Integration (Dipilih)**
   - Backend TermDeck melakukan pemanggilan langsung ke API resmi Cloudflare (`https://api.cloudflare.com/client/v4/`):
     - `/accounts`: untuk verifikasi token dan mendapatkan Account ID.
     - `/zones`: untuk mendapatkan daftar domain yang aktif.
     - `/accounts/{account_id}/cfd_tunnel`: untuk list & info tunnel.
     - `/accounts/{account_id}/cfd_tunnel/{tunnel_id}/configurations`: untuk baca & update ingress public hostname.
     - `/zones/{zone_id}/dns_records`: untuk sinkronisasi CNAME record.
   - *Alasan dipilih:* Resmi, cepat, tanpa dependensi CLI lokal, mendukung remote-managed tunnel secara native.
2. **Approach 2: Local CLI `cloudflared` Subprocess Wrapper**
   - Memanggil binary `cloudflared` via subprocess di mesin backend.
   - *Alasan ditolak:* Memerlukan binary `cloudflared` terinstall di host, parsing output CLI rentan perubahan versi, dan tidak mendukung manajemen remote configuration semudah REST API.

## 5. Architectural & Technical Specification

### 5.1 Database Schema Migration (`backend/app/db.py`)
Tabel baru di SQLite vault:
```sql
CREATE TABLE IF NOT EXISTS cf_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    token TEXT NOT NULL,
    account_id TEXT NOT NULL,
    account_name TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 5.2 Backend API (`backend/app/cloudflare_api.py`)
Prefix router: `/api/cf`
- `GET /api/cf/accounts` -> List semua akun CF tersimpan (tanpa menampilkan token lengkap, hanya token mask atau ID).
- `POST /api/cf/accounts/verify` -> Tes token sebelum disimpan (memvalidasi ke Cloudflare `/accounts`, mengembalikan nama akun & account_id).
- `POST /api/cf/accounts` -> Simpan akun baru `{ name, token }`.
- `DELETE /api/cf/accounts/{id}` -> Hapus akun.
- `GET /api/cf/accounts/{id}/tunnels` -> List tunnel pada akun tersebut.
- `GET /api/cf/accounts/{id}/zones` -> List active zones (domain) pada akun tersebut.
- `GET /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> Mengambil daftar ingress public hostnames saat ini.
- `POST /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> Menambahkan public hostname (update ingress config + buat CNAME DNS record).
- `DELETE /api/cf/accounts/{id}/tunnels/{tunnel_id}/routes` -> Menghapus public hostname (update ingress config + hapus CNAME DNS record).
- `GET /api/cf/accounts/{id}/tunnels/{tunnel_id}/token` -> Mengambil run token untuk instalasi `cloudflared tunnel run --token ...`.

### 5.3 Frontend UI Integration
1. **Collapsible Mini Sidebar (`Sidebar.tsx`, `App.tsx`, `styles.css`):**
   - State `sidebarMini` di `App.tsx` (default dari `localStorage.getItem("termdeck.sidebarMini")`).
   - Tombol toggle di sidebar header: ikon panah lipat / burger.
   - CSS class `.sidebar-mini` mengecilkan lebar sidebar menjadi ~52px, menyembunyikan teks label, dan menampilkan ikon navigasi cepat.
2. **Cloudflare Sidebar Panel (`CloudflarePanel.tsx`):**
   - Diletakkan di dalam scroll area sidebar atau bagian collapsible panel di bawah server.
   - Header panel dengan judul "Cloudflare Tunnel", status jumlah tunnel aktif, dan tombol toggle accordion.
   - Dropdown pilih akun aktif + tombol kelola token (`+` / `⚙`).
   - Tampilan tunnel aktif: badge status hijau (Healthy) atau abu-abu/merah.
   - Daftar rute aktif (Hostname &rarr; Target Service) dengan tombol hapus.
   - Tombol "+ Tambah Rute" membuka modal input rute baru.
3. **Modal Dialogs:**
   - `CloudflareAccountModal.tsx`: Form input Label Akun + Token CF (dengan tombol Tes Koneksi).
   - `CloudflareRouteModal.tsx`: Form tambah rute (Subdomain, Pilih Zone dari dropdown, Service Protocol HTTP/HTTPS/SSH, Port/IP Origin).
