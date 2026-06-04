// HomeBase Android — Zeiterfassung (time tracking)
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Sheet } = window;

  const PROJECTS = [
    { id: "app", name: "Nebenprojekt: App", color: "#5b9e7a", total: "12 Std 20 Min", running: true },
    { id: "steuer", name: "Steuererklärung", color: "#c9805a", total: "8 Std 05 Min" },
    { id: "garten", name: "Garten & Balkon", color: "#6a8fc0", total: "4 Std 30 Min" },
    { id: "lernen", name: "Spanisch lernen", color: "#c2a14d", total: "2 Std 15 Min" },
  ];

  function ProjCard({ p, idle }) {
    const running = p.running && !idle;
    return h("div", { className: "hb-card hb-projcard" + (running ? " is-running" : "") },
      h("div", { className: "hb-projcard__head" },
        h("span", { className: "hb-pdot", style: { background: p.color } }),
        h("div", { className: "hb-projcard__name" }, p.name)),
      h("button", { className: "hb-projcard__statbtn", style: { background: "none", border: "none", padding: 0, textAlign: "left", cursor: "pointer" } },
        h("div", { className: "hb-projcard__stat" },
          p.total.split(" ")[0], h("span", null, p.total.split(" ").slice(1).join(" ")))),
      running
        ? h("button", { className: "hb-btn hb-btn--soft hb-btn--sm", style: { alignSelf: "flex-start" } },
            h(Icon, { name: "stop", size: 14, fill: true }), "Stopp")
        : h("button", { className: "hb-btn hb-btn--secondary hb-btn--sm", style: { alignSelf: "flex-start" } },
            h(Icon, { name: "play", size: 14, fill: true }), "Start"));
  }

  function EntryRow({ color, name, desc, range, user, dur, own }) {
    return h("div", { className: "hb-row" },
      h("span", { className: "hb-pdot", style: { background: color } }),
      h("div", { className: "hb-row__main" },
        h("div", { className: "hb-row__title", style: { fontSize: 14.5 } }, name,
          desc && h("span", { style: { color: "var(--ink-3)", fontWeight: 400 } }, " · " + desc)),
        h("div", { className: "hb-row__meta" }, h(Avatar, { user, size: 18 }), h("span", null, range))),
      h("div", { className: "hb-row__right" },
        h("span", { className: "hb-mono", style: { fontSize: 13.5, fontWeight: 600 } }, dur),
        own ? h(Icon, { name: "trash", size: 16, style: { color: "var(--ink-3)" } })
            : h(Icon, { name: "lock", size: 15, style: { color: "var(--ink-3)" } })));
  }

  function DaySep({ label, sum }) {
    return h("div", { className: "hb-daysep" },
      h("span", { className: "hb-daysep__label" }, label),
      h("div", { className: "hb-daysep__line" }),
      h("span", { className: "hb-daysep__sum" }, "Σ " + sum));
  }

  function projectsBlock(idle) {
    return h(React.Fragment, null,
      h("div", { style: { display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 8, marginBottom: 12 } },
        h("div", { className: "hb-sectionlabel", style: { margin: 0 } }, "Projekte"),
        h("button", { className: "hb-link" }, "Archiv", h(Icon, { name: "chevronRight", size: 14 }))),
      h("div", { className: "hb-proj-grid" }, PROJECTS.map((p) => h(ProjCard, { key: p.id, p, idle }))));
  }

  function recentBlock() {
    return h(React.Fragment, null,
      h("div", { className: "hb-sectionlabel", style: { marginTop: 26 } }, "Letzte Einträge"),
      h(DaySep, { label: "Heute", sum: "2 Std 45 Min" }),
      h("div", { className: "hb-list" },
        h(EntryRow, { color: "#5b9e7a", name: "Nebenprojekt: App", desc: "Notizen-Editor", range: "14:30 – 16:30", user: "max", dur: "2:00", own: true }),
        h(EntryRow, { color: "#c2a14d", name: "Spanisch lernen", desc: "Vokabeln Einheit 4", range: "09:10 – 09:55", user: "max", dur: "0:45", own: true })),
      h(DaySep, { label: "Gestern", sum: "1 Std 30 Min" }),
      h("div", { className: "hb-list" },
        h(EntryRow, { color: "#c9805a", name: "Steuererklärung", desc: "Belege scannen", range: "10:00 – 11:30", user: "lea", dur: "1:30", own: false })));
  }

  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "Zeiterfassung", title: "Zeit",
      right: h(AppbarAction, { icon: "more" }) });
  }

  // ---- Running ----
  window.ScreenZeit = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-timerhero is-running" },
        h("div", { className: "hb-timerhero__live" }, h("span", { className: "hb-livedot" }), "Läuft"),
        h("div", { className: "hb-timerhero__proj" },
          h("span", { className: "hb-pdot", style: { background: "#5b9e7a" } }), "Nebenprojekt: App"),
        h("div", { className: "hb-timerhero__desc" }, "Sync-Bug nachstellen"),
        h("div", { className: "hb-timerhero__clock" }, "01:35:08"),
        h("button", { className: "hb-btn hb-btn--primary hb-btn--md hb-btn--block" },
          h(Icon, { name: "stop", size: 17, fill: true }), "Timer stoppen")),
      projectsBlock(false),
      recentBlock()),
    h(Fab, { key: "fab", icon: "plus", label: "Projekt" }),
  ]);

  // ---- Idle ----
  window.ScreenZeitIdle = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-timerhero" },
        h("div", { style: { fontSize: 13, fontWeight: 600, color: "var(--ink-3)", textTransform: "uppercase", letterSpacing: "0.05em" } }, "Kein Timer aktiv"),
        h("div", { className: "hb-timerhero__idle-clock", style: { margin: "14px 0 4px" } }, "00:00:00"),
        h("div", { style: { fontSize: 14, color: "var(--ink-3)" } }, "Starte unten ein Projekt, um die Zeit zu erfassen.")),
      projectsBlock(true),
      recentBlock()),
    h(Fab, { key: "fab", icon: "plus", label: "Projekt" }),
  ]);

  // ---- Project detail sheet ----
  function Fact({ v, l }) {
    return h("div", { className: "hb-fact" }, h("div", { className: "hb-fact__v" }, v), h("div", { className: "hb-fact__l" }, l));
  }
  function Week({ label, range, max, lea, total, count, top }) {
    const tot = max + lea;
    return h("div", { className: "hb-weekrow" },
      h("div", { className: "hb-weekrow__head" },
        h("span", { className: "hb-weekrow__label" }, label),
        range && h("span", { className: "hb-weekrow__range" }, range),
        h("span", { className: "hb-weekrow__ms" }, total)),
      h("div", { className: "hb-weekbar" },
        max > 0 && h("div", { className: "hb-weekbar__seg", style: { background: "oklch(0.62 0.09 150)", flex: max } }),
        lea > 0 && h("div", { className: "hb-weekbar__seg", style: { background: "oklch(0.62 0.09 250)", flex: lea } }),
        h("div", { style: { flex: top - tot } })),
      h("div", { className: "hb-weekrow__sub" }, count + " Einträge"));
  }
  window.ScreenZeitDetail = () => h(Phone, null, ScreenZeitBaseInner(),
    h(Sheet, {
      full: true, title: "Nebenprojekt: App",
      foot: h("button", { className: "hb-btn hb-btn--secondary hb-btn--block" }, "Schließen"),
    },
      h("div", { style: { display: "flex", alignItems: "center", gap: 8, marginBottom: 4 } },
        h("span", { className: "hb-pdot", style: { background: "#5b9e7a", width: 13, height: 13 } }),
        h("span", { style: { fontSize: 13, color: "var(--ink-3)", fontWeight: 600 } }, "Aktives Projekt")),
      h("div", { className: "hb-detail-stats" },
        h(Fact, { v: "12 Std", l: "Gesamt" }),
        h(Fact, { v: "4 Std 20", l: "Diese Woche" }),
        h(Fact, { v: "7", l: "Einträge" }),
        h(Fact, { v: "1 Std 45", l: "ø / Eintrag" })),
      h("div", { style: { display: "flex", gap: 10, marginTop: 16, flexWrap: "wrap" } },
        h("div", { className: "hb-detail-user" }, h(Avatar, { user: "max", size: 24 }),
          h("span", { className: "hb-detail-user__name" }, "Max"), h("span", { className: "hb-detail-user__ms" }, "9 Std 40")),
        h("div", { className: "hb-detail-user" }, h(Avatar, { user: "lea", size: 24 }),
          h("span", { className: "hb-detail-user__name" }, "Lea"), h("span", { className: "hb-detail-user__ms" }, "2 Std 40"))),
      h("div", { className: "hb-sectionlabel", style: { marginTop: 22 } }, "Pro Woche"),
      h(Week, { label: "Diese Woche", max: 5, lea: 2, total: "4 Std 20", count: 4, top: 9 }),
      h(Week, { label: "Letzte Woche", max: 4, lea: 1, total: "3 Std 30", count: 3, top: 9 }),
      h(Week, { label: "12.–18. Mai", range: "", max: 6, lea: 3, total: "5 Std 10", count: 5, top: 9 }),
      h("div", { className: "hb-sectionlabel", style: { marginTop: 22 } }, "Alle Einträge"),
      h(DaySep, { label: "Heute", sum: "2 Std 00 Min" }),
      h("div", { className: "hb-list" },
        h(EntryRow, { color: "#5b9e7a", name: "Notizen-Editor", range: "14:30 – 16:30", user: "max", dur: "2:00", own: true })),
      h(DaySep, { label: "Vorgestern", sum: "1 Std 20 Min" }),
      h("div", { className: "hb-list" },
        h(EntryRow, { color: "#5b9e7a", name: "Code-Review", range: "11:00 – 12:20", user: "lea", dur: "1:20", own: false })),
    ));

  // base behind detail sheet = running screen inner (without fab to avoid overlap clutter)
  function ScreenZeitBaseInner() {
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        h("div", { className: "hb-timerhero is-running" },
          h("div", { className: "hb-timerhero__live" }, h("span", { className: "hb-livedot" }), "Läuft"),
          h("div", { className: "hb-timerhero__proj" },
            h("span", { className: "hb-pdot", style: { background: "#5b9e7a" } }), "Nebenprojekt: App"),
          h("div", { className: "hb-timerhero__clock" }, "01:35:08")),
        projectsBlock(false)),
    ];
  }

  // ---- New project sheet ----
  const SWATCHES = ["#5b9e7a", "#c9805a", "#6a8fc0", "#c2a14d", "#a86ab0", "#9a9a9a"];
  window.ScreenZeitNeu = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-timerhero" },
        h("div", { style: { fontSize: 13, fontWeight: 600, color: "var(--ink-3)", textTransform: "uppercase", letterSpacing: "0.05em" } }, "Kein Timer aktiv"),
        h("div", { className: "hb-timerhero__idle-clock", style: { margin: "14px 0 4px" } }, "00:00:00")),
      projectsBlock(true)),
  ],
    h(Sheet, {
      title: "Neues Projekt",
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Erstellen")),
    },
      h("div", { className: "m-fieldgap" },
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Name"),
          h("input", { className: "hb-input", placeholder: "z. B. Renovierung", readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Farbe"),
          h("div", { className: "hb-swatches" },
            SWATCHES.map((c, i) => h("button", { key: c, className: "hb-swatch" + (i === 0 ? " is-active" : ""), style: { background: c } })))),
      )));
})();
