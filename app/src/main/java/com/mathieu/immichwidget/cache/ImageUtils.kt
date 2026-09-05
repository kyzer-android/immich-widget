package com.mathieu.immichwidget.cache

import android.graphics.Bitmap

object ImageUtils {
    /** Redimensionne pour que max(largeur, hauteur) == maxDimension, ratio conservé, sans recadrage. */
    fun scaleToFit(source: Bitmap, maxDimension: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= maxDimension) return source

        val scale = maxDimension.toFloat() / longSide
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
    }
}
