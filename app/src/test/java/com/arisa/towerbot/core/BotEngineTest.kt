package com.arisa.towerbot.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val W = 100
private const val H = 200
private const val RED = 0xC00000
private const val GREEN = 0x00A000
private const val BLUE = 0x0000C0
private const val SLOT_BLUE = 0x2040C0
private const val MAGENTA = 0xC000C0
private const val YELLOW = 0xC0C000
private val adBox = Box(5, 85, 35, 95)
private val closeAd = Pt(90, 10)
private val confirmAd = Pt(50, 90)
private val offerClose = Pt(90, 20)
private const val OFFER = 0x707070
private const val STORE = 0x00C0C0
private val storeTab = Pt(90, 190)
private val battleTab = Pt(10, 190)
private val storeOffer = Box(10, 85, 40, 100)
private val storeVideo = Box(10, 110, 40, 125)

private val anchorBox = Box(0, 0, W, 40)
private val slotBox = Box(10, 100, 90, 140)
private val battle = Pt(50, 20)
private val retry = Pt(50, 30)
private val goHome = Pt(80, 30)
private val tierPrev = Pt(10, 50)
private val tierNext = Pt(90, 50)
private val healthTab = Pt(50, 190)
private val waveBox = Box(0, 40, 50, 60)
private val coinsBox = Box(0, 60, 50, 80)
private val tierBox = Box(50, 40, 100, 60)
private val hudBox = Box(50, 60, 100, 80)

/**
 * Un juego de mentira: inicio rojo, partida verde, fin azul. En el nivel t cada partida
 * dura [runLength] capturas y da [coins] monedas. Las flechas del inicio cambian de nivel.
 */
