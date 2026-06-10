// HomeBase — stroke-glyph icon set. Ported from the design handoff (icons.jsx).
// Each path is drawn on a 24×24 viewBox; stroke = currentColor.
import type { CSSProperties } from 'react'

const PATHS: Record<string, string> = {
  home: 'M3 11.5 12 4l9 7.5M5 10v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-9',
  check: 'M4 12.5 9 17.5 20 6.5',
  checkCircle: 'M9 12.5 11 14.5 15.5 9.5 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  circle: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  cart: 'M3 4h2l2.4 12.2a1 1 0 0 0 1 .8h8.2a1 1 0 0 0 1-.8L21 8H6 M10 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z M17 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z',
  note: 'M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z M14 3v5h5',
  image: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z M8.5 11a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z M21 16l-5-5L5 20',
  clock: 'M12 7v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  chef: 'M7 21h10 M8 17h8v-2a4 4 0 1 0-2.5-7.4 3.5 3.5 0 0 0-7 0A4 4 0 1 0 8 15v2Z',
  play: 'M8 5.5v13l11-6.5-11-6.5Z',
  stop: 'M7 7h10v10H7z',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z M21 21l-4.3-4.3',
  tag: 'M3 3h7l11 11-7 7L3 10V3Z M7.5 7.5h.01',
  trash: 'M4 7h16 M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2 M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13',
  edit: 'M4 20h4L19 9l-4-4L4 16v4Z M14 6l4 4',
  x: 'M6 6l12 12M18 6 6 18',
  chevronRight: 'M9 6l6 6-6 6',
  chevronLeft: 'M15 6l-6 6 6 6',
  chevronDown: 'M6 9l6 6 6-6',
  calendar: 'M4 6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z M4 9h16 M8 3v4 M16 3v4',
  inbox: 'M4 13h4l1.5 3h5L16 13h4 M4 13 6 5h12l2 8v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6Z',
  flag: 'M5 21V4 M5 4h12l-2 4 2 4H5',
  lock: 'M7 10V8a5 5 0 0 1 10 0v2 M5 10h14v10H5z',
  users: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z M2.5 20a6.5 6.5 0 0 1 13 0 M16 4.5a3.5 3.5 0 0 1 0 7 M18 14.2A6.5 6.5 0 0 1 21.5 20',
  archive: 'M4 7h16v3H4z M5 10h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-9Z M10 14h4',
  send: 'M4 11.5 20 4l-6 16-2.5-7L4 11.5Z',
  sun: 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10Z M12 1v2 M12 21v2 M4.2 4.2l1.4 1.4 M18.4 18.4l1.4 1.4 M1 12h2 M21 12h2 M4.2 19.8l1.4-1.4 M18.4 5.6l1.4-1.4',
  timer: 'M10 2h4 M12 14l3-3 M19 14a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z',
  logout: 'M14 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4 M9 12h11 M16 8l4 4-4 4',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z M19.4 13a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 8 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H2a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 3.4 8a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H8a1.7 1.7 0 0 0 1-1.5V2a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V8a1.7 1.7 0 0 0 1.5 1H22a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z',
  dot: 'M12 12m-3 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0',
  download: 'M12 4v11 M8 11l4 4 4-4 M5 20h14',
  repeat: 'M17 2l4 4-4 4 M21 6H7a4 4 0 0 0-4 4v1 M7 22l-4-4 4-4 M3 18h14a4 4 0 0 0 4-4v-1',
  folder: 'M4 6a1 1 0 0 1 1-1h4l2 2h8a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z',
}

export type IconName = keyof typeof PATHS

interface IconProps {
  name: string
  size?: number
  stroke?: number
  fill?: boolean
  className?: string
  style?: CSSProperties
}

export function Icon({ name, size = 20, stroke = 1.8, fill = false, className, style }: IconProps) {
  const d = PATHS[name] ?? PATHS.dot
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={fill ? 'currentColor' : 'none'}
      stroke={fill ? 'none' : 'currentColor'}
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={{ flexShrink: 0, ...style }}
    >
      <path d={d} />
    </svg>
  )
}
