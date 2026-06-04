/* HomeBase — shared UI primitives. Loaded as Babel. Exports to window. */
const { useState, useEffect, useRef } = React;

/* Person avatar — uses per-user hue */
function Avatar({ user, size = 28 }) {
  const u = typeof user === "string" ? HB.users[user] : user;
  if (!u) return (
    <div className="hb-avatar hb-avatar--empty" style={{ width: size, height: size, fontSize: size * 0.42 }}>
      <Icon name="users" size={size * 0.5} stroke={2} />
    </div>
  );
  return (
    <div className="hb-avatar" title={u.name}
      style={{
        width: size, height: size, fontSize: size * 0.42,
        background: `oklch(0.92 0.045 ${u.hue})`,
        color: `oklch(0.42 0.09 ${u.hue})`,
      }}>
      {u.initials}
    </div>
  );
}

const PRIO = {
  HIGH: { label: "Hoch", hue: 32 },
  MEDIUM: { label: "Mittel", hue: 75 },
  LOW: { label: "Niedrig", hue: 200 },
};

function PriorityDot({ priority, withLabel = false }) {
  if (!priority) return null;
  const p = PRIO[priority];
  return (
    <span className="hb-prio" style={{ color: `oklch(0.6 0.13 ${p.hue})` }}>
      <span className="hb-prio__dot" style={{ background: "currentColor" }} />
      {withLabel && <span className="hb-prio__label">{p.label}</span>}
    </span>
  );
}

function Badge({ children, tone = "neutral", style }) {
  return <span className={`hb-badge hb-badge--${tone}`} style={style}>{children}</span>;
}

function Button({ children, variant = "primary", size = "md", icon, onClick, type = "button", disabled, style }) {
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className={`hb-btn hb-btn--${variant} hb-btn--${size}`} style={style}>
      {icon && <Icon name={icon} size={size === "sm" ? 16 : 18} stroke={2} />}
      {children && <span>{children}</span>}
    </button>
  );
}

function IconButton({ icon, onClick, label, active, size = 18, style, danger }) {
  return (
    <button type="button" onClick={onClick} aria-label={label} title={label}
      className={`hb-iconbtn${active ? " is-active" : ""}${danger ? " is-danger" : ""}`} style={style}>
      <Icon name={icon} size={size} stroke={2} />
    </button>
  );
}

function Card({ children, className = "", style, onClick, as = "div" }) {
  const Tag = as;
  return (
    <Tag className={`hb-card ${className}`} style={style} onClick={onClick}>
      {children}
    </Tag>
  );
}

function SegmentedControl({ value, onChange, options }) {
  return (
    <div className="hb-seg" role="tablist">
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={value === o.value}
          className={`hb-seg__item${value === o.value ? " is-active" : ""}`}
          onClick={() => onChange(o.value)}>
          {o.label}
          {o.count != null && <span className="hb-seg__count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

function Checkbox({ checked, onChange, hue }) {
  return (
    <button type="button" role="checkbox" aria-checked={checked}
      className={`hb-check${checked ? " is-checked" : ""}`}
      onClick={(e) => { e.stopPropagation(); onChange(!checked); }}
      style={hue != null && checked ? { background: `oklch(0.62 0.11 ${hue})`, borderColor: `oklch(0.62 0.11 ${hue})` } : undefined}>
      {checked && <Icon name="check" size={14} stroke={2.6} />}
    </button>
  );
}

function EmptyState({ icon, title, hint }) {
  return (
    <div className="hb-empty">
      <div className="hb-empty__icon"><Icon name={icon} size={26} stroke={1.6} /></div>
      <div className="hb-empty__title">{title}</div>
      {hint && <div className="hb-empty__hint">{hint}</div>}
    </div>
  );
}

/* Modal / sheet */
function Modal({ open, onClose, title, children, footer, width = 460 }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
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
  );
}

function Field({ label, children, hint }) {
  return (
    <label className="hb-field">
      {label && <span className="hb-field__label">{label}</span>}
      {children}
      {hint && <span className="hb-field__hint">{hint}</span>}
    </label>
  );
}

function TextInput({ value, onChange, placeholder, autoFocus, onKeyDown, type = "text", style }) {
  const ref = useRef(null);
  useEffect(() => { if (autoFocus && ref.current) ref.current.focus(); }, [autoFocus]);
  return (
    <input ref={ref} type={type} className="hb-input" value={value} placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)} onKeyDown={onKeyDown} style={style} />
  );
}

function Select({ value, onChange, children, style }) {
  return (
    <div className="hb-select-wrap" style={style}>
      <select className="hb-select" value={value} onChange={(e) => onChange(e.target.value)}>
        {children}
      </select>
      <Icon name="chevronDown" size={16} stroke={2} className="hb-select-caret" />
    </div>
  );
}

/* Tiny markdown renderer (headings, bold, italic, blockquote, lists) */
function renderMarkdown(md) {
  const lines = (md || "").split("\n");
  const out = [];
  let list = null; let listType = null; let key = 0;
  const inline = (s) => {
    const parts = [];
    let rest = s; let m; let i = 0;
    const re = /(\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`)/;
    while ((m = re.exec(rest))) {
      if (m.index > 0) parts.push(rest.slice(0, m.index));
      if (m[2] != null) parts.push(<strong key={`b${i}`}>{m[2]}</strong>);
      else if (m[3] != null) parts.push(<em key={`i${i}`}>{m[3]}</em>);
      else if (m[4] != null) parts.push(<code key={`c${i}`} className="hb-md-code">{m[4]}</code>);
      rest = rest.slice(m.index + m[0].length); i++;
    }
    if (rest) parts.push(rest);
    return parts;
  };
  const flush = () => {
    if (list) {
      const Tag = listType === "ol" ? "ol" : "ul";
      out.push(<Tag key={`l${key++}`} className="hb-md-list">{list}</Tag>);
      list = null; listType = null;
    }
  };
  lines.forEach((raw) => {
    const line = raw.trimEnd();
    let m;
    if ((m = line.match(/^(#{1,3})\s+(.*)/))) {
      flush();
      const lvl = m[1].length;
      const Tag = `h${lvl + 2}`;
      out.push(<Tag key={`h${key++}`} className={`hb-md-h hb-md-h${lvl}`}>{inline(m[2])}</Tag>);
    } else if ((m = line.match(/^>\s?(.*)/))) {
      flush();
      out.push(<blockquote key={`q${key++}`} className="hb-md-quote">{inline(m[1])}</blockquote>);
    } else if ((m = line.match(/^[-*]\s+(.*)/))) {
      if (listType !== "ul") flush();
      listType = "ul"; list = list || [];
      list.push(<li key={`li${key++}`}>{inline(m[1])}</li>);
    } else if ((m = line.match(/^\d+\.\s+(.*)/))) {
      if (listType !== "ol") flush();
      listType = "ol"; list = list || [];
      list.push(<li key={`li${key++}`}>{inline(m[1])}</li>);
    } else if (line === "") {
      flush();
    } else {
      flush();
      out.push(<p key={`p${key++}`} className="hb-md-p">{inline(line)}</p>);
    }
  });
  flush();
  return out;
}

Object.assign(window, {
  Avatar, PriorityDot, PRIO, Badge, Button, IconButton, Card,
  SegmentedControl, Checkbox, EmptyState, Modal, Field, TextInput, Select,
  renderMarkdown,
});
