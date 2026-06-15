// HomeBase — shared UI primitives. Ported from the design handoff (ui.jsx)
// to TypeScript/React. Pure presentational components over the design tokens.
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  Fragment,
  type CSSProperties,
  type ReactNode,
  type KeyboardEvent,
  type RefObject,
} from 'react'
import { useTranslation } from 'react-i18next'
import { Icon } from './Icon'
import { userMeta } from './format'
import { useAvatarHues } from '../hooks/useAvatarHues'
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

// `hueOverride` (when given) wins over the roster/derived hue — used by the colour
// picker to preview a swatch before it is persisted. Otherwise the avatar reads the
// household-visible per-user override from the roster context (Teil von #100) and falls
// back to the username-hash hue (#160). null means "automatic" (use the derived hue).
export function Avatar({ user, size = 28, hueOverride }: { user?: string | null; size?: number; hueOverride?: number | null }) {
  const { hueOf } = useAvatarHues()
  const u = userMeta(user, hueOverride !== undefined ? hueOverride : hueOf(user))
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

// `action` (HB-12 / #228) is an optional primary action rendered under the hint —
// e.g. a "New recipe" button on a first-run empty list. Omit it for terminal/positive
// empty states ("all done", "everything bought") where there is nothing to prompt.
export function EmptyState({ icon, title, hint, action }: { icon: string; title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="hb-empty">
      <div className="hb-empty__icon"><Icon name={icon} size={26} stroke={1.6} /></div>
      <div className="hb-empty__title">{title}</div>
      {hint && <div className="hb-empty__hint">{hint}</div>}
      {action && <div className="hb-empty__action">{action}</div>}
    </div>
  )
}

// --- Modal -----------------------------------------------------------------

// Module-level stack of open overlays so Escape only dismisses the *topmost* one.
// Without this, a ConfirmDialog stacked over an open Sheet would close both on a
// single Escape (each attaches its own window listener), discarding the form the
// dialog is supposed to leave intact. Registration order == mount order == visual
// stacking order, so the last-mounted overlay wins.
let nextOverlayId = 1
const overlayStack: number[] = []

function useTopmostEscape(open: boolean, onClose: () => void) {
  // Hold the latest onClose in a ref so the effect can depend on `open` alone —
  // re-registering on every onClose identity change would reshuffle the stack
  // order and let Escape hit the wrong layer.
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    if (!open) return
    const id = nextOverlayId++
    overlayStack.push(id)
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape' && overlayStack[overlayStack.length - 1] === id) onCloseRef.current()
    }
    window.addEventListener('keydown', onKey as EventListener)
    return () => {
      window.removeEventListener('keydown', onKey as EventListener)
      const i = overlayStack.indexOf(id)
      if (i >= 0) overlayStack.splice(i, 1)
    }
  }, [open])
}

// Focus management for an open overlay (HB-11 / #227): on open move focus into the
// dialog, keep Tab / Shift+Tab cycling within it, and return focus to the element that
// opened it on close. Pairs with useTopmostEscape (Esc) and the dialog roles below for
// keyboard-complete modals/sheets. A child with autoFocus wins — child effects run before
// this parent effect, so we only seed focus when nothing inside is focused yet.
function useFocusTrap(open: boolean, ref: RefObject<HTMLElement>) {
  const openerRef = useRef<HTMLElement | null>(null)
  // Capture the element that had focus *before* the dialog opened, in a LAYOUT effect so it
  // runs during commit — ahead of a child's passive autoFocus effect. A passive effect here
  // would instead capture the autofocused field and "return" focus to a now-unmounted node
  // (i.e. to <body>) on close.
  useLayoutEffect(() => {
    if (open) openerRef.current = document.activeElement as HTMLElement | null
  }, [open])
  useEffect(() => {
    const container = ref.current
    if (!open || !container) return
    const SEL = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    const focusables = () => Array.from(container.querySelectorAll<HTMLElement>(SEL)).filter((el) => el.offsetParent !== null)
    // Seed focus only if a child autoFocus hasn't already placed it inside the dialog.
    if (!container.contains(document.activeElement)) (focusables()[0] ?? container).focus()
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Tab') return
      const els = focusables()
      if (els.length === 0) { e.preventDefault(); container.focus(); return }
      const first = els[0]
      const last = els[els.length - 1]
      const active = document.activeElement
      if (e.shiftKey && (active === first || active === container)) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && active === last) { e.preventDefault(); first.focus() }
    }
    container.addEventListener('keydown', onKey)
    return () => {
      container.removeEventListener('keydown', onKey)
      // Return focus to the opener, but only if it's still in the document.
      const opener = openerRef.current
      if (opener && document.contains(opener)) opener.focus()
    }
  }, [open, ref])
}

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
  const { t } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  const titleId = useId()
  useTopmostEscape(open, onClose)
  useFocusTrap(open, ref)
  if (!open) return null
  return (
    <div className="hb-modal-scrim" onClick={onClose}>
      <div
        className="hb-modal"
        style={{ width }}
        onClick={(e) => e.stopPropagation()}
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <div className="hb-modal__head">
          <h3 id={titleId}>{title}</h3>
          <IconButton icon="x" onClick={onClose} label={t('common.close')} />
        </div>
        <div className="hb-modal__body">{children}</div>
        {footer && <div className="hb-modal__foot">{footer}</div>}
      </div>
    </div>
  )
}

// --- Slide-over / bottom sheet ---------------------------------------------

