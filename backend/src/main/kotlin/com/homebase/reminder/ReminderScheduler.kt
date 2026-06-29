package com.homebase.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Drives [ReminderService.runOnce] on a tight tick (#429 Phase 2a). Unlike the once-a-day digests,
 * reminders need fine granularity to fire near a todo's due time, so this loops every [tickMillis]
 * (default 60s). Each pass re-reads its settings, so in-app changes apply without a restart.
 */
class ReminderScheduler(
    private val service: ReminderService,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 60_000,
) {
    private val logger = LoggerFactory.getLogger(ReminderScheduler::class.java)

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { service.runOnce() }
                    .onFailure { logger.error("Reminder run failed", it) }
                delay(tickMillis)
            }
        }
    }
}
