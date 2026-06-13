package com.homebase.digest

import com.homebase.db.AbsencesTable
import com.homebase.db.KitaClosuresTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate

/**
 * Stable identifiers for every selectable digest content section (#182). The [id] is what gets
 * persisted in `app_settings` (DIGEST_*_SECTIONS, a CSV) and sent over the config API, so it must
 * stay constant even if headings change. [evening] / [morning] list the sections each digest can
 * render, in display order; an unset selection means "all of them" (back-compat with the pre-#182
 * full digest).
 */
enum class DigestSection(val id: String) {
    // Evening recap (looks back + a short look-ahead).
    EVENING_DONE_TODAY("evening_done_today"),
    EVENING_NEW_INBOX("evening_new_inbox"),
    EVENING_DUE_TOMORROW("evening_due_tomorrow"),
    EVENING_ABSENT_TOMORROW("evening_absent_tomorrow"),
    EVENING_KITA_TOMORROW("evening_kita_tomorrow"),

    // Morning briefing (looks at the day ahead).
    MORNING_DUE_TODAY("morning_due_today"),
    MORNING_OVERDUE("morning_overdue"),
    MORNING_INBOX("morning_inbox"),
    MORNING_ABSENT("morning_absent"),
    MORNING_KITA("morning_kita");

    companion object {
        val evening = listOf(
            EVENING_DONE_TODAY, EVENING_NEW_INBOX, EVENING_DUE_TOMORROW,
            EVENING_ABSENT_TOMORROW, EVENING_KITA_TOMORROW,
        )
        val morning = listOf(
            MORNING_DUE_TODAY, MORNING_OVERDUE, MORNING_INBOX, MORNING_ABSENT, MORNING_KITA,
        )

        /**
         * Parses a persisted CSV of section ids into the set of selected sections, intersected with
         * [allowed] (so a stale id is ignored). A **null** value — the key was never written, i.e.
         * the fresh-DB default — selects all of [allowed], keeping the full digest. A present value
         * (even an empty string, the "deselect everything" state) is parsed literally, so a stored
         * empty selection stays empty rather than springing back to all.
         */
        fun parseSelection(csv: String?, allowed: List<DigestSection>): Set<DigestSection> {
            if (csv == null) return allowed.toSet()
            val byId = allowed.associateBy { it.id }
            return csv.split(",").mapNotNull { byId[it.trim()] }.toSet()
        }
    }
}

/**
 * Family-calendar context for a single day, shared by the morning briefing and the evening
 * preview-of-tomorrow (#182) so the absence/kita queries + label formatting live in one place.
 * Call inside an open Exposed transaction.
 */
data class FamilyCalendarDay(val absent: List<String>, val kitaClosed: List<String>)

fun familyCalendarFor(date: LocalDate): FamilyCalendarDay {
    val absent = AbsencesTable.selectAll().where { AbsencesTable.date eq date }
        .orderBy(AbsencesTable.userId, SortOrder.ASC)
        .map { formatAbsence(it[AbsencesTable.userId], it[AbsencesTable.type], it[AbsencesTable.half]) }

    val kitaClosed = KitaClosuresTable.selectAll().where { KitaClosuresTable.date eq date }
        .orderBy(KitaClosuresTable.label, SortOrder.ASC)
        .map { it[KitaClosuresTable.label] }

    return FamilyCalendarDay(absent, kitaClosed)
}

fun formatAbsence(userId: String, type: String, half: String?): String {
    val typeLabel = when (type) {
        "URLAUB" -> "Urlaub"
        "KRANK" -> "Krank"
        "KIND_KRANK" -> "Kind krank"
        else -> type
    }
    val halfLabel = when (half) {
        "vm" -> " (vormittags)"
        "nm" -> " (nachmittags)"
        else -> ""
    }
    return "$userId — $typeLabel$halfLabel"
}

/**
 * Max items rendered per digest section before the rest is summarized (#167). The cumulative
 * morning lists (overdue / inbox) — and any growing evening section — can otherwise push the
 * rendered message past Telegram's 4096-char limit, which the client then silently drops. Capping
 * the *item count* keeps every section bounded (each item title is itself short); with 10 sections
 * this stays comfortably under 4096.
 */
const val MAX_SECTION_ITEMS = 20

/**
 * Appends a "• "-bulleted section to [sb], capped at [MAX_SECTION_ITEMS] items with a trailing
 * "… und X weitere" line for the remainder (#167), so a large backlog can't blow the Telegram
 * limit. [emptyPlaceholder] (the evening recap's "— keine —") is printed for an empty list; when
 * null the whole section is omitted when empty (the morning briefing's behavior).
 */
fun appendSection(sb: StringBuilder, heading: String, items: List<String>, emptyPlaceholder: String?) {
    if (items.isEmpty()) {
        if (emptyPlaceholder == null) return
        sb.append("\n\n").append(heading).append("\n").append(emptyPlaceholder)
        return
    }
    sb.append("\n\n").append(heading)
    items.take(MAX_SECTION_ITEMS).forEach { sb.append("\n• ").append(it) }
    val overflow = items.size - MAX_SECTION_ITEMS
    if (overflow > 0) sb.append("\n… und ").append(overflow).append(" weitere")
}
