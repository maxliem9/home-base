/* HomeBase — Aufgaben (Todos) */
function PlanModal({ todo, api, onClose }) {
  const [assignee, setAssignee] = useState(todo.assignee || "");
  const [due, setDue] = useState(todo.due_date || HB.iso(0));
  const [priority, setPriority] = useState(todo.priority || "MEDIUM");
  const [desc, setDesc] = useState(todo.description || "");
  const isPlanning = todo.status === "INBOX";

  const save = () => {
    api.updateTodo(todo.id, {
      assignee: assignee || null, due_date: due || null, priority,
      description: desc, status: todo.status === "INBOX" ? "PLANNED" : todo.status,
    });
    onClose();
  };

  return (
    <Modal open onClose={onClose} title={isPlanning ? "Aufgabe planen" : "Aufgabe bearbeiten"} width={480}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button onClick={save} icon={isPlanning ? "calendar" : "check"}>{isPlanning ? "Einplanen" : "Speichern"}</Button>
      </>}>
      <div style={{ fontFamily: "var(--font-display)", fontSize: 22, lineHeight: 1.2 }}>{todo.title}</div>
      <Field label="Beschreibung">
        <textarea className="hb-input" rows={2} value={desc} placeholder="Optionale Notiz …"
          onChange={(e) => setDesc(e.target.value)} style={{ resize: "vertical", lineHeight: 1.5 }} />
      </Field>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
        <Field label="Wer übernimmt?">
          <div className="hb-pickrow">
            <button className={`hb-pick${!assignee ? " is-active" : ""}`} onClick={() => setAssignee("")}>
              <Avatar user={null} size={22} /> Offen
            </button>
            {Object.values(HB.users).map((u) => (
              <button key={u.id} className={`hb-pick${assignee === u.id ? " is-active" : ""}`} onClick={() => setAssignee(u.id)}>
                <Avatar user={u} size={22} /> {u.name}
              </button>
            ))}
          </div>
        </Field>
        <Field label="Fällig am">
          <input type="date" className="hb-input" value={due || ""} onChange={(e) => setDue(e.target.value)} />
        </Field>
      </div>
      <Field label="Priorität">
        <div className="hb-pickrow">
          {Object.keys(PRIO).map((k) => (
            <button key={k} className={`hb-pick${priority === k ? " is-active" : ""}`} onClick={() => setPriority(k)}>
              <span className="hb-prio__dot" style={{ background: `oklch(0.6 0.13 ${PRIO[k].hue})` }} /> {PRIO[k].label}
            </button>
          ))}
        </div>
      </Field>
    </Modal>
  );
}

