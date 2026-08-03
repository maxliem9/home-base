package com.homebase

import com.homebase.db.DatabaseFactory
import com.homebase.db.UserSeeder
import com.homebase.plugins.*
import com.homebase.shopping.ShoppingCatalog
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    // Erste Log-Zeile beim Start: welcher Build läuft hier (#626) — beim Debuggen auf dem NAS
    // die schnellste Antwort auf „ist das Deployment überhaupt durchgelaufen?".
    log.info("HomeBase backend {} startet…", AppVersion.display)
    DatabaseFactory.init(environment.config)
    UserSeeder.seedFromConfig(environment.config)
    ShoppingCatalog.seedIfEmpty() // #411: seed the editable category catalog into the empty table
    configureSerialization()
    configureAuthentication(environment.config)
    configureWebSockets()
    configureStatusPages()
    configureCORS()
    configureRouting()
    configureDigest()
    configureRecurringTodos()
}
