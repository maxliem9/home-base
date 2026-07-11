package com.homebase.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Suspending replacement for Exposed's blocking `transaction { … }` (#549).
 *
 * Blocking JDBC must not run directly on Ktor's request dispatcher threads: the Hikari pool is
 * small (10, see [DatabaseFactory]) and a blocked worker thread cannot service other coroutines,
 * so under load the request dispatcher can starve. [newSuspendedTransaction] runs the transaction
 * body on the dedicated [Dispatchers.IO] pool and suspends (instead of blocking) the caller until
 * it completes, freeing the request thread meanwhile.
 *
 * Drop-in for call sites in a suspend context (Ktor route handlers are already suspend): replace
 * `transaction { … }` with `dbQuery { … }` and any `return@transaction` with `return@dbQuery`.
 * Because it is a `suspend` function it can only be called from a coroutine; genuinely blocking
 * startup/bootstrap code (e.g. [UserSeeder]) keeps using `transaction { }`.
 */
suspend fun <T> dbQuery(block: Transaction.() -> T): T =
    // Bind the transaction to the currently-registered default Database explicitly. Because the body
    // runs on a Dispatchers.IO thread, letting Exposed resolve the database lazily there would pick up
    // that pooled thread's stale thread-local TransactionManager instead of the one in force now — a
    // no-op in production (single DB) but wrong in tests, where each case connects its own H2 instance.
    newSuspendedTransaction(Dispatchers.IO, db = TransactionManager.defaultDatabase, statement = block)
