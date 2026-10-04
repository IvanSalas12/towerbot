package com.arisa.towerbot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import java.util.Calendar
import kotlin.random.Random

/** Una captura lista para mirar. */
interface Shot {
    val frame: Frame
}

/** Lo que el bot puede hacer con el teléfono. */
interface Device {
    suspend fun capture(): Shot?
    suspend fun tap(p: Pt)
    suspend fun swipe(from: Pt, to: Pt, durationMs: Long)

    /** Arrastra despacio y se queda quieto antes de soltar, para que la lista no siga deslizándose. */
    suspend fun drag(from: Pt, to: Pt, durationMs: Long)
    fun back()
    fun launchGame(packageName: String)
    fun foregroundPackage(): String?
    fun ownPackage(): String
    fun batteryTempC(): Float?
}

/** Lee el texto de una zona de una captura. */
fun interface NumberReader {
    suspend fun read(shot: Shot, box: Box): String?
}

/** Donde el bot guarda lo que necesita entre noches. */
interface BotMemory {
    val calibration: Calibration
    val settings: BotSettings
    var brain: BrainState
    val runs: List<RunRecord>
    fun addRun(run: RunRecord)
}

@Serializable
data class BotSettings(
    val stopAtEnabled: Boolean = true,
    val stopHour: Int = 7,
    val stopMinute: Int = 0,
    val maxBatteryTempC: Float = 43f,
    val buyIntervalMs: Long = 2500,
    val maxBuysPerStep: Int = 4,
    val matchThreshold: Double = 0.10,
    val matchShape: Double = 0.6,
    val slotSimilarity: Double = 0.8,
    val buyChangeThreshold: Double = 0.01,
    val unaffordableCooldownMs: Long = 10_000,
    val tierMode: TierMode = TierMode.AUTO,
    /** El nivel más alto que tienes desbloqueado. */
    val maxTier: Int = 3,
    /** Parte del tiempo que dedica a probar otros niveles. */
    val exploreShare: Double = 0.15,
    /** Un duelo decide como pronto tras [minPairs] pares de partidas y como tarde tras [maxPairs]. */
    val minPairs: Int = 2,
    val maxPairs: Int = 4,
)

/**
 * Una partida. [coins]: lo que dice el resumen al morir (la partida entera). [gained] y
 * [minutes]: las monedas que el bot vio entrar y en cuánto tiempo, de donde sale
 * [coinsPerMinute]; si el bot no vio empezar la partida, salen del contador de arriba.
 * [startWave]: la oleada en la que el bot la cogió (1 si la empezó él).
 * [explore]: el nivel se eligió para aprender, no por ser el mejor.
 */
@Serializable
data class RunRecord(
    val startedAt: Long,
    val endedAt: Long,
    val wave: Int?,
    val coins: Double?,
    val coinsPerMinute: Double?,
    val strategyId: String,
    val counted: Boolean,
    val purchases: Int,
    val endReason: String,
    val tier: Int? = null,
    val startWave: Int? = null,
    val gained: Double? = null,
    val minutes: Double? = null,
    val explore: Boolean = false,
)

data class BotStatus(
    val running: Boolean = false,
    val screen: String = "—",
    val tier: Int? = null,
    val wave: Int = 0,
    val strategyId: String? = null,
    val runPurchases: Int = 0,
    val sessionRuns: Int = 0,
    val sessionCoins: Double = 0.0,
    val message: String = "Detenido",
)

/**
 * El bucle del bot: mirar la pantalla, decidir qué es y actuar.
 * No sabe nada de Android, así que se puede probar con un teléfono de mentira.
 */
