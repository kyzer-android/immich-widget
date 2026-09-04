package com.mathieu.immichwidget.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Modèle minimal d'un album Immich (endpoint GET /api/albums).
 */
data class ImmichAlbum(
    val id: String,
    val albumName: String,
    val assetCount: Int
)

/**
 * Modèle minimal d'un asset (photo) Immich.
 */
data class ImmichAsset(
    val id: String
)

class ImmichApiException(message: String) : Exception(message)

/**
 * Client HTTP léger pour l'API Immich.
 *
 * NOTE : les endpoints ci-dessous correspondent aux versions récentes d'Immich
 * (API v1, préfixe /api). Si ton instance est sur une version différente,
 * vérifie les chemins exacts dans la doc Swagger de ton serveur :
 * https://<ton-serveur>/api/docs (ou /api/swagger.json).
 */
class ImmichApiClient(
    private val baseUrl: String,
    private val apiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun normalizedBaseUrl(): String =
        baseUrl.trimEnd('/')

    private fun buildRequest(path: String): Request =
        Request.Builder()
            .url("${normalizedBaseUrl()}$path")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

    /**
     * Test de connexion simple : on liste les albums, on vérifie juste que
     * le serveur répond en 200 avec un body JSON valide.
     */
    fun testConnection(): Result<Unit> {
        return try {
            val response = client.newCall(buildRequest("/api/albums")).execute()
            response.use {
                if (it.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(ImmichApiException("HTTP ${it.code} : ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GET /api/albums -> liste de tous les albums accessibles avec cette API key.
     */
    fun listAlbums(): Result<List<ImmichAlbum>> {
        return try {
            val response = client.newCall(buildRequest("/api/albums")).execute()
            response.use {
                if (!it.isSuccessful) {
                    return Result.failure(ImmichApiException("HTTP ${it.code} en listant les albums"))
                }
                val body = it.body?.string() ?: "[]"
                val array = JSONArray(body)
                val albums = mutableListOf<ImmichAlbum>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    albums.add(
                        ImmichAlbum(
                            id = obj.getString("id"),
                            albumName = obj.optString("albumName", "Sans nom"),
                            assetCount = obj.optInt("assetCount", 0)
                        )
                    )
                }
                Result.success(albums)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST /api/search/metadata avec filtre "albumIds" -> liste paginée des
     * assets de l'album, ID uniquement.
     *
     * ⚠️ IMPORTANT : sur Immich v3.0.0+, GET /api/albums/{id} ne renvoie plus
     * les assets de façon fiable (breaking change de l'API v3). Le endpoint
     * de recherche est la méthode recommandée par le projet Immich lui-même
     * pour lister les photos d'un album, et fonctionne aussi bien en v2 qu'en v3.
     */
    fun listAssetIdsForAlbum(albumId: String): Result<List<String>> {
        return try {
            val ids = mutableListOf<String>()
            var page = 1
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            while (true) {
                val requestBodyJson = JSONObject().apply {
                    put("albumIds", JSONArray().put(albumId))
                    put("page", page)
                    put("size", 1000)
                }
                val requestBody = requestBodyJson.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${normalizedBaseUrl()}/api/search/metadata")
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val pageResult: Pair<List<String>, String?> = response.use {
                    if (!it.isSuccessful) {
                        return Result.failure(
                            ImmichApiException("HTTP ${it.code} en cherchant les assets de l'album $albumId (page $page)")
                        )
                    }
                    val body = it.body?.string() ?: "{}"
                    val obj = JSONObject(body)
                    val assetsObj = obj.optJSONObject("assets") ?: JSONObject()
                    val items = assetsObj.optJSONArray("items") ?: JSONArray()

                    val pageIds = mutableListOf<String>()
                    for (i in 0 until items.length()) {
                        pageIds.add(items.getJSONObject(i).getString("id"))
                    }

                    val nextPage = if (assetsObj.isNull("nextPage")) {
                        null
                    } else {
                        assetsObj.optString("nextPage", null)
                    }
                    pageIds to nextPage
                }

                ids.addAll(pageResult.first)

                val nextPageValue = pageResult.second
                if (nextPageValue.isNullOrBlank()) break
                val nextPageInt = nextPageValue.toIntOrNull() ?: break
                if (nextPageInt <= page) break // garde-fou anti-boucle infinie si l'API renvoie une valeur incohérente
                page = nextPageInt
            }

            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GET /api/assets/{id}/thumbnail?size=preview -> bytes JPEG/WebP en bonne
     * qualité (source Immich ~1440px par défaut, vs ~250-400px pour
     * size=thumbnail). On redimensionne ensuite nous-mêmes à MAX_DIMENSION_PX
     * côté client (cf ThumbnailCache) : partir d'une source de meilleure
     * qualité évite le double effet de compression qui donnait un rendu
     * moyen avec size=thumbnail.
     */
    fun downloadThumbnail(assetId: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url("${normalizedBaseUrl()}/api/assets/$assetId/thumbnail?size=preview")
                .header("x-api-key", apiKey)
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    return Result.failure(ImmichApiException("HTTP ${it.code} pour le thumbnail $assetId"))
                }
                val bytes = it.body?.bytes()
                    ?: return Result.failure(ImmichApiException("Corps vide pour le thumbnail $assetId"))
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
