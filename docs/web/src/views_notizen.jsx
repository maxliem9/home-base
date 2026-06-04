/* HomeBase — Notizen */
function NoteEditor({ note, api, onClose }) {
  const [title, setTitle] = useState(note.title);
  const [content, setContent] = useState(note.content);
  const [tags, setTags] = useState(note.tags.join(", "));
  const [vis, setVis] = useState(note.visibility);

  const save = () => {
    api.updateNote(note.id, {
      title: title.trim() || "Ohne Titel", content,
      tags: tags.split(",").map((t) => t.trim()).filter(Boolean),
      visibility: vis,
    });
    onClose();
  };

  return (
    <Modal open onClose={onClose} title="Notiz bearbeiten" width={620}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button icon="check" onClick={save}>Speichern</Button>
      </>}>
      <Field label="Titel"><TextInput value={title} onChange={setTitle} placeholder="Titel" /></Field>
      <Field label="Inhalt (Markdown)">
        <textarea className="hb-input hb-mono-area" rows={11} value={content}
          onChange={(e) => setContent(e.target.value)} style={{ resize: "vertical", lineHeight: 1.55, fontSize: 14 }} />
      </Field>
      <div style={{ display: "grid", gridTemplateColumns: "1fr auto", gap: 16, alignItems: "end" }}>
        <Field label="Tags (Komma-getrennt)"><TextInput value={tags} onChange={setTags} placeholder="urlaub, reise" /></Field>
        <Field label="Sichtbarkeit">
          <div className="hb-pickrow">
            <button className={`hb-pick${vis === "shared" ? " is-active" : ""}`} onClick={() => setVis("shared")}><Icon name="users" size={16} stroke={2} /> Geteilt</button>
            <button className={`hb-pick${vis === "private" ? " is-active" : ""}`} onClick={() => setVis("private")}><Icon name="lock" size={16} stroke={2} /> Privat</button>
          </div>
        </Field>
      </div>
    </Modal>
  );
}

function NotizenView({ db, api }) {
  const [selId, setSelId] = useState(db.notes[0]?.id || null);
  const [query, setQuery] = useState("");
  const [activeTag, setActiveTag] = useState(null);
  const [editing, setEditing] = useState(null);

  const allTags = [...new Set(db.notes.flatMap((n) => n.tags))].sort();

  let list = db.notes;
  if (activeTag) list = list.filter((n) => n.tags.includes(activeTag));
  if (query.trim()) {
    const q = query.toLowerCase();
    list = list.filter((n) => n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q) || n.tags.some((t) => t.includes(q)));
  }
  list = [...list].sort((a, b) => (b.updated_at || "").localeCompare(a.updated_at || ""));

  const selected = db.notes.find((n) => n.id === selId) || list[0] || null;

  const createNote = () => {
    const n = api.addNote();
    setSelId(n.id);
    setEditing(n);
  };

  return (
    <div className="hb-page hb-page--notes">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{db.notes.length} Notizen</div>
          <h1>Notizen</h1>
        </div>
        <Button icon="plus" onClick={createNote}>Neue Notiz</Button>
      </div>

      <div className="hb-notes-layout">
        <div className="hb-notes-list">
          <div className="hb-quickadd hb-search" style={{ marginBottom: 12 }}>
            <Icon name="search" size={18} stroke={2} style={{ color: "var(--ink-3)" }} />
            <input value={query} placeholder="Volltextsuche …" onChange={(e) => setQuery(e.target.value)} />
          </div>
          {allTags.length > 0 && (
            <div className="hb-tagrow">
              <button className={`hb-tagchip${!activeTag ? " is-active" : ""}`} onClick={() => setActiveTag(null)}>Alle</button>
              {allTags.map((t) => (
                <button key={t} className={`hb-tagchip${activeTag === t ? " is-active" : ""}`} onClick={() => setActiveTag(activeTag === t ? null : t)}>#{t}</button>
              ))}
            </div>
          )}
          <div className="hb-notes-items">
            {list.length === 0 && <EmptyState icon="search" title="Nichts gefunden" />}
            {list.map((n) => (
              <button key={n.id} className={`hb-noteitem${selected && selected.id === n.id ? " is-active" : ""}`} onClick={() => setSelId(n.id)}>
                <div className="hb-noteitem__top">
                  <span className="hb-noteitem__title">{n.title}</span>
                  <Icon name={n.visibility === "private" ? "lock" : "users"} size={14} stroke={2} style={{ color: "var(--ink-3)", flexShrink: 0 }} />
                </div>
                <div className="hb-noteitem__preview">{n.content.replace(/[#*>`]/g, "").trim().slice(0, 70)}</div>
                <div className="hb-noteitem__meta">{HBfmt.relTime(n.updated_at)}{n.tags.length ? ` · #${n.tags[0]}` : ""}</div>
              </button>
            ))}
          </div>
        </div>

        <div className="hb-notes-detail">
          {!selected ? <Card className="hb-card--pad"><EmptyState icon="note" title="Keine Notiz ausgewählt" /></Card> : (
            <Card className="hb-card--pad hb-note-doc">
              <div className="hb-note-doc__head">
                <div>
                  <h2 className="hb-note-doc__title">{selected.title}</h2>
                  <div className="hb-note-doc__meta">
                    <Badge tone={selected.visibility === "private" ? "clay" : "accent"}>
                      <Icon name={selected.visibility === "private" ? "lock" : "users"} size={12} stroke={2} />
                      {selected.visibility === "private" ? "Privat" : "Geteilt"}
                    </Badge>
                    <span className="hb-muted" style={{ fontSize: 13 }}>von {HB.users[selected.created_by].name} · bearbeitet {HBfmt.relTime(selected.updated_at)}</span>
                  </div>
                </div>
                <div style={{ display: "flex", gap: 4 }}>
                  <IconButton icon="edit" label="Bearbeiten" onClick={() => setEditing(selected)} />
                  <IconButton icon="trash" label="Löschen" danger onClick={() => { api.deleteNote(selected.id); setSelId(null); }} />
                </div>
              </div>
              {selected.tags.length > 0 && (
                <div className="hb-tagrow" style={{ marginBottom: 18 }}>
                  {selected.tags.map((t) => <span key={t} className="hb-tagchip is-static">#{t}</span>)}
                </div>
              )}
              <div className="hb-md">{renderMarkdown(selected.content)}</div>
            </Card>
          )}
        </div>
      </div>

      {editing && <NoteEditor note={editing} api={api} onClose={() => setEditing(null)} />}
    </div>
  );
}

window.NotizenView = NotizenView;
