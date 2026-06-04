package com.homebase

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.IngredientsTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.NotesTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.ProjectsTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UsersTable
import com.homebase.plugins.*
import com.homebase.security.sha256
import io.ktor.server.config.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Configures a test Ktor application with an H2 in-memory database and seeded users.
 * Bypasses DatabaseFactory (which requires a running PostgreSQL instance) and Flyway
 * (which uses Postgres-specific SQL). Tables are created via Exposed SchemaUtils.
 */
fun ApplicationTestBuilder.configureTestApplication() {
    environment {
        config = MapApplicationConfig(
            "jwt.secret" to "test-secret-key-for-testing-only",
            "jwt.issuer" to "homebase",
            "jwt.audience" to "homebase-users",
            "jwt.realm" to "HomeBase",
        )
    }
    application {
        Database.connect(
            url = "jdbc:h2:mem:homebase_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                UsersTable, TodoListsTable, TodosTable, TodoSubtasksTable, ShoppingListsTable, ShoppingItemsTable, NotesTable,
                ProjectsTable, TimeEntriesTable,
                RecipesTable, IngredientsTable, RecipeStepsTable,
                AbsencesTable, PartTimeRulesTable, KitaClosuresTable, AbsSettingsTable,
            )
            UsersTable.insert {
                it[id] = UUID.fromString("00000000-0000-0000-0000-000000000001")
                it[username] = "alice"
                it[passwordHash] = sha256("password123")
                it[createdAt] = Instant.now()
            }
            UsersTable.insert {
                it[id] = UUID.fromString("00000000-0000-0000-0000-000000000002")
                it[username] = "bob"
                it[passwordHash] = sha256("password456")
                it[createdAt] = Instant.now()
            }
        }
        configureSerialization()
        configureAuthentication(environment.config)
        configureWebSockets()
        configureStatusPages()
        configureCORS()
        configureRouting()
    }
}
