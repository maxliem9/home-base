package com.homebase.android.data.aufgaben

import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.squareup.moshi.JsonClass

/**
 * The tasks screen's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous lists + todos instead of an empty screen
 * (#520, rolling out the shopping read-cache #517 to the other views).
 *
 * Full fidelity: both datasets the screen loads are cached — [lists] (the tabs) and [todos] (the
 * rows, carrying their assignees/subtasks/recurrence). The household-configured "Erledigt"-window
 * (`done_window_days`) is deliberately NOT cached: it is a setting with a safe code default that the
 * view already falls back to before its GET lands, so grouping/counts degrade gracefully offline.
 */
@JsonClass(generateAdapter = true)
data class TodoSnapshot(
    val lists: List<TodoListDto> = emptyList(),
    val todos: List<TodoDto> = emptyList(),
)
