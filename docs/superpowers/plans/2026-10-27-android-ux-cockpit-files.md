# TermDeck Android UX Redesign & Cockpit File Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Cockpit-inspired SFTP/SSH File Manager (browse, create file/folder, edit text files, chmod permissions, rename/move, delete, upload, download) and Android mobile UX enhancements (pinch-to-zoom font, split pane maximize/restore, broadcast input mode, mobile compose box + quick command chips, and Android notification lock/download integration).

**Architecture:** Extend both the Android Ktor backend (`id.termdeck.app`) and Python FastAPI backend (`backend/app`) with identical file management endpoints backed by SFTP (with shell fallback where applicable). Build pure TypeScript helpers for Cockpit file formatting, permission octal/rwx conversion, breadcrumbs, sorting/filtering, and mobile quick commands, then integrate the `FileManager` modal/drawer and mobile terminal ergonomics into the React frontend.

**Tech Stack:** TypeScript, React 18, xterm.js, Node test runner, Kotlin 1.9, Ktor CIO, SSHJ SFTPClient, JUnit 4, Python 3 FastAPI/AsyncSSH.

**Spec:** `docs/superpowers/specs/2026-10-27-android-ux-cockpit-files-design.md`

## Global Constraints

- Text file read/edit limit: `MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024` (2 MB).
- File upload limit: `MAX_UPLOAD_FILE_BYTES = 50 * 1024 * 1024` (50 MB).
- Permission mode validation: octal string matching `^[0-7]{3,4}$`.
- Zero `any` in TypeScript; all frontend tests (`npm test --prefix frontend`) and `npx tsc --noEmit` must pass.
- All Android JUnit tests (`./gradlew testDebugUnitTest`) and APK assembly (`./build-android.sh`) must pass.

## Review Focus

- Octal permission conversion (`modeToRwx`, `rwxToMode`, `validateOctalMode`) handles both 3-digit (`755`) and 4-digit (`0755`) strings and rejects non-octal characters.
- Breadcrumb path splitting handles root `/`, relative `~`, and nested paths `/var/www/html` without empty segments.
- File sorting and filtering places directories first and filters by case-insensitive substring and hidden dotfile toggle.
- Remote file operations reject empty paths and enforce size limits before loading large binaries into memory as text.
- Broadcast mode sends input to all connected visible panes while single-pane mode sends only to the active pane.

---

### Task 1: Frontend Cockpit File Manager Pure Logic & Unit Tests (`fileManager.ts`)

**Files:**
- Create: `frontend/src/fileManager.ts`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api.ts`
- Test: `frontend/tests/fileManager.test.ts`

**Interfaces:**
- Produces:
  - `splitBreadcrumbs(path: string): { label: string; path: string }[]`
  - `parentDirPath(path: string): string`
  - `joinRemotePath(dir: string, name: string): string`
  - `modeToRwx(mode: string, isDir: boolean): string`
  - `parseModeBits(mode: string): { owner: [boolean, boolean, boolean]; group: [boolean, boolean, boolean]; other: [boolean, boolean, boolean] }`
  - `bitsToMode(bits: { owner: [boolean, boolean, boolean]; group: [boolean, boolean, boolean]; other: [boolean, boolean, boolean] }): string`
  - `filterAndSortEntries(entries: DirEntry[], opts: { query: string; showHidden: boolean; sortBy: "name" | "size" | "mtime"; sortAsc: boolean }): DirEntry[]`
  - `QUICK_TERMINAL_COMMANDS: { label: string; command: string }[]`

- [ ] **Step 1: Write failing tests in `frontend/tests/fileManager.test.ts`**
- [ ] **Step 2: Run `npm test --prefix frontend` to verify it fails**
- [ ] **Step 3: Implement `frontend/src/fileManager.ts`, `frontend/src/types.ts`, and `frontend/src/api.ts`**
- [ ] **Step 4: Run `npm test --prefix frontend` and `npx tsc --noEmit --project frontend/tsconfig.json` to verify PASS**

---

### Task 2: Android Backend & Python Backend Cockpit File Management Endpoints + Tests

**Files:**
- Modify: `android/app/src/main/java/id/termdeck/app/data/Models.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/ssh/SshOperations.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/ssh/ConnectionPool.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/server/TermDeckServer.kt`
- Modify: `android/app/src/test/java/id/termdeck/app/SshOperationsTest.kt`
- Modify: `android/app/src/test/java/id/termdeck/app/ServerProtocolTest.kt`
- Modify: `backend/app/schemas.py`
- Modify: `backend/app/ssh.py`
- Modify: `backend/app/api.py`

**Interfaces:**
- Produces:
  - `GET /api/servers/{serverId}/files/read?path=...` -> `FileContentOut`
  - `PUT /api/servers/{serverId}/files/write` -> `FileActionOut`
  - `POST /api/servers/{serverId}/files/mkdir` -> `FileActionOut`
  - `POST /api/servers/{serverId}/files/rename` -> `FileActionOut`
  - `POST /api/servers/{serverId}/files/chmod` -> `FileActionOut`
  - `DELETE /api/servers/{serverId}/files?path=...` -> `204 No Content`
  - `POST /api/servers/{serverId}/files/upload?path=...` -> `FileActionOut`
  - `GET /api/servers/{serverId}/files/download?path=...` -> raw bytes attachment

- [ ] **Step 1: Write failing unit tests in `SshOperationsTest.kt` and `ServerProtocolTest.kt`**
- [ ] **Step 2: Implement file models, SFTP/shell helpers, and Ktor + FastAPI endpoints**
- [ ] **Step 3: Run Android JUnit tests to verify PASS**

---

### Task 3: Cockpit File Manager UI & Android Mobile UX Enhancements

**Files:**
- Create: `frontend/src/components/FileManager.tsx`
- Modify: `frontend/src/components/Icon.tsx`
- Modify: `frontend/src/components/MobileKeyBar.tsx`
- Modify: `frontend/src/components/TabBar.tsx`
- Modify: `frontend/src/components/TerminalView.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`
- Modify: `android/app/src/main/java/id/termdeck/app/MainActivity.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/TermDeckService.kt`

- [ ] **Step 1: Create `FileManager.tsx` with Cockpit breadcrumbs, search/sort/hidden toggles, file viewer/editor, chmod modal, mkdir/rename/delete, upload/download, and "Buka Terminal di Sini"**
- [ ] **Step 2: Add pinch-to-zoom font in `TerminalView.tsx`, Compose Input Bar + Quick Commands in `MobileKeyBar.tsx`, Broadcast Input + File Manager button in `TabBar.tsx`, and Split Pane Maximize/Restore in `App.tsx`**
- [ ] **Step 3: Add native download support and notification "Kunci & Putuskan" action in `MainActivity.kt` and `TermDeckService.kt`**
- [ ] **Step 4: Run `./build-android.sh` to verify all frontend tests, TypeScript check, Android JUnit tests, and APK build succeed**
