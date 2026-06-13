package com.homebase.android.data.shopping

import com.squareup.moshi.JsonClass

/**
 * One queued, not-yet-acknowledged check-off intent for a shopping item.
 *
 * @property checked the desired checked state the user tapped
 * @property at      wall-clock millis of the tap — the latest-wins tiebreaker and the
 *                   guard against a stale in-flight response clobbering a newer toggle
 */
@JsonClass(generateAdapter = true)
data class PendingCheck(val checked: Boolean, val at: Long)

/**
 * Durable, key-value backing store for the offline check-off queue (key = item id → intent).
 *
 * Mirrors the web's `localStorage['homebase_shopping_pending']` blob: tapping a checkbox in a
 * store with flaky/no wifi must not silently lose the change, so every toggle is persisted here
 * and retried on every connectivity signal until it lands (see [com.homebase.android.ui.shopping.ShoppingViewModel]).
 * Keyed by item id (not user), so it is correct across the single account on one device.
 *
 * Implemented over [android.content.SharedPreferences] (a tiny JSON map, no new dependency)
 * rather than Room/DataStore — the data is a handful of entries and the web analog is equally
 * lightweight. The interface keeps the queue logic unit-testable with an in-memory fake.
 *
 * Both methods are `suspend` so the implementation can do its disk I/O off the main thread
 * (the SharedPreferences load/commit blocks); see [SharedPrefsShoppingPendingStore].
 */
interface ShoppingPendingStore {
    suspend fun load(): Map<String, PendingCheck>
    suspend fun save(pending: Map<String, PendingCheck>)
}
