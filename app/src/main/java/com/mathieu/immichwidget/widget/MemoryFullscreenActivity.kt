package com.mathieu.immichwidget.widget

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.cache.MemoryCache
import com.mathieu.immichwidget.cache.SecurePrefs
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Diaporama plein écran des Souvenirs du jour, façon "story" :
 * - barre de progression segmentée SPÉCIFIQUE À L'ANNÉE affichée (pas un
 *   total sur toutes les années) — elle se reconstruit à chaque changement
 *   d'année, avec un nombre de segments = nombre de photos de CETTE année
 * - démarre toujours sur la 1ère photo de l'année demandée, identifiée par
 *   sa valeur (l'année elle-même), pas par une position d'index qui pourrait
 *   décaler entre deux lectures du cache
 * - auto-défilement (durée réglable dans les params, 5s par défaut) à
 *   travers les photos de l'année en cours
 * - bascule auto vers l'année suivante en fin de groupe, avec une NOUVELLE
 *   piste audio tirée au hasard (Free To Use, API publique sans clé)
 * - swipe manuel gauche/droite : change de photo/année SANS toucher au son
 * - fermeture automatique après la dernière année ; croix pour fermer plus tôt
 */
class MemoryFullscreenActivity : BaseFullscreenActivity() {

    companion object {
        const val EXTRA_START_YEAR = "extra_start_year"
        private const val SEGMENT_GAP_DP = 4
    }

    private data class CachedGroup(val year: Int, val assetIds: List<String>)

    private lateinit var imageView: ImageView
    private lateinit var backgroundImageView: ImageView
    private lateinit var yearsAgoView: TextView
    private lateinit var dateView: TextView
    private lateinit var locationView: TextView
    private lateinit var muteIcon: ImageView
    private lateinit var segmentsContainer: LinearLayout

    private var groups: List<CachedGroup> = emptyList()
    private var currentGroupIndex = 0
    private var currentPhotoIndex = 0
    private val segmentForegrounds = mutableListOf<View>()
    private var segmentAnimator: ValueAnimator? = null
    private var slideDurationMs: Long = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private val autoAdvanceRunnable = Runnable { goToNextPhoto() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_memory_fullscreen)

        imageView = findViewById(R.id.memory_fullscreen_image)
        backgroundImageView = findViewById(R.id.memory_fullscreen_background)
        yearsAgoView = findViewById(R.id.memory_fullscreen_years_ago)
        dateView = findViewById(R.id.memory_fullscreen_date)
        locationView = findViewById(R.id.memory_fullscreen_location)
        muteIcon = findViewById(R.id.memory_fullscreen_mute)
        segmentsContainer = findViewById(R.id.progress_segments_container)
        val closeIcon: ImageView = findViewById(R.id.memory_fullscreen_close)

        initMuteState()
        updateMuteIcon(muteIcon)
        slideDurationMs = SecurePrefs.getInstance(applicationContext).memorySlideDurationSeconds * 1000L

        buildGroups()
        if (groups.isEmpty()) {
            finish()
            return
        }

        // Identifié par la VALEUR de l'année (pas une position d'index) :
        // immunisé contre un éventuel décalage si l'ordre du cache a changé
        // entre le moment où le widget a construit l'intent et maintenant.
        val startYear = intent.getIntExtra(EXTRA_START_YEAR, Int.MIN_VALUE)
        currentGroupIndex = groups.indexOfFirst { it.year == startYear }.let { if (it >= 0) it else 0 }
        currentPhotoIndex = 0 // toujours la 1ère photo de l'année demandée

        buildProgressSegmentsForCurrentGroup()
        showCurrentPhoto(isGroupChange = true) // force le lancement de la 1ère piste
        setupSwipeGestures(imageView, onSwipeLeft = { goToNextPhoto() }, onSwipeRight = { goToPreviousPhoto() })
        excludeSystemGestures(imageView)

        muteIcon.setOnClickListener { toggleMute(muteIcon) }
        closeIcon.setOnClickListener { finish() }
    }

    private fun buildGroups() {
        val loaded = MemoryCache.loadIndex(applicationContext)
        groups = loaded
            .map { group ->
                CachedGroup(
                    year = group.year,
                    assetIds = group.assetIds.filter { MemoryCache.isCached(applicationContext, it) }
                )
            }
            .filter { it.assetIds.isNotEmpty() }
    }

    private fun currentGroup(): CachedGroup = groups[currentGroupIndex]

    /** Reconstruit la barre avec un segment par photo de L'ANNÉE EN COURS uniquement. */
    private fun buildProgressSegmentsForCurrentGroup() {
        segmentsContainer.removeAllViews()
        segmentForegrounds.clear()
        val gapPx = (SEGMENT_GAP_DP * resources.displayMetrics.density).toInt()
        val photoCount = currentGroup().assetIds.size

        repeat(photoCount) { index ->
            val segment = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index != photoCount - 1) lp.marginEnd = gapPx
            segment.layoutParams = lp

            val background = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x59FFFFFF) // blanc ~35%, segment "pas encore vu"
            }
            val foreground = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xFFFFFFFF.toInt())
                pivotX = 0f
                scaleX = 0f
            }
            segment.addView(background)
            segment.addView(foreground)
            segmentsContainer.addView(segment)
            segmentForegrounds.add(foreground)
        }
    }

    /** Segments avant l'actuel = pleins, après = vides, l'actuel s'anime sur slideDurationMs. */
    private fun updateProgressSegments() {
        segmentAnimator?.cancel()
        segmentForegrounds.forEachIndexed { index, view ->
            when {
                index < currentPhotoIndex -> view.scaleX = 1f
                index > currentPhotoIndex -> view.scaleX = 0f
                else -> {
                    view.scaleX = 0f
                    segmentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = slideDurationMs
                        addUpdateListener { anim -> view.scaleX = anim.animatedValue as Float }
                        start()
                    }
                }
            }
        }
    }

    private fun goToNextPhoto() {
        val group = currentGroup()
        if (currentPhotoIndex + 1 < group.assetIds.size) {
            currentPhotoIndex++
            showCurrentPhoto(isGroupChange = false)
        } else {
            if (currentGroupIndex + 1 >= groups.size) {
                finish() // dernière année terminée -> fermeture automatique
                return
            }
            currentGroupIndex++
            currentPhotoIndex = 0
            buildProgressSegmentsForCurrentGroup() // nouvelle année -> nouvelle barre, repart de zéro
            showCurrentPhoto(isGroupChange = true)
        }
    }

    private fun goToPreviousPhoto() {
        if (currentPhotoIndex > 0) {
            currentPhotoIndex--
            showCurrentPhoto(isGroupChange = false)
        } else {
            if (currentGroupIndex == 0) return // déjà tout au début
            currentGroupIndex--
            currentPhotoIndex = (groups[currentGroupIndex].assetIds.size - 1).coerceAtLeast(0)
            buildProgressSegmentsForCurrentGroup()
            showCurrentPhoto(isGroupChange = true)
        }
    }

    private fun showCurrentPhoto(isGroupChange: Boolean) {
        val group = currentGroup()
        val assetId = group.assetIds[currentPhotoIndex]
        val file = MemoryCache.filePathFor(applicationContext, assetId)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            backgroundImageView.setImageBitmap(BlurUtils.createBlurredBackground(bitmap))
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val yearsAgo = currentYear - group.year
        yearsAgoView.text = getString(R.string.label_years_ago, yearsAgo)
        dateView.text = formatMemoryDate(yearsAgo)
        loadAndShowLocation(assetId, locationView)

        updateProgressSegments()

        // Nouvelle piste UNIQUEMENT au changement d'année — un swipe à
        // l'intérieur de la même année ne touche jamais à l'audio en cours.
        if (isGroupChange) {
            playNewRandomTrack(muteIcon, chainOnCompletion = false)
        }

        handler.removeCallbacks(autoAdvanceRunnable)
        handler.postDelayed(autoAdvanceRunnable, slideDurationMs)
    }

    /**
     * Un souvenir "il y a X ans" tombe par définition le même jour/mois
     * qu'aujourd'hui — pas besoin de récupérer une date côté API (peu
     * fiable, cf le champ localDateTime abandonné) : on prend la date du
     * jour et on recule juste l'année de X.
     */
    private fun formatMemoryDate(yearsAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -yearsAgo)
        return DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(cal.time)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoAdvanceRunnable)
        segmentAnimator?.cancel()
        super.onDestroy()
    }
}
