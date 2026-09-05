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
        private const val KEY_AUTO_INTERVAL_MINUTES = "auto_interval_minutes"
        private const val KEY_CROP_MODE = "crop_mode"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_CURRENT_WIDGET_MODE = "current_widget_mode"
        private const val KEY_AUDIO_MUTED = "audio_muted"
        private const val KEY_MEMORY_SLIDE_DURATION_SECONDS = "memory_slide_duration_seconds"
        private const val KEY_ALBUM_SLIDE_DURATION_SECONDS = "album_slide_duration_seconds"

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

    /** 0 = désactivé (pas de changement auto, uniquement au tap sur le widget). */
    var autoChangeIntervalMinutes: Int
        get() = prefs.getInt(KEY_AUTO_INTERVAL_MINUTES, 0)
        set(value) = prefs.edit().putInt(KEY_AUTO_INTERVAL_MINUTES, value).apply()

    /** true = image recadrée pour remplir le cadre (comportement d'origine) ; false = image entière visible. */
    var cropMode: Boolean
        get() = prefs.getBoolean(KEY_CROP_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_CROP_MODE, value).apply()

    /** "ALBUM", "MEMORY" ou "BOTH" — quelle(s) source(s) le widget peut afficher. */
    var sourceMode: String
        get() = prefs.getString(KEY_SOURCE_MODE, "ALBUM") ?: "ALBUM"
        set(value) = prefs.edit().putString(KEY_SOURCE_MODE, value).apply()

    /** "ALBUM" ou "MEMORY" — ce que le widget affiche actuellement (pertinent seulement si sourceMode == "BOTH"). */
    var currentWidgetMode: String
        get() = prefs.getString(KEY_CURRENT_WIDGET_MODE, "ALBUM") ?: "ALBUM"
        set(value) = prefs.edit().putString(KEY_CURRENT_WIDGET_MODE, value).apply()

    var audioMuted: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_MUTED, value).apply()

    /** Durée d'affichage de chaque photo en plein écran Memory, en secondes. */
    var memorySlideDurationSeconds: Int
        get() = prefs.getInt(KEY_MEMORY_SLIDE_DURATION_SECONDS, 5)
        set(value) = prefs.edit().putInt(KEY_MEMORY_SLIDE_DURATION_SECONDS, value).apply()

    /** Durée d'affichage de chaque photo en plein écran Album, en secondes. 0 = défilement manuel uniquement (swipe). */
    var albumSlideDurationSeconds: Int
        get() = prefs.getInt(KEY_ALBUM_SLIDE_DURATION_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_ALBUM_SLIDE_DURATION_SECONDS, value).apply()

    fun isConfigured(): Boolean =
        !serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && !albumId.isNullOrBlank()

    fun clearAlbumSelection() {
        prefs.edit().remove(KEY_ALBUM_ID).remove(KEY_ALBUM_NAME).apply()
    }
}
