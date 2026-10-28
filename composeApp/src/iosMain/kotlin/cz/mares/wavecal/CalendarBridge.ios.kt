package cz.mares.wavecal

import platform.Foundation.*
import platform.UIKit.UIPasteboard
import kotlin.math.floor

actual object CalendarBridge {
    private var permissionListener: (() -> Unit)? = null

    private data class StoredIosEvent(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val startMillis: Long,
        val durationMinutes: Int,
        val alertMinutes: Int,
        val rrule: String?,
        val untilMillis: Long?,
        val excludedStarts: Set<Long>
    )

    actual fun setPermissionResultListener(listener: () -> Unit) {
        permissionListener = listener
    }

    actual fun getSystemLanguageCode(): String {
        val list = NSLocale.preferredLanguages
        val first = list.firstOrNull() as? String
        return (first ?: "cs").take(2).lowercase()
    }

    actual fun loadSettings(): AppSettings {
        val defaults = NSUserDefaults.standardUserDefaults
        val defaultCalId = if (defaults.objectForKey("default_calendar_id") != null) {
            defaults.integerForKey("default_calendar_id").toLong()
        } else 1L
        val lastExpertCalId = if (defaults.objectForKey("posledni_kalendar_id") != null) {
            defaults.integerForKey("posledni_kalendar_id").toLong()
        } else 1L
        val stat = defaults.stringForKey("vybrany_stat") ?: "cz"
        val expert = defaults.boolForKey("expert_mode")
        val duration = if (defaults.objectForKey("default_duration_minutes") != null) {
            defaults.integerForKey("default_duration_minutes").toInt()
        } else 15
        val alert = if (defaults.objectForKey("default_alert_minutes") != null) {
            defaults.integerForKey("default_alert_minutes").toInt()
        } else 15
        val visibleStr = defaults.stringForKey("zobrazene_kalendare_csv")
        val visibleSet = visibleStr?.split(",")?.filter { it.isNotEmpty() }?.toSet()

        return AppSettings(
            defaultCalendarId = defaultCalId,
            lastExpertCalendarId = lastExpertCalId,
            selectedCountry = stat,
            expertMode = expert,
            defaultDurationMinutes = duration,
            defaultAlertMinutes = alert,
            visibleCalendarIds = visibleSet
        )
    }

    actual fun saveSettings(settings: AppSettings) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(settings.defaultCalendarId, forKey = "default_calendar_id")
        defaults.setInteger(settings.lastExpertCalendarId, forKey = "posledni_kalendar_id")
        defaults.setObject(settings.selectedCountry, forKey = "vybrany_stat")
        defaults.setBool(settings.expertMode, forKey = "expert_mode")
        defaults.setInteger(settings.defaultDurationMinutes.toLong(), forKey = "default_duration_minutes")
        defaults.setInteger(settings.defaultAlertMinutes.toLong(), forKey = "default_alert_minutes")
        if (settings.visibleCalendarIds != null) {
            defaults.setObject(settings.visibleCalendarIds.joinToString(","), forKey = "zobrazene_kalendare_csv")
        }
        defaults.synchronize()
    }

    actual fun getAvailableCalendars(): List<MujKalendar> {
        return when (getSystemLanguageCode()) {
            "cs" -> listOf(
                MujKalendar(1L, "Osobní"),
                MujKalendar(2L, "Práce / Taxi"),
                MujKalendar(3L, "Rodina")
            )
            "sk" -> listOf(
                MujKalendar(1L, "Osobný"),
                MujKalendar(2L, "Práca"),
                MujKalendar(3L, "Rodina")
            )
            "pl" -> listOf(
                MujKalendar(1L, "Osobisty"),
                MujKalendar(2L, "Praca"),
                MujKalendar(3L, "Rodzina")
            )
            "de" -> listOf(
                MujKalendar(1L, "Persönlich"),
                MujKalendar(2L, "Arbeit"),
                MujKalendar(3L, "Familie")
            )
            else -> listOf(
                MujKalendar(1L, "Personal"),
                MujKalendar(2L, "Work"),
                MujKalendar(3L, "Family")
            )
        }
    }

    private fun encodeText(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\n", "\\n")
    }

    private fun decodeText(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun loadStoredEvents(): MutableList<StoredIosEvent> {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey("wavecal_ios_events_v1") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        val result = mutableListOf<StoredIosEvent>()
        raw.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split("|")
            if (parts.size >= 9) {
                val id = parts[0].toLongOrNull() ?: return@forEach
                val calId = parts[1].toLongOrNull() ?: 1L
                val title = decodeText(parts[2])
                val startMillis = parts[3].toLongOrNull() ?: return@forEach
                val dur = parts[4].toIntOrNull() ?: 15
                val alert = parts[5].toIntOrNull() ?: 15
                val rrule = parts[6].ifEmpty { null }
                val untilMillis = parts[7].toLongOrNull()
                val excluded = parts[8].split(",").mapNotNull { it.toLongOrNull() }.toSet()
                result.add(
                    StoredIosEvent(
                        id = id,
                        calendarId = calId,
                        title = title,
                        startMillis = startMillis,
                        durationMinutes = dur,
                        alertMinutes = alert,
                        rrule = rrule,
                        untilMillis = untilMillis,
                        excludedStarts = excluded
                    )
                )
            }
        }
        return result
    }

    private fun saveStoredEvents(events: List<StoredIosEvent>) {
        val serialized = events.joinToString("\n") { ev ->
            val rruleStr = ev.rrule ?: ""
            val untilStr = ev.untilMillis?.toString() ?: ""
            val exclStr = ev.excludedStarts.joinToString(",")
            "${ev.id}|${ev.calendarId}|${encodeText(ev.title)}|${ev.startMillis}|${ev.durationMinutes}|${ev.alertMinutes}|$rruleStr|$untilStr|$exclStr"
        }
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(serialized, forKey = "wavecal_ios_events_v1")
        defaults.synchronize()
    }

    private fun occursOnDay(ev: StoredIosEvent, year: Int, monthZeroBased: Int, day: Int): Long? {
        val evParts = extractDateTime(ev.startMillis)
        val targetOccurrenceMillis = createMillis(year, monthZeroBased, day, evParts.hour, evParts.minute)
        if (ev.excludedStarts.contains(targetOccurrenceMillis)) return null
        if (ev.untilMillis != null && targetOccurrenceMillis > ev.untilMillis) return null

        val evEpochDays = daysFromCivil(evParts.year, evParts.monthZeroBased + 1, evParts.day)
        val targetEpochDays = daysFromCivil(year, monthZeroBased + 1, day)
        val diffDays = targetEpochDays - evEpochDays
        if (diffDays < 0L) return null

        val rule = ev.rrule
        if (rule.isNullOrEmpty()) {
            return if (diffDays == 0L) ev.startMillis else null
        }

        val matches = when {
            rule.startsWith("FREQ=DAILY") -> true
            rule.startsWith("FREQ=WEEKLY;INTERVAL=2") -> (diffDays % 14L) == 0L
            rule.startsWith("FREQ=WEEKLY") -> (diffDays % 7L) == 0L
            rule.startsWith("FREQ=MONTHLY") -> evParts.day == day
            rule.startsWith("FREQ=YEARLY") -> evParts.day == day && evParts.monthZeroBased == monthZeroBased
            else -> diffDays == 0L
        }

        return if (matches) targetOccurrenceMillis else null
    }

    actual fun getDaysWithEvents(year: Int, monthZeroBased: Int): Set<Int> {
        val visibleIds = loadSettings().visibleCalendarIds
        val events = loadStoredEvents().filter { ev ->
            visibleIds == null || visibleIds.contains(ev.calendarId.toString())
        }
        if (events.isEmpty()) return emptySet()
        val maxDays = getDaysInMonth(year, monthZeroBased)
        val result = mutableSetOf<Int>()
        for (d in 1..maxDays) {
            for (ev in events) {
                if (occursOnDay(ev, year, monthZeroBased, d) != null) {
                    result.add(d)
                    break
                }
            }
        }
        return result
    }

    actual fun getEventsForDay(
        year: Int,
        monthZeroBased: Int,
        day: Int,
        untitledText: String,
        allDayText: String
    ): List<Udalost> {
        val visibleIds = loadSettings().visibleCalendarIds
        val events = loadStoredEvents().filter { ev ->
            visibleIds == null || visibleIds.contains(ev.calendarId.toString())
        }
        val list = mutableListOf<Udalost>()
        for (ev in events) {
            val occMillis = occursOnDay(ev, year, monthZeroBased, day)
            if (occMillis != null) {
                list.add(
                    Udalost(
                        instanceId = occMillis,
                        eventId = ev.id,
                        title = ev.title.ifEmpty { untitledText },
                        beginTime = occMillis,
                        calendarId = ev.calendarId,
                        rrule = ev.rrule,
                        allDay = false,
                        allDayString = allDayText
                    )
                )
            }
        }
        return list.sortedBy { it.beginTime }
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
        val all = loadStoredEvents()
        val newStartMillis = createMillis(year, monthZeroBased, day, hour, minute)
        val nowId = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

        if (existingEvent == null) {
            all.add(
                StoredIosEvent(
                    id = nowId,
                    calendarId = calendar.id,
                    title = title,
                    startMillis = newStartMillis,
                    durationMinutes = durationMinutes,
                    alertMinutes = alertMinutes,
                    rrule = rrule,
                    untilMillis = null,
                    excludedStarts = emptySet()
                )
            )
            if (isExpertMode) {
                val cur = loadSettings()
                saveSettings(cur.copy(lastExpertCalendarId = calendar.id))
            }
        } else {
            val idx = all.indexOfFirst { it.id == existingEvent.eventId }
            if (idx != -1) {
                val old = all[idx]
                if (old.rrule.isNullOrEmpty()) {
                    all[idx] = old.copy(
                        calendarId = calendar.id,
                        title = title,
                        startMillis = newStartMillis,
                        durationMinutes = durationMinutes,
                        alertMinutes = alertMinutes,
                        rrule = rrule
                    )
                } else {
                    if (editThisAndFuture) {
                        all[idx] = old.copy(untilMillis = existingEvent.beginTime - 1000L)
                        all.add(
                            StoredIosEvent(
                                id = nowId,
                                calendarId = calendar.id,
                                title = title,
                                startMillis = newStartMillis,
                                durationMinutes = durationMinutes,
                                alertMinutes = alertMinutes,
                                rrule = rrule,
                                untilMillis = null,
                                excludedStarts = emptySet()
                            )
                        )
                    } else {
                        all[idx] = old.copy(excludedStarts = old.excludedStarts + existingEvent.beginTime)
                        all.add(
                            StoredIosEvent(
                                id = nowId,
                                calendarId = calendar.id,
                                title = title,
                                startMillis = newStartMillis,
                                durationMinutes = durationMinutes,
                                alertMinutes = alertMinutes,
                                rrule = null,
                                untilMillis = null,
                                excludedStarts = emptySet()
                            )
                        )
                    }
                }
            }
        }
        saveStoredEvents(all)
        return true
    }

    actual fun deleteEvent(event: Udalost, thisAndFuture: Boolean): Boolean {
        val all = loadStoredEvents()
        val idx = all.indexOfFirst { it.id == event.eventId }
        if (idx == -1) return false
        val old = all[idx]
        if (old.rrule.isNullOrEmpty()) {
            all.removeAt(idx)
        } else {
            if (thisAndFuture) {
                all[idx] = old.copy(untilMillis = event.beginTime - 1000L)
            } else {
                all[idx] = old.copy(excludedStarts = old.excludedStarts + event.beginTime)
            }
        }
        saveStoredEvents(all)
        return true
    }

    actual fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    actual fun contactMatchesNameDay(nameDayString: String): Boolean {
        return false
    }

    private fun daysFromCivil(year: Int, monthOneBased: Int, day: Int): Long {
        val y = if (monthOneBased <= 2) year - 1L else year.toLong()
        val era = if (y >= 0) y / 400L else (y - 399L) / 400L
        val yoe = y - era * 400L
        val m = monthOneBased.toLong()
        val d = day.toLong()
        val doy = (153L * (if (m > 2) m - 3L else m + 9L) + 2L) / 5L + d - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146097L + doe - 719468L
    }

    private fun civilFromDays(zRaw: Long): Triple<Int, Int, Int> {
        val z = zRaw + 719468L
        val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val finalYear = if (m <= 2L) y + 1L else y
        return Triple(finalYear.toInt(), (m - 1L).toInt(), d.toInt())
    }

    private fun currentTimeZoneOffsetSeconds(): Long {
        return NSTimeZone.localTimeZone.secondsFromGMT.toLong()
    }

    private fun createMillis(year: Int, monthZeroBased: Int, day: Int, hour: Int, minute: Int): Long {
        val epochDays = daysFromCivil(year, monthZeroBased + 1, day)
        val localSeconds = epochDays * 86400L + hour * 3600L + minute * 60L
        val exactUtcSeconds = localSeconds - currentTimeZoneOffsetSeconds()
        return exactUtcSeconds * 1000L
    }

    actual fun getTodayParts(): DateTimeParts {
        val nowMillis = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
        return extractDateTime(nowMillis)
    }

    actual fun extractDateTime(timeMillis: Long): DateTimeParts {
        val localSeconds = (timeMillis / 1000L) + currentTimeZoneOffsetSeconds()
        val localDays = floor(localSeconds / 86400.0).toLong()
        val remSeconds = ((localSeconds % 86400L) + 86400L) % 86400L
        val hour = (remSeconds / 3600L).toInt()
        val minute = ((remSeconds % 3600L) / 60L).toInt()
        val (y, mZero, d) = civilFromDays(localDays)
        return DateTimeParts(
            year = y,
            monthZeroBased = mZero,
            day = d,
            hour = hour,
            minute = minute
        )
    }

    actual fun getDaysInMonth(year: Int, monthZeroBased: Int): Int {
        val m = monthZeroBased + 1
        return when (m) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28
            else -> 30
        }
    }

    actual fun getFirstDayOfWeekOffsetMondayBased(year: Int, monthZeroBased: Int): Int {
        val epochDays = daysFromCivil(year, monthZeroBased + 1, 1)
        return (((epochDays + 3L) % 7L) + 7L).toInt() % 7
    }

    actual fun formatLongDate(year: Int, monthZeroBased: Int, day: Int): String {
        val strings = Localization.getStrings(getSystemLanguageCode())
        val monthName = strings.monthNames.getOrElse(monthZeroBased) { "${monthZeroBased + 1}." }.lowercase()
        return "$day. $monthName $year"
    }

    actual fun formatMediumDate(year: Int, monthZeroBased: Int, day: Int): String {
        return "$day. ${monthZeroBased + 1}. $year"
    }
}
