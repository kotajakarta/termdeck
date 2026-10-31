# Design Document: TermDeck Containers Module Table View & Operability Improvements

## 1. Intent and The Why
Saat ini daftar kontainer pada modul Containers menggunakan tampilan kartu (*card list*) vertikal. Jika server memiliki banyak kontainer, tampilan kartu memakan banyak ruang vertikal dan sulit dipindai (*scanned*) secara cepat. Pengguna meminta daftar kontainer yang tertata rapi berkolom (data table), informatif, dan mudah dioperasikan.

## 2. Goals, Non-Goals, & Anti-Goals
### Goals
- **Tata Letak Berkolom (Data Table View):** Menampilkan daftar kontainer dalam tabel berkolom yang rapi, sejajar, dan mudah dibaca:
  1. **Status**: Indikator visual status (*Running*, *Exited*, *Paused*) dengan badge warna standar.
  2. **Name & ID**: Nama kontainer yang menonjol dan Container ID ringkas (12 karakter) dengan tombol salin cepat.
  3. **Image**: Nama image beserta tag dalam font monospace ringkas dengan tooltip teks lengkap.
  4. **Ports**: Port bindings dalam format badge rapi (misal `8080 → 80/tcp`).
  5. **Created / Status**: Informasi status/uptime dan waktu dibuat.
  6. **Aksi Cepat (Actions)**: Kumpulan aksi operasional langsung di baris tabel:
     - State-aware lifecycle control: Start, Stop, Restart.
     - Tombol **Logs** untuk membuka modal log kontainer.
     - Tombol **Exec** untuk langsung meluncurkan sesi shell interaktif di terminal.
     - Menu aksi lanjutan / tombol Delete dengan konfirmasi.
- **Toggle View Mode (Table / Card):** Memberikan tombol toggle antara tampilan Tabel (*Table View*, default) dan Tampilan Kartu (*Card View*) agar pengguna dapat memilih mode yang paling nyaman sesuai preferensi atau ukuran layar.
- **Responsivitas & Operabilitas:**
  - Tetap responsif di layar mobile/desktop. Pada mobile atau layar sempit, tabel dapat di-scroll horizontal tanpa merusak layout atau pengguna dapat beralih ke Card View.
  - Quick filter cards (Total, Running, Paused, Exited) dan live search bar tetap terintegrasi mulus.
  - State indikator loading (spinner/disabled) pada tombol aksi saat request sedang berjalan.

### Non-Goals
- Tidak mengubah kontrak API backend (`/api/containers/...`). Seluruh peningkatan difokuskan pada penyajian data dan UX di frontend.
- Tidak menambahkan fitur multi-selection batch action di luar kebutuhan single container lifecycle.

## 3. Approaches Considered
1. **Tabel Data Terstruktur dengan View Toggle (Dipilih):**
   - Menambahkan tabel data responsif untuk kontainer dengan kolom: Status, Nama & ID, Image, Port, Uptime/Created, dan Aksi.
   - Menyediakan view toggle (Tabel vs Kartu) dengan preferensi tersimpan di local state atau `storage.ts`.
   - Menggunakan CSS khusus untuk table cells, action toolbars, dan port badges.
   - *Alasan:* Paling fleksibel, memberikan kerapian maksimal pada desktop sekaligus tetap menjaga kompatibilitas mobile.

2. **Hanya Merombak Kartu Menjadi Grid:**
   - Menyusun kartu menjadi 2 atau 3 kolom (grid).
   - *Alasan ditolak:* Masih sulit untuk membandingkan baris data (port, image, status) secara sejajar dibandingkan tabel berkolom sejati.

## 4. Technical Specifications
- **Komponen Utama:** `frontend/src/components/containers/ContainerList.tsx`
- **Gaya Tampilan (CSS):** `frontend/src/styles.css`
  - Tambah styling `.containers-table-wrap`, `.container-name-cell`, `.container-quick-actions`, `.view-mode-toggle`, `.port-chip`.
- **Integrasi State:**
  - View mode state: `"table" | "cards"` (default `"table"`).
