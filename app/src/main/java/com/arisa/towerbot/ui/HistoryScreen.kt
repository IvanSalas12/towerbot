package com.arisa.towerbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.NumberParser
import com.arisa.towerbot.core.TierPlanner
import com.arisa.towerbot.core.TierStats
import kotlin.math.roundToInt

/** Una línea con lo que dicen los datos de un nivel. */
fun tierSummary(s: TierStats): String {
    if (s.rate == null) return "Nivel ${s.tier}: sin datos"
    val parts = listOfNotNull(
        "${NumberParser.format(s.rate)}/min" + if (s.known) "" else " (pocos datos)",
        "${s.runs} ${if (s.runs == 1) "partida" else "partidas"}, ${s.minutes.roundToInt()} min",
        s.bestWave?.let { "mejor oleada $it" },
        s.medianDeathWave?.let { "suele morir en la $it" },
    )
    return "Nivel ${s.tier}: " + parts.joinToString(" · ")
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val store = TowerBotApp.store
    val runs by store.runsFlow.collectAsState()
    val settings by store.settingsFlow.collectAsState()
    val now = System.currentTimeMillis()
    val today = runs.filter { it.endedAt >= now - 24 * 3_600_000L }

    Column(Modifier.fillMaxSize()) {
        TopBar("Historial", onBack)
        Section("Por nivel (últimos 7 días, pesa más lo reciente)") {
            TierPlanner.stats(runs, 1..settings.maxTier, now).forEach { Text(tierSummary(it)) }
        }
        Section("Últimas 24 horas") {
            Text("${today.size} partidas · ${NumberParser.format(today.sumOf { it.gained ?: 0.0 })} monedas vistas entrar")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(runs.asReversed()) { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val from = r.startWave?.takeIf { it > 1 }?.let { " (desde la $it)" } ?: ""
                    Text(
                        "${formatTime(r.startedAt)} · " + (r.tier?.let { "Nivel $it · " } ?: "Nivel ? · ") +
                            "oleada ${r.wave ?: "?"}$from",
                    )
                    Text(
                        "${r.coins?.let(NumberParser::format) ?: "?"} monedas · " +
                            "${r.coinsPerMinute?.let(NumberParser::format) ?: "?"}/min" +
                            (r.minutes?.let { " en ${it.roundToInt()} min" } ?: "") + " · ${r.purchases} compras",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${r.strategyId} · ${r.endReason}" +
                            (if (r.explore) " · para aprender" else "") +
                            (if (r.counted) " · cuenta en el duelo" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (r.adAttempts > 0) Text("${r.adAttempts} anuncios solicitados", style = MaterialTheme.typography.bodySmall)
                    if (r.cards.isNotEmpty()) Text("Cartas: ${r.cards.joinToString()} · duelo en ${r.learningMetric}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
