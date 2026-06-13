package com.homebase.android

import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.TodoDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for Moshi-codegen tolerance to omitted fields (issue #156, PR #150/#142).
 *
 * The backend serializes with `encodeDefaults=false`, so it OMITS every field whose value
 * equals its default — including `null` optionals and empty lists (see root CLAUDE.md,
 * "JSON-Serialisierung"). KSP codegen is stricter than the old reflection: a non-nullable
 * field WITHOUT a Kotlin default that the backend omits would THROW on parse (reflection
 * would have silently filled the default). The `*RepositoryTest`s mock [HomeBaseApi] and
 * never parse real JSON, so nothing else in CI catches a future divergence.
 *
 * These tests feed a MINIMAL payload (only the always-sent required fields, every droppable
 * field omitted) through the app's configured Moshi and assert the parse succeeds with list
 * fields defaulting to empty (not null). They fail the moment someone adds a non-nullable,
 * no-default field to a response DTO the backend can drop.
 *
 * [moshi] is built exactly like the app's instance (`AppContainer` and every WebSocket client):
 * `addLast(KotlinJsonAdapterFactory())` so the generated `@JsonClass(generateAdapter = true)`
 * adapters take precedence and the reflective factory is only the fallback — NOT a bare
 * `Moshi.Builder()`. Replicating the builder (rather than reusing `AppContainer.moshi`, which is
 * private and needs an Android `Context`) keeps this a pure JVM unit test while still exercising
 * the real generated adapters.
 */
class MoshiRoundTripTest {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `TodoDto parses from a minimal payload with all droppable fields omitted`() {
        // Only the fields the backend always sends for an INBOX todo: id/title/status +
        // created_by/created_at. description, assignee, dueDate, priority, listId, recurrence,
        // subtasks and doneAt are all omitted (the backend drops them under encodeDefaults=false).
        val json = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "title": "Milch kaufen",
              "status": "INBOX",
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val todo = moshi.adapter(TodoDto::class.java).fromJson(json)

        assertNotNull("minimal TodoDto payload must parse", todo)
        requireNotNull(todo)
        assertEquals("11111111-1111-1111-1111-111111111111", todo.id)
        assertEquals("Milch kaufen", todo.title)
        assertEquals("INBOX", todo.status)
        assertEquals("max", todo.createdBy)
        assertEquals("2026-06-13T08:00:00Z", todo.createdAt)
        // The crux: an omitted list field must default to empty, never null.
        assertEquals("omitted subtasks must default to empty list", emptyList<Any>(), todo.subtasks)
        // Omitted optionals default to null (their declared default).
        assertNull(todo.description)
        assertNull(todo.assignee)
        assertNull(todo.dueDate)
        assertNull(todo.priority)
        assertNull(todo.listId)
        assertNull(todo.recurrence)
        assertNull(todo.doneAt)
    }

    @Test
    fun `RecipeDto parses from a minimal payload with both list fields omitted`() {
        // RecipeDto carries TWO embedded list fields (ingredients, steps) plus an optional
        // nested cover image — a recipe with none of those serializes without any of the keys.
        val json = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "title": "Pfannkuchen",
              "servings": 4,
              "category": "BREAKFAST",
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z",
              "updatedAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val recipe = moshi.adapter(RecipeDto::class.java).fromJson(json)

        assertNotNull("minimal RecipeDto payload must parse", recipe)
        requireNotNull(recipe)
        assertEquals("Pfannkuchen", recipe.title)
        assertEquals(4, recipe.servings)
        assertEquals("BREAKFAST", recipe.category)
        // Both omitted embedded lists must default to empty, never null.
        assertEquals("omitted ingredients must default to empty list", emptyList<Any>(), recipe.ingredients)
        assertEquals("omitted steps must default to empty list", emptyList<Any>(), recipe.steps)
        assertNull(recipe.image)
        assertNull(recipe.description)
        assertNull(recipe.prepTimeMinutes)
        assertNull(recipe.cookTimeMinutes)
    }

    @Test
    fun `generated adapter populates embedded list elements when present`() {
        // Counterpart to the omission tests: when the backend DOES send the nested lists, the
        // codegen adapter must materialise them (proves the empty-list default isn't masking a
        // dropped payload). Keeps the round-trip honest end to end.
        val json = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "title": "Geschirr spülen",
              "status": "PLANNED",
              "assignee": "max",
              "dueDate": "2026-06-14",
              "subtasks": [
                { "id": "a", "title": "einräumen", "done": false, "sortOrder": 0 },
                { "id": "b", "title": "ausräumen", "done": true, "sortOrder": 1 }
              ],
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val todo = moshi.adapter(TodoDto::class.java).fromJson(json)

        requireNotNull(todo)
        assertEquals(2, todo.subtasks.size)
        assertEquals("einräumen", todo.subtasks[0].title)
        assertTrue(todo.subtasks[1].done)
        assertEquals("max", todo.assignee)
    }
}
