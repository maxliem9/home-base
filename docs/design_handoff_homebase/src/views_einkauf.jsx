/* HomeBase — Einkaufsliste (mehrere Listen, Tabs) */
function NewListModal({ api, onClose, onCreated }) {
  const [name, setName] = useState("");
  const create = () => {
    if (!name.trim()) return;
    const l = api.addList(name.trim());
    onCreated(l);
  };
  return (
    <Modal open onClose={onClose} title="Neue Liste" width={420}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button variant="primary" icon="check" onClick={create} disabled={!name.trim()}>Erstellen</Button>
      </>}>
      <Field label="Name">
        <TextInput value={name} onChange={setName} placeholder="z. B. Wocheneinkauf"
          autoFocus onKeyDown={(e) => { if (e.key === "Enter") create(); }} />
      </Field>
    </Modal>
  );
}

function EinkaufView({ db, api }) {
  const lists = db.shoppingLists;
  const [activeId, setActiveId] = useState(lists[0] ? lists[0].id : null);
  const [name, setName] = useState("");
  const [newOpen, setNewOpen] = useState(false);

  // fall back if the active list was deleted
  const active = lists.find((l) => l.id === activeId) || lists[0] || null;

  const itemsOf = (id) => db.shopping.filter((s) => s.list_id === id);
  const openCount = (id) => itemsOf(id).filter((s) => !s.checked).length;

  const items = active ? itemsOf(active.id) : [];
  const open = items.filter((s) => !s.checked);
  const checked = items.filter((s) => s.checked);

  const submit = () => { if (!name.trim() || !active) return; api.addItem(name.trim(), active.id); setName(""); };

  const removeList = () => {
    if (!active) return;
    if (lists.length <= 1) return;
    if (!confirm(`Liste „${active.name}" und alle Einträge löschen?`)) return;
    const idx = lists.findIndex((l) => l.id === active.id);
    const next = lists[idx + 1] || lists[idx - 1];
    api.deleteList(active.id);
    setActiveId(next ? next.id : null);
  };

  const totalOpen = db.shopping.filter((s) => !s.checked).length;

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{lists.length} {lists.length === 1 ? "Liste" : "Listen"} · {totalOpen} offen</div>
          <h1>Einkaufslisten</h1>
        </div>
      </div>

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist">
        {lists.map((l) => (
          <button key={l.id} role="tab" aria-selected={active && l.id === active.id}
            className={`hb-tab${active && l.id === active.id ? " is-active" : ""}`}
            onClick={() => setActiveId(l.id)}>
            {l.name}
            {openCount(l.id) > 0 && <span className="hb-tab__count">{openCount(l.id)}</span>}
          </button>
        ))}
        <button className="hb-tab hb-tab--add" onClick={() => setNewOpen(true)}>
          <Icon name="plus" size={16} stroke={2.2} />Neue Liste
        </button>
      </div>

      {!active ? (
        <Card className="hb-card--pad">
          <EmptyState icon="cart" title="Noch keine Liste" hint="Lege oben deine erste Einkaufsliste an." />
        </Card>
      ) : (
        <>
          <div className="hb-shop-add">
            <div className="hb-quickadd" style={{ flex: 1 }}>
              <Icon name="cart" size={19} stroke={2} style={{ color: "var(--ink-3)" }} />
              <input value={name} placeholder={`Was fehlt in „${active.name}"? …`}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />
            </div>
            <Button icon="plus" onClick={submit} disabled={!name.trim()}>Hinzufügen</Button>
          </div>

          {open.length === 0 && checked.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="cart" title="Liste ist leer" hint="Füge oben das erste Produkt hinzu." /></Card>
          ) : (
            <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
              <div className="hb-list">
                {open.map((s) => (
                  <div key={s.id} className="hb-row" style={{ padding: "11px 4px" }}>
                    <Checkbox checked={false} onChange={() => api.toggleItem(s.id)} />
                    <div className="hb-row__main"><div className="hb-row__title">{s.name}</div></div>
                    <div className="hb-row__right">
                      <Avatar user={s.created_by} size={22} />
                      <div className="hb-row__actions"><IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteItem(s.id)} /></div>
                    </div>
                  </div>
                ))}
                {open.length === 0 && <div className="hb-muted" style={{ padding: "14px 4px", fontSize: 14 }}>Alles abgehakt 🎉</div>}
              </div>
            </Card>
          )}

          {checked.length > 0 && (
            <div style={{ marginTop: 26 }}>
              <div className="hb-cardhead" style={{ marginBottom: 12 }}>
                <div className="hb-sectionlabel" style={{ margin: 0 }}>Im Wagen · {checked.length}</div>
                <button className="hb-link" onClick={() => api.clearChecked(active.id)}>Abgehakte entfernen <Icon name="trash" size={14} stroke={2} /></button>
              </div>
              <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                <div className="hb-list">
                  {checked.map((s) => (
                    <div key={s.id} className="hb-row hb-row--done" style={{ padding: "10px 4px" }}>
                      <Checkbox checked onChange={() => api.toggleItem(s.id)} />
                      <div className="hb-row__main"><div className="hb-row__title">{s.name}</div></div>
                      <Avatar user={s.created_by} size={22} />
                    </div>
                  ))}
                </div>
              </Card>
            </div>
          )}

          {lists.length > 1 && (
            <button className="hb-link hb-link--danger" style={{ marginTop: 26 }} onClick={removeList}>
              <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: "-2px", marginRight: 5 }} />Liste „{active.name}" löschen
            </button>
          )}
        </>
      )}

      {newOpen && <NewListModal api={api} onClose={() => setNewOpen(false)} onCreated={(l) => { setNewOpen(false); setActiveId(l.id); }} />}
    </div>
  );
}

window.EinkaufView = EinkaufView;
