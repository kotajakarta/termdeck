import assert from "node:assert/strict";
import { test } from "node:test";

import {
  activeTabId,
  defaultTrackSizes,
  emptyPanes,
  focusSlot,
  gridTemplateAreasFor,
  layoutDimensions,
  removeTab,
  resizeTrack,
  setLayout,
  showTab,
} from "../src/layout.ts";

test("single layout: showing a tab replaces the visible one", () => {
  let s = emptyPanes("1");
  s = showTab(s, "a");
  assert.deepEqual(s.slots, ["a"]);
  s = showTab(s, "b");
  assert.deepEqual(s.slots, ["b"]);
  assert.equal(activeTabId(s), "b");
});

test("showing an already visible tab only moves focus to its pane", () => {
  let s = setLayout(emptyPanes("1"), "2v", []);
  s = showTab(s, "a");
  s = focusSlot(s, 1);
  s = showTab(s, "b");
  assert.deepEqual(s.slots, ["a", "b"]);
  s = showTab(s, "a");
  assert.deepEqual(s.slots, ["a", "b"]);
  assert.equal(s.focused, 0);
  assert.equal(activeTabId(s), "a");
});

test("new tab goes to the focused pane", () => {
  let s = setLayout(emptyPanes("1"), "4", []);
  s = focusSlot(s, 2);
  s = showTab(s, "x");
  assert.deepEqual(s.slots, [null, null, "x", null]);
});

test("growing the layout fills new panes with hidden tabs in order", () => {
  let s = showTab(emptyPanes("1"), "b");
  s = setLayout(s, "4", ["a", "b", "c"]);
  assert.deepEqual(s.slots, ["b", "a", "c", null]);
  assert.equal(s.focused, 0);
});

test("shrinking keeps the focused tab visible", () => {
  let s = setLayout(emptyPanes("1"), "4", []);
  for (const [i, id] of ["a", "b", "c", "d"].entries()) s = showTab(focusSlot(s, i), id);
  s = focusSlot(s, 3);
  s = setLayout(s, "1", ["a", "b", "c", "d"]);
  assert.deepEqual(s.slots, ["d"]);
  assert.equal(s.focused, 0);
  s = setLayout(setLayout(s, "4", ["a", "b", "c", "d"]), "2h", ["a", "b", "c", "d"]);
  assert.deepEqual(s.slots, ["d", "a"]);
});

test("closing a visible tab shows a hidden neighbour, or leaves the pane empty", () => {
  let s = setLayout(emptyPanes("1"), "2v", []);
  s = showTab(s, "a");
  s = showTab(focusSlot(s, 1), "b");
  s = removeTab(s, "b", ["a", "b", "c"]);
  assert.deepEqual(s.slots, ["a", "c"]);
  s = removeTab(s, "c", ["a", "c"]);
  assert.deepEqual(s.slots, ["a", null]);
  s = removeTab(s, "a", ["a"]);
  assert.deepEqual(s.slots, [null, null]);
  assert.equal(activeTabId(s), null);
});

test("closing a hidden tab changes nothing", () => {
  let s = showTab(emptyPanes("1"), "a");
  s = removeTab(s, "z", ["a", "z"]);
  assert.deepEqual(s.slots, ["a"]);
});

test("layoutDimensions: correctly returns dimensions for preset and custom layouts", () => {
  assert.deepEqual(layoutDimensions("1"), { rows: 1, cols: 1, slots: 1 });
  assert.deepEqual(layoutDimensions("2v"), { rows: 1, cols: 2, slots: 2 });
  assert.deepEqual(layoutDimensions("2h"), { rows: 2, cols: 1, slots: 2 });
  assert.deepEqual(layoutDimensions("3v"), { rows: 1, cols: 3, slots: 3 });
  assert.deepEqual(layoutDimensions("4"), { rows: 2, cols: 2, slots: 4 });
  assert.deepEqual(layoutDimensions("2x3"), { rows: 2, cols: 3, slots: 6 });
  assert.deepEqual(layoutDimensions("3x1"), { rows: 3, cols: 1, slots: 3 });
  // Unknown or invalid falls back to 1
  assert.deepEqual(layoutDimensions("invalid"), { rows: 1, cols: 1, slots: 1 });
});

test("gridTemplateAreasFor: correctly constructs grid template areas string", () => {
  assert.equal(gridTemplateAreasFor(1, 3), '"p0 p1 p2"');
  assert.equal(gridTemplateAreasFor(2, 2), '"p0 p1" "p2 p3"');
  assert.equal(gridTemplateAreasFor(2, 3), '"p0 p1 p2" "p3 p4 p5"');
});

test("defaultTrackSizes and resizeTrack: calculates and enforces constraints", () => {
  assert.deepEqual(defaultTrackSizes(2), [50, 50]);
  const three = defaultTrackSizes(3);
  assert.equal(three.length, 3);
  assert(Math.abs(three[0] + three[1] + three[2] - 100) < 0.001);

  // Resize track 0 with delta +10
  const resized = resizeTrack([50, 50], 0, 10, 10);
  assert.deepEqual(resized, [60, 40]);

  // Enforces minPct clamp
  const clamped = resizeTrack([20, 80], 0, -15, 10);
  assert.deepEqual(clamped, [10, 90]);
});

test("3v preset layout transitions correctly with tabs", () => {
  let s = emptyPanes("3v");
  assert.deepEqual(s.slots, [null, null, null]);
  s = showTab(s, "tab1");
  s = focusSlot(s, 1);
  s = showTab(s, "tab2");
  s = focusSlot(s, 2);
  s = showTab(s, "tab3");
  assert.deepEqual(s.slots, ["tab1", "tab2", "tab3"]);
});

test("custom layout 2x3 expands slots and preserves focused tab", () => {
  let s = showTab(emptyPanes("1"), "a");
  s = setLayout(s, "2x3", ["a", "b", "c", "d"]);
  assert.deepEqual(s.slots, ["a", "b", "c", "d", null, null]);
  assert.equal(s.slots.length, 6);
});

