// HomeBase Android — Abwesenheit / Familienkalender (absence & vacation planner)
// Static state screens built with h(). Uses HBcal (holidays.jsx) for real dates.
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Sheet } = window;
  const C = window.HBcal;

  // ---- demo data (2026; "today" = 4 June, matching the desktop seed) ----
  const YEAR = 2026;
  const TODAY = "2026-06-04";
  const PEOPLE = [
    { id: "max", name: "Max", hue: 150, state: "BE" },
    { id: "lea", name: "Lea", hue: 250, state: "BY" },
  ];
  const HOL = { max: C.holidays(YEAR, "BE"), lea: C.holidays(YEAR, "BY") };
  const PT = {
    max: [{ wd: 1, from: "2026-01-01", to: "2026-04-30" }], // Mondays off, Jan–Apr
    lea: [{ wd: 5, from: "2026-03-01", to: null }],          // Fridays off, from March
  };
  const a = (type, half) => ({ type, half: half || null });
  const ABS = {
    max: {
      "2026-02-17": a("URLAUB"), "2026-02-18": a("URLAUB"), "2026-02-19": a("URLAUB"), "2026-02-20": a("URLAUB"),
      "2026-03-10": a("KRANK"), "2026-03-11": a("KRANK"), "2026-04-22": a("KIND_KRANK"), "2026-05-15": a("URLAUB"),
      "2026-07-27": a("URLAUB"), "2026-07-28": a("URLAUB"), "2026-07-29": a("URLAUB"), "2026-07-30": a("URLAUB"), "2026-07-31": a("URLAUB"),
      "2026-08-03": a("URLAUB"), "2026-08-04": a("URLAUB"), "2026-08-05": a("URLAUB"), "2026-08-06": a("URLAUB"), "2026-08-07": a("URLAUB"),
    },
    lea: {
      "2026-04-07": a("URLAUB"), "2026-04-08": a("URLAUB"), "2026-04-09": a("URLAUB"),
      "2026-03-12": a("KIND_KRANK"), "2026-05-19": a("KRANK"), "2026-06-11": a("URLAUB", "vm"),
      "2026-07-27": a("URLAUB"), "2026-07-28": a("URLAUB"), "2026-07-29": a("URLAUB"), "2026-07-30": a("URLAUB"),
      "2026-08-03": a("URLAUB"), "2026-08-04": a("URLAUB"), "2026-08-05": a("URLAUB"), "2026-08-06": a("URLAUB"),
    },
  };
  const KITA = new Set([
    "2026-02-16", "2026-02-17", "2026-05-15", "2026-06-05",
    "2026-08-03", "2026-08-04", "2026-08-05", "2026-08-06", "2026-08-07",
    "2026-08-10", "2026-08-11", "2026-08-12", "2026-08-13", "2026-08-14",
    "2026-12-23", "2026-12-24", "2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31",
  ]);

  // ---- palette (light only) ----
  const PAL = {
    urlaub: (hue) => `oklch(0.7 0.108 ${hue})`,
    KRANK: "oklch(0.71 0.13 27)",
    KIND_KRANK: "oklch(0.78 0.125 62)",
    FEIER: "oklch(0.82 0.05 288)",
    teilzeit: (hue) => `oklch(0.91 0.034 ${hue})`,
    WEEKEND: "oklch(0.925 0.006 130)",
    WORK: "var(--surface)",
  };
  const TYPES = { URLAUB: "Urlaub", KRANK: "Krank", KIND_KRANK: "Kind-krank" };
  const fmt = (n) => (Number.isInteger(n) ? String(n) : n.toFixed(1).replace(".", ","));

  function resolve(pid, ds) {
    const date = C.parse(ds);
    const ab = (ABS[pid] || {})[ds];
    return {
      type: ab ? ab.type : null,
      half: ab ? ab.half : null,
      holiday: HOL[pid][ds] || null,
      weekend: C.isWeekend(date),
      ptOff: (PT[pid] || []).some((r) => r.wd === C.isoDow(date) && ds >= r.from && (!r.to || ds <= r.to)),
    };
  }
  function colorOf(pid, st) {
    const hue = PEOPLE.find((p) => p.id === pid).hue;
    if (st.type) return st.type === "URLAUB" ? PAL.urlaub(hue) : PAL[st.type];
    if (st.holiday) return PAL.FEIER;
    if (st.ptOff) return PAL.teilzeit(hue);
    if (st.weekend) return PAL.WEEKEND;
    return PAL.WORK;
  }
  function cellBg(cA, cB) {
    if (cA === cB) return cA;
    const div = "oklch(0.5 0 0 / 0.14)";
    return `linear-gradient(135deg, ${cA} 0 calc(50% - 0.6px), ${div} calc(50% - 0.6px) calc(50% + 0.6px), ${cB} calc(50% + 0.6px) 100%)`;
  }

  // ---- shared chrome ----
  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "Familienkalender", title: "Abwesenheit",
      right: h(AppbarAction, { icon: "settings" }) });
  }
  function seg(active) {
    return h("div", { className: "hb-seg", style: { marginBottom: 16 } },
      h("button", { className: "hb-seg__item" + (active === "jahr" ? " is-active" : "") }, "Jahr"),
      h("button", { className: "hb-seg__item" + (active === "monat" ? " is-active" : "") }, "Monat"));
  }

  // ---- summary (both people, compact) ----
  const SUM = {
    max: { remaining: 18, taken: 5, planned: 10, allowance: 30, total: 33, carry: "+3 Übertrag · bis 31.03.", krank: 2, kind: 1, cap: 15 },
    lea: { remaining: 17.5, taken: 3, planned: 8.5, allowance: 24, total: 29, carry: "+5 Übertrag · 2 verfallen", carryWarn: true, krank: 1, kind: 1, cap: 15 },
  };
  function pcard(p) {
    const s = SUM[p.id];
    const tk = Math.min(100, (s.taken / s.total) * 100);
    const pl = Math.min(100 - tk, (s.planned / s.total) * 100);
    return h("div", { className: "abwm-pcard", key: p.id },
      h("div", { className: "abwm-pcard__top" },
        h(Avatar, { user: p.id, size: 30 }),
        h("div", { className: "abwm-ptid" },
          h("div", { className: "abwm-ptname" }, p.name),
          h("div", { className: "abwm-ptsub" }, C.stateName(p.state) + " · Anspruch " + s.allowance)),
        h("div", { className: "abwm-ptbig" },
          h("div", { className: "abwm-ptbig__v hb-mono", style: { color: `oklch(0.55 0.1 ${p.hue})` } }, fmt(s.remaining)),
          h("div", { className: "abwm-ptbig__l" }, "übrig"))),
      h("div", { className: "abwm-bar" },
        h("span", { className: "abwm-bar__seg", style: { width: tk + "%", background: `oklch(0.6 0.1 ${p.hue})` } }),
        h("span", { className: "abwm-bar__seg", style: { width: pl + "%", background: `oklch(0.6 0.1 ${p.hue})`, opacity: 0.45 } })),
      h("div", { className: "abwm-chips" },
        h("span", { className: "hb-badge " + (s.carryWarn ? "hb-badge--over" : "hb-badge--accent") }, s.carry),
        h("span", { className: "hb-badge hb-badge--neutral" }, "Krank " + s.krank),
        h("span", { className: "hb-badge hb-badge--neutral" }, "Kind-krank " + s.kind + " / " + s.cap)));
  }
  function summary() {
    return h("div", { className: "hb-card abwm-sum" }, PEOPLE.map(pcard));
  }

  function legend() {
    const items = [
      { c: cellBg(PAL.urlaub(150), PAL.urlaub(250)), l: "Urlaub" },
      { c: PAL.KRANK, l: "Krank" }, { c: PAL.KIND_KRANK, l: "Kind-krank" },
      { c: PAL.FEIER, l: "Feiertag" }, { c: PAL.teilzeit(220), l: "Teilzeit" },
      { c: PAL.WEEKEND, l: "Wochenende" }, { c: "kita", l: "Kita zu" },
    ];
    return h("div", { className: "abwm-legend" }, items.map((it, i) =>
      h("span", { className: "abwm-legend__i", key: i },
        it.c === "kita" ? h("span", { className: "abwm-legend__sw abwm-legend__sw--kita" })
          : h("span", { className: "abwm-legend__sw", style: { background: it.c } }),
        it.l)));
  }

  // ---- MONTH grid (June 2026) ----
  function chipFor(pid, st) {
    if (!st.type && !st.holiday && !st.ptOff) return null;
    const txt = st.type ? (st.half ? (st.half === "vm" ? "AM" : "PM") : (pid === "max" ? "M" : "C"))
      : st.holiday ? (pid === "max" ? "M" : "C") : (pid === "max" ? "M" : "C");
    const title = `${PEOPLE.find((p) => p.id === pid).name}: ` +
      (st.type ? (st.half ? (st.half === "vm" ? "½ vormittags " : "½ nachmittags ") : "") + TYPES[st.type]
        : st.holiday ? "Feiertag · " + st.holiday : "frei");
    return h("span", { key: pid, className: "abwm-mc", title, style: { background: colorOf(pid, st), color: "oklch(0.26 0.03 150)" } }, txt);
  }
  function monthInner(active) {
    const m = 5; // June
    const first = new Date(YEAR, m, 1, 12);
    const lead = (first.getDay() + 6) % 7;
    const start = C.addDays(first, -lead);
    const cells = [];
    for (let i = 0; i < 42; i++) {
      const date = C.addDays(start, i);
      const ds = C.ymd(date);
      const inM = date.getMonth() === m;
      const today = ds === TODAY, weekend = C.isWeekend(date), kita = KITA.has(ds);
      const chips = PEOPLE.map((p) => chipFor(p.id, resolve(p.id, ds))).filter(Boolean);
      cells.push(h("button", { key: ds, className: "abwm-mcell" + (inM ? "" : " is-out") + (weekend ? " is-we" : "") + (today ? " is-today" : "") },
        h("div", { className: "abwm-mcell__top" },
          h("span", { className: "abwm-mcell__n" + (today ? " is-today" : "") }, date.getDate()),
          kita ? h("span", { className: "abwm-mcell__k", title: "Kita geschlossen" }) : null),
        h("div", { className: "abwm-mcell__chips" }, chips)));
    }
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        seg(active),
        summary(),
        h("div", { className: "abwm-mnav" },
          h("button", { className: "m-iconbtn" }, h(Icon, { name: "chevronLeft", size: 20 })),
          h("div", { className: "abwm-mnav__t" }, "Juni ", h("span", null, YEAR)),
          h("button", { className: "m-iconbtn" }, h(Icon, { name: "chevronRight", size: 20 }))),
        h("div", { className: "abwm-mhead" }, C.WD_MIN.map((w, i) => h("div", { className: "abwm-mhead__c", key: w }, w))),
        h("div", { className: "abwm-mgrid" }, cells),
        h("div", { style: { marginTop: 16 } }, legend())),
    ];
  }

  // ---- YEAR grid (months as rows, days as columns) ----
  function yearInner() {
    const cells = [h("div", { className: "abwm-yr__corner", key: "c" })];
    for (let d = 1; d <= 31; d++) cells.push(h("div", { className: "abwm-yr__dh", key: "h" + d }, (d === 1 || d % 7 === 0) ? d : ""));
    for (let m = 0; m < 12; m++) {
      cells.push(h("div", { className: "abwm-yr__ml", key: "l" + m }, C.MON_ABBR[m]));
      const dim = C.daysInMonth(YEAR, m);
      for (let d = 1; d <= 31; d++) {
        if (d > dim) { cells.push(h("div", { className: "abwm-yr__cell abwm-yr__cell--void", key: m + "-" + d })); continue; }
        const ds = `${YEAR}-${C.pad(m + 1)}-${C.pad(d)}`;
        const bg = cellBg(colorOf("max", resolve("max", ds)), colorOf("lea", resolve("lea", ds)));
        const cls = "abwm-yr__cell" + (KITA.has(ds) ? " is-kita" : "") + (ds === TODAY ? " is-today" : "");
        cells.push(h("div", { key: m + "-" + d, className: cls, style: { background: bg } }));
      }
    }
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        seg("jahr"),
        summary(),
        h("div", { className: "abwm-yr" }, cells),
        h("div", { style: { marginTop: 16 } }, legend())),
    ];
  }

  // ---- Screens ----
  window.ScreenAbwesenheit = () => h(Phone, null, monthInner("monat").concat([h(Fab, { key: "fab", icon: "plus", label: "Zeitraum" })]));
  window.ScreenAbwesenheitJahr = () => h(Phone, null, yearInner().concat([h(Fab, { key: "fab", icon: "plus", label: "Zeitraum" })]));

  // ---- Day editor sheet (over month) ----
  function pickRow(active) {
    const opts = [["Arbeit", null], ["Urlaub", "URLAUB"], ["Krank", "KRANK"], ["Kind-krank", "KIND_KRANK"]];
    return h("div", { className: "hb-pickrow" }, opts.map(([l, id]) =>
      h("button", { key: l, className: "hb-pick" + (active === id ? " is-active" : "") }, l)));
  }
  function halfRow(active) {
    return h("div", { className: "abwm-half" }, [["Ganzer Tag", null], ["Vormittag (AM)", "vm"], ["Nachmittag (PM)", "nm"]].map(([l, v]) =>
      h("button", { key: l, className: "abwm-half__b" + (active === v ? " is-active" : "") }, l)));
  }
  function editPerson(pid, note, active, half) {
    return h("div", { className: "abwm-ed" },
      h("div", { className: "abwm-ed__head" },
        h(Avatar, { user: pid, size: 26 }),
        h("span", { className: "abwm-ed__name" }, PEOPLE.find((p) => p.id === pid).name),
        note ? h("span", { className: "abwm-ed__note" }, note) : null),
      pickRow(active),
      active ? halfRow(half) : null);
  }
  window.ScreenAbwesenheitTag = () => h(Phone, null, monthInner("monat"),
    h(Sheet, { title: "Do, 11. Juni 2026", onClose: () => {},
      foot: h("button", { className: "hb-btn hb-btn--primary hb-btn--block" }, "Fertig") },
      editPerson("max", null, null, null),
      editPerson("lea", null, "URLAUB", "vm"),
      h("div", { className: "abwm-kitarow" },
        h("div", null,
          h("div", { className: "abwm-kitarow__t" }, "Kita-Schließtag"),
          h("div", { className: "abwm-kitarow__s hb-muted" }, "Gilt für die ganze Familie")),
        h("span", { className: "abwm-switch" }, h("span", { className: "abwm-switch__k" })))));

  // ---- Zeitraum (period) sheet ----
  window.ScreenAbwesenheitZeitraum = () => h(Phone, null, monthInner("monat"),
    h(Sheet, { title: "Zeitraum eintragen", onClose: () => {},
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Übernehmen")) },
      h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Für wen"),
        h("div", { className: "hb-pickrow" },
          h("button", { className: "hb-pick is-active" }, "Max"),
          h("button", { className: "hb-pick is-active" }, "Lea"))),
      h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Art"),
        h("div", { className: "hb-pickrow" },
          h("button", { className: "hb-pick is-active" }, "Urlaub"),
          h("button", { className: "hb-pick" }, "Krank"),
          h("button", { className: "hb-pick" }, "Kind-krank"),
          h("button", { className: "hb-pick" }, "Löschen"))),
      h("div", { className: "abwm-dates" },
        h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Von"), h("input", { className: "hb-input", type: "date", defaultValue: "2026-07-27", readOnly: true })),
        h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Bis"), h("input", { className: "hb-input", type: "date", defaultValue: "2026-08-07", readOnly: true }))),
      h("div", { className: "hb-muted", style: { fontSize: 12.5, lineHeight: 1.5 } },
        "Nur Arbeitstage — Wochenenden, Feiertage und feste freie Tage werden übersprungen (≈ 10 Tage für Max).")));

  // ---- Settings sheet ----
  function setField(label, value) {
    return h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, label),
      h("input", { className: "hb-input", defaultValue: value, readOnly: true }));
  }
  function ptRow(wd, from, to) {
    return h("div", { className: "abwm-ptrow" },
      h("span", { className: "hb-badge hb-badge--neutral" }, wd + ". frei"),
      h("span", { className: "abwm-ptrow__r hb-muted" }, "ab " + from + (to ? " bis " + to : "")),
      h(Icon, { name: "trash", size: 16, style: { color: "var(--ink-3)" } }));
  }
  function setPerson(pid, state, allow, rest, cap, rules) {
    return h("div", { className: "abwm-setp" },
      h("div", { className: "abwm-setp__head" }, h(Avatar, { user: pid, size: 26 }), PEOPLE.find((p) => p.id === pid).name),
      setField("Bundesland", C.stateName(state)),
      h("div", { className: "abwm-setgrid" }, setField("Anspruch", allow), setField("Resturlaub", rest)),
      h("div", { className: "abwm-setgrid" }, setField("verfällt am", "31.03.2026"), setField("Kind-krank", cap)),
      h("div", { className: "abwm-setp__pt" },
        h("div", { className: "hb-field__label", style: { marginBottom: 8 } }, "Teilzeit · feste freie Tage"),
        rules.map((r, i) => h(React.Fragment, { key: i }, ptRow(r[0], r[1], r[2]))),
        h("button", { className: "hb-link", style: { marginTop: 6 } }, h(Icon, { name: "plus", size: 14 }), " Freien Tag hinzufügen")));
  }
  window.ScreenAbwesenheitSettings = () => h(Phone, null, monthInner("monat"),
    h(Sheet, { full: true, title: "Kalender-Einstellungen", onClose: () => {},
      foot: h("button", { className: "hb-btn hb-btn--primary hb-btn--block" }, "Fertig") },
      setPerson("max", "BE", "30", "3", "15", [["Mo", "01.01.", "30.04."]]),
      setPerson("lea", "BY", "24", "5", "15", [["Fr", "01.03.", null]]),
      h("div", { className: "abwm-setkita" },
        h("div", { className: "hb-field__label", style: { marginBottom: 8 } }, "Kita-Schließtage"),
        h("div", { className: "abwm-kitalist" },
          h("div", { className: "abwm-kitaitem" }, h("span", { className: "hb-mono" }, "16.–17.02."), h("span", null, "Faschingsferien"), h(Icon, { name: "trash", size: 15, style: { color: "var(--ink-3)" } })),
          h("div", { className: "abwm-kitaitem" }, h("span", { className: "hb-mono" }, "05.06."), h("span", null, "Brückentag"), h(Icon, { name: "trash", size: 15, style: { color: "var(--ink-3)" } })),
          h("div", { className: "abwm-kitaitem" }, h("span", { className: "hb-mono" }, "03.–14.08."), h("span", null, "Sommerschließung"), h(Icon, { name: "trash", size: 15, style: { color: "var(--ink-3)" } }))),
        h("button", { className: "hb-link", style: { marginTop: 8 } }, h(Icon, { name: "plus", size: 14 }), " Schließtag / Zeitraum hinzufügen"))));
})();
