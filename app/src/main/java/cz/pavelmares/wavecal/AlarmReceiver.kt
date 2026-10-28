package cz.mares.wavecal

import android.app.NotificationChannel
import cz.mares.wavecal.R
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isRefresh = intent.getBooleanExtra("IS_REFRESH", false)

        if (isRefresh) {
            // TICHÁ AKTUALIZACE WIDGETU: Vynutí okamžité smazání skončené události z displeje
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, AgendaWidget::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        } else {
            // ČISTÉ UPOZORNĚNÍ NA UDÁLOST
            val title = intent.getStringExtra("TITLE") ?: "Událost"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "kalendar_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Upozornění na události",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Upozornění před událostí"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val clickIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title) // Zobrazuje se pouze samotný název události
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}