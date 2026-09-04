package com.mathieu.immichwidget.config

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var textConnectionStatus: TextView
    private lateinit var progressAlbums: ProgressBar
    private lateinit var recyclerAlbums: RecyclerView
    private lateinit var textSyncStatus: TextView
    private lateinit var inputIntervalMinutes: EditText
    private lateinit var btnIntervalMinus: Button
    private lateinit var btnIntervalPlus: Button
    private lateinit var btnSave: Button

    private lateinit var prefs: SecurePrefs
    private lateinit var albumAdapter: AlbumListAdapter

    private var selectedAlbum: ImmichAlbum? = null
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    /** true une fois que "Tester la connexion" a réussi -> le bouton devient "Charger les albums". */
    private var connectionVerified = false

    companion object {
        private const val INTERVAL_STEP_MINUTES = 5
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
        textConnectionStatus = findViewById(R.id.text_connection_status)
        progressAlbums = findViewById(R.id.progress_albums)
        recyclerAlbums = findViewById(R.id.recycler_albums)
        textSyncStatus = findViewById(R.id.text_sync_status)
        inputIntervalMinutes = findViewById(R.id.input_interval_minutes)
        btnIntervalMinus = findViewById(R.id.btn_interval_minus)
        btnIntervalPlus = findViewById(R.id.btn_interval_plus)
        btnSave = findViewById(R.id.btn_save)
    }

    private fun prefillFromPrefs() {
        inputServerUrl.setText(prefs.serverUrl ?: "")
        inputApiKey.setText(prefs.apiKey ?: "")
        inputIntervalMinutes.setText(prefs.autoChangeIntervalMinutes.toString())
    }

    private fun setupAlbumList() {
        albumAdapter = AlbumListAdapter { album -> selectedAlbum = album }
        recyclerAlbums.layoutManager = LinearLayoutManager(this)
        recyclerAlbums.adapter = albumAdapter
    }

    private fun setupListeners() {
        // Le bouton fait deux choses différentes selon l'état :
        // pas encore vérifié -> teste la connexion ; déjà vérifié -> (re)charge les albums.
        btnTestConnection.setOnClickListener {
            if (connectionVerified) loadAlbums() else testConnection()
        }

        // Toucher à l'URL ou à la clé invalide la vérification précédente :
        // on revient à l'état "Tester la connexion" tant que ça n'a pas été re-testé.
        val resetOnEdit = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = resetConnectionState()
        }
        inputServerUrl.addTextChangedListener(resetOnEdit)
        inputApiKey.addTextChangedListener(resetOnEdit)

        btnIntervalMinus.setOnClickListener { adjustInterval(-INTERVAL_STEP_MINUTES) }
        btnIntervalPlus.setOnClickListener { adjustInterval(INTERVAL_STEP_MINUTES) }

        btnSave.setOnClickListener { saveConfigAndSync() }
    }

    private fun resetConnectionState() {
        if (!connectionVerified) return
        connectionVerified = false
        btnTestConnection.text = getString(R.string.btn_test_connection)
        textConnectionStatus.text = ""
        albumAdapter.submitList(emptyList(), null)
        selectedAlbum = null
    }

    private fun adjustInterval(deltaMinutes: Int) {
        val current = inputIntervalMinutes.text?.toString()?.toIntOrNull() ?: 0
        val next = (current + deltaMinutes).coerceIn(0, INTERVAL_MAX_MINUTES)
        inputIntervalMinutes.setText(next.toString())
    }

    private fun currentClient(): ImmichApiClient? {
        val url = inputServerUrl.text?.toString()?.trim().orEmpty()
        val key = inputApiKey.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty()) return null
        return ImmichApiClient(url, key)
    }

    /** Étape 1 du bouton : vérifie juste que le serveur répond. */
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
                connectionVerified = true
                btnTestConnection.text = getString(R.string.btn_load_albums)
            }.onFailure { error ->
                textConnectionStatus.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: "erreur inconnue"
                )
            }
        }
    }

    /**
     * Étape 2 du bouton (une fois connectionVerified = true) : charge/recharge
     * la liste des albums. Reste l'action du bouton tant que l'URL/clé ne
     * changent pas, donc réappuyer ici sert aussi de "rafraîchir la liste".
     */
    private fun loadAlbums() {
        val client = currentClient() ?: return

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
        val intervalMinutes = inputIntervalMinutes.text?.toString()?.toIntOrNull()
            ?.coerceIn(0, INTERVAL_MAX_MINUTES) ?: 0

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
        prefs.autoChangeIntervalMinutes = intervalMinutes

        // Si on change d'album, le SyncWorker purgera automatiquement les
        // anciennes photos au prochain cycle (delta sync : purgeExcept sur
        // les IDs du nouvel album) — pas besoin de nettoyer ici.
        textSyncStatus.text = getString(R.string.msg_sync_in_progress)

        SyncWorker.schedulePeriodic(applicationContext)
        SyncWorker.triggerImmediateSync(applicationContext)

        // (Re)programme le changement automatique avec le nouvel intervalle.
        // Si intervalMinutes == 0, ça annule simplement l'alarme existante.
        AutoChangeScheduler.scheduleNext(applicationContext)

        textSyncStatus.text = getString(R.string.msg_config_saved)

        // Force un refresh du widget d'origine (icône + état) sans attendre le prochain tap
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            WidgetUpdateHelper.showNextRandomPhoto(applicationContext, appWidgetManager, appWidgetId)
        }

        finish()
    }
}
