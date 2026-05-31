package com.homebase

import com.homebase.db.UserSeeder
import com.homebase.db.UsersTable
import com.homebase.security.sha256
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSeederTest {

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:seeder_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(UsersTable) }
    }

    private fun userCount() = transaction { UsersTable.selectAll().count() }
    private fun hashOf(username: String): String? = transaction {
        UsersTable.selectAll().where { UsersTable.username eq username }
            .singleOrNull()?.get(UsersTable.passwordHash)
    }

    // --- parse() ---

    @Test
    fun `parse reads username password pairs`() {
        val users = UserSeeder.parse("alice:pw1,bob:pw2")
        assertEquals(2, users.size)
        assertEquals("alice", users[0].username)
        assertEquals("pw1", users[0].password)
        assertEquals("bob", users[1].username)
    }

    @Test
    fun `parse trims whitespace around entries and usernames`() {
        val users = UserSeeder.parse(" alice : pw1 , bob:pw2 ")
        assertEquals("alice", users[0].username)
        // password keeps everything after the first colon (incl. leading space here)
        assertEquals("bob", users[1].username)
    }

    @Test
    fun `parse keeps colons inside the password`() {
        val users = UserSeeder.parse("alice:pw:with:colons")
        assertEquals(1, users.size)
        assertEquals("pw:with:colons", users[0].password)
    }

    @Test
    fun `parse skips entries without a colon or with blank parts`() {
        val users = UserSeeder.parse("noColon,:nopassword,alice:,bob:good")
        assertEquals(1, users.size)
        assertEquals("bob", users[0].username)
    }

    @Test
    fun `parse de-duplicates by username keeping the first`() {
        val users = UserSeeder.parse("alice:first,alice:second")
        assertEquals(1, users.size)
        assertEquals("first", users[0].password)
    }

    // --- seed() ---

    @Test
    fun `seed inserts new users with hashed passwords`() {
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "secret")))

        assertEquals(1, userCount())
        assertEquals(sha256("secret"), hashOf("alice"))
    }

    @Test
    fun `seed is idempotent and does not duplicate users`() {
        val users = listOf(UserSeeder.SeedUser("alice", "secret"))
        UserSeeder.seed(users)
        UserSeeder.seed(users)

        assertEquals(1, userCount())
    }

    @Test
    fun `seed updates password hash when password changes`() {
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "old")))
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "new")))

        assertEquals(1, userCount())
        assertEquals(sha256("new"), hashOf("alice"))
    }

    @Test
    fun `seed with empty list does nothing`() {
        UserSeeder.seed(emptyList())
        assertEquals(0, userCount())
        assertNull(hashOf("alice"))
    }

    @Test
    fun `seed handles multiple users`() {
        UserSeeder.seed(
            listOf(
                UserSeeder.SeedUser("alice", "pw1"),
                UserSeeder.SeedUser("bob", "pw2"),
            )
        )
        assertEquals(2, userCount())
        assertTrue(hashOf("alice") != null && hashOf("bob") != null)
    }
}
