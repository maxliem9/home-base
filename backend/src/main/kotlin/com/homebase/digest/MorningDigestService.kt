package com.homebase.digest

import com.homebase.db.TodosTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

/**
 * Forward-looking "Guten Morgen" briefing (#100 follow-up): what's on for today, what
 * slipped, what still needs planning, plus the family-calendar context (who's absent,
 * whether the kita is closed). Distinct from the evening [DigestService] recap — that one
 * looks back (done today / new inbox / due tomorrow), this one looks at the day ahead.
 *
 * Everything keys off plain calendar dates (todo due dates, absence/kita dates), so unlike
 * the evening recap there's no timezone/instant conversion here — the caller (the scheduler)
 * passes the reference day as [today].
 */
data class MorningDigestContent(
    val date: LocalDate,
    val dueToday: List<String>,
    val overdue: List<String>,
    val inbox: List<String>,
    val absent: List<String>,
    val kitaClosed: List<String>,
) {
    val isEmpty: Boolean
        get() = dueToday.isEmpty() && overdue.isEmpty() && inbox.isEmpty() &&
            absent.isEmpty() && kitaClosed.isEmpty()
}

/**
 * @param sections which content sections to render (#182), re-read each send cycle from
 *   `app_settings`. Defaults to all morning sections so a fresh DB / direct construction keeps
 *   the full briefing.
 */
class MorningDigestService(
    private val sections: () -> Set<DigestSection> = { DigestSection.morning.toSet() },
) : DigestSource {

    fun buildDigest(today: LocalDate): MorningDigestContent = transaction {
        val dueToday = TodosTable.selectAll().where {
            (TodosTable.status neq "DONE") and (TodosTable.dueDate eq today)
        }.orderBy(TodosTable.title, SortOrder.ASC).map { it[TodosTable.title] }

        val overdue = TodosTable.selectAll().where {
            (TodosTable.status neq "DONE") and (TodosTable.dueDate less today)
        }.orderBy(TodosTable.dueDate to SortOrder.ASC, TodosTable.title to SortOrder.ASC)
            .map { it[TodosTable.title] }

        // The standing triage pile: unplanned AND undated. A still-INBOX todo that has a due
        // date already shows under dueToday/overdue, so excluding dated ones keeps it from
        // being listed twice (the common case — INBOX items have no due date — is unaffected).
        val inbox = TodosTable.selectAll().where {
            (TodosTable.status eq "INBOX") and (TodosTable.dueDate.isNull())
        }.orderBy(TodosTable.createdAt, SortOrder.ASC).map { it[TodosTable.title] }

        val calendar = familyCalendarFor(today)

        MorningDigestContent(today, dueToday, overdue, inbox, calendar.absent, calendar.kitaClosed)
    }

    override fun buildMessage(today: LocalDate): String? = render(buildDigest(today), sections())

    /** Renders only the [selected] sections (#182); an all-empty/all-deselected briefing yields null. */
    fun render(content: MorningDigestContent, selected: Set<DigestSection> = DigestSection.morning.toSet()): String? {
        val sb = StringBuilder()
        var any = false
        fun emit(section: DigestSection, heading: String, items: List<String>) {
            if (section !in selected || items.isEmpty()) return
            appendSection(sb, heading, items, emptyPlaceholder = null)
            any = true
        }
        emit(DigestSection.MORNING_DUE_TODAY, "📅 Heute fällig", content.dueToday)
        emit(DigestSection.MORNING_OVERDUE, "⚠️ Überfällig", content.overdue)
        emit(DigestSection.MORNING_INBOX, "📥 Inbox", content.inbox)
        emit(DigestSection.MORNING_ABSENT, "🏖️ Heute abwesend", content.absent)
        emit(DigestSection.MORNING_KITA, "🚸 Kita geschlossen", content.kitaClosed)
        if (!any) return null
        return "🌅 HomeBase — Guten Morgen! ${content.date}$sb"
    }
}
