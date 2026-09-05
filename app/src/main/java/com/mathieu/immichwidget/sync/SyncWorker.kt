package com.mathieu.immichwidget.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import com.mathieu.immichwidget.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Sync périodique (toutes les 6h) : ne télécharge que les photos nouvelles
 * dans l'album, purge celles qui en ont été retirées. Téléchargement
 * parallèle limité à MAX_CONCURRENT_DOWNLOADS pour ne pas spammer le
 * serveur Immich sur les gros albums (ex: 2000 photos à la 1ère sync).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_WORK_NAME = "immich_widget_periodic_sync"
        private const val MAX_CONCURRENT_DOWNLOADS = 8

        /** À appeler une fois (ex: depuis WidgetConfigActivity après sauvegarde de la config). */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Sync immédiate (ex: juste après avoir choisi un album dans la config). */
        fun triggerImmediateSync(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
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
        val client = ImmichApiClient(serverUrl, apiKey)

        val remoteIdsResult = client.listAssetIdsForAlbum(albumId)
        val remoteIdsOrdered = remoteIdsResult.getOrElse {
            Log.e(TAG, "Échec récupération liste assets album $albumId", it)
            return Result.retry()
        }
        val remoteIds = remoteIdsOrdered.toSet()

        val localIds = ThumbnailCache.listCachedAssetIds(applicationContext)

        val toDownload = remoteIds - localIds
        val toDelete = localIds - remoteIds

        Log.i(TAG, "Delta sync album $albumId : +${toDownload.size} / -${toDelete.size}")

        // Purge des photos retirées de l'album côté serveur
        if (toDelete.isNotEmpty()) {
            ThumbnailCache.purgeExcept(applicationContext, remoteIds)
        }

        // Téléchargement parallèle contrôlé des nouvelles photos
        if (toDownload.isNotEmpty()) {
            val downloadOk = downloadAllLimited(client, toDownload)
            if (!downloadOk) {
                // On garde ce qui a été téléchargé avec succès, on retentera
                // les manquants à la prochaine sync (elles réapparaîtront
                // dans le prochain delta puisqu'absentes du cache).
                Log.w(TAG, "Sync partielle : certains téléchargements ont échoué, retry au prochain cycle")
            }
        }

        prefs.lastSyncMillis = System.currentTimeMillis()
        com.mathieu.immichwidget.cache.AssetOrderIndex.save(applicationContext, remoteIdsOrdered)

        // Si le widget n'affiche encore rien (1ère sync), on force un affichage initial
        WidgetUpdateHelper.updateAllWidgetsIfEmpty(applicationContext)

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
                    val saved = ThumbnailCache.saveThumbnail(applicationContext, assetId, bytes)
                    if (!saved) allSucceeded = false
                } catch (e: Exception) {
                    Log.e(TAG, "Échec téléchargement asset $assetId", e)
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
