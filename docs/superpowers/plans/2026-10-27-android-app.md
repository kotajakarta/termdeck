# TermDeck Android Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, lightweight, and secure Android application (`id.termdeck.app`) for TermDeck with 100% feature parity (SSH password/key auth, TOFU host key verification, connection pooling, PTY terminal, SFTP/shell folder browser, image paste upload, live server stats, and scrypt + SQLCipher AES-256 PIN vault) alongside mobile-responsive drawer navigation and a touch terminal key bar.

**Architecture:** Enhance the existing React + xterm.js frontend (`frontend/`) with a slide-out mobile sidebar drawer, touch-friendly action buttons, and a `MobileKeyBar` component backed by pure modifier/key-sequence helpers (`mobileKeys.ts`). Package the compiled web UI inside a native Kotlin Android application (`android/`) that runs an embedded `127.0.0.1` Ktor CIO HTTP/WebSocket backend implementing the exact TermDeck REST/WebSocket API using SQLCipher, BouncyCastle scrypt, and SSHJ. Provide a one-command `./build-android.sh` script that sets up the portable JDK/Android SDK if needed, runs unit tests, and outputs `dist/termdeck-android.apk`.

**Tech Stack:** React 18, TypeScript, Vite, xterm.js, Kotlin 1.9, Android SDK 34 (minSdk 26), Ktor CIO Server & WebSockets, SQLCipher for Android, SSHJ + BouncyCastle + EdDSA, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-27-android-app-design.md`

## Global Constraints

- Preserve 100% compatibility with desktop browser mode (`./run.sh` and `backend/` must continue to pass all tests and work identically).
- All TypeScript code must pass `npx tsc --noEmit` with zero `any` types.
- All user-facing strings and error messages must remain in Indonesian matching `backend/app/api.py`, `backend/app/ssh.py`, and `backend/app/vault.py`.
- The embedded Android HTTP/WebSocket server must bind exclusively to `127.0.0.1` and enforce `X-Token` and `X-Session` headers on all API routes and session validation on `/ws/terminal`.
- PIN key derivation must use `scrypt(pin, salt, N=65536, r=8, p=1, dkLen=32)` with a 32-byte random salt stored in `vault.key` and AES-256 SQLCipher encryption in `termdeck.vault`.

## Review Focus

1. **Sticky Ctrl/Alt modifiers on soft keyboard input**: When `Ctrl` or `Alt` is toggled on `MobileKeyBar` and the user types a character on the Android soft keyboard inside xterm.js, the character must be transformed into its control/meta sequence (e.g. `Ctrl` + `c` → `\x03`) and the sticky modifier must reset automatically.
2. **Application Cursor Keys mode (`DECCKM`)**: Arrow keys (`↑`, `↓`, `←`, `→`) and `Home`/`End` on `MobileKeyBar` must send `\x1bOA`–`\x1bOD` when `vim`/`htop`/`less` enables application cursor mode, and `\x1b[A`–`\x1b[D` in normal mode.
3. **TOFU Host Key verification before authentication**: When connecting to a server whose stored `host_key` differs from the remote server's presented key, SSHJ must abort before sending password or private key signature and return the Indonesian `HostKeyMismatch` message.
4. **Secret merging on server update**: Updating a server with `password = null` or `private_key = null` must keep the existing stored secret, while `""` clears it, and changing `host` or `port` must clear the stored `host_key` and `host_key_fp`.
5. **PIN brute-force throttling**: After 5 failed PIN attempts, subsequent attempts must be rejected with HTTP 429 and `Retry-After` header for 30s, doubling on each additional failure up to 900s, even before running `scrypt`.

---

### Task 1: Mobile Terminal Key Helpers (`frontend/src/mobileKeys.ts`)

**Files:**
- Create: `frontend/src/mobileKeys.ts`
- Create: `frontend/tests/mobileKeys.test.ts`

**Interfaces:**
- Produces:
  ```typescript
  export interface ModifierState {
    ctrl: boolean;
    alt: boolean;
  }

  export type SpecialKey =
    | "Esc"
    | "Tab"
    | "Up"
    | "Down"
    | "Left"
    | "Right"
    | "Home"
    | "End"
    | "PgUp"
    | "PgDn"
    | "CtrlC"
    | "CtrlD"
    | "CtrlZ"
    | "CtrlL"
    | "Hyphen"
    | "Slash"
    | "Pipe"
    | "Tilde";

  export function toCtrlChar(ch: string): string;
  export function applyModifiers(
    input: string,
    mods: ModifierState,
  ): { data: string; consumed: boolean };
  export function specialKeySequence(
    key: SpecialKey,
    appCursor?: boolean,
    mods?: ModifierState,
  ): string;
  ```

- [ ] **Step 1: Write failing unit tests in `frontend/tests/mobileKeys.test.ts`**

Test:
- `toCtrlChar("c")` and `toCtrlChar("C")` return `"\x03"`, `toCtrlChar("a")` returns `"\x01"`, `toCtrlChar("z")` returns `"\x1a"`, `toCtrlChar("/")` returns `"\x1f"`.
- `applyModifiers("c", { ctrl: true, alt: false })` returns `{ data: "\x03", consumed: true }`.
- `applyModifiers("f", { ctrl: false, alt: true })` returns `{ data: "\x1bf", consumed: true }`.
- `applyModifiers("hello", { ctrl: false, alt: false })` returns `{ data: "hello", consumed: false }`.
- `specialKeySequence("Up", false)` returns `"\x1b[A"` and `specialKeySequence("Up", true)` returns `"\x1bOA"`.
- `specialKeySequence("Tab", false)` returns `"\t"`, `specialKeySequence("Esc", false)` returns `"\x1b"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test --prefix frontend`
Expected: FAIL (`Cannot find module '../src/mobileKeys.ts'`)

- [ ] **Step 3: Implement `frontend/src/mobileKeys.ts`**

Implement `toCtrlChar`, `applyModifiers`, and `specialKeySequence` with full TypeScript types and zero `any`.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test --prefix frontend`
Expected: PASS all tests in `frontend/tests/*.test.ts`.

---

### Task 2: Mobile Touch Key Bar & Responsive Drawer UI (`frontend/`)

**Files:**
- Create: `frontend/src/components/MobileKeyBar.tsx`
- Modify: `frontend/src/components/Icon.tsx`
- Modify: `frontend/src/components/TerminalView.tsx`
- Modify: `frontend/src/components/TabBar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: `ModifierState`, `SpecialKey`, `applyModifiers`, `specialKeySequence` from `../mobileKeys`
- Produces:
  - `<MobileKeyBar>`: renders touch bar for `Esc`, `Tab`, sticky `Ctrl`, sticky `Alt`, `↑`, `↓`, `←`, `→`, `Home`, `End`, `PgUp`, `PgDn`, `-`, `/`, `|`, `~`, `^C`, `Alt+P`, `Alt+J`, clipboard paste, and image upload picker.
  - Responsive drawer state `sidebarOpen` in `App.tsx` with toggle button in `TabBar.tsx`.

- [ ] **Step 1: Add `menu` and `image` icons to `frontend/src/components/Icon.tsx`**

Add SVG path definitions for `menu` (`M4 6h16M4 12h16M4 18h16`) and `image` (`M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm3.5 5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM21 15l-5-5L5 19`).

- [ ] **Step 2: Update `TerminalView.tsx` to support sticky modifiers and external key/image actions**

Extend `Sender` registration or `TerminalControl` interface so the active terminal exposes:
- `send(data: string): void`
- `sendSpecial(key: SpecialKey, mods: ModifierState): void` (respecting `term.modes.applicationCursorKeysMode`)
- `pasteFiles(files: File[]): void`
- Intercept `term.onData` when sticky `mods.ctrl` or `mods.alt` is active so typing a key on the Android soft keyboard applies the modifier and clears it.

- [ ] **Step 3: Create `frontend/src/components/MobileKeyBar.tsx`**

Build the touch toolbar component with `onPointerDown={(e) => e.preventDefault()}` on buttons so tapping terminal keys never steals focus from the Android soft keyboard / xterm textarea, plus a hidden `<input type="file" accept="image/*" multiple>` for uploading images from Android gallery.

- [ ] **Step 4: Wire mobile sidebar drawer and `MobileKeyBar` in `TabBar.tsx`, `App.tsx`, and `styles.css`**

- Add hamburger menu button in `TabBar.tsx` (`onToggleSidebar`).
- In `App.tsx`, manage `sidebarOpen` state; auto-close drawer when `openTab` or `selectTab` is triggered on mobile; render `<MobileKeyBar>` beneath `.panes` when `activeTab` is present.
- In `styles.css`, add `@media (max-width: 760px)` rules for `.sidebar` off-canvas drawer, `.sidebar-backdrop`, always-visible `.row-actions` on `(hover: none)`, and `.mobile-keybar` styling.

- [ ] **Step 5: Verify frontend tests, types, and production build**

Run: `npm test --prefix frontend && npx tsc --noEmit --project frontend/tsconfig.json && npm run build --prefix frontend`
Expected: PASS all unit tests, 0 TypeScript errors, and `frontend/dist` generated cleanly.

---

### Task 3: Native Android Core Logic — Models, SSH Operations & Vault Throttling (`android/`)

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/id/termdeck/app/data/Models.kt`
- Create: `android/app/src/main/java/id/termdeck/app/ssh/SshOperations.kt`
- Create: `android/app/src/main/java/id/termdeck/app/data/PinLogic.kt`
- Create: `android/app/src/test/java/id/termdeck/app/SshOperationsTest.kt`
- Create: `android/app/src/test/java/id/termdeck/app/PinLogicTest.kt`

**Interfaces:**
- Produces:
  - Data classes in `Models.kt` matching `backend/app/schemas.py`.
  - Pure functions in `SshOperations.kt`: `quoteRemotePath`, `buildCdCommand`, `sniffImageType`, `pasteImageName`, `parseStats`, `parseShellLs`, `HostKeyMismatch`.
  - Pure PIN & throttle logic in `PinLogic.kt`: `validatePin`, `deriveVaultKey`, `PinThrottle`.

- [ ] **Step 1: Scaffold Gradle project and write JUnit tests (`SshOperationsTest.kt` and `PinLogicTest.kt`)**

Test:
- `quoteRemotePath` and `buildCdCommand` for `~`, `~/project`, `/var/www/my app`, and startup commands.
- `sniffImageType` for PNG, JPEG, GIF, WebP magic bytes and rejection of invalid bytes.
- `parseStats` on sample Linux `/proc/uptime`, `/proc/loadavg`, `/proc/meminfo`, `df -Pk /` output.
- `deriveVaultKey` producing exact 32-byte key via scrypt (`N=65536, r=8, p=1`) matching Python's `hashlib.scrypt`.
- `PinThrottle` allowing 5 attempts and blocking for 30s, 60s, ..., up to 900s.

- [ ] **Step 2: Implement `Models.kt`, `SshOperations.kt`, and `PinLogic.kt`**

Implement all models with `kotlinx.serialization`, POSIX shell quoting, stats parser, image sniffer, scrypt KDF using BouncyCastle `SCrypt.generate`, and `PinThrottle`.

---

### Task 4: Native Android SQLCipher Store, SSHJ ConnectionPool & Ktor Server (`android/`)

**Files:**
- Create: `android/app/src/main/java/id/termdeck/app/data/Store.kt`
- Create: `android/app/src/main/java/id/termdeck/app/data/Vault.kt`
- Create: `android/app/src/main/java/id/termdeck/app/ssh/ConnectionPool.kt`
- Create: `android/app/src/main/java/id/termdeck/app/server/TermDeckServer.kt`
- Create: `android/app/src/test/java/id/termdeck/app/ServerProtocolTest.kt`

**Interfaces:**
- Consumes: `Models.kt`, `SshOperations.kt`, `PinLogic.kt`
- Produces:
  - `Store`: SQLCipher database wrapper implementing `listServers`, `getServer`, `getServerSecrets`, `createServer`, `updateServer`, `reorderServers`, `deleteServer`, `setHostKey`, `listBookmarks`, `getBookmark`, `createBookmark`, `updateBookmark`, `deleteBookmark`, `listSnippets`, `createSnippet`, `updateSnippet`, `deleteSnippet`, `rekey`, `close`.
  - `Vault`: thread-safe PIN unlock/lock/changePin and session manager.
  - `ConnectionPool`: SSHJ connection pool with TOFU host key verifier, PTY shell opener, SFTP + shell `ls` directory listing, SFTP image upload, and server stats runner.
  - `TermDeckServer`: Ktor CIO server on `127.0.0.1` serving `assets/webapp` (injecting `<meta name="termdeck-token">`) and all `/api/*` + `/ws/terminal` routes.

- [ ] **Step 1: Implement `Store.kt` and `Vault.kt`**

Use `net.zetetic.database.sqlcipher.SQLiteDatabase` with raw hex key pragma `PRAGMA key = "x'${keyHex}'"` and `PRAGMA rekey = "x'${newKeyHex}'"`, matching `backend/app/db.py`.

- [ ] **Step 2: Implement `ConnectionPool.kt` with SSHJ, BouncyCastle & EdDSA**

Implement TOFU host key verification (`OpenSSHKeyV1KeyFile` / public key comparison & SHA256 fingerprint), password & private key auth, 90s idle connection reaper, PTY session creation (`xterm-256color`), SFTP directory listing with shell fallback, SFTP paste upload, and stats execution.

- [ ] **Step 3: Implement `TermDeckServer.kt` and `ServerProtocolTest.kt`**

Implement all REST routes (`/api/auth/*`, `/api/servers*`, `/api/bookmarks*`, `/api/snippets*`) and `/ws/terminal` WebSocket route with identical status codes and Indonesian messages.

---

### Task 5: Android Activity, Foreground Service, WebView & One-Command APK Builder

**Files:**
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/id/termdeck/app/MainActivity.kt`
- Create: `android/app/src/main/java/id/termdeck/app/TermDeckService.kt`
- Create: `android/app/src/main/res/values/styles.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `android/app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `build-android.sh`
- Modify: `README.md`
- Modify: `.gitignore`

- [ ] **Step 1: Implement `MainActivity.kt`, `TermDeckService.kt`, `AndroidManifest.xml`, and Android resources**

Configure `WebView` with dark background (`#18211f`), `javaScriptEnabled = true`, `domStorageEnabled = true`, file chooser support (`ActivityResultContracts.StartActivityForResult`) for private keys and images, external URL opening, back-button handling, and `TermDeckService` foreground notification so active SSH connections stay alive when switching apps.

- [ ] **Step 2: Create `build-android.sh` and update `.gitignore` & `README.md`**

Create `./build-android.sh` to:
1. Build frontend assets (`npm run build --prefix frontend`) and sync to `android/app/src/main/assets/webapp`.
2. Ensure JDK 17 and Android SDK (`platforms;android-34`, `build-tools;34.0.0`) are available (auto-downloading to `~/.local/share/termdeck-android-sdk` if absent).
3. Run JUnit tests (`./gradlew testDebugUnitTest`) and build APK (`./gradlew assembleDebug`).
4. Copy the resulting APK to `dist/termdeck-android.apk`.

- [ ] **Step 3: Run full test suite & build APK**

Run:
- `npm test --prefix frontend`
- `npx tsc --noEmit --project frontend/tsconfig.json`
- `backend/.venv/bin/python -m pytest backend/tests`
- `./build-android.sh`
Expected: All frontend tests, backend tests, Android JUnit tests pass, and `dist/termdeck-android.apk` is created and verified.
