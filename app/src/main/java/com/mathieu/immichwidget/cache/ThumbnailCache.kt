package com.mathieu.immichwidget.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Cache local des thumbnails, en fichiers WebP 500x500 dans le stockage
 * interne de l'appli (déjà sandboxé par Android, pas besoin de permissions
 * de stockage supplémentaires).
 *
 * Le nom de fichier == l'assetId Immich -> pas besoin d'index séparé,
 * la liste des fichiers présents EST la liste des photos en cache.
 */
object ThumbnailCache {

    private const val DIR_NAME = "immich_thumbnails"
    private const val TARGET_SIZE_PX = 500
    private const val WEBP_QUALITY = 85

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
     * Décode les bytes bruts du thumbnail Immich, recadre en carré 500x500
     * (center-crop, cohérent avec le rendu centerCrop du widget), et
     * sauvegarde en WebP.
     */
    fun saveThumbnail(context: Context, assetId: String, rawBytes: ByteArray): Boolean {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return false

            val squared = centerCropToSquare(original, TARGET_SIZE_PX)
            val file = fileFor(context, assetId)

            FileOutputStream(file).use { out ->
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                squared.compress(format, WEBP_QUALITY, out)
            }

            if (squared !== original) original.recycle()
            squared.recycle()
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

    private fun centerCropToSquare(source: Bitmap, targetSize: Int): Bitmap {
        // 1) Scale pour que la plus petite dimension couvre targetSize
        val scale = targetSize.toFloat() / minOf(source.width, source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        // 2) Crop centré targetSize x targetSize
        val x = ((scaledWidth - targetSize) / 2).coerceAtLeast(0)
        val y = ((scaledHeight - targetSize) / 2).coerceAtLeast(0)
        val cropWidth = minOf(targetSize, scaledWidth)
        val cropHeight = minOf(targetSize, scaledHeight)

        val cropped = Bitmap.createBitmap(scaled, x, y, cropWidth, cropHeight)
        if (scaled !== cropped) scaled.recycle()
        return cropped
    }
}
