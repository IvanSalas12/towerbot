package com.arisa.towerbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.BotSettings
import com.arisa.towerbot.core.AdReward
import com.arisa.towerbot.core.ScreenRole
import com.arisa.towerbot.core.TierMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = TowerBotApp.store
    val settings by store.settingsFlow.collectAsState()
    val calibration by store.calibrationFlow.collectAsState()
    val update: ((BotSettings) -> BotSettings) -> Unit = store::updateSettings

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Ajustes", onBack)

        Section("Horario") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Parar a una hora", Modifier.weight(1f))
                Switch(settings.stopAtEnabled, { on -> update { it.copy(stopAtEnabled = on) } })
            }
            if (settings.stopAtEnabled) {
                NumberField("Hora (0-23)", settings.stopHour.toString()) { v ->
                    v.toIntOrNull()?.takeIf { it in 0..23 }?.let { h -> update { it.copy(stopHour = h) } }
                }
                NumberField("Minuto (0-59)", settings.stopMinute.toString()) { v ->
                    v.toIntOrNull()?.takeIf { it in 0..59 }?.let { m -> update { it.copy(stopMinute = m) } }
                }
            }
        }

        Section("Seguridad") {
            NumberField("Pausar si la batería pasa de (°C)", settings.maxBatteryTempC.toString()) { v ->
                v.toFloatOrNull()?.takeIf { it in 30f..60f }?.let { t -> update { it.copy(maxBatteryTempC = t) } }
            }
        }

        Section("Compras") {
            NumberField("Cada cuántos ms intenta comprar", settings.buyIntervalMs.toString()) { v ->
                v.toLongOrNull()?.takeIf { it >= 500 }?.let { ms -> update { it.copy(buyIntervalMs = ms) } }
            }
            NumberField("Compras máximas por intento", settings.maxBuysPerStep.toString()) { v ->
                v.toIntOrNull()?.takeIf { it in 1..20 }?.let { n -> update { it.copy(maxBuysPerStep = n) } }
            }
            Text(
                "Truco: pon el multiplicador de compra del juego en x5 o x10 cuando vayas avanzado; el bot compra igual, pero más rápido.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Anuncios con recompensa") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anuncios para aumentar monedas", Modifier.weight(1f))
                Switch(settings.coinAdsEnabled, { on -> update { it.copy(coinAdsEnabled = on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anuncios para conseguir gemas", Modifier.weight(1f))
                Switch(settings.gemAdsEnabled, { on -> update { it.copy(gemAdsEnabled = on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Revisar 20 gemas en Tienda entre partidas", Modifier.weight(1f))
                Switch(settings.storeGemAdsEnabled, { on -> update { it.copy(storeGemAdsEnabled = on) } })
            }
            Text("Revisa el regalo al terminar cada partida y al iniciar el bot. Ve el video si está disponible y vuelve a jugar. Usa también el interruptor de gemas; la disponibilidad y el reinicio los decide el juego.", style = MaterialTheme.typography.bodySmall)
            if (calibration.storeAd == null) Text("Falta calibrar el regalo gratuito de la Tienda.", style = MaterialTheme.typography.bodySmall)
            Text("Abre los anuncios disponibles, espera al video y cierra sólo las pantallas de final que hayas calibrado. El juego concede la recompensa; TowerBot no compra paquetes.", style = MaterialTheme.typography.bodySmall)
            AdReward.entries.forEach { reward ->
                Text("${reward.label}: " + if (reward in calibration.adButtons) "botón calibrado ✅" else "falta marcar el botón disponible con 📷", style = MaterialTheme.typography.bodySmall)
            }
            if (calibration.screensOf(ScreenRole.AD_CLOSE).none { it.tap != null }) {
                Text("Falta calibrar «Anuncio terminado»: ancla en el botón de cierre listo y Toque en su X. Si cambia el anuncio, añade otra captura.", style = MaterialTheme.typography.bodySmall)
            }
            Text("El tiempo de anuncios cuenta en monedas/min: refleja lo que ganas por tiempo real. Al cambiar estos interruptores se reinician los pares del duelo, conservando las estrategias aprendidas.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Nivel") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Elegir el nivel según los datos", Modifier.weight(1f))
                Switch(
                    settings.tierMode == TierMode.AUTO,
                    { on -> update { it.copy(tierMode = if (on) TierMode.AUTO else TierMode.FIXED) } },
                )
            }
            Text(settings.tierMode.label, style = MaterialTheme.typography.bodySmall)
            if (settings.tierMode == TierMode.AUTO) {
                NumberField("Nivel más alto desbloqueado", settings.maxTier.toString()) { v ->
                    v.toIntOrNull()?.takeIf { it in 1..30 }?.let { n -> update { it.copy(maxTier = n) } }
                }
                NumberField("% del tiempo para probar otros niveles (0-50)", (settings.exploreShare * 100).roundToInt().toString()) { v ->
                    v.toIntOrNull()?.takeIf { it in 0..50 }?.let { n -> update { it.copy(exploreShare = n / 100.0) } }
                }
                if (!calibration.canChooseTier()) {
                    Text(
                        "Falta calibrar INICIO (fin de partida) y «Nivel N» con sus flechas (inicio).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Section("Aprendizaje") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Probar estrategias con mis cartas", Modifier.weight(1f))
                Switch(settings.cardStrategiesEnabled, { on -> update { it.copy(cardStrategiesEnabled = on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aprender para alcanzar más oleadas", Modifier.weight(1f))
                Switch(settings.waveLearningEnabled, { on -> update { it.copy(waveLearningEnabled = on) } })
            }
            Text("${calibration.cards?.owned?.size ?: 0} cartas registradas · ${calibration.cards?.slots ?: 0} espacios. Equipará y verificará el mazo entre partidas. Cambia una carta o las compras en cada retadora y compara por nivel. Al cambiar objetivo o inventario inicia nuevos pares.", style = MaterialTheme.typography.bodySmall)
            NumberField("Pares de partidas como mínimo en un duelo", settings.minPairs.toString()) { v ->
                v.toIntOrNull()?.takeIf { it in 1..settings.maxPairs }?.let { n -> update { it.copy(minPairs = n) } }
            }
            NumberField("Pares de partidas como máximo en un duelo", settings.maxPairs.toString()) { v ->
                v.toIntOrNull()?.takeIf { it in settings.minPairs..10 }?.let { n -> update { it.copy(maxPairs = n) } }
            }
        }

        Section("Visión (avanzado)") {
            NumberField("Umbral para reconocer pantallas (0.02-0.30)", settings.matchThreshold.toString()) { v ->
                v.toDoubleOrNull()?.takeIf { it in 0.02..0.30 }?.let { t -> update { it.copy(matchThreshold = t) } }
            }
            NumberField("Parecido de forma mínimo de una pantalla (0-1)", settings.matchShape.toString()) { v ->
                v.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { t -> update { it.copy(matchShape = t) } }
            }
            NumberField("Parecido mínimo de un botón de mejora (0-1)", settings.slotSimilarity.toString()) { v ->
                v.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { t -> update { it.copy(slotSimilarity = t) } }
            }
            NumberField("Cambio que cuenta como compra (0.005-0.1)", settings.buyChangeThreshold.toString()) { v ->
                v.toDoubleOrNull()?.takeIf { it in 0.005..0.1 }?.let { t -> update { it.copy(buyChangeThreshold = t) } }
            }
            Text("Paquete del juego: ${calibration.gamePackage}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Un campo numérico que guarda en cuanto el valor es válido. */
@Composable
private fun NumberField(label: String, value: String, onValid: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValid(it)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
