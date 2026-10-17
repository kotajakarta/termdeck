export type AuthType = "password" | "key";

export interface Server {
  id: number;
  name: string;
  host: string;
  port: number;
  username: string;
  auth_type: AuthType;
  group_name: string;
  color: string;
  has_password: boolean;
  has_private_key: boolean;
  host_key_fingerprint: string | null;
}

export interface ServerInput {
  name: string;
  host: string;
  port: number;
  username: string;
  auth_type: AuthType;
  /** null keeps the stored secret on update */
  password: string | null;
  private_key: string | null;
  passphrase: string | null;
  group_name: string;
  color: string;
}

export interface Bookmark {
  id: number;
  server_id: number;
  name: string;
  path: string;
  startup_cmd: string;
}

export type BookmarkInput = Omit<Bookmark, "id">;

export interface Snippet {
  id: number;
  name: string;
  command: string;
}

export type SnippetInput = Omit<Snippet, "id">;

export interface DirEntry {
  name: string;
  path: string;
  is_dir: boolean;
}

export interface DirListing {
  path: string;
  entries: DirEntry[];
}

export interface ServerStats {
  hostname: string;
  os: string;
  kernel: string;
  uptime_seconds: number | null;
  load: number[];
  cpu_count: number | null;
  mem_total: number | null;
  mem_used: number | null;
  disk_total: number | null;
  disk_used: number | null;
}

export interface ConnectionCheck {
  ok: boolean;
  message: string;
}

export type TabStatus = "connecting" | "connected" | "closed" | "error";

export interface Tab {
  id: string;
  serverId: number;
  bookmarkId: number | null;
  title: string;
  status: TabStatus;
  /** bumped to force a fresh SSH session in the same tab */
  session: number;
}

/** Control frames sent by the backend as text over the terminal WebSocket. */
export type ControlMessage =
  | { type: "status"; status: "connecting" | "connected" }
  | { type: "error"; message: string }
  | { type: "exit"; code: number | null };

export interface AuthStatus {
  unlocked: boolean;
  session_valid: boolean;
}
