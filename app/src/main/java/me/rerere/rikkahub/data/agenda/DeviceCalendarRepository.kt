package me.rerere.rikkahub.data.agenda

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val description: String,
    val location: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val calendarName: String,
)

class DeviceCalendarRepository(private val context: Context) {
    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun getEvents(beginAt: Long, endAt: Long, limit: Int = 100): List<DeviceCalendarEvent> =
        withContext(Dispatchers.IO) {
            if (!canRead() || beginAt >= endAt) return@withContext emptyList()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(beginAt.toString())
                .appendPath(endAt.toString())
                .build()
            buildList {
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            DeviceCalendarEvent(
                                id = cursor.getLong(0),
                                title = cursor.getString(1).orEmpty().ifBlank { "无标题日程" },
                                description = cursor.getString(2).orEmpty(),
                                location = cursor.getString(3).orEmpty(),
                                startAt = cursor.getLong(4),
                                endAt = cursor.getLong(5),
                                allDay = cursor.getInt(6) == 1,
                                calendarName = cursor.getString(7).orEmpty(),
                            )
                        )
                    }
                }
            }
        }

    fun openEvent(eventId: Long): Boolean = runCatching {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
