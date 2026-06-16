// German UI string catalog. Single source of truth for all user-facing text
// in the web app. To add another language later, create a sibling file (e.g.
// `en.ts`) with the same shape and wire it up in `index.ts` — see the note there.
export const de = {
  common: {
    loading: 'Lädt…',
    cancel: 'Abbrechen',
    confirm: 'Bestätigen',
    add: 'Hinzufügen',
    save: 'Speichern',
    delete: 'Löschen',
    edit: 'Bearbeiten',
    logout: 'Abmelden',
    close: 'Schließen',
    by: 'von', // rendered as "von {name}"
    titlePlaceholder: 'Titel…',
    descriptionOptional: 'Beschreibung (optional)…',
    networkError: 'Keine Verbindung – bitte später erneut versuchen.',
  },
  // HB-07 — locale-aware date/relative-time wording used by ui/format.ts.
  fmt: {
    today: 'Heute',
    tomorrow: 'Morgen',
    yesterday: 'Gestern',
    yesterdayRel: 'gestern', // lowercase relative-time form ("… / gestern")
    dayBeforeYesterday: 'Vorgestern',
    overdueDays: '{n} Tage überfällig',
    inDays: 'In {n} Tagen',
    justNow: 'gerade eben',
    minAgo: 'vor {n} Min.',
    hrsAgo: 'vor {n} Std.',
    daysAgo: 'vor {n} Tagen',
    weeksAgo: 'vor {n} Wo.',
    durMin: '{m} Min',
    durHourMin: '{h} Std {m} Min',
    thisWeek: 'Diese Woche',
    lastWeek: 'Letzte Woche',
  },
  // Backend ErrorResponse codes → German text. Shared across views so write
  // failures read consistently; falls back to a per-action default when a code
  // is missing/unknown (see i18n `errorText` and issue #84).
  errors: {
    PROJECT_ARCHIVED: 'Das Projekt ist archiviert.',
    INVALID_RANGE: 'Das Ende muss nach dem Start liegen.',
    INVALID_DATE: 'Ungültiges Datum.',
    INVALID_ID: 'Ungültige Auswahl.',
    INVALID_PROJECT: 'Projektname darf nicht leer sein.',
    INVALID_COLOR: 'Ungültige Farbe.',
    DEFAULT_REQUIRED: 'Solange Wochenstunden gesetzt sind, braucht es ein Standard-Projekt.',
    ENTRY_RUNNING: 'Laufende Timer können nicht gesplittet werden — erst stoppen.',
    NOT_FOUND: 'Nicht gefunden – bitte neu laden.',
    NO_RUNNING_TIMER: 'Es läuft gerade kein Timer.',
    BAD_REQUEST: 'Ungültige Anfrage.',
    INTERNAL_ERROR: 'Serverfehler – bitte später erneut versuchen.',
    FORBIDDEN: 'Dazu fehlt dir die Berechtigung.',
    MISSING_PARAM: 'Pflichtangabe fehlt.',
    INVALID_NAME: 'Name darf nicht leer sein (max. 60 Zeichen).',
    INVALID_PASSWORD: 'Aktuelles Passwort stimmt nicht.',
    WEAK_PASSWORD: 'Neues Passwort braucht mindestens 8 Zeichen.',
    PASSWORD_UNCHANGED: 'Neues Passwort muss sich vom alten unterscheiden.',
    INVALID_TIME: 'Ungültige Uhrzeit (HH:mm).',
    // Todos / Lists
    INVALID_TODO: 'Aufgabe unvollständig – Titel oder Zuständige:r/Fälligkeit angeben.',
    INVALID_STATUS: 'Ungültiger Status.',
    INVALID_PRIORITY: 'Ungültige Priorität.',
    INVALID_DUE_DATE: 'Ungültiges Fälligkeitsdatum.',
    INVALID_RECURRENCE: 'Ungültige Wiederholung – für eine Wiederholung ein Fälligkeitsdatum angeben.',
    INVALID_SUBTASK: 'Titel der Unteraufgabe darf nicht leer sein.',
    INVALID_LIST: 'Listenname darf nicht leer sein.',
    INVALID_VISIBILITY: 'Ungültige Sichtbarkeit.',
    // Shopping
    INVALID_SHOPPING_ITEM: 'Name darf nicht leer sein.',
    INVALID_TEMPLATE: 'Vorlagenname darf nicht leer sein.',
    // Notes
    INVALID_NOTE: 'Titel darf nicht leer sein.',
    VISIBILITY_FORBIDDEN: 'Nur der Ersteller darf die Sichtbarkeit ändern.',
    IMAGE_TOO_LARGE: 'Bild ist zu groß.',
    UNSUPPORTED_TYPE: 'Nur JPEG, PNG, WebP oder GIF erlaubt.',
    EMPTY_IMAGE: 'Das hochgeladene Bild war leer.',
    NO_IMAGE: 'Keine Bilddatei in der Anfrage.',
    // Recipes
    INVALID_RECIPE: 'Rezeptangaben ungültig – Titel, Portionen und Zeiten prüfen.',
    INVALID_INGREDIENT: 'Zutatenmenge muss ≥ 0 sein.',
    INVALID_CATEGORY: 'Unbekannte Kategorie.',
    // Abwesenheit
    INVALID_TYPE: 'Ungültige Art.',
    INVALID_HALF: 'Ungültige Tageshälfte.',
    INVALID_WEEKDAY: 'Ungültiger Wochentag.',
    INVALID_STATE: 'Ungültiges Bundesland.',
    INVALID_YEAR: 'Ungültiges Jahr.',
    // Gilt für beide Editoren, die ein Datum eindeutig belegen (Kita-Schließtag PUT,
    // eigener Feiertag PUT) — daher bewusst neutral formuliert, nicht Kita-spezifisch (#254).
    DATE_CONFLICT: 'Für dieses Datum gibt es schon einen Eintrag.',
    RANGE_TOO_LARGE: 'Der Zeitraum ist zu lang.',
    TOO_MANY_DATES: 'Zu viele Tage im Zeitraum.',
  } as Record<string, string>,
  shell: {
    brandSub: 'Mäxchen', // default household label; overridable in settings
    syncActive: 'Echtzeit-Sync aktiv',
    timerRunning: 'Timer läuft',
    logoutTitle: 'Abmelden?',
    logoutBody: 'Du wirst abgemeldet und musst dich danach erneut anmelden.',
  },
  nav: {
    dashboard: 'Dashboard',
    todos: 'Aufgaben',
    shopping: 'Einkaufsliste',
    notes: 'Notizen',
    time: 'Zeiterfassung',
    recipes: 'Rezepte',
    wochenplan: 'Wochenplan',
    abwesenheit: 'Kalender',
    settings: 'Einstellungen',
    more: 'Mehr', // bottom-tab "Mehr" overflow sheet (HB-09)
    main: 'Hauptnavigation', // aria-label for the main (bottom/side) nav landmark
    // Short labels for the mobile bottom tab bar (7 items must fit on a 360px phone).
    short: {
      dashboard: 'Start',
      todos: 'Aufgaben',
      shopping: 'Einkauf',
      notes: 'Notizen',
      time: 'Zeit',
      recipes: 'Rezepte',
      wochenplan: 'Plan',
      abwesenheit: 'Kalender',
      more: 'Mehr',
    },
  },
  login: {
    title: 'HomeBase',
    subtitle: 'Familien-Hub',
    username: 'Benutzername',
    password: 'Passwort',
    submit: 'Anmelden',
    failed: 'Login fehlgeschlagen',
  },
  // HB-03 — global search / command palette (⌘K)
  palette: {
    title: 'Suche',
    open: 'Suchen',
    placeholder: 'Suchen oder springen zu …',
    actions: 'Aktionen',
    groupTodos: 'Aufgaben',
    groupNotes: 'Notizen',
    groupRecipes: 'Rezepte',
    groupProjects: 'Projekte',
    groupShopping: 'Einkauf',
    noResults: 'Keine Treffer',
    footNavigate: 'Navigieren',
    footOpen: 'Öffnen',
  },
  dashboard: {
    headerTitle: 'HomeBase — Heute',
    // greeting head — thresholds mirror the mock (views_heute.jsx)
    greetingNight: 'Gute Nacht',
    greetingMorning: 'Guten Morgen',
    greetingDay: 'Hallo',
    greetingEvening: 'Guten Abend',
    // quick-add (lands in the Inbox — no list)
    quickAddPlaceholder: 'Schnell erfassen – landet in der Inbox …',
    add: 'Hinzufügen',
    addFailed: 'Aufgabe konnte nicht hinzugefügt werden.',
    saveFailed: 'Änderung konnte nicht gespeichert werden.',
    // stat tiles
    statDueToday: 'Heute fällig',
    statInbox: 'In der Inbox',
    statDueTomorrow: 'Morgen fällig',
    statDoneToday: 'Heute erledigt',
    // "Heute dran" card
    todayTitle: 'Heute dran',
    allTasks: 'Alle Aufgaben',
    todayEmpty: 'Für heute nichts geplant',
    todayEmptyHint: 'Genieß den Tag — oder leere die Inbox.',
    // time card
    timeTitle: 'Zeiterfassung',
    open: 'Öffnen',
    timerRunningHint: 'Läuft …',
    expectedEndShort: 'bis ca. {time}', // forecast suffix at the running timer (#31)
    targetReachedShort: 'Soll erreicht',
    stop: 'Stoppen',
    noTimer: 'Kein Timer läuft',
    noTimerHint: 'Starte einen Timer in der Zeiterfassung.',
    // shopping peek card
    shoppingTitle: 'Einkaufsliste',
    shoppingEmpty: 'Alles eingekauft',
    moreItems: 'weitere', // rendered as "+ {n} weitere"
    // digest preview card
    digestTitle: 'Abend-Digest',
    digestBadge: 'Vorschau', // static badge; the peek doesn't fetch the configured time (/config/digest)
    digestSub: 'Vorschau der Telegram-Nachricht, die ihr beide bekommt.',
    digestDone: 'Heute erledigt',
    digestInbox: 'Neu in der Inbox',
    digestTomorrow: 'Morgen fällig',
    // HB-01 presence strip ("Wer ist da?")
    presenceTitle: 'Wer ist da?',
    presenceOpen: 'Kalender',
    presenceWeek: 'Diese Woche',
    presenceKita: 'Kita zu',
    // HB-10 recurring tasks + weekly work-target peek
    recurring: 'Wiederkehrend',
    worktargetTitle: 'Wochensoll',
    worktargetHours: 'Std', // unit after the weekly target hours
    worktargetTodayReached: 'Heute-Ziel erreicht',
    worktargetTodayLeft: 'Heute noch {time}',
  },
  todos: {
    headerTitle: 'HomeBase — Aufgaben',
    title: 'Aufgaben',
    eyebrow: 'Gemeinsam · Echtzeit',
    open: 'offen', // rendered as "{n} offen"
    plan: 'Planen',
    markDone: 'Erledigt',
    planTitle: 'Aufgabe planen',
    planHint: 'Mindestens Zuständige:r oder Fälligkeit angeben.',
    planList: 'Liste', // list picker in the plan modal (only for inbox todos, issue #69)
    planListInbox: 'Bleibt in der Inbox', // empty option of the plan-modal list picker
    assignee: 'Zuständig',
    assigneeNone: 'Niemand',
    dueDate: 'Fällig am',
    priority: 'Priorität',
    priorityNone: '—',
    // Recurrence
    recurrence: 'Wiederholung',
    recurrenceNone: 'Keine',
    recurrenceNeedsDue: 'Für eine Wiederholung ein Fälligkeitsdatum angeben.',
    recurrenceEvery: 'Alle', // rendered as "Alle {n} Wochen"
    recurrenceDaily: 'Täglich',
    recurrenceWeekly: 'Wöchentlich',
    recurrenceMonthly: 'Monatlich',
    recurUnitDay: 'Tage',
    recurUnitWeek: 'Wochen',
    recurUnitMonth: 'Monate',
    // compact badge on a recurring todo row
    recurBadgeDaily: 'täglich',
    recurBadgeWeekly: 'wöchentl.',
    recurBadgeMonthly: 'monatl.',
    recurBadgeEvery: 'alle', // "alle {n} {unit}"
    // Lists (tabs)
    newList: 'Neue Liste',
    newListTitle: 'Neue Liste',
    listName: 'Name',
    listNamePlaceholder: 'z. B. Renovierung',
    createList: 'Erstellen',
    visibility: 'Sichtbarkeit',
    visShared: 'Geteilt',
    visPrivate: 'Privat',
    visSharedHint: 'Beide sehen und bearbeiten diese Liste.',
    visPrivateHint: 'Nur du siehst diese Liste.',
    editList: 'Liste bearbeiten', // link label, rendered as `Liste bearbeiten „{name}"`
    editListTitle: 'Liste bearbeiten', // edit-modal title
    saveList: 'Speichern', // save button in the edit modal
    deleteList: 'Liste löschen', // link label, rendered as `Liste „{name}" löschen`
    deleteListTitle: 'Liste löschen?', // confirm-modal title
    deleteListConfirm: 'Endgültig löschen', // danger button in the confirm modal
    deleteListWarn: 'Das kann nicht rückgängig gemacht werden.', // shown when the list has todos
    taskOne: 'Aufgabe', // count noun, e.g. „1 Aufgabe"
    taskMany: 'Aufgaben', // count noun, e.g. „3 Aufgaben"
    quickAddPlaceholder: 'Neue Aufgabe …', // rendered as `Neue Aufgabe in „{name}" …`
    addTask: 'Erfassen',
    allDone: 'Alles erledigt',
    allDoneHint: 'Keine offenen Aufgaben in dieser Liste.',
    doneSection: 'Erledigt',
    // due buckets
    bucketOver: 'Überfällig',
    bucketToday: 'Heute',
    bucketSoon: 'Demnächst',
    bucketFar: 'Später',
    bucketNone: 'Ohne Datum',
    // Smart-/listenübergreifende Tabs (#255/#256) — von den Dashboard-Kacheln verlinkt
    tabAll: 'Alle',
    tabToday: 'Heute',
    tabTomorrow: 'Morgen',
    tabDone: 'Erledigt',
    allEmpty: 'Noch keine Aufgaben',
    allEmptyHint: 'Lege Aufgaben in einer Liste oder der Inbox an.',
    todayEmpty: 'Heute nichts fällig',
    todayEmptyHint: 'Keine offenen Aufgaben mit Fälligkeit heute.',
    tomorrowEmpty: 'Morgen nichts fällig',
    tomorrowEmptyHint: 'Keine offenen Aufgaben mit Fälligkeit morgen.',
    doneViewEmpty: 'Zuletzt nichts erledigt',
    doneViewEmptyHint: 'Aufgaben der letzten {n} Tage erscheinen hier.',
    doneWindowNote: 'Letzte {n} Tage', // Hinweis über der Erledigt-Liste (#263)
    // Subtasks
    subtasks: 'Unteraufgaben',
    addSubtask: 'Unteraufgabe hinzufügen …',
    // write-error fallbacks (issue #96)
    addFailed: 'Aufgabe konnte nicht hinzugefügt werden.',
    saveFailed: 'Änderung konnte nicht gespeichert werden.',
    deleteFailed: 'Aufgabe konnte nicht gelöscht werden.',
    subAddFailed: 'Unteraufgabe konnte nicht hinzugefügt werden.',
    subSaveFailed: 'Unteraufgabe konnte nicht gespeichert werden.',
    subDeleteFailed: 'Unteraufgabe konnte nicht gelöscht werden.',
    listCreateFailed: 'Liste konnte nicht erstellt werden.',
    listSaveFailed: 'Liste konnte nicht gespeichert werden.',
    listDeleteFailed: 'Liste konnte nicht gelöscht werden.',
  },
  shopping: {
    headerTitle: 'HomeBase — Einkaufslisten',
    title: 'Einkaufslisten',
    open: 'offen', // rendered as "{n} offen"
    listOne: 'Liste',
    listMany: 'Listen',
    newList: 'Neue Liste',
    newListTitle: 'Neue Liste',
    listName: 'Name',
    listNamePlaceholder: 'z. B. Wocheneinkauf',
    createList: 'Erstellen',
    deleteList: 'Liste löschen', // rendered as `Liste „{name}" löschen`
    deleteListTitle: 'Liste löschen?', // confirm-modal title
    deleteListConfirm: 'Liste und alle Einträge löschen?', // modal body (legacy, no longer used in body)
    deleteListBtn: 'Endgültig löschen', // danger button in the confirm modal
    deleteListBody: 'Die Liste „{name}" und alle Einträge darin werden gelöscht.', // confirm-modal body, {name} = Listenname
    deleteListWarn: 'Das kann nicht rückgängig gemacht werden.', // shown in delete-list modal body
    noLists: 'Noch keine Liste',
    noListsHint: 'Lege oben deine erste Einkaufsliste an.',
    emptyTitle: 'Liste ist leer',
    emptyHint: 'Füge oben das erste Produkt hinzu.',
    allChecked: 'Alles abgehakt 🎉',
    namePlaceholder: 'Was fehlt in „{name}"? …', // quick-add placeholder, {name} = aktive Liste
    inCart: 'Im Wagen', // rendered as "Im Wagen · {n}"
    clearChecked: 'Abgehakte entfernen',
    // Offline-Sync: ein Abhaken ohne Verbindung wird lokal gemerkt und automatisch
    // nachgeholt; bis dahin trägt das Produkt den notSynced-Marker (Einkaufen ohne WLAN).
    notSynced: 'Noch nicht synchronisiert',
    offlineQueuedOne: '1 Änderung wird nachgeholt, sobald wieder online.',
    offlineQueuedMany: '{n} Änderungen werden nachgeholt, sobald wieder online.',
    retryNow: 'Jetzt versuchen',
    // write-error fallbacks (issue #96)
    addFailed: 'Produkt konnte nicht hinzugefügt werden.',
    deleteFailed: 'Produkt konnte nicht gelöscht werden.',
    clearFailed: 'Abgehakte konnten nicht entfernt werden.',
    listCreateFailed: 'Liste konnte nicht erstellt werden.',
    listDeleteFailed: 'Liste konnte nicht gelöscht werden.',
    // Benannte Standard-/Vorlagen-Listen (#215): gespeicherte Item-Namen für den
    // wiederkehrenden Wocheneinkauf, die per Auswahl auf eine echte Liste übernommen werden.
    templates: {
      open: 'Vorlagen', // Button-Beschriftung am Listenbereich
      manageTitle: 'Vorlagen', // Verwaltungs-Sheet-Titel
      manageHint: 'Speichere wiederkehrende Einkäufe als Vorlage und füge sie mit einem Klick zur Liste hinzu.',
      empty: 'Noch keine Vorlagen',
      emptyHint: 'Lege deine erste Standard-Liste an.',
      itemCount: 'Produkte', // gerendert als "{n} Produkte"
      itemCountOne: 'Produkt', // gerendert als "1 Produkt"
      newTemplate: 'Neue Vorlage',
      editTemplate: 'Vorlage bearbeiten',
      nameLabel: 'Name',
      namePlaceholder: 'z. B. Wocheneinkauf',
      items: 'Produkte',
      itemPlaceholder: 'Produkt …',
      addItem: '+ Produkt',
      removeItem: 'Produkt entfernen',
      noItemsYet: 'Noch keine Produkte – oben hinzufügen.',
      create: 'Erstellen',
      apply: 'Zur Liste hinzufügen',
      applyTitle: 'Vorlage zur Liste hinzufügen',
      applyToList: 'Liste',
      applyNoList: 'Lege zuerst eine Einkaufsliste an.',
      selected: 'ausgewählt', // gerendert als "{n} von {total} ausgewählt"
      all: 'Alle',
      none: 'Keine',
      applyAdd: 'hinzufügen', // gerendert als "{n} hinzufügen"
      deleteTitle: 'Vorlage löschen?',
      deleteConfirm: 'Die Vorlage „{name}" wird gelöscht. Das kann nicht rückgängig gemacht werden.',
      deleteBtn: 'Endgültig löschen',
      // Schreibfehler-Fallbacks
      saveFailed: 'Vorlage konnte nicht gespeichert werden.',
      deleteFailed: 'Vorlage konnte nicht gelöscht werden.',
      applyFailed: 'Vorlage konnte nicht zur Liste hinzugefügt werden.',
      loadFailed: 'Vorlagen konnten nicht geladen werden.',
      added: 'hinzugefügt', // Toast: "{n} hinzugefügt"
      merged: 'zusammengeführt', // Toast: "{n} zusammengeführt"
      nothingToAdd: 'Keine neuen Produkte',
    },
  },
  notes: {
    headerTitle: 'HomeBase — Notizen',
    title: 'Notizen',
    count: 'Notizen', // rendered as "{n} Notizen"
    searchPlaceholder: 'Suchen …',
    allTags: 'Alle',
    allFolders: 'Alle Ordner',
    noFolder: 'Ohne Ordner',
    noResults: 'Keine Treffer',
    empty: 'Noch keine Notizen',
    emptyHint: 'Erstelle eine Notiz',
    selectHint: 'Wähle links eine Notiz oder erstelle eine neue.',
    newNote: 'Neue Notiz',
    editNote: 'Notiz bearbeiten',
    contentPlaceholder: 'Inhalt (Markdown)…',
    tagsPlaceholder: 'Tags (kommagetrennt)…',
    folderLabel: 'Ordner',
    folderPlaceholder: 'Ordner (optional)…',
    visibility: 'Sichtbarkeit:',
    private: 'Privat',
    shared: 'Geteilt',
    images: 'Bilder',
    addImage: 'Bild hinzufügen',
    removeImage: 'Bild entfernen',
    insertImage: 'In Text einfügen',
    insertImageLabel: 'Bild in den Text einfügen',
    uploading: 'Wird hochgeladen…',
    // shown while several selected images upload one after another ({done}/{total})
    uploadingMany: 'Lade {done}/{total} hoch…',
    imageUploadingInline: 'Bild wird hochgeladen…',
    // shown when trying to paste/drop an image into a not-yet-saved draft
    imageSaveFirst: 'Notiz zuerst speichern, dann Bilder einfügen.',
    imageTooLarge: 'Bild ist zu groß (max. 10 MB).',
    imageBadType: 'Nur JPEG, PNG, WebP oder GIF erlaubt.',
    imageUploadFailed: 'Upload fehlgeschlagen.',
    // shown when some images of a multi-select upload failed ({count} of them)
    imagesSomeFailed: '{count} Bild(er) konnten nicht hochgeladen werden.',
    // write-error fallbacks (issue #96)
    saveFailed: 'Notiz konnte nicht gespeichert werden.',
    deleteFailed: 'Notiz konnte nicht gelöscht werden.',
    imageDeleteFailed: 'Bild konnte nicht gelöscht werden.',
  },
  // Inbox tab in the todos view: all todos without a list (issue #69) —
  // Dashboard quick-add and the Android FAB create these.
  inbox: {
    headerTitle: 'HomeBase — Inbox',
    tab: 'Inbox', // tab label in the todos view
    empty: 'Inbox ist leer',
    emptyHint: 'Füge eine Aufgabe hinzu',
    quickAddPlaceholder: 'Neue Aufgabe in der Inbox …',
  },
  time: {
    headerTitle: 'HomeBase — Zeit',
    title: 'Zeiterfassung',
    running: 'Timer läuft',
    subDay: 'Tag',
    subWeek: 'Woche',
    subProjects: 'Projekte',
    recordEntry: 'Eintrag erfassen',
    startTimer: 'Timer starten',
    noTimer: 'Kein Timer aktiv',
    startPrompt: 'Womit startest du?',
    descPlaceholder: 'Woran arbeitest du? …',
    stop: 'Stoppen',
    start: 'Start',
    project: 'Projekt',
    projectsLabel: 'Projekte',
    recentEntries: 'Letzte Einträge',
    noDescription: 'ohne Beschreibung',
    viewDetails: 'Details ansehen',
    open: 'Öffnen', // labelled "open detail" button on the project card (#220)
    backToOverview: 'Zurück',
    detailTotal: 'Gesamt',
    detailEntries: 'Einträge',
    detailAvg: 'ø pro Eintrag',
    perWeek: 'Pro Woche',
    allEntries: 'Alle Einträge',
    detailEmptyHint: 'Starte den Timer für dieses Projekt.',
    entryOne: 'Eintrag',
    entryMany: 'Einträge',
    noProjects: 'Noch keine Projekte',
    noProjectsHint: 'Lege oben rechts ein Projekt an',
    noProjectsConfigHint: 'Lege ein erstes Projekt an, um die Zeit zu erfassen', // shown on the main view's empty state (#86)
    firstProject: 'Erstes Projekt anlegen', // bootstrap action on the main view when there are no projects yet (#86)
    noEntries: 'Noch keine Einträge',
    partnerIdle: 'Kein Timer aktiv',
    startForPartner: 'Für {name}', // start a timer on the partner's behalf
    // Cross-person actions always confirm first — via custom ConfirmDialog, not
    // window.confirm() (#125/#129). Both users may manage each other's entries.
    partnerActionTitle: 'Aktion bestätigen',
    confirmStartForPartner: 'Timer für {name} starten?',
    confirmStopPartner: 'Timer von {name} stoppen?',
    confirmEditPartner: 'Eintrag von {name} bearbeiten?',
    confirmSplitPartner: 'Eintrag von {name} splitten?',
    confirmDeletePartner: 'Eintrag von {name} löschen?',
    confirmCreateForPartner: 'Eintrag für {name} erfassen?',
    personLabel: 'Person', // manual-entry sheet: who the entry is recorded for
    emptyTitle: 'Noch keine Zeiteinträge',
    emptyHint: 'Starte einen Timer oder erfasse einen Eintrag',
    today: 'Heute',
    yesterday: 'Gestern',
    thisWeek: 'Diese Woche',
    legend: 'Legende',
    weekdays: ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'],
    newProject: 'Neues Projekt',
    editProject: 'Projekt bearbeiten',
    projectNamePlaceholder: 'Projektname…',
    color: 'Farbe',
    colorLabel: 'Farbe', // rendered as "Farbe {hex}"
    create: 'Anlegen',
    active: 'Aktiv',
    noActiveProjects: 'Keine aktiven Projekte',
    archive: 'Archivieren',
    showArchived: 'Archivierte anzeigen',
    hideArchived: 'Archivierte ausblenden',
    archivedSection: 'Archiviert',
    reactivate: 'Reaktivieren',
    endAfterStart: 'Ende muss nach dem Start liegen',
    editEntry: 'Eintrag bearbeiten',
    editRunning: 'Laufenden Timer bearbeiten',
    editRunningHint: 'Läuft noch – die Stoppzeit wird erst beim Stoppen gesetzt.',
    startInFuture: 'Beginn darf nicht in der Zukunft liegen.',
    startLabel: 'Beginn',
    endLabel: 'Ende',
    date: 'Datum',
    from: 'Von',
    to: 'Bis',
    exportCsv: 'CSV-Export', // card title in Einstellungen → Zeiterfassung (#99)
    exportTitle: 'Als CSV exportieren',
    exportHint: 'Optional auf Zeitraum und Projekt eingrenzen. Leer lassen exportiert alle abgeschlossenen Einträge.',
    exportAllProjects: 'Alle Projekte',
    exportSubmit: 'Exportieren',
    startFailed: 'Timer konnte nicht gestartet werden',
    stopFailed: 'Timer konnte nicht gestoppt werden',
    saveFailed: 'Konnte nicht gespeichert werden',
    deleteFailed: 'Eintrag konnte nicht gelöscht werden',
    archiveFailed: 'Projekt konnte nicht aktualisiert werden',
    // Wochensoll & Forecast (#31)
    expectedEnd: 'Voraussichtlich fertig um {time}', // shown at the running timer
    targetReached: 'Tagessoll erreicht',
    weekTargetTitle: 'Wochensoll',
    targetsModalTitle: 'Wochensoll konfigurieren',
    targetsModalHint: 'Wochenstunden pro Person und Projekt. Urlaub, Krankheit und Feiertage werden dem Standard-Projekt gutgeschrieben.',
    hoursPerWeek: 'Std/Woche',
    defaultColumn: 'Standard',
    defaultRequired: 'Bitte ein Standard-Projekt wählen',
    invalidHours: 'Stunden müssen zwischen 0 und 168 liegen',
    targetsFailed: 'Wochensoll konnte nicht gespeichert werden',
    weekLeft: 'noch {time}', // remaining hours toward the weekly target
    weekOver: '+{time}', // weekly target exceeded
    todayLeft: 'Heute noch {time}',
    todayOver: 'Heute {time} über Soll',
    credited: 'gutgeschrieben', // rendered as "{time} gutgeschrieben"
    // Eintrag splitten (#62)
    split: 'Splitten',
    splitTitle: 'Eintrag splitten',
    splitHint: 'Teilt den Eintrag an der Trennzeit in zwei. Eine Pause bleibt als Lücke zwischen den Teilen unerfasst — danach lässt sich Teil 2 wie gewohnt bearbeiten (z. B. anderes Projekt).',
    splitAtLabel: 'Trennzeit',
    breakLabel: 'Pause in Minuten (optional)',
    splitPart1: 'Teil 1:',
    splitPart2: 'Teil 2:',
    splitInvalidCut: 'Die Trennzeit muss zwischen Start und Ende liegen',
    splitInvalidBreak: 'Pause in Minuten angeben (z. B. 30)',
    splitBreakTooLong: 'Die Pause muss vor dem Ende des Eintrags enden',
    splitFailed: 'Eintrag konnte nicht gesplittet werden',
  },
  // Zentrale Einstellungen (#99): Sammelort für selten geänderte Konfiguration,
  // nach Unterseiten getrennt. Reine Frontend-Verlagerung bestehender Configs.
  settings: {
    title: 'Einstellungen',
    // Haushalt-Unterseite (#100): editierbarer Haushaltsname.
    household: 'Haushalt',
    householdNameTitle: 'Haushaltsname',
    householdNameHint: 'Wird in der Seitenleiste angezeigt. Beide können ihn ändern.',
    householdNameLabel: 'Name',
    householdNameRequired: 'Bitte einen Namen angeben.',
    householdSaved: 'Gespeichert',
    householdSaveFailed: 'Name konnte nicht gespeichert werden.',
    // Mitglieder-Übersicht (#100): read-only Liste der Haushaltsmitglieder.
    householdMembersTitle: 'Mitglieder',
    householdMembersHint: 'Alle Personen in diesem Haushalt.',
    // Konto-Unterseite (#100): Darstellung (Theme) + eigenes Passwort ändern.
    account: 'Konto',
    accountSignedInAs: 'Angemeldet als',
    // Sprache (#6): pro Browser gewählte UI-Sprache (localStorage), sofort wirksam.
    languageTitle: 'Sprache',
    languageHint: 'Sprache der Oberfläche. Gilt auf diesem Gerät, sofort wirksam.',
    languageLabel: 'Anzeigesprache',
    languageGerman: 'Deutsch',
    languageEnglish: 'Englisch',
    languageSystem: 'System', // follow the browser language
    // Darstellung / Theme (#100): pro Person gespeichert (user_prefs), gilt app-weit.
    themeTitle: 'Darstellung',
    themeHint: 'Hell, dunkel oder dem System folgen. Nur für dich, sofort wirksam.',
    themeLabel: 'Erscheinungsbild',
    themeLight: 'Hell',
    themeDark: 'Dunkel',
    themeSystem: 'System',
    themeSaveFailed: 'Einstellung konnte nicht gespeichert werden.',
    // Avatar-Farbe (Teil von #100): pro Person gewählte Farbe, haushaltsweit sichtbar
    // (der Partner sieht sie). Null/„Automatisch" = aus dem Benutzernamen abgeleitet.
    avatarTitle: 'Avatar-Farbe',
    avatarHint: 'Deine Farbe für Avatare – auch für deinen Partner sichtbar. Sofort wirksam.',
    avatarLabel: 'Farbe',
    avatarAuto: 'Automatisch',
    avatarAutoHint: 'Aus deinem Namen abgeleitet',
    avatarSaveFailed: 'Farbe konnte nicht gespeichert werden.',
    passwordTitle: 'Passwort ändern',
    passwordHint: 'Zum Ändern zuerst dein aktuelles Passwort eingeben.',
    passwordCurrent: 'Aktuelles Passwort',
    passwordNew: 'Neues Passwort',
    passwordConfirm: 'Neues Passwort wiederholen',
    passwordChange: 'Passwort ändern',
    passwordChanged: 'Passwort geändert',
    passwordChangeFailed: 'Passwort konnte nicht geändert werden.',
    passwordMismatch: 'Die neuen Passwörter stimmen nicht überein.',
    passwordTooShort: 'Mindestens 8 Zeichen.',
    passwordSameAsOld: 'Neues Passwort muss sich vom alten unterscheiden.',
    // Benachrichtigungen-Unterseite (#100): Telegram-Digest-Zeiten (morgens + abends).
    notifications: 'Benachrichtigungen',
    // Morgen-Briefing („Guten Morgen"): heute fällig, überfällig, Inbox, Abwesenheiten, Kita.
    morningDigestTitle: 'Morgen-Digest',
    morningDigestHint: 'Morgendliche Übersicht: heute fällig, überfällig, Inbox, Abwesenheiten und Kita-Schließtage.',
    // Abend-Recap. Label/Speichern-/Hinweis-Texte teilen sich beide Digest-Karten.
    digestTitle: 'Abend-Digest',
    digestHint: 'Tägliche Zusammenfassung (heute erledigt, neue Inbox, morgen fällig).',
    digestTimeLabel: 'Uhrzeit',
    digestSaved: 'Gespeichert',
    digestSaveFailed: 'Uhrzeit konnte nicht gespeichert werden.',
    digestApplies: 'Änderungen greifen ab dem nächsten geplanten Digest.',
    digestDisabled: 'Telegram ist nicht konfiguriert — der Digest ist derzeit inaktiv. Einstellungen kannst du trotzdem setzen.',
    // Pro-Digest an/aus + Inhalts-Auswahl (#182). Beide Digests teilen sich diese Texte.
    digestEnabledLabel: 'Digest aktiv',
    digestSectionsLabel: 'Inhalte',
    digestSectionsHint: 'Welche Abschnitte dieser Digest zeigt.',
    digestSaveSections: 'Inhalte konnten nicht gespeichert werden.',
    // Abschnitts-Labels, indexiert über die Section-IDs vom Backend (availableSections).
    digestSections: {
      evening_done_today: 'Heute erledigt',
      evening_new_inbox: 'Neu in der Inbox',
      evening_due_tomorrow: 'Morgen fällig',
      evening_absent_tomorrow: 'Morgen abwesend (Vorschau)',
      evening_kita_tomorrow: 'Kita morgen geschlossen (Vorschau)',
      morning_due_today: 'Heute fällig',
      morning_overdue: 'Überfällig',
      morning_inbox: 'Inbox',
      morning_absent: 'Heute abwesend',
      morning_kita: 'Kita geschlossen',
    } as Record<string, string>,
    // Wiederholungs-Planer-Uhrzeit (#100): tägliche Laufzeit des Sicherheitsnetzes für
    // wiederkehrende Aufgaben (rollt verpasste offene Wiederholungen vor).
    recurringTitle: 'Wiederholungs-Planer',
    recurringHint: 'Tägliches Sicherheitsnetz: rollt verpasste, noch offene wiederkehrende Aufgaben auf die aktuelle Periode vor.',
    recurringTimeLabel: 'Uhrzeit für wiederkehrende Aufgaben',
    recurringSaved: 'Gespeichert',
    recurringSaveFailed: 'Uhrzeit konnte nicht gespeichert werden.',
    recurringApplies: 'Änderungen greifen ab dem nächsten geplanten Lauf.',
    // Abwesenheit-Unterseite (#99): Kalender-Konfiguration im Hub.
    absence: 'Abwesenheit',
    absenceTitle: 'Kontingente & Kalender',
    absenceHint: 'Pro Person Urlaubskontingent, Übertrag, Bundesland und Teilzeit; dazu haushaltsweite Schließ- und Feiertage. Kontingent und Übertrag gelten pro Jahr.',
    time: 'Zeiterfassung',
    projectsTitle: 'Projekte',
    projectsHint: 'Projekte anlegen, umbenennen, einfärben oder archivieren.',
    wochensollEdit: 'Wochensoll bearbeiten',
    wochensollEmpty: 'Noch kein Wochensoll festgelegt.',
    exportOpen: 'CSV herunterladen', // opens the export filter dialog
    perWeek: 'Std/Woche', // rendered as "{n} Std/Woche"
    defaultBadge: 'Standard',
  },
  recipes: {
    headerTitle: 'HomeBase — Rezepte',
    title: 'Rezepte',
    count: 'Rezepte', // rendered as "{n} Rezepte"
    filterAll: 'Alle',
    emptyAll: 'Noch keine Rezepte',
    emptyCategory: 'Keine Rezepte in dieser Kategorie',
    emptyHint: 'Erstelle ein Rezept',
    newRecipe: 'Neues Rezept',
    editRecipe: 'Rezept bearbeiten',
    minutesAbbr: 'Min',
    servingsAbbr: 'Port.',
    prep: 'Vorbereitung',
    cook: 'Kochzeit',
    totalTime: 'Gesamt',
    prepLabel: 'Vorbereitung (Min)',
    cookLabel: 'Kochzeit (Min)',
    servings: 'Portionen',
    lessServings: 'Weniger Portionen',
    moreServings: 'Mehr Portionen',
    ingredients: 'Zutaten',
    preparation: 'Zubereitung',
    edit: 'Bearbeiten',
    export: 'Exportieren',
    exportTitle: 'Rezept exportieren',
    exportHint: 'Wähle ein Format zum Herunterladen.',
    exportMarkdown: 'Als Markdown',
    exportPdf: 'Als PDF',
    exportFailed: 'Rezept konnte nicht exportiert werden.',
    category: 'Kategorie',
    addIngredient: '+ Zutat',
    ingredientName: 'Zutat',
    amount: 'Menge',
    unitAbbr: 'Einh.',
    removeIngredient: 'Zutat entfernen',
    addSection: '+ Abschnitt',
    sectionName: 'Abschnitt (optional)',
    removeSection: 'Abschnitt entfernen',
    newRecipeEyebrow: 'Rezept',
    addStep: '+ Schritt',
    stepPlaceholder: 'Schritt beschreiben…',
    removeStep: 'Schritt entfernen',
    // ingredient bulk/free-text editor (paste a whole list at once)
    editAsText: 'Als Text',
    editAsList: 'Als Liste',
    ingredientsTextPlaceholder: 'Eine Zutat pro Zeile, z. B. „200 g Mehl"\n# Name beginnt einen Abschnitt (z. B. # Teig)',
    ingredientsTextHint: 'Eine Zutat pro Zeile (z. B. „200 g Mehl"). Eine Zeile mit „# Name" beginnt einen Abschnitt.',
    // recipe cover image (single)
    image: 'Bild',
    addImage: 'Bild hinzufügen',
    changeImage: 'Bild ändern',
    uploading: 'Wird hochgeladen…',
    removeImage: 'Bild entfernen',
    openImage: 'Bild öffnen',
    imageTooLarge: 'Bild ist zu groß (max. 10 MB).',
    imageBadType: 'Nur JPEG, PNG, WebP oder GIF erlaubt.',
    imageUploadFailed: 'Upload fehlgeschlagen.',
    imageDeleteFailed: 'Bild konnte nicht gelöscht werden.',
    addToList: 'Zutaten zur Liste',
    addedToList: 'Zutaten zur Einkaufsliste hinzugefügt', // "{n} Zutaten …"
    addedOne: 'Zutat zur Einkaufsliste hinzugefügt', // "1 Zutat …"
    added: 'hinzugefügt', // toast: "{n} hinzugefügt"
    merged: 'zusammengeführt', // toast: "{n} zusammengeführt"
    nothingToAdd: 'Keine neuen Zutaten',
    pickerScaledTo: 'Mengen für {n} Portionen',
    viewList: 'Ansehen',
    backToRecipes: 'Alle Rezepte',
    pickerTitle: 'Zutaten zur Liste',
    pickerSelected: '{n} von {total} ausgewählt', // Zutaten-Picker-Zähler
    pickerAll: 'Alle',
    pickerNone: 'Keine',
    pickerTargetList: 'Liste',
    pickerAdd: 'hinzufügen', // rendered as "{n} hinzufügen"
    pickerNoList: 'Lege zuerst eine Einkaufsliste an.',
    // write-error fallbacks (issue #96)
    saveFailed: 'Rezept konnte nicht gespeichert werden.',
    deleteFailed: 'Rezept konnte nicht gelöscht werden.',
    addToListFailed: 'Zutaten konnten nicht zur Einkaufsliste hinzugefügt werden.',
    // keys match the RecipeCategory enum values from the backend
    categories: {
      BREAKFAST: 'Frühstück',
      DINNER: 'Abend',
      SNACK: 'Snack',
      DESSERT: 'Dessert',
      DRINK: 'Getränk',
    },
  },
  // HB-02 — Wochenplan / Essensplaner (#218)
  wochenplan: {
    eyebrow: 'Essensplaner',
    title: 'Wochenplan',
    weekNav: 'Wochen-Navigation',
    prevWeek: 'Vorherige Woche',
    nextWeek: 'Nächste Woche',
    today: 'Diese Woche',
    // grid meal slots (independent of the recipe categories)
    slots: {
      BREAKFAST: 'Frühstück',
      LUNCH: 'Mittag',
      DINNER: 'Abend',
    },
    addMeal: 'Rezept einplanen',
    removeMeal: 'Aus dem Plan entfernen',
    // recipe picker (set/replace a slot)
    pickSearch: 'Rezept suchen…',
    pickEmpty: 'Lege zuerst ein Rezept an.',
    pickNoMatch: 'Kein passendes Rezept.',
    pickConfirm: 'Übernehmen',
    remove: 'Entfernen',
    // Portionen pro Eintrag (#251) — Picker-Stepper + Kachel-Anzeige
    servings: 'Portionen',
    servingsShort: '{n} Port.', // Kachel-Badge, z. B. „4 Port."
    lessServings: 'Weniger Portionen',
    moreServings: 'Mehr Portionen',
    saveFailed: 'Rezept konnte nicht eingeplant werden.',
    removeFailed: 'Eintrag konnte nicht entfernt werden.',
    // "in Einkaufsliste"
    addToShopping: 'In Einkaufsliste',
    addToShoppingTitle: 'Zutaten der Woche zur Einkaufsliste',
    addToShoppingSummary: '{items} Zutaten aus {dishes} geplanten Gerichten',
    addToShoppingFailed: 'Zutaten konnten nicht hinzugefügt werden.',
    targetList: 'Liste',
    noList: 'Lege zuerst eine Einkaufsliste an.',
    addConfirm: 'Hinzufügen',
    added: 'hinzugefügt', // toast: "{n} hinzugefügt"
    merged: 'zusammengeführt', // toast: "{n} zusammengeführt"
    nothingToAdd: 'Keine neuen Zutaten',
  },
  abwesenheit: {
    headerTitle: 'HomeBase — Kalender',
    eyebrow: 'Familienkalender',
    title: 'Kalender',
    layoutYear: 'Jahr',
    layoutMonth: 'Monat',
    period: 'Zeitraum',
    today: 'Heute',
    prevYear: 'Vorheriges Jahr',
    nextYear: 'Nächstes Jahr',
    yearNav: 'Jahr: {year}', // a11y group label for the year stepper (#133)
    prevMonth: 'Vorheriger Monat',
    nextMonth: 'Nächster Monat',
    clickHint: 'Tag klicken zum Bearbeiten · mit ⇧ Shift einen Zeitraum markieren',
    loadError: 'Kalender konnte nicht geladen werden.',
    // summary card
    leaveRemaining: 'Urlaub übrig',
    taken: 'Genommen',
    planned: 'Geplant',
    allowance: 'Anspruch',
    carryover: 'Übertrag', // "+{n} Übertrag"
    carryUntil: 'bis', // "bis {DD.MM.}"
    carryLost: 'verfallen', // "{n} verfallen"
    sick: 'Krank',
    childSick: 'Kind-krank',
    plannedTitle: 'von {used} von {total} Tagen verplant', // tooltip on the bar
    // legend
    legendUrlaub: 'Urlaub (je Person)',
    legendKrank: 'Krank',
    legendKind: 'Kind-krank',
    legendFeiertag: 'Feiertag',
    legendTeilzeit: 'Teilzeit frei',
    legendWeekend: 'Wochenende',
    legendKita: 'Kita-Schließtag',
    // states / labels
    stateFeiertag: 'Feiertag',
    stateTeilzeit: 'Teilzeit frei',
    stateWeekend: 'Wochenende',
    stateWorkday: 'Arbeitstag',
    kitaShort: 'Kita',
    frei: 'frei',
    // day editor
    work: 'Arbeit',
    urlaub: 'Urlaub',
    krank: 'Krank',
    kindKrank: 'Kind-krank',
    fullDay: 'Ganzer Tag',
    halfDay: 'Halbtags',
    forenoon: 'Vormittag (AM)',
    afternoon: 'Nachmittag (PM)',
    noteHoliday: 'Feiertag', // "Feiertag · {name}"
    noteTeilzeit: 'Teilzeit · ohnehin frei',
    noteWeekend: 'Wochenende',
    kitaClosure: 'Kita-Schließtag',
    kitaForFamily: 'Gilt für die ganze Familie',
    occasionOptional: 'Anlass (optional)',
    occasionPlaceholder: 'z. B. Sommerschließung',
    occasion: 'Anlass',
    done: 'Fertig',
    kitaDefaultLabel: 'Kita geschlossen',
    // range modal
    periodTitle: 'Zeitraum eintragen',
    forWhom: 'Für wen',
    kind: 'Art',
    from: 'Von',
    to: 'Bis',
    deleteEntry: 'Eintrag löschen',
    apply: 'Übernehmen',
    // "Wird nur auf Arbeitstage angewendet … (≈ {n} Tage für {name}). Für halbe Tage einen einzelnen Tag anklicken."
    rangeHint: 'Wird nur auf Arbeitstage angewendet — Wochenenden, Feiertage und feste freie Tage werden übersprungen',
    rangePreview: '≈ {n} Tage für {name}',
    rangeHalfHint: 'Für halbe Tage einen einzelnen Tag anklicken.',
    rangeClearHint: 'Entfernt alle Einträge der gewählten Person(en) im Zeitraum.',
    bundesland: 'Bundesland',
    yearAllowance: 'Jahresanspruch (Tage)',
    restLeave: 'Resturlaub Vorjahr',
    expiresOn: '… verfällt am',
    kindKrankCap: 'Kind-krank Anspruch',
    teilzeitTitle: 'Teilzeit · feste freie Tage',
    teilzeitEmpty: 'Keine Regel — Vollzeit.',
    teilzeitFromLabel: 'ab',
    teilzeitToLabel: 'bis',
    addFreeDay: 'Freien Tag hinzufügen',
    weekdayFree: '. frei', // "{Mo}. frei"
    deleteRule: 'Regel löschen',
    kitaSection: 'Kita-Schließtage',
    kitaSectionHint: 'Gelten für die ganze Familie — als Hintergrund-Markierung im Kalender.',
    kitaEmpty: 'Noch keine Schließtage erfasst.',
    singleDay: 'Einzeltag',
    add: 'Hinzufügen',
    kitaRangeHint: 'Wochenenden werden beim Zeitraum automatisch übersprungen.',
    // eigene Feiertage (#51)
    holidaySection: 'Eigene Feiertage',
    holidaySectionHint: 'Gelten für die ganze Familie und wiederholen sich jedes Jahr (z. B. Heiligabend, Silvester). Halbtags = ein halber freier Tag.',
    holidayEmpty: 'Noch keine eigenen Feiertage erfasst.',
    holidayDate: 'Datum (jährlich)',
    holidayDefaultLabel: 'Feiertag',
    holidayRecurHint: 'Das Jahr im Datum wird ignoriert — nur Tag und Monat zählen und gelten jedes Jahr.',
    delete: 'Löschen',
    weekdaysShort: ['Mo', 'Di', 'Mi', 'Do', 'Fr'],
    // write-error fallbacks (issue #96)
    saveFailed: 'Eintrag konnte nicht gespeichert werden.',
    deleteFailed: 'Eintrag konnte nicht gelöscht werden.',
    settingsFailed: 'Einstellung konnte nicht gespeichert werden.',
    kitaFailed: 'Kita-Schließtag konnte nicht gespeichert werden.',
    holidayFailed: 'Eigener Feiertag konnte nicht gespeichert werden.',
    partTimeFailed: 'Teilzeit-Regel konnte nicht gespeichert werden.',
  },
}
