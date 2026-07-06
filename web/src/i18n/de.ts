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
    familienkalender: 'Familienkalender',
    abwesenheit: 'Kalender',
    settings: 'Einstellungen',
    more: 'Mehr', // bottom-tab "Mehr" overflow sheet (HB-09)
    main: 'Hauptnavigation', // aria-label for the main (bottom/side) nav landmark
    // Short labels for the mobile bottom tab bar (7 items must fit on a 360px phone).
    short: {
      dashboard: 'Dashboard',
      todos: 'Aufgaben',
      shopping: 'Einkauf',
      notes: 'Notizen',
      time: 'Zeit',
      recipes: 'Rezepte',
      wochenplan: 'Plan',
      familienkalender: 'Termine',
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
    // greeting head — thresholds mirror the original design
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
    statOverdue: 'Überfällig',
    statDueTomorrow: 'Morgen fällig',
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
    editDateTitle: 'Fälligkeit ändern', // quick-edit popover: date/time only, opened from the row
    editAssigneeTitle: 'Zuständig ändern', // quick-edit popover: assignees only, opened from the row
    planHint: 'Titel ist Pflicht. Zuständige:r oder Fälligkeit machen daraus eine geplante Aufgabe.',
    titleLabel: 'Titel',
    planList: 'Liste', // list picker in the plan sheet (all todos; move between lists #409)
    planListInbox: 'Ohne Liste (Inbox)', // empty option of the plan-sheet list picker (#69/#409)
    // Live auto-save chip + close button in the plan sheet — edits persist automatically (parity with
    // the notes editor and the Android edit sheet).
    autosaveSaving: 'Speichert…',
    autosaveSaved: 'Gespeichert',
    autosaveError: 'Nicht gespeichert',
    planDone: 'Fertig',
    assignee: 'Zuständig',
    assigneeNone: 'Niemand',
    // Metadaten-Zeile (Provenienz) im ausgeklappten Row-Panel + Edit-Sheet
    metaCreated: 'Erstellt von {who}', // gefolgt von „· vor 3 Tagen"; {who}=Ersteller (i18next-Prefix „{")
    metaUpdated: 'Geändert', // gefolgt von „· vor 1 Std"; nur wenn seit dem Anlegen editiert
    metaDone: 'Erledigt', // gefolgt von „· vor 2 Std"
    dueDate: 'Fällig am',
    dueTime: 'Uhrzeit',
    priority: 'Priorität',
    priorityNone: '—',
    priorityLow: 'Niedrig',
    priorityMedium: 'Mittel',
    priorityHigh: 'Hoch',
    // Quick-add „Details"-Panel: Felder direkt beim Erfassen setzen
    description: 'Beschreibung',
    descriptionPlaceholder: 'Optionale Notiz …',
    quickAddDetails: 'Details',
    quickAddHasDetailsSr: 'Felder gesetzt',
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
    editListNamed: 'Liste „{name}" bearbeiten', // link label, {name} = Listenname (Quotes sprachabhängig)
    editListTitle: 'Liste bearbeiten', // edit-modal title
    saveList: 'Speichern', // save button in the edit modal
    deleteListNamed: 'Liste „{name}" löschen', // link label, {name} = Listenname (Quotes sprachabhängig)
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
    tabOverdue: 'Überfällig',
    tabToday: 'Heute',
    tabTomorrow: 'Morgen',
    tabDone: 'Erledigt',
    // Zwei-Zeilen-Tableiste: Filter (übergreifend) oben, Listen unten — aria-labels
    filtersAria: 'Aufgaben-Filter',
    listsAria: 'Listen',
    allEmpty: 'Noch keine Aufgaben',
    allEmptyHint: 'Lege Aufgaben in einer Liste oder der Inbox an.',
    overdueEmpty: 'Nichts überfällig',
    overdueEmptyHint: 'Alles rechtzeitig erledigt.',
    todayEmpty: 'Heute nichts fällig',
    todayEmptyHint: 'Keine offenen Aufgaben mit Fälligkeit heute.',
    tomorrowEmpty: 'Morgen nichts fällig',
    tomorrowEmptyHint: 'Keine offenen Aufgaben mit Fälligkeit morgen.',
    doneViewEmpty: 'Zuletzt nichts erledigt',
    doneViewEmptyHint: 'Aufgaben der letzten {n} Tage erscheinen hier.',
    doneViewEmptyAllHint: 'Es wurde noch nichts erledigt.', // bei aktivem "Alle anzeigen" (#340)
    doneWindowNote: 'Letzte {n} Tage', // Hinweis über der Erledigt-Liste (#263)
    // "Alle anzeigen"-Umschalter für die Erledigt-Historie (#340)
    doneShowAll: 'Alle anzeigen',
    doneShowWindow: 'Nur letzte {n} Tage',
    doneShowingAll: 'Gesamte Historie',
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
    listsAria: 'Einkaufslisten', // aria-label der Listen-Tabs (role=tablist)
    listOne: 'Liste',
    listMany: 'Listen',
    newList: 'Neue Liste',
    newListTitle: 'Neue Liste',
    listName: 'Name',
    listNamePlaceholder: 'z. B. Wocheneinkauf',
    createList: 'Erstellen',
    deleteListNamed: 'Liste „{name}" löschen', // link label, {name} = Listenname (Quotes sprachabhängig)
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
    // Listen-/Kachelansicht-Umschalter (#440)
    viewToggleAria: 'Ansicht umschalten',
    viewList: 'Listenansicht',
    viewTiles: 'Kachelansicht',
    checkOff: '„{name}" abhaken', // Kachel-Tap: ins „Im Wagen" legen
    uncheck: '„{name}" zurücklegen', // abgehakte Kachel wieder offen
    // Item bearbeiten (Name + Menge/Notiz, #445)
    editItem: '„{name}" bearbeiten',
    editFailed: 'Artikel konnte nicht gespeichert werden.',
    fieldName: 'Name',
    fieldQuantity: 'Menge',
    fieldQuantityHint: 'Frei eingebbar, z. B. „500 g", „2 Packungen", „10er".',
    fieldQuantityPlaceholder: 'z. B. 500 g',
    fieldNote: 'Notiz',
    fieldNotePlaceholder: 'z. B. im roten Glas',
    // Icon pro Item wählen (#442)
    fieldIcon: 'Icon',
    chooseIcon: 'Icon wählen',
    iconSearch: 'Icon suchen … (z. B. Möhre)',
    iconNoMatch: 'Kein passendes Icon gefunden.',
    // Kategorien, Emoji-Icons & „Meist genutzt"-Autocomplete (#389)
    suggestionsHint: 'Häufig gekauft',
    moveCategory: 'In Kategorie verschieben',
    moveFailed: 'Kategorie konnte nicht geändert werden.',
    // Eigene Kategorien pro Liste (#412): eine Liste kann statt des geteilten Katalogs ihren eigenen
    // Satz führen (z. B. Baumarkt). Verwaltung inline auf der Liste, startet mit „Sonstiges".
    ownCategories: 'Eigene Kategorien für diese Liste',
    ownCategoriesHint: 'Statt der geteilten Haushalts-Kategorien bekommt diese Liste ihren eigenen Satz — praktisch für z. B. Baumarkt.',
    manageCategories: 'Kategorien verwalten',
    manageCategoriesTitle: 'Kategorien: {name}',
    ownCategoriesCardTitle: 'Eigene Kategorien',
    ownCategoriesCardHint: 'Nur für diese Liste. Nicht zugeordnete Artikel landen in „Sonstiges".',
    listUpdateFailed: 'Liste konnte nicht geändert werden.',
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
    // bulk collapse/expand control above the folder-grouped list (#345)
    collapseAll: 'Alle einklappen',
    expandAll: 'Alle ausklappen',
    noResults: 'Keine Treffer',
    empty: 'Noch keine Notizen',
    emptyHint: 'Erstelle eine Notiz',
    selectHint: 'Wähle links eine Notiz oder erstelle eine neue.',
    newNote: 'Neue Notiz',
    editNote: 'Notiz bearbeiten',
    // HB-13 — preview-first inline editor
    emptyDoc: 'Leere Notiz — klicke, um zu schreiben',
    outsideSaves: 'Klick außerhalb speichert',
    escCloses: 'schließt',
    metaLine: 'von {name} · bearbeitet {time}',
    // auto-save status indicator (replaces the manual save button)
    saving: 'Speichert…',
    saved: 'Gespeichert',
    // mobile: collapse the list when a note is open + jump to another note
    backToList: 'Notizen',
    switchNote: 'Notiz wechseln',
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
    downloadImage: 'Bild herunterladen',
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
    // Datei-Anhänge (#431): beliebige Dateien (PDF, Office, Text …) an einer Notiz
    attachments: 'Anhänge',
    addAttachment: 'Datei anhängen',
    removeAttachment: 'Anhang entfernen',
    openAttachment: 'Öffnen',
    attachmentTooLarge: 'Datei ist zu groß (max. 10 MB).',
    attachmentBadType: 'Dateityp nicht erlaubt (PDF, Text, Office …).',
    attachmentUploadFailed: 'Upload fehlgeschlagen.',
    attachmentsSomeFailed: '{count} Datei(en) konnten nicht hochgeladen werden.',
    attachmentDeleteTitle: 'Anhang löschen?',
    attachmentDeleteConfirmNamed: '„{name}" wird gelöscht. Das kann nicht rückgängig gemacht werden.',
    attachmentDeleteConfirmUnnamed: 'Der Anhang wird gelöscht. Das kann nicht rückgängig gemacht werden.',
    attachmentDeleteFailed: 'Anhang konnte nicht gelöscht werden.',
    attachmentDownloadFailed: 'Anhang konnte nicht heruntergeladen werden.',
    // delete confirm (destruktive Aktion über ConfirmDialog, #125/#129/#378)
    deleteTitle: 'Notiz löschen?',
    deleteConfirmTitled: '„{title}" wird gelöscht. Angehängte Bilder werden mitgelöscht. Das kann nicht rückgängig gemacht werden.',
    deleteConfirmUntitled: 'Die Notiz wird gelöscht. Angehängte Bilder werden mitgelöscht. Das kann nicht rückgängig gemacht werden.',
    deleteBtn: 'Endgültig löschen',
    // einzelnes Bild löschen — ebenfalls über ConfirmDialog (#385, Geschwister von #378)
    imageDeleteTitle: 'Bild löschen?',
    imageDeleteConfirmNamed: '„{name}" wird gelöscht. Das kann nicht rückgängig gemacht werden.',
    imageDeleteConfirmUnnamed: 'Das Bild wird gelöscht. Das kann nicht rückgängig gemacht werden.',
    // write-error fallbacks (issue #96)
    saveFailed: 'Notiz konnte nicht gespeichert werden.',
    deleteFailed: 'Notiz konnte nicht gelöscht werden.',
    imageDeleteFailed: 'Bild konnte nicht gelöscht werden.',
    imageDownloadFailed: 'Bild konnte nicht heruntergeladen werden.',
    // Offline-resilientes Auto-Save (#323): ein fehlgeschlagener Save wird in einer dauerhaften
    // Queue gehalten und nachgeholt, sobald wieder online; bis dahin trägt die Notiz den
    // notSynced-Marker (Notiz ohne WLAN bearbeiten, analog Einkauf).
    notSynced: 'Noch nicht synchronisiert',
    offlineQueuedOne: '1 Änderung wird nachgeholt, sobald wieder online.',
    offlineQueuedMany: '{n} Änderungen werden nachgeholt, sobald wieder online.',
    retryNow: 'Jetzt versuchen',
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
    exportQuickMonth: 'Ganzer Monat',
    exportLastMonth: 'Letzter Monat',
    exportThisMonth: 'Dieser Monat',
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
    // Todo-Erinnerungen (#429 Phase 2a) — sofortige Erinnerung über Telegram, sobald eine Aufgabe fällig ist.
    remindersTitle: 'Aufgaben-Erinnerungen',
    remindersHint: 'Sofortige Erinnerung über Telegram und/oder Browser, wenn eine Aufgabe mit Uhrzeit fällig ist (optional mit Vorlauf).',
    remindersEnabled: 'Erinnerungen senden',
    remindersQuietStart: 'Ruhezeit ab',
    remindersQuietEnd: 'Ruhezeit bis',
    remindersQuietHint: 'In der Ruhezeit werden keine Erinnerungen gesendet; fällige Erinnerungen kommen danach nach.',
    remindersQuietIncomplete: 'Ruhezeit braucht beide Uhrzeiten (oder beide leer).',
    remindersSaved: 'Gespeichert',
    remindersSaveFailed: 'Einstellung konnte nicht gespeichert werden.',
    // Browser-Benachrichtigungen (#429 Phase 2b) — Web Push pro Gerät.
    pushTitle: 'Browser-Benachrichtigungen',
    pushHint: 'Aufgaben-Erinnerungen direkt als Benachrichtigung in diesem Browser empfangen.',
    pushEnable: 'Auf diesem Gerät aktivieren',
    pushDisable: 'Auf diesem Gerät deaktivieren',
    pushEnabled: 'Aktiviert',
    pushDisabled: 'Deaktiviert.',
    pushDenied: 'Benachrichtigungen wurden blockiert. Bitte in den Browser-Einstellungen für diese Seite erlauben.',
    pushUnavailable: 'Web Push ist auf dem Server nicht konfiguriert (kein VAPID-Schlüssel).',
    pushError: 'Konnte nicht aktiviert werden. Bitte erneut versuchen.',
    pushDeviceNote: 'Gilt nur für diesen Browser/dieses Gerät — auf jedem Gerät einzeln aktivieren.',
    // Aufgaben-Unterseite (#356): haushaltsweite Anzeige-Optionen für Aufgaben.
    todos: 'Aufgaben',
    // „Erledigt"-Fenster (#356, Folge aus #340): wie viele Tage die Erledigt-Historie zeigt,
    // bevor sie gekappt wird. Der „Alle anzeigen"-Schalter pro Gerät überschreibt das weiterhin.
    doneWindowTitle: 'Erledigt-Fenster',
    doneWindowHint: 'Wie viele Tage erledigte Aufgaben im „Erledigt"-Tab und im Erledigt-Bereich angezeigt werden. „Alle anzeigen" pro Gerät überschreibt das weiterhin; die Zähler bleiben auf „heute".',
    doneWindowLabel: 'Tage',
    doneWindowSaved: 'Gespeichert',
    doneWindowSaveFailed: 'Wert konnte nicht gespeichert werden.',
    doneWindowInvalid: 'Bitte eine ganze Zahl zwischen {{min}} und {{max}} angeben.',
    doneWindowApplies: 'Änderungen greifen beim nächsten Laden der Aufgaben.',
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
    // Einkaufskategorien-Unterseite (#411): haushaltsweiter Katalog der Einkaufs-Kategorien
    // (Überschriften in der Einkaufsliste) + automatische Zuordnungsregeln (Name → Kategorie).
    shopping: 'Einkaufskategorien',
    shoppingTitle: 'Einkaufskategorien',
    shoppingHint: 'Kategorien für die Einkaufsliste und automatische Zuordnungsregeln für neu erfasste Artikel. Gilt für den ganzen Haushalt.',
    // Kategorien-Karte: Liste, Anlegen, Bearbeiten, Umsortieren, Löschen.
    shoppingCatsTitle: 'Kategorien',
    shoppingCatsHint: 'Überschriften, nach denen die Einkaufsliste gruppiert wird.',
    shoppingCatsEmpty: 'Noch keine Kategorien.',
    shoppingCatAdd: 'Kategorie hinzufügen',
    shoppingCatEdit: 'Kategorie bearbeiten',
    shoppingCatNew: 'Neue Kategorie',
    shoppingCatLabel: 'Bezeichnung',
    shoppingCatLabelPlaceholder: 'z. B. Obst & Gemüse',
    shoppingCatEmoji: 'Emoji',
    shoppingCatEmojiPlaceholder: '🥦',
    shoppingCatMoveUp: 'Nach oben',
    shoppingCatMoveDown: 'Nach unten',
    shoppingCatBuiltin: 'Vorgabe',
    shoppingCatDeleteTitle: 'Kategorie löschen',
    shoppingCatDeleteBody: '„{{label}}“ löschen? Artikel dieser Kategorie wandern nach „Sonstiges“.',
    shoppingCatDeleteConfirm: 'Löschen',
    shoppingCatProtected: 'Diese Kategorie kann nicht gelöscht werden.',
    shoppingCatSaveFailed: 'Kategorie konnte nicht gespeichert werden.',
    shoppingCatDeleteFailed: 'Kategorie konnte nicht gelöscht werden.',
    // Regeln-Karte: Liste, Anlegen/Bearbeiten (Upsert), Löschen.
    shoppingRulesTitle: 'Auto-Zuordnungsregeln',
    shoppingRulesHint: 'Ordnet neu erfassten Artikeln anhand des Namens automatisch eine Kategorie (und ein Emoji) zu.',
    shoppingRulesEmpty: 'Noch keine Regeln.',
    shoppingRuleAdd: 'Regel hinzufügen',
    shoppingRuleEdit: 'Regel bearbeiten',
    shoppingRuleNew: 'Neue Regel',
    shoppingRuleName: 'Artikelname',
    shoppingRuleNamePlaceholder: 'z. B. Milch',
    shoppingRuleCategory: 'Kategorie',
    shoppingRuleEmoji: 'Emoji (optional)',
    shoppingRuleSave: 'Speichern',
    shoppingRuleDeleteTitle: 'Regel löschen',
    shoppingRuleDeleteBody: 'Regel für „{{name}}“ löschen?',
    shoppingRuleDeleteConfirm: 'Löschen',
    shoppingRuleSaveFailed: 'Regel konnte nicht gespeichert werden.',
    shoppingRuleDeleteFailed: 'Regel konnte nicht gelöscht werden.',
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
    // URL-Import (schema.org/Recipe JSON-LD, #430)
    importFromUrl: 'Aus URL importieren',
    importTitle: 'Rezept aus URL importieren',
    importHint: 'Füge die Adresse einer Rezeptseite ein. Wir lesen Titel, Zutaten und Schritte aus — du prüfst und ergänzst sie anschließend im Editor.',
    importUrlLabel: 'Rezept-URL',
    importAction: 'Importieren',
    importing: 'Wird importiert…',
    importNoData: 'Auf dieser Seite wurden keine Rezeptdaten gefunden. Du kannst das Rezept manuell anlegen.',
    importFailed: 'Import fehlgeschlagen. Bitte prüfe die URL.',
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
    downloadImage: 'Bild herunterladen',
    imageTooLarge: 'Bild ist zu groß (max. 10 MB).',
    imageBadType: 'Nur JPEG, PNG, WebP oder GIF erlaubt.',
    imageUploadFailed: 'Upload fehlgeschlagen.',
    imageDeleteFailed: 'Bild konnte nicht gelöscht werden.',
    imageDownloadFailed: 'Bild konnte nicht heruntergeladen werden.',
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
    addMeal: 'Gericht einplanen',
    removeMeal: 'Aus dem Plan entfernen',
    // recipe picker (set/replace a slot)
    pickSearch: 'Rezept suchen oder Gericht eintippen…',
    pickEmpty: 'Noch keine Rezepte — tippe oben einfach ein Gericht ein.',
    pickNoMatch: 'Kein passendes Rezept.',
    useAsText: 'Als Gericht übernehmen:', // #293 — gefolgt vom eingetippten Text
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
    // Zwei unabhängige Zähler in einem Satz → je ein pluralisiertes Fragment
    // (i18next `_one`/`_other`, count-getrieben), zusammengefügt im Summary.
    // So stimmt der Singular: „1 Zutat aus 1 geplantem Gericht".
    addToShoppingItems_one: '{count} Zutat',
    addToShoppingItems_other: '{count} Zutaten',
    addToShoppingDishes_one: '{count} geplantem Gericht',
    addToShoppingDishes_other: '{count} geplanten Gerichten',
    addToShoppingSummary: '{items} aus {dishes}',
    addToShoppingFailed: 'Zutaten konnten nicht hinzugefügt werden.',
    targetList: 'Liste',
    noList: 'Lege zuerst eine Einkaufsliste an.',
    addConfirm: 'Hinzufügen',
    added: 'hinzugefügt', // toast: "{n} hinzugefügt"
    merged: 'zusammengeführt', // toast: "{n} zusammengeführt"
    nothingToAdd: 'Keine neuen Zutaten',
  },
  // #427 — Familienkalender: domänenübergreifende read-only Monatsansicht (Todos/Abwesenheit/
  // Kita/Wochenplan) + iCal-Abo-Hinweis.
  familienkalender: {
    eyebrow: 'Übersicht',
    title: 'Familienkalender',
    monthNav: 'Monats-Navigation',
    prevMonth: 'Vorheriger Monat',
    nextMonth: 'Nächster Monat',
    today: 'Heute',
    // weekday header (Mo–So)
    weekdays: ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'],
    // legend / category labels
    legend: 'Legende',
    catTodos: 'Fällige Aufgaben',
    catAbsence: 'Abwesenheit',
    catKita: 'Kita zu',
    catMeals: 'Essensplan',
    catEvents: 'Termine',
    // day-detail sheet
    detailEmpty: 'Nichts an diesem Tag.',
    sectionTodos: 'Fällige Aufgaben',
    sectionAbsence: 'Abwesenheit',
    sectionKita: 'Kita',
    sectionMeals: 'Essensplan',
    sectionEvents: 'Termine',
    moreCount: '+{count} mehr', // overflow chip on a packed day cell
    half: { vm: 'vormittags', nm: 'nachmittags' },
    // iCal subscription hint
    subscribe: 'Abonnieren',
    subscribeTitle: 'In deinem Kalender abonnieren',
    subscribeIntro: 'Abonniere diesen Feed einmal in Apple oder Google Kalender — dann erscheinen fällige Aufgaben, Abwesenheiten, Kita-Schließtage und der Essensplan automatisch auf deinem Handy.',
    subscribeCopy: 'Link kopieren',
    subscribeCopied: 'Kopiert!',
    subscribeNote: 'Der Link enthält dein persönliches Zugangs-Token — teile ihn nicht.',
    subscribeIncludeLabel: 'Was soll enthalten sein?',
    subscribeIncludeHint: 'Gilt nur für deinen eigenen Feed — wird beim Antippen gespeichert.',
    subscribeSaveFailed: 'Konnte nicht gespeichert werden.',
    feedSection: {
      todos: 'Fällige Aufgaben',
      absences: 'Abwesenheiten',
      parttime: 'Teilzeit-freie Tage',
      kita: 'Kita-Schließtage',
      meals: 'Essensplan',
      events: 'Termine',
    },
  },
  abwesenheit: {
    headerTitle: 'HomeBase — Kalender',
    eyebrow: 'Familienkalender',
    title: 'Kalender',
    layoutAria: 'Ansicht', // aria-label des Jahr/Monat-Umschalters (role=tablist)
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