class BotEngine(
    private val device: Device,
    private val memory: BotMemory,
    private val reader: NumberReader,
    val status: MutableStateFlow<BotStatus>,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    /** Detalle de cada paso (qué busca, qué compra): para depurar con adb logcat. */
    private val trace: (String) -> Unit = {},
) {
    /**
     * [learnerTier]: el nivel cuyo aprendizaje eligió la estrategia. Si luego resulta que
     * [tier] es otro (el lector se equivocó al empezar), la partida no cuenta para aprender.
     */
    private class ActiveRun(
        val strategy: Strategy,
        val startedAt: Long,
        val clean: Boolean,
        val learnerTier: Int,
        var tier: Int?,
        val explore: Boolean,
    ) {
        var wave = 0
        var startWave: Int? = null
        var lastWaveReadAt = 0L
        val coins = CoinTracker()
        var hudTaps = 0
        var lastHudTapAt = 0L
        var phaseIndex = -1
        val bought = mutableMapOf<Upgrade, Int>()
        val cooldownUntil = mutableMapOf<Upgrade, Long>()
        val maxed = mutableSetOf<Upgrade>()
        var purchases = 0
        var lastBuyAt = 0L
    }

    private var run: ActiveRun? = null
    private var lastExitRole: ScreenRole? = null
    private var lastExitRoleAt = 0L
    private var unknownSince: Long? = null
    private var lastBackAt = 0L
    private var notInGameSince: Long? = null

    /** El nivel de la próxima partida, ya comprobado en pantalla. */
    private var plan: TierChoice? = null
    private var tierTries = 0
    private var lastChoice: TierChoice? = null

    /** Si las flechas no pasan de un nivel, es el más alto desbloqueado. */
    private var tierCeiling = Int.MAX_VALUE

    private val settings get() = memory.settings
    private val calibration get() = memory.calibration

    suspend fun runLoop(stopAt: Long?) {
        status.update { it.copy(running = true, message = "En marcha", sessionRuns = 0, sessionCoins = 0.0) }
        try {
            while (currentCoroutineContext().isActive) {
                if (stopAt != null && now() >= stopAt) {
                    say("Hora de parar alcanzada")
                    break
                }
                val temp = device.batteryTempC()
                if (temp != null && temp >= settings.maxBatteryTempC) {
                    say("Batería a %.1f °C: pausa de 5 min para que se enfríe".format(temp))
                    delay(5 * 60_000L)
                    continue
                }
                if (!gameInFront()) {
                    delay(2000)
                    continue
                }
                val shot = device.capture()
                if (shot == null) {
                    delay(1000)
                    continue
                }
                try {
                    step(shot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trace("Error en un paso, sigo: $e")
                    say("Error en un paso (sigo jugando): ${e.javaClass.simpleName}")
                }
                delay(TICK_MS)
            }
        } finally {
            run?.let { finishRun(it, it.wave.takeIf { w -> w > 0 }, null, "bot detenido") }
            run = null
            status.update { it.copy(running = false, message = "Detenido · ${it.message}") }
        }
    }

    private fun say(message: String) = status.update { it.copy(message = message) }

    private fun gameInFront(): Boolean {
        val fg = device.foregroundPackage()
        val game = calibration.gamePackage
        if (fg == null || fg == game) {
            notInGameSince = null
            return true
        }
        // Tu propia app o la cortina de notificaciones: esperar sin molestar.
        if (fg == device.ownPackage() || fg == "com.android.systemui") {
            say("Pausado: el juego no está en pantalla")
            notInGameSince = null
            return false
        }
        val since = notInGameSince ?: now().also { notInGameSince = it }
        if (now() - since > 15_000) {
            say("El juego se cerró: lo vuelvo a abrir")
            device.launchGame(game)
            notInGameSince = now()
        }
        return false
    }

    private suspend fun step(shot: Shot) {
        val match = ScreenClassifier.classify(shot.frame, calibration.screens, settings.matchThreshold, settings.matchShape)
        status.update { it.copy(screen = match?.screen?.name ?: "Desconocida") }
        if (match == null) {
            onUnknown()
            return
        }
        unknownSince = null
        val screen = match.screen
        when (screen.role) {
            ScreenRole.HOME -> onHome(screen)
            ScreenRole.IN_RUN -> onInRun(screen, shot)
            ScreenRole.GAME_OVER -> onGameOver(screen)
            ScreenRole.POPUP -> screen.tap?.let {
                say("Cerrando «${screen.name}»")
                device.tap(it)
                delay(800)
            }
        }
        if (screen.role == ScreenRole.HOME || screen.role == ScreenRole.GAME_OVER) {
            lastExitRole = screen.role
            lastExitRoleAt = now()
        }
    }

    private fun onUnknown() {
        val since = unknownSince ?: now().also { unknownSince = it }
        if (now() - since > 25_000 && now() - lastBackAt > 60_000) {
            say("Pantalla desconocida hace rato: pulso Atrás")
            device.back()
            lastBackAt = now()
        }
    }

    private suspend fun readTier(shot: Shot?, box: Box?): Int? {
        shot ?: return null
        box ?: return null
        return reader.read(shot, box)?.let(NumberParser::parseTier)
    }

    /** El nivel que toca según tus datos, o null si el bot no debe elegirlo. */
    private fun decideTier(current: Int?): TierChoice? {
        if (settings.tierMode != TierMode.AUTO || !calibration.canChooseTier()) return null
        val choice = TierPlanner.choose(
            memory.runs, now(), minOf(settings.maxTier, tierCeiling), settings.exploreShare, current,
        )
        // Al diario sólo va cuando cambia lo que decide, no en cada partida.
        if (choice.tier != lastChoice?.tier || choice.explore != lastChoice?.explore) {
            memory.brain = memory.brain.withLog("${clock(now())} ${choice.reason}")
        }
        lastChoice = choice
        return choice
    }

    private suspend fun onHome(screen: ScreenDef) {
        run?.let { finishRun(it, it.wave.takeIf { w -> w > 0 }, null, "abandonada") }
        run = null
        val tap = screen.tap ?: return say("Falta calibrar el punto de BATALLA")
        val current = readTier(device.capture(), screen.tierBox)
        status.update { it.copy(tier = current) }
        val wanted = plan?.tier ?: decideTier(current)?.also { plan = it }?.tier
        val prev = screen.tierPrev
        val next = screen.tierNext
        if (wanted != null && current != null && wanted != current && prev != null && next != null) {
            if (tierTries < MAX_TIER_TAPS) {
                tierTries++
                say("Cambio al Nivel $wanted (estoy en el $current)")
                device.tap(if (wanted > current) next else prev)
                delay(900)
                return
            }
            // Las flechas no llegan: ese nivel no está desbloqueado.
            if (wanted > current) tierCeiling = current
            memory.brain = memory.brain.withLog("${clock(now())} ⚠ No llego al Nivel $wanted: juego el $current")
            plan = TierChoice(current, explore = false, reason = "no se pudo cambiar")
        }
        tierTries = 0
        // Sólo se apunta el nivel si se ha leído: si no, lo leerá ya en partida.
        plan = if (current == null) null else plan?.takeIf { it.tier == current } ?: TierChoice(current, false, "")
        say("Empiezo partida" + (current?.let { " en el Nivel $it" } ?: ""))
        device.tap(tap)
        delay(3000)
    }

    private suspend fun onGameOver(screen: ScreenDef) {
        val r = run
        var shot: Shot? = null
        if (r != null) {
            // Los números del resumen pueden estar animándose: espera y vuelve a mirar.
            delay(2000)
            shot = device.capture()
            val wave = shot?.let { s -> screen.waveBox?.let { reader.read(s, it) } }
                ?.let(NumberParser::parseInt) ?: r.wave.takeIf { it > 0 }
            val coins = shot?.let { s -> screen.coinsBox?.let { reader.read(s, it) } }
                ?.let(NumberParser::parse)
            if (r.tier == null) r.tier = readTier(shot, screen.tierBox)
            finishRun(r, wave, coins, "muerte")
            run = null
        }
        val current = readTier(shot ?: device.capture(), screen.tierBox)
        val choice = decideTier(current)
        val home = screen.homeTap
        if (choice != null && current != null && choice.tier != current && home != null) {
            // Para cambiar de nivel hay que ir al inicio; allí se mueven las flechas.
            plan = choice
            tierTries = 0
            say("Voy al inicio para jugar el Nivel ${choice.tier}")
            delay(1000)
            device.tap(home)
            delay(3000)
            return
        }
        plan = current?.let { t -> choice?.takeIf { it.tier == t } ?: TierChoice(t, false, "") }
        val tap = screen.tap ?: return say("Falta calibrar el punto de REINTENTAR")
        delay(1000)
        device.tap(tap)
        delay(3000)
    }

    private fun inRunBox(screen: ScreenDef, pick: (ScreenDef) -> Box?): Box? =
        pick(screen) ?: calibration.screensOf(ScreenRole.IN_RUN).firstNotNullOfOrNull(pick)

    private suspend fun onInRun(screen: ScreenDef, shot: Shot) {
        val r = run ?: startRun(screen, shot).also { run = it }
        if (now() - r.lastWaveReadAt > WAVE_READ_MS) {
            r.lastWaveReadAt = now()
            inRunBox(screen) { it.waveBox }?.let { box ->
                val text = reader.read(shot, box)
                trace("Oleada leída: «$text»")
                text?.let(NumberParser::parseInt)?.let { w ->
                    // Descarta lecturas absurdas (el lector a veces confunde dígitos).
                    if (r.wave == 0 || w in r.wave..r.wave + 300) r.wave = w
                }
                if (r.startWave == null && r.wave > 0) r.startWave = if (r.clean) 1 else r.wave
            }
            if (r.tier == null) r.tier = readTier(shot, inRunBox(screen) { it.tierBox })
            inRunBox(screen) { it.coinsBox }?.let { box ->
                val text = reader.read(shot, box)
                if (text != null && "/" in text) {
                    // El contador enseña el ritmo («315/min») en vez del saldo. Pulsarlo lo
                    // devuelve al saldo; pocas veces y con pausa, por si no responde.
                    if (r.hudTaps < MAX_HUD_TAPS && now() - r.lastHudTapAt > HUD_TAP_GAP_MS) {
                        trace("El contador de monedas enseña «$text»: lo pulso para ver el saldo")
                        r.hudTaps++
                        r.lastHudTapAt = now()
                        device.tap(box.center)
                        delay(600)
                    }
                } else {
                    text?.let(NumberParser::parse)?.let { r.coins.add(now(), it) }
                }
            }
            status.update { it.copy(wave = r.wave, tier = r.tier) }
        }
        if (now() - r.lastBuyAt >= settings.buyIntervalMs) {
            buyStep(r)
            r.lastBuyAt = now()
            status.update { it.copy(runPurchases = r.purchases) }
        }
    }

    /** Dónde murió [strategyId] en sus últimas partidas de [tier]: orienta la próxima retadora. */
    private fun deathWaves(tier: Int, strategyId: String) =
        memory.runs.filter { it.tier == tier && it.strategyId == strategyId && it.endReason == "muerte" }
            .mapNotNull { it.wave }.takeLast(6)

    private suspend fun startRun(screen: ScreenDef, shot: Shot): ActiveRun {
        // Una partida sólo cuenta para aprender si el bot la vio empezar.
        val clean = (lastExitRole == ScreenRole.HOME || lastExitRole == ScreenRole.GAME_OVER) &&
            now() - lastExitRoleAt < 90_000
        val planned = plan?.takeIf { clean }
        plan = null
        val tier = readTier(shot, inRunBox(screen) { it.tierBox }) ?: planned?.tier
        val explore = planned?.explore == true && planned.tier == tier
        val learnerTier = tier ?: 1
        val learner = Learner(settings.minPairs, settings.maxPairs, random = random)
        val brain = memory.brain
        val current = brain.learner(learnerTier)
        val (state, strategy) = learner.next(
            current, calibration.availableUpgrades(), deathWaves(learnerTier, current.champion.id),
        )
        memory.brain = brain.with(learnerTier, state)
        status.update { it.copy(strategyId = strategy.id, tier = tier, wave = 0, runPurchases = 0) }
        val where = tier?.let { " en el Nivel $it" } ?: ""
        say(
            if (clean) "Partida nueva$where con ${strategy.id}" + if (explore) " (para aprender)" else ""
            else "Partida ya empezada$where: juego, pero no cuenta para aprender",
        )
        return ActiveRun(strategy, now(), clean, learnerTier, tier, explore)
    }

    private suspend fun buyStep(r: ActiveRun) {
        val cal = calibration
        val phaseIndex = r.strategy.phaseIndexFor(r.wave)
        if (phaseIndex != r.phaseIndex) {
            r.phaseIndex = phaseIndex
            r.bought.clear()
        }
        val weights = r.strategy.phases[phaseIndex].weights
        var buys = 0
        var attempts = 0
        while (buys < settings.maxBuysPerStep && attempts < settings.maxBuysPerStep + 2) {
            attempts++
            val t = now()
            val ready = cal.slots.map { it.upgrade }
                .filter { it !in r.maxed && (r.cooldownUntil[it] ?: 0L) <= t }.toSet()
            val choice = Allocator.choose(weights, r.bought, ready)
            if (choice == null) {
                trace("Fase ${phaseIndex + 1}: nada que comprar ahora (en espera o al máximo)")
                return
            }
            val slot = cal.slotFor(choice) ?: return
            val spot = showSlot(slot)
            if (spot == null) {
                trace("No encuentro ${choice.label}: espero ${settings.unaffordableCooldownMs / 1000} s")
                r.cooldownUntil[choice] = now() + settings.unaffordableCooldownMs
                continue
            }
            val button = (slot.button ?: slot.box).shifted(spot.dy)
            if (ScreenClassifier.looksMaxed(spot.shot.frame, button)) {
                // Al máximo en esta partida: no se vuelve a intentar hasta la siguiente.
                trace("${choice.label} está al máximo")
                r.maxed += choice
                continue
            }
            val look = Template.sample(spot.shot.frame, button, 40)
            device.tap(button.center)
            delay(450)
            val after = device.capture() ?: return
            if (!inRun(after)) return
            val change = look.distance(after.frame)
            if (change >= settings.buyChangeThreshold) {
                trace("Comprado ${choice.label} (cambio ${"%.3f".format(change)}, oleada ${r.wave})")
                r.bought[choice] = (r.bought[choice] ?: 0) + 1
                r.purchases++
                buys++
            } else {
                // No cambió nada: no alcanzaba el dinero o ya está al máximo.
                trace("${choice.label} no cambió (${"%.3f".format(change)}): falta dinero, espero")
                r.cooldownUntil[choice] = now() + settings.unaffordableCooldownMs
            }
        }
    }

    private fun inRun(shot: Shot) =
        ScreenClassifier.classify(shot.frame, calibration.screens, settings.matchThreshold, settings.matchShape)
            ?.screen?.role == ScreenRole.IN_RUN

    private class Spot(val shot: Shot, val dy: Int)

    /**
     * Deja [slot] a la vista y devuelve la captura y cuánto se ha movido desde donde se
     * calibró. Primero abre su pestaña (sin tocarla si ya está abierta, porque eso cierra
     * el panel); luego sube la lista del todo y la va bajando a pasos hasta encontrarla.
     */
    private suspend fun showSlot(slot: UpgradeSlot): Spot? {
        val cal = calibration
        val area = cal.listArea() ?: return null
        var wentTop = false
        var beforeStep: Template? = null
        repeat(MAX_SEARCH_STEPS) {
            val shot = device.capture() ?: return null
            if (!inRun(shot)) return null
            if (cal.tabHeaders.isNotEmpty() &&
                ScreenClassifier.activeTab(shot.frame, cal.tabHeaders, settings.matchThreshold) != slot.tab
            ) {
                trace("Abro la pestaña ${slot.tab.label}")
                device.tap(cal.tabs[slot.tab] ?: return null)
                delay(700)
                wentTop = false
                beforeStep = null
                return@repeat
            }
            ScreenClassifier.locateSlot(shot.frame, slot, cal.slots, area, settings.slotSimilarity)
                ?.let { return Spot(shot, it) }
            if (!wentTop) {
                scrollToTop(area)
                wentTop = true
                return@repeat
            }
            // Si la lista no se movió con el último paso, ya estaba al fondo: no está.
            if (beforeStep?.let { it.distance(shot.frame) < LIST_STILL } == true) return null
            beforeStep = Template.sample(shot.frame, area, 24)
            stepDown(area)
        }
        return null
    }

    private suspend fun scrollToTop(area: Box) {
        // Por el hueco entre las dos columnas, para no pulsar ningún botón.
        val x = area.center.x
        repeat(2) { device.swipe(Pt(x, area.top + 40), Pt(x, area.bottom - 20), 150) }
        delay(800)
    }

    private suspend fun stepDown(area: Box) {
        val x = area.center.x
        val from = area.bottom - 30
        device.drag(Pt(x, from), Pt(x, from - area.height / 2), 500)
        delay(300)
    }

    private fun finishRun(r: ActiveRun, wave: Int?, coins: Double?, reason: String) {
        val end = now()
        // Si vio la partida entera, vale el resumen final; si no, lo que vio entrar arriba.
        val whole = r.clean && reason == "muerte" && coins != null
        val gained = if (whole) coins else r.coins.gained()
        val minutes = if (whole) (end - r.startedAt) / 60_000.0 else r.coins.minutes()
        val cpm = if (gained != null && minutes != null && minutes >= 0.5) gained / minutes else null
        val counted = whole && cpm != null && r.tier != null && r.tier == r.learnerTier
        memory.addRun(
            RunRecord(
                r.startedAt, end, wave, coins, cpm, r.strategy.id, counted, r.purchases, reason,
                tier = r.tier,
                startWave = r.startWave,
                gained = gained.takeIf { cpm != null },
                minutes = minutes.takeIf { cpm != null },
                explore = r.explore,
            ),
        )
        if (counted) {
            val learner = Learner(settings.minPairs, settings.maxPairs, random = random)
            val brain = memory.brain
            memory.brain = brain.with(r.learnerTier, learner.report(brain.learner(r.learnerTier), r.strategy.id, cpm!!))
        }
        status.update {
            it.copy(
                sessionRuns = it.sessionRuns + 1,
                sessionCoins = it.sessionCoins + (gained ?: 0.0),
                message = "Partida terminada ($reason): ${r.tier?.let { t -> "Nivel $t, " } ?: ""}oleada ${wave ?: "?"}, " +
                    "${coins?.let(NumberParser::format) ?: "?"} monedas" +
                    (cpm?.let { c -> " · ${NumberParser.format(c)}/min" } ?: ""),
            )
        }
    }

    private fun clock(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    companion object {
        const val TICK_MS = 500L
        const val WAVE_READ_MS = 15_000L
        const val MAX_SEARCH_STEPS = 14
        const val LIST_STILL = 0.01
        const val MAX_TIER_TAPS = 6
        const val MAX_HUD_TAPS = 3
        const val HUD_TAP_GAP_MS = 20_000L
    }
}
