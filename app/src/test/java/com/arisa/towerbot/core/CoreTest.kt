package com.arisa.towerbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

fun solidFrame(color: Int, w: Int = 100, h: Int = 200) = ArrayFrame(w, h, IntArray(w * h) { color })

class NumberParserTest {
    @Test fun `numeros del juego`() {
        assertEquals(78_830.0, NumberParser.parse("78,83K")!!, 0.01)
        assertEquals(78_830.0, NumberParser.parse("78.83K")!!, 0.01)
        assertEquals(1_500_000.0, NumberParser.parse("$1.5M")!!, 0.01)
        assertEquals(1234.0, NumberParser.parse("1,234")!!, 0.01)
        assertEquals(1_234_567.0, NumberParser.parse("1.234.567")!!, 0.01)
        assertEquals(2.5e12, NumberParser.parse("Monedas: 2,5T")!!, 1.0)
        assertEquals(200.0, NumberParser.parse("200 coins")!!, 0.01)
        // Leído así en tu teléfono, con el icono de la moneda detrás:
        assertEquals(30_550.0, NumberParser.parse("30.55K C")!!, 0.01)
        assertEquals(30_550.0, NumberParser.parse("30.55KC")!!, 0.01)
        assertNull(NumberParser.parse("sin números"))
    }

    @Test fun `oleada`() {
        assertEquals(200, NumberParser.parseInt("Oleada 200"))
        assertEquals(1234, NumberParser.parseInt("Wave 1,234"))
        assertNull(NumberParser.parseInt("Oleada"))
    }
}

class TemplateTest {
    @Test fun `una zona igual da distancia cero y una distinta no`() {
        val red = solidFrame(0xFF0000)
        val box = Box(10, 10, 50, 30)
        val t = Template.sample(red, box)
        assertEquals(0.0, t.distance(red), 1e-9)
        assertTrue(t.distance(solidFrame(0x0000FF)) > 0.5)
    }

    @Test fun `la forma se reconoce aunque cambie el brillo`() {
        val w = 60
        val h = 20
        fun striped(dark: Int, light: Int) = ArrayFrame(w, h, IntArray(w * h) { i -> if ((i % w) / 10 % 2 == 0) dark else light })
        val box = Box(0, 0, w, h)
        val look = Template.sample(striped(0x202020, 0xE0E0E0), box, 12)
        assertTrue(look.similarity(striped(0x101010, 0x909090)) > 0.9)
        val shifted = ArrayFrame(w, h, IntArray(w * h) { i -> if (((i % w) + 10) / 10 % 2 == 0) 0x202020 else 0xE0E0E0 })
        assertTrue(look.similarity(shifted) < 0)
    }

    @Test fun `una zona fuera de la pantalla nunca coincide`() {
        val t = Template.sample(solidFrame(0xFF0000, 300, 300), Box(200, 200, 280, 280))
        assertEquals(1.0, t.distance(solidFrame(0xFF0000)), 0.0)
    }
}

class ClassifierTest {
    private fun screen(role: ScreenRole, color: Int) =
        ScreenDef(role.name, role.label, role, listOf(Template.sample(solidFrame(color), Box(0, 0, 100, 40))))

    @Test fun `elige la pantalla más parecida dentro del umbral`() {
        val screens = listOf(screen(ScreenRole.HOME, 0xFF0000), screen(ScreenRole.GAME_OVER, 0x0000FF))
        assertEquals(ScreenRole.HOME, ScreenClassifier.classify(solidFrame(0xF00000), screens, 0.1, 0.6)?.screen?.role)
        assertNull(ScreenClassifier.classify(solidFrame(0x00FF00), screens, 0.1, 0.6))
    }
}

class ShapeTest {
    /** Fondo claro con una línea de "texto" oscuro a la altura [textRow]. */
    private fun page(textRow: Int) = ArrayFrame(100, 100, IntArray(100 * 100) { i ->
        val x = i % 100
        val y = i / 100
        if (y in textRow until textRow + 2 && x in 10 until 70) 0x202020 else 0xF4F4F8
    })

    @Test fun `dos zonas claras con el texto en otro sitio no son la misma pantalla`() {
        val box = Box(0, 0, 100, 40)
        val screens = listOf(ScreenDef("h", "Inicio", ScreenRole.HOME, listOf(Template.sample(page(10), box))))
        val moved = ScreenClassifier.rank(page(28), screens).first()
        assertTrue("el color medio se parece", moved.distance < 0.1)
        assertNull(ScreenClassifier.classify(page(28), screens, 0.1, 0.6))
        assertEquals(ScreenRole.HOME, ScreenClassifier.classify(page(10), screens, 0.1, 0.6)?.screen?.role)
    }

