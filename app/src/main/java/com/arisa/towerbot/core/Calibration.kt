package com.arisa.towerbot.core

import kotlinx.serialization.Serializable

enum class ScreenRole(val label: String) {
    HOME("Inicio (botón BATALLA)"),
    IN_RUN("En partida"),
    GAME_OVER("Fin de partida"),
    POPUP("Ventana emergente"),
}

/**
 * Una pantalla del juego que el bot sabe reconocer.
 *
 * Se reconoce cuando TODAS sus [anchors] se parecen a lo que hay ahora en pantalla.
 * [tap] es lo que pulsa al verla: BATALLA en el inicio, REINTENTAR al morir,
 * la X de una ventana emergente.
 *
 * [coinsBox] son las monedas ganadas al morir, o el contador de monedas de arriba en
 * partida. [tierBox] es donde pone «Nivel N». Para cambiar de nivel solo, el fin de
 * partida necesita [homeTap] (INICIO) y el inicio las flechas [tierPrev] y [tierNext].
 */
@Serializable
data class ScreenDef(
    val id: String,
    val name: String,
    val role: ScreenRole,
    val anchors: List<Template>,
    val tap: Pt? = null,
    val waveBox: Box? = null,
    val coinsBox: Box? = null,
    val tierBox: Box? = null,
    val homeTap: Pt? = null,
    val tierPrev: Pt? = null,
    val tierNext: Pt? = null,
)

/**
 * Una mejora de la lista. [look] es cómo se ve [box] (su nombre, que nunca cambia):
 * el bot lo busca subiendo y bajando la lista, porque la lista se desplaza.
 * [button] es el botón de compra; se mueve junto con [box]. Si falta, se pulsa [box].
 */
@Serializable
data class UpgradeSlot(
    val upgrade: Upgrade,
    val tab: Tab,
    val pos: ListPos = ListPos.TOP,
    val box: Box,
    val look: Template,
    val button: Box? = null,
)

@Serializable
data class Calibration(
    val gamePackage: String = "com.TechTreeGames.TheTower",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screens: List<ScreenDef> = emptyList(),
    val tabs: Map<Tab, Pt> = emptyMap(),
    val slots: List<UpgradeSlot> = emptyList(),
    /** El título de cada pestaña ("MEJORAS DE ATAQUE"…): así sabe cuál está abierta. */
    val tabHeaders: Map<Tab, Template> = emptyMap(),
    /** La parte visible de la lista de mejoras, entre el título y las pestañas. */
    val upgradeList: Box? = null,
) {
    fun availableUpgrades(): Set<Upgrade> = slots.map { it.upgrade }.toSet()

    fun screensOf(role: ScreenRole) = screens.filter { it.role == role }

    fun slotFor(upgrade: Upgrade) = slots.firstOrNull { it.upgrade == upgrade }

    /** La zona donde se desliza la lista de mejoras; si no se marcó, la unión de sus botones. */
    fun listArea(): Box? = upgradeList ?: slots.map { it.box }.reduceOrNull { a, b -> a.union(b) }

    /** Qué falta para que el bot pueda jugar solo. Vacío = listo. */
    fun missing(): List<String> = buildList {
        if (screensOf(ScreenRole.HOME).none { it.tap != null }) add("Pantalla de inicio con el punto de BATALLA")
        if (screensOf(ScreenRole.IN_RUN).isEmpty()) add("Pantalla en partida")
        if (screensOf(ScreenRole.GAME_OVER).none { it.tap != null }) add("Fin de partida con el punto de REINTENTAR")
        if (slots.isEmpty()) add("Al menos un botón de mejora")
        val tabsUsed = slots.map { it.tab }.toSet()
        (tabsUsed - tabs.keys).forEach { add("Botón de la pestaña ${it.label}") }
        (tabsUsed - tabHeaders.keys).forEach { add("Título de la pestaña ${it.label}") }
    }

    /** El bot puede elegir el nivel él solo: ir al inicio, leer el nivel y mover las flechas. */
    fun canChooseTier(): Boolean =
        screensOf(ScreenRole.GAME_OVER).any { it.homeTap != null } &&
            screensOf(ScreenRole.HOME).any { it.tierBox != null && it.tierPrev != null && it.tierNext != null }

    /** Lo que no impide jugar, pero sin ello el bot aprende peor. */
    fun recommended(): List<String> = buildList {
        if (screensOf(ScreenRole.IN_RUN).none { it.waveBox != null }) add("Zona del número de oleada (en partida)")
        if (screensOf(ScreenRole.GAME_OVER).none { it.coinsBox != null }) add("Zona de monedas ganadas (fin de partida)")
        if (screensOf(ScreenRole.IN_RUN).none { it.coinsBox != null }) add("Contador de monedas de arriba (en partida)")
        if (screensOf(ScreenRole.GAME_OVER).none { it.tierBox != null } && screensOf(ScreenRole.IN_RUN).none { it.tierBox != null }) {
            add("Zona de «Nivel N» (en partida o en el fin de partida)")
        }
        if (!canChooseTier()) add("INICIO en el fin de partida, y «Nivel N» y sus flechas en el inicio (para elegir el nivel solo)")
    }
}

