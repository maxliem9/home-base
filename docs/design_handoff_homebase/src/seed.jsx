// HomeBase — Seed data (Deutsch). Attaches HB.* to window.
(function () {
  const DAY = 86400000;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const iso = (offsetDays) => {
    const d = new Date(today.getTime() + offsetDays * DAY);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  };
  const ago = (mins) => new Date(Date.now() - mins * 60000).toISOString();

  const users = {
    max: { id: "max", name: "Max", hue: 150, initials: "M" },
    lea: { id: "lea", name: "Lea", hue: 250, initials: "L" },
  };

  let _id = 1000;
  const uid = (p) => `${p}_${++_id}`;

  const todoLists = [
    { id: "tl_haushalt", name: "Haushalt", visibility: "shared", created_by: "lea" },
    { id: "tl_familie", name: "Familie & Termine", visibility: "shared", created_by: "max" },
    { id: "tl_max", name: "Persönlich", visibility: "private", created_by: "max" },
  ];
  const todos = [
    // Inbox
    { id: uid("t"), title: "Geschenk für Mama besorgen", list_id: "tl_familie", description: "", status: "INBOX", assignee: null, due_date: null, priority: null, created_by: "lea", created_at: ago(40), subtasks: [
      { id: uid("st"), title: "Wunschliste checken", done: true },
      { id: uid("st"), title: "Budget festlegen", done: false },
      { id: uid("st"), title: "Bestellen", done: false },
    ] },
    { id: uid("t"), title: "Zahnarzttermin vereinbaren", list_id: "tl_max", description: "Kontrolle, ist überfällig.", status: "INBOX", assignee: null, due_date: null, priority: null, created_by: "max", created_at: ago(180), subtasks: [] },
    { id: uid("t"), title: "Rückgabe Amazon-Paket", list_id: "tl_max", description: "Bis Freitag in der Filiale abgeben.", status: "INBOX", assignee: null, due_date: null, priority: null, created_by: "max", created_at: ago(600), subtasks: [] },
    { id: uid("t"), title: "Steuerunterlagen sortieren", list_id: "tl_haushalt", description: "", status: "INBOX", assignee: null, due_date: null, priority: null, created_by: "lea", created_at: ago(1500), subtasks: [
      { id: uid("st"), title: "Belege sammeln", done: false },
      { id: uid("st"), title: "Nach Kategorie sortieren", done: false },
      { id: uid("st"), title: "Scannen", done: false },
    ] },
    // Planned
    { id: uid("t"), title: "Müll rausbringen", list_id: "tl_haushalt", description: "Gelber Sack + Restmüll.", status: "PLANNED", assignee: "max", due_date: iso(0), priority: "MEDIUM", created_by: "max", created_at: ago(2000), subtasks: [] },
    { id: uid("t"), title: "Blumen auf dem Balkon gießen", list_id: "tl_haushalt", description: "", status: "PLANNED", assignee: "lea", due_date: iso(0), priority: "LOW", created_by: "lea", created_at: ago(2500), subtasks: [] },
    { id: uid("t"), title: "Auto zur Inspektion bringen", list_id: "tl_familie", description: "Termin in der Werkstadt um 9:00.", status: "PLANNED", assignee: "lea", due_date: iso(3), priority: "HIGH", created_by: "max", created_at: ago(3000), subtasks: [
      { id: uid("st"), title: "Termin bestätigen", done: true },
      { id: uid("st"), title: "Scheckheft einpacken", done: false },
    ] },
    { id: uid("t"), title: "Stromzähler ablesen", list_id: "tl_haushalt", description: "Stand fotografieren und an Stadtwerke senden.", status: "PLANNED", assignee: "max", due_date: iso(1), priority: "MEDIUM", created_by: "max", created_at: ago(4000), subtasks: [] },
    { id: uid("t"), title: "Geburtstagskarte für Opa schreiben", list_id: "tl_familie", description: "Wird nächste Woche 80.", status: "PLANNED", assignee: "lea", due_date: iso(1), priority: "HIGH", created_by: "lea", created_at: ago(5000), subtasks: [] },
    { id: uid("t"), title: "Kinokarten reservieren", list_id: "tl_familie", description: "", status: "PLANNED", assignee: "max", due_date: iso(2), priority: "LOW", created_by: "lea", created_at: ago(6000), subtasks: [] },
    // Done
    { id: uid("t"), title: "Wocheneinkauf erledigt", list_id: "tl_haushalt", description: "", status: "DONE", assignee: "max", due_date: iso(0), priority: "MEDIUM", created_by: "max", created_at: ago(8000), done_at: ago(120), subtasks: [] },
    { id: uid("t"), title: "Wäsche gewaschen & aufgehängt", list_id: "tl_haushalt", description: "", status: "DONE", assignee: "lea", due_date: iso(0), priority: "LOW", created_by: "lea", created_at: ago(9000), done_at: ago(300), subtasks: [] },
    { id: uid("t"), title: "Spülmaschine ausgeräumt", list_id: "tl_haushalt", description: "", status: "DONE", assignee: "max", due_date: iso(-1), priority: "LOW", created_by: "max", created_at: ago(12000), done_at: ago(800), subtasks: [] },
  ];

  const shoppingLists = [
    { id: "sl_woche", name: "Wocheneinkauf", created_by: "lea" },
    { id: "sl_drog", name: "Drogerie", created_by: "max" },
  ];
  const shopping = [
    { id: uid("s"), name: "Äpfel", list_id: "sl_woche", checked: false, created_by: "lea" },
    { id: uid("s"), name: "Bananen", list_id: "sl_woche", checked: false, created_by: "max" },
    { id: uid("s"), name: "Babyspinat", list_id: "sl_woche", checked: true, created_by: "lea" },
    { id: uid("s"), name: "Tomaten", list_id: "sl_woche", checked: false, created_by: "max" },
    { id: uid("s"), name: "Milch (1,5%)", list_id: "sl_woche", checked: false, created_by: "max" },
    { id: uid("s"), name: "Naturjoghurt", list_id: "sl_woche", checked: false, created_by: "lea" },
    { id: uid("s"), name: "Butter", list_id: "sl_woche", checked: true, created_by: "max" },
    { id: uid("s"), name: "Gouda am Stück", list_id: "sl_woche", checked: false, created_by: "lea" },
    { id: uid("s"), name: "Filterkaffee", list_id: "sl_woche", checked: false, created_by: "lea" },
    { id: uid("s"), name: "Spülmittel", list_id: "sl_drog", checked: false, created_by: "max" },
    { id: uid("s"), name: "Toilettenpapier", list_id: "sl_drog", checked: false, created_by: "lea" },
    { id: uid("s"), name: "AA-Batterien", list_id: "sl_drog", checked: false, created_by: "max" },
  ];
  const shopCategories = ["Obst & Gemüse", "Kühlware", "Haushalt", "Sonstiges"];

  const notes = [
    {
      id: uid("n"), title: "Urlaubsplanung Sommer", visibility: "shared", tags: ["urlaub", "reise"],
      created_by: "lea", updated_at: ago(220),
      content: `## Toskana, Ende Juli\n\nGrobe Idee für 10 Tage:\n\n- **Anreise** über Nacht, Stopp in Verona\n- 4 Nächte Florenz, dann 4 Nächte am Meer\n- Agriturismo statt Hotel — mehr Ruhe\n\n> Budget grob: **1.800 €** ohne Sprit\n\n### Noch klären\n1. Hund bei Oma oder Tierhotel?\n2. Mietwagen vor Ort vs. eigenes Auto\n3. Reiseapotheke auffüllen`,
    },
    {
      id: uid("n"), title: "WLAN & wichtige Codes", visibility: "private", tags: ["zuhause", "passwörter"],
      created_by: "max", updated_at: ago(4300),
      content: `## Zugänge\n\n- **WLAN:** HomeBase-Netz\n- **Gäste-WLAN:** Passwort liegt am Kühlschrank\n- Heizung Servicecode: siehe Ordner *Wohnung*\n\nBitte nicht teilen.`,
    },
    {
      id: uid("n"), title: "Geschenkideen Lea 🎁", visibility: "private", tags: ["geschenke"],
      created_by: "max", updated_at: ago(1440),
      content: `### Ideen fürs nächste Mal\n\n- Töpferkurs am Wochenende\n- Die neue Kamera-Tasche (braun)\n- Wochenendtrip nach Hamburg\n- Lieblingstee nachbestellen`,
    },
    {
      id: uid("n"), title: "Hausmeister & Kontakte", visibility: "shared", tags: ["wohnung"],
      created_by: "lea", updated_at: ago(9000),
      content: `## Wichtige Nummern\n\n- **Hausmeister Herr Klein** — erreichbar Mo–Fr vormittags\n- **Notdienst Heizung** — Aushang im Treppenhaus\n- **Vermietung** — per E-Mail bevorzugt`,
    },
    {
      id: uid("n"), title: "Ideen fürs Wohnzimmer", visibility: "shared", tags: ["zuhause", "deko"],
      created_by: "lea", updated_at: ago(15000),
      content: `### Umgestaltung\n\n- Großer Teppich in warmem Sandton\n- Stehlampe mit warmem Licht\n- Mehr Pflanzen am Fenster\n- Bilderleiste über dem Sofa`,
    },
  ];

  const projects = [
    { id: "p_app", name: "Nebenprojekt: App", color: "#5b9e7a", archived: false, created_by: "max", created_at: ago(60000) },
    { id: "p_steuer", name: "Steuererklärung", color: "#c9805a", archived: false, created_by: "lea", created_at: ago(50000) },
    { id: "p_garten", name: "Garten & Balkon", color: "#6a8fc0", archived: false, created_by: "lea", created_at: ago(40000) },
    { id: "p_lernen", name: "Spanisch lernen", color: "#c2a14d", archived: false, created_by: "max", created_at: ago(30000) },
    { id: "p_alt", name: "Umzug 2024", color: "#9a9a9a", archived: true, created_by: "max", created_at: ago(120000) },
  ];

  const timeEntries = [
    { id: uid("e"), project_id: "p_app", user_id: "max", started_at: ago(95), stopped_at: null, description: "Sync-Bug nachstellen", created_at: ago(95), updated_at: ago(95) },
    { id: uid("e"), project_id: "p_steuer", user_id: "lea", started_at: ago(1500), stopped_at: ago(1410), description: "Belege scannen", created_at: ago(1500), updated_at: ago(1410) },
    { id: uid("e"), project_id: "p_app", user_id: "max", started_at: ago(2400), stopped_at: ago(2280), description: "Notizen-Editor", created_at: ago(2400), updated_at: ago(2280) },
    { id: uid("e"), project_id: "p_lernen", user_id: "max", started_at: ago(2900), stopped_at: ago(2855), description: "Vokabeln Einheit 4", created_at: ago(2900), updated_at: ago(2855) },
    { id: uid("e"), project_id: "p_garten", user_id: "lea", started_at: ago(4400), stopped_at: ago(4280), description: "Hochbeet bepflanzt", created_at: ago(4400), updated_at: ago(4280) },
    { id: uid("e"), project_id: "p_steuer", user_id: "lea", started_at: ago(5900), stopped_at: ago(5780), description: "", created_at: ago(5900), updated_at: ago(5780) },
    // ---- last week ----
    { id: uid("e"), project_id: "p_app", user_id: "max", started_at: ago(12120), stopped_at: ago(12000), description: "Kalender-Ansicht", created_at: ago(12120), updated_at: ago(12000) },
    { id: uid("e"), project_id: "p_app", user_id: "lea", started_at: ago(13090), stopped_at: ago(13000), description: "Code-Review", created_at: ago(13090), updated_at: ago(13000) },
    { id: uid("e"), project_id: "p_steuer", user_id: "lea", started_at: ago(15150), stopped_at: ago(15000), description: "Umsatzsteuer Q1", created_at: ago(15150), updated_at: ago(15000) },
    { id: uid("e"), project_id: "p_lernen", user_id: "max", started_at: ago(16560), stopped_at: ago(16500), description: "Vokabeln Einheit 5", created_at: ago(16560), updated_at: ago(16500) },
    // ---- two weeks ago ----
    { id: uid("e"), project_id: "p_app", user_id: "max", started_at: ago(22180), stopped_at: ago(22000), description: "Onboarding-Flow", created_at: ago(22180), updated_at: ago(22000) },
    { id: uid("e"), project_id: "p_app", user_id: "lea", started_at: ago(24075), stopped_at: ago(24000), description: "Bugfixing", created_at: ago(24075), updated_at: ago(24000) },
    { id: uid("e"), project_id: "p_steuer", user_id: "lea", started_at: ago(26120), stopped_at: ago(26000), description: "Belege sortieren", created_at: ago(26120), updated_at: ago(26000) },
    { id: uid("e"), project_id: "p_garten", user_id: "lea", started_at: ago(28090), stopped_at: ago(28000), description: "Beet umgegraben", created_at: ago(28090), updated_at: ago(28000) },
    // ---- three weeks ago ----
    { id: uid("e"), project_id: "p_app", user_id: "max", started_at: ago(32150), stopped_at: ago(32000), description: "Auth-Modul", created_at: ago(32150), updated_at: ago(32000) },
    { id: uid("e"), project_id: "p_steuer", user_id: "lea", started_at: ago(35100), stopped_at: ago(35000), description: "Termin vorbereitet", created_at: ago(35100), updated_at: ago(35000) },
  ];

  const recipes = [
    {
      id: "r_pan", title: "Fluffige Buttermilch-Pancakes", description: "Sonntagsklassiker — innen weich, außen goldbraun.",
      servings: 4, prep_time_minutes: 10, cook_time_minutes: 15, category: "BREAKFAST",
      created_by: "lea", updated_at: ago(3000),
      ingredients: [
        { name: "Mehl", amount: "250", unit: "g" }, { name: "Buttermilch", amount: "300", unit: "ml" },
        { name: "Eier", amount: "2", unit: "Stk" }, { name: "Zucker", amount: "2", unit: "EL" },
        { name: "Backpulver", amount: "1", unit: "TL" }, { name: "Butter", amount: "1", unit: "Prise" },
      ],
      steps: [
        "Trockene Zutaten in einer Schüssel vermengen.",
        "Buttermilch und Eier verquirlen, zur Mehlmischung geben und nur kurz verrühren.",
        "Teig 10 Minuten ruhen lassen.",
        "In einer Pfanne bei mittlerer Hitze portionsweise goldbraun backen.",
        "Mit Ahornsirup und frischen Beeren servieren.",
      ],
    },
    {
      id: "r_carb", title: "Spaghetti Carbonara", description: "Original ohne Sahne — nur Ei, Pecorino und Pfeffer.",
      servings: 2, prep_time_minutes: 10, cook_time_minutes: 15, category: "MAIN",
      created_by: "max", updated_at: ago(6000),
      ingredients: [
        { name: "Spaghetti", amount: "250", unit: "g" }, { name: "Guanciale", amount: "120", unit: "g" },
        { name: "Eigelb", amount: "3", unit: "Stk" }, { name: "Pecorino", amount: "60", unit: "g" },
        { name: "Schwarzer Pfeffer", amount: "", unit: "nach Geschmack" },
      ],
      steps: [
        "Spaghetti in reichlich Salzwasser al dente kochen.",
        "Guanciale würfeln und in der Pfanne knusprig auslassen.",
        "Eigelb mit geriebenem Pecorino und Pfeffer verrühren.",
        "Nudeln abgießen, etwas Nudelwasser auffangen.",
        "Pfanne von der Hitze nehmen, Nudeln, Ei-Mischung und Nudelwasser zügig zu einer Creme verrühren.",
      ],
    },
    {
      id: "r_lin", title: "Herzhafte Linsensuppe", description: "Wärmt an kalten Tagen und schmeckt aufgewärmt noch besser.",
      servings: 4, prep_time_minutes: 15, cook_time_minutes: 40, category: "MAIN",
      created_by: "lea", updated_at: ago(12000),
      ingredients: [
        { name: "Tellerlinsen", amount: "250", unit: "g" }, { name: "Suppengrün", amount: "1", unit: "Bund" },
        { name: "Kartoffeln", amount: "2", unit: "Stk" }, { name: "Gemüsebrühe", amount: "1", unit: "l" },
        { name: "Essig", amount: "1", unit: "Schuss" },
      ],
      steps: [
        "Suppengrün und Kartoffeln würfeln, kurz anschwitzen.",
        "Linsen und Brühe zugeben, aufkochen.",
        "Ca. 35 Minuten köcheln, bis die Linsen weich sind.",
        "Mit Salz, Pfeffer und einem Schuss Essig abschmecken.",
      ],
    },
    {
      id: "r_kuchen", title: "Saftiger Schokoladenkuchen", description: "Einfach, schokoladig, gelingt immer.",
      servings: 12, prep_time_minutes: 20, cook_time_minutes: 35, category: "DESSERT",
      created_by: "max", updated_at: ago(20000),
      ingredients: [
        { name: "Mehl", amount: "200", unit: "g" }, { name: "Zucker", amount: "180", unit: "g" },
        { name: "Kakao", amount: "40", unit: "g" }, { name: "Eier", amount: "3", unit: "Stk" },
        { name: "Öl", amount: "120", unit: "ml" }, { name: "Milch", amount: "150", unit: "ml" },
      ],
      steps: [
        "Ofen auf 175 °C vorheizen, Form fetten.",
        "Trockene und feuchte Zutaten getrennt verrühren, dann zusammenführen.",
        "In die Form geben und ca. 35 Minuten backen (Stäbchenprobe).",
        "Auskühlen lassen und nach Wunsch mit Puderzucker bestäuben.",
      ],
    },
    {
      id: "r_balls", title: "Dattel-Energy-Balls", description: "Schneller Snack ohne Backen.",
      servings: 10, prep_time_minutes: 15, cook_time_minutes: 0, category: "SNACK",
      created_by: "lea", updated_at: ago(25000),
      ingredients: [
        { name: "Datteln", amount: "150", unit: "g" }, { name: "Haferflocken", amount: "100", unit: "g" },
        { name: "Mandelmus", amount: "2", unit: "EL" }, { name: "Kakao", amount: "1", unit: "EL" },
      ],
      steps: [
        "Datteln einweichen, dann alles fein pürieren.",
        "Zu kleinen Kugeln rollen.",
        "Mindestens 30 Minuten kühlen.",
      ],
    },
    {
      id: "r_tea", title: "Pfirsich-Eistee", description: "Erfrischend für warme Nachmittage.",
      servings: 4, prep_time_minutes: 5, cook_time_minutes: 0, category: "DRINK",
      created_by: "max", updated_at: ago(30000),
      ingredients: [
        { name: "Schwarztee", amount: "3", unit: "Beutel" }, { name: "Pfirsich", amount: "2", unit: "Stk" },
        { name: "Zitrone", amount: "1", unit: "Stk" }, { name: "Eiswürfel", amount: "", unit: "reichlich" },
      ],
      steps: [
        "Tee aufgießen und abkühlen lassen.",
        "Pfirsich pürieren, mit Tee und Zitronensaft mischen.",
        "Über Eis servieren.",
      ],
    },
  ];

  window.HB = {
    DAY, today, iso, ago, users,
    recipeCategories: {
      BREAKFAST: "Frühstück", MAIN: "Hauptgerichte",
      SNACK: "Snack", DESSERT: "Dessert", DRINK: "Getränk",
    },
    shopCategories,
    seed: { todos, todoLists, shopping, shoppingLists, notes, projects, timeEntries, recipes },
  };
})();
