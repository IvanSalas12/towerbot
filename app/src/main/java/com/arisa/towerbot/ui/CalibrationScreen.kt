package com.arisa.towerbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.Tab

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val store = TowerBotApp.store
    val calibration by store.calibrationFlow.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Calibración", onBack)

        Section("Cómo se calibra") {
            listOf(
                "Abre The Tower con la burbuja visible. Cada vez que pulses 📷 se captura lo que hay debajo y se abre aquí para marcarlo.",
                "Inicio: captura la pantalla con el botón BATALLA (con tu nivel ya elegido). Marca con «Ancla» una zona que sólo salga ahí, y con «Toque» el botón BATALLA.",
                "En partida: captura una partida. Ancla = algo fijo que siempre se vea jugando (la barra de pestañas de abajo). Marca también «Oleada» sobre el número de oleada.",
                "Mejoras: para cada pestaña (Ataque, Defensa, Utilidad) abre la pestaña, sube la lista del todo y captura. Marca «Pestaña» sobre su botón, «Título» sobre el título de color del panel y «Mejora» sobre cada botón que quieras que el bot compre. Si alguna sólo se ve bajando la lista, baja del todo, captura y márcala como «abajo».",
                "Fin de partida: deja que la torre muera, captura. Ancla = el título o el botón de reintentar, Toque = REINTENTAR, y «Monedas» sobre las monedas ganadas.",
                "Ventanas emergentes (ofertas, avisos): captura, Ancla en la ventana y Toque en su X.",
                "Comprueba con 🔍 en la burbuja que el bot reconoce cada pantalla.",
            ).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            Text("Juego: ${calibration.gamePackage}", style = MaterialTheme.typography.bodySmall)
        }

        Section("Pantallas") {
            if (calibration.screens.isEmpty()) Text("Ninguna todavía.")
            calibration.screens.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.name == s.role.label) s.name else "${s.name} — ${s.role.label}")
                        val extras = listOfNotNull(
                            "${s.anchors.size} anclas",
                            s.tap?.let { "toque" },
                            s.waveBox?.let { "oleada" },
                            s.coinsBox?.let { "monedas" },
                            s.tierBox?.let { "nivel" },
                            s.homeTap?.let { "INICIO" },
                            s.tierNext?.let { "flechas" },
                        )
                        Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { store.updateCalibration { c -> c.copy(screens = c.screens - s) } }) { Text("Borrar") }
                }
            }
        }

        Section("Pestañas") {
            Tab.entries.forEach { t ->
                val point = calibration.tabs[t]?.let { "botón ✅" } ?: "botón —"
                val header = if (t in calibration.tabHeaders) "título ✅" else "título —"
                Text("${t.label}: $point · $header")
            }
        }

        Section("Mejoras que puede comprar") {
            if (calibration.slots.isEmpty()) Text("Ninguna todavía.")
            Tab.entries.forEach { t ->
                val inTab = calibration.slots.filter { it.tab == t }
                if (inTab.isNotEmpty()) Text(t.label, style = MaterialTheme.typography.labelLarge)
                inTab.forEach { slot ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${slot.upgrade.label} · ${slot.pos.label}", Modifier.weight(1f))
                        TextButton(onClick = { store.updateCalibration { c -> c.copy(slots = c.slots - slot) } }) { Text("Borrar") }
                    }
                }
            }
        }
    }
}
