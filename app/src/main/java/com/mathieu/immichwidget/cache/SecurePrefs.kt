package com.mathieu.immichwidget.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stockage chiffré (Android Keystore) pour tout ce qui est sensible :
 * URL du serveur (exposé publiquement), API key Immich, album choisi.
 *
 * Un seul jeu de valeurs pour l'instant (pas de gestion multi-widgets avec
 * des configs différentes) — cohérent avec le scope défini : un widget,
 * un album, une config globale.
 */
class SecurePrefs private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_FILE = "immich_widget_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ALBUM_ID = "album_id"
        private const val KEY_ALBUM_NAME = "album_name"
        private const val KEY_LAST_SYNC_MILLIS = "last_sync_millis"

        @Volatile
        private var instance: SecurePrefs? = null

        fun getInstance(context: Context): SecurePrefs {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): SecurePrefs {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return SecurePrefs(prefs)
        }
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var albumId: String?
        get() = prefs.getString(KEY_ALBUM_ID, null)
        set(value) = prefs.edit().putString(KEY_ALBUM_ID, value).apply()

    var albumName: String?
        get() = prefs.getString(KEY_ALBUM_NAME, null)
        set(value) = prefs.edit().putString(KEY_ALBUM_NAME, value).apply()

    var lastSyncMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_MILLIS, value).apply()

    fun isConfigured(): Boolean =
        !serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && !albumId.isNullOrBlank()

    fun clearAlbumSelection() {
        prefs.edit().remove(KEY_ALBUM_ID).remove(KEY_ALBUM_NAME).apply()
    }
}
