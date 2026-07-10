package com.homebase

import com.homebase.shopping.GroceryCatalog
import com.homebase.shopping.ShoppingCatalog
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-unit coverage of the resolution algorithm (`ShoppingCatalog.RuleSet`, built here from the
 * `GroceryCatalog` seed — no DB) + `GroceryCatalog.normalize` and the seeded category set.
 */
class GroceryCatalogTest {

    // The resolution algorithm now lives in ShoppingCatalog.RuleSet; feed it the code seed to unit-test.
    private val seedRules = ShoppingCatalog.RuleSet(
        GroceryCatalog.seed.map { ShoppingCatalog.RuleSet.Rule(it.normalized, it.name, it.category, it.icon) },
    )

    @Test
    fun `resolves a known staple to its category and emoji`() {
        val r = seedRules.match("Milch")
        assertEquals("DAIRY", r.category)
        assertEquals("🥛", r.icon)
    }

    @Test
    fun `strips a leading quantity and unit before matching`() {
        assertEquals("PRODUCE", seedRules.match("2 Paprika").category)
        assertEquals("PANTRY", seedRules.match("500 g Mehl").category)
        assertEquals("mehl", GroceryCatalog.normalize("500 g Mehl"))
        assertEquals("paprika", GroceryCatalog.normalize("2 Paprika"))
    }

    @Test
    fun `multi-word and pluralised names still resolve via word-boundary matching`() {
        // adjective/qualifier + known noun → matches the whole word
        assertEquals("PRODUCE", seedRules.match("Bio Tomaten").category)
        assertEquals("PRODUCE", seedRules.match("frische Paprika").category)
        // singular ↔ plural (≤2-char suffix)
        assertEquals("PRODUCE", seedRules.match("Tomate").category)
        // exact compound entry is unaffected
        assertEquals("DAIRY", seedRules.match("Hafermilch").category)
    }

    @Test
    fun `a category-carrying prefix never wins — the #441 bug`() {
        // The classic bug came from a free substring match reading the PREFIX: "Apfelschorle" → apfel
        // (PRODUCE) and "Leberkäse" → käse (DAIRY). Both must resolve by the real head, not the prefix.
        assertEquals("DRINKS", seedRules.match("Apfelschorle").category)     // ↛ apfel/PRODUCE
        assertEquals("BAKERY", seedRules.match("Käsebrot").category)         // ↛ käse/DAIRY (it's bread)
        // "…käse" that is actually meat is pinned as an exact seed entry, resolved before the head rule.
        assertEquals("MEAT_FISH", seedRules.match("Leberkäse").category)
        assertEquals("MEAT_FISH", seedRules.match("Fleischkäse").category)
    }

    @Test
    fun `head-noun lie is corrected even on an already-seeded DB without the exact entry`() {
        // Simulate a prod rule table seeded BEFORE this change: it has "käse"→DAIRY but no "leberkäse"
        // entry. The endsWith head-noun step would otherwise read "Leberkäse" as käse/DAIRY — the
        // in-code HEAD_NOUN_LIES guard must keep it MEAT_FISH regardless.
        val legacy = ShoppingCatalog.RuleSet(
            listOf(
                ShoppingCatalog.RuleSet.Rule("käse", "Käse", "DAIRY", "🧀"),
                ShoppingCatalog.RuleSet.Rule("milch", "Milch", "DAIRY", "🥛"),
            ),
        )
        assertEquals("DAIRY", legacy.match("Käse").category)        // generic cheese still DAIRY
        assertEquals("DAIRY", legacy.match("Vollmilch").category)   // legit head noun via endsWith
        assertEquals("MEAT_FISH", legacy.match("Leberkäse").category) // the lie, corrected in code
    }

    @Test
    fun `compound head noun carries the category (Vollmilch to milch)`() {
        assertEquals("DAIRY", seedRules.match("Vollmilch").category)
        assertEquals("DAIRY", seedRules.match("Buttermilch").category)
        assertEquals("MEAT_FISH", seedRules.match("Leberwurst").category)
        assertEquals("BAKERY", seedRules.match("Knoblauchbrot").category)
    }

