package cz.mares.wavecal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App() {
    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
    val strings = remember { Localization.getStrings(CalendarBridge.getSystemLanguageCode()) }
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
                .systemBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Horní hlavička s vybraným datem a svátkem
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = CalendarBridge.formatLongDate(selectedYear, selectedMonth, selectedDay),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (selectedDayNameDay.isNotEmpty()) {
                            Text(
                                text = selectedDayNameDay,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isNameDayInContacts) {
                                    if (isDark) Color(0xFFFFEA00) else Color(0xFFD99B00)
                                } else {
                                    Color(0xFFFF4444)
                                },
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    // Seznam událostí pro vybraný den (horní část obrazovky)
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
                                        .padding(vertical = 12.dp, horizontal = 8.dp)
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

                        SmallFloatingActionButton(
                            onClick = {
                                dialogInitYear = selectedYear
                                dialogInitMonth = selectedMonth
                                dialogInitDay = selectedDay
                                eventToEdit = null
                                editThisAndFuture = false
                                showAddEditDialog = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "+"
                            )
                        }
                    }

                    // Lišta pro přepínání měsíců a nastavení
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
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
                            }
                        ) {
                            Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        val monthTitle = "${strings.monthNames.getOrElse(cursorMonth) { "" }} $cursorYear"
                        Text(
                            text = monthTitle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showMonthYearDialog = true }
                                .padding(vertical = 8.dp)
                        )

                        IconButton(
                            onClick = { showMenuDialog = true },
                            modifier = Modifier.size(48.dp)
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
                            }
                        ) {
                            Text(">", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Mřížka kalendáře (spodní část obrazovky, weight 1.5f)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.5f)
                    ) {
                        // Hlavičky dnů v týdnu (Po - Ne)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            strings.shortWeekdays.forEach { dayHeader ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayHeader,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color.LightGray else Color.DarkGray
                                    )
                                }
                            }
                        }

                        // Jednotlivé týdny
                        val rows = cells.chunked(7)
                        rows.forEach { week ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                            .padding(2.dp)
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
                                                    fontSize = 16.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) {
                                                        if (isDark) Color.Black else Color.White
                                                    } else {
                                                        if (isDark) Color.White else Color.Black
                                                    }
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
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
                        }
                    }
                }

                // Plovoucí zpráva (Toast náhrada)
                if (snackMessage != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
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

            // 2. Dialog výběru měsíce a roku
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
    var selMonth by remember { mutableStateOf(initialMonth) }
    var selYear by remember { mutableStateOf(initialYear) }

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
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Výběr měsíce
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = {
                            if (selMonth == 0) {
                                selMonth = 11
                                if (selYear > 1900) selYear--
                            } else {
                                selMonth--
                            }
                        }) { Text("▲") }

                        Text(
                            text = monthNames.getOrElse(selMonth) { "" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )

                        OutlinedButton(onClick = {
                            if (selMonth == 11) {
                                selMonth = 0
                                if (selYear < 2100) selYear++
                            } else {
                                selMonth++
                            }
                        }) { Text("▼") }
                    }

                    // Výběr roku
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { if (selYear > 1900) selYear-- }) { Text("▲") }
                        Text(
                            text = selYear.toString(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        OutlinedButton(onClick = { if (selYear < 2100) selYear++ }) { Text("▼") }
                    }
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
    var y by remember { mutableStateOf(initialYear) }
    var m by remember { mutableStateOf(initialMonth) }
    var d by remember { mutableStateOf(initialDay) }

    val maxDays = CalendarBridge.getDaysInMonth(y, m)
    if (d > maxDays) d = maxDays

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
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { d = if (d >= maxDays) 1 else d + 1 }) { Text("▲") }
                        Text(d.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { d = if (d <= 1) maxDays else d - 1 }) { Text("▼") }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { m = (m + 1) % 12 }) { Text("▲") }
                        Text(monthNames.getOrElse(m) { "" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { m = if (m <= 0) 11 else m - 1 }) { Text("▼") }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { if (y < 2100) y++ }) { Text("▲") }
                        Text(y.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { if (y > 1900) y-- }) { Text("▼") }
                    }
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
    var hour by remember { mutableStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember { mutableStateOf(((initialMinute / 5) * 5).coerceIn(0, 55)) }

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
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { hour = (hour + 1) % 24 }) { Text("▲") }
                        Text(
                            text = hour.toString().padStart(2, '0'),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                        OutlinedButton(onClick = { hour = if (hour <= 0) 23 else hour - 1 }) { Text("▼") }
                    }

                    Text(
                        text = ":",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { minute = (minute + 5) % 60 }) { Text("▲") }
                        Text(
                            text = minute.toString().padStart(2, '0'),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                        OutlinedButton(onClick = { minute = if (minute < 5) 55 else minute - 5 }) { Text("▼") }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(cancelText) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { onConfirm(hour, minute) }, modifier = Modifier.weight(1f)) { Text(okText) }
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
