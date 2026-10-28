package cz.mares.wavecal

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

object AlarmsManager {

    // Přijímá již finální, přesně vypočítaný čas zvonění, nedělá žádné plošné odpočty
    fun nastavitVlastniUpozorneni(context: Context, eventId: Long, triggerTimeMillis: Long, title: String) {
        if (triggerTimeMillis > System.currentTimeMillis()) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("TITLE", title)
                putExtra("IS_REFRESH", false)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, eventId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
        }
    }

    fun zrusitVlastniUpozorneni(context: Context, eventId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intentNotif = Intent(context, AlarmReceiver::class.java)
        val pendingIntentNotif = PendingIntent.getBroadcast(context, eventId.toInt(), intentNotif, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntentNotif)
    }

    fun naplanujBudikyNaPozadi(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return

        val startMillis = System.currentTimeMillis()
        val calEnd = Calendar.getInstance().apply { add(Calendar.YEAR, 2) }
        val endMillis = calEnd.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY
        )

        val prefs = context.getSharedPreferences("KalendPrefs", Context.MODE_PRIVATE)
        val ulozeneId = prefs.getStringSet("zobrazene_kalendare", null)
        val filtr = if (ulozeneId != null && ulozeneId.isNotEmpty()) {
            "${CalendarContract.Instances.CALENDAR_ID} IN (${ulozeneId.joinToString(",")})"
        } else null

        // Načtení záložní hodnoty pro události, které nemají zadané žádné upozornění
        val defaultMinut = prefs.getInt("default_alert_minutes", 15)

        context.contentResolver.query(builder.build(), proj, filtr, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)

            while (cursor.moveToNext()) {
                val isAllDay = cursor.getInt(allDayIdx) == 1
                if (!isAllDay) {
                    val eventId = cursor.getLong(idIdx)
                    val title = cursor.getString(titleIdx) ?: "Bez názvu"
                    val beginTime = cursor.getLong(beginIdx)

                    // 1. Zjištění konkrétních minut pro tuto událost z databáze Google Kalendáře
                    var nalezeneMinuty = -1
                    val remProj = arrayOf(CalendarContract.Reminders.MINUTES)

                    try {
                        val remCursor = context.contentResolver.query(
                            CalendarContract.Reminders.CONTENT_URI,
                            remProj,
                            "${CalendarContract.Reminders.EVENT_ID} = ?",
                            arrayOf(eventId.toString()),
                            null
                        )

                        remCursor?.use { rCursor ->
                            if (rCursor.moveToFirst()) {
                                nalezeneMinuty = rCursor.getInt(0)
                            }
                        }
                    } catch (e: Exception) {
                        // Tichá pojistka pro případ chyby čtení z databáze
                    }

                    // 2. Logika pro aplikování minut (Uložené v události > Záložní v aplikaci)
                    val finalMinuty = if (nalezeneMinuty != -1) {
                        nalezeneMinuty
                    } else {
                        defaultMinut
                    }

                    // 3. Vlastní odečet a finální vložení
                    if (finalMinuty != -1) {
                        val triggerTime = beginTime - (finalMinuty * 60 * 1000L)
                        nastavitVlastniUpozorneni(context, eventId, triggerTime, title)
                    } else {
                        // Pokud není nalezeno nic a uživatel má defaultně "Žádné" (-1), bezpečně zrušíme
                        zrusitVlastniUpozorneni(context, eventId)
                    }
                }
            }
        }
    }
}