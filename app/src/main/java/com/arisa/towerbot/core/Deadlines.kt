package com.arisa.towerbot.core

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android no siempre contesta: si se pierde la respuesta de una captura o de un toque (pasa
 * cuando cambia algo en Accesibilidad justo en ese momento), el bot se quedaría esperando
 * para siempre con la burbuja en ■. Con plazo, lo que no vuelve cuenta como fallido y el
 * bucle sigue.
 */
class DeadlineDevice(private val inner: Device, private val trace: (String) -> Unit = {}) : Device by inner {
    override suspend fun capture(): Shot? {
        var answered = false
        val shot = withTimeoutOrNull(CAPTURE_MS) { inner.capture().also { answered = true } }
        if (!answered) trace("Android no devolvió la captura en ${CAPTURE_MS / 1000} s: la doy por fallida")
        return shot
    }

    override suspend fun tap(p: Pt) = gesture("el toque en $p", 0) { inner.tap(p) }
    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) = gesture("el deslizamiento", durationMs) { inner.swipe(from, to, durationMs) }
    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) = gesture("el arrastre", durationMs) { inner.drag(from, to, durationMs) }

    private suspend fun gesture(what: String, durationMs: Long, block: suspend () -> Unit) {
        if (withTimeoutOrNull(durationMs + GESTURE_MARGIN_MS) { block() } == null) {
            trace("Android no confirmó $what: sigo")
        }
    }

    companion object {
        /** Una captura tarda décimas; con los reintentos por ir demasiado seguido, menos de 2 s. */
        const val CAPTURE_MS = 5_000L
        const val GESTURE_MARGIN_MS = 5_000L
    }
}

/** El lector de texto con plazo: una lectura que no vuelve cuenta como no leída. */
class DeadlineReader(private val inner: NumberReader, private val trace: (String) -> Unit = {}) : NumberReader {
    override suspend fun read(shot: Shot, box: Box): String? {
        var answered = false
        val text = withTimeoutOrNull(READ_MS) { inner.read(shot, box).also { answered = true } }
        if (!answered) trace("El lector de texto no contestó en ${READ_MS / 1000} s: lo doy por no leído")
        return text
    }

    companion object {
        const val READ_MS = 10_000L
    }
}
