# Custom Terminal Layout and Draggable Splitters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 3-column preset layout, customizable RxC grid layouts with a visual grid picker, and interactive draggable splitters between terminal panes.

**Architecture:** Extend `layout.ts` to support both preset layouts (`1`, `2v`, `2h`, `3v`, `4`) and custom `${rows}x${cols}` format. Build a visual grid picker popover in `TabBar.tsx` for easy layout selection, and integrate dynamic CSS Grid track sizes (`colSizes`, `rowSizes`) with draggable splitter handles in `App.tsx` and `styles.css` while preserving xterm sessions.

**Tech Stack:** React 18, TypeScript, Vite, CSS Grid, xterm.js with FitAddon, Node.js test runner.

**Spec:** In-conversation approved specification for 3-column layout, custom RxC panel layout, and draggable dividers.

## Global Constraints

- Do not disrupt existing SSH WebSocket sessions or unmount active terminals when changing layout.
- Maintain full compatibility with existing `localStorage` keys (`termdeck.layout`).
- Ensure all TypeScript types are strictly typed without `any`.
- Comply with Indonesian localization for UI labels.
- Follow TDD with Node test runner (`npm test`) and verify with `npx tsc --noEmit` and `npm run build`.

## Review Focus

- Restoring saved layout from localStorage when it's an unrecognized or legacy string must safely fallback to "1".
- Resizing splitters must not drag below the minimum pane width (80px) or height (60px).
- Mouse release outside window or over xterm canvas during drag must cleanly terminate the drag operation.
- Double-clicking a splitter must reset column or row sizes back to equal distribution.
- Switching between different grid dimensions (e.g. 3v to 2x2) must gracefully redistribute `colSizes` and `rowSizes`.

---

### Task 1: Extend `layout.ts` with 3v preset, custom RxC layout support, and track sizing

**Files:**
- Modify: `frontend/src/layout.ts`
- Modify: `frontend/tests/layout.test.ts`

**Interfaces:**
- `export type LayoutKind = "1" | "2v" | "2h" | "3v" | "4" | `${number}x${number}` | string;`
- `export function layoutDimensions(layout: LayoutKind): { rows: number; cols: number; slots: number }`
- `export function gridTemplateAreasFor(rows: number, cols: number): string`
- `export function defaultTrackSizes(count: number): number[]`
- `export function resizeTrack(sizes: number[], index: number, deltaPct: number, minPct?: number): number[]`

- [ ] **Step 1: Write failing tests for 3v and custom RxC layout in `layout.test.ts`**

Add tests for:
- `layoutDimensions("3v")` returning `{ rows: 1, cols: 3, slots: 3 }`
- `layoutDimensions("2x3")` returning `{ rows: 2, cols: 3, slots: 6 }`
- `gridTemplateAreasFor(2, 3)` producing `"\"p0 p1 p2\" \"p3 p4 p5\""`
- `defaultTrackSizes(3)` returning `[33.333, 33.333, 33.333]` (summing to 100)
- `resizeTrack([50, 50], 0, 10)` returning `[60, 40]` with minPct enforcement
- `setLayout` transitioning to "3v" and custom "2x3"

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test` in `frontend`
Expected: FAIL due to missing functions / layout 3v not supported

- [ ] **Step 3: Implement functions in `frontend/src/layout.ts`**

Implement `LAYOUTS`, `layoutDimensions`, `gridTemplateAreasFor`, `defaultTrackSizes`, `resizeTrack`, and update `slotCount` & `setLayout` to support 3v and custom dimensions.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test` in `frontend`
Expected: PASS

- [ ] **Step 5: Verify types with tsc**

Run: `npx tsc --noEmit` in `frontend`

---

### Task 2: Add SVG Icons in `frontend/src/components/Icon.tsx`

**Files:**
- Modify: `frontend/src/components/Icon.tsx`

**Interfaces:**
- Produces: `layout3v` and `layoutCustom` keys in `PATHS`

- [ ] **Step 1: Add SVG paths for `layout3v` and `layoutCustom`**

