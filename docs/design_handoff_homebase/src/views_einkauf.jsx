/* HomeBase — Einkaufsliste */
function EinkaufView({ db, api }) {
  const [name, setName] = useState("");
  const [cat, setCat] = useState(HB.shopCategories[0]);

  const submit = () => { if (!name.trim()) return; api.addItem(name.trim(), cat); setName(""); };

  const open = db.shopping.filter((s) => !s.checked);
  const checked = db.shopping.filter((s) => s.checked);

  const byCat = {};
  HB.shopCategories.forEach((c) => (byCat[c] = []));
  open.forEach((s) => { (byCat[s.category] || (byCat[s.category] = [])).push(s); });
  const cats = Object.keys(byCat).filter((c) => byCat[c].length);

  const catIcon = { "Obst & Gemüse": "sparkle", "Kühlware": "inbox", "Haushalt": "home", "Sonstiges": "tag" };

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{open.length} offen · {checked.length} im Wagen</div>
          <h1>Einkaufsliste</h1>
        </div>
      </div>

      <div className="hb-shop-add">
        <div className="hb-quickadd" style={{ flex: 1 }}>
          <Icon name="cart" size={19} stroke={2} style={{ color: "var(--ink-3)" }} />
          <input value={name} placeholder="Was fehlt? …"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />
        </div>
        <Select value={cat} onChange={setCat} style={{ width: 168 }}>
          {HB.shopCategories.map((c) => <option key={c} value={c}>{c}</option>)}
        </Select>
        <Button icon="plus" onClick={submit} disabled={!name.trim()}>Hinzufügen</Button>
      </div>

      {open.length === 0 && checked.length === 0 && (
        <Card className="hb-card--pad"><EmptyState icon="cart" title="Liste ist leer" hint="Füge oben das erste Produkt hinzu." /></Card>
      )}

      <div className="hb-shop-grid">
        {cats.map((c) => (
          <Card key={c} className="hb-card--pad hb-shop-cat">
            <div className="hb-cardhead">
              <h3><Icon name={catIcon[c] || "tag"} size={16} stroke={2} style={{ verticalAlign: "-2px", marginRight: 8, color: "var(--accent)" }} />{c}</h3>
              <span className="hb-muted hb-mono" style={{ fontSize: 13 }}>{byCat[c].length}</span>
            </div>
            <div className="hb-list">
              {byCat[c].map((s) => (
                <div key={s.id} className="hb-row" style={{ padding: "10px 4px" }}>
                  <Checkbox checked={false} onChange={() => api.toggleItem(s.id)} />
                  <div className="hb-row__main"><div className="hb-row__title">{s.name}</div></div>
                  <div className="hb-row__right">
                    <Avatar user={s.created_by} size={22} />
                    <div className="hb-row__actions"><IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteItem(s.id)} /></div>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        ))}
      </div>

      {checked.length > 0 && (
        <div style={{ marginTop: 30 }}>
          <div className="hb-cardhead" style={{ marginBottom: 12 }}>
            <div className="hb-sectionlabel" style={{ margin: 0 }}>Im Wagen · {checked.length}</div>
            <button className="hb-link" onClick={() => api.clearChecked()}>Abgehakte entfernen <Icon name="trash" size={14} stroke={2} /></button>
          </div>
          <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
            <div className="hb-list">
              {checked.map((s) => (
                <div key={s.id} className="hb-row hb-row--done" style={{ padding: "10px 4px" }}>
                  <Checkbox checked onChange={() => api.toggleItem(s.id)} />
                  <div className="hb-row__main"><div className="hb-row__title">{s.name}</div></div>
                  <Badge tone="neutral">{s.category}</Badge>
                </div>
              ))}
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}

window.EinkaufView = EinkaufView;
