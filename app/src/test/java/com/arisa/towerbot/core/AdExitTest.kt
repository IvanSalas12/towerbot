package com.arisa.towerbot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private const val AW = 540
private const val AH = 1200
private const val DARK = 0x1C1E22
private const val PLAY = 0x00A000
private const val AD_BUTTON = 0xC000C0
private val adButton = Box(20, 1000, 120, 1060)
private val xAt = Pt(503, 62)
private val panel = Box(150, 1050, 390, 1200)

private fun canvas(color: Int) = IntArray(AW * AH) { color }

/** Una X de [r] de medio lado y 3 px de grosor, como las de los anuncios. */
private fun IntArray.drawX(c: Pt, r: Int, color: Int) {
    for (t in -r..r) for (d in -1..1) {
        this[(c.y + t) * AW + c.x + t + d] = color
        this[(c.y - t) * AW + c.x + t + d] = color
    }
}

private fun IntArray.fill(b: Box, color: Int) {
    for (y in b.top until b.bottom) for (x in b.left until b.right) this[y * AW + x] = color
}

/**
 * Un juego con un anuncio que no está calibrado: el video dura [videoCaptures] capturas
 * y luego enseña una X (o sólo botones con nombre, si [drawn] es false).
 */
private class AdGame(private val videoCaptures: Int, private val drawn: Boolean = true) : Device {
    var state = "run"
    var adCaptures = 0
    val taps = mutableListOf<Pt>()
    var closedAt: Int? = null
    var backCalls = 0
    var exits: List<AdExit> = emptyList()
    var bottomPanel = false
    val ended get() = adCaptures >= videoCaptures

    override suspend fun capture(): Shot {
        if (state == "ad") adCaptures++
        val px = when (state) {
            "ad" -> canvas(DARK).also {
                if (ended && drawn) it.drawX(xAt, 9, 0xFFFFFF)
                if (bottomPanel) it.fill(panel, 0x3060F0)
            }
            else -> canvas(PLAY).also { if (closedAt == null) it.fill(adButton, AD_BUTTON) }
        }
        val f = ArrayFrame(AW, AH, px)
        return object : Shot { override val frame = f }
    }

    override suspend fun tap(p: Pt) {
        taps += p
        when {
            state == "run" && p == adButton.center && closedAt == null -> state = "ad"
            state == "ad" && ended && (p.near(xAt, 6) || exits.any { it.at == p && it.kind == AdExit.Kind.CLOSE }) -> {
                state = "run"
                closedAt = adCaptures
            }
        }
    }

    override fun adExits() = if (state == "ad") exits else emptyList()
    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) {}
    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) {}
    override fun back() { backCalls++ }
    override fun launchGame(packageName: String) {}
    override fun foregroundPackage() = "com.TechTreeGames.TheTower"
    override fun ownPackage() = "com.arisa.towerbot"
    override fun batteryTempC(): Float? = null
}

private class AdMemory(override val calibration: Calibration) : BotMemory {
    override val settings = BotSettings(tierMode = TierMode.FIXED, coinAdsEnabled = true)
    override var brain = BrainState()
    override val runs = mutableListOf<RunRecord>()
    override fun addRun(run: RunRecord) { runs += run }
}

