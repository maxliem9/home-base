/* HomeBase — Zeiterfassung */
const ME = "max"; // current signed-in user
function BigClock({ since }) {
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(id); }, []);
  return <span className="hb-mono">{HBfmt.fmtDuration(Date.now() - new Date(since))}</span>;
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

  const recent = [...db.timeEntries].filter((e) => e.stopped_at).sort((a, b) => b.stopped_at.localeCompare(a.stopped_at)).slice(0, 8);

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
                    <span className="hb-projcard__name">{p.name}</span>
                    <div className="hb-row__actions" style={{ marginLeft: "auto" }}>
                      <IconButton icon="edit" label="Bearbeiten" size={15} onClick={() => setProjModal(p)} />
                      <IconButton icon="archive" label={p.archived ? "Aktivieren" : "Archivieren"} size={15} onClick={() => api.archiveProject(p.id, !p.archived)} />
                    </div>
                  </div>
                  <div className="hb-projcard__stat hb-mono">{perProject[p.id] ? HBfmt.fmtDurationShort(perProject[p.id]) : "—"}<span> gesamt</span></div>
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
              <div className="hb-list">
                {recent.map((e) => {
                  const p = db.projects.find((x) => x.id === e.project_id);
                  const dur = new Date(e.stopped_at) - new Date(e.started_at);
                  return (
                    <div key={e.id} className="hb-row">
                      <span className="hb-pdot" style={{ background: p?.color || "#999" }} />
                      <div className="hb-row__main">
                        <div className="hb-row__title">{p?.name || "—"}</div>
                        <div className="hb-row__meta">
                          {e.description ? <span>{e.description}</span> : <span className="hb-muted">ohne Beschreibung</span>}
                          <span className="dot-sep" />
                          <span>{HBfmt.clockTime(e.started_at)}–{HBfmt.clockTime(e.stopped_at)}</span>
                        </div>
                      </div>
                      <div className="hb-row__right">
                        <Avatar user={e.user_id} size={24} />
                        <span className="hb-mono" style={{ fontWeight: 600, minWidth: 64, textAlign: "right" }}>{HBfmt.fmtDurationShort(dur)}</span>
                        <div className="hb-row__actions">
                          {e.user_id === ME
                            ? <IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.deleteEntry(e.id)} />
                            : <Icon name="lock" size={14} stroke={2} className="hb-muted" title={`Eintrag von ${HB.users[e.user_id]?.name || e.user_id}`} style={{ opacity: 0.5 }} />}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </div>
      </div>

      {projModal !== undefined && <ProjectModal project={projModal} api={api} onClose={() => setProjModal(undefined)} />}
    </div>
  );
}

window.ZeitView = ZeitView;
