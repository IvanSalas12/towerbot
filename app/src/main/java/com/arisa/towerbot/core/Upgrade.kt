package com.arisa.towerbot.core

enum class Tab(val label: String) {
    ATTACK("Ataque"),
    DEFENSE("Defensa"),
    UTILITY("Utilidad"),
}

/** Dónde está una mejora dentro de su lista: el bot sólo sabe ir al tope o al fondo. */
enum class ListPos(val label: String) {
    TOP("Lista arriba del todo"),
    BOTTOM("Lista abajo del todo"),
}

/**
 * Las mejoras con dinero (cash) de dentro de la partida.
 * [tab] es sólo una pista para ordenar el selector; la pestaña real la dice la calibración.
 */
enum class Upgrade(val label: String, val tab: Tab) {
    DANO("Daño", Tab.ATTACK),
    VEL_ATAQUE("Velocidad de ataque", Tab.ATTACK),
    PROB_CRITICO("Prob. crítico", Tab.ATTACK),
    FACTOR_CRITICO("Factor crítico", Tab.ATTACK),
    ALCANCE("Alcance", Tab.ATTACK),
    DANO_METRO("Daño/metro", Tab.ATTACK),
    MULTI_PROB("Multidisparo (prob.)", Tab.ATTACK),
    MULTI_OBJ("Multidisparo (objetivos)", Tab.ATTACK),
    FUEGO_RAPIDO_PROB("Fuego rápido (prob.)", Tab.ATTACK),
    FUEGO_RAPIDO_DUR("Fuego rápido (duración)", Tab.ATTACK),
    REBOTE_PROB("Rebote (prob.)", Tab.ATTACK),
    REBOTE_OBJ("Rebote (objetivos)", Tab.ATTACK),
    REBOTE_ALCANCE("Rebote (alcance)", Tab.ATTACK),
    SALUD("Salud", Tab.DEFENSE),
    REGEN("Regeneración", Tab.DEFENSE),
    DEF_PCT("Defensa %", Tab.DEFENSE),
    DEF_ABS("Defensa absoluta", Tab.DEFENSE),
    ESPINAS("Espinas", Tab.DEFENSE),
    ROBO_VIDA("Robo de vida", Tab.DEFENSE),
    KNOCKBACK_PROB("Knockback (prob.)", Tab.DEFENSE),
    KNOCKBACK_FUERZA("Fuerza de empuje", Tab.DEFENSE),
    ESFERA_VEL("Velocidad de esfera", Tab.DEFENSE),
    ESFERAS("Esferas", Tab.DEFENSE),
    ONDA_TAMANO("Onda de choque (tamaño)", Tab.DEFENSE),
    ONDA_FREC("Onda de choque (frecuencia)", Tab.DEFENSE),
    BONUS_DINERO("Bonus de dinero", Tab.UTILITY),
    DINERO_OLEADA("Dinero/oleada", Tab.UTILITY),
    MONEDAS_MUERTE("Monedas/muerte", Tab.UTILITY),
    MONEDAS_OLEADA("Monedas/oleada", Tab.UTILITY),
    ATAQUE_GRATIS("Mejora de ataque gratis", Tab.UTILITY),
    DEFENSA_GRATIS("Mejora de defensa gratis", Tab.UTILITY),
    UTILIDAD_GRATIS("Mejora de utilidad gratis", Tab.UTILITY),
    INTERES_OLEADA("Interés/oleada", Tab.UTILITY),
}
