package com.mathieu.immichwidget.widget

import android.graphics.Bitmap

/**
 * Flou "pauvre" mais efficace et sans dépendance : on réduit drastiquement la
 * photo (quelques dizaines de pixels de large), puis on l'affiche en
 * centerCrop plein cadre — le filtrage bilinéaire de l'agrandissement donne
 * naturellement un effet flouté. Alternative à RenderEffect (API 31+ only,
 * incompatible avec notre minSdk 26) ou à RenderScript (déprécié).
 */
object BlurUtils {
    fun createBlurredBackground(source: Bitmap): Bitmap {
        val scale = 0.06f
        val w = (source.width * scale).toInt().coerceAtLeast(8)
        val h = (source.height * scale).toInt().coerceAtLeast(8)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
