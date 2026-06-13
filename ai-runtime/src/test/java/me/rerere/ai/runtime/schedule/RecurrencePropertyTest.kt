package me.rerere.ai.runtime.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Pure-recurrence PBT (SPEC.md M1 / task T2). [Recurrence] owns the WHEN of a recurring schedule —
 * given a [RecurrenceSpec], its anchor (`firstFireAt`), the last fire, the schedule's IANA zone and
 * the current wall clock, it returns the first occurrence strictly AFTER now. The repository's
 * `claimDue` advances `next_fire_at` to this value, so its three load-bearing properties are pinned
 * here as hermetic JVM tests off any Room/Android surface:
 *
 *  - BOUNDARY: a candidate landing exactly on a boundary (now == an occurrence) is NOT returned;
 *    the result is strictly future (`> now`), matching `claimDue`'s `nextFireAt > now` due-check.
 *  - MONOTONICITY: the result always strictly advances past `now` (a schedule can never go backwards).
 *  - METAMORPHIC: coalescing K missed windows (one jump) == stepping one interval at a time until
 *    past `now` (`MisfirePolicy.FIRE_ONCE_AND_COALESCE` fires once, never N catch-up runs).
 */
class RecurrencePropertyTest {

    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // ---- BOUNDARY: now exactly on an occurrence ⇒ the NEXT occurrence, never now itself ----
    @Test
    fun `exactly-on-boundary now returns the strictly-next occurrence`() {
        val spec = RecurrenceSpec(every = 30, unit = RecurrenceUnit.MINUTES)
        val first = 1_000_000L
        // now lands precisely on the 3rd occurrence (first + 3*30min); result must be the 4th.
        val onBoundary = first + 3 * 30 * minute
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = onBoundary, utc, now = onBoundary)
        assertEquals(first + 4 * 30 * minute, next)
        assertTrue("result must be strictly after now", next > onBoundary)
    }

    // ---- BOUNDARY: now just before an occurrence ⇒ that occurrence ----
    @Test
    fun `now one milli before an occurrence returns that occurrence`() {
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.HOURS)
        val first = 500L
        val target = first + 5 * hour
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now = target - 1)
        assertEquals(target, next)
    }

    // ---- MONOTONICITY: result is ALWAYS strictly > now, for any spec/anchor/now ----
    @Test
    fun `result strictly advances past now for any interval anchor and now`() {
        runBlocking {
            checkAll(
                1000,
                Arb.int(1..120),                       // every
                Arb.long(0L..10_000_000_000L),         // firstFireAt
                Arb.long(0L..10_000_000_000L),         // now
            ) { every, first, now ->
                for (unit in RecurrenceUnit.entries) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now)
                    assertTrue("$unit/$every: next $next must be > now $now", next > now)
                }
            }
        }
    }

    // ---- MONOTONICITY: every result is itself a valid occurrence (on the anchored grid) ----
    @Test
    fun `result lands on the recurrence grid`() {
        runBlocking {
            checkAll(
                500,
                Arb.int(1..90),
                Arb.long(0L..5_000_000_000L),
                Arb.long(0L..5_000_000_000L),
            ) { every, first, now ->
                // MINUTES/HOURS are exact fixed-millis grids anchored at first; assert divisibility.
                for (unit in listOf(RecurrenceUnit.MINUTES, RecurrenceUnit.HOURS)) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val step = if (unit == RecurrenceUnit.MINUTES) every * minute else every * hour
                    val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now)
                    assertEquals("offset from anchor must be a whole multiple of the interval",
                        0L, (next - first) % step)
                }
            }
        }
    }

    // ---- METAMORPHIC: coalesce(K windows) == step-one-at-a-time until past now ----
    @Test
    fun `coalescing K missed windows equals stepping to the first future occurrence`() {
        runBlocking {
            checkAll(
                800,
                Arb.int(1..240),
                Arb.long(0L..5_000_000_000L),
                Arb.long(0L..5_000_000_000L),
            ) { every, first, now ->
                for (unit in RecurrenceUnit.entries) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val coalesced = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now)

                    // Reference oracle: naive forward stepping one interval at a time from the anchor.
                    val reference = Recurrence.coalesceMissed(spec, first, lastFiredAt = null, utc, now)
                    assertEquals("coalesce must equal next-occurrence (one fire, not N)", coalesced, reference)
                    assertTrue(reference > now)
                }
            }
        }
    }

    // ---- METAMORPHIC (concrete): 5 skipped daily windows collapse to one future fire ----
    @Test
    fun `five skipped daily windows collapse to a single next fire`() {
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS)
        val first = day // anchor at day 1 boundary in UTC
        // Process was dead for 5 days: now is 5.5 days past first, last fire was at first.
        val now = first + 5 * day + hour * 12
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = first, utc, now)
        // The first DAILY occurrence strictly after now is day 7 (first + 6*day), NOT day 2.
        assertEquals(first + 6 * day, next)
    }

    // ---- DAYS with time-of-day anchor: fires at the anchored local time, DST-safe ----
    @Test
    fun `daily schedule with time-of-day anchor fires at that local time`() {
        val ny = ZoneId.of("America/New_York")
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00")
        // 2026-03-07T00:00:00Z anchor; now is mid-day on 2026-03-09 (after a DST spring-forward 03-08).
        val first = java.time.ZonedDateTime.parse("2026-03-07T09:00:00-05:00").toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.parse("2026-03-09T12:00:00-04:00").toInstant().toEpochMilli()
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, ny, now)
        // Next 09:00 New York after now is 2026-03-10T09:00 EDT — still 09:00 local despite DST.
        val expected = java.time.ZonedDateTime.parse("2026-03-10T09:00:00-04:00").toInstant().toEpochMilli()
        assertEquals(expected, next)
    }
}
