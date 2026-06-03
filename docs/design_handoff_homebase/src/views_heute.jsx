/* HomeBase — Heute (Dashboard) */
function greeting() {
  const hr = new Date().getHours();
  if (hr < 5) return "Gute Nacht";
  if (hr < 11) return "Guten Morgen";
  if (hr < 17) return "Hallo";
  if (hr < 22) return "Guten Abend";
  return "Gute Nacht";
}

function StatTile({ value, label, icon, tone, onClick }) {
  return (
    <button className="hb-stat" onClick={onClick} style={tone ? { "--tile": tone } : undefined}>
      <div className="hb-stat__icon"><Icon name={icon} size={19} stroke={2} /></div>
      <div className="hb-stat__value hb-mono">{value}</div>
      <div className="hb-stat__label">{label}</div>
    </button>
  );
}

function HeuteView({ db, api, navigate }) {
  const [quick, setQuick] = useState("");
  const me = HB.users.max;
  const todayIso = HB.iso(0);

  const dueToday = db.todos.filter((t) => t.status === "PLANNED" && t.due_date === todayIso);
  const dueTomorrow = db.todos.filter((t) => t.status === "PLANNED" && t.due_date === HB.iso(1));
  const inboxCount = db.todos.filter((t) => t.status === "INBOX").length;
  const doneToday = db.todos.filter((t) => t.status === "DONE" && t.done_at && new Date(t.done_at) >= HB.today);
  const openShop = db.shopping.filter((s) => !s.checked);
  const running = db.timeEntries.find((e) => !e.stopped_at);
  const runningProject = running && db.projects.find((p) => p.id === running.project_id);

  const submitQuick = () => {
    if (!quick.trim()) return;
    api.addTodo(quick.trim());
    setQuick("");
  };

  const now = new Date();
  const dateStr = `${HBfmt.WD[now.getDay()]}, ${now.getDate()}. ${HBfmt.MON[now.getMonth()]}`;

  return (
    <div className="hb-page">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{dateStr}</div>
          <h1>{greeting()}, {me.name}.</h1>
        </div>
      </div>

      <div className="hb-quickadd" style={{ marginBottom: 26 }}>
        <Icon name="sparkle" size={19} stroke={2} style={{ color: "var(--accent)" }} />
        <input value={quick} placeholder="Schnell erfassen – landet in der Inbox …"
          onChange={(e) => setQuick(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submitQuick(); }} />
        <Button size="sm" icon="plus" onClick={submitQuick} disabled={!quick.trim()}>Hinzufügen</Button>
      </div>

      <div className="hb-stats">
        <StatTile value={dueToday.length} label="Heute fällig" icon="calendar" onClick={() => navigate("aufgaben", { seg: "PLANNED" })} />
        <StatTile value={inboxCount} label="In der Inbox" icon="inbox" onClick={() => navigate("aufgaben", { seg: "INBOX" })} />
        <StatTile value={dueTomorrow.length} label="Morgen fällig" icon="clock" onClick={() => navigate("aufgaben", { seg: "PLANNED" })} />
        <StatTile value={doneToday.length} label="Heute erledigt" icon="checkCircle" onClick={() => navigate("aufgaben", { seg: "DONE" })} />
      </div>

      <div className="hb-heute-grid">
        <div className="hb-stack" style={{ gap: "var(--gap)" }}>
          {/* Today's tasks */}
          <Card className="hb-card--pad">
            <div className="hb-cardhead">
              <h3>Heute dran</h3>
              <button className="hb-link" onClick={() => navigate("aufgaben", { seg: "PLANNED" })}>Alle Aufgaben <Icon name="chevronRight" size={15} stroke={2.2} /></button>
            </div>
            {dueToday.length === 0 ? (
              <EmptyState icon="checkCircle" title="Für heute nichts geplant" hint="Genieß den Tag — oder leere die Inbox." />
            ) : (
              <div className="hb-list">
                {dueToday.map((t) => (
                  <div key={t.id} className="hb-row">
                    <Checkbox checked={false} hue={t.assignee ? HB.users[t.assignee].hue : null} onChange={() => api.toggleDone(t.id)} />
                    <div className="hb-row__main">
                      <div className="hb-row__title">{t.title}</div>
                      <div className="hb-row__meta">
                        <PriorityDot priority={t.priority} withLabel />
                      </div>
                    </div>
                    <div className="hb-row__right">{t.assignee && <Avatar user={t.assignee} size={26} />}</div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Digest preview */}
          <Card className="hb-card--pad hb-digest">
            <div className="hb-cardhead">
              <h3><Icon name="send" size={17} stroke={2} style={{ verticalAlign: "-2px", marginRight: 7, color: "var(--accent)" }} />Abend-Digest</h3>
              <Badge tone="neutral">heute · 20:00</Badge>
            </div>
            <p className="hb-muted" style={{ fontSize: 13.5, margin: "2px 0 14px" }}>Vorschau der Telegram-Nachricht, die ihr beide bekommt.</p>
            <div className="hb-digest__body">
              <div className="hb-digest__line"><span className="hb-digest__k">✓ Heute erledigt</span><span>{doneToday.length}</span></div>
              <div className="hb-digest__line"><span className="hb-digest__k">＋ Neu in der Inbox</span><span>{inboxCount}</span></div>
              <div className="hb-digest__line"><span className="hb-digest__k">↻ Morgen fällig</span><span>{dueTomorrow.length}</span></div>
              {dueTomorrow.slice(0, 3).map((t) => (
                <div key={t.id} className="hb-digest__sub">· {t.title}{t.assignee ? ` (${HB.users[t.assignee].name})` : ""}</div>
              ))}
            </div>
          </Card>
        </div>

        <div className="hb-stack" style={{ gap: "var(--gap)" }}>
          {/* Running timer */}
          <Card className="hb-card--pad">
            <div className="hb-cardhead"><h3>Zeiterfassung</h3>
              <button className="hb-link" onClick={() => navigate("zeit")}>Öffnen <Icon name="chevronRight" size={15} stroke={2.2} /></button>
            </div>
            {running ? (
              <div className="hb-runwidget">
                <span className="hb-runwidget__pdot" style={{ background: runningProject.color }} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="hb-row__title" style={{ fontWeight: 600 }}>{runningProject.name}</div>
                  <div className="hb-muted" style={{ fontSize: 13 }}>{running.description || "Läuft …"}</div>
                </div>
                <LiveClock since={running.started_at} />
                <IconButton icon="stop" label="Stoppen" onClick={() => api.stopTimer(running.id)} />
              </div>
            ) : (
              <EmptyState icon="timer" title="Kein Timer läuft" hint="Starte einen Timer in der Zeiterfassung." />
            )}
          </Card>

          {/* Shopping peek */}
          <Card className="hb-card--pad">
            <div className="hb-cardhead"><h3>Einkaufsliste</h3>
              <button className="hb-link" onClick={() => navigate("einkauf")}>Öffnen <Icon name="chevronRight" size={15} stroke={2.2} /></button>
            </div>
            {openShop.length === 0 ? (
              <EmptyState icon="cart" title="Alles eingekauft" />
            ) : (
              <>
                <div className="hb-list">
                  {openShop.slice(0, 5).map((s) => (
                    <div key={s.id} className="hb-row" style={{ padding: "9px 4px" }}>
                      <Checkbox checked={false} onChange={() => api.toggleItem(s.id)} />
                      <div className="hb-row__main"><div className="hb-row__title">{s.name}</div></div>
                      <Badge tone="neutral">{s.category}</Badge>
                    </div>
                  ))}
                </div>
                {openShop.length > 5 && <div className="hb-muted" style={{ fontSize: 13, marginTop: 10, textAlign: "center" }}>+ {openShop.length - 5} weitere</div>}
              </>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}

function LiveClock({ since }) {
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(id); }, []);
  return <span className="hb-mono hb-runwidget__clock">{HBfmt.fmtDuration(Date.now() - new Date(since))}</span>;
}

window.HeuteView = HeuteView;
window.LiveClock = LiveClock;
