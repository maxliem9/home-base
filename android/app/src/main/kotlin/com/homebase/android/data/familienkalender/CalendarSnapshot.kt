package com.homebase.android.data.familienkalender

import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.KitaClosureDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.squareup.moshi.JsonClass

/**
 * The Familienkalender's last-known overlay, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous month instead of an empty grid (#520, rolling
 * out the shopping read-cache #517 to the other views).
 *
 * [meals] and [events] are **range-scoped** to the visible month, so [monthAnchor] (ISO first-of-month)
 * is cached with them and they are only seeded when the cached month equals the currently-visible month
 * (see `FamilienkalenderViewModel.restoreAndMirrorSnapshot`). [todos] and the absence-derived
 * [absences]/[kitaClosures] are not month-scoped (the grid buckets them by date), so they seed freely.
 */
@JsonClass(generateAdapter = true)
data class CalendarSnapshot(
    val monthAnchor: String = "",
    val todos: List<TodoDto> = emptyList(),
    val absences: List<AbsenceDto> = emptyList(),
    val kitaClosures: List<KitaClosureDto> = emptyList(),
    val meals: List<MealPlanEntryDto> = emptyList(),
    val events: List<CalendarEventDto> = emptyList(),
)
