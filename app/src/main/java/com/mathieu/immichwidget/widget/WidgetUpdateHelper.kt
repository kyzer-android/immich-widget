package com.mathieu.immichwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.cache.MemoryCache
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import com.mathieu.immichwidget.config.WidgetConfigActivity
import java.util.Calendar

/**
 * Centralise la construction du RemoteViews pour les 2 sources possibles
 * (Album / Memory). Le mode actif est un réglage global (pas par widget) :
 * si sourceMode == "BOTH", currentWidgetMode détermine lequel est affiché ;
 * sinon la source configurée s'applique directement, sans toggle visible.
 */
object WidgetUpdateHelper {

    private val currentAlbumAssetIdByWidget = mutableMapOf<Int, String>()
    private val currentMemoryYearIndexByWidget = mutableMapOf<Int, Int>()
    private val currentMemoryPhotoIndexByWidget = mutableMapOf<Int, Int>()

    private fun effectiveMode(context: Context): String {
        val prefs = SecurePrefs.getInstance(context)
        return if (prefs.sourceMode == "BOTH") prefs.currentWidgetMode else prefs.sourceMode
    }

    /** Tap sur la PHOTO : photo aléatoire suivante (Album) ou photo suivante DANS la même année (Memory). */
    fun advance(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        when (effectiveMode(context)) {
            "MEMORY" -> {
                val groups = MemoryCache.loadIndex(context)
                val yearIndex = (currentMemoryYearIndexByWidget[widgetId] ?: 0).coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                val cachedCount = groups.getOrNull(yearIndex)
                    ?.assetIds
                    ?.count { MemoryCache.isCached(context, it) } ?: 0
                if (cachedCount > 0) {
                    val current = currentMemoryPhotoIndexByWidget[widgetId] ?: 0
                    currentMemoryPhotoIndexByWidget[widgetId] = (current + 1) % cachedCount
                }
            }
            else -> {
                // Rien à faire ici : prepareAlbumContent choisit déjà une
                // nouvelle photo aléatoire à chaque appel (cf exclusion de la précédente).
            }
        }
        render(context, appWidgetManager, widgetId)
    }

    /** Tap sur le BADGE "il y a X ans" (Memory uniquement) : passe à l'année suivante, repart à la 1ère photo de cette année. */
    fun advanceYear(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val groupCount = MemoryCache.loadIndex(context).size
        if (groupCount > 0) {
            val current = currentMemoryYearIndexByWidget[widgetId] ?: 0
            currentMemoryYearIndexByWidget[widgetId] = (current + 1) % groupCount
            currentMemoryPhotoIndexByWidget[widgetId] = 0
        }
        render(context, appWidgetManager, widgetId)
    }

    /** Bascule explicite Album <-> Memory via les icônes toggle (seulement si sourceMode == BOTH). */
    fun setMode(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int, mode: String) {
        SecurePrefs.getInstance(context).currentWidgetMode = mode
        render(context, appWidgetManager, widgetId)
    }

    private data class PreparedContent(
        val bitmap: android.graphics.Bitmap?,
        val badgeText: String?
    )

