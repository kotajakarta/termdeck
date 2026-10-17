import { useEffect, useRef } from "react";
import { Terminal, type ITheme } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { WebLinksAddon } from "@xterm/addon-web-links";
import "@xterm/xterm/css/xterm.css";

import { openExternal, terminalSocketUrl } from "../api";
import type { ControlMessage, Tab, TabStatus } from "../types";

const THEME: ITheme = {
  background: "#141c1a",
  foreground: "#d8e0d9",
  cursor: "#e8a33d",
  cursorAccent: "#141c1a",
  selectionBackground: "rgba(232, 163, 61, 0.32)",
  black: "#1d2725",
  red: "#e2574c",
  green: "#7cbf6b",
  yellow: "#e8a33d",
  blue: "#5f9fd8",
  magenta: "#c088d0",
  cyan: "#4fb3c8",
  white: "#d8e0d9",
  brightBlack: "#5b6b66",
  brightRed: "#f07a6f",
  brightGreen: "#9fd88f",
  brightYellow: "#f3c06c",
  brightBlue: "#86bdf0",
  brightMagenta: "#d9a6e6",
  brightCyan: "#7fd0e0",
  brightWhite: "#f4f7f3",
};

const DIM = "\x1b[38;2;122;140;134m";
const RED = "\x1b[38;2;240;122;111m";
const RESET = "\x1b[0m";

export type Sender = (data: string) => void;

/** Alt-based shortcuts handled by App; browsers reserve most Ctrl combinations. */
export function isAppShortcut(event: KeyboardEvent): boolean {
  if (event.ctrlKey && event.shiftKey && event.code === "KeyP") return true;
  if (!event.altKey || event.ctrlKey || event.metaKey) return false;
  if (event.shiftKey) return ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.code);
  return ["KeyP", "KeyT", "KeyW", "KeyJ"].includes(event.code);
}

interface TerminalViewProps {
  tab: Tab;
  label: string;
  /** Shown in a pane (hidden tabs keep their session but are not laid out). */
  visible: boolean;
  /** The pane that receives keyboard input. */
  focused: boolean;
  onStatus: (tabId: string, status: TabStatus) => void;
  onReconnect: (tabId: string) => void;
  registerSender: (tabId: string, sender: Sender | null) => void;
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

export function TerminalView({ tab, label, visible, focused, onStatus, onReconnect, registerSender }: TerminalViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const statusRef = useRef<TabStatus>("connecting");
  const visibleRef = useRef(visible);
  const callbacks = useRef({ onStatus, onReconnect });
  callbacks.current = { onStatus, onReconnect };
  visibleRef.current = visible;

  const setStatus = (status: TabStatus) => {
    statusRef.current = status;
    callbacks.current.onStatus(tab.id, status);
  };

  const send = (payload: object) => {
    const ws = socketRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload));
  };

  const safeFit = () => {
    const el = containerRef.current;
    if (!el || el.offsetWidth === 0 || el.offsetHeight === 0) return;
    try {
      fitRef.current?.fit();
    } catch {
      // Fit can throw while the renderer is not attached yet; the next resize retries.
    }
  };

  // Terminal instance: lives as long as the tab, survives reconnects.
  useEffect(() => {
    const term = new Terminal({
      fontFamily: '"IBM Plex Mono", ui-monospace, Consolas, monospace',
      fontSize: 14,
      lineHeight: 1.15,
      cursorBlink: true,
      scrollback: 10000,
      theme: THEME,
      allowProposedApi: false,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.loadAddon(new WebLinksAddon((_event, uri) => openExternal(uri)));
    term.open(containerRef.current as HTMLDivElement);
    termRef.current = term;
    fitRef.current = fit;

    term.attachCustomKeyEventHandler((event) => {
      if (event.type !== "keydown") return true;
      // Ctrl+C copies when text is selected; otherwise it is sent as ^C (interrupt).
      if (event.ctrlKey && !event.altKey && event.code === "KeyC" && (event.shiftKey || term.hasSelection())) {
        const selection = term.getSelection();
        if (selection) void copyText(selection);
        term.clearSelection();
        event.preventDefault();
        return false;
      }
      // Ctrl+V / Ctrl+Shift+V: let the browser fire its native paste event, which xterm handles.
      if (event.ctrlKey && !event.altKey && event.code === "KeyV") return false;
      // App shortcuts (see isAppShortcut) bubble up to the window listener in App.
      if (isAppShortcut(event)) return false;
      return true;
    });

    const dataSub = term.onData((data) => {
      const status = statusRef.current;
      if (status === "connected") {
        send({ type: "input", data });
      } else if ((status === "closed" || status === "error") && data === "\r") {
        callbacks.current.onReconnect(tab.id);
      }
    });
    const resizeSub = term.onResize(({ cols, rows }) => send({ type: "resize", cols, rows }));

    const observer = new ResizeObserver(() => {
      if (visibleRef.current) safeFit();
    });
    observer.observe(containerRef.current as HTMLDivElement);

    registerSender(tab.id, (data) => {
      if (statusRef.current === "connected") {
        send({ type: "input", data });
        term.focus();
      }
    });

    return () => {
      registerSender(tab.id, null);
      observer.disconnect();
      dataSub.dispose();
      resizeSub.dispose();
      term.dispose();
      termRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // One SSH session per `tab.session` value; bumping it reconnects.
  useEffect(() => {
    const term = termRef.current;
    if (!term) return;
    safeFit();
    term.write(`${tab.session > 0 ? "\r\n" : ""}${DIM}Menghubungkan ke ${label}…${RESET}\r\n`);
    setStatus("connecting");

    const ws = new WebSocket(terminalSocketUrl(tab.serverId, tab.bookmarkId, term.cols, term.rows));
    ws.binaryType = "arraybuffer";
    socketRef.current = ws;
    let finished = false;

    const finish = (status: TabStatus, line: string) => {
      if (finished) return;
      finished = true;
      term.write(`\r\n${line}\r\n${DIM}Tekan Enter untuk menyambung ulang.${RESET}\r\n`);
      setStatus(status);
    };

    ws.onmessage = (event: MessageEvent<ArrayBuffer | string>) => {
      if (typeof event.data !== "string") {
        term.write(new Uint8Array(event.data));
        return;
      }
      let msg: ControlMessage;
      try {
        msg = JSON.parse(event.data) as ControlMessage;
      } catch {
        return;
      }
      if (msg.type === "status" && msg.status === "connected") {
        setStatus("connected");
        send({ type: "resize", cols: term.cols, rows: term.rows });
      } else if (msg.type === "error") {
        finish("error", `${RED}${msg.message}${RESET}`);
      } else if (msg.type === "exit") {
        finish("closed", `${DIM}Sesi berakhir${msg.code !== null ? ` (kode ${msg.code})` : ""}.${RESET}`);
      }
    };
    ws.onerror = () => finish("error", `${RED}Tidak bisa terhubung ke backend TermDeck.${RESET}`);
    ws.onclose = () => finish("closed", `${DIM}Koneksi terputus.${RESET}`);

    return () => {
      finished = true;
      ws.onclose = null;
      ws.onerror = null;
      ws.onmessage = null;
      ws.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab.session]);

  useEffect(() => {
    if (!visible) return;
    const frame = requestAnimationFrame(() => safeFit());
    return () => cancelAnimationFrame(frame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  useEffect(() => {
    if (!visible || !focused) return;
    const frame = requestAnimationFrame(() => termRef.current?.focus());
    return () => cancelAnimationFrame(frame);
  }, [visible, focused]);

  return <div className="terminal-host" ref={containerRef} />;
}
