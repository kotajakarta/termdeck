# TermDeck Android UX Redesign & Cockpit File Manager Design Spec

## 1. Intent & Goals
Upgrade TermDeck (especially the Android application and responsive mobile/desktop web UI) with:
1. **Cockpit-Style SFTP/SSH File Manager (`FileManager`):** Full visual file browser, text/config editor, file uploader/downloader, directory creator, rename/move, and permission (`chmod`) manager inspired by Red Hat Cockpit Navigator.
2. **Android & Split-View Ergonomics:**
   - **Pane Maximize / Restore in Split Mode:** One-tap button or double-click on pane header to temporarily maximize a single split pane to full screen without losing the split layout.
   - **Broadcast Input Mode:** Send commands, mobile keys, and snippets to all visible connected panes in split mode at once.
   - **Mobile Compose Bar & Quick Command Chips:** Optional 1-line command compose input box (supporting Android keyboard predictive text, autocorrect, and easy pasting) and scrollable quick-command/snippet chips in `MobileKeyBar`.
   - **Pinch-to-Zoom Terminal Font:** Two-finger pinch gesture on `TerminalView` to scale terminal font size naturally on Android touchscreens.
   - **Android Native Integration:** Support file downloads from the embedded server and add a quick "Kunci & Putuskan" (Lock & Disconnect) action button in the Android foreground service notification.

## 2. Architecture & API Contract
Both the Android embedded Ktor backend (`android/`) and the Python FastAPI backend (`backend/`) expose identical REST endpoints under `/api/servers/{serverId}`:

- `GET /api/servers/{serverId}/ls?path=<path>`:
  - Returns `DirListing` where each `DirEntry` includes:
    - `name: string`
    - `path: string`
    - `is_dir: boolean`
    - `size: number` (bytes, default `0`)
    - `mtime: number | null` (epoch seconds, default `null`)
    - `mode: string` (octal permission string like `"0755"` or `"0644"`, default `""`)
    - `is_symlink: boolean` (default `false`)
- `GET /api/servers/{serverId}/files/read?path=<path>`:
  - Reads a remote UTF-8 text file (up to 2 MB) and returns `FileContentOut { path, content, size, mtime, mode }`.
- `PUT /api/servers/{serverId}/files/write`:
  - Accepts `FileWriteIn { path, content }` and writes the UTF-8 content to the remote file via SFTP (or shell fallback). Returns `FileActionOut { path, message }`.
- `POST /api/servers/{serverId}/files/mkdir`:
  - Accepts `FilePathIn { path }` and creates the directory (including parents). Returns `FileActionOut { path, message }`.
- `POST /api/servers/{serverId}/files/rename`:
  - Accepts `FileRenameIn { old_path, new_path }` and renames/moves the file or folder. Returns `FileActionOut { path, message }`.
- `POST /api/servers/{serverId}/files/chmod`:
  - Accepts `FileChmodIn { path, mode }` (octal string `000`–`7777`, e.g., `"755"` or `"0644"`) and updates file permissions. Returns `FileActionOut { path, message }`.
- `DELETE /api/servers/{serverId}/files?path=<path>`:
  - Deletes a file or directory on the remote server. Returns `204 No Content`.
- `POST /api/servers/{serverId}/files/upload?path=<remoteFilePath>`:
  - Streams raw request body bytes (up to 50 MB) to `<remoteFilePath>` via SFTP. Returns `FileActionOut { path, message }`.
- `GET /api/servers/{serverId}/files/download?path=<remoteFilePath>`:
  - Streams raw file bytes with `Content-Disposition: attachment; filename="<name>"`. Accepts either `X-Token`+`X-Session` headers or `?session=<session>&token=<token>` query parameters for browser/WebView native download support.
