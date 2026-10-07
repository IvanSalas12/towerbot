package com.arisa.towerbot.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CardStrategiesTest {
    private class StillDevice(private val frame: Frame) : Device {
        var touches = 0
        override suspend fun capture(): Shot = object : Shot { override val frame = this@StillDevice.frame }
        override suspend fun tap(p: Pt) { touches++ }
        override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) {}
        override suspend fun drag(from: Pt, to: Pt, durationMs: Long) {}
        override fun back() {}
        override fun launchGame(packageName: String) {}
        override fun foregroundPackage() = "game"
        override fun ownPackage() = "bot"
        override fun batteryTempC(): Float? = null
    }
    private val look = Template.sample(solidFrame(0x203040, 100, 200), Box(10, 100, 40, 130))
    private val layout = CardLayout(Pt(50, 190), Pt(10, 190), Box(0, 80, 100, 180),
        listOf(Box(0, 40, 45, 55), Box(50, 40, 100, 55)), Box(40, 60, 60, 75),
        listOf(OwnedCard("Salud", 2, look), OwnedCard("Dinero", 2, look), OwnedCard("Daño", 3, look)),
        listOf("Salud", "Dinero"))

    @Test fun `retadoras conservan cartas o sustituyen solo una por una poseida`() {
        val champion = DefaultStrategy.create().copy(cards = layout.initialDeck)
        var cardsChanged = 0
        var purchasesChanged = 0
        repeat(100) { seed ->
            val (_, challengerState) = Learner(random = Random(seed)).next(
                LearnerState(champion, champion.copy(id = "test"), listOf(100.0)), emptySet(), cards = layout,
            )
            assertEquals(listOf("Salud", "Dinero"), challengerState.cards)
            val (state, _) = Learner(random = Random(seed)).next(LearnerState(champion), emptySet(), cards = layout)
            val challenger = state.challenger!!
            assertEquals(layout.slots, challenger.cards!!.size)
            assertEquals(layout.slots, challenger.cards!!.distinct().size)
            assertTrue(challenger.cards!!.all { name -> layout.owned.any { it.name == name } })
            if (challenger.cards != champion.cards) {
                cardsChanged++
                assertEquals(1, challenger.cards!!.count { it !in champion.cards!! })
                assertEquals(champion.phases, challenger.phases)
            } else purchasesChanged++
        }
        assertTrue(cardsChanged > 10)
        assertTrue(purchasesChanged > 10)
    }

    @Test fun `rechaza mazos imposibles antes de tocar el juego`() = runTest {
        var touches = 0
        val device = object : Device {
            override suspend fun capture(): Shot? = null
            override suspend fun tap(p: Pt) { touches++ }
            override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) { touches++ }
            override suspend fun drag(from: Pt, to: Pt, durationMs: Long) { touches++ }
            override fun back() {}
            override fun launchGame(packageName: String) {}
            override fun foregroundPackage() = "game"
            override fun ownPackage() = "bot"
            override fun batteryTempC(): Float? = null
        }
        val equipment = CardEquipment(device, NumberReader { _, _ -> "" }, { Calibration() }, { BotSettings() }, {})
        assertFalse(equipment.equip(layout, listOf("Salud", "Desconocida")))
        assertFalse(equipment.equip(layout, listOf("Salud", "Salud")))
        assertFalse(equipment.equip(layout, listOf("Salud")))
        assertEquals(0, touches)
    }

    @Test fun `lee carta nueva con borde cian y estrellas sin incluir recuadro bloqueado`() = runTest {
        val pixels = IntArray(300 * 800) { 0x203040 }
        fun row(y: Int, color: Int) { for (x in 10..250) pixels[y * 300 + x] = color }
        for (y in 100..104) row(y, 0x4CEDFF)
        for (offset in listOf(50, 224)) for (dy in 0..4) row(100 + offset + dy, 0xFFFFFF)
        for (y in 396..400) row(y, 0x4CEDFF)
        // Tres estrellas, con separación; dos píxeles aislados no cuentan.
        for (left in listOf(30, 70, 110)) for (x in left until left + 15) pixels[362 * 300 + x] = 0xFFFFFF
        pixels[362 * 300 + 210] = 0xFFFFFF
        for (y in 451..455) row(y, 0x00FFFF)
        for (y in 747..751) row(y, 0x00FFFF)
        val device = StillDevice(ArrayFrame(300, 800, pixels))
        val cal = Calibration(screens = listOf(ScreenDef("cards", "Cartas", ScreenRole.CARDS,
            listOf(Template.sample(device.capture().frame, Box(0, 0, 100, 40))))))
        val grid = layout.copy(area = Box(0, 80, 300, 780), owned = emptyList(),
            inventoryColumns = listOf(Box(10, 100, 250, 324)))
        val equipment = CardEquipment(device, NumberReader { _, _ -> "Carta nueva" }, { cal }, { BotSettings() }, {})
        val reviewed = equipment.review(grid, 123L)!!
        assertEquals(listOf("Carta nueva"), reviewed.owned.map { it.name })
        assertEquals(3, reviewed.owned.single().stars)
        assertEquals(123L, reviewed.reviewedAt)
        assertEquals(0, device.touches)
    }

    @Test fun `rechaza seleccion incompleta y capacidad distinta sin tocar cartas`() = runTest {
        val device = StillDevice(solidFrame(0x203040, 100, 200))
        val cal = Calibration(screens = listOf(ScreenDef("cards", "Cartas", ScreenRole.CARDS,
            listOf(Template.sample(device.capture().frame, Box(0, 0, 100, 30))))))
        var capacity = "2/2"
        val reader = NumberReader { _, box -> when (box) {
            layout.countBox -> capacity
            layout.activeTitles[0] -> "Salud"
            layout.activeTitles[1] -> "Dinero"
            else -> null
        } }
        val equipment = CardEquipment(device, reader, { cal }, { BotSettings() }, {})
        assertFalse(equipment.equip(layout, listOf("Dinero", "Salud")))
        capacity = "2/3"
        assertFalse(equipment.equip(layout, listOf("Dinero", "Salud")))
        assertEquals(0, device.touches)
    }

    @Test fun `cambia solo la carta que sobra y no desliza si todas estan a la vista`() = runTest {
        val active = mutableListOf("Salud", "Daño")
        var taps = 0
        var swipes = 0
        val boxes = mapOf("Salud" to Box(10, 100, 70, 130), "Daño" to Box(100, 100, 160, 130), "Dinero" to Box(200, 100, 260, 130))
        val colors = mapOf("Salud" to 0xC00000, "Daño" to 0x0000C0, "Dinero" to 0x00C000)
        fun frame(): Frame = ArrayFrame(300, 300, IntArray(300 * 300) { index ->
            val x = index % 300
            val y = index / 300
            val card = boxes.entries.firstOrNull { (_, box) -> x in box.left until box.right && y in box.top until box.bottom }?.key
            val selected = boxes.any { (name, box) -> name in active && x in box.right - 10 until box.right + 4 && y in 175..188 }
            when {
                selected -> 0x00FF00
                card != null -> colors.getValue(card)
                else -> 0x203040
            }
        })
        val device = object : Device {
            override suspend fun capture(): Shot = object : Shot { override val frame = frame() }
            override suspend fun tap(p: Pt) {
                taps++
                val name = boxes.entries.firstOrNull { (_, box) -> p.x in box.left until box.right && p.y in box.top until box.bottom }?.key
                    ?: error("Toque fuera de las cartas: $p")
                if (name in active) active.remove(name) else active.add(name)
            }
            override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) { swipes++ }
            override suspend fun drag(from: Pt, to: Pt, durationMs: Long) { swipes++ }
            override fun back() { fail("Atrás inesperado") }
            override fun launchGame(packageName: String) { fail("Relanzamiento inesperado") }
            override fun foregroundPackage() = "game"
            override fun ownPackage() = "bot"
            override fun batteryTempC(): Float? = null
        }
        val grid = layout.copy(area = Box(0, 82, 300, 260), owned = boxes.map { (name, box) -> OwnedCard(name, 2, Template.sample(frame(), box)) }, equipped = active.toList())
        val cal = Calibration(screens = listOf(ScreenDef("cards", "Cartas", ScreenRole.CARDS,
            listOf(Template.sample(frame(), Box(0, 0, 100, 30))))))
        val reader = NumberReader { _, box -> when (box) {
            grid.countBox -> "ACTIVO\n${active.size}/2"
            else -> null
        } }
        assertTrue(CardEquipment(device, reader, { cal }, { BotSettings() }, {}).equip(grid, grid.initialDeck))
        assertEquals(listOf("Salud", "Dinero"), active)
        // Quita Daño y pone Dinero; Salud se queda puesta.
        assertEquals(2, taps)
        assertEquals(0, swipes)
    }

    @Test fun `un borde verde vecino no cuenta como carta equipada`() {
        val device = StillDevice(solidFrame(0x203040, 100, 200))
        val equipment = CardEquipment(device, NumberReader { _, _ -> null }, { Calibration() }, { BotSettings() }, {})
        fun frame(size: Int) = ArrayFrame(100, 200, IntArray(100 * 200) { i ->
            if (i % 100 in 10 until 10 + size && i / 100 in 170 until 170 + size) 0x00FF00 else 0x203040
        })
        assertFalse(equipment.selected(frame(6), look))
        assertTrue(equipment.selected(frame(16), look))
    }
}
