package com.mathieu.immichwidget.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Cache local des thumbnails, en fichiers WebP dans le stockage interne de
 * l'appli (déjà sandboxé par Android, pas besoin de permissions de
 * stockage supplémentaires).
 *
 * Le nom de fichier == l'assetId Immich -> pas besoin d'index séparé,
 * la liste des fichiers présents EST la liste des photos en cache.
 *
 * IMPORTANT : on ne recadre JAMAIS l'image ici. On redimensionne juste pour
 * que son plus grand côté ne dépasse pas MAX_DIMENSION_PX, en conservant le
 * ratio d'origine intact. Le choix "recadrer / image entière" est un
 * réglage d'AFFICHAGE (scaleType côté widget, cf WidgetUpdateHelper) — s'il
 * était appliqué ici, l'info hors-cadre serait perdue définitivement et le
 * mode "image entière" n'aurait plus aucun sens.
 */
object ThumbnailCache {

    private const val DIR_NAME = "immich_thumbnails"
    private const val MAX_DIMENSION_PX = 500
    private const val WEBP_QUALITY = 90

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(context: Context, assetId: String): File =
        File(cacheDir(context), "$assetId.webp")

    /** IDs de toutes les photos actuellement en cache (source de vérité pour le delta sync). */
    fun listCachedAssetIds(context: Context): Set<String> =
        cacheDir(context).listFiles()
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

    fun isCached(context: Context, assetId: String): Boolean =
        fileFor(context, assetId).exists()

    /**
     * Décode les bytes bruts du thumbnail Immich, redimensionne SANS
     * recadrer (le plus grand côté est ramené à MAX_DIMENSION_PX), et
     * sauvegarde en WebP.
     */
    fun saveThumbnail(context: Context, assetId: String, rawBytes: ByteArray): Boolean {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return false

            val resized = scaleToFit(original, MAX_DIMENSION_PX)
            val file = fileFor(context, assetId)

            FileOutputStream(file).use { out ->
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                resized.compress(format, WEBP_QUALITY, out)
            }

            if (resized !== original) original.recycle()
            resized.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteThumbnail(context: Context, assetId: String) {
        fileFor(context, assetId).delete()
    }

    /** Supprime tous les fichiers en cache dont l'ID n'est plus dans l'album (purge delta sync). */
    fun purgeExcept(context: Context, keepAssetIds: Set<String>) {
        cacheDir(context).listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in keepAssetIds) {
                file.delete()
            }
        }
    }

    /**
     * Choisit un fichier au hasard dans le cache, en évitant si possible de
     * retomber sur la même photo que celle actuellement affichée.
     */
    fun pickRandom(context: Context, excludeAssetId: String? = null): File? {
        val files = cacheDir(context).listFiles()?.toList().orEmpty()
        if (files.isEmpty()) return null
        if (files.size == 1) return files.first()

        val candidates = if (excludeAssetId != null) {
            files.filter { it.nameWithoutExtension != excludeAssetId }
        } else {
            files
        }
        return candidates.ifEmpty { files }.random()
    }

    fun cacheSizeBytes(context: Context): Long =
        cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Vide entièrement le cache de thumbnails. Utile après un correctif qui
     * change la façon dont les images sont traitées (ex: passage crop -> pas
     * de recadrage) : le delta sync ne retélécharge JAMAIS une photo dont
     * l'ID est déjà en cache, donc sans ce vidage manuel les anciennes
     * vignettes (potentiellement traitées avec l'ancien code) restent
     * coincées indéfiniment.
     */
    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Redimensionne pour que max(largeur, hauteur) == maxDimension, ratio conservé, sans recadrage. */
    private fun scaleToFit(source: Bitmap, maxDimension: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= maxDimension) return source // déjà assez petit, pas besoin de retraiter

        val scale = maxDimension.toFloat() / longSide
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
    }
}
