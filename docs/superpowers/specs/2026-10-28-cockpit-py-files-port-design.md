# Porting `cockpit-py` File Manager to TermDeck (Web & Android) — Design Spec

## 1. Intent & Why
Pengguna menginginkan File Manager di TermDeck memiliki tampilan dan seluruh alur kerja pengoperasian yang sama persis dengan File Manager yang sudah matang di `/mnt/d/htdocs/cockpit-py` (`FilesView`, `FilePane`, `FileDialogs`, `FileViewer`, `FileThumb`, `RemoteSync`, dan `files_agent.py`), menggantikan drawer File Manager sederhana sebelumnya.

## 2. Goals & Non-Goals
### Goals
1. **Full Workspace File Manager (`FilesView` + `FilePane`):**
   - Tampil sebagai workspace penuh (dapat diakses dari switcher `Terminal | Files` di header/TabBar, tombol `File` di pane/Home/MobileKeyBar) bukan sekadar drawer sempit.
   - Mendukung **Dual-Pane Split View (F3)** ala Dolphin/WinSCP, di mana masing-masing pane dapat memilih **Server** sendiri dari dropdown toolbar, memiliki mobile switcher (`Panel 1 ⇄ Panel 2`) saat layar sempit, dan berbagi clipboard serta transfer job.
2. **Toolbar, Breadcrumb & Navigasi Matang (`FilePane`):**
   - Pemilih server per-pane, menu Bookmarks (`Home`, `Filesystem root (/)`, daftar path favorit yang tersinkronisasi dengan database TermDeck, `Add/Remove bookmark`), tombol `Up` (`ArrowUp`), **Smart Address Bar** (Breadcrumb dengan ikon `HardDrive` untuk `/`, auto-collapse segmen tengah ke menu `…` saat sempit, dan klik ikon pensil / double-click untuk edit path manual), input `Filter directory`, toggle `List / Grid view`, tombol `Upload`, tombol `Terminal` (buka terminal di folder aktif), `Refresh`, `Split view (F3)`, `Details panel (F11)`, dan menu aksi direktori (`⋮`).
3. **Operasi File Lengkap (`files_agent.py` + Fallback SFTP):**
   - List direktori lengkap dengan `type` (`directory`, `file`, `link`, `device`, `fifo`, `socket`, `other`), `size`, `mtime`, `mode`, `perms` (`drwxr-xr-x`), `owner`, `group`, `target`, `target_type` (`directory` / `file` / `broken`).
   - Endpoint `/api/files/{serverId}/principals` untuk mengambil daftar `users`, `groups`, dan `home` dari server.
   - Endpoint `/api/files/{serverId}/action` yang mendukung operasi:
     - `mkdir` (buat folder)
     - `write` (buat file baru atau simpan edit teks UTF-8 ≤ 2 MB secara atomik dengan pengecekan `expected_mtime`)
     - `symlink` (buat symbolic link)
     - `rename` (ubah nama file/folder)
     - `paste` (`copy` / `move` multi-item di server yang sama dengan auto-deduplikasi nama `(copy)`)
     - `delete` (hapus multi-item rekursif dengan proteksi `PROTECTED` system paths)
     - `chmod` (ubah permission mode oktal + `owner` + `group` + opsi `recursive` di mana direktori tetap memiliki `+x` dan file mengikuti checkbox `Set executable`)
   - Upload multi-file (`/api/files/{serverId}/upload`) dengan deteksi konflik `409 File Exists` (dialog konfirmasi `Replace` / `Skip`) dan progress bar per file.
   - Download file (`/api/files/{serverId}/download`), inline media streaming dengan dukungan HTTP `Range` (`/api/files/{serverId}/view`), dan thumbnail (`/api/files/{serverId}/thumb`).
