package com.mathieu.immichwidget.sync

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.mathieu.immichwidget.widget.PhotoWidgetProvider
import com.mathieu.immichwidget.widget.WidgetUpdateHelper

class AutoChangeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "com.mathieu.immichwidget.ACTION_AUTO_TICK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Les alarmes AlarmManager ne survivent pas à un reboot,
                // contrairement aux PeriodicWorkRequest de WorkManager.
                // On reprogramme donc le prochain tick ici si un intervalle est configuré.
                AutoChangeScheduler.scheduleNext(context)
            }

            ACTION_TICK -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val widgetIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, PhotoWidgetProvider::class.java)
                )
                widgetIds.forEach { widgetId ->
                    WidgetUpdateHelper.showNextRandomPhoto(context, appWidgetManager, widgetId)
                }
                // Reprogramme le prochain tick (l'intervalle est relu depuis les
                // prefs à chaque fois, donc un changement de valeur est pris en
                // compte dès le tick suivant sans redémarrage nécessaire).
                AutoChangeScheduler.scheduleNext(context)
            }
        }
    }
}
