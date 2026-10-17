# Windows 11 Snap Layouts Specification

## Overview
Implementasi tata letak terminal bergaya **Windows 11 Snap Layouts** pada TermDeck. Fitur ini menyediakan pemisahan layar terminal dengan berbagai template preset (seperti 2 kolom seimbang, 2 kolom asimetris 67/33 dan 33/67, 3 panel L-split kiri penuh + kanan atas/bawah, 3 panel kanan penuh + kiri atas/bawah, 3 kolom seimbang, 3 kolom 25/50/25, 4 kuadran 2x2, dan 2 baris seimbang), serta popover menu interaktif dengan kartu-kartu visual dan hover zona ala Windows 11 Snap Assist.

---

## 1. Data Model & Layout Engine (`frontend/src/layout.ts`)

### 1.1 Extended Layout Types
```typescript
export type LayoutKind =
  | "1"
  | "2v"
  | "2h"
  | "3v"
  | "4"
  | "win-2-left-wide"    // 67% : 33%
  | "win-2-right-wide"   // 33% : 67%
  | "win-3-left-tall"    // Kiri 50% penuh, kanan atas 50%, kanan bawah 50%
  | "win-3-right-tall"   // Kanan 50% penuh, kiri atas 50%, kiri bawah 50%
  | "win-3-center-focus" // 25% : 50% : 25%
  | `${number}x${number}`
  | string;
```

### 1.2 Layout Configuration Schema
Setiap layout memiliki metadata lengkap:
```typescript
export interface LayoutZone {
  slotIndex: number;
  label: string;
  colStart: number;
  colSpan: number;
  rowStart: number;
  rowSpan: number;
}

export interface LayoutDefinition {
  kind: LayoutKind;
  label: string;
  slots: number;
  rows: number;
  cols: number;
  areas: string;
  defaultCols: number[];
  defaultRows: number[];
  zones: LayoutZone[];
}
```

### 1.3 Windows Snap Preset Definitions
1. **`win-2-equal` (alias `2v`)**:
   - `slots`: 2, `cols`: 2, `rows`: 1
   - `areas`: `"p0 p1"`
   - `defaultCols`: `[50, 50]`, `defaultRows`: `[100]`
2. **`win-2-left-wide`**:
   - `slots`: 2, `cols`: 2, `rows`: 1
   - `areas`: `"p0 p1"`
   - `defaultCols`: `[67, 33]`, `defaultRows`: `[100]`
3. **`win-2-right-wide`**:
   - `slots`: 2, `cols`: 2, `rows`: 1
   - `areas`: `"p0 p1"`
   - `defaultCols`: `[33, 67]`, `defaultRows`: `[100]`
4. **`win-3-left-tall`**:
   - `slots`: 3, `cols`: 2, `rows`: 2
   - `areas`: `"p0 p1" "p0 p2"`
   - `defaultCols`: `[50, 50]`, `defaultRows`: `[50, 50]`
   - Zona 0: row 1 span 2, col 1
   - Zona 1: row 1, col 2
   - Zona 2: row 2, col 2
5. **`win-3-right-tall`**:
   - `slots`: 3, `cols`: 2, `rows`: 2
   - `areas`: `"p0 p2" "p1 p2"`
   - `defaultCols`: `[50, 50]`, `defaultRows`: `[50, 50]`
   - Zona 0: row 1, col 1
   - Zona 1: row 2, col 1
   - Zona 2: row 1 span 2, col 2
6. **`win-3-equal` (alias `3v`)**:
   - `slots`: 3, `cols`: 3, `rows`: 1
   - `areas`: `"p0 p1 p2"`
   - `defaultCols`: `[33.333, 33.333, 33.334]`, `defaultRows`: `[100]`
7. **`win-3-center-focus`**:
   - `slots`: 3, `cols`: 3, `rows`: 1
   - `areas`: `"p0 p1 p2"`
   - `defaultCols`: `[25, 50, 25]`, `defaultRows`: `[100]`
