package com.mathieu.immichwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.cache.SecurePrefs
import com.mathieu.immichwidget.cache.ThumbnailCache
import com.mathieu.immichwidget.config.WidgetConfigActivity

/**
 * Centralise la construction du RemoteViews : on évite de dupliquer cette
 * logique entre le SyncWorker (1ère photo après sync) et le
 * PhotoWidgetProvider (tap = photo suivante).
 */
object WidgetUpdateHelper {

    /** Mémorise, par instance de widget, l'ID de la photo actuellement affichée (évite les répétitions). */
    private val currentAssetIdByWidget = mutableMapOf<Int, String>()

    fun showNextRandomPhoto(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val excludeId = currentAssetIdByWidget[widgetId]
        val file = ThumbnailCache.pickRandom(context, excludeAssetId = excludeId)
        val bitmap = file?.let { BitmapFactory.decodeFile(it.absolutePath) }

        // IMPORTANT : mettre à jour l'ID AVANT de construire les RemoteViews.
        // buildRemoteViews() lit currentAssetIdByWidget pour savoir sur quelle
        // photo le plein écran doit démarrer — si on le fait après, il pointe
        // toujours vers la photo précédente (bug de décalage d'un cran).
        if (file != null && bitmap != null) {
            currentAssetIdByWidget[widgetId] = file.nameWithoutExtension
        }

        val views = buildRemoteViews(context, widgetId)
        val cropMode = SecurePrefs.getInstance(context).cropMode

        if (bitmap != null) {
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
        // Si le cache est vide (pas encore de sync), le widget garde son
        // état initial (fond + icône settings) — rien à afficher pour l'instant.

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    /** Appelé par SyncWorker après la 1ère sync, pour peupler les widgets encore vides. */
    fun updateAllWidgetsIfEmpty(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(context, PhotoWidgetProvider::class.java)
        )
        ids.forEach { widgetId ->
            if (currentAssetIdByWidget[widgetId] == null) {
                showNextRandomPhoto(context, appWidgetManager, widgetId)
            }
        }
    }

    fun buildRemoteViews(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_photo)

        // Tap sur la zone photo (conteneur commun aux 2 ImageView) -> broadcast au provider
        val nextPhotoIntent = Intent(context, PhotoWidgetProvider::class.java).apply {
            action = PhotoWidgetProvider.ACTION_NEXT_PHOTO
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val nextPhotoPendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId, // requestCode unique par widget pour ne pas écraser les PendingIntent entre widgets
            nextPhotoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_click_area, nextPhotoPendingIntent)

        // Tap sur l'icône ⚙️ -> ouvre l'activité de config
        val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val configPendingIntent = PendingIntent.getActivity(
            context,
            widgetId + 100_000, // offset pour ne pas collisionner avec le requestCode ci-dessus
            configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_settings_zone, configPendingIntent)

        // Tap sur l'icône ⛶ -> ouvre le plein écran, en partant de la photo actuellement affichée
        val currentAssetId = currentAssetIdByWidget[widgetId]
        val fullscreenIntent = Intent(context, FullscreenPhotoActivity::class.java).apply {
            putExtra(FullscreenPhotoActivity.EXTRA_CURRENT_ASSET_ID, currentAssetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullscreenPendingIntent = PendingIntent.getActivity(
            context,
            widgetId + 200_000, // offset distinct des autres requestCode
            fullscreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_fullscreen_zone, fullscreenPendingIntent)

        return views
    }
}
