package com.mathieu.immichwidget.config

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import com.mathieu.immichwidget.sync.SyncWorker
import com.mathieu.immichwidget.widget.PhotoWidgetProvider
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
    private lateinit var btnSave: Button

    private lateinit var prefs: SecurePrefs
    private lateinit var albumAdapter: AlbumListAdapter

    private var selectedAlbum: ImmichAlbum? = null
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

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
        btnSave = findViewById(R.id.btn_save)
    }

    private fun prefillFromPrefs() {
        inputServerUrl.setText(prefs.serverUrl ?: "")
        inputApiKey.setText(prefs.apiKey ?: "")
    }

    private fun setupAlbumList() {
        albumAdapter = AlbumListAdapter { album -> selectedAlbum = album }
        recyclerAlbums.layoutManager = LinearLayoutManager(this)
        recyclerAlbums.adapter = albumAdapter
    }

    private fun setupListeners() {
        btnTestConnection.setOnClickListener { testConnectionAndLoadAlbums() }
        btnSave.setOnClickListener { saveConfigAndSync() }
    }

    private fun currentClient(): ImmichApiClient? {
        val url = inputServerUrl.text?.toString()?.trim().orEmpty()
        val key = inputApiKey.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty()) return null
        return ImmichApiClient(url, key)
    }

    private fun testConnectionAndLoadAlbums() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus.text = getString(R.string.msg_connection_failed, "URL ou API key manquante")
            return
        }

        textConnectionStatus.text = "Test en cours…"
        progressAlbums.visibility = View.VISIBLE

        lifecycleScope.launch {
            val testResult = withContext(Dispatchers.IO) { client.testConnection() }

            if (testResult.isFailure) {
                textConnectionStatus.text = getString(
                    R.string.msg_connection_failed,
                    testResult.exceptionOrNull()?.message ?: "erreur inconnue"
                )
                progressAlbums.visibility = View.GONE
                return@launch
            }

            textConnectionStatus.text = getString(R.string.msg_connection_ok)

            val albumsResult = withContext(Dispatchers.IO) { client.listAlbums() }
            progressAlbums.visibility = View.GONE

            albumsResult.onSuccess { albums ->
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

        // Si on change d'album, le SyncWorker purgera automatiquement les
        // anciennes photos au prochain cycle (delta sync : purgeExcept sur
        // les IDs du nouvel album) — pas besoin de nettoyer ici.
        textSyncStatus.text = getString(R.string.msg_sync_in_progress)

        SyncWorker.schedulePeriodic(applicationContext)
        SyncWorker.triggerImmediateSync(applicationContext)

        textSyncStatus.text = getString(R.string.msg_config_saved)

        // Force un refresh du widget d'origine (icône + état) sans attendre le prochain tap
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            WidgetUpdateHelper.showNextRandomPhoto(applicationContext, appWidgetManager, appWidgetId)
        }

        finish()
    }
}