function TodoRow({ t, api, onEdit }) {
  const due = HBfmt.dueLabel(t.due_date);
  return (
    <div className={`hb-row${t.status === "DONE" ? " hb-row--done" : ""}`}>
      <Checkbox checked={t.status === "DONE"} hue={t.assignee ? HB.users[t.assignee].hue : null} onChange={() => api.toggleDone(t.id)} />
      <div className="hb-row__main hb-clickable" style={{ padding: "2px 6px", margin: "-2px -6px" }} onClick={() => onEdit(t)}>
        <div className="hb-row__title">{t.title}</div>
        <div className="hb-row__meta">
          {t.description ? <span style={{ maxWidth: 280, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{t.description}</span> : null}
          {t.priority && t.status !== "DONE" && <PriorityDot priority={t.priority} withLabel />}
          {t.status === "DONE" && t.done_at && <span>erledigt {HBfmt.relTime(t.done_at)}</span>}
        </div>
      </div>
      <div className="hb-row__right">
        {due && t.status !== "DONE" && <Badge tone={due.tone}>{due.text}</Badge>}
        {t.assignee ? <Avatar user={t.assignee} size={28} /> : (t.status === "INBOX"
          ? <Button size="sm" variant="soft" icon="calendar" onClick={() => onEdit(t)}>Planen</Button>
          : <Avatar user={null} size={28} />)}
        <div className="hb-row__actions">
          <IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteTodo(t.id)} />
        </div>
      </div>
    </div>
  );
}

function AufgabenView({ db, api, focus }) {
  const [seg, setSeg] = useState(focus?.seg || "INBOX");
  const [quick, setQuick] = useState("");
  const [editing, setEditing] = useState(null);

  useEffect(() => { if (focus?.seg) setSeg(focus.seg); }, [focus]);

  const inbox = db.todos.filter((t) => t.status === "INBOX");
  const planned = db.todos.filter((t) => t.status === "PLANNED")
    .sort((a, b) => (a.due_date || "9999").localeCompare(b.due_date || "9999"));
  const done = db.todos.filter((t) => t.status === "DONE")
    .sort((a, b) => (b.done_at || "").localeCompare(a.done_at || ""));

  const lists = { INBOX: inbox, PLANNED: planned, DONE: done };
  const current = lists[seg];

  const submitQuick = () => { if (!quick.trim()) return; api.addTodo(quick.trim()); setQuick(""); };

  // group planned by due bucket
  const renderPlanned = () => {
    const buckets = { over: [], today: [], soon: [], far: [], none: [] };
    planned.forEach((t) => {
      const d = HBfmt.dueLabel(t.due_date);
      buckets[d ? d.tone : "none"].push(t);
    });
    const order = [["over", "Überfällig"], ["today", "Heute"], ["soon", "Demnächst"], ["far", "Später"], ["none", "Ohne Datum"]];
    return order.filter(([k]) => buckets[k].length).map(([k, label]) => (
      <div key={k} style={{ marginBottom: 22 }}>
        <div className="hb-sectionlabel">{label}</div>
        <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
          <div className="hb-list">{buckets[k].map((t) => <TodoRow key={t.id} t={t} api={api} onEdit={setEditing} />)}</div>
        </Card>
      </div>
    ));
  };

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">Gemeinsam · Echtzeit</div>
          <h1>Aufgaben</h1>
        </div>
        <SegmentedControl value={seg} onChange={setSeg} options={[
          { value: "INBOX", label: "Inbox", count: inbox.length },
          { value: "PLANNED", label: "Geplant", count: planned.length },
          { value: "DONE", label: "Erledigt", count: done.length },
        ]} />
      </div>

      {seg === "INBOX" && (
        <div className="hb-quickadd" style={{ marginBottom: 22 }}>
          <Icon name="inbox" size={19} stroke={2} style={{ color: "var(--ink-3)" }} />
          <input value={quick} placeholder="Neue Aufgabe – nur Titel nötig …"
            onChange={(e) => setQuick(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") submitQuick(); }} />
          <Button size="sm" icon="plus" onClick={submitQuick} disabled={!quick.trim()}>Erfassen</Button>
        </div>
      )}

      {seg === "INBOX" && (
        inbox.length === 0
          ? <Card className="hb-card--pad"><EmptyState icon="checkCircle" title="Inbox ist leer" hint="Stark! Alles eingeplant." /></Card>
          : <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
              <div className="hb-list">{inbox.map((t) => <TodoRow key={t.id} t={t} api={api} onEdit={setEditing} />)}</div>
            </Card>
      )}

      {seg === "PLANNED" && (planned.length === 0
        ? <Card className="hb-card--pad"><EmptyState icon="calendar" title="Nichts geplant" hint="Plane Aufgaben aus der Inbox ein." /></Card>
        : renderPlanned())}

      {seg === "DONE" && (done.length === 0
        ? <Card className="hb-card--pad"><EmptyState icon="checkCircle" title="Noch nichts erledigt" /></Card>
        : <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
            <div className="hb-list">{done.map((t) => <TodoRow key={t.id} t={t} api={api} onEdit={setEditing} />)}</div>
          </Card>)}

      {editing && <PlanModal todo={editing} api={api} onClose={() => setEditing(null)} />}
    </div>
  );
}

window.AufgabenView = AufgabenView;
