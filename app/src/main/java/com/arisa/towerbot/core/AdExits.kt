package com.arisa.towerbot.core

import java.text.Normalizer

/** Un botón para salir de un anuncio, encontrado por su nombre y no por calibración. */
data class AdExit(val at: Pt, val kind: Kind, val label: String) {
    /** En orden de preferencia: reanudar antes que cerrar, por si cerrar pierde el premio. */
    enum class Kind { RESUME, CLOSE, SKIP }
}

/** Qué hace un botón de un anuncio según su texto, su descripción o su id. */
object AdExits {
    private val resume = Regex("""\b(resume|reanudar|keep watching|continue watching|seguir viendo|volver al video|back to video)\b""")
    // Sin límite de palabra: los ids suelen ir pegados («ivClose», «btnskip»).
    private val close = Regex("""^[x×✕✖]$|close|cerrar|dismiss|fechar""")
    private val skip = Regex("""skip|omitir|saltar""")

    /**
     * Lo que lleva al anunciante, y los subtítulos («closed captions»): un botón así
     * nunca se pulsa aunque diga «cerrar».
     */
    private val never = Regex("""\b(install|instalar|download|descargar|learn more|mas informacion|open|abrir|get|obtener|visit|visitar|shop|comprar)\b|caption|subtitul""")

    fun kindOf(label: String): AdExit.Kind? {
        val words = Normalizer.normalize(label.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace('_', ' ').trim()
        if (never.containsMatchIn(words)) return null
        return when {
            resume.containsMatchIn(words) -> AdExit.Kind.RESUME
            close.containsMatchIn(words) -> AdExit.Kind.CLOSE
            skip.containsMatchIn(words) -> AdExit.Kind.SKIP
            else -> null
        }
    }
}
