/* HomeBase — App shell, store, navigation, tweaks */
const NAV = [
  { id: "heute", label: "Dashboard", icon: "home" },
  { id: "aufgaben", label: "Aufgaben", icon: "checkCircle" },
  { id: "einkauf", label: "Einkaufsliste", icon: "cart" },
  { id: "notizen", label: "Notizen", icon: "note" },
  { id: "zeit", label: "Zeiterfassung", icon: "clock" },
  { id: "rezepte", label: "Rezepte", icon: "chef" },
];

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "look": "klar",
  "accentHue": 35,
  "theme": "light",
  "density": "regular"
}/*EDITMODE-END*/;

const LOOKS = [
  { value: "klar", label: "Klar" },
  { value: "kontur", label: "Kontur" },
  { value: "erde", label: "Erde" },
];

const ACCENTS = [
  { label: "Salbei", hue: 150 },
  { label: "Lehm", hue: 35 },
  { label: "Himmel", hue: 250 },
  { label: "Pflaume", hue: 320 },
];

let _seq = 5000;
const nid = (p) => `${p}_${++_seq}`;

function App() {
  const params = new URLSearchParams(location.search);
  const urlLook = params.get("look");
  const urlRoute = params.get("route");
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [route, setRoute] = useState(urlRoute || "heute");
  const [focus, setFocus] = useState(null);

  // ---- the store ----
  const [db, setDb] = useState(() => JSON.parse(JSON.stringify(HB.seed)));
  const update = (key, fn) => setDb((d) => ({ ...d, [key]: fn(d[key]) }));

  useEffect(() => {
    // URL param forces the look (used by the side-by-side comparison page)
    document.documentElement.setAttribute("data-look", urlLook || t.look);
    document.documentElement.setAttribute("data-theme", t.theme);
    document.documentElement.setAttribute("data-density", t.density);
    document.documentElement.style.setProperty("--accent-hue", t.accentHue);
  }, [t.look, t.theme, t.density, t.accentHue, urlLook]);

  const navigate = (id, f = null) => { setRoute(id); setFocus(f); window.scrollTo({ top: 0 }); };

  const api = {
    // todos
    addTodoList: (name, visibility = "shared") => {
      const l = { id: nid("tl"), name, visibility, created_by: "max" };
      update("todoLists", (xs) => [...xs, l]);
      return l;
    },
    renameTodoList: (id, patch) => update("todoLists", (xs) => xs.map((l) => l.id === id ? { ...l, ...patch } : l)),
    deleteTodoList: (id) => setDb((d) => ({
      ...d,
      todoLists: d.todoLists.filter((l) => l.id !== id),
      todos: d.todos.filter((t) => t.list_id !== id),
    })),
    addTodo: (title, listId) => update("todos", (xs) => [
      { id: nid("t"), title, list_id: listId, description: "", status: "INBOX", assignee: null, due_date: null, priority: null, created_by: "max", created_at: new Date().toISOString(), subtasks: [] },
      ...xs,
    ]),
    updateTodo: (id, patch) => update("todos", (xs) => xs.map((t) => t.id === id ? { ...t, ...patch } : t)),
    toggleDone: (id) => update("todos", (xs) => xs.map((t) => {
      if (t.id !== id) return t;
      if (t.status === "DONE") return { ...t, status: t.due_date ? "PLANNED" : "INBOX", done_at: null };
      return { ...t, status: "DONE", done_at: new Date().toISOString() };
    })),
    deleteTodo: (id) => update("todos", (xs) => xs.filter((t) => t.id !== id)),
    addSubtask: (todoId, title) => update("todos", (xs) => xs.map((t) => t.id === todoId
      ? { ...t, subtasks: [...(t.subtasks || []), { id: nid("st"), title, done: false }] } : t)),
    toggleSubtask: (todoId, subId) => update("todos", (xs) => xs.map((t) => t.id === todoId
      ? { ...t, subtasks: (t.subtasks || []).map((s) => s.id === subId ? { ...s, done: !s.done } : s) } : t)),
    deleteSubtask: (todoId, subId) => update("todos", (xs) => xs.map((t) => t.id === todoId
      ? { ...t, subtasks: (t.subtasks || []).filter((s) => s.id !== subId) } : t)),
    // shopping
    addList: (name) => {
      const l = { id: nid("sl"), name, created_by: "max" };
      update("shoppingLists", (xs) => [...xs, l]);
      return l;
    },
    renameList: (id, name) => update("shoppingLists", (xs) => xs.map((l) => l.id === id ? { ...l, name } : l)),
    deleteList: (id) => setDb((d) => ({
      ...d,
      shoppingLists: d.shoppingLists.filter((l) => l.id !== id),
      shopping: d.shopping.filter((s) => s.list_id !== id),
    })),
    addItem: (name, listId) => update("shopping", (xs) => [...xs, { id: nid("s"), name, list_id: listId, checked: false, created_by: "max" }]),
    toggleItem: (id) => update("shopping", (xs) => xs.map((s) => s.id === id ? { ...s, checked: !s.checked } : s)),
    deleteItem: (id) => update("shopping", (xs) => xs.filter((s) => s.id !== id)),
    clearChecked: (listId) => update("shopping", (xs) => xs.filter((s) => !(s.checked && (listId == null || s.list_id === listId)))),
    addIngredientsToShopping: (ings, listId) => update("shopping", (xs) => {
      const target = listId || (db.shoppingLists[0] && db.shoppingLists[0].id);
      const existing = new Set(xs.filter((s) => s.list_id === target).map((s) => s.name.toLowerCase()));
      const add = ings.filter((i) => !existing.has(i.name.toLowerCase()))
        .map((i) => ({ id: nid("s"), name: i.name, list_id: target, checked: false, created_by: "max" }));
      return [...xs, ...add];
    }),
    // notes
    addNote: () => {
      const n = { id: nid("n"), title: "Neue Notiz", content: "## Neue Notiz\n\nSchreib hier los …", visibility: "shared", tags: [], created_by: "max", updated_at: new Date().toISOString() };
      update("notes", (xs) => [n, ...xs]);
      return n;
    },
    updateNote: (id, patch) => update("notes", (xs) => xs.map((n) => n.id === id ? { ...n, ...patch, updated_at: new Date().toISOString() } : n)),
    deleteNote: (id) => update("notes", (xs) => xs.filter((n) => n.id !== id)),
    // time
    addProject: (name, color) => update("projects", (xs) => [...xs, { id: nid("p"), name, color, archived: false, created_by: "max", created_at: new Date().toISOString() }]),
    updateProject: (id, patch) => update("projects", (xs) => xs.map((p) => p.id === id ? { ...p, ...patch } : p)),
    archiveProject: (id, archived) => update("projects", (xs) => xs.map((p) => p.id === id ? { ...p, archived } : p)),
    startTimer: (projectId, description = "") => setDb((d) => {
      const now = new Date().toISOString();
      // invariant: stop any running timer for current user first
      const entries = d.timeEntries.map((e) => (!e.stopped_at && e.user_id === "max") ? { ...e, stopped_at: now, updated_at: now } : e);
      entries.unshift({ id: nid("e"), project_id: projectId, user_id: "max", started_at: now, stopped_at: null, description, created_at: now, updated_at: now });
      return { ...d, timeEntries: entries };
    }),
    stopTimer: (id) => update("timeEntries", (xs) => xs.map((e) => e.id === id ? { ...e, stopped_at: new Date().toISOString(), updated_at: new Date().toISOString() } : e)),
    updateEntry: (id, patch) => update("timeEntries", (xs) => xs.map((e) => e.id === id ? { ...e, ...patch, updated_at: new Date().toISOString() } : e)),
    deleteEntry: (id) => update("timeEntries", (xs) => xs.filter((e) => e.id !== id)),
    // recipes
    addRecipe: (data) => {
      const r = {
        id: nid("r"), title: "Neues Rezept", description: "", category: "MAIN",
        servings: 2, prep_time_minutes: 0, cook_time_minutes: 0,
        ingredients: [], steps: [], created_by: "max", updated_at: new Date().toISOString(),
        ...data,
      };
      update("recipes", (xs) => [r, ...xs]);
      return r;
    },
    deleteRecipe: (id) => update("recipes", (xs) => xs.filter((r) => r.id !== id)),
  };

  const View = { heute: HeuteView, aufgaben: AufgabenView, einkauf: EinkaufView, notizen: NotizenView, zeit: ZeitView, rezepte: RezepteView }[route];

  // live nav badges
  const _todayStr = HB.iso(0);
  const inboxCount = db.todos.filter((x) => x.status !== "DONE" && x.due_date && x.due_date <= _todayStr).length;
  const shopCount = db.shopping.filter((x) => !x.checked).length;
  const badges = { aufgaben: inboxCount, einkauf: shopCount };
  const running = db.timeEntries.find((e) => !e.stopped_at);

  return (
    <div className="hb-app">
      <aside className="hb-sidebar">
        <div className="hb-brand">
          <div className="hb-brand__mark"><Icon name="home" size={21} stroke={2.2} /></div>
          <div>
            <div className="hb-brand__name">HomeBase</div>
            <div className="hb-brand__sub">Max &amp; Lea</div>
          </div>
        </div>
        <nav className="hb-nav">
          {NAV.map((n) => (
            <button key={n.id} className={`hb-navitem${route === n.id ? " is-active" : ""}`} onClick={() => navigate(n.id)}>
              <Icon name={n.icon} size={20} stroke={2} />
              <span>{n.label}</span>
              {n.id === "zeit" && running ? <span className="hb-syncdot" style={{ animation: "none", background: "var(--clay)" }} title="Timer läuft" /> : null}
              {badges[n.id] ? <span className="hb-navitem__badge">{badges[n.id]}</span> : null}
            </button>
          ))}
        </nav>
        <div className="hb-side-foot">
          <div className="hb-userchip">
            <Avatar user="max" size={34} />
            <div>
              <div className="hb-userchip__name">Max</div>
              <div className="hb-userchip__sub">Echtzeit-Sync aktiv</div>
            </div>
            <span className="hb-syncdot" title="Verbunden" />
          </div>
        </div>
      </aside>

      <main className="hb-main">
        <View db={db} api={api} navigate={navigate} focus={focus} />
      </main>

      <TweaksPanel>
        <TweakSection label="Richtung" />
        <TweakRadio label="Stil" value={t.look} options={LOOKS} onChange={(v) => setTweak("look", v)} />
        <TweakSection label="Akzentfarbe" />
        <div className="hb-accentrow">
          {ACCENTS.map((a) => (
            <button key={a.hue} className={`hb-accentopt${t.accentHue === a.hue ? " is-active" : ""}`}
              onClick={() => setTweak("accentHue", a.hue)} title={a.label}
              style={{ background: `oklch(0.55 0.09 ${a.hue})` }} />
          ))}
        </div>
        <TweakSection label="Darstellung" />
        <TweakRadio label="Theme" value={t.theme} options={[{ value: "light", label: "Hell" }, { value: "dark", label: "Dunkel" }]} onChange={(v) => setTweak("theme", v)} />
        <TweakRadio label="Dichte" value={t.density} options={[{ value: "compact", label: "Kompakt" }, { value: "regular", label: "Normal" }, { value: "comfy", label: "Luftig" }]} onChange={(v) => setTweak("density", v)} />
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
