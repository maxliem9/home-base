// HomeBase Android — Notizen (notes)
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll } = window;

  const NOTES = [
    { id: 1, title: "Urlaubsplanung Sommer", shared: true, tags: ["urlaub", "reise"], when: "vor 4 Std.", user: "lea",
      preview: "Toskana, Ende Juli — grobe Idee für 10 Tage: Anreise über Nacht, Stopp in Verona, dann Florenz …" },
    { id: 2, title: "WLAN & wichtige Codes", shared: false, tags: ["zuhause", "passwörter"], when: "vor 3 Tagen", user: "max",
      preview: "Zugänge: WLAN-Netz, Gäste-WLAN am Kühlschrank, Heizung Servicecode im Ordner Wohnung …" },
    { id: 3, title: "Geschenkideen Lea 🎁", shared: false, tags: ["geschenke"], when: "gestern", user: "max",
      preview: "Ideen fürs nächste Mal: Töpferkurs am Wochenende, neue Kamera-Tasche (braun), Wochenendtrip …" },
    { id: 4, title: "Hausmeister & Kontakte", shared: true, tags: ["wohnung"], when: "vor 6 Tagen", user: "lea",
      preview: "Wichtige Nummern: Hausmeister Herr Klein (Mo–Fr vormittags), Notdienst Heizung, Vermietung …" },
    { id: 5, title: "Ideen fürs Wohnzimmer", shared: true, tags: ["zuhause", "deko"], when: "vor 1 Wo.", user: "lea",
      preview: "Umgestaltung: großer Teppich in warmem Sandton, Stehlampe mit warmem Licht, mehr Pflanzen …" },
  ];

  function NoteItem(n) {
    return h("button", { key: n.id, className: "hb-noteitem" },
      h("div", { className: "hb-noteitem__top" },
        !n.shared && h(Icon, { name: "lock", size: 14, style: { color: "var(--ink-3)" } }),
        h("div", { className: "hb-noteitem__title" }, n.title)),
      h("div", { className: "hb-noteitem__preview" }, n.preview),
      h("div", { className: "hb-noteitem__meta" },
        h(Avatar, { user: n.user, size: 18 }),
        h("span", null, n.when),
        h("span", { className: "dot-sep", style: { width: 3, height: 3, borderRadius: "50%", background: "var(--ink-3)", opacity: 0.5 } }),
        n.tags.map((t) => h("span", { key: t, className: "hb-tagchip is-static", style: { padding: "2px 9px", fontSize: 12 } }, t))));
  }

  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "Notizen", title: "Notizen",
      right: h(AppbarAction, { icon: "search" }) });
  }

  window.ScreenNotizen = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-tagrow" },
        h("button", { className: "hb-tagchip is-active" }, "Alle"),
        ["urlaub", "zuhause", "geschenke", "wohnung", "deko"].map((t) =>
          h("button", { key: t, className: "hb-tagchip" }, t))),
      h("div", { className: "hb-notes-items" }, NOTES.map(NoteItem))),
    h(Fab, { key: "fab", icon: "plus", label: "Notiz" }),
  ]);

  // ---- Detail ----
  window.ScreenNotizDetail = () => h(Phone, null, [
    h(AppBar, { key: "ab", leftIcon: "chevronLeft", titleSm: true, title: "Notiz", bordered: true,
      right: h(React.Fragment, null,
        h(AppbarAction, { icon: "edit" }),
        h(AppbarAction, { icon: "more" })) }),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-note-doc__title", style: { marginTop: 8 } }, "Urlaubsplanung Sommer"),
      h("div", { className: "hb-note-doc__meta" },
        h("span", { className: "hb-badge hb-badge--accent" }, h(Icon, { name: "users", size: 13 }), "Geteilt"),
        h(Avatar, { user: "lea", size: 20 }),
        h("span", { style: { fontSize: 12.5, color: "var(--ink-3)" } }, "Lea · vor 4 Std.")),
      h("div", { className: "hb-tagrow" },
        ["urlaub", "reise"].map((t) => h("button", { key: t, className: "hb-tagchip is-static" }, t))),
      // rendered markdown
      h("div", { className: "hb-md" },
        h("div", { className: "hb-md-h hb-md-h2" }, "Toskana, Ende Juli"),
        h("p", { className: "hb-md-p" }, "Grobe Idee für 10 Tage:"),
        h("ul", { className: "hb-md-list" },
          h("li", null, h("strong", null, "Anreise"), " über Nacht, Stopp in Verona"),
          h("li", null, "4 Nächte Florenz, dann 4 Nächte am Meer"),
          h("li", null, "Agriturismo statt Hotel — mehr Ruhe")),
        h("div", { className: "hb-md-quote" }, "Budget grob: ", h("strong", null, "1.800 €"), " ohne Sprit"),
        h("div", { className: "hb-md-h hb-md-h3" }, "Noch klären"),
        h("ol", { className: "hb-md-list" },
          h("li", null, "Hund bei Oma oder Tierhotel?"),
          h("li", null, "Mietwagen vor Ort vs. eigenes Auto"),
          h("li", null, "Reiseapotheke auffüllen"))),
    ),
  ]);

  // ---- Empty ----
  window.ScreenNotizEmpty = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-empty" },
        h("div", { className: "hb-empty__icon" }, h(Icon, { name: "note", size: 26 })),
        h("div", { className: "hb-empty__title" }, "Noch keine Notizen"),
        h("div", { className: "hb-empty__hint" }, "Halte Ideen, Codes und Pläne fest —", h("br"), "geteilt oder nur für dich.")),
    ),
    h(Fab, { key: "fab", icon: "plus", label: "Notiz" }),
  ]);
})();
