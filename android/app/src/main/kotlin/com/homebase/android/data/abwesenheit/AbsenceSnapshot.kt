package com.homebase.android.data.abwesenheit

import com.homebase.android.data.model.AbsenceStateDto
import com.squareup.moshi.JsonClass

/**
 * The Familienkalender's last-known snapshot, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous planner instead of an empty screen (#520,
 * rolling out the shopping read-cache #517 to the other views).
 *
 * The screen already loads its whole state as one [AbsenceStateDto], so the cache is just that object.
 */
@JsonClass(generateAdapter = true)
data class AbsenceSnapshot(
    val data: AbsenceStateDto = AbsenceStateDto(),
)
