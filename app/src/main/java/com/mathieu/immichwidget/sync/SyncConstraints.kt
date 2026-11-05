package com.mathieu.immichwidget.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import com.mathieu.immichwidget.cache.SecurePrefs

/**
 * Contrainte réseau à appliquer aux deux Workers (Album et Memory) : si
 * "Wi-Fi uniquement" est activé dans les params, on exige NetworkType.UNMETERED
 * (WorkManager ne déclenchera pas tant que l'appareil n'est pas sur un réseau
 * non mesuré — en pratique le Wi-Fi) ; sinon n'importe quelle connexion suffit.
 */
object SyncConstraints {
    fun build(context: Context): Constraints {
        val wifiOnly = SecurePrefs.getInstance(context).wifiOnlySync
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }
}
