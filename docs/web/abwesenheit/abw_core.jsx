/* HomeBase — Abwesenheit: data model, palette + summary math.
   Plain JS. Attaches window.ABW. Depends on HBcal + HB. */
(function () {
  "use strict";
  const C = window.HBcal;

  // Markable, explicit day-types (Feiertag + Teilzeit are *derived*, not stored)
  const TYPES = {
    URLAUB: { id: "URLAUB", label: "Urlaub", short: "U" },
    KRANK: { id: "KRANK", label: "Krank", short: "K" },
    KIND_KRANK: { id: "KIND_KRANK", label: "Kind-krank", short: "KK" },
  };
  // derived display states (not user-set): FEIERTAG, TEILZEIT, WEEKEND, FREI(none)

  // Theme-aware fill palette. urlaub takes the person hue; the rest from configurable hues.
  function palette(theme, opts) {
    opts = opts || {};
    const dark = theme === "dark";
    const hK = opts.krank != null ? opts.krank : 27;
    const hKK = opts.kind != null ? opts.kind : 62;
    const hF = opts.feier != null ? opts.feier : 288;
    return {
      dark,
      urlaub: (hue) => (dark ? `oklch(0.56 0.105 ${hue})` : `oklch(0.7 0.108 ${hue})`),
      KRANK: dark ? `oklch(0.56 0.13 ${hK})` : `oklch(0.71 0.13 ${hK})`,
      KIND_KRANK: dark ? `oklch(0.62 0.115 ${hKK})` : `oklch(0.78 0.125 ${hKK})`,
      FEIERTAG: dark ? `oklch(0.5 0.045 ${hF})` : `oklch(0.82 0.05 ${hF})`,
      teilzeit: (hue) => (dark ? `oklch(0.39 0.035 ${hue})` : `oklch(0.91 0.034 ${hue})`),
      WEEKEND: dark ? "oklch(0.29 0.008 150)" : "oklch(0.925 0.006 130)",
      WORKDAY: "var(--surface)",
      ink: dark ? "oklch(0.16 0.02 150)" : "oklch(0.99 0.01 150)", // text on filled cells (light ink in dark)
      onLight: dark ? "oklch(0.95 0.01 150)" : "oklch(0.3 0.03 150)",
    };
  }

  // colour for a person's resolved day-state
  function colorFor(pal, st) {
    if (!st) return pal.WORKDAY;
    if (st.type) return st.type === "URLAUB" ? pal.urlaub(st.hue) : pal[st.type];
    if (st.holiday) return pal.FEIERTAG;
    if (st.ptOff) return pal.teilzeit(st.hue);
    if (st.weekend) return pal.WEEKEND;
    return pal.WORKDAY;
  }

  // is this user off this weekday under a part-time rule active on `date`?
  function partTimeOff(rules, userId, date, dateStr) {
    const wd = C.isoDow(date); // 1..7
    return rules.some((r) =>
      r.user_id === userId && r.weekday === wd &&
      dateStr >= r.start && (!r.end || dateStr <= r.end));
  }

  // Build a lookup context once per render: holidays per user, absence map, etc.
  function buildContext(db, year) {
    const settings = {};
    const holidays = {};
    const absByUser = {};
    Object.keys(HB.users).forEach((uid) => {
      const s = (db.absSettings || []).find((x) => x.user_id === uid) ||
        { user_id: uid, state: "BE", allowance: 30, carryover: 0, carryoverExpires: `${year}-03-31`, kindKrankCap: 15 };
      settings[uid] = s;
      holidays[uid] = C.holidays(year, s.state);
      absByUser[uid] = {};
    });
    (db.absences || []).forEach((a) => {
      if (a.date.slice(0, 4) !== String(year)) return;
      if (!absByUser[a.user_id]) absByUser[a.user_id] = {};
      absByUser[a.user_id][a.date] = a;
    });
    const kita = {};
    (db.kitaClosures || []).forEach((k) => { kita[k.date] = k; });
    return { year, settings, holidays, absByUser, kita, parttime: db.parttime || [] };
  }

  // Resolve a single person's day → { type?, half?, holiday?, ptOff, weekend, hue, kita }
  function personDay(ctx, userId, dateStr) {
    const date = C.parse(dateStr);
    const hue = (ctx.hue && ctx.hue[userId] != null) ? ctx.hue[userId] : HB.users[userId].hue;
    const abs = ctx.absByUser[userId] && ctx.absByUser[userId][dateStr];
    const holiday = ctx.holidays[userId][dateStr] || null;
    const weekend = C.isWeekend(date);
    const ptOff = partTimeOff(ctx.parttime, userId, date, dateStr);
    return {
      hue,
      type: abs ? abs.type : null,
      half: abs ? abs.half || null : null,
      holiday, weekend, ptOff,
    };
  }

  // would this be a working day absent any leave? (used for counting)
  const wouldWork = (st) => !st.weekend && !st.holiday && !st.ptOff;

  // Per-person yearly summary
  function summarize(ctx, userId, todayStr) {
    const s = ctx.settings[userId];
    let taken = 0, planned = 0, krank = 0, kind = 0;
    C.yearDates(ctx.year).forEach((ds) => {
      const st = personDay(ctx, userId, ds);
      if (!st.type) return;
      const amt = st.half ? 0.5 : 1;
      if (!wouldWork(st)) return; // leave on an already-free day doesn't count
      if (st.type === "URLAUB") { if (ds <= todayStr) taken += amt; else planned += amt; }
      else if (st.type === "KRANK") krank += amt;
      else if (st.type === "KIND_KRANK") kind += amt;
    });
    const allowance = s.allowance || 0;
    const carry = s.carryover || 0;
    const total = allowance + carry;
    const used = taken + planned;
    const remaining = total - used;
    const carryExpired = todayStr > (s.carryoverExpires || `${ctx.year}-03-31`);
    const carryUsed = Math.min(carry, taken);
    const carryLost = carryExpired ? Math.max(0, carry - carryUsed) : 0;
    return {
      allowance, carry, total, taken, planned, used, remaining,
      krank, kind, kindCap: s.kindKrankCap, state: s.state,
      carryExpires: s.carryoverExpires, carryExpired, carryLost,
    };
  }

  // pretty day count: "3", "2,5"
  const fmtDays = (n) => (Number.isInteger(n) ? String(n) : n.toFixed(1).replace(".", ","));

  // inclusive list of date-strings from→to
  function eachDate(from, to) {
    if (from > to) { const t = from; from = to; to = t; }
    const out = [];
    let d = C.parse(from);
    const end = C.parse(to);
    while (d <= end) { out.push(C.ymd(d)); d = C.addDays(d, 1); }
    return out;
  }
  // would this date be a working day for this user (not weekend / holiday / part-time-off)?
  function isWorkdayFor(db, userId, ds) {
    const s = (db.absSettings || []).find((x) => x.user_id === userId) || { state: "BE" };
    const date = C.parse(ds);
    if (C.isWeekend(date)) return false;
    if (C.holidays(date.getFullYear(), s.state)[ds]) return false;
    if (partTimeOff(db.parttime || [], userId, date, ds)) return false;
    return true;
  }

  window.ABW = {
    TYPES, palette, colorFor, partTimeOff, buildContext, personDay, wouldWork, summarize, fmtDays,
    eachDate, isWorkdayFor,
  };
})();
