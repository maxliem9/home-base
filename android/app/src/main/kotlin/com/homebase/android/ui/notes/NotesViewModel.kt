package com.homebase.android.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.android.BuildConfig
import com.homebase.android.data.model.CreateNoteRequest
import com.homebase.android.data.model.NoteDto
import com.homebase.android.data.model.NoteImageDto
import com.homebase.android.data.model.UpdateNoteRequest
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.websocket.NotesWebSocketClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesUiState(
    val notes: List<NoteDto> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** One picked image to upload to a note: its bytes + the original filename and MIME type. */
data class NoteImageUpload(val bytes: ByteArray, val filename: String, val contentType: String)

/** Auto-save status shown in the editor app bar (#309). */
enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState(isLoading = true))
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    // --- Editor / auto-save state (#309/#310) ---
    private val _editorState = MutableStateFlow<NoteEditorState?>(null)
    val editorState: StateFlow<NoteEditorState?> = _editorState.asStateFlow()

    /** Debounce job: cancelled on each keystroke, fires the save after [AUTOSAVE_DEBOUNCE_MS]. */
    private var autosaveJob: Job? = null

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
        if (id != null) deleteNote(id)
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
        if (id == null && title.isBlank()) return false // never create an untitled note
        // Snapshot of exactly what we send, so a no-op repeat is skipped and a change mid-flight stays dirty.
        val sentSnapshot = EditorSnapshot.of(draft.title, draft.content, draft.tags, draft.folder, draft.visibility)
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
                savedSnapshot = sentSnapshot
                // Capture the new id (first create) + refresh server-owned fields, but keep the live
                // text draft so in-flight keystrokes survive.
                _editorState.update { st ->
                    st?.copy(noteId = note.id, images = note.images, status = SaveStatus.SAVED)
                }
                true
            },
            onFailure = { e ->
                setEditorStatus(SaveStatus.ERROR)
                _uiState.update { it.copy(error = e.message) }
                false
            },
        )
    }

    private fun setEditorStatus(status: SaveStatus) {
        _editorState.update { it?.copy(status = status) }
    }

    fun deleteNote(id: String) {
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
        // Re-sync on every (re)connect — the "server reachable again" signal (#269, mirrors the time
        // channel + shopping queue flush). The first connect also fires this; that one re-sync
        // overlaps load()'s fetch (harmless — a cheap GET at cold start), and every later reconnect
        // then reliably re-syncs without bespoke state.
        repository.setWebSocketOnConnected { syncFromServer() }
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                when (event) {
                    is NotesWebSocketClient.WsEvent.NoteCreated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteUpdated -> upsert(event.note)
                    is NotesWebSocketClient.WsEvent.NoteDeleted ->
                        _uiState.update { state ->
                            state.copy(notes = state.notes.filter { it.id != event.note.id })
                        }
                }
            }
        }
    }

    /**
     * Called from the UI when the app returns to the foreground (#269). Reconnects the channel if it
     * dropped **and** re-syncs from the server: a reconnect fires `onConnected` → [syncFromServer],
     * but if the socket survived the background no callback fires, so we also refetch here. Either way
     * the list matches the server after a backgrounded change elsewhere.
     */
    fun ensureConnected() {
        repository.ensureWebSocketConnected()
        syncFromServer()
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
        saveJob?.cancel()
        repository.setWebSocketOnConnected(null)
        repository.disconnectWebSocket()
    }

    companion object {
        /** Idle delay after the last keystroke before an auto-save fires (#309). */
        const val AUTOSAVE_DEBOUNCE_MS = 1000L
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
