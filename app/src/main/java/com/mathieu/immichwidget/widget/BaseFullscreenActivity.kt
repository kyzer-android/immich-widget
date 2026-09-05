package com.mathieu.immichwidget.widget

import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.api.FreeToUseApiClient
import com.mathieu.immichwidget.api.ImmichApiClient
import com.mathieu.immichwidget.cache.LocationCache
import com.mathieu.immichwidget.cache.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Base commune à FullscreenPhotoActivity (Album) et MemoryFullscreenActivity :
 * swipe gauche/droite, exclusion des gestes système, et tout le cycle de vie
 * MediaPlayer (lecture/mute/libération). Les deux écrans partagent cet
 * habillage mais divergent sur la navigation (auto-avance + groupes par
 * année pour Memory, liste plate + enchaînement continu pour Album) — ce
 * qui reste dans les sous-classes.
 */
abstract class BaseFullscreenActivity : AppCompatActivity() {

    companion object {
        private const val SWIPE_MIN_DISTANCE_PX = 120
        private const val SWIPE_MIN_VELOCITY = 200
    }

    protected var mediaPlayer: MediaPlayer? = null
    protected var isMuted = false

    protected fun initMuteState() {
        isMuted = SecurePrefs.getInstance(applicationContext).audioMuted
    }

    /**
     * Sur les ROM avec navigation par gestes, un swipe démarrant près du bord
     * de l'écran peut être intercepté par le système pour le geste "retour"
     * avant d'atteindre notre GestureDetector.
     */
    protected fun excludeSystemGestures(target: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            target.post {
                window.decorView.systemGestureExclusionRects = listOf(
                    Rect(0, 0, target.width, target.height)
                )
            }
        }
    }

    protected fun setupSwipeGestures(target: View, onSwipeLeft: () -> Unit, onSwipeRight: () -> Unit) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // CRITIQUE : sans ce override, onDown() renvoie false par défaut,
            // ce qui bloque la transmission des événements suivants du geste
            // (déplacement/relâchement) — onFling ne se déclenche alors jamais.
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
                    if (deltaX < 0) onSwipeLeft() else onSwipeRight()
                    return true
                }
                return false
            }
        })
        target.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    /**
     * Récupère une nouvelle piste Free To Use et la joue.
     * @param chainOnCompletion true = enchaîne automatiquement une nouvelle
     *   piste dès que celle-ci se termine (mode Album) ; false = ne joue
     *   qu'une fois, le déclenchement de la suivante reste à la charge de
     *   l'appelant (mode Memory : nouvelle piste seulement au changement d'année).
     */
    protected fun playNewRandomTrack(muteIconView: ImageView, chainOnCompletion: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FreeToUseApiClient().fetchRandomFreeTrackUrl()
            }
            result.onSuccess { url ->
                releaseMediaPlayer()
                try {
                    val player = MediaPlayer()
                    player.setDataSource(url)
                    val volume = if (isMuted) 0f else 1f
                    player.setVolume(volume, volume)
                    player.setOnPreparedListener { it.start() }
                    if (chainOnCompletion) {
                        player.setOnCompletionListener { playNewRandomTrack(muteIconView, chainOnCompletion = true) }
                    }
                    player.prepareAsync()
                    mediaPlayer = player
                } catch (e: Exception) {
                    // Pas bloquant : la navigation continue même si l'audio échoue
                }
            }
            // En cas d'échec (pas de réseau, API indisponible), on laisse
            // simplement continuer sans son plutôt que de bloquer.
        }
    }

    protected fun toggleMute(muteIconView: ImageView) {
        isMuted = !isMuted
        SecurePrefs.getInstance(applicationContext).audioMuted = isMuted
        val volume = if (isMuted) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
        updateMuteIcon(muteIconView)
    }

    protected fun updateMuteIcon(muteIconView: ImageView) {
        muteIconView.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
    }

    protected fun releaseMediaPlayer() {
        mediaPlayer?.apply {
            setOnCompletionListener(null) // évite un enchaînement fantôme sur le player qu'on relâche
            try {
                stop()
            } catch (e: Exception) {
                // déjà arrêté/relâché, sans conséquence
            }
            release()
        }
        mediaPlayer = null
    }

    /**
     * Charge et affiche "Ville, Pays" pour l'asset donné (récupéré depuis
     * exifInfo via GET /api/assets/{id}, déjà géocodé côté serveur Immich —
     * pas d'API tierce nécessaire). Mis en cache mémoire pour éviter de
     * rappeler l'API en revoyant la même photo dans la session.
     *
     * Le tag posé sur la vue sert de garde-fou anti-race-condition : si
     * l'utilisateur a déjà navigué vers une autre photo au moment où la
     * réponse arrive, on ignore le résultat plutôt que d'afficher une
     * localisation qui ne correspond plus à la photo affichée.
     */
    protected fun loadAndShowLocation(assetId: String, locationView: TextView) {
        locationView.tag = assetId

        val cached = LocationCache.get(assetId)
        if (cached != null) {
            applyLocationText(locationView, assetId, cached)
            return
        }

        locationView.text = ""
        locationView.visibility = View.GONE

        lifecycleScope.launch {
            val prefs = SecurePrefs.getInstance(applicationContext)
            val url = prefs.serverUrl
            val key = prefs.apiKey
            if (url.isNullOrBlank() || key.isNullOrBlank()) return@launch

            val result = withContext(Dispatchers.IO) {
                ImmichApiClient(url, key).getAssetLocation(assetId)
            }
            val location = result.getOrNull() ?: ""
            LocationCache.put(assetId, location)
            applyLocationText(locationView, assetId, location)
        }
    }

    private fun applyLocationText(locationView: TextView, assetId: String, location: String) {
        if (locationView.tag != assetId) return // l'utilisateur a déjà changé de photo entre-temps
        locationView.text = location
        locationView.visibility = if (location.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }
}
