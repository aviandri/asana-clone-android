package com.mekari.project.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Display strings for dates (pure, so JVM tests cover them). */
object Formatting {
    /** "Today", "Tomorrow", "Yesterday", a weekday within the next 6 days, else "Mar 4" ("Mar 4, 2027" in another year). */
    fun due(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Yesterday"
            days in 2..6 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
    }

    fun isOverdue(date: LocalDate, today: LocalDate): Boolean = date.isBefore(today)
}
