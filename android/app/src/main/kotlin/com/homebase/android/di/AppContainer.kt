package com.homebase.android.di

import android.content.Context
import com.homebase.android.BuildConfig
import com.homebase.android.data.api.AuthInterceptor
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.CalendarRepository
import com.homebase.android.data.repository.MealPlanRepository
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.repository.ThemeRepository
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.abwesenheit.AbsenceSnapshot
import com.homebase.android.data.aufgaben.TodoSnapshot
import com.homebase.android.data.recipes.RecipesSnapshot
import com.homebase.android.data.wochenplan.MealPlanSnapshot
import com.homebase.android.data.cache.SharedPrefsSnapshotStore
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.notes.NotesPendingStore
import com.homebase.android.data.notes.NotesSnapshot
import com.homebase.android.data.notes.SharedPrefsNotesPendingStore
import com.homebase.android.data.shopping.ConnectivityObserver
import com.homebase.android.data.shopping.SharedPrefsShoppingPendingStore
import com.homebase.android.data.shopping.ShoppingPendingStore
import com.homebase.android.data.shopping.ShoppingSnapshot
import com.homebase.android.data.shopping.ShoppingViewPrefs
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.data.websocket.EventWebSocketClient
import com.homebase.android.data.websocket.MealPlanWebSocketClient
import com.homebase.android.data.websocket.NotesWebSocketClient
import com.homebase.android.data.websocket.OkHttp
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.homebase.android.notifications.ReminderScheduler
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Duration

class AppContainer(context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Token holder written once after login; read by the OkHttp interceptor on each request.
    // Accessed from background threads (OkHttp dispatcher) — volatile is sufficient.
    @Volatile var currentToken: String? = null

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { currentToken })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        // Keep WebSockets alive and surface dead connections quickly: OkHttp pings every 30s and
        // reports a failure (→ client reconnect) when a pong is missed. Harmless for REST calls.
        .pingInterval(Duration.ofSeconds(30))
        .build()

    private val api: HomeBaseApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(HomeBaseApi::class.java)

    val authRepository = AuthRepository(context, api) { token -> currentToken = token }

    val configRepository = ConfigRepository(api)

    /** Per-user UI-theme preference (#244): light/dark/system, persisted in user_prefs. */
    val themeRepository = ThemeRepository(api)

    val todoRepository = TodoRepository(
        api = api,
        wsClient = TodoWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /**
     * Durable "last-known lists + todos" cache for the tasks screen (#520), so a cold start with no
     * connection shows the previous state instead of an empty screen. Read-side twin of the shopping
     * cache (#517); own prefs file, best-effort writes.
     */
    val todoSnapshotStore: SnapshotStore<TodoSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(TodoSnapshot::class.java),
        prefsName = "homebase_todos_cache",
    )

    /**
     * Schedules device-local reminder notifications for timed todos (#429 Phase 2c). MainActivity
     * feeds it the current todo list on every change (WS reload / edit / app start); it reconciles the
     * WorkManager jobs. App-scoped (uses the Application context) so the work outlives any one screen.
     */
    val reminderScheduler = ReminderScheduler(context.applicationContext)

    val shoppingRepository = ShoppingRepository(
        api = api,
        wsClient = ShoppingWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /**
     * Dedicated repo for the Einkaufskategorien settings subpage (#411). It shares the same REST API
     * but gets its OWN shopping WebSocket instance: the "shopping" channel's event flow is a
     * single-consumer channel and connect/disconnect is per-socket, so reusing [shoppingRepository]
     * would make the settings VM and the shopping screen VM fight over events + the connection
     * lifecycle (same reason the Wochenplan owns a separate recipe socket). A separate instance keeps
     * the settings subpage self-contained.
     */
    val shoppingCategoriesRepository = ShoppingRepository(
        api = api,
        wsClient = ShoppingWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /** Durable backing store for the shopping offline check-off queue (issue #170). */
    val shoppingPendingStore: ShoppingPendingStore = SharedPrefsShoppingPendingStore(context, moshi)

    /**
     * Durable "last-known lists + items" cache for the shopping screen (#517), so a cold start with
     * no connection shows the previous state instead of an empty screen. Read-side twin of the
     * offline check-off queue; own prefs file, best-effort writes.
     */
    val shoppingSnapshotStore: SnapshotStore<ShoppingSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(ShoppingSnapshot::class.java),
        prefsName = "homebase_shopping_cache",
    )

    /** Persisted list/tile view choice for the shopping screen (#446). */
    val shoppingViewPrefs: ShoppingViewPrefs = ShoppingViewPrefs(context)

    /** Device network-available signal — retry trigger for the offline check-off queue. */
    val connectivityObserver = ConnectivityObserver(context)

    val notesRepository = NotesRepository(
        api = api,
        wsClient = NotesWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /** Durable backing store for the notes offline auto-save queue (issue #323). */
    val notesPendingStore: NotesPendingStore = SharedPrefsNotesPendingStore(context, moshi)

    /**
     * Durable "last-known notes" cache for the notes screen (#520), so a cold start with no connection
     * shows the previous notes instead of an empty screen. Read-side twin of the offline auto-save
     * queue; own prefs file, best-effort writes.
     */
    val notesSnapshotStore: SnapshotStore<NotesSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(NotesSnapshot::class.java),
        prefsName = "homebase_notes_cache",
    )

    val timeRepository = TimeRepository(
        api = api,
        wsClient = TimeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val recipesRepository = RecipesRepository(
        api = api,
        wsClient = RecipeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /** Durable "last-known recipes" cache (#520); own prefs file, best-effort writes. */
    val recipesSnapshotStore: SnapshotStore<RecipesSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(RecipesSnapshot::class.java),
        prefsName = "homebase_recipes_cache",
    )

    val absenceRepository = AbsenceRepository(
        api = api,
        wsClient = AbsenceWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /** Durable "last-known planner snapshot" cache for the Familienkalender (#520). */
    val absenceSnapshotStore: SnapshotStore<AbsenceSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(AbsenceSnapshot::class.java),
        prefsName = "homebase_absence_cache",
    )

    // Wochenplan (#250): its own meal-plan socket + a dedicated recipe socket (a recipe delete
    // cascades plan rows but only broadcasts on the recipes channel — the planner watches both).
    val mealPlanRepository = MealPlanRepository(
        api = api,
        mealPlanWsClient = MealPlanWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
        recipeWsClient = RecipeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    /** Durable "last-known plan" cache for the Wochenplan (#520); own prefs file, best-effort writes. */
    val mealPlanSnapshotStore: SnapshotStore<MealPlanSnapshot> = SharedPrefsSnapshotStore(
        context = context,
        adapter = moshi.adapter(MealPlanSnapshot::class.java),
        prefsName = "homebase_mealplan_cache",
    )

    // Familienkalender (#435): read-only overlay of todos/absence/meals/events. Each WS client is a
    // dedicated instance (separate from the feature screens') so connect/disconnect lifecycles don't
    // collide; recipes is included for the meal-plan cascade-delete broadcast (see CalendarRepository).
    val calendarRepository = CalendarRepository(
        api = api,
        todoWsClient = TodoWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
        absenceWsClient = AbsenceWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
        mealPlanWsClient = MealPlanWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
        recipeWsClient = RecipeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
        eventWsClient = EventWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )
}
