// HomeBase Android — shared shell: icons, phone frame, app bar, FAB, drawer.
// Exposes to window for the screen modules.
(function () {
  const React = window.React;
  const h = React.createElement;

  // ---- Icon set (24x24, currentColor stroke) ----
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
    chevronUp: "M6 15l6-6 6 6",
    calendar: "M4 6a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6Z M4 9h16 M8 3v4 M16 3v4",
    inbox: "M4 13h4l1.5 3h5L16 13h4 M4 13 6 5h12l2 8v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6Z",
    flag: "M5 21V4 M5 4h12l-2 4 2 4H5",
    lock: "M7 10V8a5 5 0 0 1 10 0v2 M5 10h14v10H5z",
    users: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z M2.5 20a6.5 6.5 0 0 1 13 0 M16 4.5a3.5 3.5 0 0 1 0 7 M18 14.2A6.5 6.5 0 0 1 21.5 20",
    archive: "M4 7h16v3H4z M5 10h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-9Z M10 14h4",
    send: "M4 11.5 20 4l-6 16-2.5-7L4 11.5Z",
    sparkle: "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z",
    dot: "M12 12m-3 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0",
    menu: "M4 7h16 M4 12h16 M4 17h16",
    more: "M12 6.5a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z M12 12.6a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z M12 18.7a.6.6 0 1 0 0-1.2.6.6 0 0 0 0 1.2Z",
    bell: "M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6 M9.5 19a2.5 2.5 0 0 0 5 0",
    settings: "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z M19.4 13a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V19a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.2 5.4l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1V1a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1Z",
    list: "M8 6h12 M8 12h12 M8 18h12 M4 6h.01 M4 12h.01 M4 18h.01",
  };
  function Icon({ name, size = 22, stroke = 1.8, fill = false, style }) {
    const d = P[name] || P.dot;
    return h("svg", {
      width: size, height: size, viewBox: "0 0 24 24",
      fill: fill ? "currentColor" : "none",
      stroke: fill ? "none" : "currentColor",
      strokeWidth: stroke, strokeLinecap: "round", strokeLinejoin: "round",
      style: { flexShrink: 0, display: "block", ...style },
    }, h("path", { d }));
  }

  // ---- Avatar (initial on hue circle) ----
  const HUES = { max: 150, lea: 250 };
  function Avatar({ user, size = 26 }) {
    if (user === "empty") {
      return h("div", { className: "hb-avatar hb-avatar--empty",
        style: { width: size, height: size, fontSize: size * 0.42 } });
    }
    const hue = HUES[user] || 150;
    const initial = user === "lea" ? "L" : "M";
    return h("div", { className: "hb-avatar",
      style: { width: size, height: size, fontSize: size * 0.42,
        background: `oklch(0.62 0.09 ${hue})` } }, initial);
  }

  // ---- App bar ----
  function AppBar({ leftIcon = "menu", onLeft, eyebrow, title, titleSm, right, bordered }) {
    return h("div", { className: "m-appbar" + (bordered ? " m-appbar--bordered" : "") },
      h("button", { className: "m-iconbtn", onClick: onLeft },
        h(Icon, { name: leftIcon, size: 24 })),
      h("div", { className: "m-appbar__title" },
        eyebrow && h("div", { className: "m-appbar__eyebrow" }, eyebrow),
        h("div", { className: "m-appbar__h" + (titleSm ? " m-appbar__h--sm" : "") }, title)),
      right || null,
    );
  }
  function AppbarAction({ icon, badge, onClick }) {
    return h("button", { className: "m-iconbtn", onClick },
      h(Icon, { name: icon, size: 23 }),
      badge != null && h("span", { className: "m-iconbtn__badge" }, badge));
  }

  // ---- FAB ----
  function Fab({ icon = "plus", label, onClick }) {
    return h("button", { className: "m-fab" + (label ? "" : " m-fab--round"), onClick },
      h(Icon, { name: icon, size: 24, stroke: 2 }),
      label && h("span", null, label));
  }

  // ---- Phone frame (reuses starter status bar + gesture nav) ----
  const AndroidStatusBar = window.AndroidStatusBar;
  const AndroidNavBar = window.AndroidNavBar;
  function Phone({ children }) {
    return h("div", { className: "hb-frame" },
      h("div", { className: "hb-statusbar" }, h(AndroidStatusBar, { dark: false })),
      h("div", { className: "hb-screen" }, children),
      h("div", { className: "hb-navhost" }, h(AndroidNavBar, { dark: false })),
    );
  }

  // Scrollable content region
  function Scroll({ children, style }) {
    return h("div", { className: "hb-scroll" },
      h("div", { className: "hb-scroll__inner", style }, children));
  }

  // ---- Navigation drawer (overlay) ----
  const NAV = [
    { id: "heute", icon: "home", label: "Heute" },
    { id: "aufgaben", icon: "checkCircle", label: "Aufgaben", badge: 4 },
    { id: "einkauf", icon: "cart", label: "Einkaufsliste", badge: 10 },
    { id: "notizen", icon: "note", label: "Notizen" },
    { id: "zeit", icon: "clock", label: "Zeiterfassung", dot: true },
    { id: "rezepte", icon: "chef", label: "Rezepte" },
  ];
  function Drawer({ active = "heute" }) {
    return h(React.Fragment, null,
      h("div", { className: "m-scrim" }),
      h("div", { className: "m-drawer" },
        h("div", { className: "m-drawer__brand" },
          h("div", { className: "m-brandmark" }, h(Icon, { name: "home", size: 22 })),
          h("div", null,
            h("div", { className: "m-brandname" }, "HomeBase"),
            h("div", { className: "m-brandsub" }, "Max & Lea"))),
        h("div", { className: "m-nav" },
          NAV.map((n) => h("button", { key: n.id, className: "m-navitem" + (n.id === active ? " is-active" : "") },
            h(Icon, { name: n.icon, size: 21 }),
            h("span", null, n.label),
            n.badge != null && h("span", { className: "m-navitem__badge" }, n.badge),
            n.dot && h("span", { className: "m-navitem__dot" }),
          ))),
        h("div", { className: "m-drawer__foot" },
          h(Avatar, { user: "max", size: 36 }),
          h("div", null,
            h("div", { className: "m-userchip__name" }, "Max"),
            h("div", { className: "m-userchip__sub" }, "Echtzeit-Sync aktiv")),
          h("span", { className: "m-syncdot" })),
      ),
    );
  }

  // ---- Bottom sheet ----
  function Sheet({ title, onClose, children, foot, full }) {
    return h("div", { className: "m-sheet-wrap" },
      h("div", { className: "m-scrim" }),
      h("div", { className: "m-sheet" + (full ? " m-sheet--full" : "") },
        h("div", { className: "m-sheet__grip" }),
        title && h("div", { className: "m-sheet__head" },
          h("h3", null, title),
          h("button", { className: "m-iconbtn", onClick: onClose }, h(Icon, { name: "x", size: 22 }))),
        h("div", { className: "m-sheet__body" }, children),
        foot && h("div", { className: "m-sheet__foot" }, foot),
      ),
    );
  }

  Object.assign(window, { Icon, Avatar, AppBar, AppbarAction, Fab, Phone, Scroll, Drawer, Sheet });
})();
