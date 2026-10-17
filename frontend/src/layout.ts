// Which open tabs are shown in which pane of the split view. Pure functions so
// the rules are easy to test; App keeps a PaneState in React state.

export type LayoutKind = "1" | "2v" | "2h" | "3v" | "4" | `${number}x${number}` | string;

export const LAYOUTS: { kind: LayoutKind; label: string; slots: number }[] = [
  { kind: "1", label: "Satu panel", slots: 1 },
  { kind: "2v", label: "Dua kolom", slots: 2 },
  { kind: "3v", label: "Tiga kolom", slots: 3 },
  { kind: "2h", label: "Dua baris", slots: 2 },
  { kind: "4", label: "Empat panel", slots: 4 },
];

export function layoutDimensions(layout: LayoutKind): { rows: number; cols: number; slots: number } {
  switch (layout) {
    case "1":
      return { rows: 1, cols: 1, slots: 1 };
    case "2v":
      return { rows: 1, cols: 2, slots: 2 };
    case "2h":
      return { rows: 2, cols: 1, slots: 2 };
    case "3v":
      return { rows: 1, cols: 3, slots: 3 };
    case "4":
      return { rows: 2, cols: 2, slots: 4 };
    default: {
      const match = /^(\d+)x(\d+)$/.exec(layout.trim());
      if (match) {
        const rows = Math.max(1, Math.min(10, parseInt(match[1], 10)));
        const cols = Math.max(1, Math.min(10, parseInt(match[2], 10)));
        return { rows, cols, slots: rows * cols };
      }
      return { rows: 1, cols: 1, slots: 1 };
    }
  }
}

export function gridTemplateAreasFor(rows: number, cols: number): string {
  const rowStrings: string[] = [];
  for (let r = 0; r < rows; r++) {
    const rowSlots: string[] = [];
    for (let c = 0; c < cols; c++) {
      rowSlots.push(`p${r * cols + c}`);
    }
    rowStrings.push(`"${rowSlots.join(" ")}"`);
  }
  return rowStrings.join(" ");
}

export function defaultTrackSizes(count: number): number[] {
  if (count <= 0) return [];
  const pct = 100 / count;
  return Array(count).fill(pct);
}

export function resizeTrack(sizes: number[], index: number, deltaPct: number, minPct = 10): number[] {
  if (index < 0 || index >= sizes.length - 1) return sizes;
  const currentLeft = sizes[index];
  const currentRight = sizes[index + 1];
  const totalPair = currentLeft + currentRight;

  let newLeft = currentLeft + deltaPct;
  let newRight = currentRight - deltaPct;

  if (newLeft < minPct) {
    newLeft = minPct;
    newRight = totalPair - minPct;
  } else if (newRight < minPct) {
    newRight = minPct;
    newLeft = totalPair - minPct;
  }

  const result = [...sizes];
  result[index] = newLeft;
  result[index + 1] = newRight;
  return result;
}

export interface PaneState {
  layout: LayoutKind;
  /** Tab id shown in each pane, null for an empty pane. */
  slots: (string | null)[];
  /** Index of the pane that receives new tabs, snippets and shortcuts. */
  focused: number;
}

export function slotCount(layout: LayoutKind): number {
  return layoutDimensions(layout).slots;
}

export function emptyPanes(layout: LayoutKind): PaneState {
  return { layout, slots: Array<string | null>(slotCount(layout)).fill(null), focused: 0 };
}

export function activeTabId(state: PaneState): string | null {
  return state.slots[state.focused] ?? null;
}

export function focusSlot(state: PaneState, index: number): PaneState {
  if (index < 0 || index >= state.slots.length || index === state.focused) return state;
  return { ...state, focused: index };
}

/** Focus the pane already showing the tab, or put the tab into the focused pane. */
export function showTab(state: PaneState, tabId: string): PaneState {
  const existing = state.slots.indexOf(tabId);
  if (existing !== -1) return { ...state, focused: existing };
  const slots = [...state.slots];
  slots[state.focused] = tabId;
  return { ...state, slots };
}

/** Change the split; the focused tab stays visible and new panes get hidden tabs. */
export function setLayout(state: PaneState, layout: LayoutKind, tabIds: string[]): PaneState {
  const count = slotCount(layout);
  const slots = state.slots.slice(0, count);
  while (slots.length < count) slots.push(null);
  let focused = state.focused;
  if (focused >= count) {
    slots[count - 1] = state.slots[focused];
    focused = count - 1;
  }
  const hidden = tabIds.filter((id) => !slots.includes(id));
  for (let i = 0; i < slots.length && hidden.length > 0; i += 1) {
    if (slots[i] === null) slots[i] = hidden.shift() ?? null;
  }
  return { layout, slots, focused };
}

/** Called before a tab closes. `allTabIds` is the tab order including the closing tab. */
export function removeTab(state: PaneState, tabId: string, allTabIds: string[]): PaneState {
  if (!state.slots.includes(tabId)) return state;
  const index = allTabIds.indexOf(tabId);
  const hidden = allTabIds.filter((id) => id !== tabId && !state.slots.includes(id));
  // Prefer the right-hand neighbour in the tab bar, then the left one.
  const replacement =
    hidden.find((id) => allTabIds.indexOf(id) > index) ?? hidden[hidden.length - 1] ?? null;
  return { ...state, slots: state.slots.map((id) => (id === tabId ? replacement : id)) };
}
