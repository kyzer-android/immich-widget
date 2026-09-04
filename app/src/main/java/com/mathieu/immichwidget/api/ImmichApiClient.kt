package com.mathieu.immichwidget.api

import okhttp3.OkHttpClient
import okhttp3.Request
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
     * GET /api/albums/{id} -> détail de l'album, notamment la liste "assets"
     * avec l'ID de chaque photo. On ne garde que les IDs, c'est tout ce dont
     * on a besoin pour le cache.
     */
    fun listAssetIdsForAlbum(albumId: String): Result<List<String>> {
        return try {
            val response = client.newCall(buildRequest("/api/albums/$albumId")).execute()
            response.use {
                if (!it.isSuccessful) {
                    return Result.failure(ImmichApiException("HTTP ${it.code} en lisant l'album $albumId"))
                }
                val body = it.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (i in 0 until assetsArray.length()) {
                    ids.add(assetsArray.getJSONObject(i).getString("id"))
                }
                Result.success(ids)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GET /api/assets/{id}/thumbnail?size=thumbnail -> bytes JPEG/WebP du thumbnail.
     * On redimensionne ensuite nous-mêmes en 500x500 côté client (cf ThumbnailCache)
     * pour garder un poids/qualité maîtrisés indépendamment de ce que sert Immich.
     */
    fun downloadThumbnail(assetId: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url("${normalizedBaseUrl()}/api/assets/$assetId/thumbnail?size=thumbnail")
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
