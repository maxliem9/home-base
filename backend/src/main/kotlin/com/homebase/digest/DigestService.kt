package com.homebase.digest

import com.homebase.db.TodosTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Content of the daily digest as described in CLAUDE.md:
 * today's completed todos, new inbox items, and todos due tomorrow.
 */
data class DigestContent(
    val date: LocalDate,
    val doneToday: List<String>,
    val newInbox: List<String>,
    val dueTomorrow: List<String>,
) {
    val isEmpty: Boolean
        get() = doneToday.isEmpty() && newInbox.isEmpty() && dueTomorrow.isEmpty()
}

/**
 * Builds the digest from the todos table. The reference [ZoneId] decides where the
 * "day" boundaries fall — timestamps are stored as instants, so done/created filtering
 * uses the [start, nextDay) instant range for the local day.
 */
class DigestService(private val zone: ZoneId = ZoneId.systemDefault()) : DigestSource {

    override fun buildMessage(today: LocalDate): String? =
        buildDigest(today).takeUnless { it.isEmpty }?.let { render(it) }

    fun buildDigest(today: LocalDate): DigestContent {
        val startOfToday = today.atStartOfDay(zone).toInstant()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
        val tomorrow = today.plusDays(1)

        return transaction {
            val doneToday = TodosTable.selectAll().where {
                (TodosTable.status eq "DONE") and
                    (TodosTable.doneAt greaterEq startOfToday) and
                    (TodosTable.doneAt less startOfTomorrow)
            }.map { it[TodosTable.title] }

            val newInbox = TodosTable.selectAll().where {
                (TodosTable.status eq "INBOX") and
                    (TodosTable.createdAt greaterEq startOfToday) and
                    (TodosTable.createdAt less startOfTomorrow)
            }.map { it[TodosTable.title] }

            val dueTomorrow = TodosTable.selectAll().where {
                TodosTable.dueDate eq tomorrow
            }.map { it[TodosTable.title] }

            DigestContent(today, doneToday, newInbox, dueTomorrow)
        }
    }

    fun render(content: DigestContent): String {
        val sb = StringBuilder()
        sb.append("📋 HomeBase — Tagesübersicht ").append(content.date)
        section(sb, "✅ Heute erledigt", content.doneToday)
        section(sb, "📥 Neu in der Inbox", content.newInbox)
        section(sb, "📅 Morgen fällig", content.dueTomorrow)
        return sb.toString()
    }

    private fun section(sb: StringBuilder, heading: String, items: List<String>) {
        sb.append("\n\n").append(heading)
        if (items.isEmpty()) {
            sb.append("\n— keine —")
        } else {
            items.forEach { sb.append("\n• ").append(it) }
        }
    }
}
