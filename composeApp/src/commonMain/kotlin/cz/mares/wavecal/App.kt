package cz.mares.wavecal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App() {
    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
    val langCode = remember { CalendarBridge.getSystemLanguageCode() }
    val strings = remember { Localization.getStrings(langCode) }

    val today = remember { CalendarBridge.getTodayParts() }
    var cursorYear by remember { mutableStateOf(today.year) }
    var cursorMonth by remember { mutableStateOf(today.monthZeroBased) }

    var selectedYear by remember { mutableStateOf(today.year) }
    var selectedMonth by remember { mutableStateOf(today.monthZeroBased) }
    var selectedDay by remember { mutableStateOf(today.day) }

    var refreshTrigger by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(CalendarBridge.loadSettings()) }

    DisposableEffect(Unit) {
        CalendarBridge.setPermissionResultListener {
            settings = CalendarBridge.loadSettings()
            refreshTrigger++
        }
        onDispose {}
    }

    val daysWithEvents = remember(cursorYear, cursorMonth, refreshTrigger, settings) {
        CalendarBridge.getDaysWithEvents(cursorYear, cursorMonth)
    }

    val dayEvents = remember(selectedYear, selectedMonth, selectedDay, refreshTrigger, settings) {
        CalendarBridge.getEventsForDay(
            selectedYear,
            selectedMonth,
            selectedDay,
            strings.eventUntitled,
            strings.eventAllDay
        )
    }

    val selectedDayNameDay = remember(selectedMonth, selectedDay, settings.selectedCountry) {
        NameDaysData.getNameDay(settings.selectedCountry, selectedMonth, selectedDay)
    }

    val isNameDayInContacts = remember(selectedDayNameDay, refreshTrigger) {
        if (selectedDayNameDay.isEmpty()) false else CalendarBridge.contactMatchesNameDay(selectedDayNameDay)
    }

    val selectedWeekNum = remember(selectedYear, selectedMonth, selectedDay) {
        getIsoWeekNumber(selectedYear, selectedMonth, selectedDay)
    }

    var showMenuDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCalendarsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showMonthYearDialog by remember { mutableStateOf(false) }

    var activeEventForActions by remember { mutableStateOf<Udalost?>(null) }
    var pendingRepeatAction by remember { mutableStateOf<Pair<Udalost, String>?>(null) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<Udalost?>(null) }
    var editThisAndFuture by remember { mutableStateOf(false) }
    var dialogInitYear by remember { mutableStateOf(selectedYear) }
    var dialogInitMonth by remember { mutableStateOf(selectedMonth) }
    var dialogInitDay by remember { mutableStateOf(selectedDay) }

    var snackMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun showToast(msg: String) {
        snackMessage = msg
        scope.launch {
            delay(2200)
            if (snackMessage == msg) snackMessage = null
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    // Horní hlavička s vybraným datem, svátkem a číslem týdne
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = CalendarBridge.formatLongDate(selectedYear, selectedMonth, selectedDay),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (selectedDayNameDay.isNotEmpty()) {
                                Text(
                                    text = selectedDayNameDay,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isNameDayInContacts) {
                                        if (isDark) Color(0xFFFFEA00) else Color(0xFFD99B00)
                                    } else {
                                        Color(0xFFFF4444)
                                    }
                                )
                                Text(
                                    text = "  •  ",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                text = formatWeekLabel(langCode, selectedWeekNum),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
                    )

                    // Seznam událostí pro vybraný den (zabírá volný prostor nahoře)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dayEvents) { udalost ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { activeEventForActions = udalost },
                                            onLongClick = { activeEventForActions = udalost }
                                        )
                                        .padding(vertical = 10.dp, horizontal = 6.dp)
                                ) {
                                    Text(
                                        text = udalost.formatRadek(),
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )

                    // Mřížka kalendáře s pevnou výškou na 6 řádků (nic neposkakuje)
                    val firstDayOffset = CalendarBridge.getFirstDayOfWeekOffsetMondayBased(cursorYear, cursorMonth)
                    val maxDays = CalendarBridge.getDaysInMonth(cursorYear, cursorMonth)
                    val cells = remember(cursorYear, cursorMonth, firstDayOffset, maxDays) {
                        buildList {
                            for (i in 0 until firstDayOffset) add(null)
                            for (d in 1..maxDays) add(d)
                            while (size % 7 != 0) add(null)
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Hlavičky dnů v týdnu (prázdný roh + Po - Ne)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.width(26.dp))
                            strings.shortWeekdays.forEach { dayHeader ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayHeader,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color.LightGray else Color.DarkGray
                                    )
                                }
                            }
                        }

                        // Vždy přesně 6 řádků týdnů pro stabilní výšku mřížky
                        val rows = cells.chunked(7)
                        for (rowIndex in 0 until 6) {
                            val week = rows.getOrNull(rowIndex)
                            if (week != null) {
                                val firstValidDay = week.firstOrNull { it != null } ?: 1
                                val rowWeekNum = getIsoWeekNumber(cursorYear, cursorMonth, firstValidDay)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Číslo týdne na začátku řádku
                                    Box(
                                        modifier = Modifier
                                            .width(26.dp)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = rowWeekNum.toString(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF888888)
                                        )
                                    }

                                    week.forEach { dayNum ->
                                        val isSelected = dayNum != null &&
                                                dayNum == selectedDay &&
                                                cursorMonth == selectedMonth &&
                                                cursorYear == selectedYear
                                        val hasEvents = dayNum != null && daysWithEvents.contains(dayNum)
                                        val selectedBgColor = if (isDark) Color(0xFFFFD500) else Color(0xFF009B77)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .padding(1.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) selectedBgColor else Color.Transparent)
                                                .then(
                                                    if (dayNum != null) {
                                                        Modifier.combinedClickable(
                                                            onClick = {
                                                                selectedYear = cursorYear
                                                                selectedMonth = cursorMonth
                                                                selectedDay = dayNum
                                                            },
                                                            onLongClick = {
                                                                selectedYear = cursorYear
                                                                selectedMonth = cursorMonth
                                                                selectedDay = dayNum
                                                                dialogInitYear = cursorYear
                                                                dialogInitMonth = cursorMonth
                                                                dialogInitDay = dayNum
                                                                eventToEdit = null
                                                                editThisAndFuture = false
                                                                showAddEditDialog = true
                                                            }
                                                        )
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (dayNum != null) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Text(
                                                        text = dayNum.toString(),
                                                        fontSize = 15.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) {
                                                            if (isDark) Color.Black else Color.White
                                                        } else {
                                                            if (isDark) Color.White else Color.Black
                                                        }
                                                    )
                                                    Spacer(modifier = Modifier.height(1.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (hasEvents) Color(0xFFFF4444) else Color.Transparent
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Prázdný 6. řádek u kratších měsíců, aby kalendář nikdy neposkočil
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                )
                            }
                        }
                    }

                    // Spodní ovládací lišta pevně ukotvená úplně dole
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (cursorMonth == 0) {
                                    cursorMonth = 11
                                    cursorYear--
                                } else {
                                    cursorMonth--
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("<", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }

                        val monthTitle = "${strings.monthNames.getOrElse(cursorMonth) { "" }} $cursorYear"
                        Text(
                            text = monthTitle,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showMonthYearDialog = true }
                                .padding(vertical = 6.dp)
                        )

                        IconButton(
                            onClick = {
                                dialogInitYear = selectedYear
                                dialogInitMonth = selectedMonth
                                dialogInitDay = selectedDay
                                eventToEdit = null
                                editThisAndFuture = false
                                showAddEditDialog = true
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "+",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = { showMenuDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = strings.menuSettings,
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Button(
                            onClick = {
                                if (cursorMonth == 11) {
                                    cursorMonth = 0
                                    cursorYear++
                                } else {
                                    cursorMonth++
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(">", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Plovoucí zpráva (Toast náhrada)
                if (snackMessage != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 64.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDark) Color(0xFF383838) else Color(0xFF222222),
                        tonalElevation = 6.dp
                    ) {
                        Text(
                            text = snackMessage ?: "",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 1. Zakulacené Menu
            if (showMenuDialog) {
                Dialog(onDismissRequest = { showMenuDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDark) Color(0xFF2C2C2C) else Color(0xFFF5F5F5)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = strings.menuTitle,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                            Button(
                                onClick = {
                                    showMenuDialog = false
                                    showSettingsDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.menuSettings)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    showMenuDialog = false
                                    showCalendarsDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.menuCalendars)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    showMenuDialog = false
                                    showAboutDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.menuAbout)
                            }
                        }
                    }
                }
            }

            // 2. Dialog výběru měsíce a roku (s točícími kolečky)
            if (showMonthYearDialog) {
                MonthYearPickerDialog(
                    initialMonth = cursorMonth,
                    initialYear = cursorYear,
                    monthNames = strings.monthNames,
                    cancelText = strings.btnCancel,
                    okText = strings.btnOk,
                    isDark = isDark,
                    onDismiss = { showMonthYearDialog = false },
                    onConfirm = { newMonth, newYear ->
                        cursorMonth = newMonth
                        cursorYear = newYear
                        showMonthYearDialog = false
                    }
                )
            }

            // 3. Dialog akcí s událostí (Upravit / Smazat / Kopírovat)
            val currentActionEvent = activeEventForActions
            if (currentActionEvent != null) {
                Dialog(onDismissRequest = { activeEventForActions = null }) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDark) Color(0xFF2C2C2C) else Color.White
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Text(
                                text = currentActionEvent.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            TextButton(
                                onClick = {
                                    activeEventForActions = null
                                    if (!currentActionEvent.rrule.isNullOrEmpty()) {
                                        pendingRepeatAction = currentActionEvent to "upravit"
                                    } else {
                                        val dt = CalendarBridge.extractDateTime(currentActionEvent.beginTime)
                                        dialogInitYear = dt.year
                                        dialogInitMonth = dt.monthZeroBased
                                        dialogInitDay = dt.day
                                        eventToEdit = currentActionEvent
                                        editThisAndFuture = true
                                        showAddEditDialog = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.actionEdit, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(
                                onClick = {
                                    activeEventForActions = null
                                    if (!currentActionEvent.rrule.isNullOrEmpty()) {
                                        pendingRepeatAction = currentActionEvent to "smazat"
                                    } else {
                                        if (CalendarBridge.deleteEvent(currentActionEvent, true)) {
                                            showToast(strings.toastEventDeleted)
                                            refreshTrigger++
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.actionDelete, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(
                                onClick = {
                                    activeEventForActions = null
                                    CalendarBridge.copyToClipboard(strings.appName, currentActionEvent.title)
                                    showToast(strings.toastCopied)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.actionCopy, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            // 4. Dialog dotazu na opakující se událost (Pouze tuto / Tato a budoucí)
            val repeatPair = pendingRepeatAction
            if (repeatPair != null) {
                val (udalost, akce) = repeatPair
                AlertDialog(
                    onDismissRequest = { pendingRepeatAction = null },
                    title = { Text(strings.dialogRepeatTitle, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(if (akce == "smazat") strings.dialogRepeatMsgDelete else strings.dialogRepeatMsgEdit)
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingRepeatAction = null
                                if (akce == "smazat") {
                                    if (CalendarBridge.deleteEvent(udalost, false)) {
                                        showToast(strings.toastEventDeleted)
                                        refreshTrigger++
                                    }
                                } else {
                                    val dt = CalendarBridge.extractDateTime(udalost.beginTime)
                                    dialogInitYear = dt.year
                                    dialogInitMonth = dt.monthZeroBased
                                    dialogInitDay = dt.day
                                    eventToEdit = udalost
                                    editThisAndFuture = false
                                    showAddEditDialog = true
                                }
                            }
                        ) {
                            Text(strings.dialogOnlyThis)
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(
                                onClick = {
                                    pendingRepeatAction = null
                                    if (akce == "smazat") {
                                        if (CalendarBridge.deleteEvent(udalost, true)) {
                                            showToast(strings.toastEventDeleted)
                                            refreshTrigger++
                                        }
                                    } else {
                                        val dt = CalendarBridge.extractDateTime(udalost.beginTime)
                                        dialogInitYear = dt.year
                                        dialogInitMonth = dt.monthZeroBased
                                        dialogInitDay = dt.day
                                        eventToEdit = udalost
                                        editThisAndFuture = true
                                        showAddEditDialog = true
                                    }
                                }
                            ) {
                                Text(strings.dialogThisAndFuture)
                            }
                            TextButton(onClick = { pendingRepeatAction = null }) {
                                Text(strings.btnCancel)
                            }
                        }
                    }
                )
            }

            // 5. Dialog Nastavení aplikace
            if (showSettingsDialog) {
                SettingsDialog(
                    currentSettings = settings,
                    calendars = CalendarBridge.getAvailableCalendars(),
                    strings = strings,
                    isDark = isDark,
                    onDismiss = { showSettingsDialog = false },
                    onSave = { newSettings ->
                        CalendarBridge.saveSettings(newSettings)
                        settings = newSettings
                        refreshTrigger++
                        showSettingsDialog = false
                        showToast(strings.toastSettingsSaved)
                    }
                )
            }

            // 6. Dialog Zobrazované kalendáře
            if (showCalendarsDialog) {
                VisibleCalendarsDialog(
                    currentSettings = settings,
                    calendars = CalendarBridge.getAvailableCalendars(),
                    strings = strings,
                    isDark = isDark,
                    onDismiss = { showCalendarsDialog = false },
                    onSave = { selectedIds ->
                        val updated = settings.copy(visibleCalendarIds = selectedIds)
                        CalendarBridge.saveSettings(updated)
                        settings = updated
                        refreshTrigger++
                        showCalendarsDialog = false
                    }
                )
            }

            // 7. Dialog O aplikaci
            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(strings.menuAbout, fontWeight = FontWeight.Bold) },
                    text = { Text(strings.aboutText) },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text(strings.btnOk)
                        }
                    }
                )
            }

            // 8. Dialog Přidat / Upravit událost
            if (showAddEditDialog) {
                AddEditEventDialog(
                    year = dialogInitYear,
                    monthZeroBased = dialogInitMonth,
                    day = dialogInitDay,
                    udalostKUprave = eventToEdit,
                    upravitTutoABudouci = editThisAndFuture,
                    settings = settings,
                    calendars = CalendarBridge.getAvailableCalendars(),
                    strings = strings,
                    isDark = isDark,
                    onDismiss = { showAddEditDialog = false },
                    onEmptyTitleError = { showToast(strings.toastEnterName) },
                    onSaved = {
                        showAddEditDialog = false
                        settings = CalendarBridge.loadSettings()
                        refreshTrigger++
                        showToast(strings.toastSaved)
                    }
                )
            }
        }
    }
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

private fun civilYearFromDays(zRaw: Long): Int {
    val z = zRaw + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    val finalYear = if (m <= 2L) y + 1L else y
    return finalYear.toInt()
}

private fun getIsoWeekNumber(year: Int, monthZeroBased: Int, day: Int): Int {
    val epochDays = daysFromCivil(year, monthZeroBased + 1, day)
    val dowMondayBased = (((epochDays + 3L) % 7L) + 7L).toInt() % 7
    val thursdayEpochDays = epochDays + (3 - dowMondayBased)
    val thursdayYear = civilYearFromDays(thursdayEpochDays)
    val jan1ThursdayYear = daysFromCivil(thursdayYear, 1, 1)
    val dayOfYearZeroBased = (thursdayEpochDays - jan1ThursdayYear).toInt()
    return (dayOfYearZeroBased / 7) + 1
}

private fun formatWeekLabel(langCode: String, weekNum: Int): String {
    val isEven = (weekNum % 2 == 0)
    return when (langCode.lowercase().take(2)) {
        "cs" -> "$weekNum. týden (${if (isEven) "sudý" else "lichý"})"
        "sk" -> "$weekNum. týždeň (${if (isEven) "párny" else "nepárny"})"
        "pl" -> "$weekNum. tydzień (${if (isEven) "parzysty" else "nieparzysty"})"
        "de" -> "KW $weekNum (${if (isEven) "gerade" else "ungerade"})"
        else -> "Week $weekNum (${if (isEven) "even" else "odd"})"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChanged: (oldIndex: Int, newIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    wrapSelectorWheel: Boolean = true,
    itemHeight: Dp = 42.dp
) {
    if (items.isEmpty()) return
    val count = items.size
    val safeSelected = selectedIndex.coerceIn(0, count - 1)
    val virtualCount = if (wrapSelectorWheel && count > 1) count * 1000 else count
    val middleBase = if (wrapSelectorWheel && count > 1) {
        val half = virtualCount / 2
        half - (half % count)
    } else 0

    val initialListIndex = middleBase + safeSelected
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialListIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()

    var lastReportedActualIndex by remember { mutableStateOf(safeSelected) }

    val centeredVirtualIndex by remember(listState, count, wrapSelectorWheel) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                initialListIndex
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val closest = visible.minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                closest?.index ?: initialListIndex
            }
        }
    }

    LaunchedEffect(centeredVirtualIndex) {
        val actualIdx = ((centeredVirtualIndex % count) + count) % count
        if (actualIdx != lastReportedActualIndex) {
            val prev = lastReportedActualIndex
            lastReportedActualIndex = actualIdx
            onSelectedIndexChanged(prev, actualIdx)
        }
    }

    LaunchedEffect(safeSelected) {
        if (!listState.isScrollInProgress && safeSelected != lastReportedActualIndex) {
            lastReportedActualIndex = safeSelected
            val targetVirtual = if (wrapSelectorWheel && count > 1) {
                val currentBase = centeredVirtualIndex - ((centeredVirtualIndex % count + count) % count)
                currentBase + safeSelected
            } else {
                safeSelected
            }
            listState.animateScrollToItem(targetVirtual)
        }
    }

    val dividerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    Box(
        modifier = modifier.height(itemHeight * 3),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(virtualCount) { virtualIdx ->
                val actualIdx = ((virtualIdx % count) + count) % count
                val isCenter = (virtualIdx == centeredVirtualIndex)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(virtualIdx)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[actualIdx],
                        fontSize = if (isCenter) 20.sp else 15.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (isCenter) 1f else 0.42f)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            HorizontalDivider(thickness = 1.5.dp, color = dividerColor)
            HorizontalDivider(thickness = 1.5.dp, color = dividerColor)
        }
    }
}

@Composable
private fun MonthYearPickerDialog(
    initialMonth: Int,
    initialYear: Int,
    monthNames: List<String>,
    cancelText: String,
    okText: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val years = remember { (1900..2100).map { it.toString() } }
    var selMonth by remember { mutableStateOf(initialMonth.coerceIn(0, 11)) }
    var selYear by remember { mutableStateOf(initialYear.coerceIn(1900, 2100)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF2C2C2C) else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${monthNames.getOrElse(selMonth) { "" }} $selYear",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        items = monthNames,
                        selectedIndex = selMonth,
                        wrapSelectorWheel = true,
                        modifier = Modifier.weight(1.3f),
                        onSelectedIndexChanged = { oldVal, newVal ->
                            selMonth = newVal
                            if (oldVal >= 10 && newVal <= 1) {
                                if (selYear < 2100) selYear++
                            } else if (oldVal <= 1 && newVal >= 10) {
                                if (selYear > 1900) selYear--
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    WheelPicker(
                        items = years,
                        selectedIndex = (selYear - 1900).coerceIn(0, years.lastIndex),
                        wrapSelectorWheel = false,
                        modifier = Modifier.weight(1f),
                        onSelectedIndexChanged = { _, newIdx ->
                            selYear = 1900 + newIdx
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(cancelText)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(selMonth, selYear) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(okText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    currentSettings: AppSettings,
    calendars: List<MujKalendar>,
    strings: AppStrings,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    val countries = listOf(
        "none" to strings.countryNone,
        "cz" to "Česko (CZ)",
        "sk" to "Slovensko (SK)",
        "pl" to "Polska (PL)",
        "de" to "Deutschland (DE)",
        "en" to "English (EN)"
    )
    val durations = listOf(15, 30, 45, 60, 90, 120)
    val alerts = listOf(
        -1 to strings.alertNone,
        0 to strings.alert0,
        10 to strings.alert10,
        15 to strings.alert15,
        30 to strings.alert30,
        45 to strings.alert45,
        60 to strings.alert60
    )

    var selectedCalId by remember {
        val initial = currentSettings.defaultCalendarId
        val exists = calendars.any { it.id == initial }
        mutableStateOf(if (exists) initial else (calendars.firstOrNull()?.id ?: -1L))
    }
    var selectedCountry by remember { mutableStateOf(currentSettings.selectedCountry) }
    var expertMode by remember { mutableStateOf(currentSettings.expertMode) }
    var selectedDuration by remember { mutableStateOf(currentSettings.defaultDurationMinutes) }
    var selectedAlert by remember { mutableStateOf(currentSettings.defaultAlertMinutes) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF2C2C2C) else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = strings.settingsTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(strings.settingsDefaultCalendar, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelector(
                    items = calendars,
                    selectedItem = calendars.find { it.id == selectedCalId } ?: calendars.firstOrNull(),
                    itemLabel = { it.name },
                    onItemSelected = { selectedCalId = it.id }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(strings.settingsCountry, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelector(
                    items = countries,
                    selectedItem = countries.find { it.first == selectedCountry } ?: countries[1],
                    itemLabel = { it.second },
                    onItemSelected = { selectedCountry = it.first }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expertMode = !expertMode }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = expertMode,
                        onCheckedChange = { expertMode = it }
                    )
                    Text(
                        text = strings.settingsExpert,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(strings.settingsDuration, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelector(
                    items = durations,
                    selectedItem = durations.find { it == selectedDuration } ?: 15,
                    itemLabel = { "$it ${strings.textMinutes}" },
                    onItemSelected = { selectedDuration = it }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(strings.settingsAlert, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelector(
                    items = alerts,
                    selectedItem = alerts.find { it.first == selectedAlert } ?: alerts[3],
                    itemLabel = { it.second },
                    onItemSelected = { selectedAlert = it.first }
                )

                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.btnCancel)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            onSave(
                                currentSettings.copy(
                                    defaultCalendarId = selectedCalId,
                                    selectedCountry = selectedCountry,
                                    expertMode = expertMode,
                                    defaultDurationMinutes = selectedDuration,
                                    defaultAlertMinutes = selectedAlert
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.btnSave)
                    }
                }
            }
        }
    }
}

@Composable
private fun VisibleCalendarsDialog(
    currentSettings: AppSettings,
    calendars: List<MujKalendar>,
    strings: AppStrings,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val selectedSet = remember {
        val saved = currentSettings.visibleCalendarIds
        val initial = if (saved == null) {
            calendars.map { it.id.toString() }.toSet()
        } else {
            saved
        }
        mutableStateListOf<String>().apply { addAll(initial) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF2C2C2C) else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = strings.menuCalendars,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    calendars.forEach { cal ->
                        val idStr = cal.id.toString()
                        val isChecked = selectedSet.contains(idStr)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) selectedSet.remove(idStr) else selectedSet.add(idStr)
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (!selectedSet.contains(idStr)) selectedSet.add(idStr)
                                    } else {
                                        selectedSet.remove(idStr)
                                    }
                                }
                            )
                            Text(
                                text = cal.name,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.btnCancel)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onSave(selectedSet.toSet()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.btnSave)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddEditEventDialog(
    year: Int,
    monthZeroBased: Int,
    day: Int,
    udalostKUprave: Udalost?,
    upravitTutoABudouci: Boolean,
    settings: AppSettings,
    calendars: List<MujKalendar>,
    strings: AppStrings,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onEmptyTitleError: () -> Unit,
    onSaved: () -> Unit
) {
    val repeatOptions = listOf(
        null to strings.repeatNone,
        "FREQ=DAILY" to strings.repeatDaily,
        "FREQ=WEEKLY" to strings.repeatWeekly,
        "FREQ=WEEKLY;INTERVAL=2" to strings.repeat14Days,
        "FREQ=MONTHLY" to strings.repeatMonthly,
        "FREQ=YEARLY" to strings.repeatYearly
    )

    val initParts = remember(udalostKUprave) {
        if (udalostKUprave != null) {
            CalendarBridge.extractDateTime(udalostKUprave.beginTime)
        } else {
            DateTimeParts(year, monthZeroBased, day, 12, 0)
        }
    }

    var title by remember { mutableStateOf(udalostKUprave?.title ?: "") }
    var vybranyRok by remember { mutableStateOf(initParts.year) }
    var vybranyMesic by remember { mutableStateOf(initParts.monthZeroBased) }
    var vybranyDen by remember { mutableStateOf(initParts.day) }
    var vybranaHodina by remember { mutableStateOf(initParts.hour) }
    var vybranaMinuta by remember { mutableStateOf((initParts.minute / 5) * 5) }

    var selectedCalendar by remember {
        val targetId = when {
            udalostKUprave != null -> udalostKUprave.calendarId
            settings.expertMode && settings.lastExpertCalendarId != -1L -> settings.lastExpertCalendarId
            settings.defaultCalendarId != -1L -> settings.defaultCalendarId
            else -> calendars.firstOrNull()?.id ?: -1L
        }
        mutableStateOf(calendars.find { it.id == targetId } ?: calendars.firstOrNull())
    }

    var selectedRrule by remember {
        mutableStateOf(udalostKUprave?.rrule)
    }

    var showDateSelectModal by remember { mutableStateOf(false) }
    var showTimeSelectModal by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isDark) Color(0xFF252525) else MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                if (settings.expertMode) {
                    Text(
                        text = strings.labelCalendar,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DropdownSelector(
                        items = calendars,
                        selectedItem = selectedCalendar,
                        itemLabel = { it.name },
                        onItemSelected = { selectedCalendar = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = strings.labelEventName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(strings.hintEventName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.labelDate,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = { showDateSelectModal = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = CalendarBridge.formatMediumDate(vybranyRok, vybranyMesic, vybranyDen),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.labelTime,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = { showTimeSelectModal = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    val hh = vybranaHodina.toString().padStart(2, '0')
                    val mm = vybranaMinuta.toString().padStart(2, '0')
                    Text(
                        text = "$hh:$mm",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (settings.expertMode) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = strings.labelRepeat,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DropdownSelector(
                        items = repeatOptions,
                        selectedItem = repeatOptions.find { it.first == selectedRrule } ?: repeatOptions.first(),
                        itemLabel = { it.second },
                        onItemSelected = { selectedRrule = it.first }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(strings.btnCancel)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            val cleanTitle = title.trim()
                            if (cleanTitle.isEmpty()) {
                                onEmptyTitleError()
                                return@Button
                            }
                            val targetCal = selectedCalendar ?: return@Button
                            val ok = CalendarBridge.saveOrUpdateEvent(
                                existingEvent = udalostKUprave,
                                editThisAndFuture = upravitTutoABudouci,
                                calendar = targetCal,
                                title = cleanTitle,
                                year = vybranyRok,
                                monthZeroBased = vybranyMesic,
                                day = vybranyDen,
                                hour = vybranaHodina,
                                minute = vybranaMinuta,
                                durationMinutes = settings.defaultDurationMinutes,
                                alertMinutes = settings.defaultAlertMinutes,
                                rrule = selectedRrule,
                                isExpertMode = settings.expertMode
                            )
                            if (ok) onSaved()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.White
                        )
                    ) {
                        Text(strings.btnSave, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDateSelectModal) {
        SimpleDateSelectorDialog(
            initialYear = vybranyRok,
            initialMonth = vybranyMesic,
            initialDay = vybranyDen,
            monthNames = strings.monthNames,
            cancelText = strings.btnCancel,
            okText = strings.btnOk,
            isDark = isDark,
            onDismiss = { showDateSelectModal = false },
            onConfirm = { y, m, d ->
                vybranyRok = y
                vybranyMesic = m
                vybranyDen = d
                showDateSelectModal = false
            }
        )
    }

    if (showTimeSelectModal) {
        StepTimePickerDialog(
            initialHour = vybranaHodina,
            initialMinute = vybranaMinuta,
            titleText = strings.dialogSelectTime,
            cancelText = strings.btnCancel,
            okText = strings.btnOk,
            isDark = isDark,
            onDismiss = { showTimeSelectModal = false },
            onConfirm = { h, m ->
                vybranaHodina = h
                vybranaMinuta = m
                showTimeSelectModal = false
            }
        )
    }
}

@Composable
private fun SimpleDateSelectorDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    monthNames: List<String>,
    cancelText: String,
    okText: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int) -> Unit
) {
    val years = remember { (1900..2100).map { it.toString() } }
    var y by remember { mutableStateOf(initialYear.coerceIn(1900, 2100)) }
    var m by remember { mutableStateOf(initialMonth.coerceIn(0, 11)) }
    var d by remember { mutableStateOf(initialDay.coerceAtLeast(1)) }
    val maxDays = CalendarBridge.getDaysInMonth(y, m)
    if (d > maxDays) d = maxDays
    val days = remember(maxDays) { (1..maxDays).map { "$it." } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF2C2C2C) else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = CalendarBridge.formatMediumDate(y, m, d),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        items = days,
                        selectedIndex = (d - 1).coerceIn(0, days.lastIndex),
                        wrapSelectorWheel = true,
                        modifier = Modifier.weight(0.8f),
                        onSelectedIndexChanged = { _, newIdx ->
                            d = newIdx + 1
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    WheelPicker(
                        items = monthNames,
                        selectedIndex = m,
                        wrapSelectorWheel = true,
                        modifier = Modifier.weight(1.4f),
                        onSelectedIndexChanged = { oldVal, newVal ->
                            m = newVal
                            if (oldVal >= 10 && newVal <= 1) {
                                if (y < 2100) y++
                            } else if (oldVal <= 1 && newVal >= 10) {
                                if (y > 1900) y--
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    WheelPicker(
                        items = years,
                        selectedIndex = (y - 1900).coerceIn(0, years.lastIndex),
                        wrapSelectorWheel = false,
                        modifier = Modifier.weight(1f),
                        onSelectedIndexChanged = { _, newIdx ->
                            y = 1900 + newIdx
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(cancelText) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { onConfirm(y, m, d) }, modifier = Modifier.weight(1f)) { Text(okText) }
                }
            }
        }
    }
}

@Composable
private fun StepTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    titleText: String,
    cancelText: String,
    okText: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val hours = remember { (0..23).map { it.toString().padStart(2, '0') } }
    val minutes = remember { (0..11).map { (it * 5).toString().padStart(2, '0') } }

    var hour by remember { mutableStateOf(initialHour.coerceIn(0, 23)) }
    var minuteStepIndex by remember { mutableStateOf((initialMinute / 5).coerceIn(0, 11)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF2C2C2C) else Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = titleText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WheelPicker(
                        items = hours,
                        selectedIndex = hour,
                        wrapSelectorWheel = true,
                        modifier = Modifier.width(90.dp),
                        onSelectedIndexChanged = { _, newIdx ->
                            hour = newIdx
                        }
                    )
                    Text(
                        text = ":",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    WheelPicker(
                        items = minutes,
                        selectedIndex = minuteStepIndex,
                        wrapSelectorWheel = true,
                        modifier = Modifier.width(90.dp),
                        onSelectedIndexChanged = { _, newIdx ->
                            minuteStepIndex = newIdx
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(cancelText) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(hour, minuteStepIndex * 5) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(okText)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> DropdownSelector(
    items: List<T>,
    selectedItem: T?,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable { if (items.isNotEmpty()) expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (selectedItem != null) itemLabel(selectedItem) else "-",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("▼", fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
