package com.mathieu.immichwidget.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mathieu.immichwidget.api.ImmichMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Cache des Souvenirs du jour — dossier séparé du cache album, car son
 * contenu change chaque jour et doit être entièrement reconstruit (pas de
 * delta sync comme pour l'album, cf discussion : "il y a 3 ans" d'aujourd'hui
 * n'a plus rien à voir avec "il y a 3 ans" d'hier).
 */
object MemoryCache {

    private const val DIR_NAME = "immich_memory_thumbnails"
    private const val INDEX_FILE_NAME = "memory_index.json"
    private const val MAX_DIMENSION_PX = 750
    private const val WEBP_QUALITY = 90

    data class MemoryYearGroup(val year: Int, val assetIds: List<String>)

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun indexFile(context: Context): File = File(context.filesDir, INDEX_FILE_NAME)

    private fun fileFor(context: Context, assetId: String): File =
        File(cacheDir(context), "$assetId.webp")

    fun isCached(context: Context, assetId: String): Boolean = fileFor(context, assetId).exists()

    fun saveThumbnail(context: Context, assetId: String, rawBytes: ByteArray): Boolean {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return false
            val resized = ImageUtils.scaleToFit(original, MAX_DIMENSION_PX)
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

    fun filePathFor(context: Context, assetId: String): File = fileFor(context, assetId)

    /** Vide entièrement le cache Memory (photos + index) avant une reconstruction complète. */
    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
        indexFile(context).delete()
    }

    /** Sauvegarde la structure année -> IDs (dans l'ordre où Immich les a renvoyés). */
    fun saveIndex(context: Context, memories: List<ImmichMemory>) {
        val array = JSONArray()
        memories.forEach { memory ->
            val obj = JSONObject()
            obj.put("year", memory.year)
            obj.put("assetIds", JSONArray(memory.assetIds))
            array.put(obj)
        }
        indexFile(context).writeText(array.toString())
    }

    fun loadIndex(context: Context): List<MemoryYearGroup> {
        return try {
            val file = indexFile(context)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val idsArray = obj.getJSONArray("assetIds")
                val ids = (0 until idsArray.length()).map { idsArray.getString(it) }
                MemoryYearGroup(
                    year = obj.getInt("year"),
                    assetIds = ids
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
