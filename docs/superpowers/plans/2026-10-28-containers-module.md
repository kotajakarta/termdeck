# Containers Module (Cockpit RHEL Style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan modul Containers bergaya Cockpit RHEL (Podman & Docker) ke TermDeck Android & Desktop dengan navigasi header mode switcher, pintasan sidebar, manajemen lifecycle kontainer, inspect logs, terminal exec, dan image management.

**Architecture:** Menggunakan pendekatan *SSH Remote CLI JSON Wrapper*. Backend (Ktor di Android dan FastAPI di Linux) mengeksekusi perintah CLI `podman` / `docker` over pool SSH yang ada dengan flag output JSON, menormalisasi skema response, dan mengekspos REST API ke frontend React. Frontend menyediakan tampilan `ContainersView` terintegrasi dengan filter pencarian, status badge, streaming logs, eksekusi exec via tab terminal TermDeck, dan form pull/run image.

**Tech Stack:** 
- Frontend: React 18, TypeScript, CSS Variables, Lucide-style SVG Icons.
- Backend Desktop: Python 3.10+, FastAPI, AsyncSSH, Pydantic v2.
- Backend Android: Kotlin 1.9+, Ktor Server 2.x, Kotlinx Serialization, SshPoolBackend.

**Spec:** [`docs/superpowers/specs/2026-10-28-containers-module-design.md`](file:///home/aithendi/webku/xterm/docs/superpowers/specs/2026-10-28-containers-module-design.md)

## Global Constraints
- Remote connection MUST reuse existing SSH `ConnectionPool` (`pool.execCommand` on Android, `conn.run` on Python).
- CLI arguments executed remotely MUST be strictly sanitized/escaped using posix escaping (`shlex.quote` in Python, `shlexQuote` in Kotlin) to prevent command injection.
- Zero remote dependencies: No required remote systemd socket activation or pre-installed agent scripts.
- Touch-friendly on Android mobile screens and responsive on desktop.
- Verification required before completion: `npx tsc --noEmit`, frontend test suite, and python syntax check.

## Review Focus
1. Remote server without podman or docker: return status `engine: "none"` gracefully with informative empty state and instructions.
2. Rootless Podman on RHEL: ensure commands run in user context without requiring root or sudo.
3. Slow `stats` CLI over SSH: separate container listing from stats polling so `list` completes within <300ms without blocking.
4. Non-standard JSON formats between older Podman (v3/v4) and Docker: robust fallback parsing for `Names` (string array vs single string) and port mappings.
5. Terminal Exec: smoothly create or jump to a terminal tab in TermDeck with `podman exec -it <id> /bin/sh` or bash.

---

### Task 1: TypeScript Models, API Client, and Icon Definitions
**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/components/Icon.tsx`

- [ ] Tambahkan tipe data kontainer di `frontend/src/types.ts`: `ContainerEngineInfo`, `ContainerItem`, `ContainerPort`, `ContainerActionBody`, `ImageItem`, `ContainerRunBody`.
- [ ] Tambahkan icon `box` / `container` dan `layers` di `frontend/src/components/Icon.tsx`.
- [ ] Tambahkan method API di `frontend/src/api.ts`:
  - `containersEngine(serverId: number)`
  - `containersList(serverId: number, all?: boolean)`
  - `containersAction(body: ContainerActionBody)`
  - `containersLogs(serverId: number, containerId: string, tail?: number)`
  - `containersImages(serverId: number)`
  - `containersPullImage(serverId: number, image: string)`
  - `containersDeleteImage(serverId: number, imageId: string, force?: boolean)`
  - `containersRun(body: ContainerRunBody)`
- [ ] Verifikasi dengan `npx tsc --noEmit`.

---

### Task 2: Python Backend Containers API (`backend/app/containers_api.py`)
**Files:**
- Create: `backend/app/containers_api.py`
- Modify: `backend/app/main.py`
- Test: `backend/tests/test_containers_api.py`

- [ ] Buat router `containers_api.py` dengan dependency injection `Store` dan `ConnectionPool`.
- [ ] Implementasikan helper deteksi engine: jalankan `podman version --format json` lalu fallback ke `docker version --format '{{json .}}'`, atau cek exit code.
- [ ] Implementasikan endpoint `GET /api/containers/engine`, `GET /api/containers/list`, `POST /api/containers/action`, `GET /api/containers/logs`, `GET /api/containers/images`, `POST /api/containers/images/pull`, `DELETE /api/containers/images`, `POST /api/containers/run`.
- [ ] Normalisasikan output JSON Podman & Docker ke schema seragam `ContainerItem` dan `ImageItem`.
- [ ] Daftarkan router di `backend/app/main.py`.
- [ ] Buat unit test di `backend/tests/test_containers_api.py`.
- [ ] Verifikasi dengan `python3 -m py_compile backend/app/containers_api.py` dan jalankan test.

---

### Task 3: Android Ktor Backend Containers API (`ContainersRoutes.kt`)
**Files:**
- Create: `android/app/src/main/java/id/termdeck/app/server/ContainersRoutes.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/server/TermDeckServer.kt`

- [ ] Buat `ContainersRoutes.kt` dengan routing Ktor di `/api/containers/*`.
- [ ] Gunakan `pool.execCommand(serverId, cmd, ...)` dengan sanitasi `shlexQuote`.
- [ ] Implementasikan parsing JSON via `kotlinx.serialization` untuk output podman/docker info, ps, logs, images, run.
- [ ] Daftarkan `installContainersRoutes(...)` di `TermDeckServer.kt`.

---

### Task 4: Frontend Containers UI Components
**Files:**
- Create: `frontend/src/components/containers/ContainersView.tsx`
- Create: `frontend/src/components/containers/ContainerList.tsx`
- Create: `frontend/src/components/containers/ImageList.tsx`
- Create: `frontend/src/components/containers/ContainerLogsModal.tsx`
- Create: `frontend/src/components/containers/ContainerRunModal.tsx`
- Modify: `frontend/src/styles.css`

- [ ] Buat `ContainersView.tsx`: Toolbar atas berisi Server selector, badge Engine (`Podman (rootless)` / `Docker`), status koneksi, tombol refresh, dan sub-tab `[Containers (count)]` / `[Images (count)]`.
- [ ] Buat `ContainerList.tsx`: Kartu ringkasan (Running, Paused, Stopped), filter search, baris kontainer dengan badge status berwarna, indikator port, dan tombol aksi (Start, Stop, Restart, Pause, Delete, Logs, Exec Terminal).
- [ ] Buat `ContainerLogsModal.tsx`: Tampilan logs monospaced dengan autoscroll, tombol copy, dan opsi auto-tail.
- [ ] Buat `ContainerRunModal.tsx`: Form input untuk image, nama kontainer, port mapping (host:container), environment variables, volume mapping.
- [ ] Buat `ImageList.tsx`: Tabel daftar image, tombol Pull Image baru, Run Image, dan Delete Image.
- [ ] Tambahkan styling responsif di `frontend/src/styles.css` (termasuk optimasi mobile layar sempit).

---

### Task 5: Navigasi & Integrasi Terpadu (TabBar, Sidebar, App.tsx)
**Files:**
- Modify: `frontend/src/components/TabBar.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] Perluas `workspaceMode` menjadi `"terminal" | "files" | "containers"`.
- [ ] Di `TabBar.tsx`: Tambahkan tombol tab `Containers` dengan icon `box`.
- [ ] Di `Sidebar.tsx`: Tambahkan tombol pintasan "Containers" pada tiap server dan di panel info server.
- [ ] Di `App.tsx`:
  - Render `<ContainersView>` ketika `workspaceMode === "containers"`.
  - Pasang handler `onOpenTerminal(serverId, containerId, containerName)` yang secara otomatis membuka tab terminal baru dengan eksekusi `podman exec -it <cid> /bin/sh || docker exec -it <cid> /bin/sh`.
- [ ] Simpan preferensi server terpilih untuk Containers view.

---

### Task 6: Verifikasi, Build & Uji Menyeluruh
- [ ] Jalankan verifikasi TypeScript: `npx --prefix frontend tsc --noEmit`.
- [ ] Jalankan frontend unit test: `npm --prefix frontend test`.
- [ ] Jalankan python check: `python3 -m py_compile backend/app/containers_api.py backend/app/main.py`.
- [ ] Jalankan frontend build: `npm --prefix frontend run build`.
- [ ] Cek status git diff dan pastikan tidak ada regresi pada modul Files maupun Terminal.