4. **Inter-Server Direct Transfer & Remote Sync (`RemoteSync`):**
   - Saat mode Split membuka 2 server berbeda, aksi **Copy to `<Server>` (F5)** / **Move to `<Server>` (F6)** atau drag-and-drop lintas pane membuka `DirectTransferModal` (menampilkan hasil scan jumlah file/folder/ukuran, opsi `.gitignore`, dan menjalankan streaming tar `pack` → `unpack` antar server dengan progress bar `TransferList`).
   - Mendukung konfigurasi `FileRemote` (mapping folder antar server + deteksi `.vscode/sftp.json`).
5. **Dialog, Details Panel, Media Viewer & Thumbnail (`FileDialogs`, `FileViewer`, `FileThumb`):**
   - `NameDialog` (validasi nama, auto-select basename tanpa ekstensi), `LinkDialog`, `DeleteDialog`, `PermissionsDialog` (Owner, Group, Owner/Group/Others Access `Read and write` / `Read-only` / `No access`, `Set executable`, `Apply to all contents`, preview oktal), `EditorDialog` (`Ctrl+S`, Tab indent, indikator dirty `•`, jumlah baris).
   - `FileViewer` untuk preview gambar (zoom/actual size), video (kecepatan putar `0.5x–2x`, PiP, seek), audio, dan PDF, dengan navigasi `← / →` antar file media di folder.
   - Panel `Details` di sisi kanan (`F11`) dan baris status informasi di footer pane.

### Non-Goals
- Tidak menambahkan dependensi berat seperti Ant Design (`antd`) ke bundle TermDeck agar ukuran APK dan kecepatan WebView tetap ringan; seluruh komponen UI (`Dropdown`, `Modal`, `Table`, `Breadcrumb`, `Segmented`, `Select`, `Progress`, `Toast`) dibangun dengan komponen React + CSS native/custom yang mereplikasi tampilan, tata letak, dan perilaku `cockpit-py` secara presisi.

## 3. Architecture & File Layout
- **Backend Python (`backend/app/`):**
  - `backend/app/files_agent.py`: Script agent stdlib Python yang dijalankan di target server via SSH (`python3 -c ...`), identik dengan `cockpit-py/backend/app/services/files_agent.py`.
  - `backend/app/files_api.py`: Endpoint FastAPI `/api/files/...` untuk `list`, `principals`, `read`, `action`, `upload`, `download`, `view`, `thumb`, `bookmarks`, `remotes`, `direct-transfer/scan`, `direct-transfer/start`, dan `transfers/{jobId}`.
- **Backend Android Kotlin (`android/app/src/main/java/id/termdeck/app/`):**
  - Menyertakan `files_agent.py` di `android/app/src/main/assets/files_agent.py` dan mengeksekusinya melalui SSHJ `session.exec("python3 -c ...")` di `ConnectionPool.kt` (dengan fallback SFTP otomatis bila `python3` tidak tersedia di target server), serta menangani relay streaming `pack` → `unpack` untuk transfer lintas server di `TermDeckServer.kt`.
- **Frontend (`frontend/src/components/files/`):**
  - `fileUtils.ts`: Helper path, ikon per tipe file, format ukuran/tanggal, `previewKind`, `thumbKind`, `accessOf`, `buildMode`.
  - `FileThumb.tsx`: Thumbnail gambar dan ekstraksi frame video di browser dengan `IntersectionObserver`.
  - `FileViewer.tsx`: Modal preview gambar, video, audio, dan PDF dengan navigasi `← / →`.
  - `FileDialogs.tsx`: `NameDialog`, `LinkDialog`, `DeleteDialog`, `PermissionsDialog`, `EditorDialog`.
  - `RemoteSync.tsx`: `RemoteDialog`, `TransferSummary`, `DirectTransferSummary`, `DirectTransferModal`, `TransferList`.
  - `FilePane.tsx`: Komponen pane utama dengan toolbar, address bar (breadcrumb auto-fold + edit), tabel list view, grid view, drag & drop, context menu, details portal, dan footer.
  - `FilesView.tsx`: Kontainer workspace File Manager dengan dukungan single/split pane, switcher mobile, panel Details kanan, dan daftar transfer aktif.
