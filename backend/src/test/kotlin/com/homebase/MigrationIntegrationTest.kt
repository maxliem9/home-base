package com.homebase

import com.homebase.db.DatabaseFactory
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import io.ktor.server.config.MapApplicationConfig
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the real Flyway migrations against a throwaway PostgreSQL and exercises the writes
 * the unit suite can't. The other tests build their schema from Exposed via SchemaUtils on
 * H2 (see [configureTestApplication]), which silently diverges from the Flyway/Postgres
 * schema the app actually deploys against. That divergence once hid three production-breaking
 * schema/code mismatches; the migrations now create the right schema directly, and this test
 * keeps them honest:
 *
 *  1. todo_lists / todos.list_id must actually be created (V7), or a fresh DB can't migrate.
 *  2. todo_subtasks must be migrated (V10), or the first subtask write 500's.
 *  3. todos.status / todos.priority must be VARCHAR, not PG ENUM (V2) — an enum rejects
 *     Exposed's varchar bindings on write, so every "create todo" 500's (reads survive via
 *     PG's implicit enum->text cast, which is why H2-only tests never caught it).
 *
 * This test reproduces the real deploy path — migrate, then insert a todo with status/priority
 * and a subtask — so the same class of schema/code drift fails CI instead of production.
 *
 * Gated on a PostgreSQL DB_URL: it runs only where one is provided (the dedicated CI job, or
 * locally if you point DB_URL at a Postgres). The normal H2 unit job and a plain `gradle test`
 * skip it. The CI job additionally asserts the test was *not* skipped, so a broken gate can't
 * silently re-create the very "tests bypass the real schema" blind spot this guards against.
 */
class MigrationIntegrationTest {

    private val dbUrl: String? = System.getenv("DB_URL")
    private val dbUser: String? = System.getenv("DB_USER")
    private val dbPassword: String? = System.getenv("DB_PASSWORD")

    @BeforeTest
    fun requirePostgres() {
        assumeTrue(
            "Set DB_URL (jdbc:postgresql://...), DB_USER and DB_PASSWORD to run the migration IT",
            dbUrl?.startsWith("jdbc:postgresql") == true && dbUser != null && dbPassword != null,
        )
    }

    @Test
    fun `migrations apply on a fresh DB and the app can write todos, status priority and subtasks`() {
        // Mirrors production startup: DatabaseFactory.init runs flyway.migrate() and connects
        // Exposed. On a fresh DB this throws loudly if any migration is broken
        // (this is what catches a V7-style missing-prerequisite regression).
        DatabaseFactory.init(
            MapApplicationConfig(
                "database.url" to dbUrl!!,
                "database.user" to dbUser!!,
                "database.password" to dbPassword!!,
            ),
        )

        // Unique per run so the test is also re-runnable against a persistent local Postgres.
        val userName = "mig_it_${UUID.randomUUID().toString().take(8)}"
        val todoUuid = UUID.randomUUID()

        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = userName
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
            // status/priority are the columns that were PG ENUMs before V9. If they ever
            // revert to enum types, these varchar bindings fail here exactly as they did in prod.
            TodosTable.insert {
                it[id] = todoUuid
                it[title] = "migration smoke test"
                it[status] = "PLANNED"
                it[priority] = "HIGH"
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
            // todo_subtasks only exists if V10 ran.
            TodoSubtasksTable.insert {
                it[id] = UUID.randomUUID()
                it[todoId] = todoUuid
                it[title] = "subtask"
                it[done] = false
                it[sortOrder] = 0
                it[createdAt] = Instant.now()
            }
        }

        transaction {
            val row = TodosTable.selectAll().where { TodosTable.id eq todoUuid }.single()
            assertEquals("PLANNED", row[TodosTable.status])
            assertEquals("HIGH", row[TodosTable.priority])

            val subtaskCount = TodoSubtasksTable.selectAll()
                .where { TodoSubtasksTable.todoId eq todoUuid }
                .count()
            assertEquals(1L, subtaskCount)
        }
    }

    /**
     * Guards V7's `todos.list_id ... ON DELETE CASCADE` (issue #58). The H2 unit suite models
     * list_id without a FK, so only the real Postgres schema can prove the cascade. Deleting
     * a list row must take its todos — and their subtasks — with it.
     */
    @Test
    fun `deleting a list cascades to its todos and their subtasks`() {
        DatabaseFactory.init(
            MapApplicationConfig(
                "database.url" to dbUrl!!,
                "database.user" to dbUser!!,
                "database.password" to dbPassword!!,
            ),
        )

        val userName = "mig_cascade_${UUID.randomUUID().toString().take(8)}"
        val listUuid = UUID.randomUUID()
        val todoUuid = UUID.randomUUID()
        val subtaskUuid = UUID.randomUUID()

        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = userName
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
            TodoListsTable.insert {
                it[id] = listUuid
                it[name] = "Cascade-Liste"
                it[visibility] = "SHARED"
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
            TodosTable.insert {
                it[id] = todoUuid
                it[title] = "in der Liste"
                it[status] = "INBOX"
                it[listId] = listUuid
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
            TodoSubtasksTable.insert {
                it[id] = subtaskUuid
                it[todoId] = todoUuid
                it[title] = "Teilschritt"
                it[done] = false
                it[sortOrder] = 0
                it[createdAt] = Instant.now()
            }
        }

        transaction { TodoListsTable.deleteWhere { TodoListsTable.id eq listUuid } }

        transaction {
            assertEquals(
                0L,
                TodosTable.selectAll().where { TodosTable.id eq todoUuid }.count(),
                "todo should be cascade-deleted with its list",
            )
            assertEquals(
                0L,
                TodoSubtasksTable.selectAll().where { TodoSubtasksTable.id eq subtaskUuid }.count(),
                "subtask should be cascade-deleted with its todo",
            )
        }
    }
}
