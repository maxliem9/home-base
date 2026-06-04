/* HomeBase — Rezepte */
// merge legacy LUNCH/DINNER into the single MAIN category
const catKey = (c) => (c === "LUNCH" || c === "DINNER") ? "MAIN" : c;
const catLabel = (c) => HB.recipeCategories[catKey(c)] || c;

function RecipeForm({ api, onClose, onCreated }) {
  const [f, setF] = useState({
    title: "", category: "MAIN", servings: "2",
    prep_time_minutes: "", cook_time_minutes: "", description: "",
    ingredients: "", steps: "",
  });
  const set = (k) => (v) => setF((s) => ({ ...s, [k]: v }));

  const save = () => {
    if (!f.title.trim()) return;
    const ingredients = f.ingredients.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
      const m = l.match(/^([\d.,\/]+)\s*(\S+)?\s+(.+)$/);
      if (m && /\d/.test(m[1])) return { amount: m[1], unit: m[2] || "", name: m[3] };
      return { amount: "", unit: "", name: l };
    });
    const steps = f.steps.split("\n").map((l) => l.trim()).filter(Boolean);
    const r = api.addRecipe({
      title: f.title.trim(), description: f.description.trim(), category: f.category,
      servings: Number(f.servings) || 1,
      prep_time_minutes: Number(f.prep_time_minutes) || 0,
      cook_time_minutes: Number(f.cook_time_minutes) || 0,
      ingredients, steps,
    });
    onCreated && onCreated(r);
    onClose();
  };

  return (
    <Modal open onClose={onClose} title="Neues Rezept" width={620}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button variant="primary" icon="check" onClick={save} disabled={!f.title.trim()}>Speichern</Button>
      </>}>
      <div style={{ display: "grid", gap: 14 }}>
        <Field label="Titel">
          <TextInput value={f.title} onChange={set("title")} placeholder="z. B. Gemüsecurry" autoFocus />
        </Field>
        <div style={{ display: "grid", gridTemplateColumns: "1.4fr 1fr", gap: 14 }}>
          <Field label="Kategorie">
            <Select value={f.category} onChange={set("category")}>
              {Object.keys(HB.recipeCategories).map((c) => (
                <option key={c} value={c}>{HB.recipeCategories[c]}</option>
              ))}
            </Select>
          </Field>
          <Field label="Portionen">
            <TextInput type="number" value={f.servings} onChange={set("servings")} placeholder="2" />
          </Field>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
          <Field label="Vorbereitung (Min)">
            <TextInput type="number" value={f.prep_time_minutes} onChange={set("prep_time_minutes")} placeholder="10" />
          </Field>
          <Field label="Kochzeit (Min)">
            <TextInput type="number" value={f.cook_time_minutes} onChange={set("cook_time_minutes")} placeholder="15" />
          </Field>
        </div>
        <Field label="Beschreibung">
          <TextInput value={f.description} onChange={set("description")} placeholder="Kurze Beschreibung …" />
        </Field>
        <Field label="Zutaten" hint="Eine pro Zeile, z. B. „200 g Mehl“">
          <textarea className="hb-input" rows={5} value={f.ingredients}
            onChange={(e) => set("ingredients")(e.target.value)}
            placeholder={"200 g Mehl\n2 Eier\n1 Prise Salz"} style={{ resize: "vertical", lineHeight: 1.6 }} />
        </Field>
        <Field label="Zubereitung" hint="Ein Schritt pro Zeile">
          <textarea className="hb-input" rows={5} value={f.steps}
            onChange={(e) => set("steps")(e.target.value)}
            placeholder={"Zutaten verrühren …\nIn der Pfanne braten …"} style={{ resize: "vertical", lineHeight: 1.6 }} />
        </Field>
      </div>
    </Modal>
  );
}
function IngredientPicker({ recipe, api, onClose, onDone }) {
  const [sel, setSel] = useState(() => recipe.ingredients.map(() => true));
  const toggle = (i) => setSel((s) => s.map((v, j) => (j === i ? !v : v)));
  const count = sel.filter(Boolean).length;
  const allOn = count === recipe.ingredients.length;
  const add = () => {
    const chosen = recipe.ingredients.filter((_, i) => sel[i]);
    if (chosen.length === 0) return;
    api.addIngredientsToShopping(chosen);
    onDone(chosen.length);
  };
  return (
    <Modal open onClose={onClose} title="Zutaten zur Liste" width={440}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button variant="primary" icon="cart" onClick={add} disabled={count === 0}>{count} hinzufügen</Button>
      </>}>
      <div className="hb-picker-head">
        <span className="hb-muted">{count} von {recipe.ingredients.length} ausgewählt</span>
        <button className="hb-link" onClick={() => setSel(recipe.ingredients.map(() => !allOn))}>
          {allOn ? "Keine" : "Alle"}
        </button>
      </div>
      <div className="hb-picklist">
        {recipe.ingredients.map((ing, i) => (
          <div key={i} className="hb-ingpick" onClick={() => toggle(i)}>
            <Checkbox checked={sel[i]} onChange={() => toggle(i)} />
            <span className="hb-ing__amt hb-mono">{[ing.amount, ing.unit].filter(Boolean).join(" ") || "·"}</span>
            <span className="hb-ingpick__name">{ing.name}</span>
          </div>
        ))}
      </div>
    </Modal>
  );
}

