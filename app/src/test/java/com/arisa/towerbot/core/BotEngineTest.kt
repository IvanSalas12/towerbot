package com.arisa.towerbot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val W = 100
private const val H = 200
private const val RED = 0xC00000
private const val GREEN = 0x00A000
private const val BLUE = 0x0000C0
private const val SLOT_BLUE = 0x2040C0

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

    private fun frame(): Frame {
        val base = when (state) { "home" -> RED; "run" -> GREEN; else -> BLUE }
        // Un botón azul, como los de The Tower; cambia un poco con cada compra.
        val slotColor = if (purchases % 2 == 0) SLOT_BLUE else SLOT_BLUE + 0x10
        return ArrayFrame(W, H, IntArray(W * H) { i ->
            val x = i % W
            val y = i / W
            if (state == "run" && x in slotBox.left until slotBox.right && y in slotBox.top until slotBox.bottom) slotColor else base
        })
    }

    override suspend fun capture(): Shot {
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
        when {
            state == "home" && p == battle -> start()
            state == "home" && p == tierNext -> tier = (tier + 1).coerceAtMost(unlocked)
            state == "home" && p == tierPrev -> tier = (tier - 1).coerceAtLeast(1)
            state == "over" && p == retry -> start()
            state == "over" && p == goHome -> state = "home"
            state == "run" && p == slotBox.center -> purchases++
            state == "run" && p == hudBox.center -> { hudTaps++; hudShowsRate = false }
        }
    }

    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) {}
    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) {}
    override fun back() {}
    override fun launchGame(packageName: String) {}
    override fun foregroundPackage() = "com.TechTreeGames.TheTower"
    override fun ownPackage() = "com.arisa.towerbot"
    override fun batteryTempC(): Float? = null
}

private class FakeMemory(override val calibration: Calibration, override val settings: BotSettings) : BotMemory {
    override var brain = BrainState()
    override val runs = mutableListOf<RunRecord>()
    override fun addRun(run: RunRecord) { runs += run }
}

class BotEngineTest {
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

    @Test fun `prueba los niveles y se queda jugando el que mas paga`() = runTest {
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
