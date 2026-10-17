import { useEffect, useRef, useState } from "react";
import { serverColor } from "../format";
import type { Server, Tab } from "../types";
import { LAYOUTS, SNAP_LAYOUTS, layoutDimensions, type LayoutKind } from "../layout";
import { Icon, type IconName } from "./Icon";
import { SnapLayoutPicker } from "./SnapLayoutPicker";

interface TabBarProps {
  tabs: Tab[];
  activeId: string | null;
  /** Tabs currently shown in one of the split panes. */
  visibleIds: Set<string>;
  layout: LayoutKind;
  onLayout: (layout: LayoutKind, targetSlot?: number) => void;
  servers: Map<number, Server>;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onOpenPalette: () => void;
}

const STATUS_LABEL: Record<Tab["status"], string> = {
  connecting: "Menghubungkan",
  connected: "Terhubung",
  closed: "Terputus",
  error: "Gagal",
};

export function TabBar({
  tabs,
  activeId,
  visibleIds,
  layout,
  onLayout,
  servers,
  onSelect,
  onClose,
  onOpenPalette,
}: TabBarProps) {
  const [showSnapPicker, setShowSnapPicker] = useState(false);
  const [showPicker, setShowPicker] = useState(false);
  const pickerRef = useRef<HTMLDivElement>(null);
  const currentDims = layoutDimensions(layout);
  const [hoverDims, setHoverDims] = useState<{ rows: number; cols: number } | null>(null);
  const [manualRows, setManualRows] = useState(currentDims.rows);
  const [manualCols, setManualCols] = useState(currentDims.cols);

  const isStandardPreset = LAYOUTS.some((item) => item.kind === layout);
  const isSnapPreset = SNAP_LAYOUTS.some((item) => item.kind === layout);

  useEffect(() => {
    setManualRows(currentDims.rows);
    setManualCols(currentDims.cols);
  }, [currentDims.rows, currentDims.cols]);

  useEffect(() => {
    if (!showPicker && !showSnapPicker) return;
    const onPointerDown = (e: PointerEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) {
        setShowPicker(false);
        setShowSnapPicker(false);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setShowPicker(false);
        setShowSnapPicker(false);
      }
    };
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [showPicker, showSnapPicker]);

  const displayRows = hoverDims ? hoverDims.rows : currentDims.rows;
  const displayCols = hoverDims ? hoverDims.cols : currentDims.cols;

  return (
    <div className="tabbar">
      <div className="tabs" role="tablist" aria-label="Terminal terbuka">
        {tabs.map((tab) => {
          const server = servers.get(tab.serverId);
          return (
            <div
              key={tab.id}
              role="tab"
              tabIndex={0}
              aria-selected={tab.id === activeId}
              className={`tab${tab.id === activeId ? " is-active" : ""}${
                visibleIds.has(tab.id) ? " is-visible" : ""
              } status-${tab.status}`}
              style={{ ["--srv" as string]: serverColor(server?.color ?? "") }}
              onClick={() => onSelect(tab.id)}
              onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onSelect(tab.id)}
              onAuxClick={(e) => e.button === 1 && onClose(tab.id)}
              title={`${tab.title} (${STATUS_LABEL[tab.status]})`}
            >
              <span className="tab-status" aria-label={STATUS_LABEL[tab.status]} />
              <span className="tab-title">{tab.title}</span>
              <button
                type="button"
                className="tab-close"
                onClick={(e) => {
                  e.stopPropagation();
                  onClose(tab.id);
                }}
                aria-label={`Tutup ${tab.title}`}
              >
                <Icon name="close" size={12} />
              </button>
            </div>
          );
        })}
      </div>
      <div className="layout-switch" role="radiogroup" aria-label="Tata letak terminal" ref={pickerRef}>
        <button
          type="button"
          role="radio"
          aria-checked={isSnapPreset && !isStandardPreset}
          className={`icon-btn snap-toggle-btn${(isSnapPreset && !isStandardPreset) || showSnapPicker ? " is-on" : ""}`}
          onClick={() => {
            setShowSnapPicker((v) => !v);
            setShowPicker(false);
          }}
          title="Snap Layouts Windows (Alt+S)"
          aria-label="Snap Layouts Windows"
        >
          <Icon name="layoutSnap" size={15} />
        </button>

        {LAYOUTS.map((item) => (
          <button
            key={item.kind}
            type="button"
            role="radio"
            aria-checked={layout === item.kind}
            className={`icon-btn${layout === item.kind ? " is-on" : ""}`}
            onClick={() => {
              setShowPicker(false);
              setShowSnapPicker(false);
              onLayout(item.kind);
            }}
            title={item.label}
            aria-label={item.label}
          >
            <Icon name={`layout${item.kind}` as IconName} size={15} />
          </button>
        ))}

        <button
          type="button"
          role="radio"
          aria-checked={!isStandardPreset && !isSnapPreset}
          className={`icon-btn${!isStandardPreset && !isSnapPreset ? " is-on" : ""}${showPicker ? " is-active" : ""}`}
          onClick={() => {
            setShowPicker((v) => !v);
            setShowSnapPicker(false);
          }}
          title={`Kustom tata letak...${!isStandardPreset && !isSnapPreset ? ` (${currentDims.rows}×${currentDims.cols})` : ""}`}
          aria-label="Kustom tata letak"
        >
          <Icon name="layoutCustom" size={15} />
        </button>

        {showSnapPicker && (
          <SnapLayoutPicker
            currentLayout={layout}
            onSelectSnap={(kind, slotIndex) => {
              setShowSnapPicker(false);
              onLayout(kind, slotIndex);
            }}
            onClose={() => setShowSnapPicker(false)}
            onOpenCustomGrid={() => {
              setShowSnapPicker(false);
              setShowPicker(true);
            }}
          />
        )}

        {showPicker && (
          <div className="grid-picker-popover" role="dialog" aria-label="Kustom tata letak terminal">
            <div className="grid-picker-header">
              <span className="grid-picker-title">Kustom Tata Letak</span>
              <span className="grid-picker-badge">
                {displayRows} × {displayCols} ({displayRows * displayCols} panel)
              </span>
            </div>
            <div
              className="grid-matrix"
              role="grid"
              aria-label="Pilih dimensi kotak"
              onMouseLeave={() => setHoverDims(null)}
            >
              {Array.from({ length: 4 }).map((_, r) => (
                <div key={r} className="grid-matrix-row" role="row">
                  {Array.from({ length: 4 }).map((_, c) => {
                    const rowNum = r + 1;
                    const colNum = c + 1;
                    const isHighlighted = rowNum <= displayRows && colNum <= displayCols;
                    return (
                      <button
                        key={c}
                        type="button"
                        role="gridcell"
                        className={`grid-matrix-cell${isHighlighted ? " is-active" : ""}`}
                        onMouseEnter={() => setHoverDims({ rows: rowNum, cols: colNum })}
                        onClick={() => {
                          onLayout(`${rowNum}x${colNum}`);
                          setShowPicker(false);
                        }}
                        title={`${rowNum} × ${colNum}`}
                        aria-label={`${rowNum} baris, ${colNum} kolom`}
                      />
                    );
                  })}
                </div>
              ))}
            </div>
            <div className="grid-picker-manual">
              <div className="grid-input-group">
                <label className="grid-field">
                  <span>Baris</span>
                  <input
                    type="number"
                    min={1}
                    max={4}
                    value={manualRows}
                    onChange={(e) => setManualRows(Math.max(1, Math.min(4, parseInt(e.target.value, 10) || 1)))}
                  />
                </label>
                <span className="grid-multiply">×</span>
                <label className="grid-field">
                  <span>Kolom</span>
                  <input
                    type="number"
                    min={1}
                    max={6}
                    value={manualCols}
                    onChange={(e) => setManualCols(Math.max(1, Math.min(6, parseInt(e.target.value, 10) || 1)))}
                  />
                </label>
              </div>
              <button
                type="button"
                className="grid-apply-btn"
                onClick={() => {
                  onLayout(`${manualRows}x${manualCols}`);
                  setShowPicker(false);
                }}
              >
                Terapkan
              </button>
            </div>
          </div>
        )}
      </div>
      <button type="button" className="palette-btn" onClick={onOpenPalette} title="Lompat ke proyek (Alt+P)">
        <Icon name="search" size={14} />
        <span>Lompat ke proyek</span>
        <kbd>Alt+P</kbd>
      </button>
    </div>
  );
}
