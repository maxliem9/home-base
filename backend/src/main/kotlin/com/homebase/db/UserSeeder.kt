package com.homebase.db

import com.homebase.security.Passwords
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Seeds the two fixed users from the SEED_USERS environment variable.
 *
 * HomeBase has no self-registration (PRD: "2 feste Nutzer, kein Self-Registration"),
 * so without this the users table stays empty and login can never succeed. Seeding
 * runs on every startup and is idempotent. It is bootstrap-only for passwords: a new
 * user is inserted with the SEED_USERS password, and a legacy (pre-bcrypt) hash is
 * upgraded to bcrypt — but an existing bcrypt hash is never overwritten, so a password
 * a user changed in-app (#100) survives restarts instead of reverting to the env value.
 * Credentials stay out of the repo, per the env-var-only configuration convention.
 *
 * Format: "username:password,username2:password2"
 */
object UserSeeder {
    private val log = LoggerFactory.getLogger(UserSeeder::class.java)

    data class SeedUser(val username: String, val password: String)

    fun seedFromConfig(config: ApplicationConfig) {
        val raw = config.propertyOrNull("seed.users")?.getString()
        if (raw.isNullOrBlank()) {
            log.warn("SEED_USERS is not set — no users will be seeded and login will fail until users exist.")
            return
        }
        val users = parse(raw)
        if (users.isEmpty()) {
            log.warn("SEED_USERS is set but no valid 'username:password' pairs were parsed.")
            return
        }
        seed(users)
    }

    /**
     * Parses "alice:pw1,bob:pw2" into [SeedUser]s. Entries without a colon, or with a
     * blank username or password, are skipped. The password may itself contain colons
     * (only the first colon splits username from password).
     */
    fun parse(raw: String): List<SeedUser> =
        raw.split(",")
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                val idx = trimmed.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val username = trimmed.substring(0, idx).trim()
                val password = trimmed.substring(idx + 1)
                if (username.isBlank() || password.isEmpty()) null
                else SeedUser(username, password)
            }
            .distinctBy { it.username }

    fun seed(users: List<SeedUser>) {
        transaction {
            users.forEach { user ->
                val existing = UsersTable
                    .selectAll()
                    .where { UsersTable.username eq user.username }
                    .singleOrNull()

                if (existing == null) {
                    UsersTable.insert {
                        it[id] = UUID.randomUUID()
                        it[username] = user.username
                        it[passwordHash] = Passwords.hash(user.password)
                        it[createdAt] = Instant.now()
                    }
                    log.info("Seeded new user '{}'.", user.username)
                } else if (!existing[UsersTable.passwordHash].startsWith("\$2")) {
                    // Upgrade ONLY a legacy, pre-bcrypt hash (e.g. raw SHA-256) to bcrypt.
                    // An existing bcrypt hash ("$2…") is deliberately left untouched: once a
                    // user changes their own password in-app (#100), re-seeding from SEED_USERS
                    // on the next restart must not reset it back to the env value. SEED_USERS is
                    // thus bootstrap-only for passwords — initial insert + legacy rescue, never
                    // an overwrite of a real, current hash.
                    UsersTable.update({ UsersTable.username eq user.username }) {
                        it[passwordHash] = Passwords.hash(user.password)
                    }
                    log.info("Upgraded legacy password hash for existing user '{}' to bcrypt.", user.username)
                }
            }
        }
    }
}
