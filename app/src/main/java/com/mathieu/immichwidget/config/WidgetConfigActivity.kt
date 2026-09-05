package com.mathieu.immichwidget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.api.ImmichAlbum
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import com.mathieu.immichwidget.sync.AutoChangeScheduler
import com.mathieu.immichwidget.sync.MemorySyncWorker
import com.mathieu.immichwidget.sync.SyncWorker
import com.mathieu.immichwidget.widget.WidgetUpdateHelper
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var prefs: SecurePrefs
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var currentIntervalMinutes = 0
    private var selectedAlbum: ImmichAlbum? = null
    private var albumAdapter: AlbumListAdapter? = null

    companion object {
        private const val INTERVAL_MAX_MINUTES = 1440
    }

    // ---- Vues de la page Paramètres ----
    private var bannerBatteryWarning: View? = null
    private var btnOpenBatterySettings: Button? = null
    private var inputServerUrl: TextInputEditText? = null
    private var layoutApiKey: TextInputLayout? = null
    private var inputApiKey: TextInputEditText? = null
    private var btnTestConnection: Button? = null
    private var textConnectionStatus: TextView? = null
    private var radioGroupSource: RadioGroup? = null
    private var switchCropMode: SwitchMaterial? = null
    private var textIntervalValue: TextView? = null

    // ---- Vues de la page Album ----
    private var btnLoadAlbums: Button? = null
    private var textCurrentAlbum: TextView? = null
    private var progressAlbums: ProgressBar? = null
    private var recyclerAlbums: RecyclerView? = null
    private var textSyncStatus: TextView? = null
    private var btnClearCache: Button? = null
    private var switchAudioDefaultAlbum: SwitchMaterial? = null

    // ---- Vues de la page Memory ----
    private var btnSyncMemoryNow: Button? = null
    private var textMemorySyncStatus: TextView? = null
    private var switchAudioDefault: SwitchMaterial? = null
    private var textSlideDurationValue: TextView? = null
    private var currentSlideDurationSeconds = 5
    private var textAlbumSlideDurationValue: TextView? = null
    private var currentAlbumSlideDurationSeconds = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        prefs = SecurePrefs.getInstance(applicationContext)

        setupTabs()
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveAll() }
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
    }

    private fun setupTabs() {
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)

        val layouts = listOf(R.layout.page_settings, R.layout.page_album, R.layout.page_memory)
        viewPager.adapter = ConfigPagerAdapter(layouts) { position, view -> onPageBound(position, view) }
        viewPager.offscreenPageLimit = 2 // garde les 3 pages instanciées en même temps

        val titles = listOf(R.string.tab_settings, R.string.tab_album, R.string.tab_memory)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(titles[position])
        }.attach()
    }

    private fun onPageBound(position: Int, view: View) {
        when (position) {
            0 -> bindSettingsPage(view)
            1 -> bindAlbumPage(view)
            2 -> bindMemoryPage(view)
        }
    }

    // ================= PAGE PARAMÈTRES =================

    private fun bindSettingsPage(view: View) {
        bannerBatteryWarning = view.findViewById(R.id.banner_battery_warning)
        btnOpenBatterySettings = view.findViewById(R.id.btn_open_battery_settings)
        inputServerUrl = view.findViewById(R.id.input_server_url)
        layoutApiKey = view.findViewById(R.id.layout_api_key)
        inputApiKey = view.findViewById(R.id.input_api_key)
        btnTestConnection = view.findViewById(R.id.btn_test_connection)
        textConnectionStatus = view.findViewById(R.id.text_connection_status)
        radioGroupSource = view.findViewById(R.id.radio_group_source)
        switchCropMode = view.findViewById(R.id.switch_crop_mode)
        textIntervalValue = view.findViewById(R.id.text_interval_value)
        val btnIntervalMinus = view.findViewById<Button>(R.id.btn_interval_minus)
        val btnIntervalPlus = view.findViewById<Button>(R.id.btn_interval_plus)

        inputServerUrl?.setText(prefs.serverUrl ?: "")
        inputApiKey?.setText(prefs.apiKey ?: "")
        switchCropMode?.isChecked = prefs.cropMode
        currentIntervalMinutes = prefs.autoChangeIntervalMinutes
        updateIntervalDisplay()

        when (prefs.sourceMode) {
            "MEMORY" -> radioGroupSource?.check(R.id.radio_source_memory)
            "BOTH" -> radioGroupSource?.check(R.id.radio_source_both)
            else -> radioGroupSource?.check(R.id.radio_source_album)
        }

        btnTestConnection?.setOnClickListener { testConnection() }
        btnOpenBatterySettings?.setOnClickListener { openBatterySettings() }
        layoutApiKey?.setEndIconOnClickListener { showApiKeyPermissionsInfo() }
        btnIntervalMinus?.setOnClickListener { adjustInterval(increase = false) }
        btnIntervalPlus?.setOnClickListener { adjustInterval(increase = true) }

        checkBatteryOptimization()
    }

    private fun currentSourceMode(): String = when (radioGroupSource?.checkedRadioButtonId) {
        R.id.radio_source_memory -> "MEMORY"
        R.id.radio_source_both -> "BOTH"
        else -> "ALBUM"
    }

    private fun checkBatteryOptimization() {
        val banner = bannerBatteryWarning ?: return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isExempted = powerManager.isIgnoringBatteryOptimizations(packageName)
        banner.visibility = if (isExempted) View.GONE else View.VISIBLE
    }

    private fun openBatterySettings() {
        Toast.makeText(this, R.string.msg_battery_settings_hint, Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showApiKeyPermissionsInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_api_key_info)
            .setMessage(R.string.msg_api_key_info)
            .setPositiveButton(R.string.btn_dialog_ok, null)
            .show()
    }

    private fun adjustInterval(increase: Boolean) {
        val next = if (increase) {
            if (currentIntervalMinutes < 10) currentIntervalMinutes + 1 else currentIntervalMinutes + 5
        } else {
            if (currentIntervalMinutes <= 10) currentIntervalMinutes - 1 else currentIntervalMinutes - 5
        }
        currentIntervalMinutes = next.coerceIn(0, INTERVAL_MAX_MINUTES)
        updateIntervalDisplay()
    }

    private fun updateIntervalDisplay() {
        textIntervalValue?.text = when {
            currentIntervalMinutes <= 0 -> getString(R.string.label_interval_disabled)
            currentIntervalMinutes < 60 -> getString(R.string.format_minutes, currentIntervalMinutes)
            currentIntervalMinutes % 60 == 0 -> getString(R.string.format_hours, currentIntervalMinutes / 60)
            else -> getString(
                R.string.format_hours_minutes,
                currentIntervalMinutes / 60,
                currentIntervalMinutes % 60
            )
        }
    }

    private fun currentClient(): ImmichApiClient? {
        val url = inputServerUrl?.text?.toString()?.trim().orEmpty()
        val key = inputApiKey?.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty()) return null
        return ImmichApiClient(url, key)
    }

    private fun testConnection() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus?.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }
        textConnectionStatus?.text = getString(R.string.msg_testing_connection)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.testConnection() }
            result.onSuccess {
                textConnectionStatus?.text = getString(R.string.msg_connection_ok)
            }.onFailure { error ->
                textConnectionStatus?.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: getString(R.string.msg_unknown_error)
                )
            }
        }
    }

    // ================= PAGE ALBUM =================

    private fun bindAlbumPage(view: View) {
        btnLoadAlbums = view.findViewById(R.id.btn_load_albums)
        textCurrentAlbum = view.findViewById(R.id.text_current_album)
        progressAlbums = view.findViewById(R.id.progress_albums)
        recyclerAlbums = view.findViewById(R.id.recycler_albums)
        textSyncStatus = view.findViewById(R.id.text_sync_status)
        btnClearCache = view.findViewById(R.id.btn_clear_cache)
        switchAudioDefaultAlbum = view.findViewById(R.id.switch_audio_default_album)
        textAlbumSlideDurationValue = view.findViewById(R.id.text_album_slide_duration_value)
        val btnAlbumSlideDurationMinus = view.findViewById<Button>(R.id.btn_album_slide_duration_minus)
        val btnAlbumSlideDurationPlus = view.findViewById<Button>(R.id.btn_album_slide_duration_plus)

        currentAlbumSlideDurationSeconds = prefs.albumSlideDurationSeconds
        updateAlbumSlideDurationDisplay()
        btnAlbumSlideDurationMinus.setOnClickListener { adjustAlbumSlideDuration(-1) }
        btnAlbumSlideDurationPlus.setOnClickListener { adjustAlbumSlideDuration(1) }

        albumAdapter = AlbumListAdapter { album -> selectedAlbum = album }
        recyclerAlbums?.layoutManager = LinearLayoutManager(this)
        recyclerAlbums?.adapter = albumAdapter

        val savedAlbumName = prefs.albumName
        if (!savedAlbumName.isNullOrBlank()) {
            textCurrentAlbum?.text = getString(R.string.label_current_album, savedAlbumName)
            textCurrentAlbum?.visibility = View.VISIBLE
        }

        switchAudioDefaultAlbum?.isChecked = !prefs.audioMuted
        // Même réglage que l'onglet Memory (1 seule pref partagée) : on garde
        // les 2 switches synchronisés visuellement quel que soit celui touché.
        switchAudioDefaultAlbum?.setOnCheckedChangeListener { _, isChecked ->
            switchAudioDefault?.isChecked = isChecked
        }

        btnLoadAlbums?.setOnClickListener { loadAlbums() }
        btnClearCache?.setOnClickListener {
            ThumbnailCache.clearAll(applicationContext)
            SyncWorker.triggerImmediateSync(applicationContext)
            textSyncStatus?.text = getString(R.string.msg_cache_cleared)
        }
    }

    private fun loadAlbums() {
        val client = currentClient()
        if (client == null) {
            textConnectionStatus?.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }

        progressAlbums?.visibility = View.VISIBLE
        textConnectionStatus?.text = getString(R.string.msg_loading_albums)

        lifecycleScope.launch {
            val albumsResult = withContext(Dispatchers.IO) { client.listAlbums() }
            progressAlbums?.visibility = View.GONE

            albumsResult.onSuccess { albums ->
                textConnectionStatus?.text = getString(R.string.msg_connection_ok)
                albumAdapter?.submitList(albums, prefs.albumId)
                selectedAlbum = albums.find { it.id == prefs.albumId }
            }.onFailure { error ->
                textConnectionStatus?.text = getString(
                    R.string.msg_connection_failed,
                    error.message ?: getString(R.string.msg_albums_load_failed)
                )
            }
        }
    }

    // ================= PAGE MEMORY =================

    private fun bindMemoryPage(view: View) {
        btnSyncMemoryNow = view.findViewById(R.id.btn_sync_memory_now)
        textMemorySyncStatus = view.findViewById(R.id.text_memory_sync_status)
        switchAudioDefault = view.findViewById(R.id.switch_audio_default)
        textSlideDurationValue = view.findViewById(R.id.text_slide_duration_value)
        val btnSlideDurationMinus = view.findViewById<Button>(R.id.btn_slide_duration_minus)
        val btnSlideDurationPlus = view.findViewById<Button>(R.id.btn_slide_duration_plus)

        switchAudioDefault?.isChecked = !prefs.audioMuted
        switchAudioDefault?.setOnCheckedChangeListener { _, isChecked ->
            switchAudioDefaultAlbum?.isChecked = isChecked
        }
        currentSlideDurationSeconds = prefs.memorySlideDurationSeconds
        updateSlideDurationDisplay()

        btnSlideDurationMinus.setOnClickListener { adjustSlideDuration(-1) }
        btnSlideDurationPlus.setOnClickListener { adjustSlideDuration(1) }

        btnSyncMemoryNow?.setOnClickListener {
            val url = inputServerUrl?.text?.toString()?.trim().orEmpty()
            val key = inputApiKey?.text?.toString()?.trim().orEmpty()
            if (url.isEmpty() || key.isEmpty()) {
                textMemorySyncStatus?.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
                return@setOnClickListener
            }
            // On sauvegarde l'URL/clé tout de suite : la sync Memory en a besoin immédiatement.
            prefs.serverUrl = url
            prefs.apiKey = key
            MemorySyncWorker.triggerImmediateSync(applicationContext)
            textMemorySyncStatus?.text = getString(R.string.msg_sync_in_progress)
        }
    }

    private fun adjustSlideDuration(deltaSeconds: Int) {
        currentSlideDurationSeconds = (currentSlideDurationSeconds + deltaSeconds).coerceIn(1, 30)
        updateSlideDurationDisplay()
    }

    private fun updateSlideDurationDisplay() {
        textSlideDurationValue?.text = getString(R.string.format_seconds, currentSlideDurationSeconds)
    }

    private fun adjustAlbumSlideDuration(deltaSeconds: Int) {
        currentAlbumSlideDurationSeconds = (currentAlbumSlideDurationSeconds + deltaSeconds).coerceIn(0, 30)
        updateAlbumSlideDurationDisplay()
    }

    private fun updateAlbumSlideDurationDisplay() {
        textAlbumSlideDurationValue?.text = if (currentAlbumSlideDurationSeconds <= 0) {
            getString(R.string.label_interval_disabled)
        } else {
            getString(R.string.format_seconds, currentAlbumSlideDurationSeconds)
        }
    }

    // ================= SAUVEGARDE GLOBALE =================

    private fun saveAll() {
        val url = inputServerUrl?.text?.toString()?.trim().orEmpty()
        val key = inputApiKey?.text?.toString()?.trim().orEmpty()

        if (url.isEmpty() || key.isEmpty()) {
            textConnectionStatus?.text = getString(R.string.msg_connection_failed, getString(R.string.msg_missing_credentials))
            return
        }

        val sourceMode = currentSourceMode()

        // L'album n'est obligatoire que si la source Album est active.
        val albumId = selectedAlbum?.id ?: prefs.albumId
        val albumName = selectedAlbum?.albumName ?: prefs.albumName
        if (sourceMode != "MEMORY" && (albumId.isNullOrBlank() || albumName.isNullOrBlank())) {
            textSyncStatus?.text = getString(R.string.msg_no_album_selected)
            return
        }

        prefs.serverUrl = url
        prefs.apiKey = key
        prefs.sourceMode = sourceMode
        prefs.cropMode = switchCropMode?.isChecked ?: true
        prefs.autoChangeIntervalMinutes = currentIntervalMinutes
        prefs.audioMuted = switchAudioDefault?.isChecked?.not() ?: false
        prefs.memorySlideDurationSeconds = currentSlideDurationSeconds
        prefs.albumSlideDurationSeconds = currentAlbumSlideDurationSeconds
        if (!albumId.isNullOrBlank() && !albumName.isNullOrBlank()) {
            prefs.albumId = albumId
            prefs.albumName = albumName
        }

        // Si sourceMode a changé et ne vaut plus "BOTH", le mode actif du
        // widget doit être forcé à correspondre à l'unique source choisie.
        if (sourceMode != "BOTH") {
            prefs.currentWidgetMode = sourceMode
        }

        AutoChangeScheduler.scheduleNext(applicationContext)

        if (sourceMode != "MEMORY") {
            SyncWorker.schedulePeriodic(applicationContext)
            SyncWorker.triggerImmediateSync(applicationContext)
        }
        // Memory : on programme juste le cycle périodique (idempotent, KEEP).
        // PAS de sync immédiate ici — "Enregistrer" ne doit pas resynchroniser
        // les souvenirs à chaque fois qu'on ajuste un réglage sans rapport
        // (crop, intervalle...). Seul le bouton dédié de l'onglet Memory le fait.
        if (sourceMode != "ALBUM") {
            MemorySyncWorker.schedulePeriodic(applicationContext)
        }

        textSyncStatus?.text = getString(R.string.msg_config_saved)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            WidgetUpdateHelper.render(applicationContext, appWidgetManager, appWidgetId)
        }

        finish()
    }
}
