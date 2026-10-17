import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { api, errorMessage, token } from "./api";
import { serverColor } from "./format";
import {
  activeTabId,
  emptyPanes,
  focusSlot,
  getLayoutDefinition,
  LAYOUTS,
  removeTab,
  resizeTrack,
  setLayout,
  setLayoutWithSlot,
  showTab,
  type LayoutKind,
  type PaneState,
} from "./layout";
import { buildCdCommand } from "./shell";
import { readString, writeString } from "./storage";
import type { Bookmark, Server, Snippet, Tab, TabStatus } from "./types";
import { BookmarkForm } from "./components/BookmarkForm";
import { ChangePin } from "./components/ChangePin";
import { CommandPalette } from "./components/CommandPalette";
import { Icon } from "./components/Icon";
import { ConfirmDialog } from "./components/Modal";
import { ServerForm } from "./components/ServerForm";
import { ServerInfo } from "./components/ServerInfo";
import { Sidebar } from "./components/Sidebar";
import { Snippets } from "./components/Snippets";
import { TabBar } from "./components/TabBar";
import { isAppShortcut, TerminalView, type Sender } from "./components/TerminalView";

type Dialog =
  | { kind: "server"; server: Server | null }
  | { kind: "bookmark"; bookmark: Bookmark | null; serverId: number }
  | { kind: "confirm"; title: string; message: string; confirmLabel: string; action: () => Promise<void> }
  | { kind: "palette" }
  | { kind: "jump"; tabId: string }
  | { kind: "change-pin" }
  | null;

const LAYOUT_KEY = "termdeck.layout";

function initialPanes(): PaneState {
  const saved = readString(LAYOUT_KEY);
  if (saved) {
    if (LAYOUTS.some((l) => l.kind === saved)) return emptyPanes(saved);
    if (/^\d+x\d+$/.test(saved.trim())) return emptyPanes(saved);
  }
  return emptyPanes("1");
}

let tabCounter = 0;

