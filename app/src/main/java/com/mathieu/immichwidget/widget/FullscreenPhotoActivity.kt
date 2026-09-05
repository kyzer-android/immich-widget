package com.mathieu.immichwidget.widget

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.cache.AssetOrderIndex
import com.mathieu.immichwidget.cache.OrderedAsset
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Vue plein écran Album : swipe gauche/droite pour naviguer dans l'ordre
 * chronologique des photos déjà en cache, musique de fond en continu
 * (Free To Use, nouvelle piste dès que la précédente se termine), date
 * exacte de la photo affichée. Défilement automatique optionnel (réglable
 * dans les params, 0 = désactivé = swipe manuel uniquement). Pas de barre
 * de progression (album trop long pour que des segments par photo aient un
 * sens — contrairement à Memory qui a 3-10 photos par jour).
 */
class FullscreenPhotoActivity : BaseFullscreenActivity() {

    companion object {
        const val EXTRA_CURRENT_ASSET_ID = "extra_current_asset_id"
    }

    private lateinit var imageView: ImageView
    private lateinit var backgroundImageView: ImageView
    private lateinit var dateView: TextView
    private lateinit var locationView: TextView
    private lateinit var muteIcon: ImageView
    private lateinit var orderedAssets: List<OrderedAsset>
    private var currentIndex: Int = 0
    private var slideDurationMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val autoAdvanceRunnable = Runnable { advanceAuto() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_fullscreen_photo)
        imageView = findViewById(R.id.fullscreen_image)
        backgroundImageView = findViewById(R.id.fullscreen_background)
        dateView = findViewById(R.id.fullscreen_date)
        locationView = findViewById(R.id.fullscreen_location)
        muteIcon = findViewById(R.id.fullscreen_mute)
        val closeIcon: ImageView = findViewById(R.id.fullscreen_close)

        initMuteState()
        updateMuteIcon(muteIcon)
        slideDurationMs = SecurePrefs.getInstance(applicationContext).albumSlideDurationSeconds * 1000L

        // On ne garde que les entrées encore réellement en cache : l'index peut
        // légèrement dater si une sync a tourné entre-temps.
        // Mélange aléatoire généré une fois à l'ouverture : le swipe navigue
        // ensuite dans CET ordre (stable pendant toute la session), pas un
        // nouveau tirage à chaque swipe.
        orderedAssets = AssetOrderIndex.load(applicationContext)
            .filter { ThumbnailCache.isCached(applicationContext, it.id) }
            .shuffled()

        if (orderedAssets.isEmpty()) {
            finish()
            return
        }

        val startAssetId = intent.getStringExtra(EXTRA_CURRENT_ASSET_ID)
        currentIndex = orderedAssets.indexOfFirst { it.id == startAssetId }.coerceAtLeast(0)

        showCurrentPhoto()
        scheduleAutoAdvance()
        playNewRandomTrack(muteIcon, chainOnCompletion = true) // enchaînement continu
        setupSwipeGestures(imageView, onSwipeLeft = { showNext() }, onSwipeRight = { showPrevious() })
        excludeSystemGestures(imageView)

        muteIcon.setOnClickListener { toggleMute(muteIcon) }
        closeIcon.setOnClickListener { finish() }
    }

    /** Programme la photo suivante si le défilement auto est activé (slideDurationMs > 0). */
    private fun scheduleAutoAdvance() {
        handler.removeCallbacks(autoAdvanceRunnable)
        if (slideDurationMs > 0) {
            handler.postDelayed(autoAdvanceRunnable, slideDurationMs)
        }
    }

    /** Boucle sur la 1ère photo après la dernière — un slideshow ne doit pas juste s'arrêter. */
    private fun advanceAuto() {
        if (orderedAssets.isEmpty()) return
        currentIndex = (currentIndex + 1) % orderedAssets.size
        showCurrentPhoto()
        scheduleAutoAdvance()
    }

    private fun showNext() {
        if (orderedAssets.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(orderedAssets.size - 1)
        showCurrentPhoto()
        scheduleAutoAdvance() // reset le timer : pas de double-avance juste après un swipe manuel
    }

    private fun showPrevious() {
        if (orderedAssets.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        showCurrentPhoto()
        scheduleAutoAdvance()
    }

    private fun showCurrentPhoto() {
        if (orderedAssets.isEmpty()) return
        val asset = orderedAssets[currentIndex]
        val file = File(filesDir, "immich_thumbnails/${asset.id}.webp")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            backgroundImageView.setImageBitmap(BlurUtils.createBlurredBackground(bitmap))
        }
        dateView.text = formatDate(asset.date)
        loadAndShowLocation(asset.id, locationView)
    }

    /** Formate en toutes lettres dans la langue de l'appareil (ex: "5 septembre 2023" / "September 5, 2023"). */
    private fun formatDate(isoDate: String?): String {
        if (isoDate.isNullOrBlank() || isoDate.length < 10) return ""
        return try {
            val datePart = isoDate.substring(0, 10) // "yyyy-MM-dd", on ignore l'heure
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart) ?: return ""
            DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(date)
        } catch (e: Exception) {
            ""
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoAdvanceRunnable)
        super.onDestroy()
    }
}
