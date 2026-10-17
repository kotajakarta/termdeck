const PATHS = {
  plus: "M12 5v14M5 12h14",
  terminal: "M4 6l5 5-5 5M11 18h9",
  folder: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
  edit: "M4 20h4L19 9l-4-4L4 16zM14 6l4 4",
  trash: "M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
  refresh: "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7",
  chevron: "M9 6l6 6-6 6",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4",
  close: "M6 6l12 12M18 6L6 18",
  up: "M12 19V5M6 11l6-6 6 6",
  bolt: "M13 3L5 14h6l-1 7 8-11h-6z",
  server: "M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01",
  lock: "M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3",
  key: "M14 10a4 4 0 1 0-3.5 3.97L10 14v2H8v2H6v2H3v-3l6.03-6.03A4 4 0 0 0 14 10zM15.5 8.5h.01",
  layout1: "M4 5h16v14H4z",
  layout2v: "M4 5h16v14H4zM12 5v14",
  layout3v: "M4 5h16v14H4zM9.33 5v14M14.67 5v14",
  layout2h: "M4 5h16v14H4zM4 12h16",
  layout4: "M4 5h16v14H4zM12 5v14M4 12h16",
  layoutCustom: "M4 5h16v14H4zM9.33 5v14M14.67 5v14M4 12h16",
  jump: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM10 13h6M13 10l3 3-3 3",
  newTab: "M4 6h16v13H4zM4 10h16M12 13v4M10 15h4",
  grip: "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01",
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
