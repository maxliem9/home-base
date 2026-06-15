package com.homebase

import com.homebase.db.AbsSettingsTable
import com.homebase.db.AbsencesTable
import com.homebase.db.AppSettingsTable
import com.homebase.db.CustomHolidaysTable
import com.homebase.db.IngredientsTable
import com.homebase.db.KitaClosuresTable
import com.homebase.db.MealPlanEntriesTable
import com.homebase.db.NoteImagesTable
import com.homebase.db.NotesTable
import com.homebase.db.PartTimeRulesTable
import com.homebase.db.ProjectsTable
import com.homebase.db.RecipeImagesTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.db.ShoppingItemsTable
import com.homebase.db.ShoppingListsTable
import com.homebase.db.ShoppingTemplateItemsTable
import com.homebase.db.ShoppingTemplatesTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.TodoListsTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import com.homebase.db.UserPrefsTable
import com.homebase.db.UsersTable
import com.homebase.plugins.*
import com.homebase.security.Passwords
import io.ktor.server.config.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Configures a test Ktor application with an H2 in-memory database and seeded users.
 * Bypasses DatabaseFactory (which requires a running PostgreSQL instance) and Flyway
 * (which uses Postgres-specific SQL). Tables are created via Exposed SchemaUtils.
 *
 * [extraConfig] appends/overrides config entries for tests that exercise optional
 * settings (e.g. "app.domain" for the CORS origin pinning).
 */
fun ApplicationTestBuilder.configureTestApplication(vararg extraConfig: Pair<String, String>): Path {
    // Each test gets its own throwaway upload directory; a low size cap keeps the
    // "too large" test cheap (just over 1 MB rather than just over 10 MB).
    val uploadDir = Files.createTempDirectory("homebase-test-uploads")
    environment {
        config = MapApplicationConfig(
            "jwt.secret" to "test-secret-key-for-testing-only",
            "jwt.issuer" to "homebase",
            "jwt.audience" to "homebase-users",
            "jwt.realm" to "HomeBase",
            "app.uploadDir" to uploadDir.toString(),
            "app.maxUploadMb" to "1",
            *extraConfig,
        )
    }
    application {
        Database.connect(
            url = "jdbc:h2:mem:homebase_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                AppSettingsTable, UserPrefsTable,
                UsersTable, TodoListsTable, TodosTable, TodoSubtasksTable, ShoppingListsTable, ShoppingItemsTable,
                ShoppingTemplatesTable, ShoppingTemplateItemsTable,
                NotesTable, NoteImagesTable,
                ProjectsTable, TimeEntriesTable, TimeWorkTargetsTable,
                RecipesTable, IngredientsTable, RecipeStepsTable, RecipeImagesTable,
                MealPlanEntriesTable,
                AbsencesTable, PartTimeRulesTable, KitaClosuresTable, CustomHolidaysTable, AbsSettingsTable,
            )
            UsersTable.insert {
                it[id] = UUID.fromString("00000000-0000-0000-0000-000000000001")
                it[username] = "alice"
                it[passwordHash] = Passwords.hash("password123")
                it[createdAt] = Instant.now()
            }
            UsersTable.insert {
                it[id] = UUID.fromString("00000000-0000-0000-0000-000000000002")
                it[username] = "bob"
                it[passwordHash] = Passwords.hash("password456")
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
    return uploadDir
}