private class FakeGame(
    private val runLength: Map<Int, Int>,
    val coins: Map<Int, Double>,
    var tier: Int = 1,
    private val unlocked: Int = 3,
) : Device {
    var state = "home"
    var captures = 0
    /** El contador de arriba enseña el ritmo («1.1K/min») hasta que se pulsa. */
    var hudShowsRate = false
    var hudTaps = 0
    var balance = 200_000.0
    var purchases = 0
    val played = mutableListOf<Int>()
    var offerAd = false
    var adReady = false
    var adsOpened = 0
    var adsClosed = 0
    var backCalls = 0
    var useConfirmation = false
    var adForeground = "com.TechTreeGames.TheTower"
    var gameLaunches = 0
    var storeVisits = 0
    var storeAvailable = true
    var adReturn = "run"
    /** Sin dinero el botón se ve gris y pulsarlo no compra nada. */
    var brokeUntilCapture = 0
    var brokeTaps = 0
    private val broke get() = state == "run" && captures < brokeUntilCapture
    /** Android a veces pierde la respuesta: la captura o el toque número N no vuelve nunca. */
    var lostCaptureAt = -1
    var lostTapAt = -1
    var lost = 0
    private var tapsSeen = 0
    private var adCaptures = 0

    private fun frame(): Frame {
        val base = when (state) { "home" -> RED; "store" -> STORE; "run" -> GREEN; "offer" -> OFFER; "ad" -> if (adReady) YELLOW else 0x101010; else -> BLUE }
        // Un botón azul, como los de The Tower; cambia un poco con cada compra.
        val slotColor = if (broke) 0x405080 else if (purchases % 2 == 0) SLOT_BLUE else SLOT_BLUE + 0x10
        return ArrayFrame(W, H, IntArray(W * H) { i ->
            val x = i % W
            val y = i / W
            when {
                state == "store" && x in storeOffer.left until storeOffer.right && y in storeOffer.top until storeOffer.bottom -> OFFER
                state == "store" && storeAvailable && x in storeVideo.left until storeVideo.right && y in storeVideo.top until storeVideo.bottom -> MAGENTA
                state == "run" && offerAd && x in adBox.left until adBox.right && y in adBox.top until adBox.bottom -> MAGENTA
                state == "run" && x in slotBox.left until slotBox.right && y in slotBox.top until slotBox.bottom -> slotColor
                else -> base
            }
        })
    }

    override suspend fun capture(): Shot {
        if (state == "run" && captures == lostCaptureAt) { lostCaptureAt = -1; lost++; awaitCancellation() }
        if (state == "ad" && ++adCaptures >= 90) adReady = true
        if (state == "run") balance += 10
        if (state == "run" && ++captures >= runLength.getValue(tier)) state = "over"
        val f = frame()
        return object : Shot { override val frame = f }
    }

    private fun start() {
        state = "run"
        captures = 0
        played += tier
    }

    override suspend fun tap(p: Pt) {
        if (++tapsSeen == lostTapAt) { lost++; awaitCancellation() }
        when {
            state == "home" && p == storeTab -> { state = "store"; storeVisits++ }
            state == "store" && p == battleTab -> state = "home"
            state == "store" && storeAvailable && p == storeVideo.center -> {
                state = "ad"; adReturn = "store"; adsOpened++; adCaptures = 0; adReady = false
            }
            state == "run" && offerAd && p == adBox.center -> {
                state = if (useConfirmation) "offer" else "ad"
                if (!useConfirmation) adsOpened++
                adCaptures = 0
            }
            state == "offer" && p == confirmAd -> { state = "ad"; adsOpened++; adCaptures = 0; adReady = false }
            state == "offer" && p == offerClose -> state = "run"
            state == "ad" && adReady && p == closeAd -> { state = if (useConfirmation) "offer" else adReturn; adsClosed++; offerAd = false; if (adReturn == "store") storeAvailable = false }
            state == "home" && p == battle -> start()
            state == "home" && p == tierNext -> tier = (tier + 1).coerceAtMost(unlocked)
            state == "home" && p == tierPrev -> tier = (tier - 1).coerceAtLeast(1)
            state == "over" && p == retry -> start()
            state == "over" && p == goHome -> state = "home"
            state == "run" && p == slotBox.center -> if (broke) brokeTaps++ else purchases++
            state == "run" && p == hudBox.center -> { hudTaps++; hudShowsRate = false }
        }
    }

    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) {}
    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) {}
    override fun back() { backCalls++ }
    override fun launchGame(packageName: String) { gameLaunches++ }
    override fun foregroundPackage() = if (state == "ad") adForeground else "com.TechTreeGames.TheTower"
    override fun ownPackage() = "com.arisa.towerbot"
    override fun batteryTempC(): Float? = null
}

private class FakeMemory(override val calibration: Calibration, override val settings: BotSettings) : BotMemory {
    override var brain = BrainState()
    override val runs = mutableListOf<RunRecord>()
    override fun addRun(run: RunRecord) { runs += run }
}

class BotEngineTest {
    @Test fun `revisa tienda entre partidas ve solo regalo disponible y vuelve a jugar`() = runTest {
        val game = FakeGame(mapOf(1 to 45), mapOf(1 to 1500.0))
        val cal = adCalibration().copy(
            storeAd = StoreAdLayout(storeTab, battleTab, Box(0, 79, 100, 180),
                Template.sample(solidFrame(OFFER, W, H), storeOffer), Template.sample(solidFrame(MAGENTA, W, H), storeVideo)),
            screens = adCalibration().screens + ScreenDef("store", "Tienda", ScreenRole.STORE,
                listOf(Template.sample(solidFrame(STORE, W, H), anchorBox))),
        )
        val memory = FakeMemory(cal, BotSettings(tierMode = TierMode.FIXED, gemAdsEnabled = true, storeGemAdsEnabled = true))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(25 * 60_000L)
        // Mira la Tienda al empezar y otra vez pasados 20 minutos, no en cada muerte.
        assertEquals("visitas a la Tienda", 2, game.storeVisits)
        assertEquals(1, game.adsOpened)
        assertEquals(1, game.adsClosed)
        assertTrue(game.played.size >= 6)
        assertEquals(0, game.backCalls)
    }

