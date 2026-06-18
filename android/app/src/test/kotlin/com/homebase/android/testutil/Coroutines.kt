package com.homebase.android.testutil

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * A leak-proof [CoroutineScope] for tests that construct a collaborator whose `init` block fires a
 * **fire-and-forget** coroutine (e.g. [com.homebase.android.data.repository.AuthRepository], whose
 * `init` launches a token-restore on `Dispatchers.IO`).
 *
 * Such a coroutine can still be running when a test's `@After` tears the mocks down
 * (`unmockkAll()`); it then hits the real collaborator and throws. With a bare `CoroutineScope(Job())`
 * and no handler, that uncaught exception leaks into kotlinx-coroutines-test's global capture and is
 * reported by the **next** `runTest` as `UncaughtExceptionsBeforeTest` — a reorder-/timing-dependent
 * cross-test failure that turns `main` red without the offending test ever failing (PR #359, issue #363;
 * the original symptom was `LogoutTeardownTest` failing because `GermanLoginErrorTest` ran before it).
 *
 * The [SupervisorJob] keeps a failed child from cancelling its siblings, and the swallowing
 * [CoroutineExceptionHandler] absorbs the exception so it never reaches the global capture. Callers
 * should still `cancel()` this scope in `@After` **before** removing their mocks, so the coroutine
 * usually never runs at all — this scope is the safety net for the race where it already has.
 */
fun leakSafeScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
