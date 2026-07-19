package com.homebase.model

/**
 * Typed domain values (#556). These replace the ad-hoc `VALID_*`/`FREQUENCIES` string sets that the
 * todo domain used to validate against by hand — the enum *is* the single source of the valid values,
 * and validation is now a typed parse.
 *
 * The **wire and DB representation stays the enum name** (`TodoStatus.INBOX` ↔ `"INBOX"`), so the
 * serialized payloads and stored strings are byte-identical to before. DTO fields deliberately keep
 * their `String` type on the wire: the recurrence `freq` carries a `"NONE"` clear sentinel (not a
 * frequency), and parsing in the service — rather than at kotlinx deserialization — lets a bad value
 * still surface as its **specific** `INVALID_STATUS`/`INVALID_PRIORITY`/… code (which the clients map,
 * #558) instead of a generic body-parse error.
 *
 * [parse] returns null for an unknown/absent value; the caller turns that into the matching error.
 */
enum class TodoStatus {
    INBOX, PLANNED, DONE;
    companion object {
        fun parse(wire: String?): TodoStatus? = entries.firstOrNull { it.name == wire }
    }
}

enum class TodoPriority {
    LOW, MEDIUM, HIGH;
    companion object {
        fun parse(wire: String?): TodoPriority? = entries.firstOrNull { it.name == wire }
    }
}

enum class ListVisibility {
    SHARED, PRIVATE;
    companion object {
        fun parse(wire: String?): ListVisibility? = entries.firstOrNull { it.name == wire }
    }
}

enum class RecurrenceFreq {
    DAILY, WEEKLY, MONTHLY;
    companion object {
        /** Parses a recurrence frequency. The `"NONE"` clear sentinel is NOT a frequency → null here;
         *  the service treats that separately (remove the rule) from an actually-invalid value. */
        fun parse(wire: String?): RecurrenceFreq? = entries.firstOrNull { it.name == wire }
    }
}
