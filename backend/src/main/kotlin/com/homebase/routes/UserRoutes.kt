package com.homebase.routes

import com.homebase.db.UsersTable
import com.homebase.model.UserDto
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The household members. HomeBase has 2 fixed users seeded from SEED_USERS; their
 * usernames are configurable, so clients can't hard-code "the other user". This list
 * lets a client resolve the partner — e.g. to start/stop their timer (#142) or render
 * an avatar — without scraping it out of unrelated payloads.
 */
fun Route.userRoutes() {
    get("/users") {
        val users = transaction {
            UsersTable.selectAll()
                .orderBy(UsersTable.username, SortOrder.ASC)
                .map { UserDto(it[UsersTable.username]) }
        }
        call.respond(users)
    }
}
