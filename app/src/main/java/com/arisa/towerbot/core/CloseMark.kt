package com.arisa.towerbot.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Busca la X de cerrar de un anuncio sin haberla calibrado.
 *
 * Una X son dos diagonales de un mismo color sobre un fondo distinto: entre sus brazos,
 * más allá de sus puntas y en un anillo alrededor no hay nada de ese color. Así no se
 * confunde con una letra «x» dentro de un texto, que tiene vecinas pegadas.
 * Se mide en brillo, y vale tanto una X clara sobre fondo oscuro como al revés.
 */
object CloseMark {
    /** Medio tamaño de las X que se prueban, en píxeles de una pantalla de 1080 de ancho. */
    private val RADII = intArrayOf(10, 13, 16, 20, 24, 28, 33, 38)
    private const val STEP = 2
    /** Lo que tiene que destacar el brazo más apagado sobre el fondo más parecido a él. */
    private const val MIN_CONTRAST = 35.0
    private const val RING_GAP = 20.0
    private const val RING_SHARE = 0.85

    /** Las X de los anuncios vistos estaban en las esquinas de arriba. */
    private const val CORNER_HEIGHT = 0.14
    private const val CORNER_WIDTH = 0.25

    data class Found(val center: Pt, val radius: Int, val contrast: Double)

    /** La X más clara de las esquinas de arriba, o null si no hay ninguna. */
    fun find(frame: Frame): Found? {
        val scale = frame.width / 1080.0
        val h = (frame.height * CORNER_HEIGHT).roundToInt()
        val w = (frame.width * CORNER_WIDTH).roundToInt()
        return listOfNotNull(
            search(frame, Box(0, 0, w, h), scale),
            search(frame, Box(frame.width - w, 0, frame.width, h), scale),
        ).maxByOrNull { it.contrast }
    }

    /** Una X cerca de [p]: comprueba que está donde se calibró antes de pulsarla. */
    fun findNear(frame: Frame, p: Pt, reach: Int = 90): Found? {
        val scale = frame.width / 1080.0
        val r = (reach * scale).roundToInt()
        return search(frame, Box(p.x - r, p.y - r, p.x + r, p.y + r).clampTo(frame.width, frame.height), scale)
    }

    /** [area]: donde puede estar el centro de la X. */
    private fun search(frame: Frame, area: Box, scale: Double): Found? {
        val radii = RADII.map { max(4, (it * scale).roundToInt()) }
        val reach = (radii.max() * 2.0).roundToInt() + 2
        val lum = Lum(frame, Box(area.left - reach, area.top - reach, area.right + reach, area.bottom + reach))
        var best: Found? = null
        for (r in radii) {
            val m = (2.0 * r).roundToInt() + 2
            val ink = inkPoints(r)
            val bg = backgroundPoints(r)
            val ring = ringPoints(r)
            var cy = max(area.top, m)
            while (cy < min(area.bottom, frame.height - m)) {
                var cx = max(area.left, m)
                while (cx < min(area.right, frame.width - m)) {
                    for (polarity in intArrayOf(1, -1)) {
                        val contrast = contrast(lum, cx, cy, polarity, ink, bg, ring) ?: continue
                        if (best == null || contrast > best.contrast) best = Found(Pt(cx, cy), r, contrast)
                    }
                    cx += STEP
                }
                cy += STEP
            }
        }
        return best
    }

    private fun contrast(lum: Lum, cx: Int, cy: Int, polarity: Int, ink: IntArray, bg: IntArray, ring: IntArray): Double? {
        var bgMax = Double.NEGATIVE_INFINITY
        for (i in bg.indices step 2) bgMax = max(bgMax, lum.extreme(cx + bg[i], cy + bg[i + 1], polarity))
        var inkMin = Double.POSITIVE_INFINITY
        for (i in ink.indices step 2) {
            inkMin = min(inkMin, lum.extreme(cx + ink[i], cy + ink[i + 1], polarity))
            if (inkMin - bgMax < MIN_CONTRAST) return null
        }
        var quiet = 0
        for (i in ring.indices step 2) if (lum.extreme(cx + ring[i], cy + ring[i + 1], polarity) < inkMin - RING_GAP) quiet++
        if (quiet < RING_SHARE * ring.size / 2) return null
        return inkMin - bgMax
    }

    /** Puntos de las dos diagonales, de punta a punta. */
    private fun inkPoints(r: Int) = points((0..8).flatMap { k ->
        val t = (-0.85 + 1.7 * k / 8) * r
        listOf(t to t, t to -t)
    })

    /** Entre los brazos (arriba, abajo y a los lados) y más allá de las puntas. */
    private fun backgroundPoints(r: Int) = points(
        listOf(0.6, 0.85, 1.1).flatMap { d -> listOf(d * r to 0.0, -d * r to 0.0, 0.0 to d * r, 0.0 to -d * r) } +
            listOf(1.3 to 1.3, -1.3 to -1.3, 1.3 to -1.3, -1.3 to 1.3).map { (x, y) -> x * r to y * r },
    )

    private fun ringPoints(r: Int) = points((0 until 16).map { k ->
        val a = 2 * PI * k / 16
        1.8 * r * cos(a) to 1.8 * r * sin(a)
    })

    private fun points(list: List<Pair<Double, Double>>) =
        list.flatMap { (x, y) -> listOf(x.roundToInt(), y.roundToInt()) }.toIntArray()

    /**
     * Brillo de una zona con su máximo y mínimo en 3x3 alrededor de cada píxel: un brazo
     * fino o un poco borroso sigue contando aunque el punto caiga a su lado.
     */
    private class Lum(frame: Frame, want: Box) {
        private val box = want.clampTo(frame.width, frame.height)
        private val w = box.width
        private val h = box.height
        private val hi = DoubleArray(w * h)
        private val lo = DoubleArray(w * h)

        init {
            val raw = DoubleArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = frame.argb(box.left + x, box.top + y)
                raw[y * w + x] = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = Double.NEGATIVE_INFINITY
                var b = Double.POSITIVE_INFINITY
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val v = raw[yy * w + xx]
                    a = max(a, v)
                    b = min(b, v)
                }
                hi[y * w + x] = a
                lo[y * w + x] = b
            }
        }

        /** Con [polarity] 1 la X es clara; con -1 es oscura y el brillo se mide al revés. */
        fun extreme(x: Int, y: Int, polarity: Int): Double {
            val i = (y - box.top).coerceIn(0, h - 1) * w + (x - box.left).coerceIn(0, w - 1)
            return if (polarity > 0) hi[i] else -lo[i]
        }
    }
}
