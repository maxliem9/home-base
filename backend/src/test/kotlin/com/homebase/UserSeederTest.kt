package com.homebase

import com.homebase.db.UserSeeder
import com.homebase.db.UsersTable
import com.homebase.security.Passwords
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        val stored = hashOf("alice")!!
        // Stored as a bcrypt hash, never the plaintext, and it verifies against the password.
        assertTrue(stored.startsWith("\$2"))
        assertTrue(Passwords.verify("secret", stored))
    }

    @Test
    fun `seed is idempotent and does not duplicate users`() {
        val users = listOf(UserSeeder.SeedUser("alice", "secret"))
        UserSeeder.seed(users)
        UserSeeder.seed(users)

        assertEquals(1, userCount())
    }

    @Test
    fun `seed does not overwrite an existing bcrypt hash so an in-app password change survives`() {
        // Seed alice, then simulate her changing her password in-app (a fresh bcrypt hash of
        // a different password). A later seed — e.g. on the next restart — must NOT reset it
        // back to the SEED_USERS value, or the self-service change (#100) would be silently
        // lost. Regression guard for the seeder-vs-self-service interaction.
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "seed-pw")))
        val changed = Passwords.hash("user-chosen-pw")
        transaction {
            UsersTable.update({ UsersTable.username eq "alice" }) { it[passwordHash] = changed }
        }

        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "seed-pw")))

        assertEquals(changed, hashOf("alice"))
        assertTrue(Passwords.verify("user-chosen-pw", hashOf("alice")!!))
        assertFalse(Passwords.verify("seed-pw", hashOf("alice")!!))
    }

    @Test
    fun `seed upgrades a legacy SHA-256 hash to bcrypt`() {
        // Simulate the pre-bcrypt state: a row whose password_hash is a raw SHA-256 hex
        // (here sha256("password")). The next seed must verify-fail against it and re-hash
        // to bcrypt — the automatic upgrade that makes a manual re-seed unnecessary (#51).
        val legacySha256 = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"
        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = "alice"
                it[passwordHash] = legacySha256
                it[createdAt] = Instant.now()
            }
        }

        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "password")))

        assertEquals(1, userCount())
        val stored = hashOf("alice")!!
        assertNotEquals(legacySha256, stored)
        assertTrue(stored.startsWith("\$2"))
        assertTrue(Passwords.verify("password", stored))
    }

    @Test
    fun `seed leaves the stored hash untouched when the password is unchanged`() {
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "secret")))
        val first = hashOf("alice")!!
        UserSeeder.seed(listOf(UserSeeder.SeedUser("alice", "secret")))

        // Unchanged password must not trigger a re-hash (which would alter the stored salt).
        assertEquals(first, hashOf("alice"))
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
