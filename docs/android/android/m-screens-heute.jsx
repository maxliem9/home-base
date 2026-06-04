// HomeBase Android — Heute (dashboard) + nav drawer
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Drawer } = window;

  const PRIO = {
    HOCH: { c: "oklch(0.58 0.16 25)", label: "Hoch" },
    MITTEL: { c: "oklch(0.72 0.13 70)", label: "Mittel" },
    NIEDRIG: { c: "oklch(0.64 0.08 195)", label: "Niedrig" },
  };
  function Prio({ p }) {
    const it = PRIO[p];
    return h("span", { className: "hb-prio", style: { color: it.c } },
      h("span", { className: "hb-prio__dot", style: { background: it.c } }), it.label);
  }

  function Stat({ icon, value, label }) {
    return h("div", { className: "hb-stat" },
      h("div", { className: "hb-stat__icon" }, h(Icon, { name: icon, size: 19 })),
      h("div", { className: "hb-stat__value" }, value),
      h("div", { className: "hb-stat__label" }, label));
  }

  function TaskMini({ title, prio, user }) {
    return h("div", { className: "hb-row" },
      h("div", { className: "hb-check" }),
      h("div", { className: "hb-row__main" },
        h("div", { className: "hb-row__title" }, title),
        h("div", { className: "hb-row__meta" }, h(Prio, { p: prio }))),
      h("div", { className: "hb-row__right" }, h(Avatar, { user, size: 26 })));
  }

  function ShopMini({ name, user, checked }) {
    return h("div", { className: "hb-row" },
      h("div", { className: "hb-check" + (checked ? " is-checked" : "") },
        checked && h(Icon, { name: "check", size: 14, stroke: 2.6 })),
      h("div", { className: "hb-row__main" },
        h("div", { className: "hb-row__title" + (checked ? "" : ""),
          style: checked ? { color: "var(--ink-3)", textDecoration: "line-through" } : null }, name)),
      h("div", { className: "hb-row__right" }, h(Avatar, { user, size: 24 })));
  }

  function inner() {
    return [
      h(AppBar, { key: "ab", leftIcon: "menu", title: "",
        right: h(React.Fragment, null,
          h(AppbarAction, { icon: "search" }),
          h(AppbarAction, { icon: "bell" })) }),
      h(Scroll, { key: "sc" },
        h("div", { className: "m-appbar__eyebrow", style: { marginLeft: 2 } }, "Mittwoch, 3. Juni"),
        h("div", { className: "m-greeting" }, "Hallo,", h("br"), "Max."),
        h("div", { className: "hb-quickadd", style: { marginBottom: 20 } },
          h(Icon, { name: "sparkle", size: 18, style: { color: "var(--ink-3)" } }),
          h("input", { placeholder: "Schnell erfassen …", readOnly: true }),
          h("button", { className: "hb-quickadd__btn" }, h(Icon, { name: "plus", size: 20, stroke: 2.2 }))),
        h("div", { className: "hb-stats" },
          h(Stat, { icon: "calendar", value: "2", label: "Heute fällig" }),
          h(Stat, { icon: "inbox", value: "4", label: "In der Inbox" }),
          h(Stat, { icon: "clock", value: "2", label: "Morgen fällig" }),
          h(Stat, { icon: "checkCircle", value: "1", label: "Heute erledigt" })),
        // Heute dran
        h("div", { className: "hb-card hb-card--pad", style: { marginBottom: 16 } },
          h("div", { className: "hb-cardhead" },
            h("h3", null, "Heute dran"),
            h("button", { className: "hb-link" }, "Alle Aufgaben", h(Icon, { name: "chevronRight", size: 15 }))),
          h("div", { className: "hb-list" },
            h(TaskMini, { title: "Müll rausbringen", prio: "MITTEL", user: "max" }),
            h(TaskMini, { title: "Blumen auf dem Balkon gießen", prio: "NIEDRIG", user: "lea" }))),
        // Zeiterfassung
        h("div", { className: "hb-card hb-card--pad", style: { marginBottom: 16 } },
          h("div", { className: "hb-cardhead" },
            h("h3", null, "Zeiterfassung"),
            h("button", { className: "hb-link" }, "Öffnen", h(Icon, { name: "chevronRight", size: 15 }))),
          h("div", { className: "hb-runwidget" },
            h("span", { className: "hb-runwidget__pdot", style: { background: "#5b9e7a" } }),
            h("div", { style: { minWidth: 0 } },
              h("div", { style: { fontWeight: 600, fontSize: 15 } }, "Nebenprojekt: App"),
              h("div", { style: { fontSize: 13, color: "var(--ink-3)" } }, "Sync-Bug nachstellen")),
            h("span", { className: "hb-runwidget__clock" }, "01:35:08")),
          h("button", { className: "hb-btn hb-btn--soft hb-btn--sm", style: { marginTop: 14 } },
            h(Icon, { name: "stop", size: 15, fill: true }), "Stoppen")),
        // Einkaufsliste
        h("div", { className: "hb-card hb-card--pad", style: { marginBottom: 16 } },
          h("div", { className: "hb-cardhead" },
            h("h3", null, "Einkaufsliste"),
            h("button", { className: "hb-link" }, "Öffnen", h(Icon, { name: "chevronRight", size: 15 }))),
          h("div", { className: "hb-list" },
            h(ShopMini, { name: "Äpfel", user: "lea" }),
            h(ShopMini, { name: "Bananen", user: "max" }),
            h(ShopMini, { name: "Tomaten", user: "max" }),
            h(ShopMini, { name: "Milch (1,5%)", user: "max" })),
          h("div", { style: { textAlign: "center", marginTop: 12, fontSize: 13.5, color: "var(--ink-3)", fontWeight: 600 } }, "+ 5 weitere")),
        // Abend-Digest
        h("div", { className: "hb-card hb-card--pad hb-digest" },
          h("div", { className: "hb-cardhead", style: { marginBottom: 4 } },
            h("h3", { style: { display: "inline-flex", alignItems: "center", gap: 8 } },
              h(Icon, { name: "send", size: 17, style: { color: "var(--accent)" } }), "Abend-Digest"),
            h("span", { className: "hb-badge hb-badge--neutral" }, "heute · 20:00")),
          h("div", { style: { fontSize: 13, color: "var(--ink-3)", marginBottom: 14 } },
            "Vorschau der Telegram-Nachricht, die ihr beide bekommt."),
          h("div", null,
            h("div", { className: "hb-digest__line" }, h("span", { className: "hb-digest__k" }, "Heute erledigt"), h("span", null, "1")),
            h("div", { className: "hb-digest__line" }, h("span", { className: "hb-digest__k" }, "Neu in der Inbox"), h("span", null, "4")),
            h("div", { className: "hb-digest__line" }, h("span", { className: "hb-digest__k" }, "Morgen fällig"), h("span", null, "2")))),
      ),
    ];
  }

  window.ScreenHeute = () => h(Phone, null, inner());
  window.ScreenDrawer = () => h(Phone, null, inner(), h(Drawer, { active: "heute" }));
})();
