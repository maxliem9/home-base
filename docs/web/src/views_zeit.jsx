/* HomeBase — Zeiterfassung */
const ME = "max"; // current signed-in user
function BigClock({ since }) {
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(id); }, []);
  return <span className="hb-mono">{HBfmt.fmtDuration(Date.now() - new Date(since))}</span>;
}

const entryMs = (e) => new Date(e.stopped_at) - new Date(e.started_at);

// group sorted-desc entries into day buckets with separator labels + per-day totals
function groupByDay(entries) {
  const groups = [];
  const map = new Map();
  entries.forEach((e) => {
    const d = new Date(e.stopped_at);
    const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    if (!map.has(key)) {
      const g = { key, label: HBfmt.dayGroupLabel(e.stopped_at), entries: [], ms: 0 };
      map.set(key, g); groups.push(g);
    }
    const g = map.get(key);
    g.entries.push(e); g.ms += entryMs(e);
  });
  return groups;
}

function EntryRow({ e, db, api, showProject = true }) {
  const p = db.projects.find((x) => x.id === e.project_id);
  const dur = entryMs(e);
  const owner = HB.users[e.user_id]?.name || e.user_id;
  return (
    <div className="hb-row">
      {showProject && <span className="hb-pdot" style={{ background: p?.color || "#999" }} />}
      <div className="hb-row__main">
        <div className="hb-row__title">{showProject ? (p?.name || "—") : (e.description || <span className="hb-muted">ohne Beschreibung</span>)}</div>
        <div className="hb-row__meta">
          {showProject && (e.description ? <span>{e.description}</span> : <span className="hb-muted">ohne Beschreibung</span>)}
          {showProject && <span className="dot-sep" />}
          <span>{HBfmt.clockTime(e.started_at)}–{HBfmt.clockTime(e.stopped_at)}</span>
        </div>
      </div>
      <div className="hb-row__right">
        <Avatar user={e.user_id} size={24} />
        <span className="hb-mono" style={{ fontWeight: 600, minWidth: 64, textAlign: "right" }}>{HBfmt.fmtDurationShort(dur)}</span>
        <div className="hb-row__actions">
          {e.user_id === ME
            ? <IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteEntry(e.id)} />
            : <Icon name="lock" size={14} stroke={2} className="hb-muted" title={`Eintrag von ${owner}`} style={{ opacity: 0.5 }} />}
        </div>
      </div>
    </div>
  );
}

function DayGroupedList({ entries, db, api, showProject = true }) {
  const groups = groupByDay(entries);
  return (
    <div className="hb-list">
      {groups.map((g) => (
        <React.Fragment key={g.key}>
          <div className="hb-daysep">
            <span className="hb-daysep__label">{g.label}</span>
            <span className="hb-daysep__line" />
            <span className="hb-daysep__sum hb-mono">{HBfmt.fmtDurationShort(g.ms)}</span>
          </div>
          {g.entries.map((e) => <EntryRow key={e.id} e={e} db={db} api={api} showProject={showProject} />)}
        </React.Fragment>
      ))}
    </div>
  );
}

