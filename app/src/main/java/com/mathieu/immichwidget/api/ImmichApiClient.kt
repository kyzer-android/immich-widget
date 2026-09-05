package com.mathieu.immichwidget.api

import android.util.Log
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
 * Un asset (photo) Immich avec sa date de prise de vue, pour l'affichage en plein écran.
 */
data class ImmichAsset(
    val id: String,
    val date: String?
)

/**
 * Un groupe de souvenirs "il y a X ans" pour une année donnée.
 */
data class ImmichMemory(
    val year: Int,
    val assetIds: List<String>
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

    companion object {
        private const val LOG_TAG = "ImmichApiClient"
    }

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
     * assets de l'album (ID + date de prise de vue).
     *
     * ⚠️ IMPORTANT : sur Immich v3.0.0+, GET /api/albums/{id} ne renvoie plus
     * les assets de façon fiable (breaking change de l'API v3). Le endpoint
     * de recherche est la méthode recommandée par le projet Immich lui-même
     * pour lister les photos d'un album, et fonctionne aussi bien en v2 qu'en v3.
     */
    fun listAssetsForAlbum(albumId: String): Result<List<com.mathieu.immichwidget.cache.OrderedAsset>> {
        return try {
            val assets = mutableListOf<com.mathieu.immichwidget.cache.OrderedAsset>()
            var page = 1
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            while (true) {
                val requestBodyJson = JSONObject().apply {
                    put("albumIds", JSONArray().put(albumId))
                    put("page", page)
                    put("size", 1000)
                    put("order", "asc") // ordre chronologique stable, nécessaire pour la navigation plein écran
                }
                val requestBody = requestBodyJson.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("${normalizedBaseUrl()}/api/search/metadata")
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val pageResult: Pair<List<com.mathieu.immichwidget.cache.OrderedAsset>, String?> = response.use {
                    if (!it.isSuccessful) {
                        return Result.failure(
                            ImmichApiException("HTTP ${it.code} en cherchant les assets de l'album $albumId (page $page)")
                        )
                    }
                    val body = it.body?.string() ?: "{}"
                    val obj = JSONObject(body)
                    val assetsObj = obj.optJSONObject("assets") ?: JSONObject()
                    val items = assetsObj.optJSONArray("items") ?: JSONArray()

                    val pageAssets = mutableListOf<com.mathieu.immichwidget.cache.OrderedAsset>()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val date = item.optString("localDateTime").ifBlank {
                            item.optString("fileCreatedAt").ifBlank { null }
                        }
                        pageAssets.add(com.mathieu.immichwidget.cache.OrderedAsset(item.getString("id"), date))
                    }

                    val nextPage = if (assetsObj.isNull("nextPage")) {
                        null
                    } else {
                        assetsObj.optString("nextPage", null as String?)
                    }
                    pageAssets to nextPage
                }

                assets.addAll(pageResult.first)

                val nextPageValue = pageResult.second
                if (nextPageValue.isNullOrBlank()) break
                val nextPageInt = nextPageValue.toIntOrNull() ?: break
                if (nextPageInt <= page) break // garde-fou anti-boucle infinie si l'API renvoie une valeur incohérente
                page = nextPageInt
            }

            Result.success(assets)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    /**
     * GET /api/memories -> les Souvenirs du jour ("il y a X ans, ce jour-là").
     * Immich calcule déjà tout côté serveur pour la date du jour ; on filtre
     * juste sur type="on_this_day" au cas où d'autres types de souvenirs
     * seraient ajoutés côté serveur à l'avenir (cf discussions Immich sur
     * "best of the month" etc.).
     */
    fun listMemories(): Result<List<ImmichMemory>> {
        return try {
            // Confirmé via Postman : sans ?for=<date>, l'API renvoie TOUT
            // l'historique de souvenirs persistés (des dizaines/centaines
            // d'entrées), pas seulement ceux du jour. La date du jour au
            // format yyyy-MM-dd filtre correctement côté serveur.
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val response = client.newCall(buildRequest("/api/memories?for=$today")).execute()
            response.use {
                if (!it.isSuccessful) {
                    Log.e(LOG_TAG, "GET /api/memories -> HTTP ${it.code}")
                    return Result.failure(ImmichApiException("HTTP ${it.code} en listant les souvenirs"))
                }
                val body = it.body?.string() ?: "[]"
                val array = JSONArray(body)
                Log.d(LOG_TAG, "GET /api/memories -> ${array.length()} entrée(s) brute(s) reçue(s)")
                if (array.length() > 0) {
                    Log.d(LOG_TAG, "JSON brut de la 1ère entrée (pour inspecter les champs dispo) : ${array.getJSONObject(0)}")
                }

                val memories = mutableListOf<ImmichMemory>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val type = obj.optString("type")
                    if (type != "on_this_day") {
                        Log.d(LOG_TAG, "Entrée #$i ignorée : type=\"$type\" (attendu \"on_this_day\")")
                        continue
                    }

                    val data = obj.optJSONObject("data")
                    val year = data?.optInt("year")
                    if (year == null) {
                        Log.w(LOG_TAG, "Entrée #$i ignorée : champ data.year absent ou illisible (data=$data)")
                        continue
                    }

                    val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
                    val assetIds = (0 until assetsArray.length()).map { idx ->
                        assetsArray.getJSONObject(idx).getString("id")
                    }
                    Log.d(LOG_TAG, "Entrée #$i : année=$year, ${assetIds.size} photo(s)")

                    if (assetIds.isNotEmpty()) {
                        memories.add(ImmichMemory(year = year, assetIds = assetIds))
                    } else {
                        Log.w(LOG_TAG, "Entrée #$i (année=$year) ignorée : liste assets vide")
                    }
                }
                // Fusion par année : si Immich renvoie plusieurs entrées pour la
                // même année (observé en pratique), on les regroupe en une seule
                // — sinon le tap sur le widget donnerait l'impression de changer
                // de photo sans jamais changer le badge "il y a X ans".
                val merged = memories
                    .groupBy { it.year }
                    .map { (year, group) -> ImmichMemory(year = year, assetIds = group.flatMap { it.assetIds }) }
                    .sortedByDescending { it.year }

                Log.i(LOG_TAG, "Résultat final : ${merged.size} année(s) — " +
                    merged.joinToString(", ") { "il y a ${it.year} (${it.assetIds.size} photos)" })

                Result.success(merged)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Exception en parsant /api/memories", e)
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

    /**
     * GET /api/assets/{id} -> détail complet de l'asset, dont exifInfo.city
     * et exifInfo.country. Immich géocode déjà côté serveur (pas besoin
     * d'appeler une API de géocodage inverse tierce) — mais cette info n'est
     * disponible que sur l'asset complet, pas sur les listes (search/metadata,
     * memories), d'où un appel dédié par photo, à la demande (pas à la sync).
     */
    fun getAssetLocation(assetId: String): Result<String?> {
        return try {
            val response = client.newCall(buildRequest("/api/assets/$assetId")).execute()
            response.use {
                if (!it.isSuccessful) {
                    return Result.failure(ImmichApiException("HTTP ${it.code} pour l'asset $assetId"))
                }
                val body = it.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val exif = obj.optJSONObject("exifInfo")

                val city = exif?.let { e -> if (e.isNull("city")) null else e.optString("city").ifBlank { null } }
                val country = exif?.let { e -> if (e.isNull("country")) null else e.optString("country").ifBlank { null } }

                val parts = listOfNotNull(city, country)
                Result.success(if (parts.isEmpty()) null else parts.joinToString(", "))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
