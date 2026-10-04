package com.arisa.towerbot.core

import kotlinx.serialization.Serializable

/** Un punto en píxeles reales de la pantalla del teléfono. */
@Serializable
data class Pt(val x: Int, val y: Int)

/** Un rectángulo en píxeles reales de la pantalla del teléfono. */
@Serializable
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val center: Pt get() = Pt((left + right) / 2, (top + bottom) / 2)

    fun union(other: Box) = Box(
        minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom),
    )

    fun shifted(dy: Int) = Box(left, top + dy, right, bottom + dy)

    fun overlaps(other: Box) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun clampTo(width: Int, height: Int) = Box(
        left.coerceIn(0, width), top.coerceIn(0, height),
        right.coerceIn(0, width), bottom.coerceIn(0, height),
    )

    companion object {
        fun of(a: Pt, b: Pt) = Box(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}

/** Una captura de pantalla vista como píxeles ARGB. */
interface Frame {
    val width: Int
    val height: Int
    fun argb(x: Int, y: Int): Int
}

class ArrayFrame(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : Frame {
    override fun argb(x: Int, y: Int) = pixels[y * width + x]
}