    fun render(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val mode = effectiveMode(context)

        // IMPORTANT : on prépare le contenu (met à jour currentAlbumAssetIdByWidget
        // / currentMemoryYearIndexByWidget) AVANT de construire les RemoteViews.
        // buildRemoteViews() lit cet état pour savoir sur quoi le plein écran
        // doit démarrer — si on le fait après, il pointe toujours vers le
        // contenu précédent (même bug de décalage d'un cran corrigé en V1).
        val prepared = when (mode) {
            "MEMORY" -> prepareMemoryContent(context, widgetId)
            else -> prepareAlbumContent(context, widgetId)
        }

        val views = buildRemoteViews(context, widgetId, mode)
        applyPreparedContent(context, views, prepared)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun prepareAlbumContent(context: Context, widgetId: Int): PreparedContent {
        val excludeId = currentAlbumAssetIdByWidget[widgetId]
        val file = ThumbnailCache.pickRandom(context, excludeAssetId = excludeId)
            ?: return PreparedContent(null, null)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return PreparedContent(null, null)
        currentAlbumAssetIdByWidget[widgetId] = file.nameWithoutExtension
        return PreparedContent(bitmap, badgeText = null)
    }

    private fun prepareMemoryContent(context: Context, widgetId: Int): PreparedContent {
        val groups = MemoryCache.loadIndex(context)
        if (groups.isEmpty()) return PreparedContent(null, null)

        val yearIndex = (currentMemoryYearIndexByWidget[widgetId] ?: 0).coerceIn(0, groups.size - 1)
        currentMemoryYearIndexByWidget[widgetId] = yearIndex
        val group = groups[yearIndex]

        val cachedAssetIds = group.assetIds.filter { MemoryCache.isCached(context, it) }
        if (cachedAssetIds.isEmpty()) return PreparedContent(null, null)

        val photoIndex = (currentMemoryPhotoIndexByWidget[widgetId] ?: 0).coerceIn(0, cachedAssetIds.size - 1)
        currentMemoryPhotoIndexByWidget[widgetId] = photoIndex
        val assetId = cachedAssetIds[photoIndex]

        val file = MemoryCache.filePathFor(context, assetId)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return PreparedContent(null, null)

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val badgeText = context.getString(R.string.label_years_ago, currentYear - group.year)
        return PreparedContent(bitmap, badgeText)
    }

    private fun applyPreparedContent(context: Context, views: RemoteViews, content: PreparedContent) {
        if (content.bitmap != null) {
            applyImageWithCropMode(context, views, content.bitmap)
        }
        if (content.badgeText != null) {
            views.setTextViewText(R.id.widget_memory_badge, content.badgeText)
            views.setViewVisibility(R.id.widget_memory_badge, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_memory_badge, View.GONE)
        }
    }

    private fun applyImageWithCropMode(context: Context, views: RemoteViews, bitmap: android.graphics.Bitmap) {
        val cropMode = SecurePrefs.getInstance(context).cropMode
        // Une seule des deux ImageView reçoit le bitmap et devient visible ;
        // l'autre est masquée. RemoteViews ne permet pas de changer le
        // scaleType d'une vue existante, d'où les 2 vues préparées dans le layout.
        if (cropMode) {
            views.setImageViewBitmap(R.id.widget_image_crop, bitmap)
            views.setViewVisibility(R.id.widget_image_crop, View.VISIBLE)
            views.setViewVisibility(R.id.widget_image_fit, View.GONE)
        } else {
            views.setImageViewBitmap(R.id.widget_image_fit, bitmap)
            views.setViewVisibility(R.id.widget_image_fit, View.VISIBLE)
            views.setViewVisibility(R.id.widget_image_crop, View.GONE)
        }
    }

    /** Appelé par SyncWorker (album) après la 1ère sync, pour peupler les widgets encore vides. */
    fun updateAllWidgetsIfEmpty(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        allWidgetIds(context, appWidgetManager).forEach { widgetId ->
            if (currentAlbumAssetIdByWidget[widgetId] == null && effectiveMode(context) == "ALBUM") {
                render(context, appWidgetManager, widgetId)
            }
        }
    }

    /** Appelé par MemorySyncWorker après une resync : le nouvel index d'années repart de zéro. */
    fun resetMemoryState() {
        currentMemoryYearIndexByWidget.clear()
        currentMemoryPhotoIndexByWidget.clear()
    }

    /** Rafraîchit tous les widgets pour le mode actuellement actif (ex: après resync memory). */
    fun updateAllWidgetsForCurrentMode(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        allWidgetIds(context, appWidgetManager).forEach { widgetId ->
            render(context, appWidgetManager, widgetId)
        }
    }

    private fun allWidgetIds(context: Context, appWidgetManager: AppWidgetManager): IntArray =
        appWidgetManager.getAppWidgetIds(ComponentName(context, PhotoWidgetProvider::class.java))

    fun buildRemoteViews(context: Context, widgetId: Int, mode: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_photo)
        val prefs = SecurePrefs.getInstance(context)

        // Tap sur la zone photo -> broadcast au provider (photo suivante / année suivante)
        val nextIntent = Intent(context, PhotoWidgetProvider::class.java).apply {
            action = PhotoWidgetProvider.ACTION_NEXT_PHOTO
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, widgetId, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_click_area, nextPendingIntent)

        // Icône ⚙️ -> config
        val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val configPendingIntent = PendingIntent.getActivity(
            context, widgetId + 100_000, configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_settings_zone, configPendingIntent)

        // Icône ⛶ -> plein écran, différent selon le mode actif
        if (mode == "MEMORY") {
            val groups = MemoryCache.loadIndex(context)
            val yearIndex = currentMemoryYearIndexByWidget[widgetId] ?: 0
            val startYear = groups.getOrNull(yearIndex)?.year ?: 0
            val fullscreenIntent = Intent(context, MemoryFullscreenActivity::class.java).apply {
                putExtra(MemoryFullscreenActivity.EXTRA_START_YEAR, startYear)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val fullscreenPendingIntent = PendingIntent.getActivity(
                context, widgetId + 200_000, fullscreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_fullscreen_zone, fullscreenPendingIntent)

            // Tap sur le badge "il y a X ans" -> année suivante (distinct du tap photo, qui change juste de photo DANS l'année)
            val nextYearIntent = Intent(context, PhotoWidgetProvider::class.java).apply {
                action = PhotoWidgetProvider.ACTION_NEXT_YEAR
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val nextYearPendingIntent = PendingIntent.getBroadcast(
                context, widgetId + 500_000, nextYearIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_memory_badge, nextYearPendingIntent)
        } else {
            val currentAssetId = currentAlbumAssetIdByWidget[widgetId]
            val fullscreenIntent = Intent(context, FullscreenPhotoActivity::class.java).apply {
                putExtra(FullscreenPhotoActivity.EXTRA_CURRENT_ASSET_ID, currentAssetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val fullscreenPendingIntent = PendingIntent.getActivity(
                context, widgetId + 200_000, fullscreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_fullscreen_zone, fullscreenPendingIntent)
        }

        // Toggle Album/Memory : visible seulement si les deux sources sont activées
        if (prefs.sourceMode == "BOTH") {
            views.setViewVisibility(R.id.widget_mode_toggle_container, View.VISIBLE)

            val albumModeIntent = Intent(context, PhotoWidgetProvider::class.java).apply {
                action = PhotoWidgetProvider.ACTION_SET_MODE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(PhotoWidgetProvider.EXTRA_MODE, "ALBUM")
            }
            views.setOnClickPendingIntent(
                R.id.widget_mode_album_zone,
                PendingIntent.getBroadcast(
                    context, widgetId + 300_000, albumModeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val memoryModeIntent = Intent(context, PhotoWidgetProvider::class.java).apply {
                action = PhotoWidgetProvider.ACTION_SET_MODE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(PhotoWidgetProvider.EXTRA_MODE, "MEMORY")
            }
            views.setOnClickPendingIntent(
                R.id.widget_mode_memory_zone,
                PendingIntent.getBroadcast(
                    context, widgetId + 400_000, memoryModeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            views.setViewVisibility(R.id.widget_mode_toggle_container, View.GONE)
        }

        return views
    }
}
