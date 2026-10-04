package com.arisa.towerbot.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Una zona de la pantalla reducida a una rejilla de colores medios.
 *
 * Es la forma de "ver" del bot: no reconoce objetos, sólo compara cómo de
 * parecida es hoy esa zona a como era cuando la calibraste.
 */
@Serializable
class Template(
    val box: Box,
    val cols: Int,
    val rows: Int,
    val rgb: IntArray,
) {
    /**
     * Diferencia media de color con la misma zona de [frame]: 0 = idéntica, 1 = opuesta.
     * Sirve para reconocer pantallas, donde el color importa.
     */
    fun distance(frame: Frame): Double {
        if (box.right > frame.width || box.bottom > frame.height) return 1.0
        return distance(sampleGrid(frame, box, cols, rows))
    }

    fun distance(other: IntArray): Double {
        var sum = 0L
        for (i in rgb.indices) {
            val a = rgb[i]
            val b = other[i]
            sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            sum += abs((a and 0xFF) - (b and 0xFF))
        }
        return sum / (rgb.size * 3.0 * 255.0)
    }

    /**
     * Correlación de forma (luminosidad normalizada) con la misma zona de [frame]:
     * 1 = misma forma aunque cambie el color, ~0 = nada que ver.
     * Sirve para saber qué mejora hay en un botón, que se oscurece o aclara
     * según te alcance el dinero pero conserva su nombre.
     */
    fun similarity(frame: Frame): Double {
        if (box.top < 0 || box.right > frame.width || box.bottom > frame.height) return 0.0
        return correlation(ownLuminance, luminance(sampleGrid(frame, box, cols, rows)))
    }

    private val ownLuminance: DoubleArray by lazy { luminance(rgb) }

    /** La misma plantilla, pero mirando en otro sitio de la pantalla. */
    fun movedTo(other: Box) = Template(other, cols, rows, rgb)

    fun shifted(dy: Int) = movedTo(box.shifted(dy))

    companion object {
        /** Varianza de brillo por celda por debajo de la cual una zona cuenta como lisa (~2 niveles). */
        private const val FLAT_VARIANCE = 4.0

        fun sample(frame: Frame, box: Box, maxCells: Int = 24): Template {
            val b = box.clampTo(frame.width, frame.height)
            val w = max(1, b.width)
            val h = max(1, b.height)
            val cols: Int
            val rows: Int
            if (w >= h) {
                cols = min(maxCells, w)
                rows = max(1, min(h, (cols.toDouble() * h / w).roundToInt()))
            } else {
                rows = min(maxCells, h)
                cols = max(1, min(w, (rows.toDouble() * w / h).roundToInt()))
            }
            return Template(b, cols, rows, sampleGrid(frame, b, cols, rows))
        }

        fun sampleGrid(frame: Frame, box: Box, cols: Int, rows: Int): IntArray {
            val out = IntArray(cols * rows)
            val w = max(1, box.width)
            val h = max(1, box.height)
            for (r in 0 until rows) {
                val y0 = box.top + r * h / rows
                val y1 = max(y0 + 1, box.top + (r + 1) * h / rows)
                for (c in 0 until cols) {
                    val x0 = box.left + c * w / cols
                    val x1 = max(x0 + 1, box.left + (c + 1) * w / cols)
                    out[r * cols + c] = cellAverage(frame, x0, y0, x1, y1)
                }
            }
            return out
        }

        private fun cellAverage(frame: Frame, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            // Como mucho 4x4 muestras por celda: suficiente y barato.
            val stepX = max(1, (x1 - x0) / 4)
            val stepY = max(1, (y1 - y0) / 4)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            var y = y0
            while (y < y1 && y < frame.height) {
                var x = x0
                while (x < x1 && x < frame.width) {
                    val p = frame.argb(x, y)
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n == 0) return 0
            return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }

        private fun luminance(rgb: IntArray) = DoubleArray(rgb.size) {
            val p = rgb[it]
            0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }

        private fun correlation(a: DoubleArray, b: DoubleArray): Double {
            val ma = a.average()
            val mb = b.average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in a.indices) {
                val x = a[i] - ma
                val y = b[i] - mb
                num += x * y
                da += x * x
                db += y * y
            }
            // Una zona "lisa" (sin texto ni bordes) no tiene forma que comparar:
            // dos lisas se parecen si su brillo se parece; lisa contra dibujada, no.
            val flatA = da / a.size < FLAT_VARIANCE
            val flatB = db / b.size < FLAT_VARIANCE
            if (flatA || flatB) return if (flatA && flatB && abs(ma - mb) < 12) 1.0 else 0.0
            return num / sqrt(da * db)
        }
    }
}