/** [distance]: la peor diferencia de color entre sus anclas. [shape]: el peor parecido de forma. */
data class ScreenMatch(val screen: ScreenDef, val distance: Double, val shape: Double)

object ScreenClassifier {
    /** Todas las pantallas calibradas ordenadas de más a menos parecida. */
    fun rank(frame: Frame, screens: List<ScreenDef>): List<ScreenMatch> =
        screens.filter { it.anchors.isNotEmpty() }
            .map { s ->
                ScreenMatch(s, s.anchors.maxOf { it.distance(frame) }, s.anchors.minOf { it.similarity(frame) })
            }
            .sortedBy { it.distance }

    /**
     * Hace falta que coincidan el color Y la forma: dos zonas claras con poco texto
     * tienen casi el mismo color medio aunque digan cosas distintas.
     */
    fun classify(frame: Frame, screens: List<ScreenDef>, threshold: Double, minShape: Double): ScreenMatch? =
        rank(frame, screens).firstOrNull { it.distance <= threshold && it.shape >= minShape }

    /**
     * Qué pestaña muestra el panel según su título, o null si el panel está cerrado.
     * Importa porque tocar la pestaña que ya está abierta cierra el panel.
     */
    fun activeTab(frame: Frame, headers: Map<Tab, Template>, threshold: Double): Tab? =
        headers.entries.map { it.key to it.value.distance(frame) }
            .filter { it.second <= threshold }
            .minByOrNull { it.second }?.first

    /**
     * Busca [slot] a lo largo de [area] y devuelve cuánto se ha movido (dy) desde donde
     * se calibró, o null si no está a la vista. Para darlo por bueno tiene que parecerse
     * lo bastante y más que cualquier otra mejora de su misma columna en ese sitio.
     */
    fun locateSlot(frame: Frame, slot: UpgradeSlot, all: List<UpgradeSlot>, area: Box, minSimilarity: Double): Int? {
        val look = slot.look
        val h = look.box.height
        var best = -1.0
        var bestDy = 0
        fun probe(dy: Int) {
            val top = look.box.top + dy
            if (top < area.top || top + h > area.bottom) return
            val s = look.shifted(dy).similarity(frame)
            if (s > best) {
                best = s
                bestDy = dy
            }
        }
        var top = area.top
        while (top + h <= area.bottom) {
            probe(top - look.box.top)
            top += COARSE_STEP
        }
        val coarse = bestDy
        for (d in coarse - COARSE_STEP + 1 until coarse + COARSE_STEP) probe(d)
        if (best < minSimilarity) return null
        val here = look.box.shifted(bestDy)
        val beaten = all.any { other ->
            other !== slot && other.look.cols == look.cols && other.look.rows == look.rows &&
                other.look.box.width == look.box.width && other.look.box.height == h &&
                other.look.box.left == look.box.left &&
                other.look.movedTo(here).similarity(frame) > best
        }
        return if (beaten) null else bestDy
    }

    /**
     * ¿Está el botón al máximo? Medido en The Tower (azul menos rojo, de media):
     * comprable ≈ 65, sin dinero ≈ 50 (gris azulado), "Máx" ≈ -12 (dorado/marrón).
     */
    fun looksMaxed(frame: Frame, button: Box): Boolean {
        val b = button.clampTo(frame.width, frame.height)
        if (b.width <= 0 || b.height <= 0) return false
        val cells = Template.sample(frame, b, 12).rgb
        val red = cells.sumOf { (it shr 16) and 0xFF } / cells.size
        val blue = cells.sumOf { it and 0xFF } / cells.size
        return blue - red < MAXED_BLUE_MARGIN
    }

    private const val COARSE_STEP = 6
    private const val MAXED_BLUE_MARGIN = 25
}
