# Design Document: TermDeck Containers Module (Cockpit RHEL Style)

## 1. Intent and The Why
TermDeck adalah manajer terminal SSH dan remote workspace (dengan fitur files ala Cockpit) untuk desktop dan Android. Pengguna (khususnya sysadmin/devops di RHEL, CentOS, Ubuntu, dll.) sering mengelola container (Podman / Docker) di server remote mereka. Saat ini mereka harus mengetik perintah CLI manual di terminal atau beralih ke Cockpit web UI eksternal. Modul **Containers** ini membawa kapabilitas manajemen kontainer grafis langsung ke dalam TermDeck Android & Desktop dengan UX terpadu.

## 2. Goals, Non-Goals, & Anti-Goals
### Goals
- **Auto-Detection Engine:** Otomatis mendeteksi engine kontainer pada server remote via SSH pool aktif: prioritas `podman` (standar RHEL/CentOS/Fedora) dengan fallback ke `docker` (Ubuntu/Debian). Menampilkan badge engine yang terdeteksi (misal: "Podman 5.2.0 (rootless)" atau "Docker 27.1.1").
- **Manajemen Kontainer Lengkap:**
  - Menampilkan daftar kontainer (running, exited, paused) dengan indikator status, CPU %, Mem %, port bindings, dan image.
  - Aksi lifecycle kontainer: Start, Stop, Restart, Pause, Unpause, Delete (force/normal).
  - Tampilan logs kontainer (tail dan live refresh).
  - Tombol **"Open Terminal" (Exec):** Membuka sesi shell interaktif langsung di tab terminal TermDeck (`podman/docker exec -it <id> /bin/sh`).
- **Manajemen Image:**
  - Daftar images lokal (ID, Repository, Tag, Size, Created).
  - Pull image baru dari registry.
  - Hapus image (rmi).
  - Dialog "Run Container": Form sederhana untuk membuat & menjalankan kontainer baru (Image, Name, Port mapping, Env vars, Volume/bind mount).
- **Integrasi Navigasi Intuitif:**
  - Header Workspace Mode Switcher: `[Terminal] | [Files] | [Containers]`.
  - Sidebar Server Action: Tombol cepat "Containers" pada tiap server di sidebar dan server details.
- **Dukungan Dual-Backend:** Berfungsi penuh baik di Android standalone (Ktor engine di `android/app/src/main/java/id/termdeck/app/server/ContainersRoutes.kt`) maupun Desktop/Linux server (`backend/app/containers_api.py`).

### Non-Goals
- Tidak membangun orchestration multi-node (Kubernetes / Docker Swarm).
- Tidak mengelola rootless network bridge konfigurasi tingkat kernel secara langsung di luar CLI podman/docker.
- Tidak menggantikan file compose kompleks (docker-compose up multi-service tetap lebih baik via terminal, meski compose container tetap terlihat di list).

### Anti-Goals
- **Zero Remote Dependencies:** Tidak boleh mewajibkan daemon/socket aktif di remote server (misal tidak memaksa `podman.socket` aktif di RHEL) atau menginstal package tambahan di remote host.
- Tidak boleh memblokir thread SSH utama saat streaming logs atau mengambil stats.

## 3. Constraints
- Koneksi ke remote server menggunakan pool SSH yang sudah ada (`ConnectionPool` di Python dan Android Kotlin).
- Keamanan: Semua argumen CLI yang dikirim ke remote shell wajib di-*escape* secara ketat (`shlex.quote` / posix escaping) untuk mencegah command injection.
- UI harus responsif: optimal untuk sentuhan mobile (Android touch friendly) dan desktop.
- Mengikuti arsitektur kode TermDeck yang ada (React 18 + TypeScript di frontend, Ktor di Android, FastAPI di Python backend).

## 4. Approaches Considered
1. **Approach 1: SSH Remote CLI JSON Wrapper (Dipilih)**
   - Eksekusi perintah CLI `podman` / `docker` over SSH dengan flag format JSON bawaan.
   - Normalisasi output di backend API layer menjadi skema seragam `ContainerInfo`, `ImageInfo`, `EngineStatus`.
   - *Alasan dipilih:* Mendukung rootless Podman RHEL secara out-of-the-box tanpa konfigurasi socket tambahan di remote server, zero-dependency, dan konsisten dengan implementasi modul `Files`.
