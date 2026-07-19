package com.homebase.recurrence

import com.homebase.model.RecurrenceFreq
import java.time.LocalDate

/**
 * The recurrence rules for todos. Deliberately a tiny preset set — full RRULE would be
 * overkill for a household hub. All date math is "fixed schedule": the next instance is anchored to
 * the previous due date, not to the completion date, so "every Monday" / "the 1st" stays stable.
 */
object Recurrence {
    // Wire/DB frequency strings, sourced from the typed [com.homebase.model.RecurrenceFreq] enum (#556)
    // so the valid set lives in one place. The date-math `when` below still switches on the string.
    val DAILY = RecurrenceFreq.DAILY.name
    val WEEKLY = RecurrenceFreq.WEEKLY.name
    val MONTHLY = RecurrenceFreq.MONTHLY.name

    /** Sentinel a client may send on an update to remove a todo's recurrence. NOT a frequency. */
    const val CLEAR = "NONE"

    /** Upper bound on the every-N interval — a sane guard against absurd values. */
    const val MAX_INTERVAL = 1000

    // Defensive cap so a far-past anchor with a daily rule can never spin forever.
    private const val MAX_STEPS = 100_000

    /**
     * The occurrence [steps] whole periods after [from] (so steps = 1 is the next occurrence).
     * Always an absolute offset from the *original* [from], never re-fed from a previous result, so
     * MONTHLY keeps its day-of-month across skips: Jan 31 + 1 = Feb 28 but + 3 = Apr 30, not Apr 28.
     * java.time clamps each absolute offset to the last valid day of the target month.
     */
    fun occurrence(from: LocalDate, freq: String, interval: Int, steps: Long): LocalDate {
        val n = interval.toLong().coerceAtLeast(1) * steps
        return when (freq) {
            DAILY -> from.plusDays(n)
            WEEKLY -> from.plusWeeks(n)
            MONTHLY -> from.plusMonths(n)
            else -> throw IllegalArgumentException("unknown recurrence freq: $freq")
        }
    }

    /** The next occurrence one whole period after [from]. */
    fun nextOccurrence(from: LocalDate, freq: String, interval: Int): LocalDate =
        occurrence(from, freq, interval, 1)

    /**
     * Due date for the instance that follows the one anchored at [from], guaranteed to be strictly
     * after [today]. For an on-time completion this is just one step on; completing a long-overdue
     * todo skips the periods that already elapsed so the successor still lands in the future. Each
     * candidate is an absolute offset from [from], so the day-of-month stays anchored (no 31→28 drift).
     */
    fun nextDueAfterCompletion(from: LocalDate, freq: String, interval: Int, today: LocalDate): LocalDate {
        var steps = 1L
        while (!occurrence(from, freq, interval, steps).isAfter(today) && steps < MAX_STEPS) steps++
        return occurrence(from, freq, interval, steps)
    }

    /**
     * Safety-net for an *open, overdue* recurring todo: collapses the periods that have fully
     * elapsed by advancing [due] while the period after it has also already passed. Only whole
     * missed periods are skipped — the current period's occurrence is kept — so a chore stays on
     * schedule without ever piling up more than the single open instance. Returns [due] unchanged
     * when nothing has fully elapsed (e.g. only a day or two late). Candidates are absolute offsets
     * from the original [due], so a last-of-month monthly rule does not drift to the 28th.
     */
    fun rollOpenDueForward(due: LocalDate, freq: String, interval: Int, today: LocalDate): LocalDate {
        var steps = 0L
        while (!occurrence(due, freq, interval, steps + 1).isAfter(today) && steps < MAX_STEPS) steps++
        return occurrence(due, freq, interval, steps)
    }
}