    @Test fun `objetivo oleadas descarta pares de monedas y registra oleadas como puntuacion`() = runTest {
        val game = FakeGame(mapOf(1 to 150), mapOf(1 to 1500.0))
        val champion = DefaultStrategy.create().copy(id = "aprendida")
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED, waveLearningEnabled = true))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(champion, champion.copy(id = "old"), listOf(9000.0), generation = 3)))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(160_000L)
        assertEquals("aprendida", memory.brain.learner(1).champion.id)
        assertEquals(3, memory.brain.learner(1).generation)
        assertTrue(memory.brain.learner(1).championScores.isNotEmpty())
        assertTrue(memory.brain.learner(1).championScores.all { it == 42.0 })
        assertEquals("oleadas", memory.runs.first().learningMetric)
    }
    private fun adCalibration() = calibration().copy(
        adButtons = mapOf(AdReward.COINS to Template.sample(solidFrame(MAGENTA, W, H), adBox)),
        screens = calibration().screens + ScreenDef("ad-close", "Cerrar anuncio", ScreenRole.AD_CLOSE,
            listOf(Template.sample(solidFrame(YELLOW, W, H), anchorBox)), tap = closeAd),
    )

    @Test fun `espera un anuncio largo sin pulsar atras y reanuda compras al cerrarlo`() = runTest {
        val game = FakeGame(mapOf(1 to 400), mapOf(1 to 1500.0)).apply {
            offerAd = true
            adForeground = "com.android.vending"
        }
        val memory = FakeMemory(adCalibration(), BotSettings(tierMode = TierMode.FIXED, coinAdsEnabled = true))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(DefaultStrategy.create().copy(
            phases = listOf(Phase(Int.MAX_VALUE, mapOf(Upgrade.DEF_PCT to 1.0))),
        ))))
        val engine = BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
        engine.runLoop(10 * 60_000L)
        assertEquals(1, game.adsOpened)
        assertEquals(1, game.adsClosed)
        assertEquals(0, game.backCalls)
        assertEquals(0, game.gameLaunches)
        assertTrue("state=${game.state}, opened=${game.adsOpened}, closed=${game.adsClosed}, purchases=${game.purchases}", game.purchases > 0)
        assertEquals(1, memory.runs.first().adAttempts)
        assertEquals("true:false", memory.brain.adPolicy)
    }

    @Test fun `interruptores independientes no abren anuncios de monedas al activar solo gemas`() = runTest {
        val game = FakeGame(mapOf(1 to 150), mapOf(1 to 1500.0)).apply { offerAd = true }
        val memory = FakeMemory(adCalibration(), BotSettings(tierMode = TierMode.FIXED, gemAdsEnabled = true))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(DefaultStrategy.create().copy(
            phases = listOf(Phase(Int.MAX_VALUE, mapOf(Upgrade.DEF_PCT to 1.0))),
        ))))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(5 * 60_000L)
        assertEquals(0, game.adsOpened)
        assertTrue(game.purchases > 0)
    }

    @Test fun `confirma monedas y cierra la oferta al volver sin repetir anuncios`() = runTest {
        val game = FakeGame(mapOf(1 to 400), mapOf(1 to 1500.0)).apply { offerAd = true; useConfirmation = true }
        val cal = adCalibration().copy(screens = adCalibration().screens + ScreenDef(
            "confirm", "Confirmar monedas", ScreenRole.COIN_AD_OFFER,
            listOf(Template.sample(solidFrame(OFFER, W, H), anchorBox)), tap = confirmAd, homeTap = offerClose,
        ))
        val memory = FakeMemory(cal, BotSettings(tierMode = TierMode.FIXED, coinAdsEnabled = true))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(DefaultStrategy.create().copy(
            phases = listOf(Phase(Int.MAX_VALUE, mapOf(Upgrade.DEF_PCT to 1.0))),
        ))))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(5 * 60_000L)
        assertEquals(1, game.adsOpened)
        assertEquals(1, game.adsClosed)
        assertEquals(0, game.backCalls)
        assertTrue(game.purchases > 0)
    }

    @Test fun `cambiar anuncios conserva estrategias y descarta pares de condiciones anteriores`() = runTest {
        val game = FakeGame(mapOf(1 to 400), mapOf(1 to 1500.0))
        val champion = DefaultStrategy.create().copy(id = "aprendida")
        val challenger = champion.copy(id = "prueba")
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED, coinAdsEnabled = true))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(champion, challenger, listOf(100.0), generation = 3)))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(20_000L)
        val learned = memory.brain.learner(1)
        assertEquals("aprendida", learned.champion.id)
        assertEquals("prueba", learned.challenger?.id)
        assertEquals(3, learned.generation)
        assertTrue(learned.championScores.isEmpty())
        assertTrue(learned.challengerScores.isEmpty())
    }
    private fun calibration(): Calibration {
        fun anchor(color: Int) = listOf(Template.sample(solidFrame(color, W, H), anchorBox))
        return Calibration(
            screens = listOf(
                ScreenDef("h", "Inicio", ScreenRole.HOME, anchor(RED), tap = battle, tierBox = tierBox, tierPrev = tierPrev, tierNext = tierNext),
                ScreenDef("r", "Partida", ScreenRole.IN_RUN, anchor(GREEN), waveBox = waveBox, tierBox = tierBox, coinsBox = hudBox),
                ScreenDef(
                    "o", "Fin", ScreenRole.GAME_OVER, anchor(BLUE),
                    tap = retry, waveBox = waveBox, coinsBox = coinsBox, tierBox = tierBox, homeTap = goHome,
                ),
            ),
            tabs = mapOf(Tab.DEFENSE to healthTab),
            slots = listOf(
                UpgradeSlot(Upgrade.DEF_PCT, Tab.DEFENSE, ListPos.TOP, slotBox, Template.sample(solidFrame(SLOT_BLUE, W, H), slotBox, 40)),
            ),
        )
    }

    private fun reader(game: FakeGame) = NumberReader { _, box ->
        when (box) {
            coinsBox -> NumberParser.format(game.coins.getValue(game.tier))
            tierBox -> "Nivel ${game.tier}"
            hudBox -> if (game.hudShowsRate) "1.1K/min" else NumberParser.format(game.balance)
            else -> "Oleada 42"
        }
    }

    @Test fun `si Android no contesta una captura, un toque o una lectura, sigue jugando`() = runTest(timeout = 30.seconds) {
        val game = FakeGame(mapOf(1 to 150), mapOf(1 to 1500.0)).apply { lostCaptureAt = 20; lostTapAt = 5 }
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED))
        var lostReads = 1
        val honest = reader(game)
        val reader = NumberReader { shot, box -> if (lostReads-- > 0) awaitCancellation() else honest.read(shot, box) }
        BotEngine(game, memory, reader, MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(30 * 60_000L)
        assertEquals("respuestas perdidas", 2, game.lost)
        assertTrue("debería seguir jugando partidas", memory.runs.count { it.endReason == "muerte" } >= 2)
    }

    @Test fun `sin dinero no vuelve a pulsar el boton gris y compra en cuanto se ilumina`() = runTest {
        val game = FakeGame(mapOf(1 to 400), mapOf(1 to 1500.0)).apply { brokeUntilCapture = 120 }
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED))
        memory.brain = BrainState(tiers = mapOf(1 to LearnerState(DefaultStrategy.create().copy(
            phases = listOf(Phase(Int.MAX_VALUE, mapOf(Upgrade.DEF_PCT to 1.0))),
        ))))
        BotEngine(game, memory, reader(game), MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(3 * 60_000L)
        // Cada partida empieza sin dinero: una sola pulsación gris por partida, no una cada 10 s.
        assertEquals("pulsó el botón gris", game.played.size, game.brokeTaps)
        assertTrue(game.purchases > 0)
    }

    @Test fun `juega partidas seguidas, compra, registra monedas y aprende`() = runTest {
        val game = FakeGame(runLength = mapOf(1 to 150), coins = mapOf(1 to 1500.0))
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED, minPairs = 1, maxPairs = 1))
        val engine = BotEngine(
            game, memory, reader(game), MutableStateFlow(BotStatus()),
            now = { testScheduler.currentTime }, random = Random(7),
        )

        engine.runLoop(stopAt = 60 * 60_000L)

        val finished = memory.runs.filter { it.endReason == "muerte" }
        assertTrue("debería jugar varias partidas, jugó ${finished.size}", finished.size >= 3)
        finished.forEach {
            assertEquals(1500.0, it.coins!!, 0.01)
            assertEquals(42, it.wave)
            assertEquals(1, it.tier)
            assertTrue(it.counted)
            assertEquals(it.coins!! / it.minutes!!, it.coinsPerMinute!!, 0.01)
        }
        assertTrue("debería comprar Defensa %", game.purchases > 0)
        // Con 1 par por duelo, tras 2 partidas válidas ya hubo al menos un duelo.
        assertTrue(memory.brain.learner(1).log.any { it.startsWith("✅") || it.startsWith("❌") })
        assertEquals("bot detenido", memory.runs.last().endReason)
    }

    // Simula muchas partidas: con todas las pruebas a la vez puede pasar del minuto por defecto.
    @Test fun `prueba los niveles y se queda jugando el que mas paga`() = runTest(timeout = 5.minutes) {
        // El Nivel 2 paga el triple por minuto que el 1; el 3 casi nada.
        val game = FakeGame(
            runLength = mapOf(1 to 400, 2 to 200, 3 to 100),
            coins = mapOf(1 to 4_000.0, 2 to 6_000.0, 3 to 300.0),
        )
        val memory = FakeMemory(calibration(), BotSettings())
        val engine = BotEngine(
            game, memory, reader(game), MutableStateFlow(BotStatus()),
            now = { testScheduler.currentTime }, random = Random(11),
        )

        engine.runLoop(stopAt = 8 * 60 * 60_000L)

        assertTrue("no probó todos los niveles: ${game.played.toSet()}", game.played.toSet() == setOf(1, 2, 3))
        val runs = memory.runs.filter { it.endReason == "muerte" }
        runs.forEach { assertEquals("el nivel apuntado no es el jugado", game.played[runs.indexOf(it)], it.tier) }
        val minutes = runs.groupBy { it.tier }.mapValues { (_, l) -> l.sumOf { it.minutes ?: 0.0 } }
        val total = minutes.values.sum()
        val share2 = minutes.getValue(2) / total
        assertTrue("debería pasar casi todo el tiempo en el Nivel 2: $minutes", share2 > 0.6)
        val exploring = runs.filter { it.explore }.sumOf { it.minutes ?: 0.0 } / total
        assertTrue("exploró el ${(exploring * 100).toInt()} % del tiempo", exploring < 0.3)
        assertTrue(memory.brain.log.any { it.contains("Pruebo el Nivel 3") })
    }

    @Test fun `si el contador enseña el ritmo lo pulsa una vez y vuelve a medir monedas`() = runTest {
        val game = FakeGame(runLength = mapOf(1 to 400), coins = mapOf(1 to 1500.0))
        game.state = "run"
        game.hudShowsRate = true
        val memory = FakeMemory(calibration(), BotSettings(tierMode = TierMode.FIXED))
        val engine = BotEngine(
            game, memory, reader(game), MutableStateFlow(BotStatus()),
            now = { testScheduler.currentTime }, random = Random(5),
        )

        engine.runLoop(stopAt = 20 * 60_000L)

        assertEquals(1, game.hudTaps)
        // La primera partida la cogió empezada: sus monedas salen del contador de arriba.
        val first = memory.runs.first()
        assertTrue("debería medir monedas aunque no la viera empezar: $first", first.gained != null && first.gained!! > 0)
    }
}