    @Test fun `una zona lisa no se confunde con una que tiene texto`() {
        val box = Box(0, 0, 100, 40)
        val screens = listOf(ScreenDef("h", "Inicio", ScreenRole.HOME, listOf(Template.sample(page(10), box))))
        val blank = solidFrame(0xF4F4F8, 100, 100)
        assertNull(ScreenClassifier.classify(blank, screens, 0.1, 0.6))
    }
}

class AllocatorTest {
    @Test fun `reparte según los pesos`() {
        val weights = mapOf(Upgrade.SALUD to 3.0, Upgrade.DEF_PCT to 1.0)
        val bought = mutableMapOf<Upgrade, Int>()
        repeat(8) {
            val u = Allocator.choose(weights, bought, weights.keys)!!
            bought[u] = (bought[u] ?: 0) + 1
        }
        assertEquals(6, bought[Upgrade.SALUD])
        assertEquals(2, bought[Upgrade.DEF_PCT])
    }

    @Test fun `ignora lo que no está disponible`() {
        val weights = mapOf(Upgrade.SALUD to 10.0, Upgrade.DEF_PCT to 1.0)
        assertEquals(Upgrade.DEF_PCT, Allocator.choose(weights, emptyMap(), setOf(Upgrade.DEF_PCT)))
        assertNull(Allocator.choose(weights, emptyMap(), setOf(Upgrade.ALCANCE)))
    }
}

class LearnerTest {
    private val available = setOf(Upgrade.SALUD, Upgrade.DEF_PCT, Upgrade.BONUS_DINERO, Upgrade.VEL_ATAQUE)

    @Test fun `alterna campeona y retadora y asciende a la que gana claro`() {
        val learner = Learner(minPairs = 2, maxPairs = 4, random = Random(1))
        var state = LearnerState(DefaultStrategy.create())
        val used = mutableListOf<String>()
        repeat(4) {
            val (s, strategy) = learner.next(state, available)
            used += strategy.id
            val score = if (strategy.id == "pdf") 100.0 else 150.0
            state = learner.report(s, strategy.id, score)
        }
        assertEquals(listOf("pdf", used[1], "pdf", used[1]), used)
        assertNotEquals("pdf", state.champion.id)
        assertEquals(1, state.generation)
        assertNull(state.challenger)
        assertTrue(state.log.last().startsWith("✅"))
    }

    @Test fun `descarta una retadora que no mejora`() {
        val learner = Learner(minPairs = 2, maxPairs = 4, random = Random(2))
        var state = LearnerState(DefaultStrategy.create())
        repeat(4) {
            val (s, strategy) = learner.next(state, available)
            state = learner.report(s, strategy.id, 100.0)
        }
        assertEquals("pdf", state.champion.id)
        assertTrue(state.log.last().startsWith("❌"))
    }

    @Test fun `con datos revueltos espera mas pares y al final decide por la media`() {
        val champion = listOf(100.0, 100.0, 100.0)
        val challenger = listOf(300.0, 50.0, 120.0)
        assertEquals(Duel.Verdict.CONTINUE, Duel.judge(champion, challenger, 2, 4, 0.03).verdict)
        val last = Duel.judge(champion, challenger, 2, 3, 0.03)
        assertEquals(Duel.Verdict.ACCEPT, last.verdict)
        assertTrue(last.low < 0 && last.high > 0.5)
    }

    @Test fun `las mutaciones mantienen las fases en orden`() {
        val random = Random(3)
        var s = DefaultStrategy.create()
        repeat(500) { i ->
            s = Mutator.mutate(s, available, random, "m$i", deathWaves = if (i % 2 == 0) listOf(59, 60, 59, 186) else emptyList())
            val limits = s.phases.map { it.untilWave }
            assertEquals(limits.sorted(), limits)
            assertEquals(limits.size, limits.toSet().size)
            assertEquals(Int.MAX_VALUE, limits.last())
            assertFalse(s.phases.any { p -> p.weights.values.any { it < 0 || it.isNaN() } })
        }
    }

