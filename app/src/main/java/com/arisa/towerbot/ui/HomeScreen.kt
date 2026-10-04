package com.arisa.towerbot.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arisa.towerbot.android.BotService
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.NumberParser
import com.arisa.towerbot.core.TierPlanner

@Composable
fun HomeScreen(navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val store = TowerBotApp.store
    val service by BotService.instance.collectAsState()
    val status by TowerBotApp.status.collectAsState()
    val calibration by store.calibrationFlow.collectAsState()
    val settings by store.settingsFlow.collectAsState()
    val runs by store.runsFlow.collectAsState()
    val brain by store.brainFlow.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("TowerBot")

        Section("1 · Permiso de accesibilidad") {
            if (service != null) {
                Text("✅ Activo. La burbuja ▶ 📷 🔍 aparece sobre las demás apps.")
            } else {
                Text("Sin él el bot no puede ver ni tocar la pantalla.")
                Text(
                    "Si Android dice «Ajuste restringido»: abre Info de la app › ⋮ › Permitir ajustes restringidos, y vuelve.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Activar")
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("Info de la app") }
                }
            }
        }

        Section("2 · Calibración") {
            val missing = calibration.missing()
            if (missing.isEmpty()) Text("✅ Lista: ${calibration.slots.size} mejoras, ${calibration.screens.size} pantallas.")
            else missing.forEach { Text("• Falta: $it") }
            calibration.recommended().forEach { Text("• Recomendado: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { navigate(Route.CALIBRATION) }) { Text("Calibrar") }
        }

        Section("3 · Jugar") {
            Text(status.message)
            if (status.running) {
                Text(
                    "Pantalla: ${status.screen} · " + (status.tier?.let { "Nivel $it · " } ?: "") +
                        "Oleada ${status.wave} · Compras ${status.runPurchases}",
                )
                Text("Estrategia: ${status.strategyId ?: "—"}")
                Text("Esta sesión: ${status.sessionRuns} ${if (status.sessionRuns == 1) "partida" else "partidas"}, ${NumberParser.format(status.sessionCoins)} monedas")
            }
            val stop = if (settings.stopAtEnabled) "Se para solo a las %02d:%02d.".format(settings.stopHour, settings.stopMinute)
            else "Sin hora de parada."
            Text("$stop Déjalo cargando: la pantalla se queda encendida.", style = MaterialTheme.typography.bodySmall)
            val ready = service != null && calibration.missing().isEmpty()
            if (status.running) {
                Button(onClick = { service?.stopBot() }, modifier = Modifier.fillMaxWidth()) { Text("■ Parar") }
            } else {
                Button(onClick = { service?.startBot() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("▶ Jugar (abre el juego)")
                }
            }
        }

        Section("Lo que ha aprendido") {
            TierPlanner.stats(runs, 1..settings.maxTier, System.currentTimeMillis()).forEach {
                Text(tierSummary(it), style = MaterialTheme.typography.bodySmall)
            }
            brain.tiers.toSortedMap().forEach { (t, l) ->
                Text("Nivel $t: generación ${l.generation}, campeona ${l.champion.id}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Partidas válidas para aprender: ${runs.count { it.counted }} de ${runs.size}", style = MaterialTheme.typography.bodySmall)
            brain.log.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { navigate(Route.STRATEGY) }, Modifier.weight(1f)) { Text("Estrategia") }
            OutlinedButton(onClick = { navigate(Route.HISTORY) }, Modifier.weight(1f)) { Text("Historial") }
            OutlinedButton(onClick = { navigate(Route.SETTINGS) }, Modifier.weight(1f)) { Text("Ajustes") }
        }
    }
}
