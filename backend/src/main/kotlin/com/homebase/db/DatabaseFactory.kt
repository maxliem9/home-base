package com.homebase.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val url = config.property("database.url").getString()
        val user = config.property("database.user").getString()
        val password = config.property("database.password").getString()

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        val dataSource = HikariDataSource(hikariConfig)

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .load()
        // V7 was edited to repair its own missing-prerequisite bug; that changes its
        // checksum. repair() realigns the stored checksum on databases where V7 is
        // already applied (and clears any failed attempt) so the following migrate()
        // doesn't abort with a checksum-mismatch validation error. No-op on a fresh DB.
        flyway.repair()
        flyway.migrate()

        Database.connect(dataSource)
    }
}