    @Test fun `si muere en la oleada 60 la mayoria de cambios van a defenderse antes`() {
        val base = DefaultStrategy.create()
        val random = Random(4)
        val survival = Mutator.SURVIVAL.toSet()
        var directed = 0
        repeat(300) { i ->
            val m = Mutator.mutate(base, available, random, "d$i", deathWaves = listOf(59, 60, 59, 186, 181))
            if (!m.note.contains("datos:")) return@repeat
            directed++
            val p = m.phases[2] // oleadas 31-60, donde muere
            val earlier = p.untilWave < 60
            val moreDefense = p.weights.filterKeys { it in survival }.values.sum() > 0
            assertTrue("el cambio dirigido no ataca la muerte: ${m.note}", earlier || moreDefense)
        }
        assertTrue("sólo $directed de 300 retadoras miran los datos", directed in 120..240)
    }
}

class TierTest {
    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun run(tier: Int, minutes: Double, coins: Double, hoursAgo: Double = 1.0, explore: Boolean = false, wave: Int = 100) =
        RunRecord(
            startedAt = now - (hoursAgo * hour).toLong() - (minutes * 60_000).toLong(),
            endedAt = now - (hoursAgo * hour).toLong(),
            wave = wave, coins = coins, coinsPerMinute = coins / minutes, strategyId = "pdf", counted = true,
            purchases = 0, endReason = "muerte", tier = tier, gained = coins, minutes = minutes, explore = explore,
        )

    @Test fun `lee el nivel aunque el lector confunda el 1`() {
        assertEquals(1, NumberParser.parseTier("Nivel 1"))
        assertEquals(1, NumberParser.parseTier("Nivel l"))
        assertEquals(3, NumberParser.parseTier("NIVEL 3"))
        assertEquals(12, NumberParser.parseTier("Tier 12"))
        assertEquals(2, NumberParser.parseTier("2"))
        assertNull(NumberParser.parseTier("Oleada 358"))
    }

    @Test fun `el contador de monedas ignora lecturas imposibles`() {
        val t = CoinTracker()
        t.add(0, 130_000.0)
        assertNull(t.gained())
        t.add(15_000, 130_300.0) // confirma la primera
        t.add(30_000, 1_303_000.0) // se comió el punto decimal: imposible
        t.add(45_000, 130_900.0)
        t.add(60_000, 131_200.0)
        assertEquals(1_200.0, t.gained()!!, 0.1)
        assertEquals(1.0, t.minutes()!!, 0.001)
    }

    @Test fun `si pierde el hilo guarda lo que llevaba y sigue`() {
        val t = CoinTracker()
        t.add(0, 100_000.0)
        t.add(60_000, 101_000.0)
        // Gastaste 50K a mano: el contador baja y ya no vuelve.
        repeat(4) { t.add(120_000L + it * 15_000, 51_000.0 + it * 100) }
        t.add(240_000, 51_700.0)
        assertEquals(1_000.0 + 400.0, t.gained()!!, 0.1)
    }

    @Test fun `sin datos se queda en el nivel en el que esta`() {
        val c = TierPlanner.choose(emptyList(), now, 3, 0.15, current = 2)
        assertEquals(2, c.tier)
        assertFalse(c.explore)
    }

    @Test fun `juega el nivel que mas paga y explora los que no conoce`() {
        val runs = listOf(
            run(1, 180.0, 650_000.0, hoursAgo = 10.0),
            run(2, 32.0, 86_000.0, hoursAgo = 5.0),
            run(2, 10.0, 13_000.0, hoursAgo = 4.0),
        )
        // Nadie ha explorado en 24 h: toca probar el Nivel 3, que no tiene datos.
        val first = TierPlanner.choose(runs, now, 3, 0.15, current = 1)
        assertEquals(3, first.tier)
        assertTrue(first.explore)
        // Con el tope de exploración cubierto, juega el mejor: el Nivel 1.
        val explored = runs + run(3, 60.0, 10_000.0, hoursAgo = 2.0, explore = true)
        val second = TierPlanner.choose(explored, now, 3, 0.15, current = 3)
        assertEquals(1, second.tier)
        assertFalse(second.explore)
    }

    @Test fun `lo reciente pesa mas que lo viejo`() {
        val runs = listOf(
            run(1, 100.0, 100_000.0, hoursAgo = 150.0), // hace casi una semana: 1000/min
            run(1, 100.0, 300_000.0, hoursAgo = 1.0), // hoy: 3000/min
        )
        val s = TierPlanner.stats(runs, 1..1, now).single()
        assertTrue("ritmo ${s.rate}", s.rate!! > 2_600)
    }
}
