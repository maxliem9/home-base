package com.homebase.digest

import com.homebase.db.TodosTable
import com.homebase.notifications.privateTodoListIds
import com.homebase.notifications.todoIsShareable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Content of the daily evening digest: today's completed todos, new inbox items, todos due
 * tomorrow, plus a short look-ahead at tomorrow's family calendar — who's absent and whether the
 * kita is closed (#182, reusing the morning briefing's calendar helper).
 */
data class DigestContent(
    val date: LocalDate,
    val doneToday: List<String>,
    val newInbox: List<String>,
    val dueTomorrow: List<String>,
    val absentTomorrow: List<String>,
    val kitaClosedTomorrow: List<String>,
) {
    val isEmpty: Boolean
        get() = doneToday.isEmpty() && newInbox.isEmpty() && dueTomorrow.isEmpty() &&
            absentTomorrow.isEmpty() && kitaClosedTomorrow.isEmpty()

    /** True if any of the [selected] sections has items — used to skip a fully-empty send (#182). */
    fun hasContentIn(selected: Set<DigestSection>): Boolean =
        (DigestSection.EVENING_DONE_TODAY in selected && doneToday.isNotEmpty()) ||
            (DigestSection.EVENING_NEW_INBOX in selected && newInbox.isNotEmpty()) ||
            (DigestSection.EVENING_DUE_TOMORROW in selected && dueTomorrow.isNotEmpty()) ||
            (DigestSection.EVENING_ABSENT_TOMORROW in selected && absentTomorrow.isNotEmpty()) ||
            (DigestSection.EVENING_KITA_TOMORROW in selected && kitaClosedTomorrow.isNotEmpty())
}

/**
 * Builds the digest from the todos table. The reference [ZoneId] decides where the
 * "day" boundaries fall — timestamps are stored as instants, so done/created filtering
 * uses the [start, nextDay) instant range for the local day.
 *
 * @param sections which content sections to render (#182), re-read each send cycle from
 *   `app_settings`. Defaults to all evening sections so a fresh DB / direct construction keeps the
 *   full recap.
 */
class DigestService(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sections: () -> Set<DigestSection> = { DigestSection.evening.toSet() },
) : DigestSource {

    override fun buildMessage(today: LocalDate): String? {
        val selected = sections()
        val content = buildDigest(today)
        // Skip a quiet evening: only send when at least one *selected* section actually has items
        // (a deselected section never counts), so the chat isn't spammed with all-"— keine —".
        if (!content.hasContentIn(selected)) return null
        return render(content, selected)
    }

    fun buildDigest(today: LocalDate): DigestContent {
        val startOfToday = today.atStartOfDay(zone).toInstant()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
        val tomorrow = today.plusDays(1)

        return transaction {
            // Private-list todos are omitted — the digest goes to the shared chat, so their titles
            // would leak to the other member (see privateTodoListIds).
            val privateLists = privateTodoListIds()

            val doneToday = TodosTable.selectAll().where {
                (TodosTable.status eq "DONE") and
                    (TodosTable.doneAt greaterEq startOfToday) and
                    (TodosTable.doneAt less startOfTomorrow)
            }.filter { it.todoIsShareable(privateLists) }.map { it[TodosTable.title] }

            val newInbox = TodosTable.selectAll().where {
                (TodosTable.status eq "INBOX") and
                    (TodosTable.createdAt greaterEq startOfToday) and
                    (TodosTable.createdAt less startOfTomorrow)
            }.filter { it.todoIsShareable(privateLists) }.map { it[TodosTable.title] }

            val dueTomorrow = TodosTable.selectAll().where {
                TodosTable.dueDate eq tomorrow
            }.filter { it.todoIsShareable(privateLists) }.map { it[TodosTable.title] }

            // Look-ahead: tomorrow's family calendar (#182), same helper the morning briefing uses.
            val calendar = familyCalendarFor(tomorrow)

            DigestContent(today, doneToday, newInbox, dueTomorrow, calendar.absent, calendar.kitaClosed)
        }
    }

    /**
     * Renders the [selected] sections (#182). Unlike the morning briefing, the core recap sections
     * (done / new inbox / due tomorrow) print a "— keine —" placeholder when empty (a deliberately
     * verbose recap); the tomorrow-preview sections are omitted when empty (extra context, not a
     * checklist). Whether to send at all on a quiet day is decided by [buildMessage] via
     * [DigestContent.hasContentIn], so this always returns the rendered text for the selection.
     */
    fun render(content: DigestContent, selected: Set<DigestSection> = DigestSection.evening.toSet()): String {
        val sb = StringBuilder()
        fun emit(section: DigestSection, heading: String, items: List<String>, placeholder: String?) {
            if (section !in selected) return
            appendSection(sb, heading, items, placeholder)
        }
        emit(DigestSection.EVENING_DONE_TODAY, "✅ Heute erledigt", content.doneToday, "— keine —")
        emit(DigestSection.EVENING_NEW_INBOX, "📥 Neu in der Inbox", content.newInbox, "— keine —")
        emit(DigestSection.EVENING_DUE_TOMORROW, "📅 Morgen fällig", content.dueTomorrow, "— keine —")
        emit(DigestSection.EVENING_ABSENT_TOMORROW, "🏖️ Morgen abwesend", content.absentTomorrow, null)
        emit(DigestSection.EVENING_KITA_TOMORROW, "🚸 Kita morgen geschlossen", content.kitaClosedTomorrow, null)
        return "📋 HomeBase — Tagesübersicht ${content.date}$sb"
    }
}
