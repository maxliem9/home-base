// HomeBase Android — Aufgaben (tasks)
(function () {
  const React = window.React;
  const h = React.createElement;
  const { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Sheet } = window;

  const PRIO = {
    HOCH: { c: "oklch(0.58 0.16 25)", label: "Hoch" },
    MITTEL: { c: "oklch(0.72 0.13 70)", label: "Mittel" },
    NIEDRIG: { c: "oklch(0.64 0.08 195)", label: "Niedrig" },
  };
  function Prio({ p }) {
    const it = PRIO[p]; if (!it) return null;
    return h("span", { className: "hb-prio", style: { color: it.c } },
      h("span", { className: "hb-prio__dot", style: { background: it.c } }), it.label);
  }
  function Due({ text, tone }) {
    return h("span", { className: "hb-badge hb-badge--" + tone }, text);
  }

  function SubPill({ done, total, open }) {
    return h("button", { className: "hb-subtoggle" + (open ? " is-open" : "") + (total === 0 ? " is-empty" : "") },
      h("span", { className: "hb-subtoggle__c" }, total === 0 ? "Unteraufgaben" : `${done}/${total}`),
      h(Icon, { name: open ? "chevronUp" : "chevronDown", size: 14 }));
  }

  function Task({ title, desc, prio, due, user, sub, undated, open, children, done }) {
    return h("div", null,
      h("div", { className: "hb-row" + (done ? " hb-row--done" : "") },
        h("div", { className: "hb-check" + (done ? " is-checked" : "") },
          done && h(Icon, { name: "check", size: 14, stroke: 2.6 })),
        h("div", { className: "hb-row__main" },
          h("div", { className: "hb-row__title" }, title),
          (prio || desc || due) && h("div", { className: "hb-row__meta" },
            prio && h(Prio, { p: prio }),
            due && h(Due, due),
            desc && h("span", { style: { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } }, desc))),
        h("div", { className: "hb-row__right" },
          sub && h(SubPill, { done: sub.done, total: sub.total, open }),
          undated
            ? h("button", { className: "hb-btn hb-btn--secondary hb-btn--sm" }, "Planen")
            : h(Avatar, { user, size: 26 }))),
      open && children);
  }

  function Sub({ title, done }) {
    return h("div", { className: "hb-subrow" + (done ? " is-done" : "") },
      h("div", { className: "hb-check" + (done ? " is-checked" : "") },
        done && h(Icon, { name: "check", size: 12, stroke: 2.8 })),
      h("div", { className: "hb-subrow__title" }, title));
  }

  function GroupLabel({ label, count }) {
    return h("div", { className: "hb-sectionlabel", style: { marginTop: 18, display: "flex", gap: 8, alignItems: "center" } },
      label, h("span", { style: { color: "var(--ink-3)", fontFamily: "var(--font-mono)", fontWeight: 700 } }, count));
  }

  function Tabs({ active }) {
    const lists = [
      { id: "haushalt", name: "Haushalt", count: 4 },
      { id: "familie", name: "Familie & Termine", count: 4 },
      { id: "max", name: "Persönlich", count: 2, lock: true },
    ];
    return h("div", { className: "hb-tabs" },
      lists.map((l) => h("button", { key: l.id, className: "hb-tab" + (l.id === active ? " is-active" : "") },
        l.lock && h(Icon, { name: "lock", size: 14 }),
        l.name,
        h("span", { className: "hb-tab__count" }, l.count))),
      h("button", { className: "hb-tab hb-tab--add" }, h(Icon, { name: "plus", size: 16 }), "Neue Liste"));
  }

  function appbar() {
    return h(AppBar, { key: "ab", leftIcon: "menu", eyebrow: "Aufgaben", title: "Haushalt",
      right: h(AppbarAction, { icon: "more" }) });
  }
  function quickadd() {
    return h("div", { className: "hb-quickadd", style: { marginBottom: 4 } },
      h(Icon, { name: "plus", size: 19, style: { color: "var(--ink-3)" } }),
      h("input", { placeholder: "Aufgabe hinzufügen …", readOnly: true }));
  }

  // ---- List screen ----
  function listInner(expandSteuer) {
    return [
      appbar(),
      h(Scroll, { key: "sc" },
        h(Tabs, { active: "haushalt" }),
        quickadd(),
        h(GroupLabel, { label: "Heute", count: 2 }),
        h("div", { className: "hb-list" },
          h(Task, { title: "Müll rausbringen", prio: "MITTEL", due: { text: "Heute", tone: "today" }, user: "max" }),
          h(Task, { title: "Blumen auf dem Balkon gießen", prio: "NIEDRIG", due: { text: "Heute", tone: "today" }, user: "lea" })),
        h(GroupLabel, { label: "Demnächst", count: 1 }),
        h("div", { className: "hb-list" },
          h(Task, { title: "Stromzähler ablesen", prio: "MITTEL", due: { text: "Morgen", tone: "soon" }, user: "max",
            desc: "Stand fotografieren" })),
        h(GroupLabel, { label: "Ohne Datum", count: 1 }),
        h("div", { className: "hb-list" },
          h(Task, {
            title: "Steuerunterlagen sortieren", undated: true, sub: { done: 1, total: 3 },
            open: expandSteuer,
            children: expandSteuer && h("div", { className: "hb-subtasks" },
              h(Sub, { title: "Belege sammeln", done: true }),
              h(Sub, { title: "Nach Kategorie sortieren", done: false }),
              h(Sub, { title: "Scannen", done: false }),
              h("div", { className: "hb-subadd" },
                h(Icon, { name: "plus", size: 16 }),
                h("input", { placeholder: "Unteraufgabe hinzufügen …", readOnly: true }))),
          })),
        // done collapsible
        h("button", { className: "hb-donehead", style: { marginTop: 22 } },
          h(Icon, { name: "chevronRight", size: 16, style: { color: "var(--ink-3)" } }),
          h("span", { className: "hb-sectionlabel", style: { margin: 0 } }, "Erledigt"),
          h("span", { className: "hb-donehead__c" }, "3")),
      ),
      h(Fab, { key: "fab", icon: "plus", label: "Aufgabe" }),
    ];
  }

  window.ScreenAufgaben = () => h(Phone, null, listInner(false));
  window.ScreenAufgabenSub = () => h(Phone, null, listInner(true));

  // ---- Edit sheet ----
  window.ScreenAufgabeEdit = () => h(Phone, null, listInner(false),
    h(Sheet, {
      title: "Aufgabe bearbeiten",
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--ghost hb-link--danger", style: { flex: "0 0 auto" } },
          h(Icon, { name: "trash", size: 17 })),
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Speichern")),
    },
      h("div", { className: "m-fieldgap" },
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Titel"),
          h("input", { className: "hb-input", defaultValue: "Müll rausbringen", readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Beschreibung"),
          h("textarea", { className: "hb-input", rows: 2, defaultValue: "Gelber Sack + Restmüll.", readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Zuständig"),
          h("div", { className: "hb-pickrow" },
            h("button", { className: "hb-pick is-active" }, h(Avatar, { user: "max", size: 20 }), "Max"),
            h("button", { className: "hb-pick" }, h(Avatar, { user: "lea", size: 20 }), "Lea"),
            h("button", { className: "hb-pick" }, "Niemand"))),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Fällig"),
          h("input", { className: "hb-input", defaultValue: "Heute · 3. Juni", readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Priorität"),
          h("div", { className: "hb-pickrow" },
            h("button", { className: "hb-pick" }, h("span", { className: "hb-prio__dot", style: { background: PRIO.NIEDRIG.c } }), "Niedrig"),
            h("button", { className: "hb-pick is-active" }, h("span", { className: "hb-prio__dot", style: { background: PRIO.MITTEL.c } }), "Mittel"),
            h("button", { className: "hb-pick" }, h("span", { className: "hb-prio__dot", style: { background: PRIO.HOCH.c } }), "Hoch"))),
      )));

  // ---- New list sheet ----
  window.ScreenAufgabenNeu = () => h(Phone, null, listInner(false),
    h(Sheet, {
      title: "Neue Liste",
      foot: h(React.Fragment, null,
        h("button", { className: "hb-btn hb-btn--secondary" }, "Abbrechen"),
        h("button", { className: "hb-btn hb-btn--primary" }, "Erstellen")),
    },
      h("div", { className: "m-fieldgap" },
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Name"),
          h("input", { className: "hb-input", placeholder: "z. B. Garten", autoFocus: false, readOnly: true })),
        h("div", { className: "hb-field" },
          h("label", { className: "hb-field__label" }, "Sichtbarkeit"),
          h("div", { className: "hb-seg" },
            h("button", { className: "hb-seg__item is-active" }, h(Icon, { name: "users", size: 17 }), "Geteilt"),
            h("button", { className: "hb-seg__item" }, h(Icon, { name: "lock", size: 16 }), "Privat")),
          h("div", { style: { fontSize: 12.5, color: "var(--ink-3)", marginTop: 4 } },
            "Geteilte Listen sehen beide. Private nur du.")),
      )));

  // ---- Empty ----
  window.ScreenAufgabenEmpty = () => h(Phone, null, [
    appbar(),
    h(Scroll, { key: "sc" },
      h(Tabs, { active: "haushalt" }),
      quickadd(),
      h("div", { className: "hb-empty" },
        h("div", { className: "hb-empty__icon" }, h(Icon, { name: "checkCircle", size: 26 })),
        h("div", { className: "hb-empty__title" }, "Alles erledigt"),
        h("div", { className: "hb-empty__hint" }, "Keine offenen Aufgaben in dieser Liste.", h("br"), "Füge oben eine neue hinzu.")),
    ),
    h(Fab, { key: "fab", icon: "plus", label: "Aufgabe" }),
  ]);
})();
