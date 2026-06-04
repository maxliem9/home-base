// HomeBase Android — Rezepte (recipes)
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, AppBar, AppbarAction, Fab, Phone, Scroll, Sheet } = window;

  const CATS = ["Alle", "Frühstück", "Hauptgerichte", "Snack", "Dessert", "Getränk"];
  const RECIPES = [
    { id: "pan", title: "Fluffige Buttermilch-Pancakes", cat: "Frühstück", desc: "Sonntagsklassiker — innen weich, außen goldbraun.", time: "25 Min", serv: "4 Portionen", rh: 78 },
    { id: "carb", title: "Spaghetti Carbonara", cat: "Hauptgerichte", desc: "Original ohne Sahne — nur Ei, Pecorino und Pfeffer.", time: "25 Min", serv: "2 Portionen", rh: 46 },
    { id: "lin", title: "Herzhafte Linsensuppe", cat: "Hauptgerichte", desc: "Wärmt an kalten Tagen und schmeckt aufgewärmt noch besser.", time: "55 Min", serv: "4 Portionen", rh: 62 },
    { id: "kuchen", title: "Saftiger Schokoladenkuchen", cat: "Dessert", desc: "Einfach, schokoladig, gelingt immer.", time: "55 Min", serv: "12 Portionen", rh: 32 },
    { id: "balls", title: "Dattel-Energy-Balls", cat: "Snack", desc: "Schneller Snack ohne Backen.", time: "15 Min", serv: "10 Portionen", rh: 40 },
    { id: "tea", title: "Pfirsich-Eistee", cat: "Getränk", desc: "Erfrischend für warme Nachmittage.", time: "5 Min", serv: "4 Portionen", rh: 24 },
  ];

  function Card(r) {
    return h("button", { key: r.id, className: "hb-card hb-recipecard", style: { "--rh": r.rh, textAlign: "left", cursor: "pointer" } },
      h("div", { className: "hb-recipecard__img" },
        h("span", { className: "hb-badge hb-badge--neutral hb-recipecard__cat" }, r.cat),
        h(Icon, { name: "chef", size: 26, stroke: 1.6 }),
        h("span", { className: "hb-recipecard__ph" }, "Foto folgt")),
      h("div", { className: "hb-recipecard__body" },
        h("div", { className: "hb-recipecard__title" }, r.title),
        h("p", { className: "hb-recipecard__desc" }, r.desc),
        h("div", { className: "hb-recipecard__meta" },
          h(Icon, { name: "clock", size: 14 }), r.time,
          h("span", { className: "dot-sep", style: { width: 3, height: 3, borderRadius: "50%", background: "var(--ink-3)" } }),
          h(Icon, { name: "users", size: 14 }), r.serv.split(" ")[0])));
  }

  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "6 Rezepte", title: "Rezepte",
      right: h(AppbarAction, { icon: "search" }) });
  }

  window.ScreenRezepte = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-chiprow" },
        CATS.map((c, i) => h("button", { key: c, className: "hb-tagchip" + (i === 0 ? " is-active" : "") }, c))),
      h("div", { className: "hb-recipe-grid" }, RECIPES.map(Card))),
    h(Fab, { key: "fab", icon: "plus", label: "Rezept" }),
  ]);

  // ---- Detail ----
  const ING = [
    ["250 g", "Spaghetti"], ["120 g", "Guanciale"], ["3 Stk", "Eigelb"],
    ["60 g", "Pecorino"], ["n. G.", "Schwarzer Pfeffer"],
  ];
  const STEPS = [
    "Spaghetti in reichlich Salzwasser al dente kochen.",
    "Guanciale würfeln und in der Pfanne knusprig auslassen.",
    "Eigelb mit geriebenem Pecorino und Pfeffer verrühren.",
    "Nudeln abgießen, etwas Nudelwasser auffangen.",
    "Pfanne von der Hitze nehmen, Nudeln, Ei-Mischung und Nudelwasser zügig zu einer Creme verrühren.",
  ];
  window.ScreenRezeptDetail = () => h(Phone, null, [
    h(AppBar, { key: "ab", leftIcon: "chevronLeft", titleSm: true, title: "Rezept", bordered: true,
      right: h(AppbarAction, { icon: "more" }) }),
    h(Scroll, { key: "sc", style: { paddingTop: 0 } },
      h("div", { className: "hb-recipe-hero", style: { "--rh": 46 } },
        h("span", { className: "hb-badge hb-badge--neutral", style: { position: "absolute", top: 12, left: 12 } }, "Hauptgerichte"),
        h(Icon, { name: "chef", size: 34, stroke: 1.5 }),
        h("span", { className: "hb-recipecard__ph" }, "Foto folgt")),
      h("h1", { className: "hb-note-doc__title", style: { marginTop: 18 } }, "Spaghetti Carbonara"),
      h("p", { style: { color: "var(--ink-3)", fontSize: 14.5, margin: "8px 0 0", lineHeight: 1.5 } },
        "Original ohne Sahne — nur Ei, Pecorino und Pfeffer."),
      h("div", { className: "hb-recipe-facts" },
        h("div", { className: "hb-fact" }, h("div", { className: "hb-fact__v" }, "2"), h("div", { className: "hb-fact__l" }, "Portionen")),
        h("div", { className: "hb-fact" }, h("div", { className: "hb-fact__v" }, "10"), h("div", { className: "hb-fact__l" }, "Vorb. Min")),
        h("div", { className: "hb-fact" }, h("div", { className: "hb-fact__v" }, "15"), h("div", { className: "hb-fact__l" }, "Koch Min")),
        h("div", { className: "hb-fact" }, h("div", { className: "hb-fact__v" }, "25"), h("div", { className: "hb-fact__l" }, "Gesamt"))),
      h("div", { className: "hb-sectionlabel", style: { marginTop: 6 } }, "Zutaten"),
      h("div", { className: "hb-ingredients" },
        ING.map(([a, n]) => h("div", { key: n, className: "hb-ing" },
          h("span", { className: "hb-ing__amt" }, a), h("span", null, n)))),
      h("div", { className: "hb-sectionlabel", style: { marginTop: 22 } }, "Zubereitung"),
      h("ol", { className: "hb-steps" },
        STEPS.map((s, i) => h("li", { key: i, className: "hb-step" },
          h("span", { className: "hb-step__n" }, i + 1), h("span", null, s)))),
      h("div", { style: { display: "flex", gap: 10, marginTop: 26 } },
        h("button", { className: "hb-btn hb-btn--ghost hb-link--danger", style: { flex: "0 0 auto" } },
          h(Icon, { name: "trash", size: 17 }), "Löschen"),
        h("button", { className: "hb-btn hb-btn--primary", style: { flex: 1 } },
          h(Icon, { name: "cart", size: 17 }), "Zutaten zur Liste")),
    ),
    h("div", { key: "toast", className: "m-toast" },
      h(Icon, { name: "checkCircle", size: 18, style: { color: "var(--accent-soft-2)" } }),
      h("span", null, "5 Zutaten zur Einkaufsliste hinzugefügt"),
      h("span", { className: "m-toast__link" }, "Ansehen")),
  ]);

  // ---- Create form sheet ----
  function selectField(label, value) {
    return h("div", { className: "hb-field" },
      h("label", { className: "hb-field__label" }, label),
      h("div", { style: { position: "relative" } },
        h("div", { className: "hb-input", style: { display: "flex", alignItems: "center", paddingRight: 38 } }, value),
        h(Icon, { name: "chevronDown", size: 18, style: { position: "absolute", right: 12, top: 13, color: "var(--ink-3)" } })));
  }
  window.ScreenRezeptNeu = () => h(Phone, null, ScreenRezepteInner(),
    h(Sheet, {
      full: true, title: "Neues Rezept",
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Speichern")),
    },
      h("div", { className: "m-fieldgap" },
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Titel"),
          h("input", { className: "hb-input", placeholder: "z. B. Ofengemüse", readOnly: true })),
        selectField("Kategorie", "Hauptgerichte"),
        h("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 10 } },
          h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Portionen"), h("input", { className: "hb-input", defaultValue: "4", readOnly: true })),
          h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Vorb."), h("input", { className: "hb-input", defaultValue: "15", readOnly: true })),
          h("div", { className: "hb-field" }, h("label", { className: "hb-field__label" }, "Kochen"), h("input", { className: "hb-input", defaultValue: "30", readOnly: true }))),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Beschreibung"),
          h("textarea", { className: "hb-input", rows: 2, placeholder: "Kurz beschreiben …", readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Zutaten"),
          h("textarea", { className: "hb-input", rows: 3, defaultValue: "200 g Mehl\n300 ml Milch\n2 Stk Eier", readOnly: true,
            style: { fontFamily: "var(--font-mono)", fontSize: 13.5 } }),
          h("div", { style: { fontSize: 12, color: "var(--ink-3)", marginTop: 4 } }, "Eine pro Zeile, z. B. „200 g Mehl“")),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Schritte"),
          h("textarea", { className: "hb-input", rows: 3, placeholder: "Ein Schritt pro Zeile …", readOnly: true })),
      )));

  function ScreenRezepteInner() {
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        h("div", { className: "hb-chiprow" },
          CATS.map((c, i) => h("button", { key: c, className: "hb-tagchip" + (i === 0 ? " is-active" : "") }, c))),
        h("div", { className: "hb-recipe-grid" }, RECIPES.slice(0, 4).map(Card))),
    ];
  }

  // ---- Empty ----
  window.ScreenRezeptEmpty = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h("div", { className: "hb-chiprow" },
        CATS.map((c, i) => h("button", { key: c, className: "hb-tagchip" + (c === "Getränk" ? " is-active" : "") }, c))),
      h("div", { className: "hb-empty" },
        h("div", { className: "hb-empty__icon" }, h(Icon, { name: "chef", size: 26 })),
        h("div", { className: "hb-empty__title" }, "Keine Rezepte"),
        h("div", { className: "hb-empty__hint" }, "In „Getränk“ ist noch nichts.", h("br"), "Lege ein neues Rezept an.")),
    ),
    h(Fab, { key: "fab", icon: "plus", label: "Rezept" }),
  ]);
})();
