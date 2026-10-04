package com.arisa.towerbot.core

import kotlin.math.pow
import kotlin.math.roundToInt

enum class TierMode(val label: String) {
    AUTO("Automático: juega el nivel que más paga según tus datos"),
    FIXED("Fijo: juega el nivel que tengas elegido"),
}

/**
 * Sigue el contador de monedas de arriba durante la partida, para saber cuántas entran
 * aunque el bot no haya visto empezar la partida.
 *
 * El lector a veces se equivoca, así que solo acepta lecturas creíbles: la primera la
 * confirma con la siguiente, y después el contador solo puede subir, y no de golpe. Si
 * pierde el hilo (por ejemplo, gastaste monedas a mano), guarda lo que llevaba y empieza
 * un tramo nuevo.
 */
class CoinTracker {
    private class Sample(val t: Long, val v: Double)

    private var candidate: Sample? = null
    private var first: Sample? = null
    private var last: Sample? = null
    private var rejects = 0
    private var bankedCoins = 0.0
    private var bankedMs = 0L

    fun add(t: Long, v: Double) {
        val l = last
        val f = first
        if (l == null || f == null) {
            val c = candidate
            if (c != null && v >= c.v && v <= c.v * 1.2 + 5_000) {
                first = c
                last = Sample(t, v)
            } else {
                candidate = Sample(t, v)
            }
            return
        }
        if (v >= l.v && v <= l.v * 1.3 + 20_000) {
            last = Sample(t, v)
            rejects = 0
        } else if (++rejects >= MAX_REJECTS) {
            bankedCoins += l.v - f.v
            bankedMs += l.t - f.t
            first = null
            last = null
            rejects = 0
            candidate = Sample(t, v)
        }
    }

    /** Monedas que vio entrar, o null si aún no tiene dos lecturas fiables. */
    fun gained(): Double? {
        val f = first
        val l = last
        return when {
            f != null && l != null -> bankedCoins + (l.v - f.v)
            bankedMs > 0 -> bankedCoins
            else -> null
        }
    }

    /** Minutos que abarcan esas monedas. */
    fun minutes(): Double? {
        val f = first
        val l = last
        return when {
            f != null && l != null -> (bankedMs + (l.t - f.t)) / 60_000.0
            bankedMs > 0 -> bankedMs / 60_000.0
            else -> null
        }
    }

    private companion object {
        const val MAX_REJECTS = 4
    }
}

/** Lo que dicen tus partidas de un nivel. [rate] pesa más lo reciente, porque tu cuenta mejora. */
data class TierStats(
    val tier: Int,
    val runs: Int,
    val minutes: Double,
    val coins: Double,
    val rate: Double?,
    val bestWave: Int?,
    val medianDeathWave: Int?,
    val lastPlayedAt: Long?,
) {
    /** Hay datos suficientes para fiarse de [rate]. */
    val known get() = rate != null && (minutes >= TierPlanner.MIN_MINUTES || runs >= 3)
}

/** El nivel de la próxima partida y por qué. [explore]: se juega para aprender, no porque sea el mejor. */
data class TierChoice(val tier: Int, val explore: Boolean, val reason: String)

/**
 * Decide en qué nivel jugar con tus datos, sin IA.
 *
 * Juega el nivel que más monedas por minuto te ha dado últimamente. Pero dedica una parte
 * pequeña del tiempo ([exploreShare]) a los demás niveles: tu cuenta mejora cada día con el
 * Taller y el Laboratorio, y el nivel que hoy no compensa puede compensar mañana. Además,
 * así la estrategia de cada nivel también aprende.
 */
object TierPlanner {
    const val MIN_MINUTES = 15.0
    const val HALF_LIFE_HOURS = 48.0
    const val WINDOW_MS = 7 * 24 * 3_600_000L
    const val SHARE_WINDOW_MS = 24 * 3_600_000L

    private fun usable(r: RunRecord, now: Long) =
        r.tier != null && r.gained != null && (r.minutes ?: 0.0) >= 0.5 && r.endedAt >= now - WINDOW_MS

    fun stats(runs: List<RunRecord>, tiers: IntRange, now: Long): List<TierStats> = tiers.map { t ->
        val mine = runs.filter { it.tier == t && it.endedAt >= now - WINDOW_MS }
        val data = mine.filter { usable(it, now) }
        var wCoins = 0.0
        var wMinutes = 0.0
        data.forEach {
            val w = 0.5.pow((now - it.endedAt) / 3_600_000.0 / HALF_LIFE_HOURS)
            wCoins += w * it.gained!!
            wMinutes += w * it.minutes!!
        }
        val deaths = mine.filter { it.endReason == "muerte" }.mapNotNull { it.wave }.sorted()
        TierStats(
            tier = t,
            runs = data.size,
            minutes = data.sumOf { it.minutes!! },
            coins = data.sumOf { it.gained!! },
            rate = if (wMinutes > 0) wCoins / wMinutes else null,
            bestWave = mine.mapNotNull { it.wave }.maxOrNull(),
            medianDeathWave = deaths.getOrNull((deaths.size - 1) / 2),
            lastPlayedAt = mine.maxOfOrNull { it.endedAt },
        )
    }

    /** Qué parte del tiempo de las últimas 24 h se ha ido en explorar. */
    fun exploreShare(runs: List<RunRecord>, now: Long): Double {
        val recent = runs.filter { it.endedAt >= now - SHARE_WINDOW_MS && it.tier != null }
        val total = recent.sumOf { it.minutes ?: 0.0 }
        return if (total <= 0) 0.0 else recent.filter { it.explore }.sumOf { it.minutes ?: 0.0 } / total
    }

    fun choose(runs: List<RunRecord>, now: Long, maxTier: Int, exploreShare: Double, current: Int?): TierChoice {
        val all = stats(runs, 1..maxTier.coerceAtLeast(1), now)
        val best = all.filter { it.known }.maxByOrNull { it.rate!! }
        if (best == null) {
            val t = (current ?: 1).coerceIn(1, maxTier.coerceAtLeast(1))
            return TierChoice(t, explore = false, reason = "Aún no hay datos para comparar: sigo en el Nivel $t hasta tenerlos")
        }
        val summary = all.joinToString(" · ") { s ->
            "N${s.tier} " + if (s.known) "${NumberParser.format(s.rate!!)}/min" else "sin datos"
        }
        val share = exploreShare(runs, now)
        val others = all.filter { it.tier != best.tier }
        if (others.isNotEmpty() && share < exploreShare) {
            // Primero lo que no se conoce; luego lo que hace más tiempo que no se prueba.
            val pick = others.minWith(compareBy<TierStats>({ it.known }, { it.lastPlayedAt ?: 0L }))
            val why = if (!pick.known) "aún no tiene datos suficientes"
            else "su último dato es de hace ${hoursAgo(pick.lastPlayedAt, now)}"
            return TierChoice(
                pick.tier, explore = true,
                reason = "Pruebo el Nivel ${pick.tier}: $why. Exploro el ${pct(share)} del tiempo (tope ${pct(exploreShare)}). $summary",
            )
        }
        return TierChoice(best.tier, explore = false, reason = "Nivel ${best.tier}, el que más paga. $summary")
    }

    private fun pct(v: Double) = "${(v * 100).roundToInt()} %"

    private fun hoursAgo(t: Long?, now: Long): String {
        val h = ((now - (t ?: now)) / 3_600_000.0)
        return if (h < 1) "${(h * 60).roundToInt()} min" else "${h.roundToInt()} h"
    }
}
