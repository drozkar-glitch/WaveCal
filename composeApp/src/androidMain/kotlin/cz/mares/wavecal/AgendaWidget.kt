package cz.mares.wavecal

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AgendaWidget : AppWidgetProvider() {

    companion object {
        fun refreshAllWidgets(context: Context) {
            try {
                val appContext = context.applicationContext
                val appWidgetManager = AppWidgetManager.getInstance(appContext)
                val componentName = ComponentName(appContext, AgendaWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                if (ids != null && ids.isNotEmpty()) {
                    AgendaWidget().onUpdate(appContext, appWidgetManager, ids)
                    val intent = Intent(appContext, AgendaWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    appContext.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                // Tichá pojistka
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.agenda_widget)

            val cal = Calendar.getInstance()
            val mesic = cal.get(Calendar.MONTH)
            val den = cal.get(Calendar.DAY_OF_MONTH)

            val jmenoSvatku = ziskejSvatek(context, mesic, den)

            if (jmenoSvatku.isEmpty()) {
                views.setViewVisibility(R.id.widget_name_day, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_name_day, View.VISIBLE)
                views.setTextViewText(R.id.widget_name_day, jmenoSvatku)

                if (obsahujeKontaktJmeno(context, jmenoSvatku)) {
                    views.setTextColor(R.id.widget_name_day, 0xFFFFFF00.toInt())
                } else {
                    views.setTextColor(R.id.widget_name_day, 0xFFFF4444.toInt())
                }
            }

            views.removeAllViews(R.id.widget_list)
            nactiAVykresliUdalosti(context, views, pendingIntent)

            views.setOnClickPendingIntent(R.id.widget_list, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_name_day, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // Půlnoční budík na 00:00:01 pro překreslení svátku a dnešních událostí
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val updateIntent = Intent(context, AgendaWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                777777,
                updateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nextMidnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 1)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, pi)
            } catch (se: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, nextMidnight, pi)
            }
        } catch (e: Exception) {
            // Tichá pojistka
        }

        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    private fun nactiAVykresliUdalosti(
        context: Context,
        views: RemoteViews,
        pendingIntent: PendingIntent
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val startMillis = System.currentTimeMillis()
        val endMillis = Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.timeInMillis

        val dnes = Calendar.getInstance()
        val utcZone = TimeZone.getTimeZone("UTC")
        val dnesUtcStart = Calendar.getInstance(utcZone).apply {
            set(dnes.get(Calendar.YEAR), dnes.get(Calendar.MONTH), dnes.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dnesUtcEnd = Calendar.getInstance(utcZone).apply {
            set(dnes.get(Calendar.YEAR), dnes.get(Calendar.MONTH), dnes.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, minOf(startMillis, dnesUtcStart))
        ContentUris.appendId(builder, endMillis)

        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.END
        )

        val prefs = context.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val ulozeneId = prefs.getStringSet("zobrazene_kalendare", null)
        val filtr = if (ulozeneId != null && ulozeneId.isNotEmpty()) {
            val safeIds = ulozeneId.mapNotNull { it.toLongOrNull() }
            if (safeIds.isNotEmpty()) {
                "${CalendarContract.Instances.CALENDAR_ID} IN (${safeIds.joinToString(",")})"
            } else null
        } else null

        val cursor: Cursor? = try {
            context.contentResolver.query(
                builder.build(),
                proj,
                filtr,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )
        } catch (e: Exception) {
            null
        }

        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("d.M.", Locale.getDefault())
        val dateFormatterUtc = SimpleDateFormat("d.M.", Locale.getDefault()).apply {
            timeZone = utcZone
        }

        var nejblizsiKonec = Long.MAX_VALUE
        var pocetNactenych = 0

        cursor?.use {
            while (it.moveToNext()) {
                if (pocetNactenych >= 12) break

                val title = it.getString(0) ?: context.getString(R.string.event_untitled)
                val begin = it.getLong(1)
                val isAllDay = it.getInt(2) == 1
                val end = it.getLong(3)

                // Vynechání skončených celodenních událostí (např. včerejšího svátku po půlnoci) i skončených běžných událostí
                if (isAllDay) {
                    if (end <= dnesUtcStart) continue
                } else {
                    if (end <= startMillis) continue
                }

                if (!isAllDay && end > startMillis && end < nejblizsiKonec) {
                    nejblizsiKonec = end
                }

                val casovyPrefix = if (isAllDay) {
                    val jeDnesAllDay = begin <= dnesUtcEnd && end > dnesUtcStart
                    if (jeDnesAllDay) context.getString(R.string.widget_today) else dateFormatterUtc.format(begin)
                } else {
                    val cal = Calendar.getInstance().apply { timeInMillis = begin }
                    val jeDnes = cal.get(Calendar.DAY_OF_YEAR) == dnes.get(Calendar.DAY_OF_YEAR) &&
                            cal.get(Calendar.YEAR) == dnes.get(Calendar.YEAR)
                    if (jeDnes) timeFormatter.format(begin) else "${dateFormatter.format(begin)} ${timeFormatter.format(begin)}"
                }

                val itemView = RemoteViews(context.packageName, R.layout.widget_item)
                itemView.setTextViewText(R.id.item_time, casovyPrefix)
                itemView.setTextViewText(R.id.item_title, title)

                itemView.setOnClickPendingIntent(R.id.item_time, pendingIntent)
                itemView.setOnClickPendingIntent(R.id.item_title, pendingIntent)

                views.addView(R.id.widget_list, itemView)
                pocetNactenych++
            }
        }

        // Domino efekt: 1 sekundu po skončení nejbližší události se widget sám překreslí a skončenou událost schová
        if (nejblizsiKonec != Long.MAX_VALUE) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val thisWidget = ComponentName(context, AgendaWidget::class.java)
                val allWidgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(thisWidget)

                val intent = Intent(context, AgendaWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    999999,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = nejblizsiKonec + 1000L
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } catch (se: SecurityException) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                // Tichá pojistka
            }
        }
    }

    private fun obsahujeKontaktJmeno(context: Context, jmenoSvatku: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val jmena = jmenoSvatku.split(Regex("(?i)\\s*(?:/|,|\\ba\\b|\\bi\\b|\\bund\\b|\\band\\b)\\s*"))

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
                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.count > 0) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // Tichá pojistka
            }
        }
        return false
    }

    private fun ziskejSvatek(context: Context, mesic: Int, den: Int): String {
        val prefs = context.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val stat = prefs.getString("vybrany_stat", "cz") ?: "cz"
        return NameDaysData.getNameDay(stat, mesic, den)
    }
}