Add:
```ts
layout3v: "M4 5h16v14H4zM9.33 5v14M14.67 5v14",
layoutCustom: "M4 5h16v14H4zM4 11h16M4 17h16M10 5v14M16 5v14",
```

- [ ] **Step 2: Verify TypeScript compilation**

Run: `npx tsc --noEmit` in `frontend`
Expected: PASS

---

### Task 3: Build Visual Grid Picker & Layout Switcher in `TabBar.tsx`

**Files:**
- Modify: `frontend/src/components/TabBar.tsx`

**Interfaces:**
- Consumes: `LAYOUTS`, `LayoutKind`, `layoutDimensions` from `../layout`
- Consumes: `Icon` from `./Icon`
- Handles custom layout selection via visual hover matrix and number inputs

- [ ] **Step 1: Update `TabBar.tsx` to include `3v` preset and `Custom` popover**

- Render preset buttons for `1`, `2v`, `3v`, `2h`, `4`.
- Render a "Kustom..." button with `layoutCustom` icon.
- In popover:
  - 4x4 interactive grid cells (hovering row `r` and col `c` highlights all cells from `(0,0)` to `(r,c)`).
  - Info badge showing e.g. `2 × 3 (6 panel)`.
  - Manual numeric inputs for Baris (1-4) and Kolom (1-6) with "Terapkan" button.
  - Click outside closes the popover.

- [ ] **Step 2: Verify TypeScript compilation**

Run: `npx tsc --noEmit` in `frontend`
Expected: PASS

---

### Task 4: Add CSS styles for 3v, custom layouts, Grid Picker, and Splitter Handles in `styles.css`

**Files:**
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: Add CSS rules**

- `.layout-3v` grid template.
- `.grid-picker-popover` styling: background, border, shadow, grid matrix cell hover, input stepper, and buttons.
- `.pane-splitter-v` and `.pane-splitter-h`:
  - Invisible or subtle 6px hit target between panes.
  - Accent colored hairline indicator.
  - Hover / active dragging styles.
  - `cursor: col-resize` and `cursor: row-resize`.
- `.panes.is-resizing`: disable pointer-events and text selection across terminals during drag.

- [ ] **Step 2: Verify CSS with `node -c` or syntax check**

---

### Task 5: Implement Resizable Dividers and Dynamic Grid in `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `layoutDimensions`, `gridTemplateAreasFor`, `defaultTrackSizes`, `resizeTrack` from `./layout`
- Renders: vertical splitters for `cols - 1` and horizontal splitters for `rows - 1`
- Handles: pointer down/move/up for smooth resizing and double-click to reset equal sizes

- [ ] **Step 1: Update `App.tsx`**

- Read saved layout and initialize `colSizes` and `rowSizes`.
- When layout changes, recalculate `colSizes` & `rowSizes` using `defaultTrackSizes`.
- Generate inline styles for `.panes`:
  - `gridTemplateAreas: gridTemplateAreasFor(rows, cols)`
  - `gridTemplateColumns: colSizes.map(s => `${s}%`).join(" ")`
  - `gridTemplateRows: rowSizes.map(s => `${s}%`).join(" ")`
- Attach drag listeners to `window` on pointerdown with cleanup on pointerup/cancel.
- Render vertical splitter overlays and horizontal splitter overlays positioned along track boundary percentages.
- Support double click on splitters to reset equal track widths/heights.

- [ ] **Step 2: Verify TypeScript compilation**

Run: `npx tsc --noEmit` in `frontend`
Expected: PASS

---

### Task 6: Comprehensive Verification & Build

**Files:**
- Verify all modified files

- [ ] **Step 1: Run unit tests**

Run: `npm test` in `frontend`
Expected: PASS all tests

- [ ] **Step 2: Run TypeScript compiler**

Run: `npx tsc --noEmit` in `frontend`
Expected: No errors

- [ ] **Step 3: Run production build**

Run: `npm run build` in `frontend`
Expected: Success

- [ ] **Step 4: Check git diff**

Run: `git diff` to ensure no accidental regressions
