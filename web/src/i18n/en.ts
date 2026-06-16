// English UI string catalog. Structurally identical to `de` — the
// `Messages = typeof de` type forces parity, so `tsc` flags any missing/extra
// key. Keep placeholders (single braces like `{name}`, `{n}`, `{time}`) intact;
// they are interpolated by i18next (single-brace prefix/suffix, see index.ts).
import type { Messages } from './index'

export const en: Messages = {
  common: {
    loading: 'Loading…',
    cancel: 'Cancel',
    confirm: 'Confirm',
    add: 'Add',
    save: 'Save',
    delete: 'Delete',
    edit: 'Edit',
    logout: 'Log out',
    close: 'Close',
    by: 'by', // rendered as "by {name}"
    titlePlaceholder: 'Title…',
    descriptionOptional: 'Description (optional)…',
    networkError: 'No connection – please try again later.',
  },
  // HB-07 — locale-aware date/relative-time wording used by ui/format.ts.
  fmt: {
    today: 'Today',
    tomorrow: 'Tomorrow',
    yesterday: 'Yesterday',
    yesterdayRel: 'yesterday',
    dayBeforeYesterday: 'Day before yesterday',
    overdueDays: '{n} days overdue',
    inDays: 'In {n} days',
    justNow: 'just now',
    minAgo: '{n} min ago',
    hrsAgo: '{n} h ago',
    daysAgo: '{n} days ago',
    weeksAgo: '{n} wk ago',
    durMin: '{m} min',
    durHourMin: '{h} h {m} min',
    thisWeek: 'This week',
    lastWeek: 'Last week',
  },
  // Backend ErrorResponse codes → English text. Shared across views so write
  // failures read consistently; falls back to a per-action default when a code
  // is missing/unknown (see i18n `errorText` and issue #84).
  errors: {
    PROJECT_ARCHIVED: 'This project is archived.',
    INVALID_RANGE: 'The end must be after the start.',
    INVALID_DATE: 'Invalid date.',
    INVALID_ID: 'Invalid selection.',
    INVALID_PROJECT: 'Project name must not be empty.',
    INVALID_COLOR: 'Invalid colour.',
    DEFAULT_REQUIRED: 'As long as weekly hours are set, a default project is required.',
    ENTRY_RUNNING: 'Running timers cannot be split — stop it first.',
    NOT_FOUND: 'Not found – please reload.',
    NO_RUNNING_TIMER: 'No timer is running right now.',
    BAD_REQUEST: 'Invalid request.',
    INTERNAL_ERROR: 'Server error – please try again later.',
    FORBIDDEN: 'You do not have permission for that.',
    MISSING_PARAM: 'A required field is missing.',
    INVALID_NAME: 'Name must not be empty (max. 60 characters).',
    INVALID_PASSWORD: 'Current password is incorrect.',
    WEAK_PASSWORD: 'New password needs at least 8 characters.',
    PASSWORD_UNCHANGED: 'New password must differ from the old one.',
    INVALID_TIME: 'Invalid time (HH:mm).',
    // Todos / Lists
    INVALID_TODO: 'Task incomplete – set a title or an assignee/due date.',
    INVALID_STATUS: 'Invalid status.',
    INVALID_PRIORITY: 'Invalid priority.',
    INVALID_DUE_DATE: 'Invalid due date.',
    INVALID_RECURRENCE: 'Invalid recurrence – set a due date for a recurrence.',
    INVALID_SUBTASK: 'Subtask title must not be empty.',
    INVALID_LIST: 'List name must not be empty.',
    INVALID_VISIBILITY: 'Invalid visibility.',
    // Shopping
    INVALID_SHOPPING_ITEM: 'Name must not be empty.',
    INVALID_TEMPLATE: 'Template name must not be empty.',
    // Notes
    INVALID_NOTE: 'Title must not be empty.',
    VISIBILITY_FORBIDDEN: 'Only the creator may change the visibility.',
    IMAGE_TOO_LARGE: 'Image is too large.',
    UNSUPPORTED_TYPE: 'Only JPEG, PNG, WebP or GIF allowed.',
    EMPTY_IMAGE: 'The uploaded image was empty.',
    NO_IMAGE: 'No image file in the request.',
    // Recipes
    INVALID_RECIPE: 'Recipe details invalid – check title, servings and times.',
    INVALID_INGREDIENT: 'Ingredient amount must be ≥ 0.',
    INVALID_CATEGORY: 'Unknown category.',
    // Abwesenheit
    INVALID_TYPE: 'Invalid type.',
    INVALID_HALF: 'Invalid half-day.',
    INVALID_WEEKDAY: 'Invalid weekday.',
    INVALID_STATE: 'Invalid federal state.',
    INVALID_YEAR: 'Invalid year.',
    // Applies to both editors that uniquely occupy a date (Kita closure PUT, custom
    // holiday PUT) — deliberately neutral wording, not Kita-specific (#254).
    DATE_CONFLICT: 'There is already an entry for this date.',
    RANGE_TOO_LARGE: 'The period is too long.',
    TOO_MANY_DATES: 'Too many days in the period.',
  } as Record<string, string>,
  shell: {
    brandSub: 'Mäxchen', // default household label; overridable in settings
    syncActive: 'Real-time sync active',
    timerRunning: 'Timer running',
    logoutTitle: 'Log out?',
    logoutBody: 'You will be logged out and must sign in again afterwards.',
  },
  nav: {
    dashboard: 'Dashboard',
    todos: 'Tasks',
    shopping: 'Shopping list',
    notes: 'Notes',
    time: 'Time tracking',
    recipes: 'Recipes',
    wochenplan: 'Meal plan',
    abwesenheit: 'Calendar',
    settings: 'Settings',
    more: 'More', // bottom-tab "More" overflow sheet (HB-09)
    main: 'Main navigation', // aria-label for the main (bottom/side) nav landmark
    // Short labels for the mobile bottom tab bar (7 items must fit on a 360px phone).
    short: {
      dashboard: 'Home',
      todos: 'Tasks',
      shopping: 'Shopping',
      notes: 'Notes',
      time: 'Time',
      recipes: 'Recipes',
      wochenplan: 'Plan',
      abwesenheit: 'Calendar',
      more: 'More',
    },
  },
  login: {
    title: 'HomeBase',
    subtitle: 'Family hub',
    username: 'Username',
    password: 'Password',
    submit: 'Sign in',
    failed: 'Login failed',
  },
  // HB-03 — global search / command palette (⌘K)
  palette: {
    title: 'Search',
    open: 'Search',
    placeholder: 'Search or jump to …',
    actions: 'Actions',
    groupTodos: 'Tasks',
    groupNotes: 'Notes',
    groupRecipes: 'Recipes',
    groupProjects: 'Projects',
    groupShopping: 'Shopping',
    noResults: 'No matches',
    footNavigate: 'Navigate',
    footOpen: 'Open',
  },
  dashboard: {
    headerTitle: 'HomeBase — Today',
    // greeting head — thresholds mirror the original design
    greetingNight: 'Good night',
    greetingMorning: 'Good morning',
    greetingDay: 'Hello',
    greetingEvening: 'Good evening',
    // quick-add (lands in the Inbox — no list)
    quickAddPlaceholder: 'Quick capture – lands in the Inbox …',
    add: 'Add',
    addFailed: 'Task could not be added.',
    saveFailed: 'Change could not be saved.',
    // stat tiles
    statDueToday: 'Due today',
    statInbox: 'In the Inbox',
    statDueTomorrow: 'Due tomorrow',
    statDoneToday: 'Done today',
    // "Heute dran" card
    todayTitle: 'On today',
    allTasks: 'All tasks',
    todayEmpty: 'Nothing planned for today',
    todayEmptyHint: 'Enjoy the day — or clear the Inbox.',
    // time card
    timeTitle: 'Time tracking',
    open: 'Open',
    timerRunningHint: 'Running …',
    expectedEndShort: 'until approx. {time}', // forecast suffix at the running timer (#31)
    targetReachedShort: 'Target reached',
    stop: 'Stop',
    noTimer: 'No timer running',
    noTimerHint: 'Start a timer in time tracking.',
    // shopping peek card
    shoppingTitle: 'Shopping list',
    shoppingEmpty: 'Everything bought',
    moreItems: 'more', // rendered as "+ {n} more"
    // HB-10 recurring tasks + weekly work-target peek
    recurring: 'Recurring',
    worktargetTitle: 'Weekly target',
    worktargetHours: 'h', // unit after the weekly target hours
    worktargetTodayReached: "Today's goal reached",
    worktargetTodayLeft: '{time} left today',
  },
  todos: {
    headerTitle: 'HomeBase — Tasks',
    title: 'Tasks',
    eyebrow: 'Shared · Real-time',
    open: 'open', // rendered as "{n} open"
    plan: 'Plan',
    markDone: 'Done',
    planTitle: 'Plan task',
    planHint: 'Set at least an assignee or a due date.',
    planList: 'List', // list picker in the plan modal (only for inbox todos, issue #69)
    planListInbox: 'Stays in the Inbox', // empty option of the plan-modal list picker
    assignee: 'Assignee',
    assigneeNone: 'Nobody',
    dueDate: 'Due on',
    priority: 'Priority',
    priorityNone: '—',
    // Recurrence
    recurrence: 'Recurrence',
    recurrenceNone: 'None',
    recurrenceNeedsDue: 'Set a due date for a recurrence.',
    recurrenceEvery: 'Every', // rendered as "Every {n} weeks"
    recurrenceDaily: 'Daily',
    recurrenceWeekly: 'Weekly',
    recurrenceMonthly: 'Monthly',
    recurUnitDay: 'days',
    recurUnitWeek: 'weeks',
    recurUnitMonth: 'months',
    // compact badge on a recurring todo row
    recurBadgeDaily: 'daily',
    recurBadgeWeekly: 'weekly',
    recurBadgeMonthly: 'monthly',
    recurBadgeEvery: 'every', // "every {n} {unit}"
    // Lists (tabs)
    newList: 'New list',
    newListTitle: 'New list',
    listName: 'Name',
    listNamePlaceholder: 'e.g. Renovation',
    createList: 'Create',
    visibility: 'Visibility',
    visShared: 'Shared',
    visPrivate: 'Private',
    visSharedHint: 'Both see and edit this list.',
    visPrivateHint: 'Only you see this list.',
    editListNamed: 'Edit list "{name}"', // link label, {name} = list name (quotes are locale-specific)
    editListTitle: 'Edit list', // edit-modal title
    saveList: 'Save', // save button in the edit modal
    deleteListNamed: 'Delete list "{name}"', // link label, {name} = list name (quotes are locale-specific)
    deleteListTitle: 'Delete list?', // confirm-modal title
    deleteListConfirm: 'Delete permanently', // danger button in the confirm modal
    deleteListWarn: 'This cannot be undone.', // shown when the list has todos
    taskOne: 'task', // count noun, e.g. „1 task"
    taskMany: 'tasks', // count noun, e.g. „3 tasks"
    quickAddPlaceholder: 'New task …', // rendered as `New task in „{name}" …`
    addTask: 'Capture',
    allDone: 'All done',
    allDoneHint: 'No open tasks in this list.',
    doneSection: 'Done',
    // due buckets
    bucketOver: 'Overdue',
    bucketToday: 'Today',
    bucketSoon: 'Soon',
    bucketFar: 'Later',
    bucketNone: 'No date',
    // Smart / cross-list tabs (#255/#256) — linked from the dashboard stat tiles
    tabAll: 'All',
    tabToday: 'Today',
    tabTomorrow: 'Tomorrow',
    tabDone: 'Done',
    allEmpty: 'No tasks yet',
    allEmptyHint: 'Add tasks in a list or the Inbox.',
    todayEmpty: 'Nothing due today',
    todayEmptyHint: 'No open tasks due today.',
    tomorrowEmpty: 'Nothing due tomorrow',
    tomorrowEmptyHint: 'No open tasks due tomorrow.',
    doneViewEmpty: 'Nothing done recently',
    doneViewEmptyHint: 'Tasks completed in the last {n} days show up here.',
    doneWindowNote: 'Last {n} days', // hint above the Done list (#263)
    // Subtasks
    subtasks: 'Subtasks',
    addSubtask: 'Add subtask …',
    // write-error fallbacks (issue #96)
    addFailed: 'Task could not be added.',
    saveFailed: 'Change could not be saved.',
    deleteFailed: 'Task could not be deleted.',
    subAddFailed: 'Subtask could not be added.',
    subSaveFailed: 'Subtask could not be saved.',
    subDeleteFailed: 'Subtask could not be deleted.',
    listCreateFailed: 'List could not be created.',
    listSaveFailed: 'List could not be saved.',
    listDeleteFailed: 'List could not be deleted.',
  },
  shopping: {
    headerTitle: 'HomeBase — Shopping lists',
    title: 'Shopping lists',
    open: 'open', // rendered as "{n} open"
    listOne: 'list',
    listMany: 'lists',
    newList: 'New list',
    newListTitle: 'New list',
    listName: 'Name',
    listNamePlaceholder: 'e.g. Weekly groceries',
    createList: 'Create',
    deleteListNamed: 'Delete list "{name}"', // link label, {name} = list name (quotes are locale-specific)
    deleteListTitle: 'Delete list?', // confirm-modal title
    deleteListConfirm: 'Delete list and all items?', // modal body (legacy, no longer used in body)
    deleteListBtn: 'Delete permanently', // danger button in the confirm modal
    deleteListBody: 'The list „{name}" and all its items will be deleted.', // confirm-modal body, {name} = list name
    deleteListWarn: 'This cannot be undone.', // shown in delete-list modal body
    noLists: 'No list yet',
    noListsHint: 'Create your first shopping list above.',
    emptyTitle: 'List is empty',
    emptyHint: 'Add the first item above.',
    allChecked: 'All checked off 🎉',
    namePlaceholder: 'What is missing in „{name}"? …', // quick-add placeholder, {name} = active list
    inCart: 'In cart', // rendered as "In cart · {n}"
    clearChecked: 'Remove checked',
    // Offline sync: checking an item off without a connection is remembered locally
    // and replayed automatically; until then the item carries the notSynced marker
    // (shopping without Wi-Fi).
    notSynced: 'Not synced yet',
    offlineQueuedOne: '1 change will be replayed once back online.',
    offlineQueuedMany: '{n} changes will be replayed once back online.',
    retryNow: 'Try now',
    // write-error fallbacks (issue #96)
    addFailed: 'Item could not be added.',
    deleteFailed: 'Item could not be deleted.',
    clearFailed: 'Checked items could not be removed.',
    listCreateFailed: 'List could not be created.',
    listDeleteFailed: 'List could not be deleted.',
    // Named standard/template lists (#215): saved item names for the recurring weekly
    // shop, applied to a real list via a selection step.
    templates: {
      open: 'Templates',
      manageTitle: 'Templates',
      manageHint: 'Save recurring shops as a template and add them to a list with one click.',
      empty: 'No templates yet',
      emptyHint: 'Create your first standard list.',
      itemCount: 'items', // rendered as "{n} items"
      itemCountOne: 'item', // rendered as "1 item"
      newTemplate: 'New template',
      editTemplate: 'Edit template',
      nameLabel: 'Name',
      namePlaceholder: 'e.g. Weekly shop',
      items: 'Items',
      itemPlaceholder: 'Item …',
      addItem: '+ Item',
      removeItem: 'Remove item',
      noItemsYet: 'No items yet — add some above.',
      create: 'Create',
      apply: 'Add to list',
      applyTitle: 'Add template to list',
      applyToList: 'List',
      applyNoList: 'Create a shopping list first.',
      selected: 'selected', // rendered as "{n} of {total} selected"
      all: 'All',
      none: 'None',
      applyAdd: 'add', // rendered as "{n} add"
      deleteTitle: 'Delete template?',
      deleteConfirm: 'The template “{name}” will be deleted. This cannot be undone.',
      deleteBtn: 'Delete permanently',
      // write-error fallbacks
      saveFailed: 'Template could not be saved.',
      deleteFailed: 'Template could not be deleted.',
      applyFailed: 'Template could not be added to the list.',
      loadFailed: 'Templates could not be loaded.',
      added: 'added', // toast: "{n} added"
      merged: 'merged', // toast: "{n} merged"
      nothingToAdd: 'No new items',
    },
  },
  notes: {
    headerTitle: 'HomeBase — Notes',
    title: 'Notes',
    count: 'notes', // rendered as "{n} notes"
    searchPlaceholder: 'Search …',
    allTags: 'All',
    allFolders: 'All folders',
    noFolder: 'No folder',
    noResults: 'No matches',
    empty: 'No notes yet',
    emptyHint: 'Create a note',
    selectHint: 'Pick a note on the left or create a new one.',
    newNote: 'New note',
    editNote: 'Edit note',
    contentPlaceholder: 'Content (Markdown)…',
    tagsPlaceholder: 'Tags (comma-separated)…',
    folderLabel: 'Folder',
    folderPlaceholder: 'Folder (optional)…',
    visibility: 'Visibility:',
    private: 'Private',
    shared: 'Shared',
    images: 'Images',
    addImage: 'Add image',
    removeImage: 'Remove image',
    insertImage: 'Insert into text',
    insertImageLabel: 'Insert image into the text',
    uploading: 'Uploading…',
    uploadingMany: 'Uploading {done}/{total}…',
    imageUploadingInline: 'Image is uploading…',
    // shown when trying to paste/drop an image into a not-yet-saved draft
    imageSaveFirst: 'Save the note first, then insert images.',
    imageTooLarge: 'Image is too large (max. 10 MB).',
    imageBadType: 'Only JPEG, PNG, WebP or GIF allowed.',
    imageUploadFailed: 'Upload failed.',
    imagesSomeFailed: '{count} image(s) could not be uploaded.',
    // write-error fallbacks (issue #96)
    saveFailed: 'Note could not be saved.',
    deleteFailed: 'Note could not be deleted.',
    imageDeleteFailed: 'Image could not be deleted.',
  },
  // Inbox tab in the todos view: all todos without a list (issue #69) —
  // Dashboard quick-add and the Android FAB create these.
  inbox: {
    headerTitle: 'HomeBase — Inbox',
    tab: 'Inbox', // tab label in the todos view
    empty: 'Inbox is empty',
    emptyHint: 'Add a task',
    quickAddPlaceholder: 'New task in the Inbox …',
  },
  time: {
    headerTitle: 'HomeBase — Time',
    title: 'Time tracking',
    running: 'Timer running',
    subDay: 'Day',
    subWeek: 'Week',
    subProjects: 'Projects',
    recordEntry: 'Add entry',
    startTimer: 'Start timer',
    noTimer: 'No timer active',
    startPrompt: 'What are you starting?',
    descPlaceholder: 'What are you working on? …',
    stop: 'Stop',
    start: 'Start',
    project: 'Project',
    projectsLabel: 'Projects',
    recentEntries: 'Recent entries',
    noDescription: 'no description',
    viewDetails: 'View details',
    open: 'Open', // labelled "open detail" button on the project card (#220)
    backToOverview: 'Back',
    detailTotal: 'Total',
    detailEntries: 'Entries',
    detailAvg: 'avg. per entry',
    perWeek: 'Per week',
    allEntries: 'All entries',
    detailEmptyHint: 'Start the timer for this project.',
    entryOne: 'entry',
    entryMany: 'entries',
    noProjects: 'No projects yet',
    noProjectsHint: 'Create a project in the top right',
    noProjectsConfigHint: 'Create a first project to track time', // shown on the main view's empty state (#86)
    firstProject: 'Create first project', // bootstrap action on the main view when there are no projects yet (#86)
    noEntries: 'No entries yet',
    partnerIdle: 'No timer active',
    startForPartner: 'For {name}', // start a timer on the partner's behalf
    // Cross-person actions always confirm first — via custom ConfirmDialog, not
    // window.confirm() (#125/#129). Both users may manage each other's entries.
    partnerActionTitle: 'Confirm action',
    confirmStartForPartner: 'Start timer for {name}?',
    confirmStopPartner: 'Stop {name}’s timer?',
    confirmEditPartner: 'Edit {name}’s entry?',
    confirmSplitPartner: 'Split {name}’s entry?',
    confirmDeletePartner: 'Delete {name}’s entry?',
    confirmCreateForPartner: 'Record an entry for {name}?',
    personLabel: 'Person', // manual-entry sheet: who the entry is recorded for
    emptyTitle: 'No time entries yet',
    emptyHint: 'Start a timer or add an entry',
    today: 'Today',
    yesterday: 'Yesterday',
    thisWeek: 'This week',
    legend: 'Legend',
    weekdays: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
    newProject: 'New project',
    editProject: 'Edit project',
    projectNamePlaceholder: 'Project name…',
    color: 'Colour',
    colorLabel: 'Colour', // rendered as "Colour {hex}"
    create: 'Create',
    active: 'Active',
    noActiveProjects: 'No active projects',
    archive: 'Archive',
    showArchived: 'Show archived',
    hideArchived: 'Hide archived',
    archivedSection: 'Archived',
    reactivate: 'Reactivate',
    endAfterStart: 'End must be after the start',
    editEntry: 'Edit entry',
    editRunning: 'Edit running timer',
    editRunningHint: 'Still running – the stop time is only set when you stop.',
    startInFuture: 'The start must not be in the future.',
    startLabel: 'Start',
    endLabel: 'End',
    date: 'Date',
    from: 'From',
    to: 'To',
    exportCsv: 'CSV export', // card title in Einstellungen → Zeiterfassung (#99)
    exportTitle: 'Export as CSV',
    exportHint: 'Optionally narrow by period and project. Leave empty to export all completed entries.',
    exportAllProjects: 'All projects',
    exportSubmit: 'Export',
    startFailed: 'Timer could not be started',
    stopFailed: 'Timer could not be stopped',
    saveFailed: 'Could not be saved',
    deleteFailed: 'Entry could not be deleted',
    archiveFailed: 'Project could not be updated',
    // Wochensoll & Forecast (#31)
    expectedEnd: 'Expected to finish at {time}', // shown at the running timer
    targetReached: 'Daily target reached',
    weekTargetTitle: 'Weekly target',
    targetsModalTitle: 'Configure weekly target',
    targetsModalHint: 'Weekly hours per person and project. Holidays, sick days and public holidays are credited to the default project.',
    hoursPerWeek: 'hrs/week',
    defaultColumn: 'Default',
    defaultRequired: 'Please choose a default project',
    invalidHours: 'Hours must be between 0 and 168',
    targetsFailed: 'Weekly target could not be saved',
    weekLeft: '{time} left', // remaining hours toward the weekly target
    weekOver: '+{time}', // weekly target exceeded
    todayLeft: '{time} left today',
    todayOver: '{time} over target today',
    credited: 'credited', // rendered as "{time} credited"
    // Eintrag splitten (#62)
    split: 'Split',
    splitTitle: 'Split entry',
    splitHint: 'Splits the entry into two at the cut time. A break stays as an unrecorded gap between the parts — afterwards part 2 can be edited as usual (e.g. a different project).',
    splitAtLabel: 'Cut time',
    breakLabel: 'Break in minutes (optional)',
    splitPart1: 'Part 1:',
    splitPart2: 'Part 2:',
    splitInvalidCut: 'The cut time must be between start and end',
    splitInvalidBreak: 'Enter the break in minutes (e.g. 30)',
    splitBreakTooLong: 'The break must end before the entry ends',
    splitFailed: 'Entry could not be split',
  },
  // Zentrale Einstellungen (#99): Sammelort für selten geänderte Konfiguration,
  // nach Unterseiten getrennt. Reine Frontend-Verlagerung bestehender Configs.
  settings: {
    title: 'Settings',
    // Haushalt-Unterseite (#100): editierbarer Haushaltsname.
    household: 'Household',
    householdNameTitle: 'Household name',
    householdNameHint: 'Shown in the sidebar. Both can change it.',
    householdNameLabel: 'Name',
    householdNameRequired: 'Please enter a name.',
    householdSaved: 'Saved',
    householdSaveFailed: 'Name could not be saved.',
    // Mitglieder-Übersicht (#100): read-only Liste der Haushaltsmitglieder.
    householdMembersTitle: 'Members',
    householdMembersHint: 'Everyone in this household.',
    // Konto-Unterseite (#100): Darstellung (Theme) + eigenes Passwort ändern.
    account: 'Account',
    accountSignedInAs: 'Signed in as',
    // Sprache (#6): pro Browser gewählte UI-Sprache (localStorage), sofort wirksam.
    languageTitle: 'Language',
    languageHint: 'Interface language. Applies on this device, takes effect instantly.',
    languageLabel: 'Display language',
    languageGerman: 'German',
    languageEnglish: 'English',
    languageSystem: 'System', // follow the browser language
    // Darstellung / Theme (#100): pro Person gespeichert (user_prefs), gilt app-weit.
    themeTitle: 'Appearance',
    themeHint: 'Light, dark or follow the system. Only for you, applies instantly.',
    themeLabel: 'Appearance',
    themeLight: 'Light',
    themeDark: 'Dark',
    themeSystem: 'System',
    themeSaveFailed: 'Setting could not be saved.',
    // Avatar-Farbe (Teil von #100): pro Person gewählte Farbe, haushaltsweit sichtbar
    // (der Partner sieht sie). Null/„Automatisch" = aus dem Benutzernamen abgeleitet.
    avatarTitle: 'Avatar colour',
    avatarHint: 'Your colour for avatars – also visible to your partner. Applies instantly.',
    avatarLabel: 'Colour',
    avatarAuto: 'Automatic',
    avatarAutoHint: 'Derived from your name',
    avatarSaveFailed: 'Colour could not be saved.',
    passwordTitle: 'Change password',
    passwordHint: 'To change it, enter your current password first.',
    passwordCurrent: 'Current password',
    passwordNew: 'New password',
    passwordConfirm: 'Repeat new password',
    passwordChange: 'Change password',
    passwordChanged: 'Password changed',
    passwordChangeFailed: 'Password could not be changed.',
    passwordMismatch: 'The new passwords do not match.',
    passwordTooShort: 'At least 8 characters.',
    passwordSameAsOld: 'New password must differ from the old one.',
    // Benachrichtigungen-Unterseite (#100): Telegram-Digest-Zeiten (morgens + abends).
    notifications: 'Notifications',
    // Morgen-Briefing („Guten Morgen"): heute fällig, überfällig, Inbox, Abwesenheiten, Kita.
    morningDigestTitle: 'Morning digest',
    morningDigestHint: 'Morning overview: due today, overdue, Inbox, absences and kita closures.',
    // Abend-Recap. Label/Speichern-/Hinweis-Texte teilen sich beide Digest-Karten.
    digestTitle: 'Evening digest',
    digestHint: 'Daily summary (done today, new in the Inbox, due tomorrow).',
    digestTimeLabel: 'Time',
    digestSaved: 'Saved',
    digestSaveFailed: 'Time could not be saved.',
    digestApplies: 'Changes take effect from the next scheduled digest.',
    digestDisabled: 'Telegram is not configured — the digest is currently inactive. You can still set the options.',
    // Pro-Digest an/aus + Inhalts-Auswahl (#182). Beide Digests teilen sich diese Texte.
    digestEnabledLabel: 'Digest active',
    digestSectionsLabel: 'Sections',
    digestSectionsHint: 'Which sections this digest shows.',
    digestSaveSections: 'Sections could not be saved.',
    // Abschnitts-Labels, indexiert über die Section-IDs vom Backend (availableSections).
    digestSections: {
      evening_done_today: 'Done today',
      evening_new_inbox: 'New in the Inbox',
      evening_due_tomorrow: 'Due tomorrow',
      evening_absent_tomorrow: 'Absent tomorrow (preview)',
      evening_kita_tomorrow: 'Kita closed tomorrow (preview)',
      morning_due_today: 'Due today',
      morning_overdue: 'Overdue',
      morning_inbox: 'Inbox',
      morning_absent: 'Absent today',
      morning_kita: 'Kita closed',
    } as Record<string, string>,
    // Wiederholungs-Planer-Uhrzeit (#100): tägliche Laufzeit des Sicherheitsnetzes für
    // wiederkehrende Aufgaben (rollt verpasste offene Wiederholungen vor).
    recurringTitle: 'Recurrence scheduler',
    recurringHint: 'Daily safety net: rolls missed, still-open recurring tasks forward to the current period.',
    recurringTimeLabel: 'Time for recurring tasks',
    recurringSaved: 'Saved',
    recurringSaveFailed: 'Time could not be saved.',
    recurringApplies: 'Changes take effect from the next scheduled run.',
    // Abwesenheit-Unterseite (#99): Kalender-Konfiguration im Hub.
    absence: 'Absences',
    absenceTitle: 'Allowances & calendar',
    absenceHint: 'Per person: leave allowance, carryover, federal state and part-time; plus household-wide closures and public holidays. Allowance and carryover apply per year.',
    time: 'Time tracking',
    projectsTitle: 'Projects',
    projectsHint: 'Create, rename, recolour or archive projects.',
    wochensollEdit: 'Edit weekly target',
    wochensollEmpty: 'No weekly target set yet.',
    exportOpen: 'Download CSV', // opens the export filter dialog
    perWeek: 'hrs/week', // rendered as "{n} hrs/week"
    defaultBadge: 'Default',
  },
  recipes: {
    headerTitle: 'HomeBase — Recipes',
    title: 'Recipes',
    count: 'recipes', // rendered as "{n} recipes"
    filterAll: 'All',
    emptyAll: 'No recipes yet',
    emptyCategory: 'No recipes in this category',
    emptyHint: 'Create a recipe',
    newRecipe: 'New recipe',
    editRecipe: 'Edit recipe',
    minutesAbbr: 'min',
    servingsAbbr: 'serv.',
    prep: 'Prep',
    cook: 'Cook time',
    totalTime: 'Total',
    prepLabel: 'Prep (min)',
    cookLabel: 'Cook time (min)',
    servings: 'Servings',
    lessServings: 'Fewer servings',
    moreServings: 'More servings',
    ingredients: 'Ingredients',
    preparation: 'Preparation',
    edit: 'Edit',
    export: 'Export',
    exportTitle: 'Export recipe',
    exportHint: 'Choose a format to download.',
    exportMarkdown: 'As Markdown',
    exportPdf: 'As PDF',
    exportFailed: 'Recipe could not be exported.',
    category: 'Category',
    addIngredient: '+ Ingredient',
    ingredientName: 'Ingredient',
    amount: 'Amount',
    unitAbbr: 'Unit',
    removeIngredient: 'Remove ingredient',
    addSection: '+ Section',
    sectionName: 'Section (optional)',
    removeSection: 'Remove section',
    newRecipeEyebrow: 'Recipe',
    addStep: '+ Step',
    stepPlaceholder: 'Describe the step…',
    removeStep: 'Remove step',
    // ingredient bulk/free-text editor (paste a whole list at once)
    editAsText: 'As text',
    editAsList: 'As list',
    ingredientsTextPlaceholder: 'One ingredient per line, e.g. „200 g flour"\n# Name starts a section (e.g. # Dough)',
    ingredientsTextHint: 'One ingredient per line (e.g. „200 g flour"). A line with „# Name" starts a section.',
    // recipe cover image (single)
    image: 'Image',
    addImage: 'Add image',
    changeImage: 'Change image',
    uploading: 'Uploading…',
    removeImage: 'Remove image',
    openImage: 'Open image',
    imageTooLarge: 'Image is too large (max. 10 MB).',
    imageBadType: 'Only JPEG, PNG, WebP or GIF allowed.',
    imageUploadFailed: 'Upload failed.',
    imageDeleteFailed: 'Image could not be deleted.',
    addToList: 'Ingredients to list',
    addedToList: 'ingredients added to the shopping list', // "{n} ingredients …"
    addedOne: 'ingredient added to the shopping list', // "1 ingredient …"
    added: 'added', // toast: "{n} added"
    merged: 'merged', // toast: "{n} merged"
    nothingToAdd: 'No new ingredients',
    pickerScaledTo: 'Amounts for {n} servings',
    viewList: 'View',
    backToRecipes: 'All recipes',
    pickerTitle: 'Ingredients to list',
    pickerSelected: '{n} of {total} selected', // ingredient-picker counter
    pickerAll: 'All',
    pickerNone: 'None',
    pickerTargetList: 'List',
    pickerAdd: 'add', // rendered as "{n} add"
    pickerNoList: 'Create a shopping list first.',
    // write-error fallbacks (issue #96)
    saveFailed: 'Recipe could not be saved.',
    deleteFailed: 'Recipe could not be deleted.',
    addToListFailed: 'Ingredients could not be added to the shopping list.',
    // keys match the RecipeCategory enum values from the backend
    categories: {
      BREAKFAST: 'Breakfast',
      DINNER: 'Dinner',
      SNACK: 'Snack',
      DESSERT: 'Dessert',
      DRINK: 'Drink',
    },
  },
  // HB-02 — meal planner (#218)
  wochenplan: {
    eyebrow: 'Meal planner',
    title: 'Meal plan',
    weekNav: 'Week navigation',
    prevWeek: 'Previous week',
    nextWeek: 'Next week',
    today: 'This week',
    // grid meal slots (independent of the recipe categories)
    slots: {
      BREAKFAST: 'Breakfast',
      LUNCH: 'Lunch',
      DINNER: 'Dinner',
    },
    addMeal: 'Plan a recipe',
    removeMeal: 'Remove from plan',
    // recipe picker (set/replace a slot)
    pickSearch: 'Search recipe…',
    pickEmpty: 'Create a recipe first.',
    pickNoMatch: 'No matching recipe.',
    pickConfirm: 'Apply',
    remove: 'Remove',
    // per-entry portions (#251) — picker stepper + cell badge
    servings: 'Servings',
    servingsShort: '{n} srv.', // cell badge, e.g. "4 srv."
    lessServings: 'Fewer servings',
    moreServings: 'More servings',
    saveFailed: 'Could not plan the recipe.',
    removeFailed: 'Could not remove the entry.',
    // "add to shopping list"
    addToShopping: 'Add to shopping list',
    addToShoppingTitle: 'Add this week’s ingredients to a list',
    addToShoppingSummary: '{items} ingredients from {dishes} planned dishes',
    addToShoppingFailed: 'Could not add the ingredients.',
    targetList: 'List',
    noList: 'Create a shopping list first.',
    addConfirm: 'Add',
    added: 'added', // toast: "{n} added"
    merged: 'merged', // toast: "{n} merged"
    nothingToAdd: 'No new ingredients',
  },
  abwesenheit: {
    headerTitle: 'HomeBase — Calendar',
    eyebrow: 'Family calendar',
    title: 'Calendar',
    layoutYear: 'Year',
    layoutMonth: 'Month',
    period: 'Period',
    today: 'Today',
    prevYear: 'Previous year',
    nextYear: 'Next year',
    yearNav: 'Year: {year}', // a11y group label for the year stepper (#133)
    prevMonth: 'Previous month',
    nextMonth: 'Next month',
    clickHint: 'Click a day to edit · hold ⇧ Shift to select a range',
    loadError: 'Calendar could not be loaded.',
    // summary card
    leaveRemaining: 'Leave left',
    taken: 'Taken',
    planned: 'Planned',
    allowance: 'Allowance',
    carryover: 'Carryover', // "+{n} Carryover"
    carryUntil: 'until', // "until {DD.MM.}"
    carryLost: 'expired', // "{n} expired"
    sick: 'Sick',
    childSick: 'Child sick',
    plannedTitle: 'of {used} of {total} days planned', // tooltip on the bar
    // legend
    legendUrlaub: 'Leave (per person)',
    legendKrank: 'Sick',
    legendKind: 'Child sick',
    legendFeiertag: 'Public holiday',
    legendTeilzeit: 'Part-time off',
    legendWeekend: 'Weekend',
    legendKita: 'Kita closure',
    // states / labels
    stateFeiertag: 'Public holiday',
    stateTeilzeit: 'Part-time off',
    stateWeekend: 'Weekend',
    stateWorkday: 'Workday',
    kitaShort: 'Kita',
    frei: 'off',
    // day editor
    work: 'Work',
    urlaub: 'Leave',
    krank: 'Sick',
    kindKrank: 'Child sick',
    fullDay: 'Full day',
    halfDay: 'Half day',
    forenoon: 'Morning (AM)',
    afternoon: 'Afternoon (PM)',
    noteHoliday: 'Public holiday', // "Public holiday · {name}"
    noteTeilzeit: 'Part-time · off anyway',
    noteWeekend: 'Weekend',
    kitaClosure: 'Kita closure',
    kitaForFamily: 'Applies to the whole family',
    occasionOptional: 'Occasion (optional)',
    occasionPlaceholder: 'e.g. Summer closure',
    occasion: 'Occasion',
    done: 'Done',
    kitaDefaultLabel: 'Kita closed',
    // range modal
    periodTitle: 'Enter a period',
    forWhom: 'For whom',
    kind: 'Type',
    from: 'From',
    to: 'To',
    deleteEntry: 'Delete entry',
    apply: 'Apply',
    // "Applies only to workdays … (≈ {n} days for {name}). For half days, click a single day."
    rangeHint: 'Applies only to workdays — weekends, public holidays and fixed days off are skipped',
    rangePreview: '≈ {n} days for {name}',
    rangeHalfHint: 'For half days, click a single day.',
    rangeClearHint: 'Removes all entries of the selected person(s) in the period.',
    bundesland: 'Federal state',
    yearAllowance: 'Annual allowance (days)',
    restLeave: 'Carried-over leave',
    expiresOn: '… expires on',
    kindKrankCap: 'Child-sick allowance',
    teilzeitTitle: 'Part-time · fixed days off',
    teilzeitEmpty: 'No rule — full time.',
    teilzeitFromLabel: 'from',
    teilzeitToLabel: 'to',
    addFreeDay: 'Add day off',
    weekdayFree: '. off', // "{Mon}. off"
    deleteRule: 'Delete rule',
    kitaSection: 'Kita closures',
    kitaSectionHint: 'Apply to the whole family — as a background marker in the calendar.',
    kitaEmpty: 'No closures recorded yet.',
    singleDay: 'Single day',
    add: 'Add',
    kitaRangeHint: 'Weekends are skipped automatically in a range.',
    // eigene Feiertage (#51)
    holidaySection: 'Custom holidays',
    holidaySectionHint: 'Apply to the whole family and recur every year (e.g. Christmas Eve, New Year’s Eve). Half day = half a day off.',
    holidayEmpty: 'No custom holidays recorded yet.',
    holidayDate: 'Date (yearly)',
    holidayDefaultLabel: 'Holiday',
    holidayRecurHint: 'The year in the date is ignored — only day and month count and apply every year.',
    delete: 'Delete',
    weekdaysShort: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'],
    // write-error fallbacks (issue #96)
    saveFailed: 'Entry could not be saved.',
    deleteFailed: 'Entry could not be deleted.',
    settingsFailed: 'Setting could not be saved.',
    kitaFailed: 'Kita closure could not be saved.',
    holidayFailed: 'Custom holiday could not be saved.',
    partTimeFailed: 'Part-time rule could not be saved.',
  },
}
