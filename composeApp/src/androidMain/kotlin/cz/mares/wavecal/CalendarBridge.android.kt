package cz.mares.wavecal

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object AndroidContextHolder {
    var appContext: Context? = null
}

actual object CalendarBridge {
    private var permissionListener: (() -> Unit)? = null

    fun notifyPermissionsResult() {
        permissionListener?.invoke()
        aktualizujWidgetyABudiky()
    }

    actual fun setPermissionResultListener(listener: () -> Unit) {
        permissionListener = listener
    }

    actual fun getSystemLanguageCode(): String {
        return Locale.getDefault().language
    }

    actual fun loadSettings(): AppSettings {
        val ctx = AndroidContextHolder.appContext ?: return AppSettings()
        val prefs = ctx.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        return AppSettings(
            defaultCalendarId = prefs.getLong("default_calendar_id", -1L),
            lastExpertCalendarId = prefs.getLong("posledni_kalendar_id", -1L),
            selectedCountry = prefs.getString("vybrany_stat", "cz") ?: "cz",
            expertMode = prefs.getBoolean("expert_mode", false),
            defaultDurationMinutes = prefs.getInt("default_duration_minutes", 15),
            defaultAlertMinutes = prefs.getInt("default_alert_minutes", 15),
            visibleCalendarIds = prefs.getStringSet("zobrazene_kalendare", null)
        )
    }

    actual fun saveSettings(settings: AppSettings) {
        val ctx = AndroidContextHolder.appContext ?: return
        val prefs = ctx.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong("default_calendar_id", settings.defaultCalendarId)
            .putLong("posledni_kalendar_id", settings.lastExpertCalendarId)
            .putString("vybrany_stat", settings.selectedCountry)
            .putBoolean("expert_mode", settings.expertMode)
            .putInt("default_duration_minutes", settings.defaultDurationMinutes)
            .putInt("default_alert_minutes", settings.defaultAlertMinutes)

        if (settings.visibleCalendarIds != null) {
            editor.putStringSet("zobrazene_kalendare", settings.visibleCalendarIds)
        }
        editor.apply()
        aktualizujWidgetyABudiky()
    }

    private fun ziskejPovoleneKalendare(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val ulozeneId = prefs.getStringSet("zobrazene_kalendare", null)
        return if (ulozeneId != null && ulozeneId.isNotEmpty()) {
            val safeIds = ulozeneId.mapNotNull { it.toLongOrNull() }
            if (safeIds.isNotEmpty()) {
                "${CalendarContract.Instances.CALENDAR_ID} IN (${safeIds.joinToString(",")})"
            } else null
        } else null
    }

    private fun aktualizujWidgetyABudiky() {
        val ctx = AndroidContextHolder.appContext ?: return
        // 1. Okamžitý přímý přepis widgetu + broadcast
        AgendaWidget.refreshAllWidgets(ctx)

        // 2. Tiché přeplánování budíků na pozadí
        Thread {
            try {
                AlarmsManager.naplanujBudikyNaPozadi(ctx)
            } catch (e: Exception) {
                // Tichá pojistka
            }
        }.start()
    }

    private data class RawCalInfo(
        val cal: MujKalendar,
        val isWritable: Boolean,
        val isPrimary: Boolean,
        val isGoogleAccount: Boolean
    )

    private fun queryCalendarsInternal(ctx: Context): List<RawCalInfo> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<RawCalInfo>()
        try {
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.ACCOUNT_TYPE
            )
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                proj,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: "Kalendář $id"
                    val access = if (!cursor.isNull(2)) cursor.getInt(2) else 0
                    val primary = if (!cursor.isNull(3)) cursor.getInt(3) == 1 else false
                    val accountType = cursor.getString(4) ?: ""
                    val writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    val isGoogle = accountType.contains("com.google", ignoreCase = true)
                    list.add(
                        RawCalInfo(
                            cal = MujKalendar(id, name),
                            isWritable = writable,
                            isPrimary = primary,
                            isGoogleAccount = isGoogle
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Tichá pojistka
        }
        // Řazení: Zapisovatelné primární Google/Samsung účty vždy nahoře, read-only svátky dole
        return list.sortedWith(
            compareByDescending<RawCalInfo> { it.isWritable }
                .thenByDescending { it.isPrimary }
                .thenByDescending { it.isGoogleAccount }
                .thenBy { it.cal.id }
        )
    }

    actual fun getAvailableCalendars(): List<MujKalendar> {
        val ctx = AndroidContextHolder.appContext ?: return emptyList()
        return queryCalendarsInternal(ctx).map { it.cal }
    }

    actual fun getDaysWithEvents(year: Int, monthZeroBased: Int): Set<Int> {
        val ctx = AndroidContextHolder.appContext ?: return emptySet()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptySet()
        }
        val dny = mutableSetOf<Int>()
        val utcZone = TimeZone.getTimeZone("UTC")

        val localCal = Calendar.getInstance().apply {
            set(year, monthZeroBased, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val localStartOfMonth = localCal.timeInMillis
        localCal.add(Calendar.MONTH, 1)
        val localEndOfMonth = localCal.timeInMillis - 1L

        val utcCal = Calendar.getInstance(utcZone).apply {
            set(year, monthZeroBased, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val utcStartOfMonth = utcCal.timeInMillis
        utcCal.add(Calendar.MONTH, 1)
        val utcEndOfMonth = utcCal.timeInMillis - 1L

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, minOf(localStartOfMonth, utcStartOfMonth))
        ContentUris.appendId(builder, maxOf(localEndOfMonth, utcEndOfMonth))

        val proj = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        try {
            ctx.contentResolver.query(
                builder.build(),
                proj,
                ziskejPovoleneKalendare(ctx),
                null,
                null
            )?.use { cursor ->
                val eventLocalCal = Calendar.getInstance()
                val eventUtcCal = Calendar.getInstance(utcZone)
                while (cursor.moveToNext()) {
                    val begin = cursor.getLong(0)
                    val end = cursor.getLong(1)
                    val isAllDay = cursor.getInt(2) == 1

                    if (isAllDay) {
                        var cur = maxOf(begin, utcStartOfMonth)
                        val effectiveEnd = if (end > begin) end else (begin + 1L)
                        while (cur < effectiveEnd && cur <= utcEndOfMonth) {
                            eventUtcCal.timeInMillis = cur
                            if (eventUtcCal.get(Calendar.YEAR) == year && eventUtcCal.get(Calendar.MONTH) == monthZeroBased) {
                                dny.add(eventUtcCal.get(Calendar.DAY_OF_MONTH))
                            }
                            cur += 86_400_000L
                        }
                    } else {
                        eventLocalCal.timeInMillis = begin
                        if (eventLocalCal.get(Calendar.YEAR) == year && eventLocalCal.get(Calendar.MONTH) == monthZeroBased) {
                            dny.add(eventLocalCal.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Tichá pojistka
        }
        return dny
    }

    actual fun getEventsForDay(
        year: Int,
        monthZeroBased: Int,
        day: Int,
        untitledText: String,
        allDayText: String
    ): List<Udalost> {
        val ctx = AndroidContextHolder.appContext ?: return emptyList()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val localStartOfDay = Calendar.getInstance().apply {
            set(year, monthZeroBased, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val localEndOfDay = Calendar.getInstance().apply {
            set(year, monthZeroBased, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val utcZone = TimeZone.getTimeZone("UTC")
        val utcStartOfDay = Calendar.getInstance(utcZone).apply {
            set(year, monthZeroBased, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val utcEndOfDay = Calendar.getInstance(utcZone).apply {
            set(year, monthZeroBased, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, minOf(localStartOfDay, utcStartOfDay))
        ContentUris.appendId(builder, maxOf(localEndOfDay, utcEndOfDay))

        val proj = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.END
        )

        val seznam = mutableListOf<Udalost>()
        try {
            ctx.contentResolver.query(
                builder.build(),
                proj,
                ziskejPovoleneKalendare(ctx),
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val instanceId = cursor.getLong(0)
                    val eventId = cursor.getLong(1)
                    val title = cursor.getString(2) ?: untitledText
                    val begin = cursor.getLong(3)
                    val calendarId = cursor.getLong(4)
                    val rrule = cursor.getString(5)
                    val isAllDay = cursor.getInt(6) == 1
                    val end = cursor.getLong(7)

                    val belongsToSelectedDay = if (isAllDay) {
                        begin <= utcEndOfDay && end > utcStartOfDay
                    } else {
                        begin in localStartOfDay..localEndOfDay
                    }

                    if (!belongsToSelectedDay) continue

                    seznam.add(
                        Udalost(
                            instanceId = instanceId,
                            eventId = eventId,
                            title = title,
                            beginTime = begin,
                            calendarId = calendarId,
                            rrule = rrule,
                            allDay = isAllDay,
                            allDayString = allDayText
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Tichá pojistka
        }
        return seznam
    }

    actual fun saveOrUpdateEvent(
        existingEvent: Udalost?,
        editThisAndFuture: Boolean,
        calendar: MujKalendar,
        title: String,
        year: Int,
        monthZeroBased: Int,
        day: Int,
        hour: Int,
        minute: Int,
        durationMinutes: Int,
        alertMinutes: Int,
        rrule: String?,
        isExpertMode: Boolean
    ): Boolean {
        val ctx = AndroidContextHolder.appContext ?: return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        // Pojistka: Pokud by vybraný kalendář byl read-only (např. Svátky), automaticky použijeme první zapisovatelný kalendář
        val allRaw = queryCalendarsInternal(ctx)
        val chosenRaw = allRaw.find { it.cal.id == calendar.id }
        val targetCalendarId = if (chosenRaw != null && chosenRaw.isWritable) {
            chosenRaw.cal.id
        } else {
            allRaw.firstOrNull { it.isWritable }?.cal?.id ?: calendar.id
        }

        val calStart = Calendar.getInstance().apply {
            set(year, monthZeroBased, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, calStart.timeInMillis)
            put(CalendarContract.Events.DTEND, calStart.timeInMillis + (durationMinutes * 60 * 1000L))
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (alertMinutes != -1) {
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            if (existingEvent == null || editThisAndFuture) {
                if (rrule != null) put(CalendarContract.Events.RRULE, rrule) else putNull(CalendarContract.Events.RRULE)
            }
        }

        val zariditNotifikace = { targetEventId: Long ->
            try {
                ctx.contentResolver.delete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID}=?",
                    arrayOf(targetEventId.toString())
                )
            } catch (e: Exception) {}

            if (alertMinutes != -1) {
                val remValues = ContentValues().apply {
                    put(CalendarContract.Reminders.MINUTES, alertMinutes)
                    put(CalendarContract.Reminders.EVENT_ID, targetEventId)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                try {
                    ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues)
                } catch (e: Exception) {}

                val casZvoneni = calStart.timeInMillis - (alertMinutes * 60 * 1000L)
                AlarmsManager.nastavitVlastniUpozorneni(ctx, targetEventId, casZvoneni, title)
            } else {
                AlarmsManager.zrusitVlastniUpozorneni(ctx, targetEventId)
            }
        }

        return try {
            if (existingEvent == null) {
                val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                val newEventId = uri?.lastPathSegment?.toLongOrNull() ?: return false
                zariditNotifikace(newEventId)
                if (isExpertMode) {
                    ctx.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("posledni_kalendar_id", targetCalendarId)
                        .apply()
                }
            } else {
                if (existingEvent.rrule.isNullOrEmpty()) {
                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEvent.eventId)
                    ctx.contentResolver.update(uri, values, null, null)
                    zariditNotifikace(existingEvent.eventId)
                } else {
                    if (editThisAndFuture) {
                        val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = existingEvent.beginTime - 1000
                        }
                        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val untilDate = fmt.format(untilCal.time)
                        val baseRrule = existingEvent.rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
                        val newRrule = "$baseRrule;UNTIL=$untilDate"

                        val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEvent.eventId)
                        val updateValues = ContentValues().apply { put(CalendarContract.Events.RRULE, newRrule) }
                        ctx.contentResolver.update(updateUri, updateValues, null, null)

                        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        val newEventId = uri?.lastPathSegment?.toLongOrNull()
                        if (newEventId != null) {
                            zariditNotifikace(newEventId)
                        }
                    } else {
                        values.put(CalendarContract.Events.ORIGINAL_ID, existingEvent.eventId)
                        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, existingEvent.beginTime)
                        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        val newEventId = uri?.lastPathSegment?.toLongOrNull()
                        if (newEventId != null) {
                            zariditNotifikace(newEventId)
                        }
                    }
                }
            }
            aktualizujWidgetyABudiky()
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun deleteEvent(event: Udalost, thisAndFuture: Boolean): Boolean {
        val ctx = AndroidContextHolder.appContext ?: return false
        return try {
            if (event.rrule.isNullOrEmpty()) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                ctx.contentResolver.delete(uri, null, null)
                AlarmsManager.zrusitVlastniUpozorneni(ctx, event.eventId)
            } else {
                if (thisAndFuture) {
                    val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = event.beginTime - 1000
                    }
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val untilDate = fmt.format(untilCal.time)
                    val baseRrule = event.rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
                    val newRrule = "$baseRrule;UNTIL=$untilDate"

                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                    val values = ContentValues().apply { put(CalendarContract.Events.RRULE, newRrule) }
                    ctx.contentResolver.update(uri, values, null, null)
                } else {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.ORIGINAL_ID, event.eventId)
                        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.beginTime)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }
            }
            aktualizujWidgetyABudiky()
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun copyToClipboard(label: String, text: String) {
        val ctx = AndroidContextHolder.appContext ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    actual fun contactMatchesNameDay(nameDayString: String): Boolean {
        val ctx = AndroidContextHolder.appContext ?: return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val jmena = nameDayString.split(Regex("(?i)\\s*(?:/|,|\\ba\\b|\\bi\\b|\\bund\\b|\\band\\b)\\s*"))
        for (jmeno in jmena) {
            val cisteJmeno = jmeno.trim()
            if (cisteJmeno.isEmpty() ||
                cisteJmeno.contains("Svátek", ignoreCase = true) ||
                cisteJmeno.contains("Nový rok", ignoreCase = true) ||
                cisteJmeno.contains("Státní", ignoreCase = true)
            ) {
                continue
            }
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$cisteJmeno%")
            try {
                ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.count > 0) return true
                }
            } catch (e: Exception) {}
        }
        return false
    }

    actual fun getTodayParts(): DateTimeParts {
        val cal = Calendar.getInstance()
        return DateTimeParts(
            year = cal.get(Calendar.YEAR),
            monthZeroBased = cal.get(Calendar.MONTH),
            day = cal.get(Calendar.DAY_OF_MONTH),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE)
        )
    }

    actual fun extractDateTime(timeMillis: Long): DateTimeParts {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return DateTimeParts(
            year = cal.get(Calendar.YEAR),
            monthZeroBased = cal.get(Calendar.MONTH),
            day = cal.get(Calendar.DAY_OF_MONTH),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE)
        )
    }

    actual fun getDaysInMonth(year: Int, monthZeroBased: Int): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    actual fun getFirstDayOfWeekOffsetMondayBased(year: Int, monthZeroBased: Int): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        var offset = cal.get(Calendar.DAY_OF_WEEK) - 2
        if (offset < 0) offset = 6
        return offset
    }

    actual fun formatLongDate(year: Int, monthZeroBased: Int, day: Int): String {
        val cal = Calendar.getInstance().apply { set(year, monthZeroBased, day) }
        return DateFormat.getDateInstance(DateFormat.LONG).format(cal.time)
    }

    actual fun formatMediumDate(year: Int, monthZeroBased: Int, day: Int): String {
        val cal = Calendar.getInstance().apply { set(year, monthZeroBased, day) }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(cal.time)
    }
}
