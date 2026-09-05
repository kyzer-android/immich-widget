package com.mathieu.immichwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class PhotoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NEXT_PHOTO = "com.mathieu.immichwidget.ACTION_NEXT_PHOTO"
        const val ACTION_NEXT_YEAR = "com.mathieu.immichwidget.ACTION_NEXT_YEAR"
        const val ACTION_SET_MODE = "com.mathieu.immichwidget.ACTION_SET_MODE"
        const val EXTRA_MODE = "extra_mode"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            WidgetUpdateHelper.render(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val appWidgetManager = AppWidgetManager.getInstance(context)

        when (intent.action) {
            ACTION_NEXT_PHOTO -> WidgetUpdateHelper.advance(context, appWidgetManager, widgetId)
            ACTION_NEXT_YEAR -> WidgetUpdateHelper.advanceYear(context, appWidgetManager, widgetId)
            ACTION_SET_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: return
                WidgetUpdateHelper.setMode(context, appWidgetManager, widgetId, mode)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // On ne supprime pas le cache ici : d'autres instances du widget
        // (ou une réinstallation) peuvent encore vouloir les mêmes photos.
        super.onDeleted(context, appWidgetIds)
    }
}
