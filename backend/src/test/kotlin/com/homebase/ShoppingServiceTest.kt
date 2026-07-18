package com.homebase

import com.homebase.db.ShoppingCategoriesTable
import com.homebase.db.ShoppingCategoryRulesTable
import com.homebase.db.ShoppingItemStatsTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.model.BatchAddShoppingRequest
import com.homebase.model.ShoppingLineInput
import com.homebase.model.UpdateShoppingItemRequest
import com.homebase.service.ShoppingService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ShoppingService] (issue #562): list/item CRUD, the unknown-list fault mapping and
 * the quantity-aware batch merge, exercised without an HTTP layer. The full HTTP contract stays
 * covered by ShoppingRouteTest.
 */
class ShoppingServiceTest {

    private val service = ShoppingService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:shoppingservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                ShoppingListsTable, ShoppingItemsTable, ShoppingCategoriesTable,
                ShoppingItemStatsTable, ShoppingCategoryRulesTable,
            )
        }
    }

    @Test
    fun `create list then add and delete an item round-trips`() = runBlocking {
        val list = service.createList("Wocheneinkauf", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val created = service.createItem("Milch", listId, quantity = null, username = "alice")
        assertTrue(created is ShoppingService.CreateItemResult.Ok)
        assertEquals("Milch", created.item.name)
        assertEquals(list.id, created.item.listId)

        assertEquals(1, service.listItems().size)
        val deleted = service.deleteItem(UUID.fromString(created.item.id))
        assertEquals(created.item.id, deleted?.id)
        assertEquals(0, service.listItems().size)
    }

    @Test
    fun `create item into an unknown list is Invalid with a 400-mapped fault`() = runBlocking {
        // regression: the pre-service route returned the NOT_FOUND-coded body with a 400 here
        val r = service.createItem("Milch", UUID.randomUUID(), quantity = null, username = "alice")
        assertTrue(r is ShoppingService.CreateItemResult.Invalid)
        assertEquals("NOT_FOUND", r.error.code)
    }

    @Test
    fun `deleting a list cascades its items`() = runBlocking {
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)
        service.createItem("Milch", listId, null, "alice")
        service.createItem("Brot", listId, null, "alice")
        assertEquals(2, service.listItems().size)

        val deleted = service.deleteList(listId)
        assertEquals(list.id, deleted?.id)
        assertEquals(0, service.listItems().size)
    }

    @Test
    fun `batch add writes a bare name plus quantity field, not a composite label`() = runBlocking {
        // #554: recipe ingredients land like the web quick-add — name "Mehl" + quantity "200 g" — so
        // clients that prefer the explicit quantity field render identically without name-parsing.
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val res = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 200.0, unit = "g")), "alice")

        val item = res?.created?.single()
        assertEquals("Mehl", item?.name)
        assertEquals("200 g", item?.quantity)
    }

    @Test
    fun `batch add merges a matching quantity line into the existing item`() = runBlocking {
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val first = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 200.0, unit = "g")), "alice")
        assertEquals(1, first?.created?.size)

        val second = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 300.0, unit = "g")), "alice")
        assertEquals(0, second?.created?.size)
        assertEquals(1, second?.updated?.size)
        // #554: summed into the bare name + quantity field (not a "500 g Mehl" composite name)
        assertEquals("Mehl", second?.updated?.first()?.name)
        assertEquals("500 g", second?.updated?.first()?.quantity)

        // still a single line on the list
        assertEquals(1, service.listItems().size)
    }

    @Test
    fun `batch add merges into a legacy composite-name row via the fallback parse`() = runBlocking {
        // Acceptance (#554): an existing old-format row ("500 g Mehl" in the name, no quantity field)
        // must still merge with a new bare-name + quantity add, summing to "700 g" and upgrading the
        // row to the new representation. This exercises the KNOWN_UNITS legacy fallback path.
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)
        // Seed a legacy row: the composite label lives in the name, the quantity column is null — exactly
        // what the pre-#554 batch path wrote.
        val legacy = service.createItem("500 g Mehl", listId, quantity = null, username = "alice")
        assertTrue(legacy is ShoppingService.CreateItemResult.Ok)
        assertNull(legacy.item.quantity)

        val res = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 200.0, unit = "g")), "alice")

        assertEquals(0, res?.created?.size)
        val merged = res?.updated?.single()
        assertEquals("Mehl", merged?.name)
        assertEquals("700 g", merged?.quantity)
        assertEquals(1, service.listItems().size)
    }

    @Test
    fun `batch add does not merge a bare-count line into an unknown-unit line (#596)`() = runBlocking {
        // "Glas" is not a KNOWN_UNIT, so it lives invisibly in the quantity field. A following bare
        // count ("1", no unit) must NOT merge into "Milch 2 Glas" and drop the "Glas" — the two stay
        // separate, mirroring the #47 invariant that "2 Glas" + "1 Glas" also stays two lines.
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val res = service.batchAdd(
            listId,
            listOf(
                ShoppingLineInput("Milch", amount = 2.0, unit = "Glas"),
                ShoppingLineInput("Milch", amount = 1.0), // bare count, no unit
            ),
            "alice",
        )

        assertEquals(2, res?.created?.size)
        assertEquals(0, res?.updated?.size)
        val quantities = service.listItems().map { it.quantity }.toSet()
        assertEquals(setOf("2 Glas", "1"), quantities) // "Glas" preserved, not folded into "3"
    }

    @Test
    fun `batch add skips an identical unknown-unit line instead of adding a duplicate (#596)`() = runBlocking {
        // Guards the new-format duplicate-skip branch (w.quantity != null): re-adding the exact same
        // "Milch 2 Glas" line must skip, not create a third row (the existing dup test only covered the
        // quantity == null legacy branch).
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val first = service.batchAdd(listId, listOf(ShoppingLineInput("Milch", amount = 2.0, unit = "Glas")), "alice")
        assertEquals(1, first?.created?.size)

        val second = service.batchAdd(listId, listOf(ShoppingLineInput("Milch", amount = 2.0, unit = "Glas")), "alice")
        assertEquals(0, second?.created?.size)
        assertEquals(0, second?.updated?.size)
        assertEquals(1, second?.skipped)
        assertEquals(1, service.listItems().size)
    }

    @Test
    fun `batch add into an unknown list returns null (404)`() = runBlocking {
        val r = service.batchAdd(UUID.randomUUID(), listOf(ShoppingLineInput("Mehl")), "alice")
        assertNull(r)
    }

    @Test
    fun `update item toggles checked and stamps checkedAt`() = runBlocking {
        val list = service.createList("L", ownCategories = false, username = "alice")
        val item = service.createItem("Milch", UUID.fromString(list.id), null, "alice")
        assertTrue(item is ShoppingService.CreateItemResult.Ok)

        val r = service.updateItem(UUID.fromString(item.item.id), UpdateShoppingItemRequest(checked = true), targetListId = null)
        assertTrue(r is ShoppingService.UpdateItemResult.Ok)
        assertTrue(r.item.checked)
        assertTrue(r.item.checkedAt != null)
    }
}
