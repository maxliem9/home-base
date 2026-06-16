package com.homebase.android

import android.os.Bundle
import androidx.activity.compose.BackHandler
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
import com.homebase.android.ui.abwesenheit.AbsenceViewModel
import com.homebase.android.ui.abwesenheit.AbwesenheitScreen
import com.homebase.android.ui.aufgaben.AufgabenScreen
import com.homebase.android.ui.aufgaben.TodoViewModel
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
import com.homebase.android.ui.wochenplan.MealPlanScreen
import com.homebase.android.ui.wochenplan.MealPlanViewModel
import com.homebase.android.ui.settings.SettingsScreen
import com.homebase.android.ui.shopping.ShoppingScreen
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HomeBaseTheme
import com.homebase.android.ui.theme.ThemePref
import com.homebase.android.ui.time.TimeScreen
import com.homebase.android.ui.time.TimeViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

// AppCompatActivity (not a bare ComponentActivity) so AppCompatDelegate.setApplicationLocales(...)
// applies the in-app de/en switch on API 26–32 too: pre-API-33 AppCompat only recreates locales for
// AppCompatActivity hosts (it tracks them in sActivityDelegates) and only then does autoStoreLocales
// persist the choice; a pure ComponentActivity flips the chip but leaves the UI German. On API 33+ the
// framework LocaleManager handles it regardless. Compose `setContent {}` works unchanged inside an
// AppCompatActivity; the theme is a Theme.AppCompat descendant (see res/values/themes.xml) as AppCompat
// requires. (Was ComponentActivity.)
class MainActivity : AppCompatActivity() {

    private val container by lazy { (application as HomeBaseApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                when (val s = authState) {
                    AuthState.Loading -> Box(Modifier.fillMaxSize().background(Hb.paper))
                    AuthState.LoggedOut -> LoginGate()
                    is AuthState.LoggedIn -> MainScaffold(s.token)
                }
            }
        }
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
        }

        var route by rememberSaveable { mutableStateOf(HbRoute.HEUTE) }
        var drawerOpen by remember { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }

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

        val badges = mapOf(
            HbRoute.AUFGABEN to todoState.openCount,
            HbRoute.EINKAUF to shoppingState.openCount,
        )
        val dots = if (timeState.running != null) setOf(HbRoute.ZEIT) else emptySet()

        val openDrawer = { drawerOpen = true }

        // HB-09 (#239): the "Mehr" overflow sheet of the bottom nav.
        var moreOpen by remember { mutableStateOf(false) }

        BackHandler(enabled = drawerOpen) { drawerOpen = false }
        BackHandler(enabled = moreOpen) { moreOpen = false }

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
                        )
                        HbRoute.AUFGABEN -> AufgabenScreen(
                            viewModel = todoVm,
                            currentUser = currentUser,
                            householdUsers = householdUsers,
                            onOpenDrawer = openDrawer,
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
            return TodoViewModel(container.todoRepository, token) as T
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
            ) as T
        }
    }

    private fun notesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(container.notesRepository, token) as T
        }
    }

    private fun timeFactory(token: String, username: String?) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TimeViewModel(container.timeRepository, token, username) as T
        }
    }

    private fun recipesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RecipesViewModel(container.recipesRepository, token) as T
        }
    }

    private fun absenceFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AbsenceViewModel(container.absenceRepository, token) as T
        }
    }

    private fun mealPlanFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MealPlanViewModel(container.mealPlanRepository, token) as T
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
