package com.arisa.towerbot.core

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class StoreAdLayout(
    val tab: Pt,
    val battleTab: Pt,
    val area: Box,
    /** Sólo el recuadro gratuito de 20 gemas, separado del botón del video. */
    val offer: Template,
    val video: Template,
)

@Serializable
data class OwnedCard(val name: String, val stars: Int, val look: Template)

@Serializable
data class CardLayout(
    val tab: Pt,
    val battleTab: Pt,
    val area: Box,
    val activeTitles: List<Box>,
    val countBox: Box,
    val owned: List<OwnedCard>,
    val initialDeck: List<String>,
    val reviewedAt: Long = 0,
    /** Rectángulos de las cuatro columnas; altura hasta el final del dibujo. */
    val inventoryColumns: List<Box> = emptyList(),
    val tileHeight: Int = 300,
    val iconBottom: Int = 224,
    val starRow: Int = 260,
    val equipped: List<String> = emptyList(),
    /** Espacios que dice el contador «ACTIVO n/N». Sin leerlo aún, los títulos calibrados. */
    val capacity: Int? = null,
) {
    val slots get() = capacity ?: activeTitles.size

    /**
     * Lo que invalida un duelo: cuántas cartas lleva cada mazo. Una carta nueva o con más
     * estrellas es como una mejora del Taller: la retadora se compara con la partida de la
     * campeona justo anterior, así que no hace falta empezar los pares otra vez.
     */
    fun fingerprint() = "/$slots"

    /**
     * [deck] con exactamente [slots] cartas tuyas. Conserva las suyas y rellena con lo que
     * tienes puesto en el juego, el mazo inicial y luego las de más estrellas.
     */
    fun fit(deck: List<String>?): List<String> {
        val mine = owned.map { it.name }.toSet()
        return (deck.orEmpty() + equipped + initialDeck + owned.sortedByDescending { it.stars }.map { it.name })
            .filter { it in mine }.distinct().take(slots)
    }

    /** Desbloqueaste (o el juego enseña) [n] espacios: el mazo inicial pasa a tener [n] cartas. */
    fun withCapacity(n: Int) = copy(capacity = n).let { it.copy(initialDeck = it.fit(initialDeck)) }
}

/** Búsqueda vertical: mantiene la columna y nunca alcanza los botones de compra. */
object BetweenRuns {
    fun locate(frame: Frame, look: Template, area: Box, shape: Double = 0.88): Int? {
        var best = shape
        var bestDy: Int? = null
        fun probe(top: Int) {
            if (top < area.top || top + look.box.height > area.bottom) return
            val dy = top - look.box.top
            val moved = look.shifted(dy)
            val s = moved.similarity(frame)
            if (s >= best && moved.distance(frame) <= 0.13) { best = s; bestDy = dy }
        }
        var top = area.top
        while (top + look.box.height <= area.bottom) { probe(top); top += 6 }
        bestDy?.let { dy -> for (y in look.box.top + dy - 5..look.box.top + dy + 5) probe(y) }
        return bestDy
    }

    fun normalize(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .filter { it.isLetterOrDigit() }

    /** El mismo nombre aunque el lector cambie una letra: «Probabilidad de críitico» es «crítico». */
    fun sameName(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x == y) return true
        if (x.isEmpty() || y.isEmpty()) return false
        return editDistance(x, y) <= maxOf(1, minOf(x.length, y.length) / 10)
    }

    private fun editDistance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) {
                next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            row = next
        }
        return row[b.length]
    }

    suspend fun top(device: Device, area: Box) {
        repeat(2) { device.swipe(Pt(area.center.x, area.top + 30), Pt(area.center.x, area.bottom - 30), 180) }
        delay(800)
    }

    suspend fun down(device: Device, area: Box) {
        device.drag(Pt(area.center.x, area.bottom - 30), Pt(area.center.x, area.bottom - 30 - area.height / 3), 600)
        delay(500)
    }
}
