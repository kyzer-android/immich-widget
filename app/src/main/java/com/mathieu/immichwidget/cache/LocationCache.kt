package com.mathieu.immichwidget.cache

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Cache persisté des libellés "Ville, Pays" par asset.
 *
 * Rempli PENDANT la synchro (Album et Memory), en parallèle du téléchargement
 * des vignettes — pas à la demande pendant le visionnage. Sans ça, chaque
 * changement de photo en plein écran déclenchait un appel réseau en direct,
 * avec un texte qui disparaît puis réapparaît une fois la réponse arrivée :
 * visuellement, ça clignote à chaque diapositive. En le préchargeant à la
 * synchro, la valeur est déjà connue au moment de l'affichage.
 */
object LocationCache {

    private const val FILE_NAME = "location_cache.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Volatile
    private var memoryCache: MutableMap<String, String>? = null

    private fun loadAll(context: Context): MutableMap<String, String> {
        memoryCache?.let { return it }
        synchronized(this) {
            memoryCache?.let { return it }
            val loaded = try {
                val f = file(context)
                if (!f.exists()) mutableMapOf() else {
                    val obj = JSONObject(f.readText())
                    val map = mutableMapOf<String, String>()
                    obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
                    map
                }
            } catch (e: Exception) {
                mutableMapOf()
            }
            memoryCache = loaded
            return loaded
        }
    }

    private fun persist(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, value) }
        file(context).writeText(obj.toString())
    }

    fun get(context: Context, assetId: String): String? = loadAll(context)[assetId]

    fun put(context: Context, assetId: String, location: String) {
        val map = loadAll(context)
        map[assetId] = location
        persist(context, map)
    }

    /** Écrit plusieurs entrées en une seule fois (sync) : une seule réécriture disque au lieu d'une par photo. */
    fun putAll(context: Context, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val map = loadAll(context)
        map.putAll(entries)
        persist(context, map)
    }

    /** Purge les entrées dont l'asset n'est plus en cache (évite un fichier qui grossit indéfiniment). */
    fun retainOnly(context: Context, assetIds: Set<String>) {
        val map = loadAll(context)
        val changed = map.keys.retainAll(assetIds)
        if (changed) persist(context, map)
    }
}
