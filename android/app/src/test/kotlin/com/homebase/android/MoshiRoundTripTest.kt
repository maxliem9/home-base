package com.homebase.android

import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingSuggestion
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateShoppingCategoryRequest
import com.homebase.android.data.model.UpdateShoppingItemRequest
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.data.model.UpsertCategoryRuleRequest
import com.homebase.android.data.model.VersionResponse
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
    fun `VersionResponse parses when the backend drops an empty commit`() {
        // GET /version (#626): `commit` is "" when the backend was built without a git context,
        // and encodeDefaults=false then drops the field entirely. The settings version line must
        // still parse — it renders the bare version in that case.
        val version = moshi.adapter(VersionResponse::class.java).fromJson("""{"version":"1.1.0"}""")

        assertNotNull("minimal VersionResponse payload must parse", version)
        requireNotNull(version)
        assertEquals("1.1.0", version.version)
        assertEquals("omitted commit must default to empty, never null", "", version.commit)
    }

    @Test
    fun `TodoDto parses from a minimal payload with all droppable fields omitted`() {
        // Only the fields the backend always sends for an INBOX todo: id/title/status +
        // created_by/created_at/updated_at (updatedAt has no default, so it is never dropped —
        // like createdAt). description, assignees, dueDate, priority, listId, recurrence,
        // subtasks and doneAt are all omitted (the backend drops them under encodeDefaults=false).
        val json = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "title": "Milch kaufen",
              "status": "INBOX",
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z",
              "updatedAt": "2026-06-13T08:00:00Z"
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
        assertEquals("2026-06-13T08:00:00Z", todo.updatedAt)
        // The crux: an omitted list field must default to empty, never null.
        assertEquals("omitted subtasks must default to empty list", emptyList<Any>(), todo.subtasks)
        assertEquals("omitted assignees must default to empty list", emptyList<Any>(), todo.assignees)
        // Omitted optionals default to null (their declared default).
        assertNull(todo.description)
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
    fun `ShoppingTemplateDto parses from a payload with items omitted`() {
        // An empty template serializes without the `items` key (encodeDefaults=false, #46/#215);
        // the non-null `items` field must default to empty rather than throw on parse.
        val json = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "name": "Wocheneinkauf",
              "createdBy": "max",
              "createdAt": "2026-06-15T08:00:00Z"
            }
        """.trimIndent()

        val template = moshi.adapter(ShoppingTemplateDto::class.java).fromJson(json)

        assertNotNull("minimal ShoppingTemplateDto payload must parse", template)
        requireNotNull(template)
        assertEquals("Wocheneinkauf", template.name)
        assertEquals("omitted items must default to empty list", emptyList<Any>(), template.items)
    }

    @Test
    fun `DigestConfigResponse defaults the section lists to empty when omitted`() {
        // A digest with no sections selected serializes without the `sections` key under
        // encodeDefaults=false; `telegramConfigured` is likewise dropped when false (#189). Both
        // list fields must default to empty, not null — that's what lets the section group render
        // cleanly (and matches the web's `?? []`).
        val json = """
            {
              "time": "20:00",
              "enabled": true,
              "availableSections": ["evening_done_today", "evening_due_tomorrow"]
            }
        """.trimIndent()

        val cfg = moshi.adapter(DigestConfigResponse::class.java).fromJson(json)

        assertNotNull("minimal DigestConfigResponse payload must parse", cfg)
        requireNotNull(cfg)
        assertEquals("20:00", cfg.time)
        assertTrue(cfg.enabled)
        // Omitted `sections` → empty list; omitted `telegramConfigured` → false default.
        assertEquals("omitted sections must default to empty list", emptyList<Any>(), cfg.sections)
        assertEquals(false, cfg.telegramConfigured)
        assertEquals(listOf("evening_done_today", "evening_due_tomorrow"), cfg.availableSections)
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
              "assignees": ["max", "bob"],
              "dueDate": "2026-06-14",
              "subtasks": [
                { "id": "a", "title": "einräumen", "done": false, "sortOrder": 0 },
                { "id": "b", "title": "ausräumen", "done": true, "sortOrder": 1 }
              ],
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z",
              "updatedAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val todo = moshi.adapter(TodoDto::class.java).fromJson(json)

        requireNotNull(todo)
        assertEquals(2, todo.subtasks.size)
        assertEquals("einräumen", todo.subtasks[0].title)
        assertTrue(todo.subtasks[1].done)
        assertEquals(listOf("max", "bob"), todo.assignees)
    }

    @Test
    fun `MealPlanEntryDto parses from the backend payload`() {
        // Recipe-backed entry: the recipe title/category are joined in (#218).
        val json = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "date": "2026-06-15",
              "slot": "DINNER",
              "recipeId": "33333333-3333-3333-3333-333333333333",
              "recipeTitle": "Lasagne",
              "recipeCategory": "DINNER",
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val entry = moshi.adapter(MealPlanEntryDto::class.java).fromJson(json)

        requireNotNull(entry)
        assertEquals("DINNER", entry.slot)
        assertEquals("Lasagne", entry.recipeTitle)
        assertEquals("33333333-3333-3333-3333-333333333333", entry.recipeId)
        assertNull(entry.dishTitle)
    }

    @Test
    fun `MealPlanEntryDto parses a free-text dish payload with no recipe fields`() {
        // Free-text entry (#293): the backend omits the recipe fields (encodeDefaults=false), so
        // Moshi must map the missing keys to null and surface only dishTitle.
        val json = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "date": "2026-06-15",
              "slot": "LUNCH",
              "dishTitle": "Pizza bestellen",
              "createdBy": "max",
              "createdAt": "2026-06-13T08:00:00Z"
            }
        """.trimIndent()

        val entry = moshi.adapter(MealPlanEntryDto::class.java).fromJson(json)

        requireNotNull(entry)
        assertEquals("Pizza bestellen", entry.dishTitle)
        assertNull(entry.recipeId)
        assertNull(entry.recipeTitle)
        assertNull(entry.recipeCategory)
    }

    @Test
    fun `UpdateTodoRequest serializes empty-string clears and omits null unchanged fields`() {
        // Regression for #265: the edit sheet sends "" to CLEAR an optional field and null to
        // leave it UNCHANGED. Moshi (no serializeNulls) drops null but keeps "", so the backend
        // sees the clear sentinel for the fields the user emptied and nothing for the rest.
        val json = moshi.adapter(UpdateTodoRequest::class.java).toJson(
            UpdateTodoRequest(
                title = "Zahnarzt",
                dueDate = "",        // user cleared the date → must reach the backend as ""
                priority = "",       // user cleared the priority → ""
                assignees = listOf("bob", "alice"), // set the whole set
                status = "INBOX",
                // description/listId/recurrence left null → must be omitted entirely
            ),
        )
        val obj = moshi.adapter(Map::class.java).fromJson(json)!!
        assertEquals("Zahnarzt", obj["title"])
        assertEquals("", obj["dueDate"])
        assertEquals("", obj["priority"])
        assertEquals(listOf("bob", "alice"), obj["assignees"])
        assertEquals("INBOX", obj["status"])
        // null fields are dropped (unchanged), never sent as JSON null
        assertTrue("null description must be omitted", !obj.containsKey("description"))
        assertTrue("null listId must be omitted", !obj.containsKey("listId"))
        assertTrue("null recurrence must be omitted", !obj.containsKey("recurrence"))
    }

    @Test
    fun `UpdateTodoRequest sends an explicit empty assignees array to clear, omits null`() {
        // List analog of the #265 clear/unchanged convention: [] must reach the backend as an
        // explicit empty JSON array (clear all assignees), while a null list is omitted (unchanged).
        // Moshi keeps a non-null empty list but drops null — exactly the two sentinels we need.
        val cleared = moshi.adapter(Map::class.java).fromJson(
            moshi.adapter(UpdateTodoRequest::class.java).toJson(UpdateTodoRequest(assignees = emptyList())),
        )!!
        assertEquals("empty list must serialize as [] (clear all)", emptyList<Any>(), cleared["assignees"])

        val unchanged = moshi.adapter(Map::class.java).fromJson(
            moshi.adapter(UpdateTodoRequest::class.java).toJson(UpdateTodoRequest(assignees = null)),
        )!!
        assertTrue("null assignees must be omitted (unchanged)", !unchanged.containsKey("assignees"))
    }

    @Test
    fun `ShoppingItemDto parses category and icon when present`() {
        // Categorized item (#400): the backend resolves and sends category + icon.
        val json = """
            {
              "id": "44444444-4444-4444-4444-444444444444",
              "name": "Milch",
              "listId": "55555555-5555-5555-5555-555555555555",
              "checked": false,
              "category": "DAIRY",
              "icon": "🥛",
              "createdBy": "max",
              "createdAt": "2026-06-20T08:00:00Z"
            }
        """.trimIndent()

        val item = moshi.adapter(ShoppingItemDto::class.java).fromJson(json)

        requireNotNull(item)
        assertEquals("Milch", item.name)
        assertEquals("DAIRY", item.category)
        assertEquals("🥛", item.icon)
    }

    @Test
    fun `ShoppingItemDto defaults category and icon to null when omitted`() {
        // Legacy row (#400): the backend omits category/icon (encodeDefaults=false) for an
        // unresolved item; the nullable defaults must map the missing keys to null, not throw.
        val json = """
            {
              "id": "44444444-4444-4444-4444-444444444444",
              "name": "Irgendwas",
              "checked": true,
              "createdBy": "max",
              "createdAt": "2026-06-20T08:00:00Z"
            }
        """.trimIndent()

        val item = moshi.adapter(ShoppingItemDto::class.java).fromJson(json)

        requireNotNull(item)
        assertEquals("Irgendwas", item.name)
        assertNull("omitted category must default to null", item.category)
        assertNull("omitted icon must default to null", item.icon)
    }

    @Test
    fun `ShoppingSuggestion parses a full payload and defaults count when omitted`() {
        // A suggestion from GET /shopping/suggestions (#400): name/category/icon always sent,
        // count droppable (defaults to 0 under encodeDefaults=false for a baseline-catalog entry).
        val withCount = moshi.adapter(ShoppingSuggestion::class.java).fromJson(
            """
                { "name": "Brot", "category": "BAKERY", "icon": "🍞", "count": 12 }
            """.trimIndent(),
        )
        requireNotNull(withCount)
        assertEquals("Brot", withCount.name)
        assertEquals("BAKERY", withCount.category)
        assertEquals("🍞", withCount.icon)
        assertEquals(12, withCount.count)

        val noCount = moshi.adapter(ShoppingSuggestion::class.java).fromJson(
            """
                { "name": "Tofu", "category": "OTHER", "icon": "🛒" }
            """.trimIndent(),
        )
        requireNotNull(noCount)
        assertEquals("omitted count must default to 0", 0, noCount.count)
    }

    @Test
    fun `ShoppingCategoryDto parses a full catalog row (#411)`() {
        // GET /shopping/categories sends every field (none has a default on the backend DTO, so
        // none is droppable) — full payload must round-trip, including the isBuiltin flag.
        val json = """
            { "key": "PRODUCE", "label": "Obst & Gemüse", "emoji": "🥦", "sortOrder": 0, "isBuiltin": true }
        """.trimIndent()

        val cat = moshi.adapter(ShoppingCategoryDto::class.java).fromJson(json)

        requireNotNull(cat)
        assertEquals("PRODUCE", cat.key)
        assertEquals("Obst & Gemüse", cat.label)
        assertEquals("🥦", cat.emoji)
        assertEquals(0, cat.sortOrder)
        assertEquals(true, cat.isBuiltin)
    }

    @Test
    fun `ShoppingCategoryRuleDto parses an auto-resolve rule (#411)`() {
        // GET /shopping/category-rules: normalizedName/displayName/category/icon, all always sent.
        val json = """
            { "normalizedName": "milch", "displayName": "Milch", "category": "DAIRY", "icon": "🥛" }
        """.trimIndent()

        val rule = moshi.adapter(ShoppingCategoryRuleDto::class.java).fromJson(json)

        requireNotNull(rule)
        assertEquals("milch", rule.normalizedName)
        assertEquals("Milch", rule.displayName)
        assertEquals("DAIRY", rule.category)
        assertEquals("🥛", rule.icon)
    }

    @Test
    fun `UpdateShoppingCategoryRequest serializes only the sortOrder for a reorder and omits null fields`() {
        // The reorder swap (#411) sends just {sortOrder}; label/emoji are left null and must be
        // dropped (no serializeNulls), so the PUT touches only the order.
        val json = moshi.adapter(UpdateShoppingCategoryRequest::class.java).toJson(
            UpdateShoppingCategoryRequest(sortOrder = 3),
        )
        val obj = moshi.adapter(Map::class.java).fromJson(json)!!
        assertEquals(3.0, obj["sortOrder"]) // JSON numbers parse back as Double
        assertTrue("null label must be omitted", !obj.containsKey("label"))
        assertTrue("null emoji must be omitted", !obj.containsKey("emoji"))
    }

    @Test
    fun `UpsertCategoryRuleRequest omits a null icon so the backend keeps or defaults it`() {
        // A category-only rule edit (#411) sends {displayName, category} with no icon — the omitted
        // icon must NOT serialize as null (the backend keeps the existing icon / defaults to 🛒).
        val json = moshi.adapter(UpsertCategoryRuleRequest::class.java).toJson(
            UpsertCategoryRuleRequest(displayName = "Milch", category = "DAIRY"),
        )
        val obj = moshi.adapter(Map::class.java).fromJson(json)!!
        assertEquals("Milch", obj["displayName"])
        assertEquals("DAIRY", obj["category"])
        assertTrue("null icon must be omitted", !obj.containsKey("icon"))
    }

    @Test
    fun `UpdateShoppingItemRequest serializes only the category for a move and omits null fields`() {
        // The "In Kategorie verschieben" move (#400) sends just {category}; every other optional
        // field is left null and must be dropped (no serializeNulls), so the backend touches only
        // the category.
        val json = moshi.adapter(UpdateShoppingItemRequest::class.java).toJson(
            UpdateShoppingItemRequest(category = "DRINKS"),
        )
        val obj = moshi.adapter(Map::class.java).fromJson(json)!!
        assertEquals("DRINKS", obj["category"])
        assertTrue("null name must be omitted", !obj.containsKey("name"))
        assertTrue("null listId must be omitted", !obj.containsKey("listId"))
        assertTrue("null checked must be omitted", !obj.containsKey("checked"))
        assertTrue("null icon must be omitted", !obj.containsKey("icon"))
    }
}
