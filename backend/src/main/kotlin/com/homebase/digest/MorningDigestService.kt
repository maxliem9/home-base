package com.homebase.digest

import com.homebase.db.AbsencesTable
import com.homebase.db.KitaClosuresTable
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

class MorningDigestService : DigestSource {

    fun buildDigest(today: LocalDate): MorningDigestContent = transaction {
        val dueToday = TodosTable.selectAll().where {
            (TodosTable.status neq "DONE") and (TodosTable.dueDate eq today)
        }.orderBy(TodosTable.title, SortOrder.ASC).map { it[TodosTable.title] }

        val overdue = TodosTable.selectAll().where {
            (TodosTable.status neq "DONE") and (TodosTable.dueDate less today)
        }.orderBy(TodosTable.dueDate, SortOrder.ASC).map { it[TodosTable.title] }

        // The standing triage pile: unplanned AND undated. A still-INBOX todo that has a due
        // date already shows under dueToday/overdue, so excluding dated ones keeps it from
        // being listed twice (the common case — INBOX items have no due date — is unaffected).
        val inbox = TodosTable.selectAll().where {
            (TodosTable.status eq "INBOX") and (TodosTable.dueDate.isNull())
        }.orderBy(TodosTable.createdAt, SortOrder.ASC).map { it[TodosTable.title] }

        val absent = AbsencesTable.selectAll().where { AbsencesTable.date eq today }
            .orderBy(AbsencesTable.userId, SortOrder.ASC)
            .map { formatAbsence(it[AbsencesTable.userId], it[AbsencesTable.type], it[AbsencesTable.half]) }

        val kitaClosed = KitaClosuresTable.selectAll().where { KitaClosuresTable.date eq today }
            .orderBy(KitaClosuresTable.label, SortOrder.ASC)
            .map { it[KitaClosuresTable.label] }

        MorningDigestContent(today, dueToday, overdue, inbox, absent, kitaClosed)
    }

    override fun buildMessage(today: LocalDate): String? =
        buildDigest(today).takeUnless { it.isEmpty }?.let { render(it) }

    fun render(content: MorningDigestContent): String {
        val sb = StringBuilder()
        sb.append("🌅 HomeBase — Guten Morgen! ").append(content.date)
        section(sb, "📅 Heute fällig", content.dueToday)
        section(sb, "⚠️ Überfällig", content.overdue)
        section(sb, "📥 Inbox", content.inbox)
        section(sb, "🏖️ Heute abwesend", content.absent)
        section(sb, "🚸 Kita geschlossen", content.kitaClosed)
        return sb.toString()
    }

    // Unlike the evening digest (which prints "— keine —" under every heading), a morning
    // briefing omits empty sections so only what needs attention shows. An all-empty briefing
    // is skipped before sending (see [buildMessage] / [MorningDigestContent.isEmpty]).
    private fun section(sb: StringBuilder, heading: String, items: List<String>) {
        if (items.isEmpty()) return
        sb.append("\n\n").append(heading)
        items.forEach { sb.append("\n• ").append(it) }
    }

    private fun formatAbsence(userId: String, type: String, half: String?): String {
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
}
