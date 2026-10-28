package cz.mares.wavecal

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class MujKalendar(val id: Long, val name: String) {
    override fun toString(): String = name
}

data class Udalost(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val beginTime: Long,
    val calendarId: Long,
    val rrule: String?,
    val allDay: Boolean,
    val allDayString: String
) {
    override fun toString(): String {
        if (allDay) return "$allDayString  -  $title"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${fmt.format(beginTime)}  -  $title"
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvSelectedDate: TextView
    private lateinit var lvAgenda: ListView
    private lateinit var gvCalendar: GridView
    private lateinit var tvMonthYear: TextView
    private lateinit var btnPrevMonth: Button
    private lateinit var btnNextMonth: Button
    private lateinit var btnSettings: View

    private var calendarCursor = Calendar.getInstance()
    private var selectedCalendar = Calendar.getInstance()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val readGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
            if (readGranted) obnovVsechno()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvSelectedDate = findViewById(R.id.tvSelectedDate)
        lvAgenda = findViewById(R.id.lvAgenda)
        gvCalendar = findViewById(R.id.gvCalendar)
        tvMonthYear = findViewById(R.id.tvMonthYear)
        btnPrevMonth = findViewById(R.id.btnPrevMonth)
        btnNextMonth = findViewById(R.id.btnNextMonth)
        btnSettings = findViewById(R.id.btnSettings)

        // Vlastní zakulacené menu místo roletky
        btnSettings.setOnClickListener {
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(60, 60, 60, 60)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 48f
                    setColor(if (isNightMode) Color.parseColor("#2C2C2C") else Color.parseColor("#F5F5F5"))
                }
            }

            val title = TextView(this).apply { text = getString(R.string.menu_title); textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 0, 0, 40) }
            val btn1 = Button(this).apply { text = getString(R.string.menu_settings); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val btn2 = Button(this).apply { text = getString(R.string.menu_calendars); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val btn3 = Button(this).apply { text = getString(R.string.menu_about); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

            layout.addView(title)
            layout.addView(btn1)
            layout.addView(btn2)
            layout.addView(btn3)

            val dialog = AlertDialog.Builder(this).setView(layout).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btn1.setOnClickListener { dialog.dismiss(); ukazNastaveniAplikace() }
            btn2.setOnClickListener { dialog.dismiss(); ukazVyberKalendaru() }
            btn3.setOnClickListener { dialog.dismiss(); ukazOAplikaci() }

            dialog.show()
        }

        btnPrevMonth.setOnClickListener {
            calendarCursor.add(Calendar.MONTH, -1)
            obnovKalendar()
        }

        btnNextMonth.setOnClickListener {
            calendarCursor.add(Calendar.MONTH, 1)
            obnovKalendar()
        }

        tvMonthYear.setOnClickListener {
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(50, 50, 50, 50)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 48f
                    setColor(if (isNightMode) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF"))
                }
            }

            val tvTitle = TextView(this).apply {
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }

            val pickerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val monthFormat = SimpleDateFormat("LLLL", Locale.getDefault())
            val tempCal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

            val nazvyMesicu = Array(12) { i ->
                tempCal.set(Calendar.MONTH, i)
                monthFormat.format(tempCal.time).replaceFirstChar { it.uppercase() }
            }

            val monthPicker = NumberPicker(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minValue = 0
                maxValue = 11
                displayedValues = nazvyMesicu
                value = calendarCursor.get(Calendar.MONTH)
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                wrapSelectorWheel = true
            }

            val yearPicker = NumberPicker(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minValue = 1900
                maxValue = 2100
                value = calendarCursor.get(Calendar.YEAR)
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            }

            val updateTitle = {
                tempCal.set(Calendar.YEAR, yearPicker.value)
                tempCal.set(Calendar.MONTH, monthPicker.value)
                val monthYearFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
                tvTitle.text = monthYearFormat.format(tempCal.time).replaceFirstChar { it.uppercase() }
            }

            updateTitle()

            monthPicker.setOnValueChangedListener { _, oldVal, newVal ->
                if (oldVal >= 10 && newVal <= 1) {
                    if (yearPicker.value < yearPicker.maxValue) yearPicker.value++
                } else if (oldVal <= 1 && newVal >= 10) {
                    if (yearPicker.value > yearPicker.minValue) yearPicker.value--
                }
                updateTitle()
            }

            yearPicker.setOnValueChangedListener { _, _, _ ->
                updateTitle()
            }

            pickerLayout.addView(monthPicker)
            pickerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(40, 1) })
            pickerLayout.addView(yearPicker)

            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 40, 0, 0) }
            val btnZrusit = Button(this).apply { text = getString(R.string.btn_cancel); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnUlozit = Button(this).apply { text = getString(android.R.string.ok); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }

            btnRow.addView(btnZrusit)
            btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })
            btnRow.addView(btnUlozit)

            mainLayout.addView(tvTitle)
            mainLayout.addView(pickerLayout)
            mainLayout.addView(btnRow)

            val dialog = AlertDialog.Builder(this).setView(mainLayout).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnZrusit.setOnClickListener { dialog.dismiss() }
            btnUlozit.setOnClickListener {
                calendarCursor.set(Calendar.DAY_OF_MONTH, 1)
                calendarCursor.set(Calendar.YEAR, yearPicker.value)
                calendarCursor.set(Calendar.MONTH, monthPicker.value)
                obnovKalendar()
                dialog.dismiss()
            }
            dialog.show()
        }

        gvCalendar.setOnItemClickListener { _, _, position, _ ->
            val text = gvCalendar.adapter.getItem(position) as String
            val day = text.toIntOrNull()
            if (day != null) {
                selectedCalendar.set(Calendar.YEAR, calendarCursor.get(Calendar.YEAR))
                selectedCalendar.set(Calendar.MONTH, calendarCursor.get(Calendar.MONTH))
                selectedCalendar.set(Calendar.DAY_OF_MONTH, day)
                obnovVsechno()
            }
        }

        gvCalendar.setOnItemLongClickListener { _, _, position, _ ->
            val text = gvCalendar.adapter.getItem(position) as String
            val day = text.toIntOrNull()
            if (day != null) {
                ukazDialogProNovouUdalost(
                    calendarCursor.get(Calendar.YEAR),
                    calendarCursor.get(Calendar.MONTH),
                    day, null, false
                )
                true
            } else false
        }

        lvAgenda.setOnItemLongClickListener { _, _, position, _ ->
            val udalost = lvAgenda.adapter.getItem(position) as Udalost
            val moznosti = arrayOf(getString(R.string.action_edit), getString(R.string.action_delete), getString(R.string.action_copy))

            val dialog = AlertDialog.Builder(this)
                .setTitle(udalost.title)
                .setItems(moznosti) { _, ktera ->
                    when (ktera) {
                        0 -> zpracujAkciSUdalosti(udalost, "upravit")
                        1 -> zpracujAkciSUdalosti(udalost, "smazat")
                        2 -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), udalost.title))
                            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.create()
            dialog.nastavZakulacenyVzhled(this)
            dialog.show()
            true
        }

        // OPRAVA 1: Doplněno oprávnění READ_CONTACTS nutné pro správnou funkci widgetu (žluté jméno svátku)
        val permissionsToRequest = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            obnovVsechno()
        }
    }

    // ZAKULACENÍ STANDARDNÍCH DIALOGŮ (Využití pozadí okna)
    private fun AlertDialog.nastavZakulacenyVzhled(context: Context) {
        val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor(if (isNightMode) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF"))
        }
        this.window?.setBackgroundDrawable(bg)
    }

    private fun aktualizujWidgety() {
        val intent = Intent(this, AgendaWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(ComponentName(application, AgendaWidget::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        sendBroadcast(intent)
        AppWidgetManager.getInstance(application).notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    private fun ziskejPovoleneKalendare(): String? {
        val prefs = getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val ulozeneId = prefs.getStringSet("zobrazene_kalendare", null)
        return if (ulozeneId != null && ulozeneId.isNotEmpty()) {
            "${CalendarContract.Instances.CALENDAR_ID} IN (${ulozeneId.joinToString(",")})"
        } else null
    }

    private fun ukazNastaveniAplikace() {
        val prefs = getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 48f; setColor(if (isNightMode) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF")) }
        }

        val tvTitle = TextView(this).apply { text = getString(R.string.settings_title); textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 40) }

        val dostupneKalendare = mutableListOf<MujKalendar>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) dostupneKalendare.add(MujKalendar(cursor.getLong(0), cursor.getString(1)))
            }
        }

        val tvDefaultCal = TextView(this).apply { text = getString(R.string.settings_default_calendar); setPadding(0, 0, 0, 10) }
        val spinnerDefaultCal = Spinner(this)
        val defaultCalAdapter = ArrayAdapter<MujKalendar>(this, android.R.layout.simple_spinner_dropdown_item, dostupneKalendare)
        spinnerDefaultCal.adapter = defaultCalAdapter

        val ulozenyDefaultCalId = prefs.getLong("default_calendar_id", -1L)
        val defaultCalIndex = dostupneKalendare.indexOfFirst { it.id == ulozenyDefaultCalId }
        if (defaultCalIndex != -1) spinnerDefaultCal.setSelection(defaultCalIndex)
        else if (dostupneKalendare.isNotEmpty()) spinnerDefaultCal.setSelection(0)

        val tvStat = TextView(this).apply { text = getString(R.string.settings_country); setPadding(0, 40, 0, 10) }
        val spinnerStat = Spinner(this)
        val staty = arrayOf(getString(R.string.country_none), "Česko (CZ)", "Slovensko (SK)", "Polska (PL)", "Deutschland (DE)", "English (EN)")
        val statyKody = arrayOf("none", "cz", "sk", "pl", "de", "en")
        spinnerStat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, staty)
        val aktualniStat = prefs.getString("vybrany_stat", "cz")
        spinnerStat.setSelection(statyKody.indexOf(aktualniStat).takeIf { it >= 0 } ?: 1)

        val cbExpert = CheckBox(this).apply {
            text = getString(R.string.settings_expert)
            isChecked = prefs.getBoolean("expert_mode", false)
            setPadding(0, 40, 0, 20)
        }

        val tvDelka = TextView(this).apply { text = getString(R.string.settings_duration); setPadding(0, 20, 0, 10) }
        val spinnerDelka = Spinner(this)
        val delky = arrayOf(15, 30, 45, 60, 90, 120)

        val delkyTexty = delky.map { "$it ${getString(R.string.text_minutes)}" }.toTypedArray()
        spinnerDelka.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, delkyTexty)
        val aktualniDelka = prefs.getInt("default_duration_minutes", 15)
        spinnerDelka.setSelection(delky.indexOf(aktualniDelka).takeIf { it >= 0 } ?: 0)

        val tvAlert = TextView(this).apply { text = getString(R.string.settings_alert); setPadding(0, 40, 0, 10) }
        val spinnerAlert = Spinner(this)
        val alerts = arrayOf(
            getString(R.string.alert_none),
            getString(R.string.alert_0),
            getString(R.string.alert_10),
            getString(R.string.alert_15),
            getString(R.string.alert_30),
            getString(R.string.alert_45),
            getString(R.string.alert_60)
        )
        val alertVals = arrayOf(-1, 0, 10, 15, 30, 45, 60)

        spinnerAlert.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, alerts)

        val aktualniAlert = prefs.getInt("default_alert_minutes", 15)
        spinnerAlert.setSelection(alertVals.indexOf(aktualniAlert).takeIf { it >= 0 } ?: 0)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 40, 0, 0) }
        val btnZrusit = Button(this).apply { text = getString(R.string.btn_cancel); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnUlozit = Button(this).apply { text = getString(R.string.btn_save); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        btnRow.addView(btnZrusit)
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })
        btnRow.addView(btnUlozit)

        layout.addView(tvTitle)
        layout.addView(tvDefaultCal)
        layout.addView(spinnerDefaultCal)
        layout.addView(tvStat)
        layout.addView(spinnerStat)
        layout.addView(cbExpert)
        layout.addView(tvDelka)
        layout.addView(spinnerDelka)
        layout.addView(tvAlert)
        layout.addView(spinnerAlert)
        layout.addView(btnRow)

        val dialog = AlertDialog.Builder(this).setView(layout).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnZrusit.setOnClickListener { dialog.dismiss() }
        btnUlozit.setOnClickListener {
            val editor = prefs.edit()

            val selectedDefaultCal = spinnerDefaultCal.selectedItem as? MujKalendar
            if (selectedDefaultCal != null) {
                editor.putLong("default_calendar_id", selectedDefaultCal.id)
            }

            editor.putString("vybrany_stat", statyKody[spinnerStat.selectedItemPosition])
                .putBoolean("expert_mode", cbExpert.isChecked)
                .putInt("default_duration_minutes", delky[spinnerDelka.selectedItemPosition])
                .putInt("default_alert_minutes", alertVals[spinnerAlert.selectedItemPosition])
                .apply()

            aktualizujWidgety()
            Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun ukazVyberKalendaru() {
        val dostupne = mutableListOf<MujKalendar>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) dostupne.add(MujKalendar(it.getLong(0), it.getString(1)))
            }
        }

        val nazvy = dostupne.map { it.name }.toTypedArray()
        val zaskrtnuto = BooleanArray(dostupne.size)
        val prefs = getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val ulozeneId = prefs.getStringSet("zobrazene_kalendare", null)

        for (i in dostupne.indices) {
            zaskrtnuto[i] = ulozeneId == null || ulozeneId.contains(dostupne[i].id.toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_calendars))
            .setMultiChoiceItems(nazvy, zaskrtnuto) { _, ktere, jeZaskrtnuto ->
                zaskrtnuto[ktere] = jeZaskrtnuto
            }
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val vybranaIds = mutableSetOf<String>()
                for (i in zaskrtnuto.indices) {
                    if (zaskrtnuto[i]) vybranaIds.add(dostupne[i].id.toString())
                }
                prefs.edit().putStringSet("zobrazene_kalendare", vybranaIds).apply()
                obnovVsechno()
                aktualizujWidgety()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        dialog.nastavZakulacenyVzhled(this)
        dialog.show()
    }

    private fun ukazOAplikaci() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_about))
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(getString(android.R.string.ok), null)
            .create()
        dialog.nastavZakulacenyVzhled(this)
        dialog.show()
    }

    private fun obnovVsechno() {
        obnovKalendar()
        zobrazUdalostiProDen(
            selectedCalendar.get(Calendar.YEAR),
            selectedCalendar.get(Calendar.MONTH),
            selectedCalendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun zpracujAkciSUdalosti(udalost: Udalost, akce: String) {
        if (!udalost.rrule.isNullOrEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_repeat_title))
                .setMessage(if (akce == "smazat") getString(R.string.dialog_repeat_msg_delete) else getString(R.string.dialog_repeat_msg_edit))
                .setPositiveButton(getString(R.string.dialog_only_this)) { _, _ -> if (akce == "smazat") provedSmazani(udalost, false) else otevreDialogSUpravou(udalost, false) }
                .setNegativeButton(getString(R.string.dialog_this_and_future)) { _, _ -> if (akce == "smazat") provedSmazani(udalost, true) else otevreDialogSUpravou(udalost, true) }
                .setNeutralButton(getString(R.string.btn_cancel), null).create()
            dialog.nastavZakulacenyVzhled(this)
            dialog.show()
        } else {
            if (akce == "smazat") provedSmazani(udalost, true) else otevreDialogSUpravou(udalost, true)
        }
    }

    private fun provedSmazani(udalost: Udalost, tatoAVsechnyBudouci: Boolean) {
        try {
            if (udalost.rrule.isNullOrEmpty()) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, udalost.eventId)
                contentResolver.delete(uri, null, null)
                AlarmsManager.zrusitVlastniUpozorneni(this, udalost.eventId)
            } else {
                if (tatoAVsechnyBudouci) {
                    val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = udalost.beginTime - 1000
                    }
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val untilDate = fmt.format(untilCal.time)
                    val baseRrule = udalost.rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
                    val newRrule = "$baseRrule;UNTIL=$untilDate"

                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, udalost.eventId)
                    val values = ContentValues().apply { put(CalendarContract.Events.RRULE, newRrule) }
                    contentResolver.update(uri, values, null, null)
                } else {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.ORIGINAL_ID, udalost.eventId)
                        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, udalost.beginTime)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }
            }
            Toast.makeText(this, getString(R.string.toast_event_deleted), Toast.LENGTH_SHORT).show()
            obnovVsechno()
            aktualizujWidgety()
        } catch (e: Exception) {}
    }

    private fun otevreDialogSUpravou(udalost: Udalost, tatoAVsechnyBudouci: Boolean) {
        val cal = Calendar.getInstance().apply { timeInMillis = udalost.beginTime }
        ukazDialogProNovouUdalost(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), udalost, tatoAVsechnyBudouci)
    }

    private fun ukazDialogProNovouUdalost(year: Int, month: Int, day: Int, udalostKUprave: Udalost?, upravitTutoABudouci: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pridat_jizdu, null)
        val spinnerCalendar = dialogView.findViewById<Spinner>(R.id.spinnerCalendar)
        val spinnerOpakovani = dialogView.findViewById<Spinner>(R.id.spinnerOpakovani)
        val etEventTitle = dialogView.findViewById<EditText>(R.id.etEventTitle)
        val btnDatePicker = dialogView.findViewById<Button>(R.id.btnDatePicker)
        val btnTimePicker = dialogView.findViewById<Button>(R.id.btnTimePicker)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        fun schovejKlavesnici(view: View) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }

        val prefs = getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val isExpertMode = prefs.getBoolean("expert_mode", false)
        val defaultDuration = prefs.getInt("default_duration_minutes", 15)
        val defaultAlert = prefs.getInt("default_alert_minutes", 15)

        val tvCalendarLabel = dialogView.findViewById<TextView>(R.id.tvCalendarLabel)
        val tvOpakovaniLabel = dialogView.findViewById<TextView>(R.id.tvOpakovaniLabel)

        if (!isExpertMode) {
            spinnerOpakovani.visibility = View.GONE
            tvOpakovaniLabel.visibility = View.GONE
            spinnerCalendar.visibility = View.GONE
            tvCalendarLabel.visibility = View.GONE
        } else {
            spinnerOpakovani.visibility = View.VISIBLE
            tvOpakovaniLabel.visibility = View.VISIBLE
            spinnerCalendar.visibility = View.VISIBLE
            tvCalendarLabel.visibility = View.VISIBLE
        }

        val dostupneKalendare = mutableListOf<MujKalendar>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) dostupneKalendare.add(MujKalendar(cursor.getLong(0), cursor.getString(1)))
            }
        }

        val spinnerAdapter = ArrayAdapter<MujKalendar>(this, android.R.layout.simple_spinner_dropdown_item, dostupneKalendare)
        spinnerCalendar.adapter = spinnerAdapter

        spinnerCalendar.setOnTouchListener { v, _ ->
            schovejKlavesnici(v)
            false
        }

        val rezimyOpakovani = arrayOf(getString(R.string.repeat_none), getString(R.string.repeat_daily), getString(R.string.repeat_weekly), getString(R.string.repeat_14days), getString(R.string.repeat_monthly), getString(R.string.repeat_yearly))
        val rruleHodnoty = arrayOf(null, "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=WEEKLY;INTERVAL=2", "FREQ=MONTHLY", "FREQ=YEARLY")

        val opakovaniAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, rezimyOpakovani)
        spinnerOpakovani.adapter = opakovaniAdapter

        spinnerOpakovani.setOnTouchListener { v, _ ->
            schovejKlavesnici(v)
            false
        }

        var vybranyRok = year
        var vybranyMesic = month
        var vybranyDen = day
        var vybranaHodina = 12
        var vybranaMinuta = 0

        if (udalostKUprave != null) {
            etEventTitle.setText(udalostKUprave.title)
            val cal = Calendar.getInstance().apply { timeInMillis = udalostKUprave.beginTime }
            vybranyRok = cal.get(Calendar.YEAR)
            vybranyMesic = cal.get(Calendar.MONTH)
            vybranyDen = cal.get(Calendar.DAY_OF_MONTH)
            vybranaHodina = cal.get(Calendar.HOUR_OF_DAY)
            vybranaMinuta = cal.get(Calendar.MINUTE)
            val calIndex = dostupneKalendare.indexOfFirst { it.id == udalostKUprave.calendarId }
            if (calIndex != -1) spinnerCalendar.setSelection(calIndex)
            val rruleIndex = rruleHodnoty.indexOf(udalostKUprave.rrule)
            if (rruleIndex != -1) spinnerOpakovani.setSelection(rruleIndex)
        } else {
            val targetCalId = if (isExpertMode) {
                prefs.getLong("posledni_kalendar_id", -1L)
            } else {
                prefs.getLong("default_calendar_id", -1L)
            }
            if (targetCalId != -1L) {
                val index = dostupneKalendare.indexOfFirst { it.id == targetCalId }
                if (index != -1) spinnerCalendar.setSelection(index)
            }
        }

        val updateDateBtnText = {
            val c = Calendar.getInstance().apply { set(vybranyRok, vybranyMesic, vybranyDen) }
            btnDatePicker.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(c.time)
        }
        updateDateBtnText()

        btnDatePicker.setOnClickListener { view ->
            schovejKlavesnici(view)
            DatePickerDialog(this, { _, y, m, d ->
                vybranyRok = y
                vybranyMesic = m
                vybranyDen = d
                updateDateBtnText()
            }, vybranyRok, vybranyMesic, vybranyDen).show()
        }

        btnTimePicker.text = String.format("%02d:%02d", vybranaHodina, vybranaMinuta)
        btnTimePicker.setOnClickListener { view ->
            schovejKlavesnici(view)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(50, 50, 50, 50)
            }
            val hourPicker = NumberPicker(this).apply {
                minValue = 0; maxValue = 23; value = vybranaHodina; wrapSelectorWheel = true
            }
            val minuteValues = arrayOf("00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55")
            val minutePicker = NumberPicker(this).apply {
                minValue = 0; maxValue = minuteValues.size - 1; displayedValues = minuteValues; value = vybranaMinuta / 5; wrapSelectorWheel = true
            }
            layout.addView(hourPicker)
            layout.addView(TextView(this).apply { text = ":"; textSize = 30f; gravity = Gravity.CENTER; setPadding(20, 0, 20, 0) })
            layout.addView(minutePicker)

            val timeDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_select_time))
                .setView(layout)
                .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                    vybranaHodina = hourPicker.value
                    vybranaMinuta = minutePicker.value * 5
                    btnTimePicker.text = String.format("%02d:%02d", vybranaHodina, vybranaMinuta)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null).create()
            timeDialog.nastavZakulacenyVzhled(this)
            timeDialog.show()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            schovejKlavesnici(it)
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            schovejKlavesnici(it)

            val title = etEventTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_enter_name), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val zvolenyKalendar = spinnerCalendar.selectedItem as? MujKalendar ?: return@setOnClickListener

            // Čistý čas události (vynulované sekundy a milisekundy)
            val calStart = Calendar.getInstance().apply {
                set(vybranyRok, vybranyMesic, vybranyDen, vybranaHodina, vybranaMinuta, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, calStart.timeInMillis)
                put(CalendarContract.Events.DTEND, calStart.timeInMillis + (defaultDuration * 60 * 1000L))
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, zvolenyKalendar.id)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)

                // Hlášení systémovému kalendáři, že tato událost má notifikace
                if (defaultAlert != -1) {
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }

                if (udalostKUprave == null || upravitTutoABudouci) {
                    val rrule = rruleHodnoty[spinnerOpakovani.selectedItemPosition]
                    if (rrule != null) put(CalendarContract.Events.RRULE, rrule) else putNull(CalendarContract.Events.RRULE)
                }
            }

            // OPRAVA 2: Sjednocená funkce pro vyčištění, aktualizaci a správný výpočet notifikací
            val zariditNotifikace = { targetEventId: Long ->
                // A) Smaže staré systémové notifikace, aby se neduplikovaly při editaci
                try {
                    contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(targetEventId.toString()))
                } catch (e: Exception) {}

                // B) Znovu je nahodí, pokud nejsou vypnuté (-1)
                if (defaultAlert != -1) {
                    val remValues = ContentValues().apply {
                        put(CalendarContract.Reminders.MINUTES, defaultAlert)
                        put(CalendarContract.Reminders.EVENT_ID, targetEventId)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    try {
                        contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues)
                    } catch (e: Exception) {}

                    // Přepočet přesného času zvonění (od času jízdy se ODEČTE nastavený počet minut)
                    val casZvoneni = calStart.timeInMillis - (defaultAlert * 60 * 1000L)
                    AlarmsManager.nastavitVlastniUpozorneni(this@MainActivity, targetEventId, casZvoneni, title)
                } else {
                    AlarmsManager.zrusitVlastniUpozorneni(this@MainActivity, targetEventId)
                }
            }

            try {
                if (udalostKUprave == null) {
                    val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    val newEventId = uri?.lastPathSegment?.toLongOrNull()
                    if (newEventId != null) {
                        zariditNotifikace(newEventId)
                    }
                    if (isExpertMode) {
                        getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE).edit().putLong("posledni_kalendar_id", zvolenyKalendar.id).apply()
                    }
                } else {
                    if (udalostKUprave.rrule.isNullOrEmpty()) {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, udalostKUprave.eventId)
                        contentResolver.update(uri, values, null, null)
                        zariditNotifikace(udalostKUprave.eventId)
                    } else {
                        if (upravitTutoABudouci) {
                            val untilCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = udalostKUprave.beginTime - 1000
                            }
                            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            val untilDate = fmt.format(untilCal.time)
                            val baseRrule = udalostKUprave.rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
                            val newRrule = "$baseRrule;UNTIL=$untilDate"

                            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, udalostKUprave.eventId)
                            val updateValues = ContentValues().apply { put(CalendarContract.Events.RRULE, newRrule) }
                            contentResolver.update(updateUri, updateValues, null, null)

                            val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                            val newEventId = uri?.lastPathSegment?.toLongOrNull()
                            if (newEventId != null) {
                                zariditNotifikace(newEventId)
                            }
                        } else {
                            values.put(CalendarContract.Events.ORIGINAL_ID, udalostKUprave.eventId)
                            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, udalostKUprave.beginTime)
                            val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                            val newEventId = uri?.lastPathSegment?.toLongOrNull()
                            if (newEventId != null) {
                                zariditNotifikace(newEventId)
                            }
                        }
                    }
                }
                Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                obnovVsechno()
                aktualizujWidgety()
            } catch (e: Exception) {}
        }
        dialog.show()
    }

    private fun ziskejDnySUdalostmi(year: Int, month: Int): Set<Int> {
        val dny = mutableSetOf<Int>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return dny

        val cal = Calendar.getInstance().apply { set(year, month, 1, 0, 0, 0) }
        val startOfMonth = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val endOfMonth = cal.timeInMillis - 1

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startOfMonth)
        ContentUris.appendId(builder, endOfMonth)

        contentResolver.query(builder.build(), arrayOf(CalendarContract.Instances.BEGIN), ziskejPovoleneKalendare(), null, null)?.use {
            val beginIndex = it.getColumnIndex(CalendarContract.Instances.BEGIN)
            val eventCal = Calendar.getInstance()
            while (it.moveToNext()) {
                eventCal.timeInMillis = it.getLong(beginIndex)
                if (eventCal.get(Calendar.MONTH) == month && eventCal.get(Calendar.YEAR) == year) dny.add(eventCal.get(Calendar.DAY_OF_MONTH))
            }
        }
        return dny
    }

    private fun obnovKalendar() {
        val monthYearFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
        tvMonthYear.text = monthYearFormat.format(calendarCursor.time).replaceFirstChar { it.uppercase() }

        val daysList = mutableListOf<String>()
        val cal = calendarCursor.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)

        val symbols = java.text.DateFormatSymbols.getInstance(Locale.getDefault())
        val shortDays = symbols.shortWeekdays
        val dayHeaders = listOf(
            shortDays[Calendar.MONDAY], shortDays[Calendar.TUESDAY], shortDays[Calendar.WEDNESDAY],
            shortDays[Calendar.THURSDAY], shortDays[Calendar.FRIDAY], shortDays[Calendar.SATURDAY], shortDays[Calendar.SUNDAY]
        ).map { it.replaceFirstChar { char -> char.uppercase() } }

        daysList.addAll(dayHeaders)

        var startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 2
        if (startDayOfWeek < 0) startDayOfWeek = 6

        for (i in 0 until startDayOfWeek) daysList.add("")
        for (i in 1..cal.getActualMaximum(Calendar.DAY_OF_MONTH)) daysList.add(i.toString())

        val dnySAkci = ziskejDnySUdalostmi(calendarCursor.get(Calendar.YEAR), calendarCursor.get(Calendar.MONTH))

        gvCalendar.adapter = object : ArrayAdapter<String>(this, 0, daysList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val layout = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val scale = resources.displayMetrics.density
                    layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (45 * scale).toInt())
                    addView(TextView(this@MainActivity).apply { id = android.R.id.text1; gravity = Gravity.CENTER; textSize = 16f })
                    addView(View(this@MainActivity).apply {
                        id = android.R.id.icon
                        layoutParams = LinearLayout.LayoutParams((5 * scale).toInt(), (5 * scale).toInt()).apply { topMargin = (2 * scale).toInt() }
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF4444")) }
                    })
                }

                val text = daysList[position]
                val dayNum = text.toIntOrNull()
                val tv = layout.findViewById<TextView>(android.R.id.text1)
                val dot = layout.findViewById<View>(android.R.id.icon)

                val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                if (position < 7) {
                    tv.text = text
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.setTextColor(if (isNightMode) Color.LTGRAY else Color.DKGRAY)
                    layout.background = null
                    dot.visibility = View.INVISIBLE
                } else {
                    tv.text = text
                    if (dayNum != null && dayNum == selectedCalendar.get(Calendar.DAY_OF_MONTH) &&
                        calendarCursor.get(Calendar.MONTH) == selectedCalendar.get(Calendar.MONTH) &&
                        calendarCursor.get(Calendar.YEAR) == selectedCalendar.get(Calendar.YEAR)) {

                        val selectedColor = if (isNightMode) Color.parseColor("#FFD500") else Color.parseColor("#009B77")
                        layout.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(selectedColor)
                        }
                        tv.setTypeface(null, android.graphics.Typeface.BOLD)
                        tv.setTextColor(if (isNightMode) Color.BLACK else Color.WHITE)
                    } else {
                        layout.background = null
                        tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                        tv.setTextColor(if (isNightMode) Color.WHITE else Color.BLACK)
                    }
                    dot.visibility = if (dayNum != null && dnySAkci.contains(dayNum)) View.VISIBLE else View.INVISIBLE
                }
                return layout
            }
        }
    }

    private fun zobrazUdalostiProDen(year: Int, month: Int, day: Int) {
        val selCal = Calendar.getInstance().apply { set(year, month, day) }
        tvSelectedDate.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(selCal.time)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return

        val startOfDay = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }.timeInMillis
        val endOfDay = Calendar.getInstance().apply { set(year, month, day, 23, 59, 59) }.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startOfDay)
        ContentUris.appendId(builder, endOfDay)

        val proj = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ALL_DAY
        )

        val cursor = contentResolver.query(builder.build(), proj, ziskejPovoleneKalendare(), null, "${CalendarContract.Instances.BEGIN} ASC")
        val seznamUdalosti = mutableListOf<Udalost>()

        cursor?.use {
            while (it.moveToNext()) {
                seznamUdalosti.add(Udalost(
                    it.getLong(0),
                    it.getLong(1),
                    it.getString(2) ?: getString(R.string.event_untitled),
                    it.getLong(3),
                    it.getLong(4),
                    it.getString(5),
                    it.getInt(6) == 1,
                    getString(R.string.event_all_day)
                ))
            }
        }

        lvAgenda.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, seznamUdalosti)
    }
}