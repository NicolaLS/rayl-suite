package xyz.lilsus.raylsuite.backend.rayl

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.UsageEvent
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.raylapi.UsageReport

class UsageCounterTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val stored = mutableListOf<Map<UsageKey, Long>>()
    private var failing = false
    private val counter = UsageCounter(
        knownEntry = { it == "claro" },
        sink = { counts ->
            check(!failing) { "database down" }
            stored += counts
        },
        clock = { Instant.parse("2026-09-29T23:59:00Z") },
        log = {}
    )

    @Test
    fun countsKnownEntriesPerDayEventAndPlatform() = runTest {
        counter.record(
            UsageReport(
                "ios",
                listOf(
                    UsageEvent("claro", UsageEventKind.Added),
                    UsageEvent("claro", UsageEventKind.Paid),
                    UsageEvent("claro", UsageEventKind.Paid),
                    UsageEvent("unknown", UsageEventKind.Paid)
                )
            )
        )
        counter.record(UsageReport("windows", listOf(UsageEvent("claro", UsageEventKind.Paid))))
        counter.flush()

        assertEquals(
            listOf(
                mapOf(
                    UsageKey(day, "claro", UsageEventKind.Added, "ios") to 1L,
                    UsageKey(day, "claro", UsageEventKind.Paid, "ios") to 2L
                )
            ),
            stored
        )
    }

    @Test
    fun capsEventsPerReport() = runTest {
        counter.record(
            UsageReport(
                "android",
                List(50) {
                    UsageEvent("claro", UsageEventKind.Paid)
                }
            )
        )
        counter.flush()
        assertEquals(20L, stored.single().values.single())
    }

    @Test
    fun keepsCountsWhenAFlushFails() = runTest {
        counter.record(UsageReport("android", listOf(UsageEvent("claro", UsageEventKind.Paid))))
        failing = true
        counter.flush()
        counter.record(UsageReport("android", listOf(UsageEvent("claro", UsageEventKind.Paid))))
        failing = false
        counter.flush()

        assertEquals(
            listOf(mapOf(UsageKey(day, "claro", UsageEventKind.Paid, "android") to 2L)),
            stored
        )
    }
}
