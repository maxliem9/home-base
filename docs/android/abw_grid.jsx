/* HomeBase — Abwesenheit grids: Jahresraster + Monatskalender.
   Babel/JSX. Exports JahresRaster, MonatsKalender, AbwLegend to window. */

/* ---- helpers shared by both layouts ---- */
function abwStatusLabel(st) {
  if (st.type) return ABW.TYPES[st.type].label;
  if (st.holiday) return "Feiertag";
  if (st.ptOff) return "Teilzeit frei";
  if (st.weekend) return "Wochenende";
  return "Arbeitstag";
}
// diagonal split bg for a 2-person cell (max = upper-left, partner = lower-right)
function cellBg(cA, cB) {
  const T = "transparent";
  if (cA === T && cB === T) return T;
  if (cA === cB) return cA;
  const div = "oklch(0.5 0 0 / 0.14)";
  return `linear-gradient(135deg, ${cA} 0 calc(50% - 0.6px), ${div} calc(50% - 0.6px) calc(50% + 0.6px), ${cB} calc(50% + 0.6px) 100%)`;
}

function AbwLegend({ userIds, pal, hues }) {
  const hueOf = (uid) => (hues && hues[uid] != null) ? hues[uid] : HB.users[uid].hue;
  const items = [
    { sw: "split", label: "Urlaub (je Person)" },
    { sw: pal.KRANK, label: "Krank" },
    { sw: pal.KIND_KRANK, label: "Kind-krank" },
    { sw: pal.FEIERTAG, label: "Feiertag" },
    { sw: pal.teilzeit(220), label: "Teilzeit frei" },
    { sw: pal.WEEKEND, label: "Wochenende" },
    { sw: "kita", label: "Kita-Schließtag" },
  ];
  return (
    <div className="abw-legend">
      {items.map((it, i) => (
        <span key={i} className="abw-legend__item">
          {it.sw === "split" ? (
            <span className="abw-legend__sw" style={{ background: cellBg(pal.urlaub(hueOf(userIds[0])), pal.urlaub(hueOf(userIds[1]))) }} />
          ) : it.sw === "kita" ? (
            <span className="abw-legend__sw abw-legend__sw--kita" />
          ) : (
            <span className="abw-legend__sw" style={{ background: it.sw }} />
          )}
          {it.label}
        </span>
      ))}
    </div>
  );
}

/* =================== JAHRESRASTER =================== */
function JahresRaster({ ctx, pal, userIds, today, onPick }) {
  const year = ctx.year;
  const [uA, uB] = userIds;
  const months = HBcal.MON_ABBR;
  const cells = [];

  // header row
  cells.push(<div key="corner" className="abw-rcell abw-rcell--corner" />);
  months.forEach((m, mi) => cells.push(
    <div key={"h" + mi} className="abw-rcell abw-rcell--mhead">{m}</div>
  ));

  for (let d = 1; d <= 31; d++) {
    cells.push(<div key={"d" + d} className="abw-rcell abw-rcell--dhead hb-mono">{d}</div>);
    for (let mi = 0; mi < 12; mi++) {
      if (d > HBcal.daysInMonth(year, mi)) {
        cells.push(<div key={mi + "_" + d} className="abw-rcell abw-rcell--void" />);
        continue;
      }
      const ds = `${year}-${HBcal.pad(mi + 1)}-${HBcal.pad(d)}`;
      const a = ABW.personDay(ctx, uA, ds);
      const b = ABW.personDay(ctx, uB, ds);
      const bg = cellBg(ABW.colorFor(pal, a), ABW.colorFor(pal, b));
      const kita = ctx.kita[ds];
      const isToday = ds === today;
      const title = `${ds} · ${HB.users[uA].name}: ${abwStatusLabel(a)} · ${HB.users[uB].name}: ${abwStatusLabel(b)}${kita ? " · Kita: " + kita.label : ""}`;
      cells.push(
        <button key={mi + "_" + d} className={`abw-rcell abw-rcell--day${isToday ? " is-today" : ""}${kita ? " is-kita" : ""}`}
          style={{ background: bg }} title={title} onClick={(e) => onPick(ds, e)}>
          {a.half ? <span className="abw-rcell__h abw-rcell__h--a">{a.half === "vm" ? "AM" : "PM"}</span> : null}
          {b.half ? <span className="abw-rcell__h abw-rcell__h--b">{b.half === "vm" ? "AM" : "PM"}</span> : null}
        </button>
      );
    }
  }
  return (
    <div className="abw-raster" role="grid" aria-label={"Jahresübersicht " + year}>
      {cells}
    </div>
  );
}

