/* HomeBase — Aufgaben (Todos) */
function NewTodoListModal({ api, onClose, onCreated }) {
  const [name, setName] = useState("");
  const [vis, setVis] = useState("shared");
  const create = () => { if (!name.trim()) return; const l = api.addTodoList(name.trim(), vis); onCreated(l); };
  return (
    <Modal open onClose={onClose} title="Neue Liste" width={440}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button variant="primary" icon="check" onClick={create} disabled={!name.trim()}>Erstellen</Button>
      </>}>
      <Field label="Name">
        <TextInput value={name} onChange={setName} placeholder="z. B. Renovierung"
          autoFocus onKeyDown={(e) => { if (e.key === "Enter") create(); }} />
      </Field>
      <Field label="Sichtbarkeit">
        <div className="hb-pickrow">
          <button className={`hb-pick${vis === "shared" ? " is-active" : ""}`} onClick={() => setVis("shared")}><Icon name="users" size={16} stroke={2} /> Geteilt</button>
          <button className={`hb-pick${vis === "private" ? " is-active" : ""}`} onClick={() => setVis("private")}><Icon name="lock" size={16} stroke={2} /> Privat</button>
        </div>
      </Field>
    </Modal>
  );
}

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

function SubtaskPanel({ t, api }) {
  const [adding, setAdding] = useState("");
  const subs = t.subtasks || [];
  const submit = () => { if (!adding.trim()) return; api.addSubtask(t.id, adding.trim()); setAdding(""); };
  return (
    <div className="hb-subtasks">
      {subs.map((s) => (
        <div key={s.id} className={`hb-subrow${s.done ? " is-done" : ""}`}>
          <Checkbox checked={s.done} onChange={() => api.toggleSubtask(t.id, s.id)} />
          <span className="hb-subrow__title">{s.title}</span>
          <div className="hb-row__actions"><IconButton icon="trash" label="Löschen" danger size={14} onClick={() => api.deleteSubtask(t.id, s.id)} /></div>
        </div>
      ))}
      <div className="hb-subadd">
        <Icon name="plus" size={15} stroke={2.2} style={{ color: "var(--ink-3)" }} />
        <input value={adding} placeholder="Unteraufgabe hinzufügen …"
          onChange={(e) => setAdding(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />
      </div>
    </div>
  );
}

function TodoRow({ t, api, onEdit }) {
  const [open, setOpen] = useState(false);
  const subs = t.subtasks || [];
  const total = subs.length;
  const done = subs.filter((s) => s.done).length;
  const due = HBfmt.dueLabel(t.due_date);
  return (
    <div className="hb-todo">
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
          <button className={`hb-subtoggle${open ? " is-open" : ""}${total ? "" : " is-empty"}`}
            onClick={() => setOpen((v) => !v)} title="Unteraufgaben">
            <Icon name="checkCircle" size={14} stroke={2} />
            {total > 0 && <span className="hb-subtoggle__c">{done}/{total}</span>}
            <Icon name="chevronDown" size={13} stroke={2.4} className="hb-subtoggle__chev" />
          </button>
          {due && t.status !== "DONE" && <Badge tone={due.tone}>{due.text}</Badge>}
          {t.assignee ? <Avatar user={t.assignee} size={28} /> : (t.status !== "DONE" && !t.due_date
            ? <Button size="sm" variant="soft" icon="calendar" onClick={() => onEdit(t)}>Planen</Button>
            : <Avatar user={null} size={28} />)}
          <div className="hb-row__actions">
            <IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteTodo(t.id)} />
          </div>
        </div>
      </div>
      {open && <SubtaskPanel t={t} api={api} />}
    </div>
  );
}

function AufgabenView({ db, api, focus }) {
  const ME = "max";
  const visibleLists = db.todoLists.filter((l) => l.visibility !== "private" || l.created_by === ME);
  const [activeId, setActiveId] = useState(visibleLists[0] ? visibleLists[0].id : null);
  const [quick, setQuick] = useState("");
  const [editing, setEditing] = useState(null);
  const [newListOpen, setNewListOpen] = useState(false);
  const [doneOpen, setDoneOpen] = useState(false);

  const active = visibleLists.find((l) => l.id === activeId) || visibleLists[0] || null;
  const listTodos = active ? db.todos.filter((t) => t.list_id === active.id) : [];
  const openCount = (id) => db.todos.filter((t) => t.list_id === id && t.status !== "DONE").length;

  const openTodos = listTodos.filter((t) => t.status !== "DONE");
  const done = listTodos.filter((t) => t.status === "DONE")
    .sort((a, b) => (b.done_at || "").localeCompare(a.done_at || ""));

  const submitQuick = () => { if (!quick.trim() || !active) return; api.addTodo(quick.trim(), active.id); setQuick(""); };

  const removeList = () => {
    if (!active || visibleLists.length <= 1) return;
    if (!confirm(`Liste „${active.name}“ und alle Aufgaben darin löschen?`)) return;
    const idx = visibleLists.findIndex((l) => l.id === active.id);
    const next = visibleLists[idx + 1] || visibleLists[idx - 1];
    api.deleteTodoList(active.id);
    setActiveId(next ? next.id : null);
  };

  // open todos, grouped by due bucket
  const buckets = { over: [], today: [], soon: [], far: [], none: [] };
  openTodos.forEach((t) => {
    const d = HBfmt.dueLabel(t.due_date);
    buckets[d ? d.tone : "none"].push(t);
  });
  // sort each bucket by date, then by priority weight
  Object.values(buckets).forEach((b) => b.sort((a, c) => (a.due_date || "9999").localeCompare(c.due_date || "9999")));
  const order = [["over", "Überfällig"], ["today", "Heute"], ["soon", "Demnächst"], ["far", "Später"], ["none", "Ohne Datum"]];
  const groups = order.filter(([k]) => buckets[k].length);

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">Gemeinsam · Echtzeit</div>
          <h1>Aufgaben</h1>
        </div>
      </div>

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist">
        {visibleLists.map((l) => (
          <button key={l.id} role="tab" aria-selected={active && l.id === active.id}
            className={`hb-tab${active && l.id === active.id ? " is-active" : ""}`}
            onClick={() => setActiveId(l.id)}>
            {l.visibility === "private" && <Icon name="lock" size={13} stroke={2} style={{ opacity: 0.7 }} />}
            {l.name}
            {openCount(l.id) > 0 && <span className="hb-tab__count">{openCount(l.id)}</span>}
          </button>
        ))}
        <button className="hb-tab hb-tab--add" onClick={() => setNewListOpen(true)}>
          <Icon name="plus" size={16} stroke={2.2} />Neue Liste
        </button>
      </div>

      {!active ? (
        <Card className="hb-card--pad"><EmptyState icon="inbox" title="Noch keine Liste" hint="Lege oben deine erste Aufgabenliste an." /></Card>
      ) : (
        <>
          <div className="hb-quickadd" style={{ marginBottom: 24 }}>
            <Icon name="plus" size={19} stroke={2} style={{ color: "var(--ink-3)" }} />
            <input value={quick} placeholder={`Neue Aufgabe in „${active.name}“ …`}
              onChange={(e) => setQuick(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") submitQuick(); }} />
            <Button size="sm" icon="plus" onClick={submitQuick} disabled={!quick.trim()}>Erfassen</Button>
          </div>

          {openTodos.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="checkCircle" title="Alles erledigt" hint="Keine offenen Aufgaben in dieser Liste." /></Card>
          ) : groups.map(([k, label]) => (
            <div key={k} style={{ marginBottom: 22 }}>
              <div className="hb-sectionlabel">{label} <span className="hb-mono" style={{ color: "var(--ink-3)", fontWeight: 500 }}>{buckets[k].length}</span></div>
              <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                <div className="hb-list">{buckets[k].map((t) => <TodoRow key={t.id} t={t} api={api} onEdit={setEditing} />)}</div>
              </Card>
            </div>
          ))}

          {done.length > 0 && (
            <div style={{ marginTop: 30 }}>
              <button className={`hb-donehead${doneOpen ? " is-open" : ""}`} onClick={() => setDoneOpen((v) => !v)}>
                <Icon name="chevronDown" size={16} stroke={2.4} className="hb-donehead__chev" />
                <span className="hb-sectionlabel" style={{ margin: 0 }}>Erledigt</span>
                <span className="hb-donehead__c hb-mono">{done.length}</span>
              </button>
              {doneOpen && (
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6, marginTop: 12 }}>
                  <div className="hb-list">{done.map((t) => <TodoRow key={t.id} t={t} api={api} onEdit={setEditing} />)}</div>
                </Card>
              )}
            </div>
          )}

          {visibleLists.length > 1 && (
            <button className="hb-link hb-link--danger" style={{ marginTop: 26, display: "block" }} onClick={removeList}>
              <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: "-2px", marginRight: 5 }} />Liste „{active.name}“ löschen
            </button>
          )}
        </>
      )}

      {editing && <PlanModal todo={editing} api={api} onClose={() => setEditing(null)} />}
      {newListOpen && <NewTodoListModal api={api} onClose={() => setNewListOpen(false)} onCreated={(l) => { setNewListOpen(false); setActiveId(l.id); }} />}
    </div>
  );
}

window.AufgabenView = AufgabenView;
