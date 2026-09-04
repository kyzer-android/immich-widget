package com.mathieu.immichwidget.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Programme le changement automatique de photo à intervalle réglable.
 *
 * On utilise AlarmManager plutôt que WorkManager ici : WorkManager impose
 * un plancher dur de 15 minutes minimum entre deux exécutions d'un
 * PeriodicWorkRequest, ce qui est trop restrictif si on veut un intervalle
 * de type 5 ou 10 minutes. AlarmManager n'a pas cette limite.
 *
 * `setAndAllowWhileIdle` (plutôt que la variante "Exact") est utilisé
 * volontairement : la précision à la seconde près n'a aucun intérêt pour
 * un changement de fond d'écran, et ça évite d'avoir à demander la
 * permission SCHEDULE_EXACT_ALARM (Android 12+) à l'utilisateur.
 */
object AutoChangeScheduler {

    private const val REQUEST_CODE = 42_001

    fun scheduleNext(context: Context) {
        val prefs = com.mathieu.immichwidget.cache.SecurePrefs.getInstance(context)
        val intervalMinutes = prefs.autoChangeIntervalMinutes

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context)

        if (intervalMinutes <= 0) {
            alarmManager.cancel(pendingIntent)
            return
        }

        val triggerAtMillis = System.currentTimeMillis() + intervalMinutes * 60_000L
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoChangeReceiver::class.java).apply {
            action = AutoChangeReceiver.ACTION_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
