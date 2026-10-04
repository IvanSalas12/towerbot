package com.arisa.towerbot.android

import android.graphics.Bitmap
import com.arisa.towerbot.core.Box
import com.arisa.towerbot.core.NumberReader
import com.arisa.towerbot.core.Shot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Lee letras y números de un trozo de pantalla. Funciona sin internet y viene dentro
 * de la app; sólo se usa para leer la oleada y las monedas, no para decidir nada.
 */
object TextReader : NumberReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(shot: Shot, box: Box): String? {
        val bitmap = (shot as? BitmapShot)?.bitmap ?: return null
        return read(bitmap, box)
    }

    suspend fun read(bitmap: Bitmap, box: Box): String? {
        val b = box.clampTo(bitmap.width, bitmap.height)
        if (b.width < 4 || b.height < 4) return null
        var crop = Bitmap.createBitmap(bitmap, b.left, b.top, b.width, b.height)
        // El lector acierta más con letras grandes.
        if (crop.height < 64) crop = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        return runCatching { recognizer.process(InputImage.fromBitmap(crop, 0)).await().text }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
