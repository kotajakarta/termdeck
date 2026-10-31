# Cockpit-py File Manager Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the complete `cockpit-py` File Manager workflow (`FilesView`, `FilePane`, `FileDialogs`, `FileViewer`, `FileThumb`, `RemoteSync`, and `files_agent.py`) into TermDeck for both Android (Kotlin/Ktor/SSHJ) and Web/Desktop (Python/FastAPI/Paramiko).

**Architecture:** The backend runs `files_agent.py` over SSH (`python3 -c ...` with SFTP fallback when `python3` is missing) to provide full POSIX metadata (`owner`, `group`, `perms`, `mode`, `target`, `target_type`), principals (`users`, `groups`, `home`), atomic file operations (`write`, `mkdir`, `symlink`, `rename`, `paste`, `delete`, `chmod` with recursive & chown support), streaming upload with conflict detection (`409`), inline media range streaming (`view`/`thumb`), and tar-stream relay (`pack` → `unpack`) for inter-server transfers. The frontend renders `FilesView` as a full workspace mode alongside `Terminal` with dual-pane split view (`F3`), per-pane server selector, list/grid views, details panel (`F11`), media viewer, and Cockpit-style dialogs.

**Tech Stack:** TypeScript, React 18, CSS, Node.js test runner, Kotlin 1.9 + Ktor + SSHJ, Python 3.12 + FastAPI + Paramiko.

**Spec:** `docs/superpowers/specs/2026-10-28-cockpit-py-files-port-design.md`

## Global Constraints

- Zero TypeScript `any` in new frontend code (`tsc --noEmit` must pass with 0 errors).
- All protected system paths (`/`, `/bin`, `/boot`, `/dev`, `/etc`, `/home`, `/lib`, `/lib64`, `/opt`, `/proc`, `/root`, `/run`, `/sbin`, `/srv`, `/sys`, `/tmp`, `/usr`, `/var`) must be guarded against deletion or move.
- Inline media endpoints (`/api/files/{serverId}/view` and `/api/files/{serverId}/thumb`) must support query-parameter authentication (`token` & `session`) so `<img>`, `<video>`, `<audio>`, and `<iframe>` elements work in both desktop browsers and Android WebView.
- No heavy UI library (`antd`) added; use lightweight custom React primitives styled to match `cockpit-py` and TermDeck.

## Review Focus

- Symlink to directory navigation: `linkDestination(entry)` must resolve both absolute and relative symlink targets without escaping root `/`.
- Cockpit-style permission mode calculation: `buildMode(current, owner, group, other, isDir, executable)` must preserve setuid/setgid/sticky bits (`0o7000`), grant `+x` on directories whenever read is granted, and grant `+x` on files only when `executable` is checked.
- `parseJsonc` for `.vscode/sftp.json`: must strip `//` line comments, `/* */` block comments, and trailing commas without corrupting URLs or strings containing `//`.
- Protected system paths in `files_agent`: attempting `delete` or `rename`/`move` on `/etc` or `/` must fail with a clear error before touching disk.
- HTTP `Range` parsing for video/audio streaming: `bytes=0-1023`, `bytes=1024-`, and `bytes=-512` must return `206 Partial Content` with accurate `Content-Range` and `Content-Length`.

---

### Task 1: Frontend File Utilities, Types, API Client & Unit Tests

