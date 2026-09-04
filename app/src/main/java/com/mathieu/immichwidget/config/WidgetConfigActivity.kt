package com.mathieu.immichwidget.config

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.api.ImmichAlbum
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.sync.AutoChangeScheduler
import com.mathieu.immichwidget.sync.SyncWorker
import com.mathieu.immichwidget.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var inputServerUrl: TextInputEditText
    private lateinit var inputApiKey: TextInputEditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnLoadAlbums: Button
    private lateinit var textConnectionStatus: TextView
    private lateinit var progressAlbums: ProgressBar
    private lateinit var recyclerAlbums: RecyclerView
    private lateinit var textSyncStatus: TextView
    private lateinit var switchCropMode: SwitchMaterial
    private lateinit var btnClearCache: Button
    private lateinit var textIntervalValue: TextView
    private lateinit var btnIntervalMinus: Button
    private lateinit var btnIntervalPlus: Button
    private lateinit var btnSave: Button

    private lateinit var prefs: SecurePrefs
    private lateinit var albumAdapter: AlbumListAdapter

    private var selectedAlbum: ImmichAlbum? = null
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    /** État interne de l'intervalle en minutes (0 = désactivé) ; la vue n'affiche que le texte formaté. */
    private var currentIntervalMinutes: Int = 0

    companion object {
        private const val INTERVAL_MAX_MINUTES = 1440 // 24h
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        prefs = SecurePrefs.getInstance(applicationContext)
        bindViews()
        prefillFromPrefs()
        setupAlbumList()
        setupListeners()
    }

    private fun bindViews() {
        inputServerUrl = findViewById(R.id.input_server_url)
        inputApiKey = findViewById(R.id.input_api_key)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnLoadAlbums = findViewById(R.id.btn_load_albums)
        textConnectionStatus = findViewById(R.id.text_connection_status)
        progressAlbums = findViewById(R.id.progress_albums)
        recyclerAlbums = findViewById(R.id.recycler_albums)
        textSyncStatus = findViewById(R.id.text_sync_status)
        switchCropMode = findViewById(R.id.switch_crop_mode)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        textIntervalValue = findViewById(R.id.text_interval_value)
        btnIntervalMinus = findViewById(R.id.btn_interval_minus)
        btnIntervalPlus = findViewById(R.id.btn_interval_plus)
        btnSave = findViewById(R.id.btn_save)
    }

    private fun prefillFromPrefs() {
        inputServerUrl.setText(prefs.serverUrl ?: "")
        inputApiKey.setText(prefs.apiKey ?: "")
        switchCropMode.isChecked = prefs.cropMode
        currentIntervalMinutes = prefs.autoChangeIntervalMinutes
        updateIntervalDisplay()
    }

    private fun setupAlbumList() {
        albumAdapter = AlbumListAdapter { album -> selectedAlbum = album }
        recyclerAlbums.layoutManager = LinearLayoutManager(this)
        recyclerAlbums.adapter = albumAdapter
    }

    private fun setupListeners() {
        // 2 boutons distincts : chacun ne fait qu'une seule chose.
        btnTestConnection.setOnClickListener { testConnection() }
        btnLoadAlbums.setOnClickListener { loadAlbums() }

        // Toucher à l'URL ou à la clé invalide la liste d'albums déjà chargée
        // (évite de garder affichée la liste d'un autre serveur/compte par erreur).
        val resetOnEdit = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                textConnectionStatus.text = ""
                albumAdapter.submitList(emptyList(), null)
                selectedAlbum = null
            }
        }
        inputServerUrl.addTextChangedListener(resetOnEdit)
        inputApiKey.addTextChangedListener(resetOnEdit)

        btnIntervalMinus.setOnClickListener { adjustInterval(increase = false) }
        btnIntervalPlus.setOnClickListener { adjustInterval(increase = true) }

        btnSave.setOnClickListener { saveConfigAndSync() }

        btnClearCache.setOnClickListener {
            com.mathieu.immichwidget.cache.ThumbnailCache.clearAll(applicationContext)
            SyncWorker.triggerImmediateSync(applicationContext)
            textSyncStatus.text = getString(R.string.msg_cache_cleared)
        }
    }

    /**
     * Incrément adaptatif : pas de 1 min tant qu'on est sous 10 min (réglage
     * fin pour les petits intervalles), puis pas de 5 min au-delà — dans les
     * deux sens, pour retomber proprement sur 10 en descendant depuis 15.
     */
    private fun adjustInterval(increase: Boolean) {
        val next = if (increase) {
            if (currentIntervalMinutes < 10) currentIntervalMinutes + 1 else currentIntervalMinutes + 5
        } else {
            if (currentIntervalMinutes <= 10) currentIntervalMinutes - 1 else currentIntervalMinutes - 5
        }
        currentIntervalMinutes = next.coerceIn(0, INTERVAL_MAX_MINUTES)
        updateIntervalDisplay()
    }

    /** Formate en "Xh Ymin" au-delà de 60 minutes, plutôt que d'afficher un nombre de minutes à 3 chiffres. */
    private fun updateIntervalDisplay() {
        textIntervalValue.text = when {
            currentIntervalMinutes <= 0 -> getString(R.string.label_interval_disabled)
            currentIntervalMinutes < 60 -> "$currentIntervalMinutes min"
            currentIntervalMinutes % 60 == 0 -> "${currentIntervalMinutes / 60}h"
            else -> "${currentIntervalMinutes / 60}h ${currentIntervalMinutes % 60}min"
        }
    }

    private fun currentClient(): ImmichApiClient? {
        val url = inputServerUrl.text?.toString()?.trim().orEmpty()
        val key = inputApiKey.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty()) return null
        return ImmichApiClient(url, key)
    }

    private fun testConnection() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus.text = getString(R.string.msg_connection_failed, "URL ou API key manquante")
            return
        }

        textConnectionStatus.text = "Test en cours…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.testConnection() }

            result.onSuccess {
                textConnectionStatus.text = getString(R.string.msg_connection_ok)
            }.onFailure { error ->
                textConnectionStatus.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: "erreur inconnue"
                )
            }
        }
    }

    private fun loadAlbums() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus.text = getString(R.string.msg_connection_failed, "URL ou API key manquante")
            return
        }

        progressAlbums.visibility = View.VISIBLE
        textConnectionStatus.text = "Chargement des albums…"

        lifecycleScope.launch {
            val albumsResult = withContext(Dispatchers.IO) { client.listAlbums() }
            progressAlbums.visibility = View.GONE

            albumsResult.onSuccess { albums ->
                textConnectionStatus.text = getString(R.string.msg_connection_ok)
                albumAdapter.submitList(albums, prefs.albumId)
                // Pré-sélection si l'album déjà configuré est toujours dans la liste
                selectedAlbum = albums.find { it.id == prefs.albumId }
            }.onFailure { error ->
                textConnectionStatus.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: "impossible de lister les albums"
                )
            }
        }
    }

    private fun saveConfigAndSync() {
        val url = inputServerUrl.text?.toString()?.trim().orEmpty()
        val key = inputApiKey.text?.toString()?.trim().orEmpty()
        val album = selectedAlbum

        if (url.isEmpty() || key.isEmpty()) {
            textSyncStatus.text = getString(R.string.msg_connection_failed, "URL ou API key manquante")
            return
        }
        if (album == null) {
            textSyncStatus.text = getString(R.string.msg_no_album_selected)
            return
        }

        prefs.serverUrl = url
        prefs.apiKey = key
        prefs.albumId = album.id
        prefs.albumName = album.albumName
        prefs.autoChangeIntervalMinutes = currentIntervalMinutes
        prefs.cropMode = switchCropMode.isChecked

        // Si on change d'album, le SyncWorker purgera automatiquement les
        // anciennes photos au prochain cycle (delta sync : purgeExcept sur
        // les IDs du nouvel album) — pas besoin de nettoyer ici.
        textSyncStatus.text = getString(R.string.msg_sync_in_progress)

        SyncWorker.schedulePeriodic(applicationContext)
        SyncWorker.triggerImmediateSync(applicationContext)

        // (Re)programme le changement automatique avec le nouvel intervalle.
        // Si currentIntervalMinutes == 0, ça annule simplement l'alarme existante.
        AutoChangeScheduler.scheduleNext(applicationContext)

        textSyncStatus.text = getString(R.string.msg_config_saved)

        // Force un refresh du widget d'origine (photo + mode d'affichage) sans attendre le prochain tap
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            WidgetUpdateHelper.showNextRandomPhoto(applicationContext, appWidgetManager, appWidgetId)
        }

        finish()
    }
}
