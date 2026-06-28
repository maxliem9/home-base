package com.homebase.android.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingSuggestion
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.shopping.FlushDecision
import com.homebase.android.data.shopping.PendingCheck
import com.homebase.android.data.shopping.PendingQueue
import com.homebase.android.data.shopping.ShoppingClock
import com.homebase.android.data.shopping.ShoppingPendingStore
import com.homebase.android.data.shopping.ShoppingViewPrefs
import com.homebase.android.data.shopping.classifyFlush
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ShoppingUiState(
    val lists: List<ShoppingListDto> = emptyList(),
    val items: List<ShoppingItemDto> = emptyList(),
    val activeListId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Item ids whose check-off has not yet been acknowledged by the backend (offline queue). */
    val pendingIds: Set<String> = emptySet(),
    /** Saved named standard lists (#215); kept in sync via the shopping WS channel. */
    val templates: List<ShoppingTemplateDto> = emptyList(),
    /** "Most used" autocomplete suggestions (#389), preloaded once; filtered client-side in the UI. */
    val suggestions: List<ShoppingSuggestion> = emptyList(),
    /**
     * The editable grocery category catalog (#411) used to group the list + drive the move-menu.
     * Initialized to [BUILTIN_CATEGORIES] (offline/first-frame fallback) and replaced by the fetched
     * catalog; reloaded on the shopping WS `CategoryChanged` event.
     */
    val categories: List<GroceryCategory> = BUILTIN_CATEGORIES,
    /** List vs. tile rendering (#446); persisted across launches, tiles by default (web parity). */
    val tileView: Boolean = true,
) {
    val activeList: ShoppingListDto? get() = lists.firstOrNull { it.id == activeListId } ?: lists.firstOrNull()

    val activeIsFirst: Boolean get() = activeList != null && lists.firstOrNull()?.id == activeList?.id

    /**
     * Items in the active list. The first list also surfaces any list-less (null) item as a
     * **safety net**: lists-first (#181) means Android no longer *creates* such items (a new item
     * always gets a list) and adopts pre-existing ones into the first list on load — but that
     * migration is best-effort (a failed PUT can leave one behind), so the first list still shows
     * stragglers rather than orphaning them off-screen.
     */
    val visibleItems: List<ShoppingItemDto>
        get() {
            val id = activeList?.id ?: return items.filter { it.listId == null }
            return items.filter { it.listId == id || (activeIsFirst && it.listId == null) }
        }

    /** Count of open (unchecked) items across all lists — used for the drawer badge. */
    val openCount: Int get() = items.count { !it.checked }

    /** Not-yet-synced check-offs among the items currently on screen (drives the sync banner). */
    val visiblePendingCount: Int get() = visibleItems.count { it.id in pendingIds }

    fun isPending(id: String): Boolean = id in pendingIds
}

/**
 * Owns the shopping screen state plus the **offline-resilient check-off** machinery (issue #170,
 * parity with the web `ShoppingView`):
 *
 * Tapping a checkbox updates the UI optimistically *and* records the intent in a durable, latest-wins
 * queue ([pendingStore]) keyed by item id. A tap in a store with flaky/no wifi is therefore remembered
 * across process death and retried — never silently lost. The item carries a "not synced" marker
 * ([ShoppingUiState.pendingIds]) until the PUT lands. Three signals drain the queue: the WebSocket
 * reconnect (`ShoppingWebSocketClient.onConnected`), a `ConnectivityObserver` network-available event,
 * and a periodic backstop (flaky store wifi often regains internet without any callback firing).
 */
