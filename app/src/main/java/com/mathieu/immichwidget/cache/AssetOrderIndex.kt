package com.mathieu.immichwidget.cache

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Persiste l'ordre chronologique des assets de l'album (tel que renvoyé par
 * l'API avec order=asc), car le système de fichiers Android ne garantit
 * aucun ordre particulier pour listFiles() sur le dossier de cache — sans
 * ça, le swipe plein écran donnerait un ordre à peu près aléatoire.
 */
object AssetOrderIndex {

    private const val FILE_NAME = "asset_order_index.json"

    private fun indexFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(context: Context, orderedAssetIds: List<String>) {
        val array = JSONArray(orderedAssetIds)
        indexFile(context).writeText(array.toString())
    }

    fun load(context: Context): List<String> {
        return try {
            val file = indexFile(context)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
