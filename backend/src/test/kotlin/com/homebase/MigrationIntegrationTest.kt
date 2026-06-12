package com.homebase

import com.homebase.db.DatabaseFactory
import com.homebase.db.ProjectsTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import io.ktor.server.config.MapApplicationConfig
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    private val hasPostgres: Boolean
        get() = dbUrl?.startsWith("jdbc:postgresql") == true && dbUser != null && dbPassword != null

    // Usernames each test seeds (mig_it_* / mig_cascade_* / mig_rec_*). Tracked so [cleanup]
    // can remove exactly the rows this test created — harmless on the throwaway CI Postgres,
    // but keeps a persistent local DB tidy instead of accumulating orphan rows every run.
    private val createdUsers = mutableListOf<String>()

    @BeforeTest
    fun requirePostgres() {
        assumeTrue(
            "Set DB_URL (jdbc:postgresql://...), DB_USER and DB_PASSWORD to run the migration IT",
            hasPostgres,
        )
    }

    /**
     * Removes the rows the test body seeded, in FK order
     * (work_targets → time_entries → projects → subtasks → todos → lists → users).
     * JUnit instantiates the class once per `@Test`, so [createdUsers] holds only the user(s)
     * the just-finished method created. Runs even if the test failed; never fails the build —
     * a skipped run (gate unmet) has nothing to clean, and a best-effort delete that finds
     * nothing (or hits a half-initialised DB) must not turn a green test red.
     */
    @AfterTest
    fun cleanup() {
        if (!hasPostgres || createdUsers.isEmpty()) return
        runCatching {
            transaction {
                // time_work_targets and time_entries reference projects + users — delete first
                TimeWorkTargetsTable.deleteWhere { TimeWorkTargetsTable.userId inList createdUsers }
                ProjectsTable.deleteWhere { ProjectsTable.createdBy inList createdUsers }

                val todoIds = TodosTable.selectAll()
                    .where { TodosTable.createdBy inList createdUsers }
                    .map { it[TodosTable.id] }
                if (todoIds.isNotEmpty()) {
                    TodoSubtasksTable.deleteWhere { TodoSubtasksTable.todoId inList todoIds }
                }
                TodosTable.deleteWhere { TodosTable.createdBy inList createdUsers }
                TodoListsTable.deleteWhere { TodoListsTable.createdBy inList createdUsers }
                UsersTable.deleteWhere { UsersTable.username inList createdUsers }
            }
        }
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
        createdUsers += userName
        val todoUuid = UUID.randomUUID()

        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = userName
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
            // status/priority are the columns V2 deliberately keeps as VARCHAR (not PG ENUM).
            // If they ever revert to enum types, these varchar bindings fail here exactly as they
            // did in prod before that was fixed.
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
        createdUsers += userName
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

    /**
     * Guards the V14 recurrence CHECK constraints. The H2 unit suite builds its schema
     * from Exposed via SchemaUtils, which carries none of these CHECKs, so only the real Postgres
     * schema can prove them. A valid recurring todo must insert; a recurring todo without a due_date
     * anchor must be rejected by `todos_recurrence_due_chk`.
     */
    @Test
    fun `V14 recurrence CHECK constraints hold on Postgres`() {
        DatabaseFactory.init(
            MapApplicationConfig(
                "database.url" to dbUrl!!,
                "database.user" to dbUser!!,
                "database.password" to dbPassword!!,
            ),
        )

        val userName = "mig_rec_${UUID.randomUUID().toString().take(8)}"
        createdUsers += userName
        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = userName
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }

        // valid: recurrence + interval + due_date anchor
        val okId = UUID.randomUUID()
        transaction {
            TodosTable.insert {
                it[id] = okId
                it[title] = "every 2 weeks"
                it[status] = "PLANNED"
                it[dueDate] = LocalDate.of(2026, 6, 8)
                it[recurrence] = "WEEKLY"
                it[recurrenceInterval] = 2
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
        }
        transaction {
            val row = TodosTable.selectAll().where { TodosTable.id eq okId }.single()
            assertEquals("WEEKLY", row[TodosTable.recurrence])
            assertEquals(2, row[TodosTable.recurrenceInterval])
        }

        // invalid: recurrence set but no due_date anchor -> todos_recurrence_due_chk rejects it
        assertFailsWith<ExposedSQLException> {
            transaction {
                TodosTable.insert {
                    it[id] = UUID.randomUUID()
                    it[title] = "no anchor"
                    it[status] = "INBOX"
                    it[recurrence] = "DAILY"
                    it[recurrenceInterval] = 1
                    it[createdBy] = userName
                    it[createdAt] = Instant.now()
                }
            }
        }
    }

    /**
     * Guards the V20 partial unique index `time_work_targets_one_default` (issue #108).
     *
     * H2 does not support partial (WHERE-filtered) unique indexes, so this constraint only
     * exists against real Postgres. Without this test a concurrent second PUT that sets
     * `is_default=true` for a different project — without first clearing the existing default —
     * would hit a Postgres unique-violation that previously surfaced as an unhandled 500.
     * PR #103 maps that violation to a 409; this test proves:
     *
     *  (a) the partial index actually rejects a second `is_default=true` row per user, and
     *  (b) the thrown [ExposedSQLException] carries SQLState `23505` and the index name
     *      `time_work_targets_one_default` — exactly the signal that
     *      `TimeForecastRoutes.isDefaultIndexConflict()` uses to translate the DB error
     *      into a 409 response (#57).
     */
    @Test
    fun `V20 partial index rejects second is_default=true per user and exception matches #57 detection`() {
        DatabaseFactory.init(
            MapApplicationConfig(
                "database.url" to dbUrl!!,
                "database.user" to dbUser!!,
                "database.password" to dbPassword!!,
            ),
        )

        val userName = "mig_def_${UUID.randomUUID().toString().take(8)}"
        createdUsers += userName

        val projectAId = UUID.randomUUID()
        val projectBId = UUID.randomUUID()

        // Seed: one user, two projects, one work-target with is_default=true for project A.
        transaction {
            UsersTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = userName
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
            ProjectsTable.insert {
                it[id] = projectAId
                it[name] = "Projekt A"
                it[color] = "#ff0000"
                it[archived] = false
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
            ProjectsTable.insert {
                it[id] = projectBId
                it[name] = "Projekt B"
                it[color] = "#0000ff"
                it[archived] = false
                it[createdBy] = userName
                it[createdAt] = Instant.now()
            }
            TimeWorkTargetsTable.insert {
                it[id] = UUID.randomUUID()
                it[userId] = userName
                it[projectId] = projectAId
                it[weeklyHours] = 20.0
                it[isDefault] = true   // first default — must succeed
            }
        }

        // Now attempt to set is_default=true for project B without clearing A first.
        // The partial unique index `time_work_targets_one_default` must reject this.
        val ex = assertFailsWith<ExposedSQLException>(
            message = "Partial index time_work_targets_one_default must reject a second is_default=true row",
        ) {
            transaction {
                TimeWorkTargetsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[userId] = userName
                    it[projectId] = projectBId
                    it[weeklyHours] = 0.0
                    it[isDefault] = true   // second default — must be rejected
                }
            }
        }

        // Part (b): verify that the exception carries the exact signals that
        // TimeForecastRoutes.isDefaultIndexConflict() uses to map this to a 409.
        // SQLState 23505 = unique_violation; message must name the index.
        val sqlEx = ex.cause as? java.sql.SQLException
        assertTrue(
            sqlEx != null,
            "ExposedSQLException.cause must be a java.sql.SQLException (got ${ex.cause?.javaClass})",
        )
        assertEquals(
            "23505",
            sqlEx.sqlState,
            "SQLState must be 23505 (unique_violation) so isDefaultIndexConflict() recognises it",
        )
        assertTrue(
            sqlEx.message.orEmpty().contains("time_work_targets_one_default", ignoreCase = true),
            "Exception message must contain the index name 'time_work_targets_one_default' " +
                "so isDefaultIndexConflict() can map it to 409. Actual message: ${sqlEx.message}",
        )
    }
}
