# Containers Module Table View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the container list into an organized, responsive columnar data table with quick operational actions and a view toggle.

**Architecture:** Refactor `ContainerList.tsx` to render a structured table view by default with columns (Status, Name & ID, Image, Ports, State/Created, Actions), add a view switcher (Table vs Card), and polish CSS styling in `styles.css` for clean alignment, badge styling, and action buttons.

**Tech Stack:** React 18, TypeScript, CSS3, Vite

**Spec:** `docs/superpowers/specs/2026-10-30-containers-table-view-design.md`

## Global Constraints
- Do not introduce breaking changes to the existing `ContainerItem` or API action signatures.
- Retain all existing container lifecycle actions (Start, Stop, Restart, Pause, Unpause, Logs, Exec, Delete).
- Strict TypeScript verification: `npm --prefix frontend run typecheck` must pass with 0 errors.
- Test suites: `npm --prefix frontend test` must pass.

## Review Focus
- Containers with multiple ports or empty ports formatting properly without overflow.
- Long container names and image paths truncating or wrapping cleanly with tooltips.
- Visual loading states when an action (e.g. Stop, Start, Delete) is busy for a particular container.
- Clean responsive layout on both desktop and mobile viewports.
- Copy container ID functionality working smoothly.

---

### Task 1: Update CSS Styles for Table View and Action Buttons

**Files:**
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Existing CSS variables and `.containers-table` styling.
- Produces: Enhanced CSS classes for container table rows, status pills, port badges, view toggle buttons, and action button groups.

- [ ] **Step 1: Inspect existing table styles and container classes**
- [ ] **Step 2: Add CSS rules for container table layout, view toggle, and compact action buttons**
- [ ] **Step 3: Verify CSS syntax by checking frontend build / assets**

---

### Task 2: Refactor `ContainerList.tsx` with Table View & Quick Actions

**Files:**
- Modify: `frontend/src/components/containers/ContainerList.tsx`

**Interfaces:**
- Consumes: `ContainerItem`, `ContainerActionBody`, `Icon` component.
- Produces: Updated `ContainerList` component rendering structured columnar table and view mode switcher.

- [ ] **Step 1: Add viewMode state (`table` | `cards`) with default `table`**
- [ ] **Step 2: Implement the table view JSX with columns: Status, Container Name & ID, Image, Ports, Uptime/Created, Actions**
- [ ] **Step 3: Implement copy-ID helper and tooltips for smooth operation**
- [ ] **Step 4: Keep card view as an alternative toggle option**
- [ ] **Step 5: Run TypeScript typecheck to verify types**

---

### Task 3: Verification & Polish

**Files:**
- Verify: `frontend/src/components/containers/ContainerList.tsx`
- Verify: `frontend/src/styles.css`

- [ ] **Step 1: Run `npm --prefix frontend test` to ensure existing unit tests pass**
- [ ] **Step 2: Run `npm --prefix frontend run typecheck` to ensure 0 TypeScript errors**
- [ ] **Step 3: Run `npm --prefix frontend run build` to ensure production bundle compiles successfully**
- [ ] **Step 4: Check git diff for cleanliness and absence of placeholders**
