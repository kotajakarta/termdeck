# Windows 11 Snap Layouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Windows 11 Snap Layouts for TermDeck terminal panels, featuring 9 layout presets, interactive visual cards with zone hover highlights, direct tab placement into clicked zones, and dynamic asymmetric CSS Grid rendering.

**Architecture:** Extend `layout.ts` with structured `LayoutDefinition` and `setLayoutWithSlot` supporting non-uniform CSS Grid Areas. Build a dedicated `SnapLayoutPicker.tsx` component matching the Windows 11 flyout aesthetic. Wire `App.tsx` and `TabBar.tsx` to apply selected layout templates and target slot positions to the workspace while keeping active SSH terminal sessions intact.

**Tech Stack:** React 18, TypeScript, Vite, CSS Grid Areas, xterm.js, Node.js test runner.

**Spec:** `docs/superpowers/specs/2026-10-17-windows-snap-layouts-design.md`

## Global Constraints

- Do not disrupt existing SSH WebSocket sessions or unmount active terminals when changing layout.
- Maintain full backward compatibility with existing `localStorage` keys (`termdeck.layout`) and existing presets (`1`, `2v`, `2h`, `3v`, `4`, custom `RxC`).
- Ensure all TypeScript code is strictly typed with zero `any`.
- Indonesian localization for all user-facing labels and tooltips.
- Follow TDD with Node test runner (`npm test`) and verify with `npx tsc --noEmit` and `npm run build`.

## Review Focus

1. **Unknown or legacy layout strings in localStorage**: Must fall back safely to preset "1" without throwing runtime exceptions.
2. **Tab allocation on layout switch with target slot**: The currently active tab MUST be placed into `targetSlot`, and any other already visible tabs should remain visible where possible, while filling newly created slots with hidden tabs without duplicate tab IDs.
3. **Asymmetric Grid Area rendering**: Layouts like `win-3-left-tall` (`"p0 p1" "p0 p2"`) must correctly map each pane's `gridArea` (`p0`, `p1`, `p2`) without collapsing heights or overflowing containers.
4. **Resizer stability on asymmetric layouts**: Dragging column or row splitters must work smoothly and reset cleanly to default sizes upon double-click.
5. **Popover accessibility & outside click**: Clicking outside the Snap Layouts popover or pressing `Escape` must close the popover cleanly.

---

### Task 1: Extend `layout.ts` with Windows 11 Snap Presets & `setLayoutWithSlot`

**Files:**
- Modify: `frontend/src/layout.ts`
- Modify: `frontend/tests/layout.test.ts`

**Interfaces:**
- Produces:
  ```typescript
  export type LayoutKind =
    | "1"
    | "2v"
    | "2h"
    | "3v"
    | "4"
    | "win-2-left-wide"
    | "win-2-right-wide"
    | "win-3-left-tall"
    | "win-3-right-tall"
    | "win-3-center-focus"
    | `${number}x${number}`
    | string;

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

  export function getLayoutDefinition(layout: LayoutKind): LayoutDefinition;
  export function setLayoutWithSlot(state: PaneState, layout: LayoutKind, targetSlot: number, allTabIds: string[]): PaneState;
  ```

- [ ] **Step 1: Write failing tests in `frontend/tests/layout.test.ts`**

Add tests covering:
- `getLayoutDefinition("win-3-left-tall")` returning correct `slots: 3`, `cols: 2`, `rows: 2`, `areas: '"p0 p1" "p0 p2"'`, and 3 zones.
- `getLayoutDefinition("win-2-left-wide")` returning `defaultCols: [67, 33]`.
- `setLayoutWithSlot` when moving active tab to slot 1 in a 3-slot layout puts the active tab in slot 1 and sets `focused: 1`.
- `layoutDimensions` backward compatibility.

- [ ] **Step 2: Run test to verify failure**

Run: `npm test --prefix frontend`
Expected: FAIL due to missing `getLayoutDefinition`, `setLayoutWithSlot`, or new preset kinds.

- [ ] **Step 3: Implement preset definitions and functions in `frontend/src/layout.ts`**

- Define `SNAP_LAYOUTS` constant array with all 9 Windows 11 presets:
  1. `2v` (50 / 50)
  2. `win-3-left-tall` (left 50% tall, right top 50%, right bottom 50%)
  3. `4` (2x2 grid)
  4. `win-2-right-wide` (left 33%, right 67%)
  5. `win-2-left-wide` (left 67%, right 33%)
  6. `3v` (33.3 / 33.3 / 33.3)
  7. `win-3-right-tall` (left top 50%, left bottom 50%, right 50% tall)
  8. `win-3-center-focus` (left 25%, center 50%, right 25%)
  9. `2h` (top 50%, bottom 50%)