2. **Approach 2: SSH Port Forwarding ke Unix Domain Socket (`podman.sock` / `docker.sock`)**
   - Menghubungkan client HTTP ke Unix socket remote melalui SSH tunnel.
   - *Alasan ditolak:* Di RHEL/CentOS, `podman.socket` secara default dinonaktifkan. Pengguna harus mengonfigurasi systemd socket secara manual. Selain itu, path socket rootless berbeda-beda per user ID.

## 5. Architectural & Technical Specification

### 5.1 Backend Contracts (`/api/containers/...`)
Endpoint REST berikut diterapkan di Python FastAPI (`backend/app/containers_api.py`) dan Kotlin Ktor (`android/.../server/ContainersRoutes.kt`):

1. `GET /api/containers/engine?server_id=<id>`
   - Mengembalikan `{ "engine": "podman" | "docker" | "none", "version": string, "rootless": boolean, "error": string | null }`
2. `GET /api/containers/list?server_id=<id>&all=true`
   - Mengembalikan array `ContainerItem`:
     `id, names, image, state (running|exited|paused), status, created, ports, cpu_pct, mem_usage, mem_limit`
3. `POST /api/containers/action`
   - Body: `{ "server_id": number, "container_id": string, "action": "start"|"stop"|"restart"|"pause"|"unpause"|"delete", "force"?: boolean }`
4. `GET /api/containers/logs?server_id=<id>&container_id=<cid>&tail=100`
   - Mengembalikan `{ "logs": string }`
5. `GET /api/containers/images?server_id=<id>`
   - Mengembalikan array `ImageItem`:
     `id, repository, tag, size, created`
6. `POST /api/containers/images/pull`
   - Body: `{ "server_id": number, "image": string }`
7. `DELETE /api/containers/images?server_id=<id>&image_id=<id>&force=false`
   - Menghapus image lokal.
8. `POST /api/containers/run`
   - Body: `{ "server_id": number, "image": string, "name"?: string, "ports"?: string[], "env"?: string[], "volumes"?: string[], "command"?: string }`

### 5.2 Frontend Components (`frontend/src/components/containers/`)
- `ContainersView.tsx`: Wrapper utama, server picker dropdown, badge engine info, tab toggle `[Containers]` dan `[Images]`, tombol refresh.
- `ContainerList.tsx`: Grid kartu atau tabel kontainer dengan badge status warna (hijau running, abu-abu exited, kuning paused), pemakaian memori/cpu, tombol aksi cepat (Play, Stop, Restart, Delete).
- `ContainerLogsModal.tsx`: Modal tampilan logs terminal gelap dengan auto-scroll dan tombol copy/refresh.
- `ContainerRunModal.tsx`: Dialog input pembuatan kontainer baru dengan validasi form port dan image.
- `ImageList.tsx`: Daftar image lokal, tombol pull image baru, dan aksi hapus/run.

### 5.3 Navigasi & Integrasi
- **TabBar.tsx:**
  - Perluas `workspaceMode` dari `"terminal" | "files"` menjadi `"terminal" | "files" | "containers"`.
  - Tambahkan tombol icon kubus/box (`Icon name="box"`) dengan label `Containers`.
- **Sidebar.tsx:**
  - Tambahkan tombol pintasan "Containers" di kartu server atau server details saat server dipilih, memicu `onChangeWorkspaceMode("containers")` dengan `selectedServerId`.
- **App.tsx:**
  - Tambahkan state handler untuk `workspaceMode === "containers"`, meneruskan server aktif dan fungsi pembuka terminal tab untuk fitur "Exec".

## 6. What's Left to the Builder
- Penyesuaian parsing JSON antara Podman v4/v5 dan Docker CLI (menangani variasi field nama seperti `Names` vs `Names[0]`, `State` string vs status enum).
- Implementasi icon SVG untuk `box` / `layers` di `Icon.tsx`.
- Optimasi auto-refresh interval (polling 5-10 detik saat tab Containers aktif, dan pause saat tab tidak aktif).