// A slide-over panel: anchored to the right edge on desktop, full-width bottom
// sheet on mobile (≤640px). Dimmed backdrop closes on click; Escape closes too.
// Same head/body/foot anatomy as Modal so callers swap with no markup churn.
// Used for content-heavy pickers that feel cramped in a centered dialog (#48).
export function Sheet({
  open,
  onClose,
  title,
  children,
  footer,
  width = 440,
}: {
  open: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  footer?: ReactNode
  width?: number
}) {
  const { t } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  const titleId = useId()
  useTopmostEscape(open, onClose)
  useFocusTrap(open, ref)
  if (!open) return null
  return (
    <div className="hb-sheet-scrim" onClick={onClose}>
      <div
        className="hb-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        style={{ maxWidth: width }}
        onClick={(e) => e.stopPropagation()}
        ref={ref}
      >
        <div className="hb-sheet__head">
          <h3 id={titleId}>{title}</h3>
          <IconButton icon="x" onClick={onClose} label={t('common.close')} />
        </div>
        <div className="hb-sheet__body">{children}</div>
        {footer && <div className="hb-sheet__foot">{footer}</div>}
      </div>
    </div>
  )
}

// --- Confirm dialog ----------------------------------------------------------

// Custom confirm step for destructive or cross-person actions. Native
// window.confirm() is banned (#125): it can't be styled, ignores the modal
// conventions and is auto-dismissed in e2e runs. Renders above an open Sheet
// (same scrim z-index, later in the DOM), so a form can stay open behind it.
export function ConfirmDialog({ title, message, confirmLabel, danger, onConfirm, onClose }: {
  title: ReactNode
  message: ReactNode
  confirmLabel?: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <Modal
      open
      onClose={onClose}
      title={title}
      width={400}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={() => { onClose(); onConfirm() }}>
            {confirmLabel ?? t('common.confirm')}
          </Button>
        </>
      }
    >
      <p style={{ margin: 0 }}>{message}</p>
    </Modal>
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
  disabled,
  maxLength,
}: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoFocus?: boolean
  onKeyDown?: (e: KeyboardEvent<HTMLInputElement>) => void
  type?: string
  style?: CSSProperties
  className?: string
  disabled?: boolean
  maxLength?: number
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
      disabled={disabled}
      maxLength={maxLength}
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

export interface MarkdownOptions {
  // Resolve a `![alt](image:<id>)` reference to a node (e.g. an authed <img>).
  // Return null/undefined to fall back to the alt text. Keeps this generic
  // renderer free of any token/auth knowledge — NotesView supplies <AuthedImage>.
  resolveImage?: (imageId: string, alt: string) => ReactNode
}

// Only http(s), mailto and in-app relative targets may become real links/images;
// anything else (javascript:, data:, …) renders as plain text. The renderer stays
// XSS-safe because it builds React elements, never innerHTML — this guards the one
// place an attacker-controlled URL reaches the DOM (href / external img src).
// `\/(?!\/)` allows a single leading slash (in-app path) but rejects protocol-relative
// `//host` URLs, which look internal yet navigate off-site.
const SAFE_URL_RE = /^(https?:|mailto:|#|\/(?!\/))/i

export function renderMarkdown(md: string, opts: MarkdownOptions = {}): ReactNode[] {
  const lines = (md || '').split('\n')
  const out: ReactNode[] = []
  let list: ReactNode[] | null = null
  let listType: 'ul' | 'ol' | null = null
  let key = 0

  // `![alt](src)`: an `image:<id>` ref goes through resolveImage (authed attachment);
  // an external http(s) URL becomes a plain <img>; anything else degrades to alt text.
  const mdImage = (src: string, alt: string, i: number): ReactNode => {
    const ref = src.trim()
    const imageRef = ref.match(/^image:(.+)$/i)
    if (imageRef) {
      const node = opts.resolveImage?.(imageRef[1], alt)
      return node ? <Fragment key={`img${i}`}>{node}</Fragment> : alt || null
    }
    if (/^https?:\/\//i.test(ref)) {
      return <img key={`img${i}`} src={ref} alt={alt} className="hb-md-img" loading="lazy" />
    }
    return alt || null
  }

  // `[text](href)`: render an <a> only for allowlisted schemes; otherwise keep the words.
  const mdLink = (href: string, text: string, i: number): ReactNode => {
    const h = href.trim()
    if (!SAFE_URL_RE.test(h)) return text
    return <a key={`a${i}`} href={h} target="_blank" rel="noopener noreferrer">{text}</a>
  }

  const inline = (s: string): ReactNode[] => {
    const parts: ReactNode[] = []
    let rest = s
    let i = 0
    // image must precede link in the alternation: at a `!` the image arm wins, so
    // `![alt](src)` is never mis-parsed as the link `[alt](src)` one char to the right.
    // URLs are `[^)\s]+` (stop at the first `)` or space) — simple by design; a URL that
    // itself contains `)` (rare, e.g. Wikipedia) is truncated. Acceptable for a tiny renderer.
    const re = /(\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`|!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]+)\]\(([^)\s]+)\))/
    let m: RegExpExecArray | null
    while ((m = re.exec(rest))) {
      if (m.index > 0) parts.push(rest.slice(0, m.index))
      if (m[2] != null) parts.push(<strong key={`b${i}`}>{m[2]}</strong>)
      else if (m[3] != null) parts.push(<em key={`i${i}`}>{m[3]}</em>)
      else if (m[4] != null) parts.push(<code key={`c${i}`} className="hb-md-code">{m[4]}</code>)
      else if (m[6] != null) parts.push(mdImage(m[6], m[5] ?? '', i))
      else if (m[8] != null) parts.push(mdLink(m[8], m[7], i))
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
