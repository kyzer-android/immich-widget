package com.mathieu.immichwidget.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.AssetOrderIndex
import com.mathieu.immichwidget.cache.LocationCache
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import com.mathieu.immichwidget.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Sync album : sur de gros albums (potentiellement plusieurs milliers de
 * photos), télécharger tout devient irréaliste en espace comme en bande
 * passante. On tire donc un ÉCHANTILLON ALÉATOIRE de N photos (réglable,
 * défaut 200) à chaque cycle, et on reconstruit entièrement le cache à
 * chaque fois (comme Memory) plutôt qu'un delta — un nouvel échantillon
 * remplace l'ancien, pas de notion "d'ajouter les nouvelles photos" qui
 * n'aurait plus de sens ici. Fréquence de renouvellement réglable
 * (jours, défaut 7), plus un déclenchement manuel possible depuis les params.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_WORK_NAME = "immich_widget_periodic_sync"
        private const val IMMEDIATE_WORK_NAME = "immich_widget_immediate_sync"
        private const val MAX_CONCURRENT_DOWNLOADS = 8

        /** À appeler depuis WidgetConfigActivity après sauvegarde de la config. */
        fun schedulePeriodic(context: Context) {
            val frequencyDays = SecurePrefs.getInstance(context).albumSyncFrequencyDays.coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(frequencyDays.toLong(), TimeUnit.DAYS)
                .setConstraints(SyncConstraints.build(context))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // la fréquence peut changer entre 2 sauvegardes des params
                request
            )
        }

        /**
         * Sync immédiate (bouton "vider le cache", sauvegarde des params, ou un
         * futur bouton "régénérer maintenant"). REPLACE plutôt que KEEP : un
         * déclenchement manuel doit toujours relancer un cycle frais, même si
         * un précédent traînait encore en attente.
         */
        fun triggerImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(SyncConstraints.build(context))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = SecurePrefs.getInstance(applicationContext)
        if (!prefs.isConfigured()) {
            Log.w(TAG, "Sync ignorée : configuration incomplète (URL/API key/album manquant)")
            return Result.failure()
        }

        val serverUrl = prefs.serverUrl!!
        val apiKey = prefs.apiKey!!
        val albumId = prefs.albumId!!
        val sampleSize = prefs.albumSampleSize.coerceAtLeast(1)
        val client = ImmichApiClient(serverUrl, apiKey)

        // Liste complète des métadonnées de l'album (léger : juste IDs + dates,
        // pas les photos elles-mêmes — supporte des albums de plusieurs milliers
        // de photos sans souci, contrairement au téléchargement intégral).
        val allAssetsResult = client.listAssetsForAlbum(albumId)
        val allAssets = allAssetsResult.getOrElse {
            Log.e(TAG, "Échec récupération liste assets album $albumId", it)
            return Result.retry()
        }

        if (allAssets.isEmpty()) {
            Log.w(TAG, "Album $albumId vide, rien à échantillonner")
            return Result.success()
        }

        // Si l'album entier tient déjà dans la limite ET que le cache actuel
        // correspond exactement à cet ensemble, il n'y a rien à regagner à
        // tout revider/retélécharger — l'échantillon serait identique.
        if (allAssets.size <= sampleSize) {
            val allIds = allAssets.map { it.id }.toSet()
            val cachedIds = ThumbnailCache.listCachedAssetIds(applicationContext)
            if (allIds == cachedIds) {
                Log.i(TAG, "Album $albumId : ${allAssets.size} photo(s), déjà toutes en cache -> resync inutile")
                AssetOrderIndex.save(applicationContext, allAssets) // garde l'ordre à jour au cas où
                return Result.success()
            }
        }

        val sample = if (allAssets.size <= sampleSize) allAssets else allAssets.shuffled().take(sampleSize)
        Log.i(TAG, "Album $albumId : ${allAssets.size} photo(s) au total, échantillon de ${sample.size} tiré")

        // Reconstruction complète : le nouvel échantillon remplace l'ancien,
        // pas de delta (un renouvellement periodique doit pouvoir faire
        // disparaître des photos vues au cycle précédent).
        ThumbnailCache.clearAll(applicationContext)

        val downloadOk = downloadAllLimited(client, sample.map { it.id }.toSet())
        if (!downloadOk) {
            Log.w(TAG, "Sync partielle : certains téléchargements de l'échantillon ont échoué")
        }

        prefs.lastSyncMillis = System.currentTimeMillis()
        AssetOrderIndex.save(applicationContext, sample)
        LocationCache.retainOnly(applicationContext, sample.map { it.id }.toSet())

        WidgetUpdateHelper.updateAllWidgetsIfEmpty(applicationContext)

        return Result.success()
    }

    private suspend fun downloadAllLimited(
        client: ImmichApiClient,
        assetIds: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        var allSucceeded = true
        val locations = ConcurrentHashMap<String, String>()

        val jobs = assetIds.map { assetId ->
            async {
                semaphore.acquire()
                try {
                    val bytes = client.downloadThumbnail(assetId).getOrNull()
                    if (bytes == null) {
                        allSucceeded = false
                        return@async
                    }
                    val saved = ThumbnailCache.saveThumbnail(applicationContext, assetId, bytes)
                    if (!saved) allSucceeded = false

                    // Précharge la localisation EN MÊME TEMPS que la vignette :
                    // elle est ainsi déjà connue au moment du visionnage, plutôt
                    // que d'être chargée en direct (ce qui faisait clignoter le
                    // texte à chaque changement de photo en plein écran).
                    val location = client.getAssetLocation(assetId).getOrNull()
                    if (!location.isNullOrBlank()) {
                        locations[assetId] = location
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Échec téléchargement asset $assetId", e)
                    allSucceeded = false
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.awaitAll()
        LocationCache.putAll(applicationContext, locations)
        allSucceeded
    }
}