    @Test
    fun `unknown name with no known head falls back to OTHER with the cart icon`() {
        val r = seedRules.match("Zaubertrank 3000")
        assertEquals(GroceryCatalog.OTHER, r.category)
        assertEquals(GroceryCatalog.DEFAULT_ICON, r.icon)
    }

    @Test
    fun `category set is the fixed ten in route order`() {
        assertEquals(10, GroceryCatalog.categories.size)
        assertEquals("PRODUCE", GroceryCatalog.categories.first().key)
        assertEquals(GroceryCatalog.OTHER, GroceryCatalog.categories.last().key)
    }
}

/** Route-level coverage of categorization, usage stats / suggestions, and manual overrides. */
class ShoppingCategoryRouteTest {

    private suspend fun ApplicationTestBuilder.token(): String {
        val res = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.addItem(token: String, name: String): JsonObject {
        val res = client.post("/api/v1/shopping") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${JsonPrimitive(name)}}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.suggestions(token: String, q: String? = null): JsonArray {
        val url = "/api/v1/shopping/suggestions" + (q?.let { "?q=$it" } ?: "")
        return Json.parseToJsonElement(client.get(url) { bearerAuth(token) }.bodyAsText()).jsonArray
    }

    @Test
    fun `POST resolves category and icon from the catalog`() = testApplication {
        configureTestApplication()
        val token = token()

        val milch = addItem(token, "Milch")
        assertEquals("DAIRY", milch["category"]?.jsonPrimitive?.content)
        assertEquals("🥛", milch["icon"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST of an unknown item lands in OTHER`() = testApplication {
        configureTestApplication()
        val token = token()

        val item = addItem(token, "Zaubertrank 3000")
        assertEquals("OTHER", item["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `suggestions expose the catalog baseline before any usage`() = testApplication {
        configureTestApplication()
        val token = token()

        val all = suggestions(token)
        assertTrue(all.isNotEmpty(), "catalog baseline should be non-empty on day one")
        val milch = all.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "Milch" }
        assertTrue(milch != null, "Milch should be in the baseline suggestions")
        assertEquals("DAIRY", milch!!["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `usage count climbs and ranks the suggestion to the top`() = testApplication {
        configureTestApplication()
        val token = token()
        addItem(token, "Milch")
        addItem(token, "Milch")

        val matches = suggestions(token, q = "milch").map { it.jsonObject }
        val first = matches.first()
        assertEquals("Milch", first["name"]?.jsonPrimitive?.content)
        assertEquals(2, first["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `PUT override moves the item and is remembered for the next add`() = testApplication {
        configureTestApplication()
        val token = token()

        val pizza = addItem(token, "Pizza")
        assertEquals("FROZEN", pizza["category"]?.jsonPrimitive?.content)
        val id = pizza["id"]!!.jsonPrimitive.content

        val moved = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"category":"PANTRY"}""")
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals("PANTRY", Json.parseToJsonElement(moved.bodyAsText()).jsonObject["category"]?.jsonPrimitive?.content)

        // a fresh add of the same name now inherits the remembered category
        val pizza2 = addItem(token, "Pizza")
        assertEquals("PANTRY", pizza2["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT with an unknown category is rejected`() = testApplication {
        configureTestApplication()
        val token = token()
        val id = addItem(token, "Brot")["id"]!!.jsonPrimitive.content

        val res = client.put("/api/v1/shopping/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"category":"NOT_A_CATEGORY"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `item whose name normalizes to blank is still created`() = testApplication {
        // "+++" has no usable stats key; resolveForItem/recordUsages must skip it gracefully, not 500.
        configureTestApplication()
        val token = token()

        val item = addItem(token, "+++")
        assertEquals("+++", item["name"]?.jsonPrimitive?.content)
        assertEquals("OTHER", item["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `batch add categorizes each created item`() = testApplication {
        configureTestApplication()
        val token = token()
        val listId = Json.parseToJsonElement(
            client.post("/api/v1/shopping/lists") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Wocheneinkauf"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/v1/shopping/batch") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"listId":"$listId","items":[{"name":"Mehl","amount":500,"unit":"g"},{"name":"Tomaten","amount":3}]}""")
        }

        val byName = Json.parseToJsonElement(client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText())
            .jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["category"]?.jsonPrimitive?.content }
        assertEquals("PANTRY", byName["500 g Mehl"])
        assertEquals("PRODUCE", byName["3 Tomaten"])
    }

    // ---- Editable category catalog (#411) ----

    private suspend fun ApplicationTestBuilder.categories(token: String): JsonArray =
        Json.parseToJsonElement(client.get("/api/v1/shopping/categories") { bearerAuth(token) }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.createCategory(token: String, label: String, emoji: String): JsonObject =
        Json.parseToJsonElement(
            client.post("/api/v1/shopping/categories") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"label":${JsonPrimitive(label)},"emoji":${JsonPrimitive(emoji)}}""")
            }.bodyAsText()
        ).jsonObject

    private suspend fun ApplicationTestBuilder.itemById(token: String, id: String): JsonObject =
        Json.parseToJsonElement(client.get("/api/v1/shopping") { bearerAuth(token) }.bodyAsText())
            .jsonArray.map { it.jsonObject }.first { it["id"]?.jsonPrimitive?.content == id }

    @Test
    fun `GET categories returns the seeded builtin catalog in route order`() = testApplication {
        configureTestApplication()
        val token = token()
        val cats = categories(token).map { it.jsonObject }
        assertEquals(10, cats.size)
        assertEquals("PRODUCE", cats.first()["key"]?.jsonPrimitive?.content)
        assertEquals("OTHER", cats.last()["key"]?.jsonPrimitive?.content)
        assertTrue(cats.all { it["isBuiltin"]?.jsonPrimitive?.boolean == true })
    }

    @Test
    fun `POST creates a custom category usable as an item override`() = testApplication {
        configureTestApplication()
        val token = token()
        val created = createCategory(token, "Drogerie", "🧴")
        val key = created["key"]!!.jsonPrimitive.content
        assertEquals("DROGERIE", key)
        assertEquals(false, created["isBuiltin"]?.jsonPrimitive?.boolean)

        // the new key is a valid override target (validation now reads the DB catalog)
        val id = addItem(token, "Wattestäbchen")["id"]!!.jsonPrimitive.content
        val moved = client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"category":"$key"}""")
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals(key, Json.parseToJsonElement(moved.bodyAsText()).jsonObject["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT edits a builtin category's label and emoji`() = testApplication {
        configureTestApplication()
        val token = token()
        val res = client.put("/api/v1/shopping/categories/DAIRY") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"label":"Molkerei","emoji":"🐄"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val dto = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("Molkerei", dto["label"]?.jsonPrimitive?.content)
        assertEquals("🐄", dto["emoji"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE a custom category reassigns its items to OTHER`() = testApplication {
        configureTestApplication()
        val token = token()
        val key = createCategory(token, "Baumarkt", "🔧")["key"]!!.jsonPrimitive.content

        val id = addItem(token, "Schrauben")["id"]!!.jsonPrimitive.content
        client.put("/api/v1/shopping/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"category":"$key"}""")
        }

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/categories/$key") { bearerAuth(token) }.status)
        assertTrue(categories(token).none { it.jsonObject["key"]?.jsonPrimitive?.content == key })
        assertEquals("OTHER", itemById(token, id)["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE a builtin category reassigns items and stops resolving to it`() = testApplication {
        configureTestApplication()
        val token = token()
        val pizza = addItem(token, "Pizza")
        assertEquals("FROZEN", pizza["category"]?.jsonPrimitive?.content)
        val pizzaId = pizza["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/categories/FROZEN") { bearerAuth(token) }.status)

        // existing item reassigned …
        assertEquals("OTHER", itemById(token, pizzaId)["category"]?.jsonPrimitive?.content)
        // … and a fresh add no longer lands in the deleted category (resolve guarded → OTHER)
        assertEquals("OTHER", addItem(token, "Tiefkühlpizza")["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE the OTHER fallback is rejected`() = testApplication {
        configureTestApplication()
        val token = token()
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/shopping/categories/OTHER") { bearerAuth(token) }.status)
        assertTrue(categories(token).any { it.jsonObject["key"]?.jsonPrimitive?.content == "OTHER" })
    }

    // ---- Auto-resolve rules (editable dictionary, #411 PR B) ----

    private suspend fun ApplicationTestBuilder.rules(token: String): JsonArray =
        Json.parseToJsonElement(client.get("/api/v1/shopping/category-rules") { bearerAuth(token) }.bodyAsText()).jsonArray

    @Test
    fun `GET category-rules returns the seeded dictionary`() = testApplication {
        configureTestApplication()
        val token = token()
        val all = rules(token).map { it.jsonObject }
        assertTrue(all.isNotEmpty())
        val milch = all.firstOrNull { it["normalizedName"]?.jsonPrimitive?.content == "milch" }
        assertTrue(milch != null, "the seeded dictionary should contain 'milch'")
        assertEquals("DAIRY", milch!!["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT teaches a rule that drives auto-resolution for a new name`() = testApplication {
        configureTestApplication()
        val token = token()
        // Teach BEFORE the first add — a remembered stats override (from a prior add) would win otherwise.
        val res = client.put("/api/v1/shopping/category-rules") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Quietscheente","category":"HOUSEHOLD","icon":"🦆"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val item = addItem(token, "Quietscheente")
        assertEquals("HOUSEHOLD", item["category"]?.jsonPrimitive?.content)
        assertEquals("🦆", item["icon"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT re-points a seeded rule and keeps its icon when omitted`() = testApplication {
        configureTestApplication()
        val token = token()
        // Pizza ships FROZEN/🍕; re-point the category only (no icon) → 🍕 preserved
        client.put("/api/v1/shopping/category-rules") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Pizza","category":"PANTRY"}""")
        }
        val item = addItem(token, "Pizza")
        assertEquals("PANTRY", item["category"]?.jsonPrimitive?.content)
        assertEquals("🍕", item["icon"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PUT a rule with an unknown category is rejected`() = testApplication {
        configureTestApplication()
        val token = token()
        val res = client.put("/api/v1/shopping/category-rules") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Irgendwas","category":"NOPE"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `DELETE a rule drops the name back to OTHER on the next add`() = testApplication {
        configureTestApplication()
        val token = token()
        // "Marmelade" ships PANTRY; with the rule gone (and no prior add → no stats), it resolves to OTHER
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/category-rules/marmelade") { bearerAuth(token) }.status)
        assertTrue(rules(token).none { it.jsonObject["normalizedName"]?.jsonPrimitive?.content == "marmelade" })
        assertEquals("OTHER", addItem(token, "Marmelade")["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE decodes and removes a multi-word rule`() = testApplication {
        configureTestApplication()
        val token = token()
        // "Passierte Tomaten" ships as a multi-word PANTRY rule (normalized "passierte tomaten")
        assertTrue(rules(token).any { it.jsonObject["normalizedName"]?.jsonPrimitive?.content == "passierte tomaten" })
        val del = client.delete("/api/v1/shopping/category-rules/passierte%20tomaten") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertTrue(rules(token).none { it.jsonObject["normalizedName"]?.jsonPrimitive?.content == "passierte tomaten" })
    }

    @Test
    fun `deleting a category re-points its rules to OTHER`() = testApplication {
        configureTestApplication()
        val token = token()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/categories/FROZEN") { bearerAuth(token) }.status)
        val pizzaRule = rules(token).map { it.jsonObject }.firstOrNull { it["normalizedName"]?.jsonPrimitive?.content == "pizza" }
        assertTrue(pizzaRule != null, "the pizza rule should still exist after its category is deleted")
        assertEquals("OTHER", pizzaRule!!["category"]?.jsonPrimitive?.content)
    }

    // ---- Per-list category sets (#412) ----

    private suspend fun ApplicationTestBuilder.createList(token: String, name: String, ownCategories: Boolean = false): JsonObject =
        Json.parseToJsonElement(
            client.post("/api/v1/shopping/lists") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"name":${JsonPrimitive(name)},"ownCategories":$ownCategories}""")
            }.bodyAsText()
        ).jsonObject

    private suspend fun ApplicationTestBuilder.categoriesFor(token: String, listId: String): JsonArray =
        Json.parseToJsonElement(client.get("/api/v1/shopping/categories?listId=$listId") { bearerAuth(token) }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.createCategoryFor(token: String, label: String, emoji: String, listId: String): JsonObject =
        Json.parseToJsonElement(
            client.post("/api/v1/shopping/categories?listId=$listId") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"label":${JsonPrimitive(label)},"emoji":${JsonPrimitive(emoji)}}""")
            }.bodyAsText()
        ).jsonObject

    private suspend fun ApplicationTestBuilder.addItemTo(token: String, name: String, listId: String?): JsonObject {
        val body = if (listId != null) """{"name":${JsonPrimitive(name)},"listId":"$listId"}""" else """{"name":${JsonPrimitive(name)}}"""
        val res = client.post("/api/v1/shopping") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.putList(token: String, listId: String, body: String) =
        client.put("/api/v1/shopping/lists/$listId") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `an own-categories list starts with only the shared Sonstiges`() = testApplication {
        configureTestApplication()
        val token = token()
        val list = createList(token, "Baumarkt", ownCategories = true)
        assertEquals(true, list["ownCategories"]?.jsonPrimitive?.boolean)
        val cats = categoriesFor(token, list["id"]!!.jsonPrimitive.content).map { it.jsonObject }
        assertEquals(1, cats.size)
        assertEquals("OTHER", cats.single()["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `custom categories are scoped to their list and absent from the shared catalog`() = testApplication {
        configureTestApplication()
        val token = token()
        val listId = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content
        val werkzeug = createCategoryFor(token, "Werkzeug", "🔧", listId)
        assertEquals(listId, werkzeug["listId"]?.jsonPrimitive?.content)
        val key = werkzeug["key"]!!.jsonPrimitive.content

        // visible in the list's own set (custom before the shared OTHER) …
        assertEquals(listOf(key, "OTHER"), categoriesFor(token, listId).map { it.jsonObject["key"]?.jsonPrimitive?.content })
        // … but NOT in the shared household catalog (still the seeded 10)
        assertEquals(10, categories(token).size)
        assertTrue(categories(token).none { it.jsonObject["key"]?.jsonPrimitive?.content == key })
    }

    @Test
    fun `resolution and remembered corrections are per list scope`() = testApplication {
        configureTestApplication()
        val token = token()
        val shared = createList(token, "Wocheneinkauf")["id"]!!.jsonPrimitive.content
        val baumarkt = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content
        val werkzeug = createCategoryFor(token, "Werkzeug", "🔧", baumarkt)["key"]!!.jsonPrimitive.content

        // grocery rules don't apply in the Baumarkt scope → OTHER
        val hammer = addItemTo(token, "Hammer", baumarkt)
        assertEquals("OTHER", hammer["category"]?.jsonPrimitive?.content)
        // move it into the custom category (remembered for future adds)
        client.put("/api/v1/shopping/${hammer["id"]!!.jsonPrimitive.content}") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"$werkzeug"}""")
        }
        // a re-add in the SAME list inherits the remembered custom category …
        assertEquals(werkzeug, addItemTo(token, "Hammer", baumarkt)["category"]?.jsonPrimitive?.content)
        // … but the same name in a shared/grocery list can't use the out-of-scope key → OTHER
        assertEquals("OTHER", addItemTo(token, "Hammer", shared)["category"]?.jsonPrimitive?.content)
        // a grocery staple still resolves normally in the shared list …
        assertEquals("DAIRY", addItemTo(token, "Milch", shared)["category"]?.jsonPrimitive?.content)
        // … yet collapses to OTHER inside the Baumarkt scope
        assertEquals("OTHER", addItemTo(token, "Joghurt", baumarkt)["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `category override is validated against the item's list scope`() = testApplication {
        configureTestApplication()
        val token = token()
        val shared = createList(token, "Wocheneinkauf")["id"]!!.jsonPrimitive.content
        val baumarkt = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content
        val werkzeug = createCategoryFor(token, "Werkzeug", "🔧", baumarkt)["key"]!!.jsonPrimitive.content

        // custom key is valid for its own list …
        val inBaumarkt = addItemTo(token, "Bohrer", baumarkt)["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/shopping/$inBaumarkt") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"$werkzeug"}""")
        }.status)
        // … but rejected on an item in a shared list (out of scope)
        val inShared = addItemTo(token, "Apfel", shared)["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/shopping/$inShared") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"$werkzeug"}""")
        }.status)
        // and a grocery key is rejected inside the own-categories list
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/shopping/$inBaumarkt") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"DAIRY"}""")
        }.status)
    }

    private suspend fun ApplicationTestBuilder.suggestionsFor(token: String, listId: String, q: String? = null): List<JsonObject> {
        val url = "/api/v1/shopping/suggestions?listId=$listId" + (q?.let { "&q=$it" } ?: "")
        return Json.parseToJsonElement(client.get(url) { bearerAuth(token) }.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    @Test
    fun `an own-categories list suggests only its own used names, not the grocery baseline`() = testApplication {
        configureTestApplication()
        val token = token()
        addItem(token, "Milch") // seed a household (shared-scope) usage for a DAIRY staple
        val baumarkt = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content

        // shared scope: the grocery baseline (+ its usage) resolves as before
        assertEquals("DAIRY", suggestions(token, q = "milch").map { it.jsonObject }.first()["category"]?.jsonPrimitive?.content)
        // #501: the Baumarkt scope drops the grocery dictionary entirely — no food autocomplete noise
        assertTrue(suggestionsFor(token, baumarkt, q = "milch").isEmpty())

        // …but a name actually used in the Baumarkt list IS suggested there, and NOT in the shared scope
        addItemTo(token, "Dachlatte", baumarkt)
        assertTrue(suggestionsFor(token, baumarkt).any { it["name"]?.jsonPrimitive?.content == "Dachlatte" })
        assertTrue(suggestions(token).map { it.jsonObject }.none { it["name"]?.jsonPrimitive?.content == "Dachlatte" })
    }

    @Test
    fun `remembered corrections are independent between two own-categories lists`() = testApplication {
        configureTestApplication()
        val token = token()
        val hobby = createList(token, "Hobby", ownCategories = true)["id"]!!.jsonPrimitive.content
        val garage = createList(token, "Garage", ownCategories = true)["id"]!!.jsonPrimitive.content
        val farbeHobby = createCategoryFor(token, "Farben", "🎨", hobby)["key"]!!.jsonPrimitive.content
        val lackGarage = createCategoryFor(token, "Lacke", "🛢️", garage)["key"]!!.jsonPrimitive.content

        // teach each list a different category for the SAME article name
        val h = addItemTo(token, "Pinsel", hobby)["id"]!!.jsonPrimitive.content
        client.put("/api/v1/shopping/$h") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"$farbeHobby"}""") }
        val g = addItemTo(token, "Pinsel", garage)["id"]!!.jsonPrimitive.content
        client.put("/api/v1/shopping/$g") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"category":"$lackGarage"}""") }

        // #501: teaching Garage did NOT overwrite Hobby's memory — each list keeps its own correction
        assertEquals(farbeHobby, addItemTo(token, "Pinsel", hobby)["category"]?.jsonPrimitive?.content)
        assertEquals(lackGarage, addItemTo(token, "Pinsel", garage)["category"]?.jsonPrimitive?.content)
    }

    @Test
    fun `flipping own-categories off hides the custom set and back on restores it`() = testApplication {
        configureTestApplication()
        val token = token()
        val listId = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content
        val key = createCategoryFor(token, "Werkzeug", "🔧", listId)["key"]!!.jsonPrimitive.content

        // revert to the shared catalog → the scoped GET returns the shared set (custom hidden)
        assertEquals(HttpStatusCode.OK, putList(token, listId, """{"ownCategories":false}""").status)
        val sharedView = categoriesFor(token, listId).map { it.jsonObject["key"]?.jsonPrimitive?.content }
        assertEquals(10, sharedView.size)
        assertTrue(sharedView.none { it == key })

        // flip back on → the custom category returns (kept in the DB — lossless)
        assertEquals(HttpStatusCode.OK, putList(token, listId, """{"ownCategories":true}""").status)
        assertTrue(categoriesFor(token, listId).any { it.jsonObject["key"]?.jsonPrimitive?.content == key })
    }

    @Test
    fun `deleting a list removes its own categories`() = testApplication {
        configureTestApplication()
        val token = token()
        val listId = createList(token, "Baumarkt", ownCategories = true)["id"]!!.jsonPrimitive.content
        val key = createCategoryFor(token, "Werkzeug", "🔧", listId)["key"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/shopping/lists/$listId") { bearerAuth(token) }.status)
        assertTrue(categories(token).none { it.jsonObject["key"]?.jsonPrimitive?.content == key })
    }
}