function ProjectDetail({ project, db, api, onClose }) {
  const entries = db.timeEntries
    .filter((e) => e.project_id === project.id && e.stopped_at)
    .sort((a, b) => b.stopped_at.localeCompare(a.stopped_at));
  const totalMs = entries.reduce((s, e) => s + entryMs(e), 0);

  // per-user totals
  const byUser = {};
  entries.forEach((e) => { byUser[e.user_id] = (byUser[e.user_id] || 0) + entryMs(e); });
  const userIds = Object.keys(byUser);

  // per-week summary (entries are newest-first → weeks newest-first)
  const weekMap = new Map();
  entries.forEach((e) => {
    const k = HBfmt.weekKey(e.stopped_at);
    if (!weekMap.has(k)) weekMap.set(k, { key: k, ...HBfmt.weekLabel(e.stopped_at), ms: 0, count: 0, byUser: {} });
    const w = weekMap.get(k);
    w.ms += entryMs(e); w.count += 1;
    w.byUser[e.user_id] = (w.byUser[e.user_id] || 0) + entryMs(e);
  });
  const weeks = [...weekMap.values()];
  const maxWeekMs = Math.max(...weeks.map((w) => w.ms), 1);
  const thisWeekMs = weekMap.get(HBfmt.weekKey(new Date().toISOString()))?.ms || 0;
  const avgMs = entries.length ? totalMs / entries.length : 0;

  return (
    <Modal open onClose={onClose} width={660} title={
      <span style={{ display: "inline-flex", alignItems: "center", gap: 11 }}>
        <span className="hb-pdot" style={{ background: project.color, width: 14, height: 14 }} />{project.name}
      </span>}>
      <div className="hb-detail-stats">
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{HBfmt.fmtDurationShort(totalMs)}</span><span className="hb-fact__l">Gesamt</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{HBfmt.fmtDurationShort(thisWeekMs)}</span><span className="hb-fact__l">Diese Woche</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{entries.length}</span><span className="hb-fact__l">Einträge</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{HBfmt.fmtDurationShort(avgMs)}</span><span className="hb-fact__l">⌀ pro Eintrag</span></div>
      </div>

      {userIds.length > 1 && (
        <div className="hb-detail-users">
          {userIds.map((uid) => (
            <div key={uid} className="hb-detail-user">
              <Avatar user={uid} size={26} />
              <span className="hb-detail-user__name">{HB.users[uid]?.name || uid}</span>
              <span className="hb-mono hb-detail-user__ms">{HBfmt.fmtDurationShort(byUser[uid])}</span>
            </div>
          ))}
        </div>
      )}

      {entries.length === 0 ? <EmptyState icon="clock" title="Noch keine Einträge" hint="Starte den Timer für dieses Projekt." /> : (
        <>
          <div className="hb-sectionlabel hb-detail-h">Pro Woche</div>
          <div className="hb-weeklist">
            {weeks.map((w) => (
              <div key={w.key} className="hb-weekrow">
                <div className="hb-weekrow__head">
                  <span className="hb-weekrow__label">{w.label || w.range}</span>
                  {w.label && <span className="hb-weekrow__range">{w.range}</span>}
                  <span className="hb-weekrow__ms hb-mono">{HBfmt.fmtDurationShort(w.ms)}</span>
                </div>
                <div className="hb-weekbar">
                  {userIds.map((uid) => (w.byUser[uid] ? (
                    <span key={uid} className="hb-weekbar__seg"
                      style={{ width: `${(w.byUser[uid] / maxWeekMs) * 100}%`, background: `oklch(0.62 0.1 ${HB.users[uid]?.hue || 150})` }}
                      title={`${HB.users[uid]?.name || uid}: ${HBfmt.fmtDurationShort(w.byUser[uid])}`} />
                  ) : null))}
                </div>
                <div className="hb-weekrow__sub">{w.count} {w.count === 1 ? "Eintrag" : "Einträge"}</div>
              </div>
            ))}
          </div>

          <div className="hb-sectionlabel hb-detail-h">Alle Einträge</div>
          <DayGroupedList entries={entries} db={db} api={api} showProject={false} />
        </>
      )}
    </Modal>
  );
}

function ProjectModal({ project, api, onClose }) {
  const [name, setName] = useState(project?.name || "");
  const [color, setColor] = useState(project?.color || "#5b9e7a");
  const swatches = ["#5b9e7a", "#c9805a", "#6a8fc0", "#c2a14d", "#a86fae", "#cf6f8a", "#5aa6a0", "#9a9a9a"];
  const save = () => {
    if (!name.trim()) return;
    if (project) api.updateProject(project.id, { name: name.trim(), color });
    else api.addProject(name.trim(), color);
    onClose();
  };
  return (
    <Modal open onClose={onClose} title={project ? "Projekt bearbeiten" : "Neues Projekt"} width={440}
      footer={<><Button variant="ghost" onClick={onClose}>Abbrechen</Button><Button icon="check" onClick={save} disabled={!name.trim()}>Speichern</Button></>}>
      <Field label="Projektname"><TextInput value={name} onChange={setName} placeholder="z. B. Garten & Balkon" autoFocus /></Field>
      <Field label="Farbe">
        <div className="hb-swatches">
          {swatches.map((c) => (
            <button key={c} className={`hb-swatch${color === c ? " is-active" : ""}`} style={{ background: c }} onClick={() => setColor(c)} aria-label={c} />
          ))}
        </div>
      </Field>
    </Modal>
  );
}

