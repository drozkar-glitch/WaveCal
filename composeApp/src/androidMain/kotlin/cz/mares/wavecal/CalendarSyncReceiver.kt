package cz.mares.wavecal

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class CalendarSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, AgendaWidget::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)

        val updateIntent = Intent(context, AgendaWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(updateIntent)

        val pendingResult = goAsync()
        Thread {
            try {
                AlarmsManager.naplanujBudikyNaPozadi(context)
            } catch (e: Exception) {
                // Tichá pojistka
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