**Files:**
- Create: `frontend/src/components/files/fileUtils.ts`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/tests/fileManager.test.ts`

**Interfaces:**
- Produces:
  - `FileType`, `FileEntry`, `FileActionBody`, `FileRemote`, `FileRemoteInput`, `TransferDirection`, `TransferScan`, `TransferJob`, `DirectTransferScanIn`, `DirectTransferStartIn` in `frontend/src/types.ts`
  - `joinPath`, `parentOf`, `isDirLike`, `linkDestination`, `formatSize`, `formatDate`, `previewKind`, `thumbKind`, `fileIconKind`, `TYPE_LABEL`, `Access`, `accessOf`, `buildMode`, `isWithin`, `remotesFor`, `parseJsonc` in `frontend/src/components/files/fileUtils.ts`
  - `api.filesList`, `api.filesRead`, `api.filesPrincipals`, `api.filesAction`, `api.filesUpload`, `api.filesDownloadUrl`, `api.filesViewUrl`, `api.filesThumbUrl`, `api.filesRemotes`, `api.createFileRemote`, `api.updateFileRemote`, `api.deleteFileRemote`, `api.scanTransfer`, `api.startTransfer`, `api.getTransfer`, `api.cancelTransfer`, `api.directTransferScan`, `api.directTransferStart`, `api.getFileBookmarks`, `api.toggleFileBookmark`, `api.syncFileBookmarks` in `frontend/src/api.ts`

- [ ] **Step 1: Write unit tests in `frontend/tests/fileManager.test.ts` testing `linkDestination`, `accessOf`, `buildMode`, `previewKind`, `isWithin`, and `parseJsonc`**
- [ ] **Step 2: Run `npm test --prefix frontend` to verify tests fail before implementation**
- [ ] **Step 3: Implement `frontend/src/components/files/fileUtils.ts`, `frontend/src/types.ts`, and `frontend/src/api.ts`**
- [ ] **Step 4: Run `npm test --prefix frontend` and `./frontend/node_modules/.bin/tsc --noEmit --project frontend/tsconfig.json` to verify all tests pass**

---

### Task 2: Python & Android Backend (`files_agent.py` + `/api/files/...` + Transfer Service)

**Files:**
- Create: `backend/app/files_agent.py`
- Create: `backend/app/files_api.py`
- Modify: `backend/app/ssh.py`
- Modify: `backend/app/api.py`
- Create: `android/app/src/main/assets/files_agent.py`
- Modify: `android/app/src/main/java/id/termdeck/app/data/Models.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/ssh/ConnectionPool.kt`
- Modify: `android/app/src/main/java/id/termdeck/app/server/TermDeckServer.kt`
- Modify: `android/app/src/test/java/id/termdeck/app/ServerProtocolTest.kt`

**Interfaces:**
- Produces HTTP endpoints on both Python FastAPI and Android Ktor:
  - `GET /api/files/{serverId}/list?path=/&show_hidden=false`
  - `GET /api/files/{serverId}/principals`
  - `GET /api/files/{serverId}/read?path=...`
  - `POST /api/files/{serverId}/action` (`write`, `mkdir`, `symlink`, `rename`, `paste`, `delete`, `chmod`)
  - `POST /api/files/{serverId}/upload?dir=...&name=...&overwrite=false`
  - `GET /api/files/{serverId}/download?path=...`
  - `GET /api/files/{serverId}/view?path=...` (with HTTP `Range` support)
  - `GET /api/files/{serverId}/thumb?path=...&v=...`
  - `GET /api/files/bookmarks`, `POST /api/files/bookmarks/toggle`, `POST /api/files/bookmarks/sync`
  - `GET /api/files/remotes`, `POST /api/files/remotes`, `PUT /api/files/remotes/{id}`, `DELETE /api/files/remotes/{id}`
  - `POST /api/files/remotes/{id}/scan`, `POST /api/files/remotes/{id}/transfer`
  - `POST /api/files/direct-transfer/scan`, `POST /api/files/direct-transfer/start`
  - `GET /api/files/transfers/{jobId}`, `POST /api/files/transfers/{jobId}/cancel`

- [ ] **Step 1: Add `backend/app/files_agent.py` and `android/app/src/main/assets/files_agent.py` (with SFTP fallback when `python3` is missing on remote host)**
- [ ] **Step 2: Implement `backend/app/files_api.py` and register it in `backend/app/api.py`, plus SSH exec/stream helpers in `backend/app/ssh.py`**
- [ ] **Step 3: Implement `files_agent` execution, SFTP fallback, and transfer relay in `ConnectionPool.kt` and `/api/files/...` routes in `TermDeckServer.kt`**
- [ ] **Step 4: Verify with `python3 -m py_compile` and `./gradlew -p android testDebugUnitTest`**

---

### Task 3: Port `cockpit-py` File Manager UI Components (`FilesView`, `FilePane`, `FileDialogs`, `FileViewer`, `FileThumb`, `RemoteSync`) & Build APK

**Files:**
- Create: `frontend/src/components/files/FileThumb.tsx`
- Create: `frontend/src/components/files/FileViewer.tsx`
- Create: `frontend/src/components/files/FileDialogs.tsx`
- Create: `frontend/src/components/files/RemoteSync.tsx`
- Create: `frontend/src/components/files/FilePane.tsx`
- Create: `frontend/src/components/files/FilesView.tsx`
- Modify: `frontend/src/components/Icon.tsx`
- Modify: `frontend/src/components/TabBar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Task 1 utilities & API client, Task 2 `/api/files/...` endpoints.
- Produces: Full-workspace `FilesView` integrated into `App.tsx` with `[Terminal] [Files]` workspace switcher and `Open terminal here` callback that opens/focuses a terminal tab at the target server and directory.

- [ ] **Step 1: Create `FileThumb.tsx`, `FileViewer.tsx`, `FileDialogs.tsx`, and `RemoteSync.tsx`**
- [ ] **Step 2: Create `FilePane.tsx` and `FilesView.tsx` matching `cockpit-py`'s layout, keyboard shortcuts (`Ctrl+A/C/X/V/H`, `F2`, `F3`, `F5`, `F6`, `F11`, `Del`, `Backspace`), drag-and-drop, context menus, and mobile dual-pane switcher**
- [ ] **Step 3: Integrate `FilesView` into `App.tsx`, `TabBar.tsx`, and `styles.css`**
- [ ] **Step 4: Run `./build-android.sh` to verify all frontend unit tests, TypeScript checks, Android JUnit tests, and build `/home/aithendi/webku/xterm/dist/termdeck-android.apk`**
