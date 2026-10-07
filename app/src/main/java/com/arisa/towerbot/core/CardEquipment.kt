package com.arisa.towerbot.core

import kotlinx.coroutines.delay

/** Cambia el mazo sólo fuera de partida y verifica nombres y capacidad antes de jugar. */
class CardEquipment(
    private val device: Device,
    private val reader: NumberReader,
    private val calibration: () -> Calibration,
    private val settings: () -> BotSettings,
    private val trace: (String) -> Unit,
) {
    private suspend fun cardsShot(): Shot? {
        val shot = device.capture() ?: return null
        val s = settings()
        return shot.takeIf {
            ScreenClassifier.classify(it.frame, calibration().screens, s.matchThreshold, s.matchShape)?.screen?.role == ScreenRole.CARDS
        }
    }

    private suspend fun count(shot: Shot, layout: CardLayout): Int? {
        val count = reader.read(shot, layout.countBox) ?: return null
        trace("Cartas activas: contador «$count»")
        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(count) ?: return null
        if (match.groupValues[2].toInt() != layout.slots) return null
        return match.groupValues[1].toInt().takeIf { it in 0..layout.slots }
    }

    private fun markerBox(look: Template) = Box(look.box.right - 35, look.box.bottom + 35,
        look.box.right + 30, look.box.bottom + 105)

    /** La marca verde está fuera del dibujo; estrellas blancas y bordes cian no cuentan. */
    internal fun selected(frame: Frame, look: Template): Boolean {
        val box = markerBox(look).clampTo(frame.width, frame.height)
        var green = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val c = frame.argb(x, y)
            val r = c shr 16 and 255
            val g = c shr 8 and 255
            val b = c and 255
            if (g > 180 && g > r * 1.4 && g > b * 1.4) green++
        }
        // La marca real ocupa ~780 píxeles verdes; un borde vecino puede dejar ~40.
        return green >= 150
    }

    /** Lee cartas con título y estrellas; los recuadros con candado no tienen esos separadores. */
    suspend fun review(layout: CardLayout, now: Long): CardLayout? {
        if (layout.inventoryColumns.isEmpty()) return layout
        BetweenRuns.top(device, layout.area)
        val found = linkedMapOf<String, OwnedCard>()
        val equipped = linkedSetOf<String>()
        var previous: Template? = null
        for (page in 0 until 8) {
            val shot = cardsShot() ?: return null
            if (previous?.distance(shot.frame)?.let { it < 0.004 } == true) break
            previous = Template.sample(shot.frame, layout.area, 24)
            for (column in layout.inventoryColumns) {
                val x = column.center.x
                fun line(y: Int, cyan: Boolean = false): Boolean {
                    if (y !in 0 until shot.frame.height) return false
                    return (x - column.width / 4..x + column.width / 4 step 4).count { px ->
                        val c = shot.frame.argb(px, y)
                        (cyan || (c shr 16 and 255) > 240) && (c shr 8 and 255) > (if (cyan) 200 else 240) && (c and 255) > 240
                    } >= column.width / 8
                }
                fun lineAround(y: Int, cyan: Boolean = false) = (y - 4..y + 4).any { line(it, cyan) }
                var y = layout.area.top
                while (y + layout.tileHeight <= layout.area.bottom) {
                    if (!line(y, true) || !lineAround(y + 50) || !lineAround(y + layout.iconBottom) ||
                        !lineAround(y + layout.tileHeight - 4, true)) { y++; continue }
                    val top = y + 2
                    val title = Box(column.left, top + 7, column.right, top + 47)
                    val text = reader.read(shot, title)?.trim()?.replace('\n', ' ')
                    if (!text.isNullOrBlank()) {
                        val name = layout.owned.firstOrNull { BetweenRuns.normalize(it.name) == BetweenRuns.normalize(text) }?.name ?: text
                        val stars = countStars(shot.frame, Box(column.left, top + layout.starRow, column.right, top + layout.starRow + 1))
                        if (stars in 1..7) {
                            val look = Template.sample(shot.frame, Box(column.left, top + 6, column.right, top + layout.iconBottom - 2), 24)
                            if (name !in found) {
                                found[name] = OwnedCard(name, stars, look)
                                trace("Inventario: $name, $stars estrellas")
                            }
                            if (selected(shot.frame, look)) equipped += name
                        }
                    }
                    y += layout.tileHeight
                }
            }
            BetweenRuns.down(device, layout.area)
        }
        // Una lectura incompleta no borra cartas ni altera el aprendizaje.
        if (found.isEmpty() || layout.owned.any { it.name !in found }) return null
        return layout.copy(owned = found.values.toList(), reviewedAt = now, equipped = equipped.toList())
    }

    internal fun countStars(frame: Frame, row: Box): Int {
        var count = 0
        var width = 0
        for (x in row.left until row.right) {
            val c = frame.argb(x, row.top)
            val white = (c shr 16 and 255) > 230 && (c shr 8 and 255) > 230 && (c and 255) > 230
            if (white) width++ else { if (width >= 6) count++; width = 0 }
        }
        if (width >= 6) count++
        return count
    }

    suspend fun equip(layout: CardLayout, target: List<String>): Boolean {
        if (target.size != layout.slots || target.distinct().size != target.size ||
            target.any { name -> layout.owned.none { it.name == name } }) return false
        var shot = cardsShot() ?: return false
        val existingCount = count(shot, layout) ?: return false
        if (layout.equipped.distinct().size != existingCount) return false

        /** Dónde está [name] en [shot], si se ve entera con su marca. */
        fun visible(shot: Shot, name: String): Template? {
            val card = layout.owned.firstOrNull { it.name == name } ?: return null
            val dy = BetweenRuns.locate(shot.frame, card.look, layout.area) ?: return null
            return card.look.shifted(dy).takeIf { markerBox(it).bottom <= layout.area.bottom }
        }

        /** Mira primero lo que ya hay en pantalla; sólo si no está, sube la lista y baja página a página. */
        suspend fun find(name: String): Pair<Shot, Template>? {
            visible(shot, name)?.let { return shot to it }
            BetweenRuns.top(device, layout.area)
            for (page in 0 until 8) {
                shot = cardsShot() ?: return null
                val look = visible(shot, name)
                trace("Busco carta $name, página $page: ${if (look != null) "encontrada" else "no visible"}")
                if (look != null) return shot to look
                BetweenRuns.down(device, layout.area)
            }
            return null
        }

        suspend fun allSelected(names: Collection<String>) = names.all { name ->
            val (s, look) = find(name) ?: return false
            selected(s.frame, look)
        }

        // Primero comprueba las marcas actuales, sin tocar la fila que puede desplazarse.
        if (!allSelected(layout.equipped)) return false
        // Sólo se cambian las cartas que sobran o faltan; las que ya están se quedan.
        val remove = layout.equipped.filter { it !in target }
        val add = target.filter { it !in layout.equipped }
        var equipped = existingCount
        for (name in remove) {
            val (_, look) = find(name) ?: return false
            trace("Quito carta $name")
            device.tap(look.box.center)
            delay(600)
            shot = cardsShot() ?: return false
            if (count(shot, layout) != --equipped || selected(shot.frame, look)) return false
        }
        for (name in add) {
            val (_, look) = find(name) ?: return false
            trace("Equipo carta $name")
            device.tap(look.box.center)
            delay(600)
            shot = cardsShot() ?: return false
            if (count(shot, layout) != ++equipped || !selected(shot.frame, look)) return false
        }
        val verified = (remove.isEmpty() && add.isEmpty() || allSelected(target)) &&
            count(cardsShot()?.also { shot = it } ?: return false, layout) == target.size
        trace("Mazo ${if (verified) "verificado" else "sin verificar"}: $target")
        return verified
    }
}
