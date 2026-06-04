// HomeBase — shared UI primitives. Ported from the design handoff (ui.jsx)
// to TypeScript/React. Pure presentational components over the design tokens.
import {
  useEffect,
  useRef,
  type CSSProperties,
  type ReactNode,
  type KeyboardEvent,
} from 'react'
import { Icon } from './Icon'
import { userMeta } from './format'
import { TodoPriority } from '../types'

// --- Page head -------------------------------------------------------------

export function PageHead({ eyebrow, title, actions }: { eyebrow?: string; title: string; actions?: ReactNode }) {
  return (
    <div className="hb-pagehead">
      <div>
        {eyebrow && <div className="hb-pagehead__eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
      </div>
      {actions && <div className="hb-pagehead__actions">{actions}</div>}
    </div>
  )
}

// --- Avatar ----------------------------------------------------------------

export function Avatar({ user, size = 28 }: { user?: string | null; size?: number }) {
  const u = userMeta(user)
  if (!u) {
    return (
      <div className="hb-avatar hb-avatar--empty" style={{ width: size, height: size, fontSize: size * 0.42 }}>
        <Icon name="users" size={size * 0.5} stroke={2} />
      </div>
    )
  }
  return (
    <div
      className="hb-avatar"
      title={u.name}
      style={{
        width: size,
        height: size,
        fontSize: size * 0.42,
        background: `oklch(0.92 0.045 ${u.hue})`,
        color: `oklch(0.42 0.09 ${u.hue})`,
      }}
    >
      {u.initials}
    </div>
  )
}

// --- Priority --------------------------------------------------------------

export const PRIO: Record<TodoPriority, { label: string; hue: number }> = {
  HIGH: { label: 'Hoch', hue: 32 },
  MEDIUM: { label: 'Mittel', hue: 75 },
  LOW: { label: 'Niedrig', hue: 200 },
}

export function PriorityDot({ priority, withLabel = false }: { priority?: TodoPriority; withLabel?: boolean }) {
  if (!priority) return null
  const p = PRIO[priority]
  return (
    <span className="hb-prio" style={{ color: `oklch(0.6 0.13 ${p.hue})` }}>
      <span className="hb-prio__dot" style={{ background: 'currentColor' }} />
      {withLabel && <span>{p.label}</span>}
    </span>
  )
}

// --- Badge -----------------------------------------------------------------

type BadgeTone = 'neutral' | 'accent' | 'clay' | 'today' | 'soon' | 'over' | 'far'

export function Badge({ children, tone = 'neutral', style }: { children: ReactNode; tone?: BadgeTone; style?: CSSProperties }) {
  return <span className={`hb-badge hb-badge--${tone}`} style={style}>{children}</span>
}

// --- Button ----------------------------------------------------------------

type Variant = 'primary' | 'secondary' | 'ghost' | 'soft' | 'danger'

export function Button({
  children,
  variant = 'primary',
  size = 'md',
  icon,
  onClick,
  type = 'button',
  disabled,
  style,
}: {
  children?: ReactNode
  variant?: Variant
  size?: 'md' | 'sm'
  icon?: string
  onClick?: () => void
  type?: 'button' | 'submit'
  disabled?: boolean
  style?: CSSProperties
}) {
  return (
    <button type={type} onClick={onClick} disabled={disabled} className={`hb-btn hb-btn--${variant} hb-btn--${size}`} style={style}>
      {icon && <Icon name={icon} size={size === 'sm' ? 16 : 18} stroke={2} />}
      {children && <span>{children}</span>}
    </button>
  )
}

export function IconButton({
  icon,
  onClick,
  label,
  active,
  size = 18,
  danger,
  disabled,
}: {
  icon: string
  onClick?: () => void
  label: string
  active?: boolean
  size?: number
  danger?: boolean
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      disabled={disabled}
      className={`hb-iconbtn${active ? ' is-active' : ''}${danger ? ' is-danger' : ''}`}
    >
      <Icon name={icon} size={size} stroke={2} />
    </button>
  )
}

// --- Card ------------------------------------------------------------------

export function Card({
  children,
  className = '',
  style,
  onClick,
}: {
  children: ReactNode
  className?: string
  style?: CSSProperties
  onClick?: () => void
}) {
  return (
    <div className={`hb-card ${className}`} style={style} onClick={onClick}>
      {children}
    </div>
  )
}

// --- Segmented control -----------------------------------------------------

export interface SegOption<T extends string> {
  value: T
  label: string
  count?: number
}

export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T
  onChange: (v: T) => void
  options: SegOption<T>[]
}) {
  return (
    <div className="hb-seg" role="tablist">
      {options.map((o) => (
        <button
          key={o.value}
          role="tab"
          aria-selected={value === o.value}
          className={`hb-seg__item${value === o.value ? ' is-active' : ''}`}
          onClick={() => onChange(o.value)}
        >
          {o.label}
          {o.count != null && <span className="hb-seg__count">{o.count}</span>}
        </button>
      ))}
    </div>
  )
}

// --- Checkbox --------------------------------------------------------------

export function Checkbox({ checked, onChange, hue }: { checked: boolean; onChange: (v: boolean) => void; hue?: number }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      className={`hb-check${checked ? ' is-checked' : ''}`}
      onClick={(e) => {
        e.stopPropagation()
        onChange(!checked)
      }}
      style={hue != null && checked ? { background: `oklch(0.62 0.11 ${hue})`, borderColor: `oklch(0.62 0.11 ${hue})` } : undefined}
    >
      {checked && <Icon name="check" size={14} stroke={2.6} />}
    </button>
  )
}

