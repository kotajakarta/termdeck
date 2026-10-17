import { useCallback, useEffect, useState } from "react";

import { api, getSession, setSession, setUnauthorizedHandler } from "./api";
import { App } from "./App";
import { LockScreen } from "./components/LockScreen";

type Gate = "checking" | "locked" | "unlocked";

/** Shows the PIN screen until the backend accepts a session, then the app. */
export function Root() {
  const [gate, setGate] = useState<Gate>(getSession() ? "checking" : "locked");

  useEffect(() => {
    setUnauthorizedHandler(() => setGate("locked"));
    if (getSession()) {
      api
        .authStatus()
        .then((status) => {
          if (!status.session_valid) setSession("");
          setGate(status.session_valid ? "unlocked" : "locked");
        })
        .catch(() => setGate("locked"));
    }
    return () => setUnauthorizedHandler(null);
  }, []);

  const lock = useCallback(async () => {
    try {
      await api.lock();
    } finally {
      setGate("locked");
    }
  }, []);

  if (gate === "checking") return null;
  if (gate === "locked") return <LockScreen onUnlocked={() => setGate("unlocked")} />;
  return <App onLock={lock} />;
}
