package com.homebase

import com.homebase.db.DatabaseFactory
import com.homebase.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    DatabaseFactory.init(environment.config)
    configureSerialization()
    configureAuthentication(environment.config)
    configureWebSockets()
    configureStatusPages()
    configureCORS()
    configureRouting()
}