- Implement `getLayoutDefinition(layout: LayoutKind): LayoutDefinition`.
- Update `layoutDimensions` to pull from `getLayoutDefinition`.
- Implement `setLayoutWithSlot(state: PaneState, layout: LayoutKind, targetSlot: number, allTabIds: string[]): PaneState`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test --prefix frontend`
Expected: PASS all tests.

- [ ] **Step 5: Verify types with tsc**

Run: `npx tsc --noEmit --project frontend/tsconfig.json`
Expected: PASS with 0 errors.

---

### Task 2: Build `SnapLayoutPicker.tsx` and integrate into `TabBar.tsx`

**Files:**
- Create: `frontend/src/components/SnapLayoutPicker.tsx`
- Modify: `frontend/src/components/TabBar.tsx`
- Modify: `frontend/src/components/Icon.tsx`

**Interfaces:**
- Consumes: `SNAP_LAYOUTS`, `LayoutDefinition`, `LayoutKind` from `../layout`
- Produces: `<SnapLayoutPicker currentLayout={layout} onSelectSnap={(kind, slotIndex) => ...} onClose={() => ...} />`

- [ ] **Step 1: Create `frontend/src/components/SnapLayoutPicker.tsx`**

- Renders popover container styled as Windows 11 Snap flyout.
- Maps `SNAP_LAYOUTS` into cards (3 columns of cards).
- Each card has CSS grid according to `card.cols` and `card.rows`.
- Each zone button has `gridColumn: ${zone.colStart} / span ${zone.colSpan}`, `gridRow: ${zone.rowStart} / span ${zone.rowSpan}`.
- Displays zone tooltip or title (e.g. "Kiri (50%)", "Kanan Atas").
- On zone click: calls `onSelectSnap(layout.kind, zone.slotIndex)`.
- Includes header with title "Tata Letak Windows" and a button to switch to manual matrix grid if needed.

- [ ] **Step 2: Add Snap Layout icon if needed in `frontend/src/components/Icon.tsx`**

Ensure `layoutSnap` or layout icon is present and clean.

- [ ] **Step 3: Update `frontend/src/components/TabBar.tsx`**

- Replace or augment the layout button group:
  - Add primary "Snap Layouts" button showing the current layout icon or Snap icon.
  - Clicking toggles `showSnapPicker`.
  - Pass `onLayout: (layout: LayoutKind, targetSlot?: number) => void` up to parent.
  - Close on outside click or `Escape`.

- [ ] **Step 4: Verify TypeScript compilation**

Run: `npx tsc --noEmit --project frontend/tsconfig.json`
Expected: PASS with 0 errors.

---

### Task 3: Dynamic Grid Rendering and Resizers in `App.tsx` and `styles.css`

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: `getLayoutDefinition`, `setLayoutWithSlot`, `layoutDimensions`
- Renders: `.panes` with exact `gridTemplateAreas`, `gridTemplateColumns`, `gridTemplateRows`

- [ ] **Step 1: Update `App.tsx` layout change handler**

- Update `changeLayout`:
  ```typescript
  const changeLayout = useCallback(
    (kind: LayoutKind, targetSlot?: number) => {
      setPanes((prev) => {
        if (targetSlot !== undefined) {
          return setLayoutWithSlot(prev, kind, targetSlot, tabs.map((t) => t.id));
        }
        return setLayout(prev, kind, tabs.map((t) => t.id));
      });
      const def = getLayoutDefinition(kind);
      setColSizes([...def.defaultCols]);
      setRowSizes([...def.defaultRows]);
      writeString(LAYOUT_KEY, kind);
    },
    [tabs],
  );
  ```
- Ensure `.panes` inline style uses `dims.areas` directly:
  `gridTemplateAreas: dims.areas`
- Keep `colSizes` and `rowSizes` synchronized with `def.defaultCols` and `def.defaultRows`.

- [ ] **Step 2: Add CSS rules in `frontend/src/styles.css`**

- Style `.snap-picker-popover`:
  - Background `#1e2024` with backdrop blur, border `1px solid #333842`, box-shadow `0 16px 36px rgba(0,0,0,0.5)`.
  - Card container: 3 columns grid, gap 10px.
  - `.snap-card`: size ~84px × 56px, background `#141618`, border `1px solid #2a2e35`, border-radius 6px, padding 3px, gap 3px.
  - `.snap-zone`: background `#2f333a`, border-radius 3px, transition `background 0.15s, transform 0.15s`, cursor pointer.
  - `.snap-zone:hover`: background `#0078d4` (Windows blue), scale up slightly.
  - Active layout card highlight.

- [ ] **Step 3: Verify TypeScript compilation**

Run: `npx tsc --noEmit --project frontend/tsconfig.json`
Expected: PASS with 0 errors.

---

### Task 4: Comprehensive Verification & Build

**Files:**
- All modified and created files

- [ ] **Step 1: Run unit tests**

Run: `npm test --prefix frontend`
Expected: PASS all tests (both existing and new tests).

- [ ] **Step 2: Run TypeScript compiler**

Run: `npx tsc --noEmit --project frontend/tsconfig.json`
Expected: 0 errors.

- [ ] **Step 3: Run production build**

Run: `npm run build --prefix frontend`
Expected: Build passes with zero errors.

- [ ] **Step 4: Check git diff**

Run: `git diff` to review all changes.
