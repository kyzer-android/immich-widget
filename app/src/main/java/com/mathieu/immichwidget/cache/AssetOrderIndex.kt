package com.mathieu.immichwidget.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OrderedAsset(val id: String, val date: String?)

/**
 * Persiste l'ordre chronologique des assets de l'album (tel que renvoyé par
 * l'API avec order=asc), car le système de fichiers Android ne garantit
 * aucun ordre particulier pour listFiles() sur le dossier de cache — sans
 * ça, le swipe plein écran donnerait un ordre à peu près aléatoire.
 *
 * Stocke aussi la date de prise de vue de chaque photo (pour la légende du
 * plein écran album) — contrairement au champ équivalent abandonné côté
 * Memory, celui-ci vient directement de l'asset dans l'album (pas d'une
 * agrégation de souvenirs), donc pas de raison de le suspecter peu fiable.
 */
object AssetOrderIndex {

    private const val FILE_NAME = "asset_order_index.json"

    private fun indexFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(context: Context, orderedAssets: List<OrderedAsset>) {
        val array = JSONArray()
        orderedAssets.forEach { asset ->
            val obj = JSONObject()
            obj.put("id", asset.id)
            obj.put("date", asset.date)
            array.put(obj)
        }
        indexFile(context).writeText(array.toString())
    }

    fun load(context: Context): List<OrderedAsset> {
        return try {
            val file = indexFile(context)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                OrderedAsset(
                    id = obj.getString("id"),
                    date = if (obj.isNull("date")) null else obj.optString("date", null as String?)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