class AdExitTest {
    private fun calibration(vararg extra: ScreenDef) = Calibration(
        screenWidth = AW, screenHeight = AH,
        screens = listOf(
            ScreenDef("r", "Partida", ScreenRole.IN_RUN, listOf(Template.sample(ArrayFrame(AW, AH, canvas(PLAY)), Box(0, 200, AW, 400)))),
        ) + extra,
        adButtons = mapOf(AdReward.COINS to Template.sample(ArrayFrame(AW, AH, canvas(PLAY).also { it.fill(adButton, AD_BUTTON) }), adButton)),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.play(game: AdGame, cal: Calibration, minutes: Int = 3) =
        BotEngine(game, AdMemory(cal), { _, _ -> null }, MutableStateFlow(BotStatus()), now = { testScheduler.currentTime })
            .runLoop(minutes * 60_000L)

    @Test fun `encuentra una X clara en una esquina y su centro`() {
        val px = canvas(DARK).also { it.drawX(xAt, 9, 0xFFFFFF) }
        val found = requireNotNull(CloseMark.find(ArrayFrame(AW, AH, px)))
        assertTrue("${found.center}", found.center.near(xAt, 2))
    }

    @Test fun `encuentra una X gris sobre fondo blanco`() {
        val at = Pt(30, 40)
        val px = canvas(0xFFFFFF).also { it.drawX(at, 10, 0x707070) }
        assertTrue(requireNotNull(CloseMark.find(ArrayFrame(AW, AH, px))).center.near(at, 2))
    }

    @Test fun `no ve X en una pantalla lisa ni en una x pegada a un numero`() {
        assertNull(CloseMark.find(ArrayFrame(AW, AH, canvas(DARK))))
        // «x7»: la x tiene el número pegado, como en las insignias de los videos.
        val px = canvas(DARK).also {
            it.drawX(xAt, 9, 0xFFFFFF)
            it.fill(Box(xAt.x + 12, xAt.y - 9, xAt.x + 16, xAt.y + 10), 0xFFFFFF)
        }
        assertNull(CloseMark.find(ArrayFrame(AW, AH, px)))
    }

    @Test fun `entiende los botones con nombre de los anuncios`() {
        assertEquals(AdExit.Kind.CLOSE, AdExits.kindOf("Close"))
        assertEquals(AdExit.Kind.CLOSE, AdExits.kindOf("Cerrar anuncio"))
        assertEquals(AdExit.Kind.CLOSE, AdExits.kindOf("ivClose"))
        assertEquals(AdExit.Kind.CLOSE, AdExits.kindOf("×"))
        assertEquals(AdExit.Kind.SKIP, AdExits.kindOf("Omitir"))
        assertEquals(AdExit.Kind.SKIP, AdExits.kindOf("mbridge_skip_btn"))
        assertEquals(AdExit.Kind.RESUME, AdExits.kindOf("Reanudar el vídeo"))
        assertNull(AdExits.kindOf("Instalar"))
        assertNull(AdExits.kindOf("Install Now"))
        assertNull(AdExits.kindOf("Más información"))
        assertNull(AdExits.kindOf("Closed captions"))
        assertNull(AdExits.kindOf("Ad 1 of 2"))
    }

    @Test fun `cierra un anuncio sin calibrar cuando aparece su X y no antes de tiempo`() = runTest {
        val game = AdGame(videoCaptures = 40)
        play(game, calibration())
        assertEquals("run", game.state)
        val closed = requireNotNull(game.closedAt)
        assertTrue("cerró en la captura $closed", closed >= 40)
        assertTrue("pulsó antes de la X: ${game.taps}", game.taps.drop(1).all { it.near(xAt, 6) })
        assertEquals(0, game.backCalls)
    }

    @Test fun `un cierre calibrado con el ancla lejos de la X espera a ver la X`() = runTest {
        // Como «cerrardo»: el ancla es el panel de abajo, que se ve durante todo el video.
        val anchor = Template.sample(ArrayFrame(AW, AH, canvas(DARK).also { it.fill(panel, 0x3060F0) }), panel)
        val cal = calibration(ScreenDef("c", "cerrardo", ScreenRole.AD_CLOSE, listOf(anchor), tap = xAt))
        val game = AdGame(videoCaptures = 200).apply { bottomPanel = true }
        play(game, cal)
        val closed = requireNotNull(game.closedAt)
        assertTrue(closed >= 200)
        assertEquals(1, game.taps.count { it == xAt })
    }

    @Test fun `prefiere reanudar a cerrar y usa los botones con nombre`() = runTest {
        val resume = AdExit(Pt(270, 700), AdExit.Kind.RESUME, "Resume video")
        val close = AdExit(Pt(400, 700), AdExit.Kind.CLOSE, "Close video")
        val game = AdGame(videoCaptures = 0, drawn = false).apply { exits = listOf(close, resume) }
        play(game, calibration())
        assertEquals("run", game.state)
        // Reanuda hasta tres veces; si el aviso no se va, cierra.
        assertEquals(listOf(resume.at, resume.at, resume.at, close.at), game.taps.drop(1))
        assertEquals(0, game.backCalls)
    }

    /**
     * Capturas de anuncios reales, si están en la carpeta de TOWERBOT_AD_SHOTS (1080×2400):
     * `NN.png` con la X en `NN.txt` («x y»), o sin .txt si no tiene X.
     */
    @Test fun `capturas reales de anuncios`() {
        val dir = System.getenv("TOWERBOT_AD_SHOTS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        dir.listFiles { f -> f.extension == "png" }!!.sorted().forEach { file ->
            val img = ImageIO.read(file)
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            val found = CloseMark.find(ArrayFrame(img.width, img.height, px))
            val want = File(file.path.removeSuffix(".png") + ".txt").takeIf { it.exists() }
                ?.readText()?.trim()?.split(" ")?.map(String::toInt)
            println("${file.name}: $found (esperado $want)")
            if (want == null) assertNull(file.name, found)
            else assertTrue(file.name, found != null && abs(found.center.x - want[0]) <= 6 && abs(found.center.y - want[1]) <= 6)
        }
    }
}
