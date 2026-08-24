package com.dmrandevu.gallery.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TR = Locale("tr", "TR")
private val TIME = DateTimeFormatter.ofPattern("HH:mm", TR)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", TR)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", TR)

/**
 * When a video reached us on Instagram, phrased the way the operator thinks about it:
 * today and yesterday are named, anything older gets its date (with the year only when it
 * is not the current one). Returns null for a missing or unparseable timestamp, so callers
 * can simply omit the label.
 */
fun formatSentAt(isoTimestamp: String?): String? {
    if (isoTimestamp.isNullOrBlank()) return null
    val instant = runCatching { Instant.parse(isoTimestamp) }.getOrNull() ?: return null
    val moment = instant.atZone(ZoneId.systemDefault())
    val date = moment.toLocalDate()
    val today = LocalDate.now()
    val time = moment.format(TIME)

    return when {
        date == today -> "Bugün $time"
        date == today.minusDays(1) -> "Dün $time"
        date.year == today.year -> "${moment.format(DAY_MONTH)} $time"
        else -> "${moment.format(DAY_MONTH_YEAR)} $time"
    }
}
