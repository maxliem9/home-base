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
    fun `batch add merges a matching quantity line into the existing item`() = runBlocking {
        val list = service.createList("L", ownCategories = false, username = "alice")
        val listId = UUID.fromString(list.id)

        val first = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 200.0, unit = "g")), "alice")
        assertEquals(1, first?.created?.size)

        val second = service.batchAdd(listId, listOf(ShoppingLineInput("Mehl", amount = 300.0, unit = "g")), "alice")
        assertEquals(0, second?.created?.size)
        assertEquals(1, second?.updated?.size)
        assertEquals("500 g Mehl", second?.updated?.first()?.name)

        // still a single line on the list
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
