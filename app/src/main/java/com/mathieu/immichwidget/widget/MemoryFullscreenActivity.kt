package com.mathieu.immichwidget.widget

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
 * Diaporama plein écran des Souvenirs du jour, façon "story" (barre de
 * progression segmentée façon Immich mobile) :
 * - auto-défilement (durée réglable dans les params, 5s par défaut) à
 *   travers les photos de l'année en cours
 * - bascule auto vers l'année suivante en fin de groupe, avec une NOUVELLE
 *   piste audio tirée au hasard (Free To Use, API publique sans clé)
 * - swipe manuel gauche/droite : change de photo/année SANS toucher au son
 * - fermeture automatique après la dernière année ; croix pour fermer plus tôt
 */
class MemoryFullscreenActivity : BaseFullscreenActivity() {

    companion object {
        const val EXTRA_START_YEAR_INDEX = "extra_start_year_index"
        private const val SEGMENT_GAP_DP = 4
    }

    private data class Slide(val groupIndex: Int, val year: Int, val assetId: String)

    private lateinit var imageView: ImageView
    private lateinit var backgroundImageView: ImageView
    private lateinit var yearsAgoView: TextView
    private lateinit var dateView: TextView
    private lateinit var muteIcon: ImageView
    private lateinit var segmentsContainer: LinearLayout

    private var slides: List<Slide> = emptyList()
    private var currentIndex = 0
    private val segmentForegrounds = mutableListOf<View>()
    private var segmentAnimator: ValueAnimator? = null
    private var slideDurationMs: Long = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private val autoAdvanceRunnable = Runnable { goTo(currentIndex + 1) }

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
        muteIcon = findViewById(R.id.memory_fullscreen_mute)
        segmentsContainer = findViewById(R.id.progress_segments_container)
        val closeIcon: ImageView = findViewById(R.id.memory_fullscreen_close)

        initMuteState()
        updateMuteIcon(muteIcon)
        slideDurationMs = SecurePrefs.getInstance(applicationContext).memorySlideDurationSeconds * 1000L

        buildSlides()
        if (slides.isEmpty()) {
            finish()
            return
        }
        buildProgressSegments()

        val startGroupIndex = intent.getIntExtra(EXTRA_START_YEAR_INDEX, 0)
        currentIndex = slides.indexOfFirst { it.groupIndex == startGroupIndex }
            .let { if (it >= 0) it else 0 }

        showSlide(currentIndex, isGroupChange = true) // force le lancement de la 1ère piste
        setupSwipeGestures(imageView, onSwipeLeft = { goTo(currentIndex + 1) }, onSwipeRight = { goTo(currentIndex - 1) })
        excludeSystemGestures(imageView)

        muteIcon.setOnClickListener { toggleMute(muteIcon) }
        closeIcon.setOnClickListener { finish() }
    }

    /** Aplati les groupes année -> une seule liste de slides, dans l'ordre. */
    private fun buildSlides() {
        val groups = MemoryCache.loadIndex(applicationContext)
        val list = mutableListOf<Slide>()
        groups.forEachIndexed { groupIndex, group ->
            group.assetIds
                .filter { MemoryCache.isCached(applicationContext, it) }
                .forEach { assetId -> list.add(Slide(groupIndex, group.year, assetId)) }
        }
        slides = list
    }

    /** Construit dynamiquement 1 segment par slide (nombre variable selon le jour). */
    private fun buildProgressSegments() {
        segmentsContainer.removeAllViews()
        segmentForegrounds.clear()
        val gapPx = (SEGMENT_GAP_DP * resources.displayMetrics.density).toInt()

        slides.forEachIndexed { index, _ ->
            val segment = android.widget.FrameLayout(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index != slides.lastIndex) lp.marginEnd = gapPx
            segment.layoutParams = lp

            val background = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x59FFFFFF) // blanc ~35%, segment "pas encore vu"
            }
            val foreground = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
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
    private fun updateProgressSegments(activeIndex: Int) {
        segmentAnimator?.cancel()
        segmentForegrounds.forEachIndexed { index, view ->
            when {
                index < activeIndex -> view.scaleX = 1f
                index > activeIndex -> view.scaleX = 0f
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

    private fun goTo(newIndex: Int) {
        if (newIndex < 0) return // déjà au tout début
        if (newIndex >= slides.size) {
            finish() // dernière année terminée -> fermeture automatique
            return
        }
        val groupChanged = slides[newIndex].groupIndex != slides[currentIndex].groupIndex
        currentIndex = newIndex
        showSlide(currentIndex, isGroupChange = groupChanged)
    }

    private fun showSlide(index: Int, isGroupChange: Boolean) {
        val slide = slides[index]
        val file = MemoryCache.filePathFor(applicationContext, slide.assetId)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            backgroundImageView.setImageBitmap(BlurUtils.createBlurredBackground(bitmap))
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val yearsAgo = currentYear - slide.year
        yearsAgoView.text = getString(R.string.label_years_ago, yearsAgo)
        dateView.text = formatMemoryDate(yearsAgo)

        updateProgressSegments(index)

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
