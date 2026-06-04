// HomeBase Android — canvas assembly
(function () {
  const React = window.React;
  const ReactDOM = window.ReactDOM;
  const h = React.createElement;
  const { DesignCanvas, DCSection, DCArtboard } = window;

  const W = 412, H = 892;
  const board = (id, label, node) =>
    h(DCArtboard, { key: id, id, label, width: W, height: H,
      style: { background: "transparent", boxShadow: "none", overflow: "visible" } },
      h("div", { className: "hbphone" }, node()));

  function App() {
    return h(DesignCanvas, null,
      h(DCSection, { id: "shell", title: "Navigation & Dashboard", subtitle: "Schubladen-Navigation + Tagesübersicht" },
        board("drawer", "Navigation (Schublade)", window.ScreenDrawer),
        board("heute", "Heute · Dashboard", window.ScreenHeute)),

      h(DCSection, { id: "aufgaben", title: "Aufgaben", subtitle: "Listen, Fälligkeits-Gruppen, Unteraufgaben" },
        board("auf-list", "Liste · gruppiert", window.ScreenAufgaben),
        board("auf-sub", "Unteraufgaben offen", window.ScreenAufgabenSub),
        board("auf-edit", "Aufgabe bearbeiten", window.ScreenAufgabeEdit),
        board("auf-neu", "Neue Liste", window.ScreenAufgabenNeu),
        board("auf-empty", "Leerer Zustand", window.ScreenAufgabenEmpty)),

      h(DCSection, { id: "einkauf", title: "Einkauf", subtitle: "Geteilte Listen, „Im Wagen“, Eingabe" },
        board("ein-list", "Liste · Im Wagen", window.ScreenEinkauf),
        board("ein-add", "Artikel eingeben", window.ScreenEinkaufAdd),
        board("ein-neu", "Neue Liste", window.ScreenEinkaufNeu),
        board("ein-empty", "Leerer Zustand", window.ScreenEinkaufEmpty)),

      h(DCSection, { id: "notizen", title: "Notizen", subtitle: "Übersicht, Markdown-Detail" },
        board("not-list", "Liste · Tags", window.ScreenNotizen),
        board("not-detail", "Notiz · Detail", window.ScreenNotizDetail),
        board("not-empty", "Leerer Zustand", window.ScreenNotizEmpty)),

      h(DCSection, { id: "zeit", title: "Zeiterfassung", subtitle: "Timer, Projekte, Auswertung" },
        board("zeit-run", "Timer läuft", window.ScreenZeit),
        board("zeit-idle", "Kein Timer aktiv", window.ScreenZeitIdle),
        board("zeit-detail", "Projekt · Detail", window.ScreenZeitDetail),
        board("zeit-neu", "Neues Projekt", window.ScreenZeitNeu)),

      h(DCSection, { id: "rezepte", title: "Rezepte", subtitle: "Übersicht, Detail, Erfassung" },
        board("rez-grid", "Übersicht", window.ScreenRezepte),
        board("rez-detail", "Rezept · Detail", window.ScreenRezeptDetail),
        board("rez-neu", "Neues Rezept", window.ScreenRezeptNeu),
        board("rez-empty", "Leerer Zustand", window.ScreenRezeptEmpty)),
    );
  }

  ReactDOM.createRoot(document.getElementById("root")).render(h(App));
})();
