package com.arisa.towerbot.core

import kotlinx.serialization.Serializable

/** Hasta la oleada [untilWave], reparte las compras según [weights]. */
@Serializable
data class Phase(val untilWave: Int, val weights: Map<Upgrade, Double>)

@Serializable
data class Strategy(
    val id: String,
    val phases: List<Phase>,
    val parentId: String? = null,
    val note: String = "",
) {
    fun phaseIndexFor(wave: Int): Int {
        val i = phases.indexOfFirst { wave <= it.untilWave }
        return if (i < 0) phases.lastIndex else i
    }
}

object DefaultStrategy {
    /**
     * El plan de tus PDF, traducido a pesos de compra:
     * economía primero, luego monedas, la transición a Blender y al final supervivencia.
     */
    fun create() = Strategy(
        id = "pdf",
        note = "Plan de los PDF",
        phases = listOf(
            // Oleadas 1-10: Dinero/Oleada es la prioridad.
            Phase(10, mapOf(
                Upgrade.DINERO_OLEADA to 6.0,
                Upgrade.BONUS_DINERO to 3.0,
                Upgrade.MONEDAS_OLEADA to 1.0,
            )),
            // 11-30: Bonus de Dinero > Dinero/Oleada > Monedas/Oleada > Monedas/Muerte.
            Phase(30, mapOf(
                Upgrade.BONUS_DINERO to 4.0,
                Upgrade.DINERO_OLEADA to 3.0,
                Upgrade.MONEDAS_OLEADA to 2.0,
                Upgrade.MONEDAS_MUERTE to 1.0,
            )),
            // 31-60: monetización.
            Phase(60, mapOf(
                Upgrade.MONEDAS_MUERTE to 3.0,
                Upgrade.BONUS_DINERO to 3.0,
                Upgrade.MONEDAS_OLEADA to 2.0,
                Upgrade.UTILIDAD_GRATIS to 1.0,
                Upgrade.ATAQUE_GRATIS to 1.0,
                Upgrade.DINERO_OLEADA to 0.5,
            )),
            // 61-120: Attack Speed > Def% > Knockback > Multishot > Health.
            Phase(120, mapOf(
                Upgrade.VEL_ATAQUE to 5.0,
                Upgrade.DEF_PCT to 4.0,
                Upgrade.KNOCKBACK_PROB to 3.0,
                Upgrade.MULTI_PROB to 2.0,
                Upgrade.SALUD to 2.0,
                Upgrade.FUEGO_RAPIDO_PROB to 1.0,
                Upgrade.MONEDAS_MUERTE to 1.0,
            )),
            // 120+: Salud 50%, Def% 25%, ataque/control 20%, economía 5%.
            Phase(Int.MAX_VALUE, mapOf(
                Upgrade.SALUD to 50.0,
                Upgrade.DEF_PCT to 25.0,
                Upgrade.VEL_ATAQUE to 10.0,
                Upgrade.MULTI_PROB to 5.0,
                Upgrade.FUEGO_RAPIDO_PROB to 5.0,
                Upgrade.MONEDAS_MUERTE to 5.0,
            )),
        ),
    )
}

object Allocator {
    /**
     * Elige la siguiente compra: la mejora que más lejos está de su parte del reparto.
     * Si los pesos dicen 50% Salud y llevas 2 de 10 compras en Salud, toca Salud.
     * Sólo se eligen mejoras de [available] (calibradas y que no estén en espera).
     */
    fun choose(weights: Map<Upgrade, Double>, bought: Map<Upgrade, Int>, available: Set<Upgrade>): Upgrade? {
        val candidates = weights.filter { (u, w) -> w > 0 && u in available }
        if (candidates.isEmpty()) return null
        val total = candidates.values.sum()
        val n = candidates.keys.sumOf { bought[it] ?: 0 } + 1
        return candidates.maxWith(
            compareBy<Map.Entry<Upgrade, Double>> { (u, w) -> w / total * n - (bought[u] ?: 0) }
                .thenBy { it.value },
        ).key
    }
}
