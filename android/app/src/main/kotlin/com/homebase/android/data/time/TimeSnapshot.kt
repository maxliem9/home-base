package com.homebase.android.data.time

import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.TimeForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.squareup.moshi.JsonClass

/**
 * The time-tracking screen's last-known data, persisted via a [com.homebase.android.data.cache.SnapshotStore]
 * so a cold start with no connection shows the previous entries instead of an empty screen (#520,
 * rolling out the shopping read-cache #517 to the other views).
 *
 * The raw fetched datasets are cached; the derived `running`/`othersRunning` timers are recomputed from
 * [entries] on restore (see `TimeViewModel.restoreAndMirrorSnapshot`). The forecast's fetch timestamp
 * (`forecastAt`) is deliberately not cached — it only drives the live Soll/Ist tick, which stays static
 * offline until a real fetch lands.
 */
@JsonClass(generateAdapter = true)
data class TimeSnapshot(
    val projects: List<ProjectDto> = emptyList(),
    val entries: List<TimeEntryDto> = emptyList(),
    val users: List<String> = emptyList(),
    val forecast: TimeForecastDto? = null,
    val targets: List<WorkTargetDto> = emptyList(),
)
