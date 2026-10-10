package com.arisa.towerbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.Duel
import com.arisa.towerbot.core.LearnerState
import com.arisa.towerbot.core.NumberParser
import com.arisa.towerbot.core.Strategy
import com.arisa.towerbot.core.TierMode
import com.arisa.towerbot.core.TierPlanner
import com.arisa.towerbot.core.Upgrade
import kotlin.math.roundToInt

@Composable
fun StrategyScreen(onBack: () -> Unit) {
    val store = TowerBotApp.store
    val brain by store.brainFlow.collectAsState()
    val runs by store.runsFlow.collectAsState()
    val calibration by store.calibrationFlow.collectAsState()
    val settings by store.settingsFlow.collectAsState()
    val available = calibration.availableUpgrades()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Estrategia", onBack)

        Section("Cómo aprende") {
            Text("Objetivo actual: " + if (settings.waveLearningEnabled) "más oleadas por partida" else "más monedas por minuto", style = MaterialTheme.typography.labelLarge)
            Text(
                "Cada nivel aprende por separado. En cada uno juega la estrategia campeona y una retadora " +
                    "(la campeona con un cambio pequeño), alternándolas. Si sabe dónde suele morir la torre, " +
                    "la mayoría de los cambios van a eso: acabar antes la fase de economía o meter defensa ahí.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Compara cada partida de la retadora con la de la campeona justo anterior y decide en cuanto la " +
                    "diferencia es clara (como pronto a los ${settings.minPairs} pares, como tarde a los ${settings.maxPairs}). " +
                    "Sólo cuentan las partidas que el bot vio enteras.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        calibration.cards?.let { cards ->
            Section("Mis cartas · ${cards.slots} espacios") {
                Text("Mazo inicial: ${cards.initialDeck.joinToString()}", style = MaterialTheme.typography.bodySmall)
                cards.owned.forEach { Text("${it.name} · ${it.stars} ★", style = MaterialTheme.typography.bodySmall) }
                Text("Inventario revisado: ${formatTime(cards.reviewedAt)}. Lee cartas nuevas, estrellas y espacios desbloqueados entre partidas.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Niveles") {
            val mode = if (settings.tierMode == TierMode.AUTO && calibration.canChooseTier()) {
                "Elige el nivel él solo: el que más paga, y un ${(settings.exploreShare * 100).roundToInt()} % del tiempo prueba los demás."
            } else if (settings.tierMode == TierMode.AUTO) {
                "Querría elegir el nivel, pero falta calibrar INICIO y las flechas de nivel: juega el que tengas elegido."
            } else {
                "Juega el nivel que tengas elegido en el juego."
            }
            Text(mode, style = MaterialTheme.typography.bodySmall)
            TierPlanner.stats(runs, 1..settings.maxTier, System.currentTimeMillis()).forEach { Text(tierSummary(it)) }
            if (brain.log.isNotEmpty()) Text("Decisiones", style = MaterialTheme.typography.labelLarge)
            brain.log.takeLast(12).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (brain.tiers.isEmpty()) {
            Section("Campeona") { Text("Todavía no ha jugado ninguna partida: empieza con el plan de los PDF.") }
        }
        brain.tiers.toSortedMap().forEach { (tier, learner) ->
            TierLearner(tier, learner, available, settings.minPairs, settings.maxPairs, if (settings.waveLearningEnabled) "oleadas" else "monedas/min")
        }

        Section("Empezar de cero") {
            Text(
                "Olvida las estrategias aprendidas y vuelve al plan de los PDF en todos los niveles. El historial se queda.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = store::resetLearner) { Text("Volver al plan de los PDF") }
        }
    }
}

@Composable
private fun TierLearner(tier: Int, learner: LearnerState, available: Set<Upgrade>, minPairs: Int, maxPairs: Int, unit: String) {
    Section("Nivel $tier · campeona ${learner.champion.id} (generación ${learner.generation})") {
        PhaseTable(learner.champion, available)
        learner.champion.cards?.let { Text("Cartas campeonas: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall) }
        learner.challenger?.let { ch ->
            Text("Retadora ${ch.id}", style = MaterialTheme.typography.labelLarge)
            Text("Cambio: ${ch.note}", style = MaterialTheme.typography.bodySmall)
            ch.cards?.let { Text("Cartas retadoras: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall) }
            val pairs = learner.championScores.zip(learner.challengerScores)
            if (pairs.isEmpty()) {
                Text("Aún sin pares completos.", style = MaterialTheme.typography.bodySmall)
            } else {
                pairs.forEachIndexed { i, (a, b) ->
                    Text(
                        "Par ${i + 1}: campeona ${NumberParser.format(a)} · retadora ${NumberParser.format(b)} $unit",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val r = Duel.judge(learner.championScores, learner.challengerScores, minPairs, maxPairs, 0.03)
                Text(
                    "Por ahora la retadora va ${pct(r.gain)}" +
                        if (r.pairs >= 2) " (entre ${pct(r.low)} y ${pct(r.high)})" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (learner.log.isNotEmpty()) Text("Diario", style = MaterialTheme.typography.labelLarge)
        learner.log.takeLast(15).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun pct(g: Double) =
    if (g.isInfinite()) "∞" else (if (g >= 0) "+" else "") + (g * 100).roundToInt() + " %"

@Composable
private fun PhaseTable(strategy: Strategy, available: Set<Upgrade>) {
    strategy.phases.forEachIndexed { i, phase ->
        val from = if (i == 0) 1 else strategy.phases[i - 1].untilWave + 1
        val to = if (phase.untilWave == Int.MAX_VALUE) "∞" else phase.untilWave.toString()
        val usable = phase.weights.filter { (u, w) -> w > 0 && u in available }
        val total = usable.values.sum()
        Text("Oleadas $from–$to", style = MaterialTheme.typography.labelLarge)
        if (usable.isEmpty()) {
            Text("  (ninguna de sus mejoras está calibrada)", style = MaterialTheme.typography.bodySmall)
        }
        usable.entries.sortedByDescending { it.value }.forEach { (u, w) ->
            Text("  ${(w / total * 100).roundToInt()} %  ${u.label}", style = MaterialTheme.typography.bodySmall)
        }
        val missing = phase.weights.keys.filter { it !in available }
        if (missing.isNotEmpty()) {
            Text(
                "  sin calibrar: ${missing.joinToString { it.label }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
