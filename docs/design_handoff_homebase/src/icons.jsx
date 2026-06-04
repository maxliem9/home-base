// HomeBase — Icons (simple stroke glyphs) + helpers. Exports to window.
(function () {
  const React = window.React;
  const h = React.createElement;

  // Each path drawn on a 24x24 viewBox, stroke = currentColor.
  const P = {
    home: "M3 11.5 12 4l9 7.5M5 10v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-9",
    check: "M4 12.5 9 17.5 20 6.5",
    checkCircle: "M9 12.5 11 14.5 15.5 9.5 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
    circle: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
    plus: "M12 5v14M5 12h14",
    cart: "M3 4h2l2.4 12.2a1 1 0 0 0 1 .8h8.2a1 1 0 0 0 1-.8L21 8H6 M10 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z M17 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z",
    note: "M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z M14 3v5h5",
    clock: "M12 7v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
    chef: "M7 21h10 M8 17h8v-2a4 4 0 1 0-2.5-7.4 3.5 3.5 0 0 0-7 0A4 4 0 1 0 8 15v2Z",
    play: "M8 5.5v13l11-6.5-11-6.5Z",
    stop: "M7 7h10v10H7z",
    search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z M21 21l-4.3-4.3",
    tag: "M3 3h7l11 11-7 7L3 10V3Z M7.5 7.5h.01",
    trash: "M4 7h16 M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2 M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13",
    edit: "M4 20h4L19 9l-4-4L4 16v4Z M14 6l4 4",
    x: "M6 6l12 12M18 6 6 18",
    chevronRight: "M9 6l6 6-6 6",
    chevronLeft: "M15 6l-6 6 6 6",
    chevronDown: "M6 9l6 6 6-6",
    calendar: "M4 6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z M4 9h16 M8 3v4 M16 3v4",
    inbox: "M4 13h4l1.5 3h5L16 13h4 M4 13 6 5h12l2 8v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6Z",
    flag: "M5 21V4 M5 4h12l-2 4 2 4H5",
    lock: "M7 10V8a5 5 0 0 1 10 0v2 M5 10h14v10H5z",
    users: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z M2.5 20a6.5 6.5 0 0 1 13 0 M16 4.5a3.5 3.5 0 0 1 0 7 M18 14.2A6.5 6.5 0 0 1 21.5 20",
    archive: "M4 7h16v3H4z M5 10h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-9Z M10 14h4",
    send: "M4 11.5 20 4l-6 16-2.5-7L4 11.5Z",
    sun: "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10Z M12 1v2 M12 21v2 M4.2 4.2l1.4 1.4 M18.4 18.4l1.4 1.4 M1 12h2 M21 12h2 M4.2 19.8l1.4-1.4 M18.4 5.6l1.4-1.4",
    timer: "M10 2h4 M12 14l3-3 M19 14a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z",
    sparkle: "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z",
    dot: "M12 12m-3 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0",
  };

  function Icon({ name, size = 20, stroke = 1.8, fill = false, style, ...rest }) {
    const d = P[name] || P.dot;
    return h("svg", {
      width: size, height: size, viewBox: "0 0 24 24",
      fill: fill ? "currentColor" : "none",
      stroke: fill ? "none" : "currentColor",
      strokeWidth: stroke, strokeLinecap: "round", strokeLinejoin: "round",
      style: { flexShrink: 0, ...style }, ...rest,
    }, h("path", { d }));
  }

  // ---- formatting helpers (Deutsch) ----
  const HB = window.HB;
  const WD = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];
  const MON = ["Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez."];

  function dueLabel(isoDate) {
    if (!isoDate) return null;
    const d = new Date(isoDate + "T00:00:00");
    const diff = Math.round((d - HB.today) / HB.DAY);
    if (diff === 0) return { text: "Heute", tone: "today" };
    if (diff === 1) return { text: "Morgen", tone: "soon" };
    if (diff === -1) return { text: "Gestern", tone: "over" };
    if (diff < 0) return { text: `${Math.abs(diff)} Tage überfällig`, tone: "over" };
    if (diff < 7) return { text: `In ${diff} Tagen`, tone: "soon" };
    return { text: `${d.getDate()}. ${MON[d.getMonth()]}`, tone: "far" };
  }

  function relTime(isoStr) {
    const mins = Math.round((Date.now() - new Date(isoStr)) / 60000);
    if (mins < 1) return "gerade eben";
    if (mins < 60) return `vor ${mins} Min.`;
    const hrs = Math.round(mins / 60);
    if (hrs < 24) return `vor ${hrs} Std.`;
    const days = Math.round(hrs / 24);
    if (days === 1) return "gestern";
    if (days < 7) return `vor ${days} Tagen`;
    const wk = Math.round(days / 7);
    return `vor ${wk} Wo.`;
  }

  function fmtDuration(ms) {
    const totalSec = Math.floor(ms / 1000);
    const hh = Math.floor(totalSec / 3600);
    const mm = Math.floor((totalSec % 3600) / 60);
    const ss = totalSec % 60;
    const pad = (n) => String(n).padStart(2, "0");
    return `${pad(hh)}:${pad(mm)}:${pad(ss)}`;
  }
  function fmtDurationShort(ms) {
    const totalMin = Math.round(ms / 60000);
    const hh = Math.floor(totalMin / 60);
    const mm = totalMin % 60;
    if (hh === 0) return `${mm} Min`;
    return `${hh} Std ${mm} Min`;
  }
  function clockTime(isoStr) {
    const d = new Date(isoStr);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }

  const WD_LONG = ["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"];

  // separator label for a day in a chronological list
  function dayGroupLabel(isoStr) {
    const d = new Date(isoStr);
    const day = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const diff = Math.round((day - HB.today) / HB.DAY);
    if (diff === 0) return "Heute";
    if (diff === -1) return "Gestern";
    if (diff === -2) return "Vorgestern";
    if (diff < 0 && diff > -7) return WD_LONG[d.getDay()];
    return `${d.getDate()}. ${MON[d.getMonth()]}`;
  }

  // Monday-based week start
  function weekStart(date) {
    const d = new Date(date);
    const dow = (d.getDay() + 6) % 7; // Mon = 0
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() - dow);
  }
  function weekKey(isoStr) {
    const s = weekStart(new Date(isoStr));
    return `${s.getFullYear()}-${String(s.getMonth() + 1).padStart(2, "0")}-${String(s.getDate()).padStart(2, "0")}`;
  }
  // { label, range } — label is "Diese Woche"/"Letzte Woche"/null, range is "12.–18. Mai"
  function weekLabel(isoStr) {
    const s = weekStart(new Date(isoStr));
    const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6);
    const diffWeeks = Math.round((s - weekStart(new Date())) / (7 * HB.DAY));
    let label = null;
    if (diffWeeks === 0) label = "Diese Woche";
    else if (diffWeeks === -1) label = "Letzte Woche";
    const range = s.getMonth() === e.getMonth()
      ? `${s.getDate()}.–${e.getDate()}. ${MON[e.getMonth()]}`
      : `${s.getDate()}. ${MON[s.getMonth()]} – ${e.getDate()}. ${MON[e.getMonth()]}`;
    return { label, range };
  }

  window.Icon = Icon;
  window.HBfmt = { dueLabel, relTime, fmtDuration, fmtDurationShort, clockTime, dayGroupLabel, weekKey, weekLabel, WD, MON };
})();