function ZeitView({ db, api }) {
  const [desc, setDesc] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [projModal, setProjModal] = useState(undefined); // undefined=closed, null=new, obj=edit
  const [detailProject, setDetailProject] = useState(null);

  const running = db.timeEntries.find((e) => !e.stopped_at && e.user_id === ME);
  const runningProject = running && db.projects.find((p) => p.id === running.project_id);
  const projects = db.projects.filter((p) => showArchived || !p.archived);

  // today's total
  const todayIso = HB.iso(0);
  const finished = db.timeEntries.filter((e) => e.stopped_at);
  const todayMs = finished
    .filter((e) => new Date(e.started_at) >= HB.today)
    .reduce((s, e) => s + (new Date(e.stopped_at) - new Date(e.started_at)), 0)
    + (running ? Date.now() - new Date(running.started_at) : 0);

  const perProject = {};
  finished.forEach((e) => { perProject[e.project_id] = (perProject[e.project_id] || 0) + (new Date(e.stopped_at) - new Date(e.started_at)); });

  const recent = [...db.timeEntries].filter((e) => e.stopped_at).sort((a, b) => b.stopped_at.localeCompare(a.stopped_at)).slice(0, 12);

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">Heute erfasst · {HBfmt.fmtDurationShort(todayMs)}</div>
          <h1>Zeiterfassung</h1>
        </div>
        <Button variant="secondary" icon="plus" onClick={() => setProjModal(null)}>Projekt</Button>
      </div>

      {/* Active timer hero */}
      <Card className={`hb-timerhero${running ? " is-running" : ""}`}>
        {running ? (
          <>
            <div className="hb-timerhero__left">
              <span className="hb-timerhero__live"><span className="hb-livedot" /> Läuft</span>
              <div className="hb-timerhero__proj"><span className="hb-pdot" style={{ background: runningProject.color }} />{runningProject.name}</div>
              <input className="hb-timerhero__desc" placeholder="Woran arbeitest du? (optional)"
                value={running.description || ""} onChange={(e) => api.updateEntry(running.id, { description: e.target.value })} />
            </div>
            <div className="hb-timerhero__right">
              <div className="hb-timerhero__clock"><BigClock since={running.started_at} /></div>
              <Button variant="secondary" icon="stop" onClick={() => api.stopTimer(running.id)}>Stoppen</Button>
            </div>
          </>
        ) : (
          <div className="hb-timerhero__idle">
            <div className="hb-timerhero__clock hb-muted">00:00:00</div>
            <p className="hb-muted" style={{ margin: 0 }}>Kein Timer aktiv. Starte unten ein Projekt — es läuft immer nur einer gleichzeitig.</p>
          </div>
        )}
      </Card>

      <div className="hb-zeit-grid">
        <div>
          <div className="hb-sectionlabel">Projekte</div>
          <div className="hb-proj-grid">
            {projects.map((p) => {
              const isRun = running && running.project_id === p.id;
              return (
                <Card key={p.id} className={`hb-projcard${isRun ? " is-running" : ""}${p.archived ? " is-archived" : ""}`}>
                  <div className="hb-projcard__head">
                    <span className="hb-pdot" style={{ background: p.color }} />
                    <button className="hb-projcard__name hb-projcard__namebtn" onClick={() => setDetailProject(p)} title="Details ansehen">{p.name}</button>
                    <div className="hb-row__actions" style={{ marginLeft: "auto" }}>
                      <IconButton icon="edit" label="Bearbeiten" size={15} onClick={() => setProjModal(p)} />
                      <IconButton icon="archive" label={p.archived ? "Aktivieren" : "Archivieren"} size={15} onClick={() => api.archiveProject(p.id, !p.archived)} />
                    </div>
                  </div>
                  <button className="hb-projcard__stat hb-projcard__statbtn hb-mono" onClick={() => setDetailProject(p)}>{perProject[p.id] ? HBfmt.fmtDurationShort(perProject[p.id]) : "—"}<span> gesamt →</span></button>
                  {p.archived ? <Badge tone="neutral">Archiviert</Badge> :
                    isRun ? <Button size="sm" variant="secondary" icon="stop" onClick={() => api.stopTimer(running.id)}>Stoppen</Button>
                          : <Button size="sm" variant="soft" icon="play" onClick={() => api.startTimer(p.id)}>Starten</Button>}
                </Card>
              );
            })}
          </div>
          <button className="hb-link" style={{ marginTop: 14 }} onClick={() => setShowArchived((v) => !v)}>
            {showArchived ? "Archivierte ausblenden" : "Archivierte anzeigen"}
          </button>
        </div>

        <div>
          <div className="hb-sectionlabel">Letzte Einträge</div>
          <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
            {recent.length === 0 ? <EmptyState icon="clock" title="Noch keine Einträge" /> : (
              <DayGroupedList entries={recent} db={db} api={api} showProject={true} />
            )}
          </Card>
        </div>
      </div>

      {projModal !== undefined && <ProjectModal project={projModal} api={api} onClose={() => setProjModal(undefined)} />}
      {detailProject && <ProjectDetail project={detailProject} db={db} api={api} onClose={() => setDetailProject(null)} />}
    </div>
  );
}

window.ZeitView = ZeitView;
