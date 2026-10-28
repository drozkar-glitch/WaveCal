package cz.mares.wavecal

expect object CalendarBridge {
    fun getSystemLanguageCode(): String
    fun setPermissionResultListener(listener: () -> Unit)
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
    fun getAvailableCalendars(): List<MujKalendar>
    fun getDaysWithEvents(year: Int, monthZeroBased: Int): Set<Int>
    fun getEventsForDay(year: Int, monthZeroBased: Int, day: Int, untitledText: String, allDayText: String): List<Udalost>
    fun saveOrUpdateEvent(
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
    ): Boolean
    fun deleteEvent(event: Udalost, thisAndFuture: Boolean): Boolean
    fun copyToClipboard(label: String, text: String)
    fun contactMatchesNameDay(nameDayString: String): Boolean
    fun getTodayParts(): DateTimeParts
    fun extractDateTime(timeMillis: Long): DateTimeParts
    fun getDaysInMonth(year: Int, monthZeroBased: Int): Int
    fun getFirstDayOfWeekOffsetMondayBased(year: Int, monthZeroBased: Int): Int
    fun formatLongDate(year: Int, monthZeroBased: Int, day: Int): String
    fun formatMediumDate(year: Int, monthZeroBased: Int, day: Int): String
}
