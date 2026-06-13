package me.rerere.rikkahub.ui.pages.schedule

import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.schedule.Recurrence
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure-summary tests (SPEC.md M3 / task T5, SC4). [scheduleSummary] renders the human phrase the
 * Schedule UI shows under the create form / on each card. Its load-bearing guarantee: the next fire
 * it shows is computed by the SAME [Recurrence.nextOccurrenceAfter] the repository's `claimDue`
 * calls — never a second date-math implementation — so the preview equals what the worker fires.
 *
 *  - ONE_SHOT shows `firstFireAt` verbatim ("Once at <date time> (<zone>)"); no recurrence math.
 *  - METAMORPHIC (SC4): a RECURRING summary's embedded next-fire instant equals
 *    `Recurrence.nextOccurrenceAfter(sameSpec, ...)` formatted in the same zone — the two
 *    implementations agree because the summary calls that one function rather than reimplementing it.
 */
class ScheduleSummaryTest {

    private val jakarta = "Asia/Jakarta"
    private val locale = Locale.ENGLISH
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `one-shot shows firstFireAt verbatim with zone, no recurrence math`() {
        val zone = ZoneId.of(jakarta)
        // 2026-06-16 09:00 Asia/Jakarta (UTC+7).
        val firstFireAt = java.time.ZonedDateTime
            .parse("2026-06-16T09:00:00+07:00").toInstant().toEpochMilli()
        val now = firstFireAt - day // before the fire, must not matter for one-shot

        val summary = scheduleSummary(
            kind = ScheduleKind.ONE_SHOT,
            spec = null,
            firstFireAt = firstFireAt,
            timeZoneId = jakarta,
            now = now,
            locale = locale,
        )

        assertTrue("one-shot phrase must start with 'Once at': $summary", summary.startsWith("Once at "))
        assertTrue("one-shot must name the zone: $summary", summary.contains("($jakarta)"))
        // The shown instant is firstFireAt itself — formatting firstFireAt in-zone must appear.
        val shown = Instant.ofEpochMilli(firstFireAt).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale))
        assertTrue("one-shot must show firstFireAt formatted in-zone: $summary", summary.contains(shown))
    }

    // ---- METAMORPHIC (SC4): the embedded next fire == Recurrence.nextOccurrenceAfter, same zone ----
    @Test
    fun `recurring summary next-fire equals Recurrence#nextOccurrenceAfter`() {
        val zone = ZoneId.of(jakarta)
        val spec = RecurrenceSpec(every = 2, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00")
        val firstFireAt = java.time.ZonedDateTime
            .parse("2026-06-16T09:00:00+07:00").toInstant().toEpochMilli()
        val now = firstFireAt + 3 * day + hour // process woke a few days later

        val nextFire = Recurrence.nextOccurrenceAfter(
            spec = spec, firstFireAt = firstFireAt, lastFiredAt = null, zone = zone, now = now,
        )
        val expectedShown = Instant.ofEpochMilli(nextFire).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale))

        val summary = scheduleSummary(
            kind = ScheduleKind.RECURRING,
            spec = spec,
            firstFireAt = firstFireAt,
            timeZoneId = jakarta,
            now = now,
            locale = locale,
        )

        assertTrue(
            "summary must embed the Recurrence-computed next fire ($expectedShown): $summary",
            summary.contains(expectedShown),
        )
        assertTrue("recurring summary must carry a cadence phrase: $summary", summary.startsWith("Every "))
        assertTrue("recurring summary must name the zone: $summary", summary.contains("($jakarta)"))
    }

    @Test
    fun `recurring minutes cadence phrase reads naturally`() {
        val spec = RecurrenceSpec(every = 30, unit = RecurrenceUnit.MINUTES)
        val firstFireAt = 1_000_000_000_000L
        val now = firstFireAt - hour
        val summary = scheduleSummary(
            kind = ScheduleKind.RECURRING,
            spec = spec,
            firstFireAt = firstFireAt,
            timeZoneId = "UTC",
            now = now,
            locale = locale,
        )
        assertTrue("minutes cadence: $summary", summary.startsWith("Every 30 minutes"))
    }

    @Test
    fun `recurring singular interval drops the plural`() {
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.HOURS)
        val firstFireAt = 1_000_000_000_000L
        val now = firstFireAt - hour
        val summary = scheduleSummary(
            kind = ScheduleKind.RECURRING,
            spec = spec,
            firstFireAt = firstFireAt,
            timeZoneId = "UTC",
            now = now,
            locale = locale,
        )
        assertTrue("singular hour cadence: $summary", summary.startsWith("Every hour"))
    }

    @Test
    fun `daily with time-of-day names the local fire time in the cadence phrase`() {
        val spec = RecurrenceSpec(every = 2, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00")
        val firstFireAt = java.time.ZonedDateTime
            .parse("2026-06-16T09:00:00+07:00").toInstant().toEpochMilli()
        val now = firstFireAt - day
        val summary = scheduleSummary(
            kind = ScheduleKind.RECURRING,
            spec = spec,
            firstFireAt = firstFireAt,
            timeZoneId = jakarta,
            now = now,
            locale = locale,
        )
        assertTrue("days+timeOfDay cadence: $summary", summary.startsWith("Every 2 days at 09:00"))
    }
}
