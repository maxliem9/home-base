/* HomeBase — German public-holiday computation + calendar date utils.
   Plain JS (no JSX). Attaches window.HBcal. */
(function () {
  "use strict";

  const pad = (n) => String(n).padStart(2, "0");
  const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  // parse "YYYY-MM-DD" as a *local* date (noon, to dodge DST edges)
  const parse = (s) => {
    const [y, m, d] = s.split("-").map(Number);
    return new Date(y, m - 1, d, 12, 0, 0, 0);
  };
  const addDays = (d, n) => { const r = new Date(d); r.setDate(r.getDate() + n); return r; };
  const daysInMonth = (year, month0) => new Date(year, month0 + 1, 0).getDate();
  // ISO weekday: Mon = 1 … Sun = 7
  const isoDow = (d) => ((d.getDay() + 6) % 7) + 1;
  const isWeekend = (d) => { const w = d.getDay(); return w === 0 || w === 6; };

  // Anonymous Gregorian Easter algorithm → Date of Easter Sunday
  function easter(year) {
    const a = year % 19;
    const b = Math.floor(year / 100);
    const c = year % 100;
    const d = Math.floor(b / 4);
    const e = b % 4;
    const f = Math.floor((b + 8) / 25);
    const g = Math.floor((b - f + 1) / 3);
    const h = (19 * a + b - d - g + 15) % 30;
    const i = Math.floor(c / 4);
    const k = c % 4;
    const l = (32 + 2 * e + 2 * i - h - k) % 7;
    const m = Math.floor((a + 11 * h + 22 * l) / 451);
    const month = Math.floor((h + l - 7 * m + 114) / 31); // 3 = March, 4 = April
    const day = ((h + l - 7 * m + 114) % 31) + 1;
    return new Date(year, month - 1, day, 12);
  }

  // Buß- und Bettag — the Wednesday before Nov 23
  function bussBettag(year) {
    let d = new Date(year, 10, 22, 12);
    while (d.getDay() !== 3) d.setDate(d.getDate() - 1);
    return d;
  }

  const STATES = [
    { code: "BW", name: "Baden-Württemberg" },
    { code: "BY", name: "Bayern" },
    { code: "BE", name: "Berlin" },
    { code: "BB", name: "Brandenburg" },
    { code: "HB", name: "Bremen" },
    { code: "HH", name: "Hamburg" },
    { code: "HE", name: "Hessen" },
    { code: "MV", name: "Mecklenburg-Vorpommern" },
    { code: "NI", name: "Niedersachsen" },
    { code: "NW", name: "Nordrhein-Westfalen" },
    { code: "RP", name: "Rheinland-Pfalz" },
    { code: "SL", name: "Saarland" },
    { code: "SN", name: "Sachsen" },
    { code: "ST", name: "Sachsen-Anhalt" },
    { code: "SH", name: "Schleswig-Holstein" },
    { code: "TH", name: "Thüringen" },
  ];
  const ALL = STATES.map((s) => s.code);
  const stateName = (code) => (STATES.find((s) => s.code === code) || {}).name || code;

  const _cache = {};
  // → { "YYYY-MM-DD": "Feiertagsname", … } for a given year + Bundesland
  function holidays(year, state) {
    const key = year + ":" + state;
    if (_cache[key]) return _cache[key];
    const E = easter(year);
    const off = (n) => addDays(E, n);
    const defs = [
      { d: new Date(year, 0, 1, 12), name: "Neujahr", s: ALL },
      { d: new Date(year, 0, 6, 12), name: "Heilige Drei Könige", s: ["BW", "BY", "ST"] },
      { d: new Date(year, 2, 8, 12), name: "Internationaler Frauentag", s: ["BE", "MV"] },
      { d: off(-2), name: "Karfreitag", s: ALL },
      { d: off(0), name: "Ostersonntag", s: ["BB"] },
      { d: off(1), name: "Ostermontag", s: ALL },
      { d: new Date(year, 4, 1, 12), name: "Tag der Arbeit", s: ALL },
      { d: off(39), name: "Christi Himmelfahrt", s: ALL },
      { d: off(49), name: "Pfingstsonntag", s: ["BB"] },
      { d: off(50), name: "Pfingstmontag", s: ALL },
      { d: off(60), name: "Fronleichnam", s: ["BW", "BY", "HE", "NW", "RP", "SL"] },
      { d: new Date(year, 7, 15, 12), name: "Mariä Himmelfahrt", s: ["SL", "BY"] },
      { d: new Date(year, 8, 20, 12), name: "Weltkindertag", s: ["TH"] },
      { d: new Date(year, 9, 3, 12), name: "Tag der Deutschen Einheit", s: ALL },
      { d: new Date(year, 9, 31, 12), name: "Reformationstag", s: ["BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH"] },
      { d: new Date(year, 10, 1, 12), name: "Allerheiligen", s: ["BW", "BY", "NW", "RP", "SL"] },
      { d: bussBettag(year), name: "Buß- und Bettag", s: ["SN"] },
      { d: new Date(year, 11, 25, 12), name: "1. Weihnachtstag", s: ALL },
      { d: new Date(year, 11, 26, 12), name: "2. Weihnachtstag", s: ALL },
    ];
    const map = {};
    defs.forEach((def) => { if (def.s.includes(state)) map[ymd(def.d)] = def.name; });
    _cache[key] = map;
    return map;
  }

  // All date-strings of a year, grouped/iterable as needed
  function yearDates(year) {
    const out = [];
    for (let m = 0; m < 12; m++) {
      const n = daysInMonth(year, m);
      for (let d = 1; d <= n; d++) out.push(ymd(new Date(year, m, d, 12)));
    }
    return out;
  }

  const MON_FULL = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"];
  const MON_ABBR = ["Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"];
  const WD_MIN = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]; // ISO order
  const WD_SHORT = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"]; // JS getDay order

  window.HBcal = {
    pad, ymd, parse, addDays, daysInMonth, isoDow, isWeekend,
    easter, holidays, yearDates, STATES, ALL, stateName,
    MON_FULL, MON_ABBR, WD_MIN, WD_SHORT,
  };
})();
