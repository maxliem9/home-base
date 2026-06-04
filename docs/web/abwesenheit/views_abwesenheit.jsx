/* HomeBase — Abwesenheit view: page, summary, day editor, settings. */

const WD_LONG_ABW = ["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"];
const ddmm = (ds) => { if (!ds) return ""; const d = HBcal.parse(ds); return `${d.getDate()}.${d.getMonth() + 1}.`; };

/* ---------- Summary card (per person) ---------- */
function AbwSummaryCard({ uid, sum, hue, pal }) {
  const u = HB.users[uid];
  const f = ABW.fmtDays;
  const H = hue != null ? hue : u.hue;
  const total = Math.max(sum.total, 1);
  const takenPct = Math.min(100, (sum.taken / total) * 100);
  const plannedPct = Math.min(100 - takenPct, (sum.planned / total) * 100);
  return (
    <Card className="abw-sumcard">
      <div className="abw-sumcard__head">
        <Avatar user={uid} size={34} />
        <div className="abw-sumcard__id">
          <div className="abw-sumcard__name">{u.name}</div>
          <div className="abw-sumcard__state">{HBcal.stateName(sum.state)}</div>
        </div>
        <div className="abw-sumcard__big">
          <span className="abw-sumcard__bigv hb-mono" style={{ color: `oklch(0.55 0.1 ${H})` }}>{f(sum.remaining)}</span>
          <span className="abw-sumcard__bigl">Urlaub übrig</span>
        </div>
      </div>

      <div className="abw-bar" title={`${f(sum.used)} von ${f(sum.total)} Tagen verplant`}>
        <span className="abw-bar__seg" style={{ width: takenPct + "%", background: `oklch(0.6 0.1 ${H})` }} />
        <span className="abw-bar__seg" style={{ width: plannedPct + "%", background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />
      </div>
      <div className="abw-sumcard__legend">
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})` }} />Genommen {f(sum.taken)}</span>
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />Geplant {f(sum.planned)}</span>
        <span className="hb-muted">Anspruch {f(sum.allowance)}</span>
      </div>

      <div className="abw-sumcard__foot">
        {sum.carry > 0 ? (
          <span className={`abw-chip${sum.carryExpired ? " abw-chip--warn" : " abw-chip--soft"}`}>
            +{f(sum.carry)} Übertrag · {sum.carryExpired ? `${f(sum.carryLost)} verfallen` : `bis ${ddmm(sum.carryExpires)}`}
          </span>
        ) : null}
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: (pal && pal.KRANK) || "oklch(0.68 0.13 27)" }} />Krank {f(sum.krank)}</span>
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: (pal && pal.KIND_KRANK) || "oklch(0.76 0.125 62)" }} />Kind-krank {f(sum.kind)}{sum.kindCap ? ` / ${sum.kindCap}` : ""}</span>
      </div>
    </Card>
  );
}

/* ---------- Day editor ---------- */
function HalfToggle({ value, onChange }) {
  const opts = [{ v: null, l: "Ganzer Tag" }, { v: "vm", l: "Vormittag (AM)" }, { v: "nm", l: "Nachmittag (PM)" }];
  return (
    <div className="abw-half">
      {opts.map((o) => (
        <button key={o.l} className={`abw-half__b${value === o.v ? " is-active" : ""}`} onClick={() => onChange(o.v)}>{o.l}</button>
      ))}
    </div>
  );
}

function AbwDayEditor({ ctx, ds, db, api, userIds, onClose }) {
  const d = HBcal.parse(ds);
  const title = `${WD_LONG_ABW[d.getDay()]}, ${d.getDate()}. ${HBcal.MON_FULL[d.getMonth()]} ${d.getFullYear()}`;
  const kita = ctx.kita[ds];
  const typeOpts = [
    { id: null, label: "Arbeit" },
    { id: "URLAUB", label: "Urlaub" },
    { id: "KRANK", label: "Krank" },
    { id: "KIND_KRANK", label: "Kind-krank" },
  ];
  return (
    <Modal open onClose={onClose} width={480} title={title}
      footer={<Button onClick={onClose}>Fertig</Button>}>
      {userIds.map((uid) => {
        const st = ABW.personDay(ctx, uid, ds);
        const note = st.holiday ? `Feiertag · ${st.holiday}` : st.ptOff ? "Teilzeit · ohnehin frei" : st.weekend ? "Wochenende" : null;
        return (
          <div key={uid} className="abw-ed-person">
            <div className="abw-ed-person__head">
              <Avatar user={uid} size={26} />
              <span className="abw-ed-person__name">{HB.users[uid].name}</span>
              {note ? <span className="abw-ed-person__note">{note}</span> : null}
            </div>
            <div className="abw-pickrow">
              {typeOpts.map((t) => (
                <button key={String(t.id)}
                  className={`abw-pick${(st.type || null) === t.id ? " is-active" : ""}`}
                  onClick={() => t.id ? api.setAbsence(uid, ds, t.id, st.type === t.id ? st.half : null) : api.clearAbsence(uid, ds)}>
                  {t.label}
                </button>
              ))}
            </div>
            {st.type ? <HalfToggle value={st.half} onChange={(h) => api.setAbsence(uid, ds, st.type, h)} /> : null}
          </div>
        );
      })}

      <div className="abw-ed-kita">
        <div>
          <div className="abw-ed-kita__t">Kita-Schließtag</div>
          <div className="abw-ed-kita__s hb-muted">Gilt für die ganze Familie</div>
        </div>
        <button className={`abw-switch${kita ? " is-on" : ""}`} role="switch" aria-checked={!!kita}
          onClick={() => api.toggleKita(ds, kita ? null : "Kita geschlossen")}>
          <span className="abw-switch__knob" />
        </button>
      </div>
      {kita ? (
        <Field label="Anlass (optional)">
          <TextInput value={kita.label} onChange={(v) => api.toggleKita(ds, v || "Kita geschlossen", true)} placeholder="z. B. Sommerschließung" />
        </Field>
      ) : null}
    </Modal>
  );
}

/* ---------- Settings ---------- */
function AbwSettings({ db, api, userIds, year, onClose }) {
  const num = (v, fallback) => { const n = parseFloat(String(v).replace(",", ".")); return Number.isFinite(n) ? n : fallback; };
  const [kDate, setKDate] = useState(`${year}-01-01`);
  const [rVon, setRVon] = useState(`${year}-07-27`);
  const [rBis, setRBis] = useState(`${year}-08-07`);
  const [rLabel, setRLabel] = useState("Sommerschließung");
  const kita = [...(db.kitaClosures || [])].sort((a, b) => a.date.localeCompare(b.date));
  return (
    <Modal open onClose={onClose} width={620} title="Kalender-Einstellungen"
      footer={<Button onClick={onClose}>Fertig</Button>}>
      {userIds.map((uid) => {
        const s = (db.absSettings || []).find((x) => x.user_id === uid) || {};
        const rules = (db.parttime || []).filter((r) => r.user_id === uid);
        return (
          <div key={uid} className="abw-set-person">
            <div className="abw-set-person__head"><Avatar user={uid} size={28} /><span>{HB.users[uid].name}</span></div>
            <div className="abw-set-grid">
              <Field label="Bundesland">
                <Select value={s.state} onChange={(v) => api.updateAbsSettings(uid, { state: v })}>
                  {HBcal.STATES.map((st) => <option key={st.code} value={st.code}>{st.name}</option>)}
                </Select>
              </Field>
              <Field label="Jahresanspruch (Tage)">
                <TextInput type="number" value={String(s.allowance ?? "")} onChange={(v) => api.updateAbsSettings(uid, { allowance: num(v, 0) })} />
              </Field>
              <Field label="Resturlaub Vorjahr">
                <TextInput type="number" value={String(s.carryover ?? "")} onChange={(v) => api.updateAbsSettings(uid, { carryover: num(v, 0) })} />
              </Field>
              <Field label="… verfällt am">
                <input type="date" className="hb-input" value={s.carryoverExpires || `${year}-03-31`} onChange={(e) => api.updateAbsSettings(uid, { carryoverExpires: e.target.value })} />
              </Field>
              <Field label="Kind-krank Anspruch">
                <TextInput type="number" value={String(s.kindKrankCap ?? "")} onChange={(v) => api.updateAbsSettings(uid, { kindKrankCap: num(v, 0) })} />
              </Field>
            </div>

            <div className="abw-set-pt">
              <div className="abw-set-pt__label">Teilzeit · feste freie Tage</div>
              {rules.length === 0 ? <div className="hb-muted abw-set-pt__empty">Keine Regel — Vollzeit.</div> : null}
              {rules.map((r) => (
                <div key={r.id} className="abw-set-ptrow">
                  <Select value={String(r.weekday)} onChange={(v) => api.updatePartTime(r.id, { weekday: Number(v) })} style={{ width: 130 }}>
                    {[1, 2, 3, 4, 5].map((w) => <option key={w} value={w}>{["Mo", "Di", "Mi", "Do", "Fr"][w - 1]}. frei</option>)}
                  </Select>
                  <span className="abw-set-ptrow__lab">ab</span>
                  <input type="date" className="hb-input" value={r.start} onChange={(e) => api.updatePartTime(r.id, { start: e.target.value })} />
                  <span className="abw-set-ptrow__lab">bis</span>
                  <input type="date" className="hb-input" value={r.end || ""} onChange={(e) => api.updatePartTime(r.id, { end: e.target.value || null })} />
                  <IconButton icon="trash" label="Regel löschen" danger size={16} onClick={() => api.removePartTime(r.id)} />
                </div>
              ))}
              <button className="hb-link" style={{ marginTop: 8 }}
                onClick={() => api.addPartTime({ user_id: uid, weekday: 1, start: `${year}-01-01`, end: null })}>
                <Icon name="plus" size={14} stroke={2.2} /> Freien Tag hinzufügen
              </button>
            </div>
          </div>
        );
      })}

      <div className="abw-set-kita">
        <div className="abw-set-pt__label">Kita-Schließtage</div>
        <div className="hb-muted abw-set-kita__hint">Gelten für die ganze Familie — als Hintergrund-Markierung im Kalender.</div>
        {kita.length === 0 ? <div className="hb-muted abw-set-pt__empty">Noch keine Schließtage erfasst.</div> : null}
        <div className="abw-kita-list">
          {kita.map((k) => (
            <div key={k.id} className="abw-kita-row">
              <input type="date" className="hb-input" value={k.date} onChange={(e) => api.updateKita(k.id, { date: e.target.value })} />
              <TextInput value={k.label} onChange={(v) => api.updateKita(k.id, { label: v })} placeholder="Anlass" />
              <IconButton icon="trash" label="Löschen" danger size={16} onClick={() => api.removeKita(k.id)} />
            </div>
          ))}
        </div>
        <div className="abw-kita-add">
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">Einzeltag</span>
            <input type="date" className="hb-input" value={kDate} onChange={(e) => setKDate(e.target.value)} />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKita(kDate, "Kita geschlossen")}>Hinzufügen</Button>
          </div>
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">Zeitraum</span>
            <input type="date" className="hb-input" value={rVon} onChange={(e) => setRVon(e.target.value)} />
            <span className="abw-set-ptrow__lab">bis</span>
            <input type="date" className="hb-input" value={rBis} onChange={(e) => setRBis(e.target.value)} />
            <TextInput value={rLabel} onChange={setRLabel} placeholder="Anlass" />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKitaRange(rVon, rBis, rLabel)}>Hinzufügen</Button>
          </div>
          <div className="hb-muted abw-set-kita__hint">Wochenenden werden beim Zeitraum automatisch übersprungen.</div>
        </div>
      </div>
    </Modal>
  );
}

/* ---------- Zeitraum (period) editor ---------- */
function AbwRangeModal({ db, api, userIds, prefill, onClose }) {
  const [targets, setTargets] = useState(userIds.slice());
  const [type, setType] = useState("URLAUB");
  const [von, setVon] = useState((prefill && prefill.von) || HBcal.ymd(new Date()));
  const [bis, setBis] = useState((prefill && prefill.bis) || HBcal.ymd(new Date()));
  const toggleT = (uid) => setTargets((t) => t.includes(uid) ? t.filter((x) => x !== uid) : [...t, uid]);
  const typeOpts = [
    { id: "URLAUB", label: "Urlaub" }, { id: "KRANK", label: "Krank" },
    { id: "KIND_KRANK", label: "Kind-krank" }, { id: null, label: "Eintrag löschen" },
  ];
  const preview = targets[0] ? ABW.eachDate(von, bis).filter((ds) => ABW.isWorkdayFor(db, targets[0], ds)).length : 0;
  const dis = targets.length === 0 || von > bis;
  const apply = () => { targets.forEach((uid) => api.setAbsenceRange(uid, type, von, bis, null)); onClose(); };
  return (
    <Modal open onClose={onClose} width={480} title="Zeitraum eintragen"
      footer={<><Button variant="ghost" onClick={onClose}>Abbrechen</Button><Button icon="check" onClick={apply} disabled={dis}>Übernehmen</Button></>}>
      <Field label="Für wen">
        <div className="abw-pickrow">
          {userIds.map((uid) => (
            <button key={uid} className={`abw-pick${targets.includes(uid) ? " is-active" : ""}`} onClick={() => toggleT(uid)}>{HB.users[uid].name}</button>
          ))}
        </div>
      </Field>
      <Field label="Art">
        <div className="abw-pickrow">
          {typeOpts.map((t) => (
            <button key={String(t.id)} className={`abw-pick${type === t.id ? " is-active" : ""}`} onClick={() => setType(t.id)}>{t.label}</button>
          ))}
        </div>
      </Field>
      <div className="abw-range-dates">
        <Field label="Von"><input type="date" className="hb-input" value={von} onChange={(e) => setVon(e.target.value)} /></Field>
        <Field label="Bis"><input type="date" className="hb-input" value={bis} onChange={(e) => setBis(e.target.value)} /></Field>
      </div>
      <div className="hb-muted" style={{ fontSize: 12.5, lineHeight: 1.5 }}>
        {type
          ? `Wird nur auf Arbeitstage angewendet — Wochenenden, Feiertage und feste freie Tage werden übersprungen${targets[0] ? ` (≈ ${preview} Tage für ${HB.users[targets[0]].name})` : ""}. Für halbe Tage einen einzelnen Tag anklicken.`
          : "Entfernt alle Einträge der gewählten Person(en) im Zeitraum."}
      </div>
    </Modal>
  );
}

/* ---------- Page ---------- */
function AbwesenheitView({ db, api, theme, forcedLayout, colors }) {
  const nowY = new Date().getFullYear();
  const [year, setYear] = useState(nowY);
  const [layout, setLayout] = useState(forcedLayout || "raster");
  const [month, setMonth] = useState(new Date().getMonth());
  const [editDs, setEditDs] = useState(null);
  const [showSettings, setShowSettings] = useState(false);
  const [anchor, setAnchor] = useState(null);
  const [rangeOpen, setRangeOpen] = useState(false);
  const [rangePrefill, setRangePrefill] = useState(null);

  useEffect(() => { if (forcedLayout) setLayout(forcedLayout); }, [forcedLayout]);

  const c = colors || {};
  const pal = ABW.palette(theme || "light", { krank: c.krank, kind: c.kind, feier: c.feier });
  const ctx = ABW.buildContext(db, year);
  const userIds = Object.keys(HB.users);
  ctx.hue = {};
  userIds.forEach((uid, i) => { const h = i === 0 ? c.urlaubMax : c.urlaubChen; if (h != null) ctx.hue[uid] = h; });
  const today = HBcal.ymd(new Date());

  // single click → day editor; shift-click after a first click → period editor for the span
  const onPick = (ds, e) => {
    if (e && e.shiftKey && anchor) {
      setRangePrefill({ von: anchor < ds ? anchor : ds, bis: anchor < ds ? ds : anchor });
      setRangeOpen(true);
      return;
    }
    setAnchor(ds);
    setEditDs(ds);
  };
  const openRange = () => { setRangePrefill({ von: today, bis: today }); setRangeOpen(true); };

  return (
    <div className="hb-page hb-page--wide">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">Familienkalender</div>
          <h1>Abwesenheit</h1>
        </div>
        <div className="hb-pagehead__actions abw-actions">
          <div className="abw-yearnav">
            <button className="hb-iconbtn" onClick={() => setYear((y) => y - 1)} aria-label="Vorheriges Jahr"><Icon name="chevronLeft" size={17} stroke={2.2} /></button>
            <span className="abw-yearnav__y hb-mono">{year}</span>
            <button className="hb-iconbtn" onClick={() => setYear((y) => y + 1)} aria-label="Nächstes Jahr"><Icon name="chevronRight" size={17} stroke={2.2} /></button>
          </div>
          <SegmentedControl value={layout} onChange={setLayout}
            options={[{ value: "raster", label: "Jahr" }, { value: "monat", label: "Monat" }]} />
          <Button variant="secondary" icon="plus" onClick={openRange}>Zeitraum</Button>
          <Button variant="secondary" icon="edit" onClick={() => setShowSettings(true)}>Einstellungen</Button>
        </div>
      </div>

      <div className="abw-sumgrid">
        {userIds.map((uid) => <AbwSummaryCard key={uid} uid={uid} sum={ABW.summarize(ctx, uid, today)} hue={ctx.hue[uid]} pal={pal} />)}
      </div>

      <div className="abw-legendrow">
        <AbwLegend userIds={userIds} pal={pal} hues={ctx.hue} />
        <div className="abw-legendrow__right">
          <span className="abw-hint">Tag klicken zum Bearbeiten · mit ⇧ Shift einen Zeitraum markieren</span>
          {layout === "monat" ? (
            <button className="hb-link" onClick={() => { setYear(nowY); setMonth(new Date().getMonth()); }}>Heute</button>
          ) : null}
        </div>
      </div>

      <Card className="abw-gridcard">
        {layout === "raster"
          ? <JahresRaster ctx={ctx} pal={pal} userIds={userIds} today={today} onPick={onPick} />
          : <MonatsKalender ctx={ctx} pal={pal} userIds={userIds} today={today} onPick={onPick} month={month} setMonth={setMonth} />}
      </Card>

      {editDs ? <AbwDayEditor ctx={ctx} ds={editDs} db={db} api={api} userIds={userIds} onClose={() => setEditDs(null)} /> : null}
      {rangeOpen ? <AbwRangeModal db={db} api={api} userIds={userIds} prefill={rangePrefill} onClose={() => setRangeOpen(false)} /> : null}
      {showSettings ? <AbwSettings db={db} api={api} userIds={userIds} year={year} onClose={() => setShowSettings(false)} /> : null}
    </div>
  );
}

window.AbwesenheitView = AbwesenheitView;
