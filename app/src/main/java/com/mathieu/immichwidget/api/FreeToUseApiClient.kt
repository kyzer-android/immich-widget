package com.mathieu.immichwidget.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client pour l'API publique Free To Use (https://freetouse.com/api,
 * spec: https://api.freetouse.com/v3/openapi.json). Aucune clé requise.
 *
 * On pioche uniquement dans des catégories "douces" (identifiées via Postman
 * sur https://api.freetouse.com/v3/music/categories/all) plutôt que dans tout
 * le catalogue — l'ambiance générale d'un défilement de photos/souvenirs
 * s'y prête mieux qu'un morceau électro/rock au hasard.
 *
 * On ne joue jamais directement l'URL mp3 sans vérifier `is_premium` :
 * les pistes premium peuvent avoir un fichier absent ou restreint, d'où le
 * filtrage explicite plutôt que de faire confiance à une seule piste au hasard.
 */
class FreeToUseApiClient {

    companion object {
        private const val LOG_TAG = "FreeToUseApiClient"

        /** Catégories "douces" identifiées via Postman (Calm, Relaxing, Peaceful, Meditative, Ambient, Chill). */
        private val SOFT_CATEGORY_IDS = listOf(
            "78b17c21-bfb1-90ae-5240-e59d82c5ef3a", // Calm
            "871f1ab8-d0f4-0573-8e22-9e995cf4fd6f", // Relaxing
            "93371cc5-be9f-fe83-a6e6-42735fa551b8", // Peaceful
            "212dbef5-8532-f318-8c4a-f3f2dde20e8a", // Meditative
            "b5bc7541-bdc2-d42a-3986-572fddd29753", // Ambient
            "91ca6cf0-08d4-0e57-e024-2fd0e1413327"  // Chill
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Récupère l'URL mp3 d'une piste douce au hasard, en piochant une
     * catégorie au hasard parmi SOFT_CATEGORY_IDS puis une piste dedans.
     * Si cette catégorie ne renvoie aucune piste exploitable (aucune gratuite,
     * pas de mp3), on retente avec les autres catégories avant d'abandonner —
     * pas de repli sur le catalogue complet pour rester dans l'ambiance douce.
     */
    fun fetchRandomFreeTrackUrl(): Result<String> {
        val shuffledCategories = SOFT_CATEGORY_IDS.shuffled()
        for (categoryId in shuffledCategories) {
            val result = fetchRandomTrackFromCategory(categoryId)
            if (result.isSuccess) return result
        }
        return Result.failure(Exception("Aucune piste douce exploitable trouvée dans les catégories configurées"))
    }

    private fun fetchRandomTrackFromCategory(categoryId: String): Result<String> {
        return try {
            val request = Request.Builder()
                .url("https://api.freetouse.com/v3/music/categories/$categoryId/tracks?order=random&limit=20")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    Log.w(LOG_TAG, "Catégorie $categoryId -> HTTP ${it.code}")
                    return Result.failure(Exception("HTTP ${it.code} Free To Use"))
                }
                val body = it.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val dataArray = obj.optJSONArray("data") ?: JSONArray()

                val candidates = mutableListOf<String>()
                for (i in 0 until dataArray.length()) {
                    val track = dataArray.getJSONObject(i)
                    val isPremium = track.optBoolean("is_premium", true)
                    if (isPremium) continue
                    val mp3Url = track.optJSONObject("files")?.optString("mp3")
                    if (!mp3Url.isNullOrBlank()) candidates.add(mp3Url)
                }

                if (candidates.isEmpty()) {
                    Log.w(LOG_TAG, "Catégorie $categoryId : aucune piste gratuite exploitable")
                    return Result.failure(Exception("Aucune piste gratuite exploitable dans cette catégorie"))
                }
                Result.success(candidates.random())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
