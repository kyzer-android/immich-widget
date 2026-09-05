package com.mathieu.immichwidget.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.MemoryCache
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Sync des Souvenirs du jour : contrairement à l'album (delta sync toutes
 * les 6h), on reconstruit ENTIÈREMENT le cache à chaque exécution réussie —
 * le contenu "il y a X ans" change chaque jour, un delta n'aurait aucun sens
 * (les souvenirs d'hier n'ont plus de raison d'être en cache aujourd'hui).
 *
 * Déclenchement : 1x/jour à 2h du matin. Si aucun souvenir n'est encore
 * disponible à cette heure (job nocturne Immich pas terminé côté serveur),
 * on retente toutes les heures jusqu'à en trouver, puis on s'arrête pour la
 * journée — pas de sync en boucle une fois le contenu du jour récupéré.
 */
class MemorySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val UNIQUE_WORK_NAME = "immich_widget_memory_daily_sync"
        private const val RETRY_WORK_NAME = "immich_widget_memory_retry"
        private const val MAX_CONCURRENT_DOWNLOADS = 8

        /**
         * Une fois par jour, calée sur 2h du matin. Si à cette heure-là le job
         * nocturne Immich n'a pas encore tourné côté serveur (aucun souvenir
         * trouvé), on reprend automatiquement toutes les heures jusqu'à en
         * trouver (cf scheduleHourlyRetry), puis on s'arrête pour la journée.
         */
        fun schedulePeriodic(context: Context) {
            val now = Calendar.getInstance()
            val nextTwoAm = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelayMs = (nextTwoAm.timeInMillis - now.timeInMillis).coerceAtLeast(0)

            val request = PeriodicWorkRequestBuilder<MemorySyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(SyncConstraints.build(context))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Sync immédiate (déclenchée par le bouton "Synchroniser maintenant"
         * de l'onglet Memory). Idempotent via enqueueUniqueWork(KEEP) : si
         * déclenchée plusieurs fois avant la fin de la précédente, on ne
         * lance pas de sync concurrente qui pourrait se marcher sur les pieds
         * avec la 1ère (clearAll()/saveIndex() en parallèle).
         */
        fun triggerImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<MemorySyncWorker>()
                .setConstraints(SyncConstraints.build(context))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "immich_widget_memory_immediate_sync",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Aucun souvenir trouvé (job nocturne pas encore terminé côté
         * serveur) -> retente dans 1h. REPLACE plutôt que KEEP : si un
         * précédent retry était déjà programmé, celui-ci le remplace au lieu
         * de s'empiler dessus.
         */
        private fun scheduleHourlyRetry(context: Context) {
            val request = OneTimeWorkRequestBuilder<MemorySyncWorker>()
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(SyncConstraints.build(context))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RETRY_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Souvenirs trouvés : plus besoin de retry, on annule celui qui aurait pu être en attente. */
        private fun cancelHourlyRetry(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(RETRY_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = SecurePrefs.getInstance(applicationContext)
        if (prefs.serverUrl.isNullOrBlank() || prefs.apiKey.isNullOrBlank()) {
            Log.w(TAG, "Sync Memory ignorée : URL/API key manquante")
            return Result.failure()
        }

        val client = ImmichApiClient(prefs.serverUrl!!, prefs.apiKey!!)

        val memoriesResult = client.listMemories()
        val memories = memoriesResult.getOrElse {
            Log.e(TAG, "Échec récupération des souvenirs du jour", it)
            return Result.retry()
        }

        if (memories.isEmpty()) {
            // Le job nocturne Immich n'a probablement pas encore tourné côté
            // serveur à cette heure — on retente dans 1h, sans toucher au
            // cache existant (pas de clearAll ici : autant garder ce qu'on a
            // déjà plutôt que de vider pour rien avant d'avoir du nouveau).
            Log.i(TAG, "Aucun souvenir trouvé pour l'instant, nouvelle tentative dans 1h")
            scheduleHourlyRetry(applicationContext)
            return Result.success()
        }

        // Trouvé : plus besoin de retenter dans l'heure qui suit.
        cancelHourlyRetry(applicationContext)

        // Reconstruction complète : on vide avant de repeupler.
        MemoryCache.clearAll(applicationContext)

        val allAssetIds = memories.flatMap { it.assetIds }.toSet()
        val downloadOk = downloadAllLimited(client, allAssetIds)
        if (!downloadOk) {
            Log.w(TAG, "Sync Memory partielle : certains téléchargements ont échoué")
        }

        MemoryCache.saveIndex(applicationContext, memories)
        Log.i(TAG, "Sync Memory terminée : ${memories.size} année(s), ${allAssetIds.size} photo(s) au total")

        // Reset l'état d'affichage widget (année courante) pour repartir sur le nouveau contenu
        WidgetUpdateHelper.resetMemoryState()
        WidgetUpdateHelper.updateAllWidgetsForCurrentMode(applicationContext)

        return Result.success()
    }

    private suspend fun downloadAllLimited(
        client: ImmichApiClient,
        assetIds: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        var allSucceeded = true

        val jobs = assetIds.map { assetId ->
            async {
                semaphore.acquire()
                try {
                    val bytes = client.downloadThumbnail(assetId).getOrNull()
                    if (bytes == null) {
                        allSucceeded = false
                        return@async
                    }
                    val saved = MemoryCache.saveThumbnail(applicationContext, assetId, bytes)
                    if (!saved) allSucceeded = false
                } catch (e: Exception) {
                    Log.e(TAG, "Échec téléchargement asset memory $assetId", e)
                    allSucceeded = false
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.awaitAll()
        allSucceeded
    }
}
