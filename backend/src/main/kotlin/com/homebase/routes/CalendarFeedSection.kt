package com.homebase.routes

/**
 * Stable identifiers for every category the iCal subscription feed (issue #427) can include, so a
 * subscriber can tailor what their own feed carries. The [id] is persisted PER USER in `user_prefs`
 * (key [com.homebase.db.UserPrefsTable.CALENDAR_FEED_SECTIONS], a compact CSV) and sent over the
 * config API, so it must stay constant even if labels change. An unset selection means "all"
 * (back-compat with the pre-toggle feed, which always included everything).
 */
enum class CalendarFeedSection(val id: String) {
    TODOS("todos"),
    ABSENCES("absences"),
    PART_TIME("parttime"),
    KITA("kita"),
    MEALS("meals"),
    EVENTS("events");

    companion object {
        /** All sections in display order (drives the API's availableSections + the client checkboxes). */
        val all = listOf(TODOS, ABSENCES, PART_TIME, KITA, MEALS, EVENTS)

        /**
         * Parses a persisted CSV of section ids into the selected set. A **null** value — the key
         * was never written, i.e. the fresh default — selects all. A present value (even an empty
         * string, the "deselect everything" state) is parsed literally, so an empty selection stays
         * empty rather than springing back to all. Unknown/stale ids are ignored.
         */
        fun parseSelection(csv: String?): Set<CalendarFeedSection> {
            if (csv == null) return all.toSet()
            val byId = all.associateBy { it.id }
            return csv.split(",").mapNotNull { byId[it.trim()] }.toSet()
        }
    }
}