class ShoppingViewModel(
    private val repository: ShoppingRepository,
    private val token: String,
    private val pendingStore: ShoppingPendingStore,
    /** Emits whenever the device regains a default network (the `online`-event analog). */
    networkAvailable: Flow<Unit>,
    private val clock: ShoppingClock = ShoppingClock.System,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    /** Persisted list/tile view choice (#446); null in tests → in-memory only, tiles by default. */
    private val viewPrefs: ShoppingViewPrefs? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShoppingUiState(isLoading = true))
    val uiState: StateFlow<ShoppingUiState> = _uiState.asStateFlow()

    /**
     * Source of truth for the durable queue; the UI only mirrors its key set via [pendingIds].
     * Starts empty and is seeded from [pendingStore] off-main in [init] (the prefs read blocks, so
     * it must not run on the main thread). Live toggles win over the restore — see [restored].
     */
    private var queue = PendingQueue()

    /** Serializes flush passes so two triggers can't double-send the same entry. */
    private val flushMutex = Mutex()

    /**
     * Serializes the off-main durable writes so two saves can't reorder and persist a stale
     * snapshot of the queue. Each mutation captures its snapshot synchronously before launching.
     */
    private val persistMutex = Mutex()

    /**
     * Serializes the auto-create-default-list path ([ensureDefaultList]). Without it, two quick adds
     * (Enter-Enter) or a partner's WS `ListCreated` arriving mid-add both pass the "does a list
     * exist?" re-check before either [ShoppingRepository.createList] completes → two "Einkaufsliste"
     * lists with distinct ids (items split across tabs). The lock makes the check-then-create atomic:
     * the first caller creates the list, later waiters re-read state under the lock and reuse it.
     */
    private val ensureListMutex = Mutex()

    /**
     * Single-flight guard for the **user-named** create path ([createList]). A double-tap on "Neue
     * Liste"/"Erstellen" (or two quick submits — the sheet isn't disabled in-flight) otherwise fires
     * [createList] twice → two lists (#191). Unlike [ensureListMutex], this must NOT dedup by reusing
     * an existing list: a user may legitimately want a second, even same-named list. So it only
     * collapses one rapid *burst* — a concurrent second call is dropped while the first is in flight;
     * a *sequential* second call (after the first resolved, a separate user action) still creates.
     */
    private var isCreatingList = false

    /**
     * Completes once the previous session's queue has been loaded and merged. [flush] awaits it, so
     * a flush trigger that fires during the async restore re-PUTs the restored entries instead of
     * racing an empty queue.
     */
    private val restored = CompletableDeferred<Unit>()

    /** Periodic backstop loop; runs only while the queue is non-empty, restarted on enqueue. */
    private var backstopJob: Job? = null

    init {
        load()
        loadTemplates()
        loadSuggestions()
        loadCategories()
        observeWebSocket()
        observeConnectivity(networkAvailable)
        // Restore the persisted list/tile view choice off-main (#446).
        viewPrefs?.let { prefs ->
            viewModelScope.launch { _uiState.update { it.copy(tileView = prefs.loadTileView()) } }
        }
        // Restore the previous session's queue off-main, then drain it. A toggle made before this
        // finishes already lives in `queue`; we merge the restored entries *under* it so a live
        // toggle (newer) is never clobbered, then flush + arm the backstop.
        viewModelScope.launch {
            val persisted = pendingStore.load()
            if (persisted.isNotEmpty()) {
                queue = PendingQueue(persisted + queue.entries) // live (later) entries override restored
                // Persist + reflect the authoritative merged set (a toggle during restore may have
                // written only itself); serialized via persistMutex so it lands last.
                persistAndReflect()
            }
            restored.complete(Unit)
            flush()
            ensureBackstop()
        }
    }

    fun load() {
        viewModelScope.launch { reload() }
    }

    /**
     * Pull-to-refresh entry point (#269). Suspends until lists + items are refetched so the UI's
     * refresh indicator can spin for the duration. Routes through [syncFromServer] (not [reload]) so
     * a still-pending offline check-off is not clobbered by the server's older state, and surfaces a
     * fetch error since it's user-triggered.
     */
    suspend fun refresh() = syncFromServer(surfaceError = true)

    /**
     * Silent background re-sync of lists + items (#269). Fires on every WS (re)connect (alongside the
     * queue [flush], see [observeWebSocket]) and on app/screen resume ([ensureConnected]). An item or
     * list created/edited/deleted on the web or another device while our socket was dead (Doze /
     * mobile-network change / backend restart) sends an item/list frame we never receive — the queue
     * flush alone only re-PUTs our own pending check-offs, it does not pull in a partner's changes, so
     * without this the list stays stale until logout/login.
     *
     * Each fetched item is merged through the same pending-aware rule as [upsertItemFromServer]: a
     * still-queued local check intent wins over the server's `checked`/`checkedAt` (the server may not
     * yet have our in-flight PUT), every other field takes the server value. Items removed on the
     * server drop out; queued checks for them are pruned. Never flips `isLoading`; on a transient
     * failure existing state is kept (the next trigger retries) unless [surfaceError] (pull-to-refresh).
     */
    private suspend fun syncFromServer(surfaceError: Boolean = false) {
        val listsResult = repository.getLists()
        val itemsResult = repository.getItems()
        val error = listsResult.exceptionOrNull()?.message ?: itemsResult.exceptionOrNull()?.message
        if (error != null) {
            if (surfaceError) _uiState.update { it.copy(error = error) }
            return
        }
        val serverItems = itemsResult.getOrDefault(emptyList())
        _uiState.update { state ->
            val merged = serverItems.map { server ->
                // A pending local check wins over the server's checked state (see upsertItemFromServer).
                val local = state.items.firstOrNull { it.id == server.id }
                if (local != null && server.id in queue) server.copy(checked = local.checked, checkedAt = local.checkedAt)
                else server
            }
            state.copy(
                lists = listsResult.getOrDefault(state.lists),
                items = merged,
                error = if (surfaceError) null else state.error,
            )
        }
        // Prune queued checks for items the server no longer has (deleted elsewhere) — they can never
        // land. Compare against the freshly fetched ids, not stale local state.
        val goneIds = queue.entries.keys.filter { id -> serverItems.none { it.id == id } }
        if (goneIds.isNotEmpty()) dequeueAll(goneIds)
        migrateListlessItems()
    }

    /** Refetch lists + items into the UI state (the body of [load]); awaited by callers that need
     *  the snapshot in place before they act, e.g. the delete-failure resync. */
    private suspend fun reload() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val lists = repository.getLists()
        val items = repository.getItems()
        val error = lists.exceptionOrNull()?.message ?: items.exceptionOrNull()?.message
        _uiState.update { state ->
            state.copy(
                lists = lists.getOrDefault(state.lists),
                items = items.getOrDefault(state.items),
                isLoading = false,
                error = error,
            )
        }
        migrateListlessItems()
    }

    /**
     * Lists-first parity with the web (#181): Android must never *leave* list-less shopping items
     * around. Newly added items already get a list (see [addItem]); this best-effort sweep adopts any
     * pre-existing list-less rows (created before this change, or by an older client) into the first
     * list via PUT `listId`. The optimistic local move keeps them visible meanwhile; the safety-net
     * `listId == null` surfacing in [ShoppingUiState.visibleItems] catches anything a failed PUT
     * leaves behind, so a migration miss is never lost.
     */
    private suspend fun migrateListlessItems() {
        val state = _uiState.value
        val targetId = state.lists.firstOrNull()?.id ?: return // no list yet → nothing to adopt into
        val orphans = state.items.filter { it.listId == null }
        if (orphans.isEmpty()) return
        for (orphan in orphans) {
            repository.updateItem(orphan.id, UpdateShoppingItemRequest(listId = targetId))
                .onSuccess { updated ->
                    // Apply the server row, but let a still-pending local check intent win for the
                    // checked/checkedAt fields (the user may have toggled this item mid-migration) —
                    // same rule as the WS echo handling in upsertItemFromServer.
                    _uiState.update { s ->
                        s.copy(items = s.items.map { local ->
                            if (local.id != updated.id) local
                            else if (updated.id in queue) updated.copy(checked = local.checked, checkedAt = local.checkedAt)
                            else updated
                        })
                    }
                }
            // On failure leave the row list-less; the visibleItems safety net keeps it reachable and
            // the next reload retries. No error surfaced — this is background housekeeping.
        }
    }

    fun selectList(id: String?) = _uiState.update { it.copy(activeListId = id) }

    /** Switch list/tile view and persist the choice (#446). */
    fun setTileView(tiles: Boolean) {
        _uiState.update { it.copy(tileView = tiles) }
        viewPrefs?.let { prefs -> viewModelScope.launch { prefs.saveTileView(tiles) } }
    }

    fun addItem(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            // Lists-first like the web (#181): an item always belongs to a list. If none exists yet,
            // auto-create a neutral default list and attach the item to it instead of producing a
            // list-less item. (The web never reaches this branch — it has no add UI without a list.)
            val listId = _uiState.value.activeList?.id ?: ensureDefaultList() ?: return@launch
            repository.createItem(name.trim(), listId)
                .onSuccess { upsertItem(it) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Preload the "most used" autocomplete suggestions (#389); non-fatal — empty just shows none. */
    private fun loadSuggestions() {
        viewModelScope.launch {
            repository.getSuggestions().onSuccess { s -> _uiState.update { it.copy(suggestions = s) } }
        }
    }

    /**
     * Fetch the editable category catalog (#411) into [ShoppingUiState.categories]; called on init and
     * on every shopping WS `CategoryChanged` event. Non-fatal: on failure the state keeps its current
     * catalog (the [BUILTIN_CATEGORIES] fallback on first load), so grouping/the move-menu still work
     * offline — it just won't show a partner's custom categories until the fetch succeeds.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            repository.getCategories().onSuccess { cats ->
                _uiState.update { it.copy(categories = cats.map { c -> c.toGrocery() }) }
            }
        }
    }

    /**
     * Reassign an item's category via the "In Kategorie verschieben" menu (#389). Optimistic; the
     * backend also remembers the choice for future adds of that name. On failure, resync from server.
     */
    fun moveItemCategory(item: ShoppingItemDto, category: String) {
        if (item.category == category) return
        _uiState.update { s -> s.copy(items = s.items.map { if (it.id == item.id) it.copy(category = category) else it }) }
        viewModelScope.launch {
            repository.updateItem(item.id, UpdateShoppingItemRequest(category = category))
                .onSuccess { upsertItem(it) }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                    syncFromServer()
                }
        }
    }

    /**
     * Ensure at least one shopping list exists and return the id to file a new item/batch under.
     * Used by [addItem] and [addIngredients] when content is added before any list exists (#181).
     * Returns the active/first list's id if one already appeared (e.g. a partner created one over WS),
     * otherwise creates the neutral default list. Returns null only if creation fails — the caller
     * then surfaces the error and skips the add rather than falling back to a list-less item.
     *
     * Serialized via [ensureListMutex] so concurrent adds (Enter-Enter) or a racing WS `ListCreated`
     * create at most ONE default list: the lock-free fast path covers the common "a list exists"
     * case, and the slow path re-reads state *inside* the lock before creating, so later waiters reuse
     * the first caller's list instead of creating a second one.
     */
    private suspend fun ensureDefaultList(): String? {
        // Fast path: a list already exists, no need to serialize.
        _uiState.value.activeList?.id?.let { return it }
        return ensureListMutex.withLock {
            // Re-read under the lock: a list may have appeared while we waited (a prior waiter created
            // the default, or a partner's WS `ListCreated` landed) — reuse it, don't create a second.
            _uiState.value.activeList?.id?.let { return@withLock it }
            repository.createList(DEFAULT_LIST_NAME)
                .onSuccess { list ->
                    _uiState.update { s ->
                        val lists = if (s.lists.any { it.id == list.id }) s.lists else s.lists + list
                        s.copy(lists = lists, activeListId = list.id)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .getOrNull()?.id
        }
    }

    /**
     * Toggle a check-off optimistically and queue it for delivery. The queue (not an inline call)
     * does the network work, so a tap in a dead zone is remembered and retried instead of lost —
     * the item just shows a "not synced" marker until it lands. `checkedAt` is set locally too, so
     * the "Im Wagen" ordering is correct immediately.
     */
    fun toggleChecked(item: ShoppingItemDto) {
        val next = !item.checked
        val at = clock.nowMillis()
        val checkedAt = if (next) clock.nowIso() else null
        _uiState.update { s ->
            s.copy(items = s.items.map { if (it.id == item.id) it.copy(checked = next, checkedAt = checkedAt) else it })
        }
        enqueue(item.id, PendingCheck(checked = next, at = at))
        flush()
    }

    fun deleteItem(id: String) {
        // A queued check for an item we're deleting can never land — drop it.
        dequeue(id)
        viewModelScope.launch {
            repository.deleteItem(id)
                .onSuccess { _uiState.update { s -> s.copy(items = s.items.filter { it.id != id }) } }
                .onFailure { e ->
                    // The check-off intent was already dropped above; if the delete itself fails the
                    // item is still on the server (and now un-queued), so a naive failure would leave
                    // the row shown-and-checked locally with no pending marker → silent divergence.
                    // Resync from the server like the web does (ShoppingView.handleDelete), then
                    // surface the error (after the reload, which clears it, so it survives).
                    reload()
                    _uiState.update { it.copy(error = e.message) }
                }
        }
    }

    /** Remove all checked ("Im Wagen") items from the active list. */
    fun clearChecked() {
        val checked = _uiState.value.visibleItems.filter { it.checked }
        checked.forEach { deleteItem(it.id) }
    }

    /**
     * Push the chosen (already serving-scaled) recipe ingredients onto [listId] via the batch
     * endpoint, which formats each as a "200 g Mehl" label and merges quantities into matching
     * items already on the list. Reports how many were freshly added vs. merged via [onResult].
     *
     * Lists-first (#181): batch-add must never create list-less items either. When no explicit
     * [listId] and no active list exists, route through [ensureDefaultList] (same serialized
     * auto-create as [addItem]); if that create fails, surface the error and skip rather than
     * batch-adding with a null list.
     */
    fun addIngredients(
        listId: String?,
        lines: List<ShoppingLineInput>,
        onResult: (added: Int, merged: Int) -> Unit = { _, _ -> },
    ) {
        if (lines.isEmpty()) {
            onResult(0, 0)
            return
        }
        viewModelScope.launch {
            val targetId = listId ?: _uiState.value.activeList?.id ?: ensureDefaultList()
            if (targetId == null) {
                // List creation failed (error already surfaced by ensureDefaultList) → skip the add
                // rather than producing list-less items.
                onResult(0, 0)
                return@launch
            }
            repository.batchAdd(targetId, lines)
                .onSuccess { resp ->
                    resp.items.forEach { upsertItem(it) }
                    onResult(resp.added, resp.merged)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message) }
                    onResult(0, 0)
                }
        }
    }

    // --- Templates (named standard lists, #215) ------------------------------------------------

    /** Refetch the saved templates into the UI state (init + every template WS event). */
    fun loadTemplates() {
        viewModelScope.launch {
            repository.getTemplates().onSuccess { templates ->
                _uiState.update { it.copy(templates = templates) }
            }
            // A templates fetch failure is non-fatal background data — don't clobber the item error.
        }
    }

    /**
     * Apply a template by batch-adding the chosen item names to the active list (or [listId]).
     * Reuses the exact recipe→shopping path ([addIngredients] → `batchAdd`): each name becomes a
     * [ShoppingLineInput] with no amount/unit, so quantities merge into matching existing items and
     * the result counts (added/merged) come back the same way. [names] is the user's checkbox
     * selection (default: all of the template's items).
     */
    fun applyTemplate(
        names: List<String>,
        listId: String? = null,
        onResult: (added: Int, merged: Int) -> Unit = { _, _ -> },
    ) = addIngredients(listId, names.map { ShoppingLineInput(name = it) }, onResult)

    /** Create a new template. Blank name no-ops (the sheet also disables the button). */
    fun createTemplate(name: String, itemNames: List<String>, onDone: () -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createTemplate(name, itemNames)
                .onSuccess { template ->
                    upsertTemplate(template)
                    onDone()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Rename + replace the item set of an existing template. */
    fun updateTemplate(id: String, name: String, itemNames: List<String>, onDone: () -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updateTemplate(id, name, itemNames)
                .onSuccess { template ->
                    upsertTemplate(template)
                    onDone()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun deleteTemplate(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteTemplate(id)
                .onSuccess {
                    _uiState.update { s -> s.copy(templates = s.templates.filter { it.id != id }) }
                    onDone()
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private fun upsertTemplate(template: ShoppingTemplateDto) {
        _uiState.update { s ->
            val templates = if (s.templates.any { it.id == template.id }) {
                s.templates.map { if (it.id == template.id) template else it }
            } else {
                s.templates + template
            }
            s.copy(templates = templates)
        }
    }

    /**
     * Create a user-named list. Guarded by [isCreatingList] single-flight so a double-tap on the
     * confirm (or two quick submits) creates only ONE list (#191) — the second concurrent call is
     * ignored while the first is in flight. A deliberate *second* list via a later, separate action
     * still works: the flag is cleared once the first create resolves, so a sequential call proceeds.
     * The flag is set synchronously (before `launch`) so an immediate re-entry on the same frame sees
     * it, and reset in `finally` so a failed create never wedges the path shut.
     */
    fun createList(name: String) {
        if (name.isBlank()) return
        if (isCreatingList) return // a create from this user intent is already in flight — ignore the double-tap
        isCreatingList = true
        viewModelScope.launch {
            try {
                repository.createList(name.trim())
                    .onSuccess { list ->
                        _uiState.update { s ->
                            val lists = if (s.lists.any { it.id == list.id }) s.lists else s.lists + list
                            s.copy(lists = lists, activeListId = list.id)
                        }
                    }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            } finally {
                isCreatingList = false
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Manual "retry now" from the sync banner. */
    fun retryPending() = flush()

    // --- Offline check-off queue ---------------------------------------------------------------

    private fun enqueue(id: String, check: PendingCheck) {
        queue = queue.enqueue(id, check)
        persistAndReflect()
        ensureBackstop()
    }

    private fun dequeue(id: String) {
        val next = queue.dequeue(id)
        if (next !== queue) {
            queue = next
            persistAndReflect()
        }
    }

    private fun dequeueAll(ids: Collection<String>) {
        val next = queue.dequeueAll(ids)
        if (next !== queue) {
            queue = next
            persistAndReflect()
        }
    }

    /**
     * Mirror the queue's key set into the UI (synchronous, so the marker/banner update on the same
     * frame as the optimistic item change) and persist the queue off-main. The snapshot is captured
     * here, before launching, and writes are serialized by [persistMutex] so concurrent mutations
     * can't reorder and leave a stale map on disk.
     */
    private fun persistAndReflect() {
        val snapshot = queue.entries
        _uiState.update { it.copy(pendingIds = snapshot.keys) }
        viewModelScope.launch {
            persistMutex.withLock { pendingStore.save(snapshot) }
        }
    }

    /**
     * Drain the queue. Each entry's PUT is kept-and-retried on a transport reject (offline) or a
     * transient 5xx — both are the "silently lost check-off" this exists to prevent. A success or a
     * terminal 4xx (e.g. 404, item already gone) drops the entry, but not if it was re-toggled
     * meanwhile (a newer `at` survives). One pass at a time, guarded by [flushMutex].
     */
    private fun flush() {
        viewModelScope.launch {
            // Wait for the previous session's queue to be restored, so a trigger that fires during
            // the async restore re-PUTs those entries instead of racing an empty queue.
            restored.await()
            if (!flushMutex.tryLock()) return@launch
            try {
                // Snapshot under the lock; new toggles appending meanwhile are handled by their own
                // flush() and by the dequeueIfUnchanged guard below.
                for ((id, intent) in queue.entries) {
                    val result = repository.updateItem(id, UpdateShoppingItemRequest(checked = intent.checked))
                    val updated = result.getOrNull()
                    if (updated != null) {
                        // Don't let a now-stale response overwrite a newer toggle made while in flight:
                        // the optimistic state already reflects the newer intent.
                        if (queue[id] == intent) {
                            _uiState.update { s ->
                                s.copy(items = s.items.map { if (it.id == updated.id) updated else it })
                            }
                        }
                        dequeueIfUnchanged(id, intent)
                    } else {
                        when (classifyFlush(result.exceptionOrNull() ?: RuntimeException())) {
                            FlushDecision.KEEP_RETRY -> break // offline / transient — stop, retry later
                            FlushDecision.DROP_TERMINAL -> dequeueIfUnchanged(id, intent)
                        }
                    }
                }
            } finally {
                flushMutex.unlock()
            }
        }
    }

    private fun dequeueIfUnchanged(id: String, expected: PendingCheck) {
        val next = queue.dequeueIfUnchanged(id, expected)
        if (next !== queue) {
            queue = next
            persistAndReflect()
        }
    }

    private fun upsertItem(item: ShoppingItemDto) {
        _uiState.update { s ->
            val items = if (s.items.any { it.id == item.id }) {
                s.items.map { if (it.id == item.id) item else it }
            } else {
                listOf(item) + s.items
            }
            s.copy(items = items)
        }
    }

    private fun upsertList(list: ShoppingListDto) {
        _uiState.update { s ->
            val lists = if (s.lists.any { it.id == list.id }) {
                s.lists.map { if (it.id == list.id) list else it }
            } else {
                s.lists + list
            }
            s.copy(lists = lists)
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        // A (re)connected socket means the server is reachable (web WS `onOpen`): drain the offline
        // check-off queue AND refetch the list (#269). The flush only re-PUTs our own pending checks;
        // the refetch pulls in a partner's item/list changes made while our socket was dead, whose
        // WS frames we missed — without it those would stay stale until logout/login.
        repository.setWebSocketOnConnected {
            flush()
            viewModelScope.launch { syncFromServer() }
        }
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is ShoppingWebSocketClient.WsEvent.ItemCreated -> upsertItem(event.item)
                    is ShoppingWebSocketClient.WsEvent.ItemUpdated -> upsertItemFromServer(event.item)
                    is ShoppingWebSocketClient.WsEvent.ItemDeleted -> {
                        _uiState.update { s -> s.copy(items = s.items.filter { it.id != event.item.id }) }
                        dequeue(event.item.id) // a queued check for a now-deleted item can never land
                    }
                    is ShoppingWebSocketClient.WsEvent.ListCreated -> upsertList(event.list)
                    is ShoppingWebSocketClient.WsEvent.ListUpdated -> upsertList(event.list)
                    is ShoppingWebSocketClient.WsEvent.ListDeleted -> {
                        val goneList = event.list.id
                        val orphanIds = _uiState.value.items.filter { it.listId == goneList }.map { it.id }
                        _uiState.update { s -> s.copy(lists = s.lists.filter { it.id != goneList }, items = s.items.filter { it.listId != goneList }) }
                        dequeueAll(orphanIds) // queued checks for items on a deleted list can't land
                    }
                    // Any template create/update/delete → refetch the whole set (web parity, #215).
                    is ShoppingWebSocketClient.WsEvent.TemplateChanged -> loadTemplates()
                    // A category was added/renamed/reordered/deleted (#411) → refetch the catalog so
                    // the grouping headers + move-menu stay current. Rule changes don't affect this
                    // view (rules only resolve at add-time, server-side), so they're a no-op here.
                    is ShoppingWebSocketClient.WsEvent.CategoryChanged -> loadCategories()
                    is ShoppingWebSocketClient.WsEvent.CategoryRuleChanged -> Unit
                }
            }
        }
    }

    /**
     * Apply a server ITEM_UPDATED echo, but let a still-pending local check intent win over it for
     * the `checked`/`checkedAt` fields (the echo may carry an older state — e.g. our own in-flight
     * PUT after we re-toggled). Other fields (name/list) take the server value.
     */
    private fun upsertItemFromServer(server: ShoppingItemDto) {
        if (server.id !in queue) {
            upsertItem(server)
            return
        }
        _uiState.update { s ->
            val items = if (s.items.any { it.id == server.id }) {
                s.items.map { local ->
                    if (local.id == server.id) server.copy(checked = local.checked, checkedAt = local.checkedAt) else local
                }
            } else {
                listOf(server) + s.items
            }
            s.copy(items = items)
        }
    }

    private fun observeConnectivity(networkAvailable: Flow<Unit>) {
        viewModelScope.launch {
            networkAvailable.collect { flush() }
        }
    }

    /**
     * Periodic backstop: flaky store wifi often regains internet without ever firing a network or
     * socket callback, so poll while the queue is non-empty. The loop exits once the queue drains
     * (and is re-armed on the next [enqueue]), so it never spins when there is nothing to send.
     */
    private fun ensureBackstop() {
        if (queue.isEmpty) return
        if (backstopJob?.isActive == true) return
        backstopJob = viewModelScope.launch {
            while (!queue.isEmpty) {
                delay(flushIntervalMs)
                if (!queue.isEmpty) flush()
            }
        }
    }

    /**
     * Called from the UI when the app returns to the foreground (#269). Reconnects the channel if it
     * dropped, **re-syncs the list** and **flushes** the offline queue. A reconnect fires `onConnected`
     * (which does both), but if the socket survived the background no callback fires, so we also do it
     * here. Either way the list matches the server after a backgrounded change elsewhere and any
     * pending check-offs retry.
     */
    fun ensureConnected() {
        repository.ensureWebSocketConnected()
        flush()
        viewModelScope.launch { syncFromServer() }
    }

    override fun onCleared() {
        super.onCleared()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 15_000L

        /** Neutral default list auto-created when a user adds an item before any list exists (#181).
         *  Deliberately generic — no user-specific naming (portable-over-hardcoded-household). */
        const val DEFAULT_LIST_NAME = "Einkaufsliste"
    }
}
