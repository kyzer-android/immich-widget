package com.mathieu.immichwidget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private lateinit var layoutApiKey: com.google.android.material.textfield.TextInputLayout
    private lateinit var inputApiKey: TextInputEditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnLoadAlbums: Button
    private lateinit var textConnectionStatus: TextView
    private lateinit var progressAlbums: ProgressBar
    private lateinit var recyclerAlbums: RecyclerView
    private lateinit var textSyncStatus: TextView
    private lateinit var switchCropMode: SwitchMaterial
    private lateinit var btnClearCache: Button
    private lateinit var textCurrentAlbum: TextView
    private lateinit var textIntervalValue: TextView
    private lateinit var btnIntervalMinus: Button
    private lateinit var btnIntervalPlus: Button
    private lateinit var btnSave: Button
    private lateinit var bannerBatteryWarning: View
    private lateinit var btnOpenBatterySettings: Button

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

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
    }

    /**
     * Vérifie si l'app est exemptée de l'optimisation batterie. Si non, une
     * bannière propose d'ouvrir les paramètres de l'app — on cible
     * ACTION_APPLICATION_DETAILS_SETTINGS (permission libre, conforme Play
     * Store) plutôt que ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (permission
     * restreinte par Google, réservée aux apps avec un usage de fond justifié
     * comme VPN/fitness). Revérifié à chaque onResume : si l'utilisateur va
     * activer l'exemption puis revient, la bannière disparaît automatiquement.
     */
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isExempted = powerManager.isIgnoringBatteryOptimizations(packageName)
        bannerBatteryWarning.visibility = if (isExempted) View.GONE else View.VISIBLE
    }

    private fun bindViews() {
        inputServerUrl = findViewById(R.id.input_server_url)
        layoutApiKey = findViewById(R.id.layout_api_key)
        inputApiKey = findViewById(R.id.input_api_key)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnLoadAlbums = findViewById(R.id.btn_load_albums)
        textConnectionStatus = findViewById(R.id.text_connection_status)
        progressAlbums = findViewById(R.id.progress_albums)
        recyclerAlbums = findViewById(R.id.recycler_albums)
        textSyncStatus = findViewById(R.id.text_sync_status)
        switchCropMode = findViewById(R.id.switch_crop_mode)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        textCurrentAlbum = findViewById(R.id.text_current_album)
        textIntervalValue = findViewById(R.id.text_interval_value)
        btnIntervalMinus = findViewById(R.id.btn_interval_minus)
        btnIntervalPlus = findViewById(R.id.btn_interval_plus)
        btnSave = findViewById(R.id.btn_save)
        bannerBatteryWarning = findViewById(R.id.banner_battery_warning)
        btnOpenBatterySettings = findViewById(R.id.btn_open_battery_settings)
    }

    private fun prefillFromPrefs() {
        inputServerUrl.setText(prefs.serverUrl ?: "")
        inputApiKey.setText(prefs.apiKey ?: "")
        switchCropMode.isChecked = prefs.cropMode
        currentIntervalMinutes = prefs.autoChangeIntervalMinutes
        updateIntervalDisplay()

        val savedAlbumName = prefs.albumName
        if (!savedAlbumName.isNullOrBlank()) {
            textCurrentAlbum.text = getString(R.string.label_current_album, savedAlbumName)
            textCurrentAlbum.visibility = View.VISIBLE
        }
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

        // Toucher à l'URL ou à la clé rend juste le statut de connexion affiché
        // obsolète (on l'efface). On NE touche PLUS à la sélection d'album ni à
        // la liste chargée : une simple correction de faute de frappe ne doit
        // pas obliger à tout recharger et resélectionner l'album.
        val clearStatusOnEdit = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                textConnectionStatus.text = ""
            }
        }
        inputServerUrl.addTextChangedListener(clearStatusOnEdit)
        inputApiKey.addTextChangedListener(clearStatusOnEdit)

        btnIntervalMinus.setOnClickListener { adjustInterval(increase = false) }
        btnIntervalPlus.setOnClickListener { adjustInterval(increase = true) }

        btnSave.setOnClickListener { saveConfigAndSync() }

        btnClearCache.setOnClickListener {
            com.mathieu.immichwidget.cache.ThumbnailCache.clearAll(applicationContext)
            SyncWorker.triggerImmediateSync(applicationContext)
            textSyncStatus.text = getString(R.string.msg_cache_cleared)
        }

        layoutApiKey.setEndIconOnClickListener { showApiKeyPermissionsInfo() }

        btnOpenBatterySettings.setOnClickListener { openBatterySettings() }
    }

    private fun showApiKeyPermissionsInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_api_key_info)
            .setMessage(R.string.msg_api_key_info)
            .setPositiveButton(R.string.btn_dialog_ok, null)
            .show()
    }

    private fun openBatterySettings() {
        android.widget.Toast.makeText(this, R.string.msg_battery_settings_hint, android.widget.Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
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
            textConnectionStatus.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }

        textConnectionStatus.text = getString(R.string.msg_testing_connection)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.testConnection() }

            result.onSuccess {
                textConnectionStatus.text = getString(R.string.msg_connection_ok)
            }.onFailure { error ->
                textConnectionStatus.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: getString(R.string.msg_unknown_error)
                )
            }
        }
    }

    private fun loadAlbums() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }

        progressAlbums.visibility = View.VISIBLE
        textConnectionStatus.text = getString(R.string.msg_loading_albums)

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
                    error.message ?: getString(R.string.msg_albums_load_failed)
                )
            }
        }
    }

    private fun saveConfigAndSync() {
        val url = inputServerUrl.text?.toString()?.trim().orEmpty()
        val key = inputApiKey.text?.toString()?.trim().orEmpty()

        if (url.isEmpty() || key.isEmpty()) {
            textSyncStatus.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }

        // Si l'utilisateur n'a pas rechargé/resélectionné d'album cette
        // session (ex: il modifie juste l'intervalle ou le mode d'affichage),
        // on garde l'album déjà configuré plutôt que d'exiger une resélection.
        val albumId = selectedAlbum?.id ?: prefs.albumId
        val albumName = selectedAlbum?.albumName ?: prefs.albumName

        if (albumId.isNullOrBlank() || albumName.isNullOrBlank()) {
            textSyncStatus.text = getString(R.string.msg_no_album_selected)
            return
        }

        prefs.serverUrl = url
        prefs.apiKey = key
        prefs.albumId = albumId
        prefs.albumName = albumName
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
