package com.mathieu.immichwidget.widget

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.cache.AssetOrderIndex
import com.mathieu.immichwidget.cache.ThumbnailCache
import java.io.File
import kotlin.math.abs

/**
 * Vue plein écran : swipe gauche/droite pour naviguer dans l'ordre
 * chronologique des photos déjà en cache (pas de retéléchargement — on
 * garde les vignettes, comme décidé). Fermeture via le bouton Retour système.
 */
class FullscreenPhotoActivity : Activity() {

    companion object {
        const val EXTRA_CURRENT_ASSET_ID = "extra_current_asset_id"
        private const val SWIPE_MIN_DISTANCE_PX = 120
        private const val SWIPE_MIN_VELOCITY = 200
    }

    private lateinit var imageView: ImageView
    private lateinit var orderedAssetIds: List<String>
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_fullscreen_photo)
        imageView = findViewById(R.id.fullscreen_image)

        // On ne garde que les IDs encore réellement en cache : l'index peut
        // légèrement dater si une sync a tourné entre-temps.
        orderedAssetIds = AssetOrderIndex.load(applicationContext)
            .filter { ThumbnailCache.isCached(applicationContext, it) }

        val startAssetId = intent.getStringExtra(EXTRA_CURRENT_ASSET_ID)
        currentIndex = orderedAssetIds.indexOf(startAssetId).coerceAtLeast(0)

        showCurrentPhoto()
        setupSwipeGestures()
        excludeSystemGestures()
    }

    /**
     * Sur les ROM avec navigation par gestes (dont LineageOS), un swipe
     * démarrant près du bord de l'écran est intercepté par le système pour
     * le geste "retour" AVANT d'atteindre notre GestureDetector — d'où un
     * swipe qui ne fait "rien" en apparence. On exclut toute la zone de
     * l'écran de cette interception système.
     */
    private fun excludeSystemGestures() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            imageView.post {
                window.decorView.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, imageView.width, imageView.height)
                )
            }
        }
    }

    private fun setupSwipeGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // CRITIQUE : sans ce override, onDown() renvoie false par défaut,
            // ce qui fait remonter "non consommé" jusqu'à OnTouchListener pour
            // ACTION_DOWN — la vue ne reçoit alors JAMAIS les événements
            // suivants (déplacement/relâchement), donc onFling ne se déclenche
            // jamais. C'est ce qui bloquait le swipe entièrement.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaX = e2.x - e1.x
                if (abs(deltaX) > SWIPE_MIN_DISTANCE_PX && abs(velocityX) > SWIPE_MIN_VELOCITY) {
                    if (deltaX < 0) showNext() else showPrevious()
                    return true
                }
                return false
            }
        })
        imageView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    private fun showNext() {
        if (orderedAssetIds.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(orderedAssetIds.size - 1)
        showCurrentPhoto()
    }

    private fun showPrevious() {
        if (orderedAssetIds.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        showCurrentPhoto()
    }

    private fun showCurrentPhoto() {
        if (orderedAssetIds.isEmpty()) return
        val assetId = orderedAssetIds[currentIndex]
        val file = File(filesDir, "immich_thumbnails/$assetId.webp")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        }
    }
}
