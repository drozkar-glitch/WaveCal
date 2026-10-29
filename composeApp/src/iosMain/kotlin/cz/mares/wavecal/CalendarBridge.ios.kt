@file:OptIn(ExperimentalForeignApi::class)

package cz.mares.wavecal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Contacts.*
import platform.EventKit.*
import platform.Foundation.*
import platform.UIKit.UIPasteboard
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.math.floor

actual object CalendarBridge {
    private val eventStore = EKEventStore()
    private val contactStore = CNContactStore()
    private var permissionListener: (() -> Unit)? = null
    private var permissionsRequested = false

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
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        if (permissionsRequested) return
        permissionsRequested = true

        eventStore.requestAccessToEntityType(EKEntityTypeEvent) { _, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                permissionListener?.invoke()
            }
        }

        contactStore.requestAccessForEntityType(CNEntityTypeContacts) { _, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                permissionListener?.invoke()
            }
        }
    }

    private fun hasCalendarAccess(): Boolean {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityTypeEvent)
        // 3 = Authorized, 4 = FullAccess (iOS 17+)
        return status == EKAuthorizationStatusAuthorized || status >= 3L
    }

    private fun hasContactsAccess(): Boolean {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityTypeContacts)
        return status == CNAuthorizationStatusAuthorized
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
        } else -1L
        val lastExpertCalId = if (defaults.objectForKey("posledni_kalendar_id") != null) {
            defaults.integerForKey("posledni_kalendar_id").toLong()
        } else -1L
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

    private fun stableIdFromString(str: String): Long {
        var h = 1125899906842597L
        for (i in 0 until str.length) {
            h = 31L * h + str[i].code.toLong()
        }
        return abs(h)
    }

    private fun getSystemEkCalendars(): List<EKCalendar> {
        if (!hasCalendarAccess()) return emptyList()
        val rawList = eventStore.calendarsForEntityType(EKEntityTypeEvent)
        val ekList = rawList.mapNotNull { it as? EKCalendar }
        val defaultCalId = eventStore.defaultCalendarForNewEvents?.calendarIdentifier
        return ekList.sortedWith(
            compareByDescending<EKCalendar> { it.allowsContentModifications }
                .thenByDescending { it.calendarIdentifier == defaultCalId }
                .thenBy { it.title }
        )
    }

    actual fun getAvailableCalendars(): List<MujKalendar> {
        requestPermissionsIfNeeded()
        val ekCalendars = getSystemEkCalendars()
        if (ekCalendars.isNotEmpty()) {
            return ekCalendars.map { cal ->
                val id = stableIdFromString(cal.calendarIdentifier)
                MujKalendar(
                    id = id,
                    name = cal.title,
                    platformId = cal.calendarIdentifier
                )
            }
        }
        return when (getSystemLanguageCode()) {
            "cs" -> listOf(
                MujKalendar(1L, "Osobní"),
                MujKalendar(2L, "Práce / Taxi"),
                MujKalendar(3L, "Rodina")
            )
            "sk" -> listOf(
                MujKalendar(1L, "Osobné"),
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

    private fun getFilteredEkCalendars(): List<EKCalendar>? {
        val allEk = getSystemEkCalendars()
        if (allEk.isEmpty()) return null
        val visibleIds = loadSettings().visibleCalendarIds
        if (visibleIds == null || visibleIds.isEmpty()) return allEk
        val filtered = allEk.filter { cal ->
            val numId = stableIdFromString(cal.calendarIdentifier).toString()
            visibleIds.contains(numId) || visibleIds.contains(cal.calendarIdentifier)
        }
        return if (filtered.isNotEmpty()) filtered else allEk
    }

    private fun formatRruleFromEkEvent(ev: EKEvent): String? {
        val rules = ev.recurrenceRules
        val firstRule = rules?.firstOrNull() as? EKRecurrenceRule ?: return null
        val interval = firstRule.interval.toInt()
        return when (firstRule.frequency) {
            EKRecurrenceFrequencyDaily -> "FREQ=DAILY"
            EKRecurrenceFrequencyWeekly -> if (interval == 2) "FREQ=WEEKLY;INTERVAL=2" else "FREQ=WEEKLY"
            EKRecurrenceFrequencyMonthly -> "FREQ=MONTHLY"
            EKRecurrenceFrequencyYearly -> "FREQ=YEARLY"
            else -> null
        }
    }

    private fun buildEkRecurrenceRule(rrule: String?): EKRecurrenceRule? {
        if (rrule.isNullOrBlank()) return null
        return when {
            rrule.startsWith("FREQ=DAILY") ->
                EKRecurrenceRule(recurrenceWithFrequency = EKRecurrenceFrequencyDaily, interval = 1L, end = null)
            rrule.startsWith("FREQ=WEEKLY;INTERVAL=2") ->
                EKRecurrenceRule(recurrenceWithFrequency = EKRecurrenceFrequencyWeekly, interval = 2L, end = null)
            rrule.startsWith("FREQ=WEEKLY") ->
                EKRecurrenceRule(recurrenceWithFrequency = EKRecurrenceFrequencyWeekly, interval = 1L, end = null)
            rrule.startsWith("FREQ=MONTHLY") ->
                EKRecurrenceRule(recurrenceWithFrequency = EKRecurrenceFrequencyMonthly, interval = 1L, end = null)
            rrule.startsWith("FREQ=YEARLY") ->
                EKRecurrenceRule(recurrenceWithFrequency = EKRecurrenceFrequencyYearly, interval = 1L, end = null)
            else -> null
        }
    }

    actual fun getDaysWithEvents(year: Int, monthZeroBased: Int): Set<Int> {
        val ekCalendars = getFilteredEkCalendars()
        if (ekCalendars != null) {
            val maxDays = getDaysInMonth(year, monthZeroBased)
            val startMillis = createMillis(year, monthZeroBased, 1, 0, 0)
            val endMillis = createMillis(year, monthZeroBased, maxDays, 23, 59) + 59_000L
            val startDate = NSDate.dateWithTimeIntervalSince1970(startMillis / 1000.0)
            val endDate = NSDate.dateWithTimeIntervalSince1970(endMillis / 1000.0)
            val predicate = eventStore.predicateForEventsWithStartDate(
                startDate = startDate,
                endDate = endDate,
                calendars = ekCalendars
            )
            val matching = eventStore.eventsMatchingPredicate(predicate).mapNotNull { it as? EKEvent }
            val dny = mutableSetOf<Int>()
            for (ev in matching) {
                val sDate = ev.startDate ?: continue
                val sMillis = (sDate.timeIntervalSince1970 * 1000.0).toLong()
                val parts = extractDateTime(sMillis)
                if (parts.year == year && parts.monthZeroBased == monthZeroBased) {
                    dny.add(parts.day)
                }
            }
            return dny
        }

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
        val ekCalendars = getFilteredEkCalendars()
        if (ekCalendars != null) {
            val startMillis = createMillis(year, monthZeroBased, day, 0, 0)
            val endMillis = createMillis(year, monthZeroBased, day, 23, 59) + 59_000L
            val startDate = NSDate.dateWithTimeIntervalSince1970(startMillis / 1000.0)
            val endDate = NSDate.dateWithTimeIntervalSince1970(endMillis / 1000.0)
            val predicate = eventStore.predicateForEventsWithStartDate(
                startDate = startDate,
                endDate = endDate,
                calendars = ekCalendars
            )
            val matching = eventStore.eventsMatchingPredicate(predicate).mapNotNull { it as? EKEvent }
            val result = mutableListOf<Udalost>()
            for (ev in matching) {
                val sDate = ev.startDate ?: continue
                val sMillis = (sDate.timeIntervalSince1970 * 1000.0).toLong()
                val parts = extractDateTime(sMillis)
                if (parts.year != year || parts.monthZeroBased != monthZeroBased || parts.day != day) {
                    continue
                }
                val evIdentifier = ev.eventIdentifier ?: "${ev.title}_$sMillis"
                val evNumId = stableIdFromString(evIdentifier)
                val calId = ev.calendar?.calendarIdentifier?.let { stableIdFromString(it) } ?: 1L
                val evTitle = ev.title?.ifEmpty { untitledText } ?: untitledText

                result.add(
                    Udalost(
                        instanceId = sMillis,
                        eventId = evNumId,
                        title = evTitle,
                        beginTime = sMillis,
                        calendarId = calId,
                        rrule = formatRruleFromEkEvent(ev),
                        allDay = ev.allDay,
                        allDayString = allDayText,
                        platformId = evIdentifier
                    )
                )
            }
            return result.sortedWith(compareBy<Udalost> { !it.allDay }.thenBy { it.beginTime })
        }

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

    private fun findEkEventInstance(existingEvent: Udalost): EKEvent? {
        val startWindow = NSDate.dateWithTimeIntervalSince1970((existingEvent.beginTime - 3600_000L) / 1000.0)
        val endWindow = NSDate.dateWithTimeIntervalSince1970((existingEvent.beginTime + 3600_000L) / 1000.0)
        val predicate = eventStore.predicateForEventsWithStartDate(
            startDate = startWindow,
            endDate = endWindow,
            calendars = null
        )
        val candidates = eventStore.eventsMatchingPredicate(predicate).mapNotNull { it as? EKEvent }
        val byIdAndTime = candidates.firstOrNull { ev ->
            val idMatches = ev.eventIdentifier == existingEvent.platformId ||
                    (ev.eventIdentifier != null && stableIdFromString(ev.eventIdentifier!!) == existingEvent.eventId)
            val sMillis = ((ev.startDate?.timeIntervalSince1970 ?: 0.0) * 1000.0).toLong()
            idMatches && abs(sMillis - existingEvent.beginTime) < 60_000L
        }
        if (byIdAndTime != null) return byIdAndTime
        return eventStore.eventWithIdentifier(existingEvent.platformId)
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
        val ekCalendars = getSystemEkCalendars()
        if (ekCalendars.isNotEmpty()) {
            val newStartMillis = createMillis(year, monthZeroBased, day, hour, minute)
            val newEndMillis = newStartMillis + durationMinutes * 60_000L

            val chosenEkCal = ekCalendars.firstOrNull {
                (it.calendarIdentifier == calendar.platformId || stableIdFromString(it.calendarIdentifier) == calendar.id) &&
                        it.allowsContentModifications
            } ?: eventStore.defaultCalendarForNewEvents
                ?: ekCalendars.firstOrNull { it.allowsContentModifications }
                ?: return false

            val targetEvent = if (existingEvent != null) {
                findEkEventInstance(existingEvent) ?: EKEvent.eventWithEventStore(eventStore)
            } else {
                EKEvent.eventWithEventStore(eventStore)
            }

            targetEvent.title = title
            targetEvent.calendar = chosenEkCal
            targetEvent.allDay = false
            targetEvent.startDate = NSDate.dateWithTimeIntervalSince1970(newStartMillis / 1000.0)
            targetEvent.endDate = NSDate.dateWithTimeIntervalSince1970(newEndMillis / 1000.0)

            // Nastavení upozornění (EKAlarm)
            targetEvent.alarms?.mapNotNull { it as? EKAlarm }?.forEach { oldAlarm ->
                targetEvent.removeAlarm(oldAlarm)
            }
            if (alertMinutes != -1) {
                val alarm = EKAlarm.alarmWithRelativeOffset(-alertMinutes * 60.0)
                targetEvent.addAlarm(alarm)
            }

            // Nastavení opakování (EKRecurrenceRule)
            if (existingEvent == null || editThisAndFuture) {
                targetEvent.recurrenceRules?.mapNotNull { it as? EKRecurrenceRule }?.forEach { oldRule ->
                    targetEvent.removeRecurrenceRule(oldRule)
                }
                val newRule = buildEkRecurrenceRule(rrule)
                if (newRule != null) {
                    targetEvent.addRecurrenceRule(newRule)
                }
            }

            val span = if (existingEvent != null && !existingEvent.rrule.isNullOrEmpty() && !editThisAndFuture) {
                EKSpanThisEvent
            } else {
                EKSpanFutureEvents
            }

            val saved = eventStore.saveEvent(targetEvent, span = span, commit = true, error = null)
            if (saved && isExpertMode && existingEvent == null) {
                val cur = loadSettings()
                saveSettings(cur.copy(lastExpertCalendarId = stableIdFromString(chosenEkCal.calendarIdentifier)))
            }
            return saved
        }

        // Záložní lokální úložiště (pokud není povolen přístup k systémovému kalendáři)
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
        val ekCalendars = getSystemEkCalendars()
        if (ekCalendars.isNotEmpty()) {
            val target = findEkEventInstance(event) ?: return false
            val span = if (!event.rrule.isNullOrEmpty() && !thisAndFuture) {
                EKSpanThisEvent
            } else {
                EKSpanFutureEvents
            }
            return eventStore.removeEvent(target, span = span, commit = true, error = null)
        }

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
        if (!hasContactsAccess()) return false
        val jmena = nameDayString.split(Regex("(?i)\\s*(?:/|,|\\ba\\b|\\bi\\b|\\bund\\b|\\band\\b)\\s*"))
        val keys = listOf(CNContactGivenNameKey, CNContactFamilyNameKey)

        for (jmeno in jmena) {
            val cisteJmeno = jmeno.trim()
            if (cisteJmeno.isEmpty() ||
                cisteJmeno.contains("Svátek", ignoreCase = true) ||
                cisteJmeno.contains("Nový rok", ignoreCase = true) ||
                cisteJmeno.contains("Státní", ignoreCase = true)
            ) {
                continue
            }
            try {
                val predicate = CNContact.predicateForContactsMatchingName(cisteJmeno)
                val matches = contactStore.unifiedContactsMatchingPredicate(
                    predicate = predicate,
                    keysToFetch = keys,
                    error = null
                )
                if (matches != null && matches.isNotEmpty()) {
                    return true
                }
            } catch (e: Exception) {
                // Tichá pojistka
            }
        }
        return false
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
