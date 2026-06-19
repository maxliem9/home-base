package com.homebase.android.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.NoteImageDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.notes.NEW_KEY
import com.homebase.android.data.notes.NoteFlushDecision
import com.homebase.android.data.notes.NotesClock
import com.homebase.android.data.notes.NotesPendingStore
import com.homebase.android.data.notes.PendingNote
import com.homebase.android.data.notes.PendingNoteQueue
import com.homebase.android.data.notes.classifyNoteFlush
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
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

data class NotesUiState(
    val notes: List<NoteDto> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Note ids whose last auto-save has not yet been acknowledged by the backend (offline queue,
     *  #323) — drives the "not synced" marker in the note list. A not-yet-created draft is queued
     *  under NEW_KEY (not a real id), so it never appears here; the editor marker covers that case. */
    val pendingIds: Set<String> = emptySet(),
) {
    fun isPending(id: String): Boolean = id in pendingIds
}

/** One picked image to upload to a note: its bytes + the original filename and MIME type. */
data class NoteImageUpload(val bytes: ByteArray, val filename: String, val contentType: String)

/**
 * Auto-save status shown in the editor app bar (#309). [PENDING] (#323) is the offline-resilient
 * state: a save failed and the edit is queued in the durable store for retry — distinct from [ERROR]
 * (a terminal failure that won't be retried), so the UI can show a "not synced" marker.
 */
enum class SaveStatus { IDLE, SAVING, SAVED, ERROR, PENDING }

/**
 * In-progress editor draft (#309/#310). Lives in the ViewModel — **not** sourced from
 * [NotesUiState.notes] — so the WS-echo list refresh ([upsert] on our own save's NOTE_UPDATED, or a
 * partner's edit) never clobbers the user's unsaved keystrokes/caret. `noteId` is null for a
 * not-yet-created note and is captured from the first successful create so later saves UPDATE that
 * id (no duplicate notes). `images` mirrors the saved note's gallery (upload/remove refresh it
 * without touching the text draft).
 */
data class NoteEditorState(
    val noteId: String?,
    val title: String,
    val content: String,
    val tags: List<String>,
    val folder: String,
    val visibility: String,
    val images: List<NoteImageDto> = emptyList(),
    val status: SaveStatus = SaveStatus.IDLE,
    // Bumped once per editor open/switch — NOT on the first-create id capture — so the UI can key
    // its caret-bearing content field on a stable token (reseeds on a note switch, survives the
    // null→id transition while typing in a brand-new note). #309/#310.
    val session: Int = 0,
)

class NotesViewModel(
    private val repository: NotesRepository,
    private val token: String,
    /** Durable backing store for the offline auto-save queue (#323). */
    private val pendingStore: NotesPendingStore,
    /** Emits whenever the device regains a default network (the web `online`-event analog). */
    networkAvailable: Flow<Unit>,
    private val clock: NotesClock = NotesClock.System,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState(isLoading = true))
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    // --- Editor / auto-save state (#309/#310) ---
    private val _editorState = MutableStateFlow<NoteEditorState?>(null)
    val editorState: StateFlow<NoteEditorState?> = _editorState.asStateFlow()

    /** Debounce job: cancelled on each keystroke, fires the save after [AUTOSAVE_DEBOUNCE_MS]. */
    private var autosaveJob: Job? = null

    // --- Offline-resilient auto-save queue (#323, parity with the shopping check-off queue) ---
    /**
     * Source of truth for the durable save queue; the UI mirrors its key set via [NotesUiState
     * .pendingIds] (note-list marker) and the open draft's [SaveStatus.PENDING] (editor marker).
     * Starts empty and is seeded from [pendingStore] off-main in [init]. Live failures win over the
     * restore — see [restored].
     */
    private var queue = PendingNoteQueue()

    /** Serializes flush passes so two triggers can't double-send the same entry. */
    private val flushMutex = Mutex()

    /** Serializes the off-main durable writes so two saves can't reorder and persist a stale map. */
    private val persistMutex = Mutex()

    /**
     * Completes once the previous session's queue has been loaded and merged. [flush] awaits it so a
     * trigger that fires during the async restore re-sends the restored entries instead of racing an
     * empty queue.
     */
    private val restored = CompletableDeferred<Unit>()

    /** Periodic backstop loop; runs only while the queue is non-empty, restarted on enqueue. */
    private var backstopJob: Job? = null

    /**
     * The single in-flight save coroutine, or null/!isActive when idle. Saves are **serialized**
     * through this one job: while it runs, no second save starts (no duplicate create); when it
     * finishes it re-checks dirtiness and saves again, so a keystroke that landed mid-save is still
     * persisted. [flushEditorSave] joins it so leaving the editor always lands the latest draft.
     */
    private var saveJob: Job? = null

    /** Snapshot of what is currently persisted on the server, to skip no-op saves (dirty check). */
    private var savedSnapshot: EditorSnapshot? = null

    /** Monotonic editor-session counter; bumped on each open/switch (see [NoteEditorState.session]). */
    private var editorSession = 0

    init {
        load()
        observeWebSocket()
        observeConnectivity(networkAvailable)
        // Restore the previous session's queue off-main, then drain it. A save that failed before this
        // finishes already lives in `queue`; we merge the restored entries *under* it so a live (later)
        // failure is never clobbered, then flush + arm the backstop.
        viewModelScope.launch {
            val persisted = pendingStore.load()
            if (persisted.isNotEmpty()) {
                queue = PendingNoteQueue(persisted + queue.entries) // live (later) entries override restored
                persistAndReflect()
            }
            restored.complete(Unit)
            flush()
            ensureBackstop()
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getNotes(_uiState.value.query)
                .onSuccess { notes -> _uiState.update { it.copy(notes = notes, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /**
     * Pull-to-refresh entry point (#269). Suspends until the refetch completes so the UI's refresh
     * indicator can spin for the duration; no full-screen spinner (the list stays visible) but it
     * does surface a fetch error like load(), since it's user-triggered. Respects the active query.
     */
    suspend fun refresh() {
        repository.getNotes(_uiState.value.query)
            .onSuccess { notes -> _uiState.update { it.copy(notes = notes, error = null) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
    }

    /**
     * Silent background re-sync of the notes list (#269). Fires on every WS (re)connect
     * (`onConnected`) and on app/screen resume ([ensureConnected]). A note created/edited/deleted on
     * the web or another device while our socket was dead (Doze / mobile-network change / backend
     * restart) sends a NOTE_* frame we never receive — without this refetch the list would stay stale
     * until logout/login. Unlike [load] this never flips `isLoading` and leaves existing notes +
     * `error` untouched on a transient failure (the next trigger retries). Respects the active query.
     */
    private fun syncFromServer() {
        viewModelScope.launch {
            repository.getNotes(_uiState.value.query)
                .onSuccess { notes -> _uiState.update { it.copy(notes = notes) } }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        load()
    }

    fun saveNote(
        id: String?,
        title: String,
        content: String,
        tags: List<String>,
        folder: String,
        visibility: String,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            // Always send a (possibly empty) string for folder: the backend trims it and maps
            // blank ⇒ null, so this both sets a folder and clears one when the field is emptied
            // (mirrors the web client).
            val folderValue = folder.trim()
            val result = if (id == null) {
                repository.createNote(
                    CreateNoteRequest(
                        title = title.trim(),
                        content = content,
                        tags = tags,
                        folder = folderValue,
                        visibility = visibility,
                    )
                )
            } else {
                repository.updateNote(
                    id,
                    UpdateNoteRequest(
                        title = title.trim(),
                        content = content,
                        tags = tags,
                        folder = folderValue,
                        visibility = visibility,
                    )
                )
            }
            result
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    // -----------------------------------------------------------------------
    // Editor / auto-save (#309/#310)
    // -----------------------------------------------------------------------

    /**
     * Open the editor for [note] (null = a brand-new note). Seeds the draft and the saved snapshot:
     * for an existing note the snapshot equals the loaded state (so the first keystroke is the first
     * dirty change — opening alone never saves); for a new note there is no snapshot yet, so the
     * first non-blank title triggers the create.
     */
    fun openEditor(note: NoteDto?) {
        autosaveJob?.cancel()
        val session = ++editorSession
        if (note == null) {
            savedSnapshot = null
            _editorState.value = NoteEditorState(
                noteId = null,
                title = "",
                content = "",
                tags = emptyList(),
                folder = "",
                visibility = "SHARED",
                images = emptyList(),
                status = SaveStatus.IDLE,
                session = session,
            )
        } else {
            savedSnapshot = EditorSnapshot.of(note.title, note.content, note.tags, note.folder ?: "", note.visibility)
            _editorState.value = NoteEditorState(
                noteId = note.id,
                title = note.title,
                content = note.content,
                tags = note.tags,
                folder = note.folder ?: "",
                visibility = note.visibility,
                images = note.images,
                status = SaveStatus.IDLE,
                session = session,
            )
        }
    }

    /**
     * Apply an editor field change and (re)arm the debounced auto-save. Each call cancels the
     * pending save and starts a fresh [AUTOSAVE_DEBOUNCE_MS] timer, so we persist ~1s after the last
     * keystroke rather than on every character. A no-op change (draft already equals the saved
     * snapshot) clears the timer and shows nothing — the dirty check.
     */
    fun updateEditor(
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        folder: String? = null,
        visibility: String? = null,
    ) {
        val current = _editorState.value ?: return
        val next = current.copy(
            title = title ?: current.title,
            content = content ?: current.content,
            tags = tags ?: current.tags,
            folder = folder ?: current.folder,
            visibility = visibility ?: current.visibility,
        )
        _editorState.value = next
        autosaveJob?.cancel()
        if (!isDirty(next)) return
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            requestSave()
        }
    }

    /**
     * Persist the current draft right now (no debounce) — called on leaving the editor or before
     * switching to another note, so an edit is never lost between the last keystroke and the debounce
     * firing. Suspends until the save (incl. any save that was already running) completes, so the
     * caller (note switch / close) can sequence reliably and the very last keystroke is always saved.
     */
    suspend fun flushEditorSave() {
        autosaveJob?.cancel()
        requestSave()
        saveJob?.join()
    }

    /**
     * Start the serialized save loop if it isn't already running. A running loop re-checks the draft
     * when it finishes the current request, so we never start a second concurrent save (no duplicate
     * create) yet never drop a mid-save edit either.
     */
    private fun requestSave() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            // Save the latest draft; if it changed (or was dirtied again) while saving, loop and save
            // the newer version. Stop on a clean draft or a failed save (don't spin on errors).
            while (true) {
                val draft = _editorState.value ?: break
                if (!isDirty(draft)) break
                val ok = saveOnce(draft)
                if (!ok) break
            }
        }
    }

    /**
     * Jump to another note from inside the editor (note-switcher, #313): flush the current draft so
     * nothing is lost, then re-seed the editor for [target]. A no-op if already on that note.
     */
    fun switchEditorTo(target: NoteDto) {
        if (_editorState.value?.noteId == target.id) return
        viewModelScope.launch {
            flushEditorSave()
            openEditor(target)
        }
    }

    /**
     * Persist the current draft now WITHOUT leaving the editor — called when returning from the
     * inline edit mode back to the rendered preview (HB-13), so the edit is saved even if the
     * debounce hasn't fired yet. Mirrors the web client's exit-edit commit.
     */
    fun commitEditor() {
        viewModelScope.launch { flushEditorSave() }
    }

    /** Close the editor, flushing a final save first (back press). */
    fun closeEditor() {
        viewModelScope.launch {
            flushEditorSave()
            autosaveJob?.cancel()
            _editorState.value = null
            savedSnapshot = null
        }
    }

    /** Delete the note currently open in the editor (if it was ever created), then close it. */
    fun deleteEditorNote() {
        autosaveJob?.cancel()
        saveJob?.cancel()
        val id = _editorState.value?.noteId
        _editorState.value = null
        savedSnapshot = null
        // Drop any queued save for this note: an existing note via deleteNote(id) below; a never-created
        // draft's NEW_KEY entry here, so a stale offline create can't resurrect it (#323).
        if (id != null) deleteNote(id) else dequeue(NEW_KEY)
    }

    /**
     * Close the editor WITHOUT saving — used when the open note was deleted elsewhere (a partner's
     * delete arriving via WS, #313). Saving would 404 against the now-missing id and re-create
     * nothing useful; we just drop the editor and any pending save. No-op for a brand-new (unsaved)
     * note so opening "new" is never yanked away by a list refresh.
     */
    fun abandonEditor() {
        autosaveJob?.cancel()
        saveJob?.cancel()
        _editorState.value = null
        savedSnapshot = null
    }

    /** A draft differs from what's persisted iff there is no snapshot yet or a field changed. */
    private fun isDirty(draft: NoteEditorState): Boolean {
        val snap = savedSnapshot ?: return true
        return snap != EditorSnapshot.of(draft.title, draft.content, draft.tags, draft.folder, draft.visibility)
    }

    /**
     * One create-or-update round for [draft]; returns true on success (so the serialized save loop in
     * [requestSave] may continue if the draft was dirtied again) and false on a skip/failure (stop).
     * Hazards handled:
     * - **No create without a title:** the backend rejects a blank title, so we don't even attempt a
     *   create until the title is non-blank (an untitled new note simply stays unsaved/IDLE).
     * - **No duplicate create:** the single [saveJob] loop is the only caller, so two creates can't
     *   run at once; the first create's returned id is captured into the editor, so the loop's next
     *   iteration (and every later save) is an UPDATE of that id.
     * - **Caret safety:** on success we only fold the *server-side* fields (id, images) into state; we
     *   never overwrite the live title/content the user may have typed while the request was in flight
     *   — those stay in [_editorState]. The snapshot is set to the values we actually sent, so if the
     *   user kept typing the draft is still dirty and the loop re-saves.
     */
    private suspend fun saveOnce(draft: NoteEditorState): Boolean {
        val title = draft.title.trim()
        val id = draft.noteId
        val isCreate = id == null
        if (isCreate && title.isBlank()) return false // never create an untitled note
        // Snapshot of exactly what we send, so a no-op repeat is skipped and a change mid-flight stays dirty.
        val sentSnapshot = EditorSnapshot.of(draft.title, draft.content, draft.tags, draft.folder, draft.visibility)
        // The pending entry to persist if this save fails (#323): the exact fields we send, so a later
        // flush re-creates the request verbatim. `at` is the latest-wins tiebreaker.
        val at = clock.nowMillis()
        val pending = PendingNote(
            id = id, title = draft.title, content = draft.content, tags = draft.tags,
            folder = draft.folder, visibility = draft.visibility, at = at,
        )
        setEditorStatus(SaveStatus.SAVING)
        val folderValue = draft.folder.trim()
        val result = if (id == null) {
            repository.createNote(
                CreateNoteRequest(
                    title = title,
                    content = draft.content,
                    tags = draft.tags,
                    folder = folderValue,
                    visibility = draft.visibility,
                ),
            )
        } else {
            repository.updateNote(
                id,
                UpdateNoteRequest(
                    title = title,
                    content = draft.content,
                    tags = draft.tags,
                    folder = folderValue,
                    visibility = draft.visibility,
                ),
            )
        }
        return result.fold(
            onSuccess = { note ->
                upsert(note)
                // The save landed → drop any queued entry for this note (#323). Saves are serialized
                // (the single saveJob loop), so a success means the newest write is on the server and
                // ANY entry queued by an earlier failed attempt (a different, older `at`) is now
                // obsolete — clear it unconditionally rather than by-`at`, or a stale older `at` would
                // orphan it and leave the marker stuck. A newer edit typed during the in-flight window
                // re-dirties the draft and the loop re-saves (re-queuing only if THAT save fails). For
                // a create the entry sits under NEW_KEY (id was null when we sent it).
                dequeue(queue.keyFor(id))
                // Reflect the landed write into the editor ONLY when the open draft still refers to THIS
                // save's note — create: noteId == null; update: noteId == note.id — mirroring the identity
                // check in [flush]. Today the join/cancel discipline (switchEditorTo/closeEditor join,
                // abandon/deleteEditorNote cancel) means the editor can't switch notes during a running
                // save, so this guard is currently always true; it keeps the path robust if that
                // discipline ever changes — neither the id/SAVED stamp NOR the dirty baseline must be
                // applied to a different note's draft (a stale savedSnapshot would mis-fire the dirty
                // check on the switched-to note). See #369.
                val current = _editorState.value
                val draftIsThisNote = current != null &&
                    ((isCreate && current.noteId == null) || (!isCreate && current.noteId == note.id))
                if (draftIsThisNote) {
                    // Refresh the dirty baseline to exactly what we sent, so the save loop sees a clean
                    // draft (and a fresh keystroke re-dirties it).
                    savedSnapshot = sentSnapshot
                    // Capture the new id (first create) + refresh server-owned fields, but keep the live
                    // text draft so in-flight keystrokes survive.
                    _editorState.update { st ->
                        when {
                            st == null -> st
                            isCreate && st.noteId == null -> st.copy(noteId = note.id, images = note.images, status = SaveStatus.SAVED)
                            !isCreate && st.noteId == note.id -> st.copy(images = note.images, status = SaveStatus.SAVED)
                            else -> st
                        }
                    }
                }
                true
            },
            onFailure = { e ->
                // Persist the edit for retry (offline / 5xx) instead of losing it; a terminal 4xx can
                // never succeed on retry, so surface a plain error and leave the queue untouched (#323).
                when (classifyNoteFlush(e)) {
                    NoteFlushDecision.KEEP_RETRY -> {
                        enqueue(pending)
                        setEditorStatus(SaveStatus.PENDING)
                    }
                    NoteFlushDecision.DROP_TERMINAL -> {
                        setEditorStatus(SaveStatus.ERROR)
                        _uiState.update { it.copy(error = e.message) }
                    }
                }
                false
            },
        )
    }

    private fun setEditorStatus(status: SaveStatus) {
        _editorState.update { it?.copy(status = status) }
    }

    // --- Offline auto-save queue (#323) --------------------------------------------------------

    /** Manual "retry now" from the editor's not-synced marker. */
    fun retryPending() = flush()

    private fun enqueue(pending: PendingNote) {
        queue = queue.enqueue(pending)
        persistAndReflect()
        ensureBackstop()
    }

    private fun dequeue(key: String) {
        val next = queue.dequeue(key)
        if (next !== queue) {
            queue = next
            persistAndReflect()
        }
    }

    private fun dequeueIfUnchanged(key: String, expected: PendingNote) {
        val next = queue.dequeueIfUnchanged(key, expected)
        if (next !== queue) {
            queue = next
            persistAndReflect()
        }
    }

    /**
     * Mirror the queue's real-note keys into the UI (synchronous, so the marker updates on the same
     * frame as the save) and persist the queue off-main. NEW_KEY is excluded from [pendingIds] — it
     * is the open draft alone, surfaced by the editor's [SaveStatus.PENDING], not a note-list row.
     * The snapshot is captured here, before launching, and writes are serialized by [persistMutex] so
     * concurrent mutations can't reorder and leave a stale map on disk.
     */
    private fun persistAndReflect() {
        val snapshot = queue.entries
        _uiState.update { it.copy(pendingIds = snapshot.keys.filter { k -> k != NEW_KEY }.toSet()) }
        viewModelScope.launch {
            persistMutex.withLock { pendingStore.save(snapshot) }
        }
    }

    /**
     * Drain the queue (#323), the offline twin of the live auto-save. Re-sends each queued body (POST
     * for a NEW_KEY create, PUT for a known id) and is kept-and-retried on a transport reject (offline)
     * or a 5xx — both the "silently lost edit" this prevents. A success or a terminal 4xx drops the
     * entry, but not if it was re-edited meanwhile (a newer `at` survives). One pass at a time, guarded
     * by [flushMutex]. It re-sends the WRITE only — it never rehydrates the editor draft, so the live
     * text/caret is never clobbered. On a create success it folds the new id into the open draft when
     * that draft is STILL the same not-yet-created note, so later saves UPDATE it (no duplicate).
     */
    private fun flush() {
        viewModelScope.launch {
            // Wait for the restore so a trigger firing during it re-sends those entries, not an empty queue.
            restored.await()
            if (!flushMutex.tryLock()) return@launch
            try {
                // Snapshot under the lock; new failures appending meanwhile get their own flush() and the
                // dequeueIfUnchanged guard below protects a re-edited entry.
                for ((key, pending) in queue.entries) {
                    val isCreate = pending.id == null
                    val folderValue = pending.folder.trim()
                    val result = if (isCreate) {
                        repository.createNote(
                            CreateNoteRequest(
                                title = pending.title.trim(),
                                content = pending.content,
                                tags = pending.tags,
                                folder = folderValue,
                                visibility = pending.visibility,
                            ),
                        )
                    } else {
                        repository.updateNote(
                            pending.id!!,
                            UpdateNoteRequest(
                                title = pending.title.trim(),
                                content = pending.content,
                                tags = pending.tags,
                                folder = folderValue,
                                visibility = pending.visibility,
                            ),
                        )
                    }
                    val saved = result.getOrNull()
                    if (saved != null) {
                        upsert(saved)
                        // Did the user type something newer than what this flush just re-sent? Compare
                        // the open draft to the exact body we delivered. If it still matches (no newer
                        // keystroke) the editor is in sync → refresh the dirty baseline + mark SAVED;
                        // otherwise leave the status to the live save loop, which persists the newer
                        // text. Mirrors the web flushPendingNotes — without refreshing savedSnapshot the
                        // editor's PENDING chip would stay stuck after a queue-driven sync of an
                        // existing note (the old isDirty check compared against the pre-edit baseline,
                        // so it was always "dirty" and never cleared). #323/#367.
                        val sentSnapshot = EditorSnapshot.of(pending.title, pending.content, pending.tags, pending.folder, pending.visibility)
                        val current = _editorState.value
                        val draftMatchesSent = current != null &&
                            ((isCreate && current.noteId == null) || (!isCreate && current.noteId == saved.id)) &&
                            EditorSnapshot.of(current.title, current.content, current.tags, current.folder, current.visibility) == sentSnapshot
                        // If the editor is still on THIS queued note, reflect that the write landed (and,
                        // for a create, capture the new id so later saves PUT it — mirrors saveOnce).
                        _editorState.update { st ->
                            when {
                                st == null -> st
                                isCreate && st.noteId == null -> st.copy(
                                    noteId = saved.id,
                                    images = saved.images,
                                    status = if (draftMatchesSent) SaveStatus.SAVED else SaveStatus.SAVING,
                                )
                                !isCreate && st.noteId == saved.id -> st.copy(
                                    images = saved.images,
                                    status = if (draftMatchesSent) SaveStatus.SAVED else st.status,
                                )
                                else -> st
                            }
                        }
                        // Refresh the dirty baseline to exactly what we sent — but only when the open
                        // draft still equals it (no newer keystroke) — so the editor's isDirty check is
                        // correct against the now-saved note (create AND update; #367).
                        if (draftMatchesSent) {
                            savedSnapshot = sentSnapshot
                        }
                        dequeueIfUnchanged(key, pending)
                    } else {
                        when (classifyNoteFlush(result.exceptionOrNull() ?: RuntimeException())) {
                            NoteFlushDecision.KEEP_RETRY -> break // offline / transient — stop, retry later
                            NoteFlushDecision.DROP_TERMINAL -> dequeueIfUnchanged(key, pending)
                        }
                    }
                }
            } finally {
                flushMutex.unlock()
            }
        }
    }

    private fun observeConnectivity(networkAvailable: Flow<Unit>) {
        viewModelScope.launch {
            networkAvailable.collect { flush() }
        }
    }

    /**
     * Periodic backstop: flaky wifi often regains internet without ever firing a network or socket
     * callback, so poll while the queue is non-empty. The loop exits once the queue drains (and is
     * re-armed on the next [enqueue]), so it never spins when there is nothing to send.
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

    fun deleteNote(id: String) {
        dequeue(id) // a queued save for a note we're deleting can never land — drop it (#323)
        viewModelScope.launch {
            repository.deleteNote(id)
                .onSuccess {
                    _uiState.update { state -> state.copy(notes = state.notes.filter { it.id != id }) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun uploadImage(noteId: String, bytes: ByteArray, filename: String, contentType: String) {
        viewModelScope.launch {
            repository.uploadImage(noteId, bytes, filename, contentType)
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Upload several picked images to a note in one go (#266). Each file is its own request
     * (the backend appends with the right sort_order, so sequential uploads keep order); we
     * upsert the returned note after each so thumbnails appear as they land. The first failure
     * is surfaced as the error (partial success is fine — the successful ones stay attached).
     */
    fun uploadImages(noteId: String, items: List<NoteImageUpload>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            var firstError: String? = null
            for (item in items) {
                repository.uploadImage(noteId, item.bytes, item.filename, item.contentType)
                    .onSuccess { note -> upsert(note) }
                    .onFailure { e -> if (firstError == null) firstError = e.message }
            }
            firstError?.let { msg -> _uiState.update { it.copy(error = msg) } }
        }
    }

    fun removeImage(noteId: String, imageId: String) {
        viewModelScope.launch {
            repository.deleteImage(noteId, imageId)
                .onSuccess { note -> upsert(note) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Authenticated URL for an image. Coil/<img> can set neither an Authorization header nor a
     * WebSocket subprotocol, so the backend accepts the JWT via the `?token=` query param for these
     * image loads only. (The web client moved its WebSocket auth to the Sec-WebSocket-Protocol
     * header; `?token=` is now the image-only fallback.)
     */
    fun imageUrl(noteId: String, imageId: String): String =
        BuildConfig.BASE_URL.trimEnd('/') + "/notes/$noteId/images/$imageId?token=$token"

    fun imageUrl(image: NoteImageDto): String = imageUrl(image.noteId, image.id)

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun upsert(note: NoteDto) {
        _uiState.update { state ->
            val notes = if (state.notes.any { it.id == note.id }) {
                state.notes.map { if (it.id == note.id) note else it }
            } else {
                listOf(note) + state.notes
            }
            state.copy(notes = notes)
        }
        // If the editor is open on this note, fold in the server-owned image gallery (an image
        // upload/remove, or a partner's change) WITHOUT touching the live text draft / caret (#309).
        _editorState.update { st ->
            if (st != null && st.noteId == note.id) st.copy(images = note.images) else st
        }
    }

    private fun observeWebSocket() {
        repository.connectWebSocket(token)
        // On every (re)connect the server is reachable again (web WS `onOpen`): re-sync the list (#269)
        // AND drain the offline auto-save queue (#323) — one of the queue's three retry triggers
        // alongside connectivity + the periodic backstop. The first connect also fires this (harmless).
        repository.setWebSocketOnConnected {
            syncFromServer()
            flush()
        }
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is NotesWebSocketClient.WsEvent.NoteCreated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteUpdated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteDeleted -> {
                        _uiState.update { state ->
                            state.copy(notes = state.notes.filter { it.id != event.note.id })
                        }
                        dequeue(event.note.id) // a queued save for a now-deleted note can never land (#323)
                    }
                }
            }
        }
    }

    /**
     * Called from the UI when the app returns to the foreground (#269). Reconnects the channel if it
     * dropped, **re-syncs** the list and **flushes** the offline auto-save queue. A reconnect fires
     * `onConnected` (which does both), but if the socket survived the background no callback fires, so
     * we also do it here. Either way the list matches the server and any pending edits retry (#323).
     */
    fun ensureConnected() {
        repository.ensureWebSocketConnected()
        syncFromServer()
        flush()
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
        saveJob?.cancel()
        backstopJob?.cancel()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }

    companion object {
        /** Idle delay after the last keystroke before an auto-save fires (#309). */
        const val AUTOSAVE_DEBOUNCE_MS = 1000L

        /** Periodic backstop interval for retrying queued saves when no callback fires (#323). */
        const val FLUSH_INTERVAL_MS = 15_000L
    }
}

/**
 * The comparable set of persisted note fields, used for the dirty check (#309). Tags compare by
 * value; title/folder are NOT pre-trimmed here so that typing a trailing space still counts as a
 * change worth saving once it settles — the trim happens only on the wire in [saveOnce].
 */
private data class EditorSnapshot(
    val title: String,
    val content: String,
    val tags: List<String>,
    val folder: String,
    val visibility: String,
) {
    companion object {
        fun of(title: String, content: String, tags: List<String>, folder: String, visibility: String) =
            EditorSnapshot(title, content, tags, folder, visibility)
    }
}
