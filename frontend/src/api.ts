import type {
  AuthStatus,
  Bookmark,
  BookmarkInput,
  ConnectionCheck,
  DirListing,
  Server,
  ServerInput,
  ServerStats,
  Snippet,
  SnippetInput,
} from "./types";

/** Session token: embedded in the page by the backend; `?token=` is the Vite dev-server fallback. */
export const token =
  document.querySelector<HTMLMetaElement>('meta[name="termdeck-token"]')?.content ??
  new URLSearchParams(window.location.search).get("token") ??
  "";

const SESSION_KEY = "termdeck.session";

function readSession(): string {
  try {
    return sessionStorage.getItem(SESSION_KEY) ?? "";
  } catch {
    return "";
  }
}

/** PIN session: kept per browser tab so a reload stays unlocked but a new tab asks for the PIN. */
let session = readSession();

export function getSession(): string {
  return session;
}

export function setSession(value: string): void {
  session = value;
  try {
    if (value) sessionStorage.setItem(SESSION_KEY, value);
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // Storage unavailable: the session still lives in memory for this page.
  }
}

let unauthorizedHandler: (() => void) | null = null;

/** Called when the backend rejects the session (locked elsewhere or restarted). */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

interface ValidationIssue {
  loc?: (string | number)[];
  msg?: string;
}

function describeDetail(detail: unknown, fallback: string): string {
  if (typeof detail === "string") return detail;
  if (Array.isArray(detail)) {
    return detail
      .map((item: ValidationIssue) => {
        const field = item.loc?.[item.loc.length - 1];
        return field ? `${field}: ${item.msg ?? ""}` : (item.msg ?? "");
      })
      .join("; ");
  }
  return fallback;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: {
      "X-Token": token,
      "X-Session": session,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let detail: unknown = null;
    try {
      detail = ((await res.json()) as { detail?: unknown }).detail;
    } catch {
      detail = null;
    }
    if (res.status === 401 && path !== "/api/auth/login") {
      setSession("");
      unauthorizedHandler?.();
    }
    throw new ApiError(describeDetail(detail, `Permintaan gagal (${res.status})`), res.status);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  authStatus: () => request<AuthStatus>("GET", "/api/auth/status"),
  login: async (pin: string) => {
    const result = await request<{ session: string }>("POST", "/api/auth/login", { pin });
    setSession(result.session);
  },
  lock: async () => {
    try {
      await request<void>("POST", "/api/auth/lock");
    } finally {
      setSession("");
    }
  },
  changePin: (oldPin: string, newPin: string) =>
    request<void>("POST", "/api/auth/change-pin", { old_pin: oldPin, new_pin: newPin }),

  listServers: () => request<Server[]>("GET", "/api/servers"),
  createServer: (data: ServerInput) => request<Server>("POST", "/api/servers", data),
  updateServer: (id: number, data: ServerInput) => request<Server>("PUT", `/api/servers/${id}`, data),
  deleteServer: (id: number) => request<void>("DELETE", `/api/servers/${id}`),
  reorderServers: (ids: number[]) => request<Server[]>("PUT", "/api/servers/order", { ids }),
  resetHostKey: (id: number) => request<Server>("POST", `/api/servers/${id}/reset-hostkey`),
  testServer: (data: ServerInput & { id: number | null }) =>
    request<ConnectionCheck>("POST", "/api/servers/test", data),
  serverStats: (id: number) => request<ServerStats>("GET", `/api/servers/${id}/stats`),
  listDir: (id: number, path: string) =>
    request<DirListing>("GET", `/api/servers/${id}/ls?path=${encodeURIComponent(path)}`),

  listBookmarks: () => request<Bookmark[]>("GET", "/api/bookmarks"),
  createBookmark: (data: BookmarkInput) => request<Bookmark>("POST", "/api/bookmarks", data),
  updateBookmark: (id: number, data: BookmarkInput) => request<Bookmark>("PUT", `/api/bookmarks/${id}`, data),
  deleteBookmark: (id: number) => request<void>("DELETE", `/api/bookmarks/${id}`),

  listSnippets: () => request<Snippet[]>("GET", "/api/snippets"),
  createSnippet: (data: SnippetInput) => request<Snippet>("POST", "/api/snippets", data),
  updateSnippet: (id: number, data: SnippetInput) => request<Snippet>("PUT", `/api/snippets/${id}`, data),
  deleteSnippet: (id: number) => request<void>("DELETE", `/api/snippets/${id}`),
};

export function terminalSocketUrl(serverId: number, bookmarkId: number | null, cols: number, rows: number): string {
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  const params = new URLSearchParams({
    session,
    server_id: String(serverId),
    cols: String(cols),
    rows: String(rows),
  });
  if (bookmarkId !== null) params.set("bookmark_id", String(bookmarkId));
  return `${proto}://${window.location.host}/ws/terminal?${params.toString()}`;
}

export function openExternal(url: string): void {
  window.open(url, "_blank", "noopener");
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
