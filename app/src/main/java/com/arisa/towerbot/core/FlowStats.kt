package com.arisa.towerbot.core

/**
 * Cuánto cuesta cada parte del bucle: capturas, lecturas de texto, toques, búsquedas
 * de mejoras y el tiempo muerto entre partidas. Sale por logcat (`MEDIDA`) para comparar
 * versiones del bot con números, no a ojo.
 */
class FlowStats(private val now: () -> Long) {
    var captures = 0; private set
    var captureMs = 0L; private set
    var reads = 0; private set
    var readMs = 0L; private set
    var taps = 0; private set
    var swipes = 0; private set
    var buyAttempts = 0
    var buys = 0
    var tabOpens = 0
    var scrolls = 0
    var notFound = 0
    /** Botones que no pulsó porque seguían grises como cuando faltó dinero. */
    var skippedTaps = 0
    var buyMs = 0L
    /** Tiempo por tipo de pantalla, según lo que el bot creyó ver en cada paso. */
    val screenMs = linkedMapOf<String, Long>()
    /** De ver el fin de partida (o arrancar en el inicio) a empezar la siguiente. */
    val betweenRuns = mutableListOf<Long>()
    private var waitingSince: Long? = null
    private val startedAt = now()

    fun captured(ms: Long) { captures++; captureMs += ms }
    fun read(ms: Long) { reads++; readMs += ms }
    fun tapped() { taps++ }
    fun swiped() { swipes++ }
    fun spent(screen: String, ms: Long) { screenMs[screen] = (screenMs[screen] ?: 0L) + ms }

    /** Se acabó una partida (o se arrancó fuera de partida): empieza el tiempo muerto. */
    fun runOver() { if (waitingSince == null) waitingSince = now() }

    /** Empezó una partida: devuelve el tiempo muerto que costó llegar, si se estaba midiendo. */
    fun runStarted(): Long? = waitingSince?.let { now() - it }.also {
        waitingSince = null
        if (it != null) betweenRuns += it
    }

    fun summary(): String {
        val min = (now() - startedAt) / 60_000.0
        fun per(n: Int) = if (min > 0) "%.1f".format(n / min) else "-"
        fun avg(total: Long, n: Int) = if (n > 0) total / n else 0
        return "MEDIDA %.1f min · capturas %d (%s/min, %d ms) · lecturas %d (%s/min, %d ms) · toques %d · deslizar %d · " .format(min, captures, per(captures), avg(captureMs, captures), reads, per(reads), avg(readMs, reads), taps, swipes) +
            "compras %d de %d intentos (%s/min, %d ms por intento, %d sin pulsar) · pestañas %d · bajar lista %d · no encontrada %d · " .format(buys, buyAttempts, per(buys), avg(buyMs, buyAttempts), skippedTaps, tabOpens, scrolls, notFound) +
            "entre partidas %s s · pantallas %s".format(
                betweenRuns.joinToString("/") { "%.0f".format(it / 1000.0) }.ifEmpty { "-" },
                screenMs.entries.joinToString(", ") { "${it.key} %.0f s".format(it.value / 1000.0) },
            )
    }
}

/** El teléfono, contando lo que se le pide y lo que tarda. */
class MeasuredDevice(private val inner: Device, private val stats: FlowStats, private val now: () -> Long) : Device by inner {
    override suspend fun capture(): Shot? {
        val t = now()
        return inner.capture().also { stats.captured(now() - t) }
    }

    override suspend fun tap(p: Pt) { stats.tapped(); inner.tap(p) }
    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) { stats.swiped(); inner.swipe(from, to, durationMs) }
    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) { stats.swiped(); inner.drag(from, to, durationMs) }
}

class MeasuredReader(private val inner: NumberReader, private val stats: FlowStats, private val now: () -> Long) : NumberReader {
    override suspend fun read(shot: Shot, box: Box): String? {
        val t = now()
        return inner.read(shot, box).also { stats.read(now() - t) }
    }
}
