package com.mathieu.immichwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class PhotoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NEXT_PHOTO = "com.mathieu.immichwidget.ACTION_NEXT_PHOTO"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            WidgetUpdateHelper.showNextRandomPhoto(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // laisse AppWidgetProvider gérer les actions système standard

        if (intent.action == ACTION_NEXT_PHOTO) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                WidgetUpdateHelper.showNextRandomPhoto(context, appWidgetManager, widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // On ne supprime pas le cache ici : d'autres instances du widget
        // (ou une réinstallation) peuvent encore vouloir les mêmes photos.
        super.onDeleted(context, appWidgetIds)
    }
}
