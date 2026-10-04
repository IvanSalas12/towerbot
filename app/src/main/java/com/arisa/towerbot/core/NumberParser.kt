package com.arisa.towerbot.core

import kotlin.math.abs

/** Convierte lo que se lee en pantalla ("78,83K", "Oleada 200", "$1.5M") en números. */
object NumberParser {
    private val suffixes = linkedMapOf(
        'K' to 1e3, 'M' to 1e6, 'B' to 1e9, 'T' to 1e12, 'q' to 1e15, 'Q' to 1e18,
        's' to 1e21, 'S' to 1e24, 'O' to 1e27, 'N' to 1e30, 'D' to 1e33,
    )
    // El sufijo puede ir pegado a lo que sigue: el lector a veces junta "30.55K" con el icono ("30.55KC").
    private val token = Regex("""(\d[\d.,]*\d|\d)\s*([KMBTqQsSOND])?""")

    /** El primer número con su sufijo de magnitud, o null si no hay ninguno. */
    fun parse(text: String): Double? {
        val m = token.find(text) ?: return null
        val suffix = m.groupValues[2].firstOrNull()
        val base = parsePlain(m.groupValues[1], suffix != null) ?: return null
        return base * (suffix?.let { suffixes[it] } ?: 1.0)
    }

    /**
     * El nivel de «Nivel 3» (o «Tier 3»). El lector a veces cambia el 1 por l, I o |.
     * Sin la palabra, acepta un número suelto pequeño.
     */
    fun parseTier(text: String): Int? {
        Regex("""(?:nivel|tier)\s*([0-9lI|]{1,2})""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return m.groupValues[1].map { if (it.isDigit()) it else '1' }.joinToString("").toIntOrNull()
        }
        return parseInt(text)?.takeIf { it in 1..30 }
    }

    /** El primer entero, para la oleada. */
    fun parseInt(text: String): Int? =
        Regex("""\d[\d.,]*""").find(text)?.value?.filter { it.isDigit() }?.toIntOrNull()

    private fun parsePlain(raw: String, hasSuffix: Boolean): Double? {
        val dots = raw.count { it == '.' }
        val commas = raw.count { it == ',' }
        val normalized = when {
            dots > 0 && commas > 0 -> {
                // El que va último es el decimal.
                if (raw.lastIndexOf('.') > raw.lastIndexOf(',')) raw.replace(",", "")
                else raw.replace(".", "").replace(',', '.')
            }
            dots + commas == 0 -> raw
            dots + commas > 1 -> raw.replace(".", "").replace(",", "")
            else -> {
                val sep = if (dots == 1) '.' else ','
                val decimals = raw.length - raw.indexOf(sep) - 1
                // "1,234" sin sufijo son miles; "78,83K" o "1.5" son decimales.
                if (!hasSuffix && decimals == 3) raw.replace(sep.toString(), "")
                else raw.replace(sep, '.')
            }
        }
        return normalized.toDoubleOrNull()
    }

    fun format(value: Double): String {
        var v = value
        var suffix = ""
        for ((s, mult) in suffixes.entries.reversed()) {
            if (abs(value) >= mult) {
                v = value / mult
                suffix = s.toString()
                break
            }
        }
        return if (suffix.isEmpty()) "%.0f".format(v) else "%.2f%s".format(v, suffix)
    }
}
