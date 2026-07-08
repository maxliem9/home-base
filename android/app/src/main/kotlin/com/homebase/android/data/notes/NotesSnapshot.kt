package com.homebase.android.data.notes

import com.homebase.android.data.model.NoteDto
import com.squareup.moshi.JsonClass

/**
 * The notes screen's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous notes instead of an empty screen (#520,
 * rolling out the shopping read-cache #517 to the other views).
 *
 * Only the **unfiltered** note list is cached ([notes]) — the one dataset the screen loads. The cache
 * is written only while the search query is blank (see `NotesViewModel.restoreAndMirrorSnapshot`), so
 * a filtered view can never poison it; folders are derived from each note's `folder` field, so they
 * come along for free. The open editor draft is deliberately not cached — it is transient UI state.
 */
@JsonClass(generateAdapter = true)
data class NotesSnapshot(
    val notes: List<NoteDto> = emptyList(),
)
