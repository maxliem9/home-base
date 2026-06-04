// HomeBase Android — Einkauf (shopping)
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Sheet } = window;
  const AndroidKeyboard = window.AndroidKeyboard;

  function Item({ name, user, checked }) {
    return h("div", { className: "hb-row" + (checked ? " hb-row--done" : "") },
      h("div", { className: "hb-check" + (checked ? " is-checked" : "") },
        checked && h(Icon, { name: "check", size: 14, stroke: 2.6 })),
      h("div", { className: "hb-row__main" },
        h("div", { className: "hb-row__title" }, name)),
      h("div", { className: "hb-row__right" }, h(Avatar, { user, size: 24 })));
  }

  function Tabs({ active }) {
    return h("div", { className: "hb-tabs" },
      h("button", { className: "hb-tab" + (active === "woche" ? " is-active" : "") }, "Wocheneinkauf",
        h("span", { className: "hb-tab__count" }, "7")),
      h("button", { className: "hb-tab" + (active === "drog" ? " is-active" : "") }, "Drogerie",
        h("span", { className: "hb-tab__count" }, "3")),
      h("button", { className: "hb-tab hb-tab--add" }, h(Icon, { name: "plus", size: 16 }), "Neue Liste"));
  }

  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "Einkaufsliste", title: "Wocheneinkauf",
      right: h(AppbarAction, { icon: "more" }) });
  }
  function addbar() {
    return h("div", { className: "hb-quickadd", style: { marginBottom: 18 } },
      h(Icon, { name: "plus", size: 19, style: { color: "var(--ink-3)" } }),
      h("input", { placeholder: "Artikel hinzufügen …", readOnly: true }));
  }

  const OPEN = [
    ["Äpfel", "lea"], ["Bananen", "max"], ["Tomaten", "max"], ["Milch (1,5%)", "max"],
    ["Naturjoghurt", "lea"], ["Gouda am Stück", "lea"], ["Filterkaffee", "lea"],
  ];

  function listInner() {
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        h(Tabs, { active: "woche" }),
        addbar(),
        h("div", { className: "hb-list" },
          OPEN.map(([n, u]) => h(Item, { key: n, name: n, user: u }))),
        // Im Wagen
        h("div", { style: { display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 24 } },
          h("div", { className: "hb-sectionlabel", style: { margin: 0 } }, "Im Wagen · 2"),
          h("button", { className: "hb-link" }, "Abgehakte entfernen")),
        h("div", { className: "hb-list", style: { marginTop: 8 } },
          h(Item, { name: "Babyspinat", user: "lea", checked: true }),
          h(Item, { name: "Butter", user: "max", checked: true })),
      ),
      h(Fab, { key: "fab", icon: "plus", label: "Artikel" }),
    ];
  }

  window.ScreenEinkauf = () => h(Phone, null, listInner());

  // ---- Add item with keyboard ----
  window.ScreenEinkaufAdd = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc", style: { paddingBottom: 16 } },
      h(Tabs, { active: "woche" }),
      h("div", { className: "hb-list" },
        OPEN.slice(0, 4).map(([n, u]) => h(Item, { key: n, name: n, user: u })))),
    h("div", { key: "comp", style: { padding: "10px 16px", borderTop: "1px solid var(--line-soft)", background: "var(--surface)" } },
      h("div", { className: "hb-quickadd" },
        h(Icon, { name: "plus", size: 19, style: { color: "var(--accent)" } }),
        h("span", { style: { flex: 1, fontSize: 15, color: "var(--ink)" } },
          "Paprika", h("span", { style: { borderLeft: "2px solid var(--accent)", marginLeft: 1 } })),
        h("button", { className: "hb-quickadd__btn" }, h(Icon, { name: "check", size: 18, stroke: 2.4 })))),
    h(AndroidKeyboard, { key: "kb" }),
  ]);

  // ---- New list sheet ----
  window.ScreenEinkaufNeu = () => h(Phone, null, listInner(),
    h(Sheet, {
      title: "Neue Liste",
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Erstellen")),
    },
      h("div", { className: "hb-field" },
        h("label", { className: "hb-field__label" }, "Name"),
        h("input", { className: "hb-input", placeholder: "z. B. Getränkemarkt", readOnly: true }),
        h("div", { style: { fontSize: 12.5, color: "var(--ink-3)", marginTop: 4 } }, "Alle Einkaufslisten sind geteilt."))));

  // ---- Empty ----
  window.ScreenEinkaufEmpty = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h(Tabs, { active: "drog" }),
      addbar(),
      h("div", { className: "hb-empty" },
        h("div", { className: "hb-empty__icon" }, h(Icon, { name: "cart", size: 26 })),
        h("div", { className: "hb-empty__title" }, "Liste ist leer"),
        h("div", { className: "hb-empty__hint" }, "Tippe oben, um den ersten", h("br"), "Artikel hinzuzufügen.")),
    ),
    h(Fab, { key: "fab", icon: "plus", label: "Artikel" }),
  ]);
})();
