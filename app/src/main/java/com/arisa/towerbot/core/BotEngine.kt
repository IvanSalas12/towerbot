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
    fun setOverlayVisible(visible: Boolean) {}

    /** Guarda [shot] en el teléfono para revisarla después. */
    fun keepCapture(shot: Shot, name: String) {}

    /** Botones de cerrar, saltar o reanudar que los anuncios anuncian por accesibilidad. */
    fun adExits(): List<AdExit> = emptyList()
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
    fun updateCards(cards: CardLayout) {}
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
    val coinAdsEnabled: Boolean = false,
    val gemAdsEnabled: Boolean = false,
    val storeGemAdsEnabled: Boolean = false,
    val cardStrategiesEnabled: Boolean = false,
    val waveLearningEnabled: Boolean = false,
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
    val adAttempts: Int = 0,
    val cards: List<String> = emptyList(),
    val learningMetric: String = "monedas/min",
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
    device: Device,
    private val memory: BotMemory,
    reader: NumberReader,
    val status: MutableStateFlow<BotStatus>,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    /** Detalle de cada paso (qué busca, qué compra): para depurar con adb logcat. */
    private val trace: (String) -> Unit = {},
) {
    val stats = FlowStats(now)
    private val device: Device = MeasuredDevice(device, stats, now)
    private val reader: NumberReader = MeasuredReader(reader, stats, now)
    private var lastSummaryAt = 0L
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
        val adPolicy: String,
        val learningContext: String,
        val preparedCards: Boolean,
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
        /** Cómo se veía el botón (azul − rojo) cuando no alcanzó el dinero. */
        val brokeTone = mutableMapOf<Upgrade, Int>()
        /** Veces seguidas que una mejora se vio sin dinero: cuantas más, más espera. */
        val greyStreak = mutableMapOf<Upgrade, Int>()
        val maxed = mutableSetOf<Upgrade>()
        var purchases = 0
        var lastBuyAt = 0L
        var adAttempts = 0
    }

    private var run: ActiveRun? = null
    private var lastExitRole: ScreenRole? = null
    private var lastExitRoleAt = 0L
    private var unknownSince: Long? = null
    private var lastBackAt = 0L
    private var notInGameSince: Long? = null
    private var adStartedAt: Long? = null
    private val lastAdAttempt = mutableMapOf<AdReward, Long>()
    private var lastAdCloseAt: Long? = null
    private var lastAdConfirmAt: Long? = null
    /** Dónde vio la X sin calibrar la vez anterior: sólo se pulsa si sigue en el mismo sitio. */
    private var pendingMark: Pt? = null
    /** Sitios ya pulsados en este anuncio, para no insistir en una X que no hace nada. */
    private val exitTaps = mutableListOf<Pt>()
    private var noExitUntil = 0L
    private var xMissingNoted = false
    private var storeChecked = false
    /** El regalo de la Tienda tarda en volver: no se mira en cada muerte. */
    private var storeNextCheckAt = 0L
    private var storeSearching = false
    private var storePages = 0
    private var preparedStrategy: Strategy? = null
    private var preparedTier: Int? = null
    private var cardsVerified = false
    /** Ya leyó el inventario de cartas desde que arrancó el bot. */
    private var cardsReviewed = false

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
                val stepStart = now()
                try {
                    step(shot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trace("Error en un paso, sigo: $e")
                    say("Error en un paso (sigo jugando): ${e.javaClass.simpleName}")
                }
                delay(TICK_MS)
                stats.spent(status.value.screen, now() - stepStart)
                if (now() - lastSummaryAt >= SUMMARY_MS) {
                    lastSummaryAt = now()
                    trace(stats.summary())
                }
            }
        } finally {
            trace(stats.summary())
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
        if (adStartedAt != null) {
            say("Anuncio en otra app: espero o busco su cierre calibrado")
            return true
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
        // El cierre calibrado tiene prioridad sobre las anclas del juego que puedan
        // seguir visibles debajo de un anuncio. Nunca se pulsa una X por coordenadas a ciegas.
        val adMatch = ScreenClassifier.classify(
            shot.frame, calibration.screens.filter { it.role in setOf(ScreenRole.AD_CLOSE, ScreenRole.AD_PLAYING, ScreenRole.AD_CLAIM,
                ScreenRole.COIN_AD_OFFER, ScreenRole.GEM_AD_OFFER) },
            settings.matchThreshold, settings.matchShape,
        )
        if (adMatch != null) {
            val screen = adMatch.screen
            status.update { it.copy(screen = screen.name) }
            if (screen.role == ScreenRole.COIN_AD_OFFER || screen.role == ScreenRole.GEM_AD_OFFER) {
                val enabled = if (screen.role == ScreenRole.COIN_AD_OFFER) settings.coinAdsEnabled else settings.gemAdsEnabled
                val available = screen.adAvailable?.let {
                    it.distance(shot.frame) <= settings.matchThreshold && it.similarity(shot.frame) >= settings.matchShape
                } ?: true
                if (!enabled || !available || (adStartedAt != null && lastAdCloseAt != null)) {
                    screen.homeTap?.let { device.tap(it); delay(1000) }
                    endAd()
                    say(if (enabled) "Vuelvo al juego con el bonus actual" else "Anuncios de esta recompensa desactivados")
                } else if (screen.tap != null &&
                    (lastAdConfirmAt == null || now() - lastAdConfirmAt!! >= AD_RETRY_GAP_MS)) {
                    if (adStartedAt == null) { adStartedAt = now(); run?.let { it.adAttempts++ } }
                    lastAdConfirmAt = now()
                    say("Confirmo el anuncio con recompensa")
                    device.tap(screen.tap)
                    delay(1500)
                } else say("Esperando a que cargue el anuncio con recompensa")
                return
            }
            if (adStartedAt == null) adStartedAt = now()
            unknownSince = null
            val tap = screen.tap
            if ((screen.role == ScreenRole.AD_CLOSE || screen.role == ScreenRole.AD_CLAIM) && tap != null &&
                (lastAdCloseAt == null || now() - lastAdCloseAt!! >= AD_CLOSE_GAP_MS)) {
                // Si el ancla no cubre la X (por ejemplo, el panel de abajo de un anuncio, que
                // se ve durante todo el video), hay que ver la X antes de pulsar.
                if (screen.role == ScreenRole.AD_CLAIM || screen.anchors.any { it.box.covers(tap, ANCHOR_TAP_MARGIN) } ||
                    CloseMark.findNear(shot.frame, tap) != null) {
                    say(if (screen.role == ScreenRole.AD_CLAIM) "Reclamo el premio del anuncio" else "Anuncio terminado: cierro y vuelvo al juego")
                    lastAdCloseAt = now()
                    device.tap(tap)
                    delay(1000)
                    return
                }
                if (!xMissingNoted) trace("«${screen.name}»: aún no hay X donde se calibró; no pulso hasta verla")
                xMissingNoted = true
            }
            if (!exitAd(shot)) say("Esperando a que termine el anuncio")
            return
        }
        val foreground = device.foregroundPackage()
        if (adStartedAt != null && foreground != null && foreground != calibration.gamePackage) {
            if (!exitAd(shot)) say("Anuncio en otra app: busco su X o espero a volver al juego")
            return
        }
        val match = ScreenClassifier.classify(shot.frame, calibration.screens, settings.matchThreshold, settings.matchShape)
        status.update { it.copy(screen = match?.screen?.name ?: "Desconocida") }
        if (match == null) {
            if (adStartedAt != null) {
                if (exitAd(shot)) return
                say(if (now() - adStartedAt!! >= AD_WAIT_NOTICE_MS)
                    "El anuncio sigue abierto: cierra su X o calibra «Anuncio terminado» con 📷"
                else "Esperando al anuncio; no pulso Atrás para conservar la recompensa")
                return
            }
            onUnknown(shot)
            return
        }
        unknownSince = null
        val screen = match.screen
        if (adStartedAt != null && screen.role != ScreenRole.POPUP) {
            trace("De vuelta al juego después del anuncio (recompensa a cargo del juego)")
            endAd()
        }
        if (screen.role in setOf(ScreenRole.HOME, ScreenRole.GAME_OVER) && tryAd(shot)) return
        when (screen.role) {
            ScreenRole.HOME -> onHome(screen)
            ScreenRole.IN_RUN -> onInRun(screen, shot)
            ScreenRole.GAME_OVER -> onGameOver(screen)
            ScreenRole.POPUP -> screen.tap?.let {
                say("Cerrando «${screen.name}»")
                device.tap(it)
                delay(800)
            }
            ScreenRole.STORE -> onStore(shot)
            ScreenRole.CARDS -> onCards()
            ScreenRole.AD_PLAYING, ScreenRole.AD_CLOSE, ScreenRole.AD_CLAIM, ScreenRole.COIN_AD_OFFER, ScreenRole.GEM_AD_OFFER -> Unit
        }
        if (screen.role == ScreenRole.HOME || screen.role == ScreenRole.GAME_OVER) {
            lastExitRole = screen.role
            lastExitRoleAt = now()
        }
    }

    private suspend fun tryAd(shot: Shot): Boolean {
        for (reward in AdReward.entries) {
            val enabled = when (reward) {
                AdReward.COINS -> settings.coinAdsEnabled
                AdReward.GEMS -> settings.gemAdsEnabled
            }
            if (!enabled) continue
            val last = lastAdAttempt[reward]
            if (last != null && now() - last < AD_RETRY_GAP_MS) continue
            val button = calibration.adButtons[reward] ?: continue
            if (button.distance(shot.frame) > settings.matchThreshold ||
                button.similarity(shot.frame) < settings.matchShape) continue
            lastAdAttempt[reward] = now()
            adStartedAt = now()
            run?.let { it.adAttempts++ }
            say("Abro anuncio: ${reward.label}")
            trace("Anuncio solicitado: ${reward.label}; la recompensa se confirma en el juego")
            device.tap(button.box.center)
            delay(1500)
            return true
        }
        return false
    }

    private fun endAd() {
        adStartedAt = null
        lastAdCloseAt = null
        pendingMark = null
        exitTaps.clear()
        noExitUntil = 0L
        xMissingNoted = false
    }

    /**
     * Sale de un anuncio cuyo final no está calibrado. Primero, los botones con nombre
     * («Cerrar», «Skip», «Reanudar»); si no hay, una X dibujada en una esquina de arriba
     * que se vea dos veces seguidas en el mismo sitio (en un video, lo que se mueve no
     * se repite). Nunca antes de [AD_MIN_WATCH_MS]: cerrar pronto puede perder el premio.
     */
    private suspend fun exitAd(shot: Shot): Boolean {
        val started = adStartedAt ?: return false
        val t = now()
        if (t - started < AD_MIN_WATCH_MS || t < noExitUntil) return false
        if (lastAdCloseAt?.let { t - it < AD_CLOSE_GAP_MS } == true) return false
        fun spent(p: Pt) = exitTaps.count { it.near(p, EXIT_SAME_SPOT) } >= MAX_EXIT_TAPS
        val exits = device.adExits().filterNot { spent(it.at) }
        val exit = AdExit.Kind.entries.firstNotNullOfOrNull { kind -> exits.firstOrNull { it.kind == kind } }
        val (at, what) = if (exit != null) {
            pendingMark = null
            exit.at to "«${exit.label}»"
        } else {
            val mark = CloseMark.find(shot.frame)?.center?.takeUnless(::spent)
            val seen = pendingMark
            pendingMark = mark
            if (mark == null || seen == null || !mark.near(seen, EXIT_SAME_SPOT)) {
                if (mark != null) trace("Posible X del anuncio en $mark: compruebo que sigue ahí")
                return false
            }
            pendingMark = null
            mark to "la X"
        }
        // Al pulsar Cerrar durante un video, algunos preguntan si quieres perder el premio.
        if (exit?.kind == AdExit.Kind.RESUME) noExitUntil = t + AD_RESUME_PAUSE_MS
        say(if (exit?.kind == AdExit.Kind.RESUME) "El anuncio quiere cerrarse antes de tiempo: pulso $what"
            else "Anuncio sin cierre calibrado: pulso $what")
        trace("Salida de anuncio sin calibrar: $what en $at")
        exitTaps += at
        lastAdCloseAt = t
        device.tap(at)
        delay(1000)
        return true
    }

    private fun onUnknown(shot: Shot) {
        val since = unknownSince ?: now().also { unknownSince = it }
        if (now() - since > 25_000 && now() - lastBackAt > 60_000) {
            // Se guarda para poder calibrarla: suele ser una variante de una pantalla conocida.
            device.keepCapture(shot, "desconocida")
            say("Pantalla desconocida hace rato: la guardo y pulso Atrás")
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
        stats.runOver()
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
        if (settings.gemAdsEnabled && settings.storeGemAdsEnabled && calibration.storeAd != null && !storeChecked) {
            say("Reviso las 20 gemas gratis de la Tienda")
            device.tap(calibration.storeAd!!.tab)
            storeSearching = false
            delay(1000)
            return
        }
        if (settings.cardStrategiesEnabled && calibration.cards != null) {
            if (current == null) return say("Necesito leer el nivel antes de preparar las cartas")
            if (preparedTier != current || preparedStrategy == null) {
                preparedStrategy = chooseStrategy(current)
                preparedTier = current
                cardsVerified = false
            }
            if (!cardsVerified) {
                say("Preparo y compruebo las cartas del Nivel $current")
                device.tap(calibration.cards!!.tab)
                delay(1000)
                return
            }
        }
        say("Empiezo partida" + (current?.let { " en el Nivel $it" } ?: ""))
        device.tap(tap)
        delay(3000)
    }

    private suspend fun onGameOver(screen: ScreenDef) {
        val r = run
        stats.runOver()
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
            storeChecked = now() < storeNextCheckAt
            preparedStrategy = null
            preparedTier = null
            cardsVerified = false
        }
        val current = readTier(shot ?: device.capture(), screen.tierBox)
        val choice = decideTier(current)
        val home = screen.homeTap
        val sameTier = choice == null || current == null || choice.tier == current
        val betweenRuns = (settings.gemAdsEnabled && settings.storeGemAdsEnabled && calibration.storeAd != null && !storeChecked) ||
            (settings.cardStrategiesEnabled && calibration.cards != null && !(sameTier && deckAlreadyOn(current)))
        if (home != null && (betweenRuns || (choice != null && current != null && choice.tier != current))) {
            // Para cambiar de nivel hay que ir al inicio; allí se mueven las flechas.
            plan = choice
            tierTries = 0
            say("Voy al inicio para preparar la siguiente partida")
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

    /**
     * Prepara ya la estrategia de la próxima partida. Si su mazo es el que quedó puesto y
     * comprobado, no hace falta ir a Cartas: se puede reintentar directamente.
     */
    private fun deckAlreadyOn(tier: Int?): Boolean {
        val layout = calibration.cards ?: return false
        if (tier == null || !cardsReviewed || now() - layout.reviewedAt >= CARD_REVIEW_MS) return false
        if (preparedTier != tier || preparedStrategy == null) {
            preparedStrategy = chooseStrategy(tier)
            preparedTier = tier
        }
        val deck = preparedStrategy?.cards ?: return false
        cardsVerified = deck.size == layout.slots && deck.toSet() == layout.equipped.toSet()
        if (cardsVerified) trace("El mazo de ${preparedStrategy?.id} ya está puesto: reintento sin pasar por Cartas")
        return cardsVerified
    }

    private suspend fun onStore(shot: Shot) {
        val layout = calibration.storeAd ?: return say("Falta calibrar la Tienda")
        if (!settings.gemAdsEnabled || !settings.storeGemAdsEnabled || storeChecked) {
            device.tap(layout.battleTab)
            delay(1000)
            return
        }
        if (!storeSearching) {
            storeSearching = true
            storePages = 0
            BetweenRuns.top(device, layout.area)
            return
        }
        val dy = BetweenRuns.locate(shot.frame, layout.offer, layout.area)
        if (dy != null) {
            val video = layout.video.shifted(dy)
            if (video.box.bottom > layout.area.bottom) {
                BetweenRuns.down(device, layout.area)
                return
            }
            // El anuncio no siempre está disponible; un contador o botón distinto no sirve.
            storeChecked = true
            storeNextCheckAt = now() + STORE_RECHECK_MS
            if (video.box.top >= layout.area.top && video.box.bottom <= layout.area.bottom &&
                video.distance(shot.frame) <= settings.matchThreshold && video.similarity(shot.frame) >= 0.85) {
                adStartedAt = now()
                say("Veo el anuncio gratuito de 20 gemas de la Tienda")
                trace("Tienda: anuncio gratuito de 20 gemas solicitado")
                device.tap(video.box.center)
                delay(1500)
            } else {
                say("Las 20 gemas aún no están disponibles; continúo")
                device.tap(layout.battleTab)
                delay(1000)
            }
            return
        }
        if (++storePages >= 8) {
            storeChecked = true
            storeNextCheckAt = now() + STORE_RECHECK_MS
            say("No aparece el anuncio gratuito de la Tienda; continúo")
            device.tap(layout.battleTab)
            delay(1000)
        } else BetweenRuns.down(device, layout.area)
    }

    private suspend fun onCards() {
        var layout = calibration.cards ?: return say("Falta revisar el inventario de cartas")
        if (!settings.cardStrategiesEnabled) {
            device.tap(layout.battleTab)
            delay(1000)
            return
        }
        val strategy = preparedStrategy ?: run {
            device.tap(layout.battleTab)
            delay(1000)
            return
        }
        device.setOverlayVisible(false)
        val equipped = try {
            delay(350)
            val equipment = CardEquipment(device, reader, { calibration }, { settings }, trace)
            // Las cartas sólo cambian si las compras o mejoras tú: basta leerlas al arrancar
            // y cada pocas horas, no después de cada muerte.
            val fresh = cardsReviewed && now() - layout.reviewedAt < CARD_REVIEW_MS
            val reviewed = if (fresh) layout else equipment.review(layout, now())
            if (reviewed == null) {
                say("No pude leer todo el inventario de cartas; espero para evitar una prueba incompleta")
                false
            } else {
                val changed = reviewed.fingerprint() != layout.fingerprint()
                memory.updateCards(reviewed)
                layout = reviewed
                cardsReviewed = true
                val selected = if (changed) chooseStrategy(preparedTier ?: 1).also { preparedStrategy = it } else strategy
                val deck = selected.cards
                (deck != null && equipment.equip(layout, deck)).also { ok ->
                    // Si algo falla a medias, no se sabe qué quedó puesto: se vuelve a leer.
                    if (ok) memory.updateCards(layout.copy(equipped = deck!!)) else cardsReviewed = false
                }
            }
        } finally { device.setOverlayVisible(true) }
        if (!equipped) {
            say("No pude comprobar las cartas. Revisa el mazo o la calibración; no inicio la prueba")
            delay(5000)
            return
        }
        cardsVerified = true
        device.tap(layout.battleTab)
        delay(1000)
    }

    private fun policy() = "${settings.coinAdsEnabled}:${settings.gemAdsEnabled}"
    private fun learningContext() = "${settings.waveLearningEnabled}:${settings.cardStrategiesEnabled}:" +
        if (settings.cardStrategiesEnabled) calibration.cards?.fingerprint().orEmpty() else ""

    private fun chooseStrategy(tier: Int): Strategy {
        val context = learningContext()
        val policy = policy()
        var brain = memory.brain
        if (brain.adPolicy != policy || brain.learningContext != context) {
            val layout = calibration.cards.takeIf { settings.cardStrategiesEnabled }
            brain = brain.copy(adPolicy = policy, learningContext = context, tiers = brain.tiers.mapValues { (_, s) ->
                val validDeck = s.champion.cards?.takeIf { deck ->
                    layout != null && deck.size == layout.slots && deck.all { name -> layout.owned.any { it.name == name } }
                }
                s.copy(champion = s.champion.copy(cards = layout?.let { validDeck ?: it.initialDeck }),
                    challenger = if (brain.learningContext != context) null else s.challenger,
                    championScores = emptyList(), challengerScores = emptyList())
                    .withLog("↺ Cambió el objetivo, inventario o anuncios: nuevos pares, conservo las compras aprendidas")
            })
        }
        var current = brain.learner(tier)
        val layout = calibration.cards.takeIf { settings.cardStrategiesEnabled }
        if (layout != null && current.champion.cards == null) current = current.copy(champion = current.champion.copy(cards = layout.initialDeck))
        val (state, strategy) = Learner(settings.minPairs, settings.maxPairs, random = random).next(
            current, calibration.availableUpgrades(), deathWaves(tier, current.champion.id), layout,
        )
        memory.brain = brain.with(tier, state)
        return strategy
    }

    private fun inRunBox(screen: ScreenDef, pick: (ScreenDef) -> Box?): Box? =
        pick(screen) ?: calibration.screensOf(ScreenRole.IN_RUN).firstNotNullOfOrNull(pick)

    private suspend fun onInRun(screen: ScreenDef, shot: Shot) {
        val r = run ?: startRun(screen, shot).also { run = it }
        if (tryAd(shot)) return
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
        stats.runStarted()?.let { trace("Entre partidas: %.1f s hasta la nueva partida".format(it / 1000.0)) }
        val tier = readTier(shot, inRunBox(screen) { it.tierBox }) ?: planned?.tier
        val explore = planned?.explore == true && planned.tier == tier
        val learnerTier = tier ?: 1
        val policy = policy()
        val verified = clean && cardsVerified && preparedTier == tier && preparedStrategy != null
        val strategy = if (verified) preparedStrategy!! else chooseStrategy(learnerTier)
        preparedStrategy = null
        preparedTier = null
        cardsVerified = false
        status.update { it.copy(strategyId = strategy.id, tier = tier, wave = 0, runPurchases = 0) }
        val where = tier?.let { " en el Nivel $it" } ?: ""
        say(
            if (clean) "Partida nueva$where con ${strategy.id}" + if (explore) " (para aprender)" else ""
            else "Partida ya empezada$where: juego, pero no cuenta para aprender",
        )
        return ActiveRun(strategy, now(), clean, learnerTier, tier, explore, policy, learningContext(), verified)
    }

    private suspend fun buyStep(r: ActiveRun) {
        val started = now()
        try {
            buyAll(r)
        } finally {
            stats.buyMs += now() - started
        }
    }

    private suspend fun buyAll(r: ActiveRun) {
        val cal = calibration
        val phaseIndex = r.strategy.phaseIndexFor(r.wave)
        if (phaseIndex != r.phaseIndex) {
            r.phaseIndex = phaseIndex
            r.bought.clear()
        }
        val weights = r.strategy.phases[phaseIndex].weights
        var buys = 0
        var attempts = 0
        wakeAffordable(r)
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
            stats.buyAttempts++
            if (spot == null) {
                stats.notFound++
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
            val tone = ScreenClassifier.tone(spot.shot.frame, button)
            val broke = r.brokeTone[choice]
            if (broke != null && tone < broke + TONE_GAIN) {
                // Sigue gris como cuando no alcanzó: pulsarlo sólo gasta tiempo.
                stats.skippedTaps++
                trace("${choice.label} Sigue gris (tono $tone): no lo pulso")
                waitForMoney(r, choice)
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
                r.brokeTone -= choice
                r.greyStreak -= choice
                r.purchases++
                stats.buys++
                buys++
            } else {
                // No cambió nada: no alcanzaba el dinero o ya está al máximo.
                trace("${choice.label} no cambió (${"%.3f".format(change)}): falta dinero, espero")
                r.brokeTone[choice] = tone
                waitForMoney(r, choice)
            }
        }
    }

    /**
     * Las mejoras en espera por falta de dinero que se ven ahora en pantalla y ya se han
     * iluminado vuelven a estar disponibles, sin esperar a que acabe su espera.
     */
    private suspend fun wakeAffordable(r: ActiveRun) {
        val waiting = r.brokeTone.keys.filter { (r.cooldownUntil[it] ?: 0L) > now() && it !in r.maxed }
        if (waiting.isEmpty()) return
        val cal = calibration
        val area = cal.listArea() ?: return
        val shot = device.capture() ?: return
        if (!inRun(shot)) return
        val tab = if (cal.tabHeaders.isEmpty()) null else ScreenClassifier.activeTab(shot.frame, cal.tabHeaders, settings.matchThreshold) ?: return
        for (upgrade in waiting) {
            val slot = cal.slotFor(upgrade) ?: continue
            if (tab != null && slot.tab != tab) continue
            val dy = ScreenClassifier.locateSlot(shot.frame, slot, cal.slots, area, settings.slotSimilarity) ?: continue
            val tone = ScreenClassifier.tone(shot.frame, (slot.button ?: slot.box).shifted(dy))
            if (tone >= r.brokeTone.getValue(upgrade) + TONE_GAIN) {
                trace("${upgrade.label} ya se ilumina: lo intento sin esperar")
                r.cooldownUntil -= upgrade
                r.greyStreak -= upgrade
            }
        }
    }

    /**
     * Espera 10 s, 20 s y luego 30 s si sigue sin alcanzar: ir a mirarla cambia de pestaña o
     * mueve la lista. Si se ilumina estando a la vista, se compra antes (ver [wakeAffordable]).
     */
    private fun waitForMoney(r: ActiveRun, upgrade: Upgrade) {
        val streak = (r.greyStreak[upgrade] ?: 0) + 1
        r.greyStreak[upgrade] = streak
        r.cooldownUntil[upgrade] = now() + minOf(settings.unaffordableCooldownMs * streak, MAX_MONEY_WAIT_MS)
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
                stats.tabOpens++
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
        stats.scrolls++
        // Por el hueco entre las dos columnas, para no pulsar ningún botón.
        val x = area.center.x
        repeat(2) { device.swipe(Pt(x, area.top + 40), Pt(x, area.bottom - 20), 150) }
        delay(800)
    }

    private suspend fun stepDown(area: Box) {
        stats.scrolls++
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
        val metric = if (settings.waveLearningEnabled) "oleadas" else "monedas/min"
        val score = if (settings.waveLearningEnabled) wave?.takeIf { it > 0 }?.toDouble() else cpm
        val counted = r.clean && reason == "muerte" && score != null && r.tier != null && r.tier == r.learnerTier &&
            r.adPolicy == policy() && r.learningContext == learningContext() &&
            (!settings.cardStrategiesEnabled || r.preparedCards)
        memory.addRun(
            RunRecord(
                r.startedAt, end, wave, coins, cpm, r.strategy.id, counted, r.purchases, reason,
                tier = r.tier,
                startWave = r.startWave,
                gained = gained.takeIf { cpm != null },
                minutes = minutes.takeIf { cpm != null },
                explore = r.explore,
                adAttempts = r.adAttempts,
                cards = r.strategy.cards.orEmpty().takeIf { r.preparedCards }.orEmpty(),
                learningMetric = metric,
            ),
        )
        if (counted) {
            val learner = Learner(settings.minPairs, settings.maxPairs, random = random)
            val brain = memory.brain
            memory.brain = brain.with(r.learnerTier, learner.report(brain.learner(r.learnerTier), r.strategy.id, score!!, metric))
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
        const val SUMMARY_MS = 60_000L
        /** Cuánto más azul tiene que verse un botón para que valga la pena volver a pulsarlo. */
        const val TONE_GAIN = 5
        const val MAX_MONEY_WAIT_MS = 30_000L
        const val STORE_RECHECK_MS = 20 * 60_000L
        const val CARD_REVIEW_MS = 6 * 60 * 60_000L
        const val WAVE_READ_MS = 15_000L
        const val MAX_SEARCH_STEPS = 14
        const val LIST_STILL = 0.01
        const val MAX_TIER_TAPS = 6
        const val MAX_HUD_TAPS = 3
        const val HUD_TAP_GAP_MS = 20_000L
        const val AD_RETRY_GAP_MS = 60_000L
        const val AD_CLOSE_GAP_MS = 2000L
        const val AD_WAIT_NOTICE_MS = 180_000L
        /** Lo que dura como mucho un video con recompensa antes de dar el premio. */
        const val AD_MIN_WATCH_MS = 30_000L
        const val AD_RESUME_PAUSE_MS = 20_000L
        const val ANCHOR_TAP_MARGIN = 20
        const val EXIT_SAME_SPOT = 12
        const val MAX_EXIT_TAPS = 3
    }
}
