package cz.mares.wavecal

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import cz.mares.wavecal.R
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.agenda_widget)

            // Zjištění aktuálního data a nastavení svátku
            val cal = Calendar.getInstance()
            val mesic = cal.get(Calendar.MONTH)       // 0 = leden, 11 = prosinec
            val den = cal.get(Calendar.DAY_OF_MONTH)  // 1 až 31

            // PŘIDÁN CONTEXT PRO PŘÍSTUP K ASSETS A NASTAVENÍ
            val jmenoSvatku = ziskejSvatek(context, mesic, den)

            // ZMĚNA: Pokud je zvoleno "none" (prázdný řetězec), pole se svátkem se schová
            if (jmenoSvatku.isEmpty()) {
                views.setViewVisibility(R.id.widget_name_day, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_name_day, View.VISIBLE)
                views.setTextViewText(R.id.widget_name_day, jmenoSvatku)

                // Změna barvy na žlutou, pokud je jméno nalezeno v telefonním seznamu
                if (obsahujeKontaktJmeno(context, jmenoSvatku)) {
                    views.setTextColor(R.id.widget_name_day, 0XFFFFFF00.toInt()) // Žlutá
                } else {
                    views.setTextColor(R.id.widget_name_day, 0XFFFF4444.toInt()) // Červená
                }
            }

            // Vykreslení událostí přímo do pevného kontejneru (náhrada za ListView a WidgetService)
            views.removeAllViews(R.id.widget_list)
            nactiAVykresliUdalosti(context, views)

            // PROKLIK: Kliknutím na jakoukoliv položku oder hlavičku se hned otevře náš kalendář
            val clickIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_list, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_name_day, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // NEKOMPROMISNÍ PŮLNOČNÍ BUDÍK: Naplánování přesné aktualizace svátku na 00:00:01
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val updateIntent = Intent(context, AgendaWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            val pi = PendingIntent.getBroadcast(
                context, 777777, updateIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextMidnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 1)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, pi)
        } catch (e: Exception) {
            // Bezpečnostní pojistka pro případ selhání systémové služby
        }

        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    private fun nactiAVykresliUdalosti(context: Context, views: RemoteViews) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val startMillis = System.currentTimeMillis()
        val endMillis = Calendar.getInstance().apply { add(Calendar.YEAR, 1) }.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
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
            "${CalendarContract.Instances.CALENDAR_ID} IN (${ulozeneId.joinToString(",")})"
        } else null

        val cursor: Cursor? = context.contentResolver.query(
            builder.build(), proj, filtr, null, "${CalendarContract.Instances.BEGIN} ASC"
        )

        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("d.M.", Locale.getDefault())

        var nejblizsiKonec = Long.MAX_VALUE
        var pocetNactenych = 0

        val clickIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        cursor?.use {
            while (it.moveToNext()) {
                // Připravíme max 12 řádků. Rámeček 4x2 jich reálně pojme méně, zbytek systém dole odsekne.
                if (pocetNactenych >= 12) break

                val title = it.getString(0) ?: context.getString(R.string.event_untitled)
                val begin = it.getLong(1)
                val isAllDay = it.getInt(2) == 1
                val end = it.getLong(3)

                // Zjištění nejbližšího konce aktuální/budoucí události pro domino budík
                if (!isAllDay && end > startMillis && end < nejblizsiKonec) {
                    nejblizsiKonec = end
                }

                val cal = Calendar.getInstance().apply { timeInMillis = begin }
                val dnes = Calendar.getInstance()
                val jeDnes = cal.get(Calendar.DAY_OF_YEAR) == dnes.get(Calendar.DAY_OF_YEAR) &&
                        cal.get(Calendar.YEAR) == dnes.get(Calendar.YEAR)

                val casovyPrefix = if (isAllDay) {
                    if (jeDnes) context.getString(R.string.widget_today) else dateFormatter.format(begin)
                } else {
                    if (jeDnes) timeFormatter.format(begin) else "${dateFormatter.format(begin)} ${timeFormatter.format(begin)}"
                }

                // Generování pevného řádku
                val itemView = RemoteViews(context.packageName, R.layout.widget_item)
                itemView.setTextViewText(R.id.item_time, casovyPrefix)
                itemView.setTextViewText(R.id.item_title, title)

                // Proklik z konkrétního prvku
                itemView.setOnClickPendingIntent(R.id.item_time, pendingIntent)
                itemView.setOnClickPendingIntent(R.id.item_title, pendingIntent)

                views.addView(R.id.widget_list, itemView)
                pocetNactenych++
            }
        }

        // OPRAVENÝ DOMINO EFEKT: Budík teď posílá přímý a tvrdý příkaz k aktualizaci celého widgetu
        if (nejblizsiKonec != Long.MAX_VALUE) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val thisWidget = android.content.ComponentName(context, AgendaWidget::class.java)
            val allWidgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(thisWidget)

            val intent = Intent(context, AgendaWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
            }
            val pi = PendingIntent.getBroadcast(
                context, 999999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nejblizsiKonec, pi)
            } catch (e: Exception) {}
        }
    }

    // Pomocná funkce pro inteligentní vyhledání shody jména v kontaktech telefonu
    private fun obsahujeKontaktJmeno(context: Context, jmenoSvatku: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        // REGEX OPRAVA: Rozdělení podle mezinárodních spojek a znaků (lomítko, čárka, a, i, und, and) nezávisle na velikosti písmen
        val jmena = jmenoSvatku.split(Regex("(?i)\\s*(?:/|,|\\ba\\b|\\bi\\b|\\bund\\b|\\band\\b)\\s*"))

        for (jmeno in jmena) {
            val cisteJmeno = jmeno.trim()
            // Přeskočit obecné texty a svátky, které nejsou lidskými jmény
            if (cisteJmeno.isEmpty() || cisteJmeno.contains("Svátek", ignoreCase = true) || cisteJmeno.contains("Nový rok", ignoreCase = true) || cisteJmeno.contains("Státní", ignoreCase = true)) {
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
                // Tichá pojistka pro případ jakékoliv systémové chyby
            }
        }
        return false
    }

    // ČTENÍ SVÁTKŮ Z LOKÁLNÍHO JSON SOUBORU V ASSETS
    private fun ziskejSvatek(context: Context, mesic: Int, den: Int): String {
        return try {
            // Zjištění vybraného státu z nastavení, výchozí je Česká republika ("cz")
            val prefs = context.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
            val stat = prefs.getString("vybrany_stat", "cz") ?: "cz"

            // ZMĚNA: Pokud je zvoleno "Nezobrazovat žádný", ihned vrátíme prázdný text
            if (stat == "none") return ""

            // Načtení konkrétního souboru ze složky assets/svatky/ (např. cz.json)
            val inputStream = context.assets.open("svatky/$stat.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            val jsonArray = JSONArray(jsonString)
            val mesicArray = jsonArray.getJSONArray(mesic)

            mesicArray.getString(den - 1)
        } catch (e: Exception) {
            "" // Pokud se cokoliv pokazí (chybějící soubor atd.), nevrátí nic
        }
    }
}