export function App({ onLock }: { onLock: () => Promise<void> }) {
  const [servers, setServers] = useState<Server[]>([]);
  const [bookmarks, setBookmarks] = useState<Bookmark[]>([]);
  const [snippets, setSnippets] = useState<Snippet[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [panes, setPanes] = useState<PaneState>(initialPanes);
  const [selectedServerId, setSelectedServerId] = useState<number | null>(null);
  const [dialog, setDialog] = useState<Dialog>(null);
  const senders = useRef(new Map<string, Sender>());

  const serverMap = useMemo(() => new Map(servers.map((s) => [s.id, s])), [servers]);
  const bookmarkMap = useMemo(() => new Map(bookmarks.map((b) => [b.id, b])), [bookmarks]);
  const groups = useMemo(
    () => [...new Set(servers.map((s) => s.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [servers],
  );
  const liveServerIds = useMemo(
    () => new Set(tabs.filter((t) => t.status === "connected").map((t) => t.serverId)),
    [tabs],
  );
  const openBookmarkIds = useMemo(
    () => new Set(tabs.flatMap((t) => (t.bookmarkId !== null ? [t.bookmarkId] : []))),
    [tabs],
  );
  const visibleIds = useMemo(() => new Set(panes.slots.filter((id): id is string => id !== null)), [panes]);
  const activeId = activeTabId(panes);
  const activeTab = tabs.find((t) => t.id === activeId) ?? null;
  const selectedServer = selectedServerId !== null ? (serverMap.get(selectedServerId) ?? null) : null;
  const multiPane = panes.slots.length > 1;
  const layoutDef = getLayoutDefinition(panes.layout);
  const [colSizes, setColSizes] = useState<number[]>(() => [...layoutDef.defaultCols]);
  const [rowSizes, setRowSizes] = useState<number[]>(() => [...layoutDef.defaultRows]);
  const [dragging, setDragging] = useState<{
    type: "col" | "row";
    index: number;
    startPos: number;
    startSizes: number[];
  } | null>(null);
  const panesContainerRef = useRef<HTMLDivElement>(null);

  const effectiveCols = colSizes.length === layoutDef.cols ? colSizes : [...layoutDef.defaultCols];
  const effectiveRows = rowSizes.length === layoutDef.rows ? rowSizes : [...layoutDef.defaultRows];

  const onStartColResize = (e: React.PointerEvent, index: number) => {
    e.preventDefault();
    setDragging({
      type: "col",
      index,
      startPos: e.clientX,
      startSizes: [...effectiveCols],
    });
  };

  const onStartRowResize = (e: React.PointerEvent, index: number) => {
    e.preventDefault();
    setDragging({
      type: "row",
      index,
      startPos: e.clientY,
      startSizes: [...effectiveRows],
    });
  };

  const onResetColResize = () => {
    setColSizes([...layoutDef.defaultCols]);
  };

  const onResetRowResize = () => {
    setRowSizes([...layoutDef.defaultRows]);
  };

  useEffect(() => {
    if (!dragging) return;

    const onPointerMove = (e: PointerEvent) => {
      const container = panesContainerRef.current;
      if (!container) return;

      if (dragging.type === "col") {
        const rect = container.getBoundingClientRect();
        const totalWidth = rect.width;
        if (totalWidth <= 0) return;
        const deltaPx = e.clientX - dragging.startPos;
        const deltaPct = (deltaPx / totalWidth) * 100;
        const minPct = Math.max(5, (80 / totalWidth) * 100);
        setColSizes(resizeTrack(dragging.startSizes, dragging.index, deltaPct, minPct));
      } else {
        const rect = container.getBoundingClientRect();
        const totalHeight = rect.height;
        if (totalHeight <= 0) return;
        const deltaPx = e.clientY - dragging.startPos;
        const deltaPct = (deltaPx / totalHeight) * 100;
        const minPct = Math.max(5, (60 / totalHeight) * 100);
        setRowSizes(resizeTrack(dragging.startSizes, dragging.index, deltaPct, minPct));
      }
    };

    const onPointerUp = () => {
      setDragging(null);
    };

    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    window.addEventListener("pointercancel", onPointerUp);

    return () => {
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", onPointerUp);
      window.removeEventListener("pointercancel", onPointerUp);
    };
  }, [dragging]);

  const reload = useCallback(async () => {
    try {
      const [srv, bms, sns] = await Promise.all([api.listServers(), api.listBookmarks(), api.listSnippets()]);
      setServers(srv);
      setBookmarks(bms);
      setSnippets(sns);
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const selectTab = useCallback(
    (id: string) => {
      setPanes((prev) => showTab(prev, id));
      const tab = tabs.find((t) => t.id === id);
      if (tab) setSelectedServerId(tab.serverId);
    },
    [tabs],
  );

  /** Open a terminal; for a saved path, jump to its open tab first unless `newTab`. */
  const openTab = useCallback(
    (serverId: number, bookmarkId: number | null, newTab: boolean) => {
      const server = serverMap.get(serverId);
      if (!server) return;
      setDialog(null);
      if (!newTab && bookmarkId !== null) {
        const existing = tabs.find((t) => t.serverId === serverId && t.bookmarkId === bookmarkId);
        if (existing) {
          selectTab(existing.id);
          return;
        }
      }
      const bookmark = bookmarkId !== null ? bookmarkMap.get(bookmarkId) : undefined;
      tabCounter += 1;
      const tab: Tab = {
        id: `tab-${tabCounter}`,
        serverId,
        bookmarkId: bookmark ? bookmark.id : null,
        title: bookmark ? `${server.name}: ${bookmark.name}` : server.name,
        status: "connecting",
        session: 0,
      };
      setTabs((prev) => [...prev, tab]);
      setPanes((prev) => showTab(prev, tab.id));
      setSelectedServerId(serverId);
    },
    [serverMap, bookmarkMap, tabs, selectTab],
  );

  const closeTab = useCallback(
    (id: string) => {
      setPanes((prev) => removeTab(prev, id, tabs.map((t) => t.id)));
      setTabs((prev) => prev.filter((t) => t.id !== id));
    },
    [tabs],
  );

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

  const focusPane = useCallback(
    (index: number) => {
      setPanes((prev) => focusSlot(prev, index));
      const id = panes.slots[index];
      const tab = id ? tabs.find((t) => t.id === id) : undefined;
      if (tab) setSelectedServerId(tab.serverId);
    },
    [panes, tabs],
  );

  const setTabStatus = useCallback((id: string, status: TabStatus) => {
    setTabs((prev) => prev.map((t) => (t.id === id && t.status !== status ? { ...t, status } : t)));
  }, []);

  const reconnectTab = useCallback((id: string) => {
    setTabs((prev) => prev.map((t) => (t.id === id ? { ...t, session: t.session + 1, status: "connecting" } : t)));
  }, []);

  const registerSender = useCallback((id: string, sender: Sender | null) => {
    if (sender) senders.current.set(id, sender);
    else senders.current.delete(id);
  }, []);

  const runSnippet = (snippet: Snippet) => {
    if (!activeId) return;
    senders.current.get(activeId)?.(snippet.command.replace(/\r?\n/g, "\r"));
  };

  /** cd the shell of an open terminal to another saved path of the same server. */
  const jumpToPath = useCallback(
    (tabId: string, bookmark: Bookmark) => {
      const tab = tabs.find((t) => t.id === tabId);
      const server = tab ? serverMap.get(tab.serverId) : undefined;
      const send = senders.current.get(tabId);
      setDialog(null);
      if (!tab || !server || !send || tab.status !== "connected") return;
      // Ctrl+U clears anything half-typed on the prompt before the cd command.
      send(`\x15${buildCdCommand(bookmark.path, bookmark.startup_cmd)}\r`);
      setTabs((prev) =>
        prev.map((t) =>
          t.id === tabId ? { ...t, bookmarkId: bookmark.id, title: `${server.name}: ${bookmark.name}` } : t,
        ),
      );
    },
    [tabs, serverMap],
  );

  // Keyboard shortcuts; state is read through a ref so the listener is registered once.
  const shortcutState = useRef({ tabs, panes, activeTab, openTab, closeTab, selectTab, focusPane, dialog });
  shortcutState.current = { tabs, panes, activeTab, openTab, closeTab, selectTab, focusPane, dialog };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!isAppShortcut(event)) return;
      event.preventDefault();
      const state = shortcutState.current;
      // While a form is open only the palettes toggle, so tabs are not closed behind it.
      if (state.dialog && state.dialog.kind !== "palette" && state.dialog.kind !== "jump") return;
      const tab = state.activeTab;
      const cycleTabs = (step: number) => {
        const list = state.tabs;
        if (list.length === 0) return;
        const idx = list.findIndex((t) => t.id === tab?.id);
        state.selectTab(list[(idx + step + list.length) % list.length].id);
      };
      const cyclePanes = (step: number) => {
        const count = state.panes.slots.length;
        state.focusPane((state.panes.focused + step + count) % count);
      };
      switch (event.code) {
        case "KeyP":
          setDialog((d) => (d?.kind === "palette" ? null : { kind: "palette" }));
          break;
        case "KeyJ":
          if (tab) setDialog((d) => (d?.kind === "jump" ? null : { kind: "jump", tabId: tab.id }));
          break;
        case "KeyW":
          if (tab) state.closeTab(tab.id);
          break;
        case "KeyT":
          if (tab) state.openTab(tab.serverId, tab.bookmarkId, true);
          break;
        case "ArrowRight":
          cycleTabs(1);
          break;
        case "ArrowLeft":
          cycleTabs(-1);
          break;
        case "ArrowDown":
          cyclePanes(1);
          break;
        case "ArrowUp":
          cyclePanes(-1);
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Ctrl+W (delete word in bash) closes the browser tab; ask before dropping open sessions.
  useEffect(() => {
    if (tabs.length === 0) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [tabs.length]);

  const reorderServers = (ids: number[]) => {
    // Show the new order immediately; the backend answer (or a reload on error) settles it.
    const byId = new Map(servers.map((s) => [s.id, s]));
    setServers(ids.flatMap((id) => byId.get(id) ?? []));
    api
      .reorderServers(ids)
      .then(setServers)
      .catch((err: unknown) => {
        setLoadError(errorMessage(err));
        void reload();
      });
  };

  const confirmDeleteServer = (server: Server) => {
    const count = bookmarks.filter((b) => b.server_id === server.id).length;
    setDialog({
      kind: "confirm",
      title: `Hapus ${server.name}?`,
      message:
        `Server ${server.username}@${server.host} beserta ${count} path tersimpannya akan dihapus dari TermDeck. ` +
        "Tab terminal yang terbuka ke server ini akan ditutup.",
      confirmLabel: "Hapus server",
      action: async () => {
        await api.deleteServer(server.id);
        const doomed = tabs.filter((t) => t.serverId === server.id);
        let order = tabs.map((t) => t.id);
        setPanes((prev) => {
          let next = prev;
          for (const tab of doomed) {
            next = removeTab(next, tab.id, order);
            order = order.filter((id) => id !== tab.id);
          }
          return next;
        });
        setTabs((prev) => prev.filter((t) => t.serverId !== server.id));
        if (selectedServerId === server.id) setSelectedServerId(null);
        await reload();
      },
    });
  };

  const confirmDeleteBookmark = (bookmark: Bookmark) => {
    setDialog({
      kind: "confirm",
      title: `Hapus path ${bookmark.name}?`,
      message: `Path ${bookmark.path} akan dihapus dari daftar. Folder di server tidak disentuh.`,
      confirmLabel: "Hapus path",
      action: async () => {
        await api.deleteBookmark(bookmark.id);
        await reload();
      },
    });
  };

  const jumpTab = dialog?.kind === "jump" ? tabs.find((t) => t.id === dialog.tabId) : undefined;

  return (
    <div className="app">
      <aside className="sidebar">
        <header className="brand">
          <span className="brand-mark" aria-hidden="true">
            <Icon name="terminal" size={16} />
          </span>
          <span className="brand-name">TermDeck</span>
          <button
            type="button"
            className="icon-btn"
            onClick={() => setDialog({ kind: "change-pin" })}
            title="Ganti PIN"
            aria-label="Ganti PIN"
          >
            <Icon name="key" size={15} />
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => void onLock()}
            title="Kunci TermDeck (semua terminal ditutup)"
            aria-label="Kunci TermDeck"
          >
            <Icon name="lock" size={15} />
          </button>
          <button type="button" className="btn btn-small btn-primary" onClick={() => setDialog({ kind: "server", server: null })}>
            <Icon name="plus" size={13} /> Server
          </button>
        </header>

        {!token && <p className="notice bad small">Token sesi tidak ditemukan. Buka TermDeck lewat http://localhost:9095 setelah menjalankan ./run.sh.</p>}
        {loadError && <p className="notice bad small">{loadError}</p>}

        <div className="sidebar-scroll">
          <Sidebar
            servers={servers}
            bookmarks={bookmarks}
            selectedServerId={selectedServerId}
            liveServerIds={liveServerIds}
            openBookmarkIds={openBookmarkIds}
            activeBookmarkId={activeTab?.bookmarkId ?? null}
            onSelectServer={setSelectedServerId}
            onOpen={openTab}
            onEditServer={(server) => setDialog({ kind: "server", server })}
            onDeleteServer={confirmDeleteServer}
            onAddBookmark={(server) => setDialog({ kind: "bookmark", bookmark: null, serverId: server.id })}
            onEditBookmark={(bookmark) => setDialog({ kind: "bookmark", bookmark, serverId: bookmark.server_id })}
            onDeleteBookmark={confirmDeleteBookmark}
            onReorder={reorderServers}
          />
        </div>

        <div className="sidebar-bottom">
          {selectedServer && (
            <ServerInfo
              key={selectedServer.id}
              server={selectedServer}
              onOpen={() => openTab(selectedServer.id, null, true)}
            />
          )}
          <Snippets
            snippets={snippets}
            canRun={activeTab?.status === "connected"}
            onRun={runSnippet}
            onChanged={() => void reload()}
          />
        </div>
      </aside>

      <main className="workspace">
        <TabBar
          tabs={tabs}
          activeId={activeId}
          visibleIds={visibleIds}
          layout={panes.layout}
          onLayout={changeLayout}
          servers={serverMap}
          onSelect={selectTab}
          onClose={closeTab}
          onOpenPalette={() => setDialog({ kind: "palette" })}
        />

        {tabs.length > 0 ? (
          <div
            ref={panesContainerRef}
            className={`panes layout-${panes.layout}${
              dragging ? (dragging.type === "col" ? " is-resizing" : " is-resizing is-resizing-h") : ""
            }`}
            style={{
              gridTemplateAreas: layoutDef.areas,
              gridTemplateColumns: effectiveCols.map((s) => `${s}%`).join(" "),
              gridTemplateRows: effectiveRows.map((s) => `${s}%`).join(" "),
            }}
          >
            {panes.slots.map((id, index) =>
              id === null ? (
                <div
                  key={`empty-${index}`}
                  className={`pane-empty${multiPane && index === panes.focused ? " is-focused" : ""}`}
                  style={{ gridArea: `p${index}` }}
                  onMouseDown={() => focusPane(index)}
                >
                  <p>Panel kosong. Klik panel ini, lalu pilih tab atau path untuk ditampilkan di sini.</p>
                </div>
              ) : null,
            )}

            {tabs.map((tab) => {
              const server = serverMap.get(tab.serverId);
              const bookmark = tab.bookmarkId !== null ? bookmarkMap.get(tab.bookmarkId) : undefined;
              const slot = panes.slots.indexOf(tab.id);
              const visible = slot !== -1;
              const focused = visible && slot === panes.focused;
              const hasPaths = bookmarks.some((b) => b.server_id === tab.serverId);
              return (
                <section
                  key={tab.id}
                  className={`pane${visible ? " is-visible" : ""}${multiPane && focused ? " is-focused" : ""}`}
                  style={{
                    ["--srv" as string]: serverColor(server?.color ?? ""),
                    gridArea: visible ? `p${slot}` : undefined,
                  }}
                  aria-label={tab.title}
                  aria-hidden={!visible}
                  onMouseDown={() => visible && focusPane(slot)}
                >
                  <header className="pane-head">
                    <span className={`tab-status status-dot-${tab.status}`} aria-hidden="true" />
                    <span className="pane-server">{server?.name ?? "Server dihapus"}</span>
                    <code className="pane-path">
                      {server ? `${server.username}@${server.host}` : ""}
                      {bookmark ? `:${bookmark.path}` : ""}
                    </code>
                    <span className="spacer" />
                    {hasPaths && (
                      <button
                        type="button"
                        className="btn btn-small pane-btn"
                        disabled={tab.status !== "connected"}
                        onClick={() => setDialog({ kind: "jump", tabId: tab.id })}
                        title="Pindah ke path tersimpan lain di terminal ini (Alt+J)"
                      >
                        <Icon name="jump" size={13} /> Pindah path
                      </button>
                    )}
                    {(tab.status === "closed" || tab.status === "error") && (
                      <button type="button" className="btn btn-small pane-btn" onClick={() => reconnectTab(tab.id)}>
                        <Icon name="refresh" size={13} /> Sambung ulang
                      </button>
                    )}
                    {multiPane && (
                      <button
                        type="button"
                        className="icon-btn"
                        onClick={() => closeTab(tab.id)}
                        aria-label={`Tutup ${tab.title}`}
                        title="Tutup terminal (Alt+W)"
                      >
                        <Icon name="close" size={13} />
                      </button>
                    )}
                  </header>
                  <div className="pane-body">
                    <TerminalView
                      tab={tab}
                      label={server ? `${server.username}@${server.host}` : tab.title}
                      visible={visible}
                      focused={focused}
                      onStatus={setTabStatus}
                      onReconnect={reconnectTab}
                      registerSender={registerSender}
                    />
                  </div>
                </section>
              );
            })}
            {layoutDef.cols > 1 &&
              Array.from({ length: layoutDef.cols - 1 }).map((_, i) => {
                let leftPct = 0;
                for (let c = 0; c <= i; c++) leftPct += effectiveCols[c];
                return (
                  <div
                    key={`split-v-${i}`}
                    className={`pane-splitter-v${dragging?.type === "col" && dragging.index === i ? " is-dragging" : ""}`}
                    style={{ left: `${leftPct}%` }}
                    onPointerDown={(e) => onStartColResize(e, i)}
                    onDoubleClick={() => onResetColResize()}
                    title="Geser untuk mengubah ukuran kolom (Klik 2x untuk reset)"
                  />
                );
              })}

            {layoutDef.rows > 1 &&
              Array.from({ length: layoutDef.rows - 1 }).map((_, j) => {
                let topPct = 0;
                for (let r = 0; r <= j; r++) topPct += effectiveRows[r];
                return (
                  <div
                    key={`split-h-${j}`}
                    className={`pane-splitter-h${dragging?.type === "row" && dragging.index === j ? " is-dragging" : ""}`}
                    style={{ top: `${topPct}%` }}
                    onPointerDown={(e) => onStartRowResize(e, j)}
                    onDoubleClick={() => onResetRowResize()}
                    title="Geser untuk mengubah ukuran baris (Klik 2x untuk reset)"
                  />
                );
              })}
          </div>
        ) : (
          <div className="welcome">
            {servers.length === 0 ? (
              <>
                <h1>Satu tempat untuk semua server Anda</h1>
                <p>Tambahkan server lewat kredensial SSH, lalu simpan folder proyek supaya bisa dibuka sekali klik.</p>
                <button type="button" className="btn btn-primary" onClick={() => setDialog({ kind: "server", server: null })}>
                  <Icon name="plus" size={14} /> Tambah server pertama
                </button>
              </>
            ) : (
              <>
                <h1>Mau kerja di mana sekarang?</h1>
                <p>Klik path di sidebar, atau tekan Alt+P lalu ketik nama proyeknya.</p>
                <div className="quick-grid">
                  {bookmarks.slice(0, 8).map((bm) => {
                    const server = serverMap.get(bm.server_id);
                    if (!server) return null;
                    return (
                      <button
                        key={bm.id}
                        type="button"
                        className="quick"
                        style={{ ["--srv" as string]: serverColor(server.color) }}
                        onClick={() => openTab(server.id, bm.id, false)}
                      >
                        <span className="quick-name">{bm.name}</span>
                        <span className="quick-server">{server.name}</span>
                        <code className="quick-path">{bm.path}</code>
                      </button>
                    );
                  })}
                </div>
              </>
            )}
            <dl className="shortcuts">
              <div><dt>Alt+P</dt><dd>Lompat ke proyek</dd></div>
              <div><dt>Alt+J</dt><dd>Pindah path di terminal ini</dd></div>
              <div><dt>Alt+Shift+Kanan / Kiri</dt><dd>Tab berikutnya / sebelumnya</dd></div>
              <div><dt>Alt+Shift+Bawah / Atas</dt><dd>Panel berikutnya / sebelumnya</dd></div>
              <div><dt>Alt+T</dt><dd>Duplikat tab</dd></div>
              <div><dt>Alt+W</dt><dd>Tutup tab</dd></div>
              <div><dt>Ctrl+C</dt><dd>Salin teks terseleksi</dd></div>
              <div><dt>Ctrl+V</dt><dd>Tempel</dd></div>
            </dl>
          </div>
        )}
      </main>

      {dialog?.kind === "server" && (
        <ServerForm
          server={dialog.server}
          groups={groups}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            setDialog(null);
            setSelectedServerId(saved.id);
            void reload();
          }}
        />
      )}
      {dialog?.kind === "bookmark" && (
        <BookmarkForm
          servers={servers}
          bookmark={dialog.bookmark}
          serverId={dialog.serverId}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            void reload();
          }}
        />
      )}
      {dialog?.kind === "confirm" && (
        <ConfirmDialog
          title={dialog.title}
          message={dialog.message}
          confirmLabel={dialog.confirmLabel}
          onCancel={() => setDialog(null)}
          onConfirm={() => {
            const { action } = dialog;
            setDialog(null);
            action().catch((err: unknown) => setLoadError(errorMessage(err)));
          }}
        />
      )}
      {dialog?.kind === "change-pin" && <ChangePin onClose={() => setDialog(null)} />}
      {dialog?.kind === "palette" && (
        <CommandPalette
          servers={servers}
          bookmarks={bookmarks}
          onPick={(serverId, bookmarkId) => openTab(serverId, bookmarkId, false)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "jump" && jumpTab && (
        <CommandPalette
          servers={servers}
          bookmarks={bookmarks}
          onlyServerId={jumpTab.serverId}
          title={`Pindah path di ${jumpTab.title}`}
          placeholder="Ketik nama atau path tujuan"
          onPick={(_serverId, bookmarkId) => {
            const target = bookmarkId !== null ? bookmarkMap.get(bookmarkId) : undefined;
            if (target) jumpToPath(jumpTab.id, target);
          }}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  );
}