function RecipeDetail({ recipe, api, onBack, onAddToShopping }) {
  const r = recipe;
  const total = (r.prep_time_minutes || 0) + (r.cook_time_minutes || 0);
  return (
    <div className="hb-page">
      <button className="hb-backlink" onClick={onBack}>
        <Icon name="chevronLeft" size={17} stroke={2.2} />Alle Rezepte
      </button>
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{catLabel(r.category)}</div>
          <h1>{r.title}</h1>
        </div>
        <div className="hb-pagehead__actions">
          <Button variant="ghost" icon="trash" onClick={() => { api.deleteRecipe(r.id); onBack(); }}>Löschen</Button>
          <Button variant="soft" icon="cart" onClick={() => onAddToShopping(r)}>Zutaten zur Liste</Button>
        </div>
      </div>
      {r.description && <p className="hb-muted" style={{ margin: "0 0 18px", fontSize: 16, maxWidth: 640 }}>{r.description}</p>}
      <div className="hb-recipe-facts" style={{ maxWidth: 520, marginBottom: 26 }}>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{r.servings}</span><span className="hb-fact__l">Portionen</span></div>
        {r.prep_time_minutes ? <div className="hb-fact"><span className="hb-fact__v hb-mono">{r.prep_time_minutes}′</span><span className="hb-fact__l">Vorbereitung</span></div> : null}
        {r.cook_time_minutes ? <div className="hb-fact"><span className="hb-fact__v hb-mono">{r.cook_time_minutes}′</span><span className="hb-fact__l">Kochzeit</span></div> : null}
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{total}′</span><span className="hb-fact__l">Gesamt</span></div>
      </div>
      <div className="hb-recipe-body">
        <div>
          <div className="hb-sectionlabel">Zutaten</div>
          <div className="hb-ingredients">
            {r.ingredients.map((ing, i) => (
              <div key={i} className="hb-ing">
                <span className="hb-ing__amt hb-mono">{[ing.amount, ing.unit].filter(Boolean).join(" ") || "·"}</span>
                <span className="hb-ing__name">{ing.name}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <div className="hb-sectionlabel">Zubereitung</div>
          <ol className="hb-steps">
            {r.steps.map((s, i) => (
              <li key={i} className="hb-step"><span className="hb-step__n">{i + 1}</span><span>{s}</span></li>
            ))}
          </ol>
        </div>
      </div>
    </div>
  );
}

function RecipeCard({ r, onOpen }) {
  const total = (r.prep_time_minutes || 0) + (r.cook_time_minutes || 0);
  // deterministic warm hue per recipe for the placeholder band
  const hue = (r.title.charCodeAt(0) * 7 + r.title.length * 13) % 80 + 30;
  return (
    <Card className="hb-recipecard hb-card--hover" onClick={() => onOpen(r)}>
      <div className="hb-recipecard__img" style={{ "--rh": hue }}>
        <Icon name="chef" size={30} stroke={1.6} />
        <span className="hb-recipecard__ph">Foto folgt</span>
        <Badge tone="neutral" style={{ position: "absolute", top: 12, left: 12, background: "var(--surface)", boxShadow: "var(--shadow-sm)" }}>{catLabel(r.category)}</Badge>
      </div>
      <div className="hb-recipecard__body">
        <h3 className="hb-recipecard__title">{r.title}</h3>
        <p className="hb-recipecard__desc">{r.description}</p>
        <div className="hb-recipecard__meta">
          <span><Icon name="clock" size={15} stroke={2} style={{ verticalAlign: "-3px", marginRight: 5 }} />{total} Min</span>
          <span className="dot-sep" />
          <span><Icon name="users" size={15} stroke={2} style={{ verticalAlign: "-3px", marginRight: 5 }} />{r.servings} Portionen</span>
        </div>
      </div>
    </Card>
  );
}

function RezepteView({ db, api, navigate }) {
  const [filter, setFilter] = useState("ALL");
  const [open, setOpen] = useState(null);
  const [adding, setAdding] = useState(false);
  const [picking, setPicking] = useState(null);
  const [toast, setToast] = useState(null);

  const cats = ["ALL", ...Object.keys(HB.recipeCategories)];
  const list = filter === "ALL" ? db.recipes : db.recipes.filter((r) => catKey(r.category) === filter);

  // keep the open recipe in sync with the store (e.g. after edits)
  const current = open ? db.recipes.find((r) => r.id === open.id) || null : null;

  const finishAdd = (n) => {
    setPicking(null);
    setToast(`${n} ${n === 1 ? "Zutat" : "Zutaten"} zur Einkaufsliste hinzugefügt`);
    setTimeout(() => setToast(null), 2600);
  };

  // ---- detail page (not a modal) ----
  if (current) {
    return (
      <>
        <RecipeDetail recipe={current} api={api} onBack={() => setOpen(null)} onAddToShopping={(r) => setPicking(r)} />
        {picking && <IngredientPicker recipe={picking} api={api} onClose={() => setPicking(null)} onDone={finishAdd} />}
        {toast && (
          <div className="hb-toast">
            <Icon name="check" size={16} stroke={2.4} style={{ color: "var(--accent)" }} />
            {toast}
            <button className="hb-link" onClick={() => navigate("einkauf")}>Ansehen</button>
          </div>
        )}
      </>
    );
  }

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{db.recipes.length} Rezepte</div>
          <h1>Rezepte</h1>
        </div>
        <Button variant="primary" icon="plus" onClick={() => setAdding(true)}>Neues Rezept</Button>
      </div>

      <div className="hb-tagrow" style={{ marginBottom: 22 }}>
        {cats.map((c) => (
          <button key={c} className={`hb-tagchip${filter === c ? " is-active" : ""}`} onClick={() => setFilter(c)}>
            {c === "ALL" ? "Alle" : HB.recipeCategories[c]}
          </button>
        ))}
      </div>

      {list.length === 0 ? (
        <Card className="hb-card--pad"><EmptyState icon="chef" title="Keine Rezepte in dieser Kategorie" /></Card>
      ) : (
        <div className="hb-recipe-grid">
          {list.map((r) => <RecipeCard key={r.id} r={r} onOpen={setOpen} />)}
        </div>
      )}

      {adding && <RecipeForm api={api} onClose={() => setAdding(false)} onCreated={(r) => { setFilter("ALL"); setOpen(r); }} />}
      {toast && (
        <div className="hb-toast">
          <Icon name="check" size={16} stroke={2.4} style={{ color: "var(--accent)" }} />
          {toast}
          <button className="hb-link" onClick={() => navigate("einkauf")}>Ansehen</button>
        </div>
      )}
    </div>
  );
}

window.RezepteView = RezepteView;
