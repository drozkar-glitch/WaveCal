package cz.mares.wavecal

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cz.mares.wavecal.R

class CalendarSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        // ABSOLUTNÍ ZÁCHRANNÁ BRZDA: Pokud zrovna nemáme práva ke kalendáři (např. čistá instalace z Google Play),
        // nesmíme se vůbec pokoušet číst data, jinak nás systém nemilosrdně zabije!
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return // Tiše to ukončíme, dokud uživatel práva v aplikaci neodsouhlasí.
        }

        // 1. Okamžitě překreslit widget při změně z jiného zařízení
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, AgendaWidget::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)

        val updateIntent = Intent(context, AgendaWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(updateIntent)
        appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)

        // 2. Přepočítat budíky tiše na vedlejším vlákně, ať nesekáme telefon
        val pendingResult = goAsync()
        Thread {
            try {
                AlarmsManager.naplanujBudikyNaPozadi(context)
            } catch (e: Exception) {
                // Bezpečnostní polštář: pokud selže cokoliv na pozadí, aplikace to ustojí a nespadne
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}