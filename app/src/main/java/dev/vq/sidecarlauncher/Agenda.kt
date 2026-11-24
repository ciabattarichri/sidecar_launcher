package dev.vq.sidecarlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val color: Int,
)

/** Upcoming events from every visible calendar synced on the phone. */
object AgendaRepo {
    private const val DAYS_AHEAD = 7L
    private const val MAX_EVENTS = 80

    suspend fun load(context: Context, hidden: Set<Long> = emptySet()): List<CalEvent> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - day)
            ContentUris.appendId(it, now + DAYS_AHEAD * day)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val zone = ZoneId.systemDefault()
        val nowLocal = LocalDateTime.now()
        val events = mutableListOf<CalEvent>()
        try {
            context.contentResolver.query(
                uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(5) in hidden) continue
                    val allDay = c.getInt(3) == 1
                    // All-day events are stored as UTC midnights.
                    val z = if (allDay) ZoneOffset.UTC else zone
                    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), z)
                    val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), z)
                    if (!end.isAfter(nowLocal)) continue
                    events += CalEvent(c.getString(0) ?: "(senza titolo)", start, end, allDay, c.getInt(4))
                }
            }
        } catch (_: SecurityException) {
        }
        events.sortedWith(compareBy({ it.start.toLocalDate() }, { !it.allDay }, { it.start })).take(MAX_EVENTS)
    }

    data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

    /** Every visible calendar synced on the phone. */
    suspend fun calendars(context: Context): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, CalendarContract.Calendars.ACCOUNT_NAME,
        )?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3))
        }
        out
    }

    fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> tr("Oggi", "Today")
            today.plusDays(1) -> tr("Domani", "Tomorrow")
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d", I18n.locale))
                .replaceFirstChar { it.uppercase() }
        }
    }
}
