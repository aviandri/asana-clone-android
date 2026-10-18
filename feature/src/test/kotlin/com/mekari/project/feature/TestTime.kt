package com.mekari.project.feature

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** A fixed "today" for bucket tests: 2026-10-18 at noon, Jakarta. */
object TestTime {
    val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    val today: LocalDate = LocalDate.of(2026, 10, 18)
    val clock: Clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
}