8. **`win-4-grid` (alias `4`)**:
   - `slots`: 4, `cols`: 2, `rows`: 2
   - `areas`: `"p0 p1" "p2 p3"`
   - `defaultCols`: `[50, 50]`, `defaultRows`: `[50, 50]`
9. **`win-2-horizontal` (alias `2h`)**:
   - `slots`: 2, `cols`: 1, `rows`: 2
   - `areas`: `"p0" "p1"`
   - `defaultCols`: `[100]`, `defaultRows`: `[50, 50]`

### 1.4 Helper Functions in `layout.ts`
- `getLayoutDefinition(layout: LayoutKind): LayoutDefinition`
- `layoutDimensions(layout: LayoutKind)`: Mengembalikan `{ rows, cols, slots, areas, defaultCols, defaultRows }`
- `setLayoutWithSlot(state: PaneState, layout: LayoutKind, targetSlot: number, allTabIds: string[]): PaneState`:
  - Menempatkan tab aktif pada `targetSlot`.
  - Mengisi slot lain dengan sisa tab yang belum terlihat.
  - Memastikan state fokus berpindah ke `targetSlot`.

---

## 2. Interactive Windows 11 Snap Flyout (`SnapLayoutPicker.tsx` & `TabBar.tsx`)

### 2.1 Visual Design
- Kontainer popover dengan tema gelap (gelap abu-abu pekat `#1e2024`, rounded 12px, border halus `#33373e`, shadow lembut).
- Header ringkas dengan judul "Snap Layouts" dan tombol tab kustom jika pengguna masih ingin NxM manual.
- Grid kartu 3x3 (atau 3 kolom kartu) yang menampilkan layout mini:
  - Setiap kartu berukuran sekitar 88px × 60px dengan border tipis dan latar abu-abu gelap.
  - Zona di dalam kartu berupa blok mini abu-abu `#2c3036` dengan rounded border 4px.
  - Saat cursor hover di atas salah satu zona:
    - Zona tersebut berubah menjadi warna aksen biru cerah khas Windows 11 (`#0078d4` atau `--primary-color`).
    - Tooltip nama posisi (misal: "Kiri Penuh", "Kanan Atas", "Kuadran Kanan Bawah").
- Mengklik sebuah zona langsung memanggil `onSelectLayout(layoutKind, targetSlotIndex)`.

---

## 3. Terminal Workspace & CSS Grid Adaptation (`App.tsx` & `styles.css`)

### 3.1 CSS Grid Template Rendering
- Properti `.panes` di-update dinamis menggunakan `layoutDimensions(panes.layout)`:
  - `gridTemplateAreas`: mengambil definisi area langsung dari layout (misal `"p0 p1" "p0 p2"`).
  - `gridTemplateColumns`: persentase ukuran kolom.
  - `gridTemplateRows`: persentase ukuran baris.
- Setiap `<section className="pane">` diberi `gridArea: p${slotIndex}`, memungkinkan span vertikal maupun horizontal tanpa mengubah hierarki DOM.
- Sesi terminal (`<TerminalView>`) tetap di-mount sesuai `tab.id`, sehingga tidak terjadi unmount/re-mount ataupun disconnect WebSocket.

### 3.2 Draggable Dividers / Splitters
- Garis splitter kolom dan baris tetap bekerja secara akurat dengan melacak persentase akumulatif kolom dan baris.
- Double click pada divider mengembalikan proporsi ke `defaultCols` atau `defaultRows`.

---

## 4. Verification Plan

1. **Unit Tests (`frontend/tests/layout.test.ts`)**:
   - Uji definisi semua preset Snap Layouts Windows 11.
   - Uji `setLayoutWithSlot` memastikan tab aktif pindah ke target slot dan tab lain teralokasi dengan benar.
   - Uji perhitungan area grid dan dimensi.
2. **TypeScript Compilation**:
   - `npx tsc --noEmit` tanpa error.
3. **Production Build**:
   - `npm run build` sukses.
4. **Browser/Visual Verification**:
   - Menjalankan dev server/build dan memastikan tampilan flyout kartu dan responsivitas klik bekerja presisi.
