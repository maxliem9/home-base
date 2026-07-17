package com.homebase

import com.homebase.db.UsersTable
import com.homebase.security.Passwords
import com.homebase.shopping.ShoppingCatalog
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

/**
 * Geteiltes Test-Postgres für die **Routen**-Tests (#555).
 *
 * Vorher bauten die Routen-Tests ihr Schema per Exposed `SchemaUtils` auf einer H2-In-Memory-DB
 * ([configureTestApplication]) — das wich vom Flyway/Postgres-Schema der Produktion ab (fehlende
 * FK-Kaskaden, CHECKs, partielle Unique-Indizes, nur genäherte REPEATABLE_READ-Isolation) und
 * zwang Prod-Handler zu Hand-Kaskaden „für die H2-Test-DB". Jetzt läuft die Routen-Suite gegen ein
 * echtes Postgres 16, dessen Schema von den **echten Flyway-Migrationen** kommt.
 *
 * **Ein** Container für die ganze Suite (nicht pro Testklasse — sonst explodiert die Laufzeit):
 * einmal starten + migrieren, dann pro Test die Daten [reset]en (TRUNCATE + neu seeden) statt neu
 * aufzusetzen. Der Container wird bewusst nicht gestoppt — Testcontainers/Ryuk räumt beim JVM-Ende
 * auf.
 *
 * Braucht eine laufende Docker-Engine (lokal wie im CI-Runner). Die isolierten Service-/Logik-Tests
 * (TodoServiceTest, TimeServiceTest …) bleiben bewusst auf ihrer eigenen minimalen H2 — sie prüfen
 * Service-Logik, nicht das Prod-Schema (#555-AC: „H2 nur noch für isolierte Unit-Helfer").
 */
object TestDatabase {

    private const val ALICE_ID = "00000000-0000-0000-0000-000000000001"
    private const val BOB_ID = "00000000-0000-0000-0000-000000000002"

    @Volatile
    private var initialized = false
    private lateinit var dataSource: HikariDataSource
    lateinit var db: Database
        private set

    // Einmal ermittelte TRUNCATE-Anweisung über alle App-Tabellen (ohne Flyways History-Tabelle).
    // Dynamisch aus dem echten Schema gelesen, damit eine neue Migration/Tabelle automatisch
    // mitgeleert wird, ohne diese Liste zu pflegen.
    private var truncateSql: String? = null

    @Synchronized
    private fun ensureStarted() {
        if (initialized) return
        val container = PostgreSQLContainer(DockerImageName.parse("postgres:16")).apply {
            withDatabaseName("homebase_test")
            withUsername("homebase")
            withPassword("homebase")
            start()
        }
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 4
                isAutoCommit = false
                // Prod-Isolation spiegeln (DatabaseFactory) — so laufen die REPEATABLE_READ-sensitiven
                // Tests (#57/#68/#105) erstmals gegen die echte Isolation.
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            },
        )
        // Echtes Prod-Schema: dieselben Flyway-Migrationen wie DatabaseFactory.init.
        Flyway.configure().dataSource(dataSource).load().migrate()
        db = Database.connect(dataSource)
        initialized = true
    }

    /**
     * Registriert diesen Container als *aktuelle* Exposed-Datenbank (last-`connect`-gewinnt) und gibt
     * sie zurück. Bewusst KEIN `TransactionManager.defaultDatabase = …`: ein expliziter Set ist global
     * klebrig und würde die H2-Service-Tests (die auf demselben Fallback beruhen) auf Postgres
     * umleiten. Stattdessen macht — wie die alte H2-Harness pro Test — jeder Aufruf Postgres wieder
     * zur aktuellen DB; jeder H2-Service-Test tut in seinem Setup dasselbe für seine H2.
     */
    fun useAsCurrent(): Database {
        ensureStarted()
        db = Database.connect(dataSource)
        return db
    }

    /**
     * Leert alle Daten und seedet den Ausgangszustand neu (2 feste Nutzer + editierbarer
     * Einkaufs-Kategorie-Katalog, wie der Prod-Boot). Macht Postgres zur aktuellen DB, damit sowohl
     * die blockierenden Seeds (`transaction { }`, `ShoppingCatalog.seedIfEmpty`) als auch später
     * `dbQuery { }` diesen Container treffen.
     */
    fun reset() {
        val db = useAsCurrent()
        transaction(db) {
            if (truncateSql == null) {
                val tables = mutableListOf<String>()
                exec(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                        "AND tablename <> 'flyway_schema_history'",
                ) { rs -> while (rs.next()) tables += rs.getString(1) }
                truncateSql = "TRUNCATE TABLE " +
                    tables.joinToString(", ") { "\"$it\"" } +
                    " RESTART IDENTITY CASCADE"
            }
            exec(truncateSql!!)

            UsersTable.insert {
                it[id] = UUID.fromString(ALICE_ID)
                it[username] = "alice"
                it[passwordHash] = Passwords.hash("password123")
                it[createdAt] = Instant.now()
            }
            UsersTable.insert {
                it[id] = UUID.fromString(BOB_ID)
                it[username] = "bob"
                it[passwordHash] = Passwords.hash("password456")
                it[createdAt] = Instant.now()
            }
        }
        // Editierbaren Kategorie-Katalog (#411) in das frische Schema seeden, wie der Prod-Startup —
        // eigene Transaktion (nutzt die eben gesetzte Default-DB).
        ShoppingCatalog.seedIfEmpty()
    }
}
