package com.mathieu.immichwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.mathieu.immichwidget.cache.SecurePrefs

/**
 * Pivote les photos paysage de 90° dans le sens horaire pour qu'elles
 * remplissent l'écran en portrait. Partagé par le widget et les deux plein
 * écrans (Album + Memory), pour ne pas dupliquer la logique.
 *
 * Les photos déjà en portrait (ou carrées) ne sont jamais touchées, et rien
 * n'est fait si l'interrupteur "Pivoter les photos paysage" est désactivé.
 */
object ImageOrientation {

    private const val ROTATION_CLOCKWISE_DEGREES = 90f

    /**
     * Renvoie le bitmap à afficher : une copie pivotée si la photo est en
     * paysage et que le réglage est actif, sinon le bitmap d'origine.
     * L'original est libéré quand une copie pivotée est produite — les
     * appelants doivent donc n'utiliser que le bitmap renvoyé.
     */
    fun applyLandscapeRotation(context: Context, source: Bitmap): Bitmap {
        if (source.width <= source.height) return source
        if (!SecurePrefs.getInstance(context).rotateLandscape) return source

        val matrix = Matrix().apply { postRotate(ROTATION_CLOCKWISE_DEGREES) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }
}
