package com.homebase

import com.homebase.model.AbsenceStateDto
import com.homebase.model.SubtaskDto
import com.homebase.model.TodoDto
import com.homebase.plugins.appJson
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard-Test für Konvention #46 (encodeDefaults = false, Issue #109).
 *
 * Sichergestellt wird:
 *  1. Felder mit Default-Wert `null` fehlen im JSON (Negativ-Fall).
 *  2. Felder mit Default-Wert `emptyList()` fehlen im JSON (Negativ-Fall).
 *  3. Gesetzte Felder erscheinen im JSON (Positiv-Fall — verhindert, dass der
 *     Test trivial durch „Feld immer weg" erfüllt wird).
 *
 * Der Test verwendet [appJson] aus Serialization.kt direkt, damit er genau
 * dieselbe Konfiguration prüft, die Ktor im Betrieb einsetzt. Wer encodeDefaults
 * in Serialization.kt auf `true` setzt, sieht diesen Test sofort rot.
 */
class SerializationConventionTest {

    // ── TodoDto — nullable Default-Felder + emptyList()-Default ──────────────

    @Test
    fun `TodoDto - nullable Felder mit null-Default fehlen im JSON`() {
        val todo = TodoDto(
            id = "abc",
            title = "Einkaufen",
            // description = null  (Default — darf im JSON nicht auftauchen)
            status = "INBOX",
            // assignees = emptyList(), dueDate = null, priority = null,
            // listId = null, recurrence = null, doneAt = null
            createdBy = "max",
            createdAt = "2026-06-11T10:00:00Z"
        )
        val json = appJson.encodeToString(TodoDto.serializer(), todo)

        // Felder, die auf ihrem null-Default stehen, dürfen nicht erscheinen
        assertFalse(json.contains("\"description\""),
            "description (null) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"assignees\""),
            "assignees (leere Liste) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"dueDate\""),
            "dueDate (null) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"priority\""),
            "priority (null) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"listId\""),
            "listId (null) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"recurrence\""),
            "recurrence (null) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"doneAt\""),
            "doneAt (null) darf nicht im JSON stehen, war: $json")
    }

    @Test
    fun `TodoDto - leere subtasks-Liste fehlt im JSON`() {
        val todo = TodoDto(
            id = "abc",
            title = "Einkaufen",
            status = "INBOX",
            // subtasks = emptyList()  (Default — darf im JSON nicht auftauchen)
            createdBy = "max",
            createdAt = "2026-06-11T10:00:00Z"
        )
        val json = appJson.encodeToString(TodoDto.serializer(), todo)

        assertFalse(json.contains("\"subtasks\""),
            "subtasks (emptyList) darf nicht im JSON stehen, war: $json")
    }

    @Test
    fun `TodoDto - gesetzte Felder erscheinen im JSON (Positiv-Fall)`() {
        val todo = TodoDto(
            id = "abc",
            title = "Einkaufen",
            description = "Milch und Brot",
            status = "PLANNED",
            assignees = listOf("max"),
            dueDate = "2026-06-12",
            priority = "HIGH",
            subtasks = listOf(SubtaskDto(id = "s1", title = "Milch", done = false, sortOrder = 0)),
            createdBy = "max",
            createdAt = "2026-06-11T10:00:00Z"
        )
        val json = appJson.encodeToString(TodoDto.serializer(), todo)

        assertTrue(json.contains("\"description\""),
            "description (gesetzt) muss im JSON stehen, war: $json")
        assertTrue(json.contains("\"assignees\""),
            "assignees (gesetzt) muss im JSON stehen, war: $json")
        assertTrue(json.contains("\"dueDate\""),
            "dueDate (gesetzt) muss im JSON stehen, war: $json")
        assertTrue(json.contains("\"priority\""),
            "priority (gesetzt) muss im JSON stehen, war: $json")
        assertTrue(json.contains("\"title\""),
            "title (gesetzt) muss im JSON stehen, war: $json")
        // Befüllte Liste (kein Default) muss erscheinen — fängt einen "Liste immer
        // weggelassen"-Fehler, den der emptyList()-Negativfall allein nicht sieht.
        assertTrue(json.contains("\"subtasks\""),
            "subtasks (befüllt) muss im JSON stehen, war: $json")
    }

    // ── AbsenceStateDto — nur emptyList()-Defaults (vollständige Listen) ─────

    @Test
    fun `AbsenceStateDto - alle leeren Listen fehlen im JSON`() {
        // Alle Felder bleiben auf ihrem emptyList()-Default
        val state = AbsenceStateDto()
        val json = appJson.encodeToString(AbsenceStateDto.serializer(), state)

        assertFalse(json.contains("\"users\""),
            "users (emptyList) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"absences\""),
            "absences (emptyList) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"partTime\""),
            "partTime (emptyList) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"kitaClosures\""),
            "kitaClosures (emptyList) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"customHolidays\""),
            "customHolidays (emptyList) darf nicht im JSON stehen, war: $json")
        assertFalse(json.contains("\"settings\""),
            "settings (emptyList) darf nicht im JSON stehen, war: $json")
    }

    @Test
    fun `AbsenceStateDto - gesetzte Felder erscheinen im JSON (Positiv-Fall)`() {
        val state = AbsenceStateDto(users = listOf("max", "lena"))
        val json = appJson.encodeToString(AbsenceStateDto.serializer(), state)

        assertTrue(json.contains("\"users\""),
            "users (gesetzt) muss im JSON stehen, war: $json")
        // Übrige leere Listen dürfen trotzdem nicht erscheinen
        assertFalse(json.contains("\"absences\""),
            "absences (emptyList) darf nicht im JSON stehen, war: $json")
    }
}
