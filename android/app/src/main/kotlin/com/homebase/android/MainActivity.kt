package com.homebase.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homebase.android.data.repository.AuthState
import com.homebase.android.notifications.ReminderWorker
import com.homebase.android.ui.abwesenheit.AbsenceViewModel
import com.homebase.android.ui.abwesenheit.AbwesenheitScreen
import com.homebase.android.ui.aufgaben.AufgabenScreen
import com.homebase.android.ui.aufgaben.TodoViewModel
import com.homebase.android.ui.aufgaben.TodosFocus
import com.homebase.android.ui.components.HbBottomNav
import com.homebase.android.ui.components.HbDrawerContent
import com.homebase.android.ui.components.HbMoreSheet
import com.homebase.android.ui.components.HbRoute
import com.homebase.android.ui.components.LocalAvatarHues
import com.homebase.android.ui.heute.HeuteScreen
import com.homebase.android.ui.login.LoginScreen
import com.homebase.android.ui.notes.NotesScreen
import com.homebase.android.ui.notes.NotesViewModel
import com.homebase.android.ui.recipes.RecipesScreen
import com.homebase.android.ui.recipes.RecipesViewModel
import com.homebase.android.ui.familienkalender.FamilienkalenderScreen
import com.homebase.android.ui.familienkalender.FamilienkalenderViewModel
import com.homebase.android.ui.wochenplan.MealPlanScreen
import com.homebase.android.ui.wochenplan.MealPlanViewModel
import com.homebase.android.ui.settings.SettingsScreen
import com.homebase.android.ui.settings.ShoppingCategoriesViewModel
import com.homebase.android.ui.shopping.ShoppingScreen
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HomeBaseTheme
import com.homebase.android.ui.theme.ThemePref
import com.homebase.android.ui.time.TimeScreen
import com.homebase.android.ui.time.TimeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A pending "open this todo" request from a tapped reminder notification (#429 Phase 2c follow-up).
 * [seq] is what makes two taps on the *same* todo two distinct requests — without it the second tap
 * would write an equal value into Compose state, and the keyed effects driving the deep-link would
 * never re-run.
 */
internal data class TodoDeepLink(val todoId: String, val seq: Int)

/**
 * How long a reminder deep-link waits for its todo to turn up in the list before it is dropped.
 * Generous, because a cold start out of the notification still has login + the first `/todos` fetch
 * ahead of it; the app stays fully usable meanwhile. Expiry matters: a link that never resolves
 * (todo deleted on the other device, stale notification) must not surface hours later.
 */
private const val DEEP_LINK_WAIT_MS = 15_000L

/** `onSaveInstanceState` key for the deep-link that was already handled. */
private const val STATE_HANDLED_TODO_ID = "handled_todo_id"

/** `onSaveInstanceState` key for the pending „Zurück führt einmalig auf Heute"-Umlenkung (#622). */
private const val STATE_BACK_TO_HEUTE = "back_to_heute"

/**
 * The deep-link todo id of a reminder-notification intent, or null for a plain app start. Top-level
 * and internal so the intent-shape contract with [com.homebase.android.notifications.ReminderWorker]
 * is unit-testable without launching the Activity.
 */
internal fun todoIdFrom(intent: Intent?): String? =
    intent?.takeIf { it.action == ReminderWorker.ACTION_OPEN_TODO }
        ?.getStringExtra(ReminderWorker.EXTRA_TODO_ID)
        ?.takeIf { it.isNotEmpty() }

// AppCompatActivity (not a bare ComponentActivity) so AppCompatDelegate.setApplicationLocales(...)
// applies the in-app de/en switch on API 26–32 too: pre-API-33 AppCompat only recreates locales for
// AppCompatActivity hosts (it tracks them in sActivityDelegates) and only then does autoStoreLocales
// persist the choice; a pure ComponentActivity flips the chip but leaves the UI German. On API 33+ the
// framework LocaleManager handles it regardless. Compose `setContent {}` works unchanged inside an
// AppCompatActivity; the theme is a Theme.AppCompat descendant (see res/values/themes.xml) as AppCompat
// requires. (Was ComponentActivity.)
class MainActivity : AppCompatActivity() {

    private val container by lazy { (application as HomeBaseApplication).container }

    /**
     * The pending reminder deep-link (see [ReminderWorker]), or null. Compose state so a tap on an
     * already-running app (`onNewIntent`) lands too; the logged-in scaffold navigates to Aufgaben,
     * opens the todo and clears it. A logged-out start just parks it here until the session exists.
     */
    private var deepLink by mutableStateOf<TodoDeepLink?>(null)

    /**
     * How many deep-links this Activity instance has taken — the [TodoDeepLink.seq] source. Two taps
     * on the *same* todo must be two distinct values, otherwise the second one wouldn't change state
     * and the scaffold's keyed effect would never re-run.
     */
    private var deepLinkSeq = 0

    /**
     * The deep-link already handled, kept across recreation *and* process death. Neither
     * `removeExtra` nor `setIntent` reliably survives both — the launching intent is re-delivered
     * verbatim after a process kill, which would re-open the todo days later — so "handled" is
     * tracked explicitly rather than by mutating the intent.
     */
    private var handledTodoId: String? = null

    /**
     * True while a **Cold-Start aus der Notification** still owes the user one back press onto
     * „Heute" (#622). Tapping a reminder with the app not running creates the task *at* the deep-link
     * target, so there is nothing behind it: closing the edit sheet and pressing Zurück would leave
     * the app. A synthetic back stack ([androidx.core.app.TaskStackBuilder]) is no help here — HomeBase
     * is a single-Activity app that routes over `route`-State, so a synthetic stack would only put a
     * second MainActivity behind this one. Instead the first system back is redirected in-app, see
     * [DeepLinkBackToHeute]. Only armed when this instance was *created* by the tap — a tap on the
     * already-running app (`onNewIntent`) is left alone: we only compensate for an entry we made.
     */
    private var backToHeutePending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledTodoId = savedInstanceState?.getString(STATE_HANDLED_TODO_ID)
        backToHeutePending = savedInstanceState?.getBoolean(STATE_BACK_TO_HEUTE) ?: false
        todoIdFrom(intent)?.takeIf { it != handledTodoId }?.let {
            deepLink = TodoDeepLink(it, deepLinkSeq++)
            // A *fresh* instance carrying the tap (savedInstanceState == null) ⇒ the task was created
            // at the deep-link target, nothing behind it (#622). Guarded on the bundle rather than on
            // the intent alone: a rotation inside the 15 s deep-link window re-runs this branch with
            // the same intent and would otherwise re-arm a redirect the user has already spent —
            // there the saved value above is the truth. (`isTaskRoot` would not tell the two apart:
            // in a single-Activity app it is true in either case.)
            if (savedInstanceState == null) backToHeutePending = true
        }
        enableEdgeToEdge()
        setContent {
            // Resolve the per-user theme choice (#244): the stored light|dark|system pref, with
            // `system` following the OS. Defaults to system until the pref loads (see MainScaffold),
            // so the UI never blocks on the network and unreachable backends fall back gracefully.
            val themePref by container.themeRepository.theme.collectAsStateWithLifecycle()
            val dark = when (themePref) {
                ThemePref.LIGHT -> false
                ThemePref.DARK -> true
                ThemePref.SYSTEM -> isSystemInDarkTheme()
            }
            // Keep the system-bar icon contrast in sync with the *resolved* theme, not just the OS
            // setting: enableEdgeToEdge() derives icon appearance from the OS, so forcing a theme
            // opposite the system (e.g. Dark while the OS is Light) would otherwise leave the status-
            // and navigation-bar icons low-contrast. Re-applied whenever `dark` changes. (#244 review.)
            DisposableEffect(dark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                onDispose { }
            }
            HomeBaseTheme(dark = dark) {
                val authState by container.authRepository.state.collectAsStateWithLifecycle()

                LogoutTeardownEffect(
                    loggedIn = authState is AuthState.LoggedIn,
                    viewModelStore = viewModelStore,
                )

                // Cancel any scheduled local reminders on logout (#429 Phase 2c): they belong to the
                // session and would otherwise fire for a logged-out device. The next login reschedules
                // from the freshly loaded todo list.
                val loggedIn = authState is AuthState.LoggedIn
                LaunchedEffect(loggedIn) {
                    if (!loggedIn) container.reminderScheduler.cancelAll()
                }

                when (val s = authState) {
                    AuthState.Loading -> Box(Modifier.fillMaxSize().background(Hb.paper))
                    AuthState.LoggedOut -> LoginGate()
                    is AuthState.LoggedIn -> MainScaffold(s.token)
                }
            }
        }
    }

    /**
     * A reminder tap while the app is already running: `launchMode="singleTop"` delivers the content
     * intent here instead of creating a second MainActivity, so the deep-link has to be picked up
     * from the new intent (the original one from onCreate never changes on its own). A repeat tap on
     * an already-handled todo is honoured again — hence the fresh [TodoDeepLink.seq].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        todoIdFrom(intent)?.let {
            handledTodoId = null
            deepLink = TodoDeepLink(it, deepLinkSeq++)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_HANDLED_TODO_ID, handledTodoId)
        outState.putBoolean(STATE_BACK_TO_HEUTE, backToHeutePending)
    }

    /**
     * Deep-link done — either opened, or given up on after [DEEP_LINK_WAIT_MS]. Only clears [link]
     * itself, so a newer tap that arrived meanwhile isn't dropped along with it.
     */
    private fun consumeDeepLink(link: TodoDeepLink) {
        if (deepLink != link) return
        handledTodoId = link.todoId
        deepLink = null
    }

    @Composable
    private fun LoginGate() {
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val loginFailed = stringResource(R.string.login_failed)

        LoginScreen(
            isLoading = isLoading,
            error = error,
            onLogin = { username, password ->
                isLoading = true
                error = null
                scope.launch {
                    container.authRepository.login(username, password)
                        .onFailure { e ->
                            isLoading = false
                            error = e.message ?: loginFailed
                        }
                        .onSuccess { isLoading = false }
                }
            },
        )
    }

    @Composable
    private fun MainScaffold(token: String) {
        val currentUser = remember(token) { usernameFromToken(token) }

        // All five ViewModels are hoisted here so the drawer can show live badges, screens stay
        // alive across navigation, and the dashboard can read several domains at once.
        val todoVm: TodoViewModel = viewModel(key = "todo-$token", factory = todoFactory(token))
        val shoppingVm: ShoppingViewModel = viewModel(key = "shopping-$token", factory = shoppingFactory(token))
        val notesVm: NotesViewModel = viewModel(key = "notes-$token", factory = notesFactory(token))
        val timeVm: TimeViewModel = viewModel(key = "time-$token", factory = timeFactory(token, currentUser))
        val recipesVm: RecipesViewModel = viewModel(key = "recipes-$token", factory = recipesFactory(token))
        val absenceVm: AbsenceViewModel = viewModel(key = "absence-$token", factory = absenceFactory(token))
        val mealPlanVm: MealPlanViewModel = viewModel(key = "mealplan-$token", factory = mealPlanFactory(token))
        val calendarVm: FamilienkalenderViewModel = viewModel(key = "calendar-$token", factory = calendarFactory(token))
        val shoppingCategoriesVm: ShoppingCategoriesViewModel =
            viewModel(key = "shopping-categories-$token", factory = shoppingCategoriesFactory(token))

        // A socket can be silently killed while the app is backgrounded (Doze, mobile-network change,
        // backend restart). OkHttp does not reconnect on its own, so on every return to the foreground
        // we ask each channel to re-open if it dropped — the clients no-op when already connected.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            todoVm.ensureConnected()
            shoppingVm.ensureConnected()
            notesVm.ensureConnected()
            timeVm.ensureConnected()
            recipesVm.ensureConnected()
            absenceVm.ensureConnected()
            mealPlanVm.ensureConnected()
            calendarVm.ensureConnected()
        }

        var route by rememberSaveable { mutableStateOf(HbRoute.HEUTE) }
        var drawerOpen by remember { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        // Pending deep-link from a dashboard stat tile into the tasks view (#255/#256): set together
        // with route = AUFGABEN, consumed (cleared) by AufgabenScreen once it has selected the tab.
        // Plain navigation to Aufgaben (drawer / bottom bar / "Mehr") leaves this null → default tab.
        var pendingTodosFocus by remember { mutableStateOf<TodosFocus?>(null) }
        val goAufgaben: (TodosFocus) -> Unit = { focus -> pendingTodosFocus = focus; route = HbRoute.AUFGABEN }

        // Load the per-user UI theme once we're authenticated (#244): /user-prefs needs the token,
        // so it can't be read at cold start. Best-effort — ThemeRepository keeps the system default
        // if the read fails, and the theme StateFlow (observed in setContent) recolours the app.
        LaunchedEffect(token) {
            container.themeRepository.load()
        }

        // Mutable so the settings Haushalt subpage can update the live sidebar brand (#101).
        // Empty string until GET /config resolves — avoids flashing a hardcoded household name.
        var household by rememberSaveable(token) { mutableStateOf("") }
        LaunchedEffect(token) {
            container.configRepository.getHouseholdName().onSuccess { household = it }
        }

        // Household members for the assignee chips; empty until GET /users resolves.
        val householdUsers by produceState(initialValue = emptyList<String>()) {
            container.configRepository.getUsers().onSuccess { if (it.isNotEmpty()) value = it }
        }

        // Per-user avatar-hue overrides (Teil von #100), from the household-visible roster.
        // Provided app-wide via LocalAvatarHues so every HbAvatar shows the colours members
        // picked on web. Empty until GET /users resolves → everyone "automatic" (derived).
        // Held in mutable state (not produceState) + a reload lambda so the Konto colour picker
        // (#242) can refresh it after a successful PUT and every avatar recolours without a restart.
        var avatarHues by remember(token) { mutableStateOf(emptyMap<String, Int>()) }
        val reloadAvatarHues: suspend () -> Unit = {
            container.configRepository.getAvatarHues().onSuccess { avatarHues = it }
        }
        LaunchedEffect(token) { reloadAvatarHues() }
        // The roster only refetches on token change and after the user saves their OWN colour
        // (#242/#246); a PARTNER's colour change would otherwise stay invisible until a cold start.
        // Mirror the web's focus-refetch (`useAvatarHues`): on every return to the foreground, re-read
        // the shared hue map (one cheap GET /users) so a partner's new colour shows up. (#253)
        val avatarHueScope = rememberCoroutineScope()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            avatarHueScope.launch { reloadAvatarHues() }
        }

        val todoState by todoVm.uiState.collectAsState()
        val shoppingState by shoppingVm.uiState.collectAsState()
        val timeState by timeVm.uiState.collectAsState()

        // Local reminder notifications (#429 Phase 2c): reconcile WorkManager jobs whenever the todo
        // list changes (cold load, WS reload, an edit). The scheduler is pure-logic + idempotent, so
        // re-running on every list snapshot just (re)computes the same plan; date-only todos are
        // skipped by the planner. Keyed on the list itself so it also re-fires after the cold load.
        LaunchedEffect(todoState.todos) {
            container.reminderScheduler.sync(todoState.todos)
        }

        // Android 13+: ask for POST_NOTIFICATIONS once we're logged in (the reminder is the only thing
        // that posts). Best-effort — a denial degrades gracefully: jobs still run, the worker just
        // skips the notify(). Pre-API-33 needs no runtime grant.
        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* granted-or-not handled gracefully in the worker */ }
        LaunchedEffect(token) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val badges = mapOf(
            HbRoute.AUFGABEN to todoState.openCount,
            HbRoute.EINKAUF to shoppingState.openCount,
        )
        val dots = if (timeState.running != null) setOf(HbRoute.ZEIT) else emptySet()

        val openDrawer = { drawerOpen = true }

        // HB-09 (#239): the "Mehr" overflow sheet of the bottom nav.
        var moreOpen by remember { mutableStateOf(false) }

        // Reminder-notification deep-link: navigate to Aufgaben so the screen below can open the
        // todo's edit sheet. Any overlay covering it (drawer, "Mehr", settings) is closed first —
        // the tap came from outside the app, so whatever was open is stale context.
        //
        // The expiry lives HERE, not in AufgabenScreen: that screen is only composed while its route
        // is active, so a user who navigates away mid-wait would cancel the screen's timeout and
        // leave the link pending forever — to then have the sheet pop up on a later, unrelated visit.
        // Owned by the scaffold, the link either resolves or expires no matter where the user goes,
        // and re-entering Aufgaben before it expires simply retries.
        val link = deepLink
        LaunchedEffect(link) {
            if (link == null) return@LaunchedEffect
            drawerOpen = false
            moreOpen = false
            settingsOpen = false
            route = HbRoute.AUFGABEN
            delay(DEEP_LINK_WAIT_MS)
            consumeDeepLink(link)
        }

        BackHandler(enabled = drawerOpen) { drawerOpen = false }
        BackHandler(enabled = moreOpen) { moreOpen = false }

        // Cold-Start aus einer Reminder-Notification: die erste Zurück-Geste landet auf „Heute"
        // statt aus der App zu führen (#622). Muss VOR dem Screen komponiert werden, damit dessen
        // eigene Handler (Edit-Sheet, Overlays) den Zurück-Druck zuerst bekommen.
        DeepLinkBackToHeute(
            pending = backToHeutePending,
            route = route,
            overlayOpen = drawerOpen || moreOpen || settingsOpen,
            onBackToHeute = { route = HbRoute.HEUTE },
            onDone = { backToHeutePending = false },
        )

        // Make the per-user avatar-hue overrides available to every HbAvatar below (Teil von #100).
        CompositionLocalProvider(LocalAvatarHues provides avatarHues) {
        Box(Modifier.fillMaxSize().background(Hb.paper)) {
            // HB-09 (#239): the active screen sits above a persistent bottom tab bar. The
            // screen takes the remaining height (weight 1) so its content never hides behind
            // the bar; overlays (scrim/drawer/settings/Mehr-sheet) are siblings below and
            // cover the bar too.
            Column(Modifier.fillMaxSize()) {
                // Consume the navigationBars inset for this screen container so the FAB and toast
                // (HbFab/HbToast inside the active screen) — which each apply navigationBarsPadding()
                // — resolve it to ~0 and sit flush above the bar, instead of double-counting it and
                // floating a full nav-inset too high (a visible gap on every FAB screen + toast).
                // HbBottomNav is a SIBLING below in this Column, OUTSIDE this Box, so it still
                // receives the full inset and stays clear of the system navigation bar. (#239 review.)
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
                    when (route) {
                        HbRoute.HEUTE -> HeuteScreen(
                            todoVm = todoVm,
                            shoppingVm = shoppingVm,
                            timeVm = timeVm,
                            currentUser = currentUser,
                            onOpenDrawer = openDrawer,
                            onNavigate = { route = it },
                            // Stat tiles deep-link into the matching tasks tab (#255/#256).
                            onOpenTodos = goAufgaben,
                        )
                        HbRoute.AUFGABEN -> AufgabenScreen(
                            viewModel = todoVm,
                            currentUser = currentUser,
                            householdUsers = householdUsers,
                            onOpenDrawer = openDrawer,
                            // Deep-link target from a dashboard tile; cleared once the tab is selected.
                            initialFocus = pendingTodosFocus,
                            onFocusConsumed = { pendingTodosFocus = null },
                            // Deep-link from a reminder notification: open this todo's edit sheet.
                            // The seq makes a repeat tap on the same todo a new request (#620).
                            openTodoId = link?.todoId,
                            openTodoSeq = link?.seq ?: 0,
                            onOpenTodoConsumed = { link?.let { consumeDeepLink(it) } },
                        )
                        HbRoute.EINKAUF -> ShoppingScreen(
                            viewModel = shoppingVm,
                            currentUser = currentUser,
                            onOpenDrawer = openDrawer,
                        )
                        HbRoute.NOTIZEN -> NotesScreen(
                            viewModel = notesVm,
                            currentUser = currentUser,
                            onOpenDrawer = openDrawer,
                        )
                        HbRoute.ZEIT -> TimeScreen(
                            viewModel = timeVm,
                            currentUser = currentUser,
                            onOpenDrawer = openDrawer,
                            // Suppress the tracker's shared error toast while the settings →
                            // Zeiterfassung overlay (same TimeViewModel) renders its own on top (#193).
                            settingsOpen = settingsOpen,
                        )
                        HbRoute.ABWESENHEIT -> AbwesenheitScreen(
                            viewModel = absenceVm,
                            onOpenDrawer = openDrawer,
                        )
                        HbRoute.REZEPTE -> RecipesScreen(
                            viewModel = recipesVm,
                            shoppingViewModel = shoppingVm,
                            onOpenDrawer = openDrawer,
                        )
                        HbRoute.WOCHENPLAN -> MealPlanScreen(
                            viewModel = mealPlanVm,
                            onOpenDrawer = openDrawer,
                        )
                        HbRoute.FAMILIENKALENDER -> FamilienkalenderScreen(
                            viewModel = calendarVm,
                            onOpenDrawer = openDrawer,
                        )
                    }
                }
                HbBottomNav(
                    active = route,
                    badges = badges,
                    dots = dots,
                    onSelect = { route = it },
                    onMore = { moreOpen = true },
                )
            }

            // Scrim
            AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Hb.scrim)
                        .clickable(interactionSource = interaction, indication = null) { drawerOpen = false },
                )
            }
            // Drawer
            AnimatedVisibility(
                visible = drawerOpen,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
            ) {
                HbDrawerContent(
                    active = route,
                    householdName = household,
                    currentUser = currentUser,
                    badges = badges,
                    dots = dots,
                    onSelect = { route = it; drawerOpen = false },
                    onOpenSettings = { settingsOpen = true; drawerOpen = false },
                )
            }

            // HB-09 (#239) — "Mehr" overflow sheet: the bottom-nav areas that don't fit in
            // the bar. Selecting one navigates and dismisses; the bar's "Mehr" item stays
            // highlighted while one of these areas is active.
            if (moreOpen) {
                HbMoreSheet(
                    active = route,
                    badges = badges,
                    dots = dots,
                    onSelect = { route = it; moreOpen = false },
                    onDismiss = { moreOpen = false },
                )
            }

            // Central settings (#101) — full-screen overlay above everything; it owns its own
            // back handling (subpage → list → close).
            if (settingsOpen) {
                SettingsScreen(
                    configRepository = container.configRepository,
                    authRepository = container.authRepository,
                    themeRepository = container.themeRepository,
                    timeViewModel = timeVm,
                    absenceViewModel = absenceVm,
                    shoppingCategoriesViewModel = shoppingCategoriesVm,
                    currentUser = currentUser,
                    householdName = household,
                    onHouseholdRenamed = { household = it },
                    // Lets the Konto avatar-colour picker (#242) refresh the shared hue map after a
                    // successful PUT, so the caller's avatar recolours app-wide without a restart.
                    onAvatarColorChanged = reloadAvatarHues,
                    // Logout (#141): close the overlay; AuthRepository.logout() flips auth state to
                    // LoggedOut, so the top-level `when` swaps MainScaffold → LoginGate on its own.
                    onLoggedOut = { settingsOpen = false },
                    onClose = { settingsOpen = false },
                )
            }
        }
        }
    }

    // --- ViewModel factories ---

    private fun todoFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TodoViewModel(
                container.todoRepository,
                container.configRepository,
                token,
                snapshotStore = container.todoSnapshotStore,
                errorText = container.errorText,
            ) as T
        }
    }

    private fun shoppingFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ShoppingViewModel(
                repository = container.shoppingRepository,
                token = token,
                pendingStore = container.shoppingPendingStore,
                networkAvailable = container.connectivityObserver.onAvailable,
                viewPrefs = container.shoppingViewPrefs,
                snapshotStore = container.shoppingSnapshotStore,
                errorText = container.errorText,
            ) as T
        }
    }

    private fun notesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(
                repository = container.notesRepository,
                token = token,
                pendingStore = container.notesPendingStore,
                networkAvailable = container.connectivityObserver.onAvailable,
                snapshotStore = container.notesSnapshotStore,
                errorText = container.errorText,
            ) as T
        }
    }

    private fun timeFactory(token: String, username: String?) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TimeViewModel(container.timeRepository, token, username, snapshotStore = container.timeSnapshotStore, errorText = container.errorText) as T
        }
    }

    private fun recipesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RecipesViewModel(container.recipesRepository, token, snapshotStore = container.recipesSnapshotStore, errorText = container.errorText) as T
        }
    }

    private fun absenceFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AbsenceViewModel(container.absenceRepository, token, snapshotStore = container.absenceSnapshotStore, errorText = container.errorText) as T
        }
    }

    private fun mealPlanFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MealPlanViewModel(container.mealPlanRepository, token, snapshotStore = container.mealPlanSnapshotStore, errorText = container.errorText) as T
        }
    }

    private fun calendarFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return FamilienkalenderViewModel(container.calendarRepository, token, snapshotStore = container.calendarSnapshotStore, errorText = container.errorText) as T
        }
    }

    private fun shoppingCategoriesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ShoppingCategoriesViewModel(container.shoppingCategoriesRepository, token, errorText = container.errorText) as T
        }
    }

    /** Reads the `username` claim from the JWT payload (drives greeting + delete permissions). */
    private fun usernameFromToken(token: String): String? = runCatching {
        val payload = token.split(".")[1]
        val decoded = String(
            android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            ),
        )
        JSONObject(decoded).optString("username").takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * Session teardown on logout (#180): the six domain ViewModels are Activity-scoped and keyed by the
 * JWT, so leaving MainScaffold does NOT clear them — their OkHttp WebSockets would linger as zombies
 * (reconnect loops) until the Activity dies or a re-login replaced them under a new token key.
 * Clearing the Activity [ViewModelStore] the moment we leave the logged-in state runs each
 * ViewModel's `onCleared()` → `disconnect()`, closing every socket at once. This is Android's analog
 * of the web closing its sockets on unmount, and it also covers re-login: the next session always
 * builds fresh ViewModels (new token key) with no socket outliving the identity change.
 *
 * Keyed on [loggedIn] so the effect re-runs only on a genuine logged-in↔logged-out flip. The guard
 * `if (!loggedIn)` is what keeps a cold start that is *already* `LoggedIn` from clobbering its own
 * freshly built session — the effect runs once on first composition but must no-op while logged in.
 * Extracted from MainActivity so it can be driven over a real composition in tests (#192).
 */
@Composable
internal fun LogoutTeardownEffect(loggedIn: Boolean, viewModelStore: ViewModelStore) {
    LaunchedEffect(loggedIn) {
        if (!loggedIn) viewModelStore.clear()
    }
}

/**
 * The one-shot „Zurück führt auf Heute"-Umlenkung nach einem Notification-Cold-Start (#622).
 *
 * Nach einem Tap auf die Reminder-Notification bei **nicht laufender App** entsteht der Task direkt
 * am Deep-Link-Ziel (Aufgaben + Edit-Sheet). Hinter ihm liegt nichts, das System-Zurück würde die App
 * verlassen. Ein synthetischer Back-Stack hilft nicht: HomeBase hat genau **eine** Activity und
 * navigiert über `route`-State — ein `TaskStackBuilder` würde nur eine zweite MainActivity
 * dahinterstapeln. Also wird der erste Zurück-Druck stattdessen **in-app** umgelenkt.
 *
 * Genau einmal: sobald „Heute" **wieder** erreicht ist — egal ob über diese Umlenkung oder weil der
 * Nutzer selbst dorthin navigiert hat — meldet [onDone] die Schuld als beglichen und Zurück verlässt
 * die App wieder normal. „Wieder" ist hier der Knackpunkt: die Route startet auf `HEUTE` und der
 * Deep-Link-Effekt schaltet erst *danach* auf Aufgaben. Würde schon das bloße „Route ist HEUTE" die
 * Schuld tilgen, wäre sie beim Cold-Start beglichen, bevor der Nutzer den Aufgaben-Screen überhaupt
 * sieht — der Handler ginge nie an. Deshalb zählt erst der Übergang: einmal weg von „Heute", dann
 * zurück.
 *
 * Der Handler ist deaktiviert, solange ein Overlay (Drawer/„Mehr"/Einstellungen) offen ist: eine
 * unsichtbare Umlenkung hinter einem Overlay wäre schlechte UX, und Drawer/„Mehr" registrieren ihre
 * Handler zudem *früher* und verlören sonst das Prioritätsrennen. (Einstellungen und das
 * Todo-Edit-Sheet werden nach diesem Handler komponiert und gewinnen ohnehin.)
 *
 * Ausgelagert aus MainActivity, damit die Regel über eine echte Composition testbar ist.
 */
@Composable
internal fun DeepLinkBackToHeute(
    pending: Boolean,
    route: HbRoute,
    overlayOpen: Boolean,
    onBackToHeute: () -> Unit,
    onDone: () -> Unit,
) {
    // Überlebt die Rotation mit, sonst begänne die Übergangserkennung von vorn und die Schuld bliebe
    // für den Rest der Sitzung offen.
    var leftHeute by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pending, route) {
        if (!pending) return@LaunchedEffect
        if (route != HbRoute.HEUTE) leftHeute = true else if (leftHeute) onDone()
    }
    // Auf „Heute" ist nichts umzulenken — ein dort verschluckter Zurück-Druck wäre wirkungslos und
    // fühlte sich wie eine hängende App an.
    BackHandler(enabled = pending && route != HbRoute.HEUTE && !overlayOpen) { onBackToHeute() }
}