// --- Empty state -----------------------------------------------------------

export function EmptyState({ icon, title, hint }: { icon: string; title: string; hint?: string }) {
  return (
    <div className="hb-empty">
      <div className="hb-empty__icon"><Icon name={icon} size={26} stroke={1.6} /></div>
      <div className="hb-empty__title">{title}</div>
      {hint && <div className="hb-empty__hint">{hint}</div>}
    </div>
  )
}

// --- Modal -----------------------------------------------------------------

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  width = 460,
}: {
  open: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  footer?: ReactNode
  width?: number
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent | globalThis.KeyboardEvent) => {
      if ((e as globalThis.KeyboardEvent).key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey as EventListener)
    return () => window.removeEventListener('keydown', onKey as EventListener)
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="hb-modal-scrim" onClick={onClose}>
      <div className="hb-modal" style={{ width }} onClick={(e) => e.stopPropagation()}>
        <div className="hb-modal__head">
          <h3>{title}</h3>
          <IconButton icon="x" onClick={onClose} label="Schließen" />
        </div>
        <div className="hb-modal__body">{children}</div>
        {footer && <div className="hb-modal__foot">{footer}</div>}
      </div>
    </div>
  )
}

// --- Form bits -------------------------------------------------------------

export function Field({ label, children, hint }: { label?: string; children: ReactNode; hint?: string }) {
  return (
    <label className="hb-field">
      {label && <span className="hb-field__label">{label}</span>}
      {children}
      {hint && <span className="hb-field__hint">{hint}</span>}
    </label>
  )
}

export function TextInput({
  value,
  onChange,
  placeholder,
  autoFocus,
  onKeyDown,
  type = 'text',
  style,
  className = '',
}: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoFocus?: boolean
  onKeyDown?: (e: KeyboardEvent<HTMLInputElement>) => void
  type?: string
  style?: CSSProperties
  className?: string
}) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (autoFocus && ref.current) ref.current.focus()
  }, [autoFocus])
  return (
    <input
      ref={ref}
      type={type}
      className={`hb-input ${className}`.trim()}
      value={value}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={onKeyDown}
      style={style}
    />
  )
}

export function Select({
  value,
  onChange,
  children,
  style,
}: {
  value: string
  onChange: (v: string) => void
  children: ReactNode
  style?: CSSProperties
}) {
  return (
    <div className="hb-select-wrap" style={style}>
      <select className="hb-select" value={value} onChange={(e) => onChange(e.target.value)}>
        {children}
      </select>
      <Icon name="chevronDown" size={16} stroke={2} className="hb-select-caret" />
    </div>
  )
}

// --- Tiny markdown renderer ------------------------------------------------

export function renderMarkdown(md: string): ReactNode[] {
  const lines = (md || '').split('\n')
  const out: ReactNode[] = []
  let list: ReactNode[] | null = null
  let listType: 'ul' | 'ol' | null = null
  let key = 0

  const inline = (s: string): ReactNode[] => {
    const parts: ReactNode[] = []
    let rest = s
    let i = 0
    const re = /(\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`)/
    let m: RegExpExecArray | null
    while ((m = re.exec(rest))) {
      if (m.index > 0) parts.push(rest.slice(0, m.index))
      if (m[2] != null) parts.push(<strong key={`b${i}`}>{m[2]}</strong>)
      else if (m[3] != null) parts.push(<em key={`i${i}`}>{m[3]}</em>)
      else if (m[4] != null) parts.push(<code key={`c${i}`} className="hb-md-code">{m[4]}</code>)
      rest = rest.slice(m.index + m[0].length)
      i++
    }
    if (rest) parts.push(rest)
    return parts
  }

  const flush = () => {
    if (list) {
      const Tag = listType === 'ol' ? 'ol' : 'ul'
      out.push(<Tag key={`l${key++}`} className="hb-md-list">{list}</Tag>)
      list = null
      listType = null
    }
  }

  lines.forEach((raw) => {
    const line = raw.trimEnd()
    let m: RegExpMatchArray | null
    if ((m = line.match(/^(#{1,3})\s+(.*)/))) {
      flush()
      const lvl = m[1].length
      const Tag = (`h${lvl + 2}`) as 'h3' | 'h4' | 'h5'
      out.push(<Tag key={`h${key++}`} className={`hb-md-h hb-md-h${lvl}`}>{inline(m[2])}</Tag>)
    } else if ((m = line.match(/^>\s?(.*)/))) {
      flush()
      out.push(<blockquote key={`q${key++}`} className="hb-md-quote">{inline(m[1])}</blockquote>)
    } else if ((m = line.match(/^[-*]\s+(.*)/))) {
      if (listType !== 'ul') flush()
      listType = 'ul'
      list = list || []
      list.push(<li key={`li${key++}`}>{inline(m[1])}</li>)
    } else if ((m = line.match(/^\d+\.\s+(.*)/))) {
      if (listType !== 'ol') flush()
      listType = 'ol'
      list = list || []
      list.push(<li key={`li${key++}`}>{inline(m[1])}</li>)
    } else if (line === '') {
      flush()
    } else {
      flush()
      out.push(<p key={`p${key++}`} className="hb-md-p">{inline(line)}</p>)
    }
  })
  flush()
  return out
}