/* =================== MONATSKALENDER =================== */
function MonatsChip({ st, user }) {
  const pal = st._pal;
  let bg, fg, label;
  if (st.type) {
    bg = st.type === "URLAUB" ? pal.urlaub(st.hue) : pal[st.type];
    fg = pal.dark ? pal.onLight : "oklch(0.99 0.01 150)";
    label = (st.half ? (st.half === "vm" ? "AM " : "PM ") : "") + ABW.TYPES[st.type].label;
  } else if (st.holiday) {
    bg = pal.FEIERTAG; fg = pal.onLight; label = st.holiday;
  } else if (st.ptOff) {
    bg = pal.teilzeit(st.hue); fg = pal.onLight; label = "frei";
  } else {
    return null;
  }
  return (
    <span className="abw-mchip" style={{ background: bg, color: fg }}>
      <span className="abw-mchip__who" style={{ background: `oklch(${pal.dark ? "0.3" : "0.99"} 0.02 ${st.hue} / ${pal.dark ? 0.55 : 0.65})` }}>{user.initials}</span>
      <span className="abw-mchip__txt">{label}</span>
    </span>
  );
}

function MonatsKalender({ ctx, pal, userIds, today, onPick, month, setMonth }) {
  const year = ctx.year;
  const first = new Date(year, month, 1, 12);
  const lead = (first.getDay() + 6) % 7; // Mon = 0
  const gridStart = HBcal.addDays(first, -lead);

  const weeks = [];
  for (let w = 0; w < 6; w++) {
    const row = [];
    for (let dow = 0; dow < 7; dow++) {
      const date = HBcal.addDays(gridStart, w * 7 + dow);
      const ds = HBcal.ymd(date);
      const inMonth = date.getMonth() === month;
      const isToday = ds === today;
      const weekend = HBcal.isWeekend(date);
      const kita = ctx.kita[ds];
      const people = userIds.map((uid) => {
        const st = ABW.personDay(ctx, uid, ds);
        st._pal = pal;
        return { uid, st, user: HB.users[uid] };
      });
      row.push(
        <button key={ds} className={`abw-mcell${inMonth ? "" : " is-out"}${weekend ? " is-weekend" : ""}${isToday ? " is-today" : ""}`}
          onClick={(e) => onPick(ds, e)}>
          <div className="abw-mcell__top">
            <span className={`abw-mcell__num hb-mono${isToday ? " is-today" : ""}`}>{date.getDate()}</span>
            {kita ? <span className="abw-mcell__kita" title={"Kita: " + kita.label}>Kita</span> : null}
          </div>
          <div className="abw-mcell__chips">
            {people.map((p) => <MonatsChip key={p.uid} st={p.st} user={p.user} />)}
          </div>
        </button>
      );
    }
    weeks.push(<div key={w} className="abw-mrow">{row}</div>);
    // stop after the week that contains the last day, but keep min 5 rows tidy
    const lastInRow = HBcal.addDays(gridStart, w * 7 + 6);
    if (lastInRow.getMonth() !== month && lastInRow > first && w >= 4) break;
  }

  return (
    <div className="abw-month">
      <div className="abw-mnav">
        <button className="hb-iconbtn" onClick={() => { const m = month - 1; if (m < 0) setMonth(11); else setMonth(m); }} aria-label="Vorheriger Monat">
          <Icon name="chevronLeft" size={18} stroke={2} />
        </button>
        <div className="abw-mnav__title">{HBcal.MON_FULL[month]} <span>{year}</span></div>
        <button className="hb-iconbtn" onClick={() => { const m = month + 1; if (m > 11) setMonth(0); else setMonth(m); }} aria-label="Nächster Monat">
          <Icon name="chevronRight" size={18} stroke={2} />
        </button>
      </div>
      <div className="abw-mhead">
        {HBcal.WD_MIN.map((w, i) => <div key={w} className={`abw-mhead__c${i >= 5 ? " is-we" : ""}`}>{w}</div>)}
      </div>
      <div className="abw-mgrid">{weeks}</div>
    </div>
  );
}

Object.assign(window, { JahresRaster, MonatsKalender, AbwLegend, abwStatusLabel, cellBg });
