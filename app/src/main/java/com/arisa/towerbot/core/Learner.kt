package com.arisa.towerbot.core

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lo que el bot ha aprendido en un nivel: la estrategia campeona, la retadora que está
 * probando y las monedas/min de cada una en este duelo, partida a partida y en orden.
 */
@Serializable
data class LearnerState(
    val champion: Strategy,
    val challenger: Strategy? = null,
    val championScores: List<Double> = emptyList(),
    val challengerScores: List<Double> = emptyList(),
    val generation: Int = 0,
    val log: List<String> = emptyList(),
) {
    fun withLog(line: String) = copy(log = (log + line).takeLast(60))
}

/**
 * Todo lo aprendido: un aprendizaje por nivel, porque lo que funciona en el Nivel 1 (donde
 * la torre aguanta horas) no es lo que funciona en el 3 (donde muere pronto). [log] es el
 * diario de qué nivel eligió y por qué.
 */
@Serializable
data class BrainState(
    val tiers: Map<Int, LearnerState> = emptyMap(),
    val log: List<String> = emptyList(),
) {
    fun learner(tier: Int) = tiers[tier] ?: LearnerState(DefaultStrategy.create())
    fun with(tier: Int, state: LearnerState) = copy(tiers = tiers + (tier to state))
    fun withLog(line: String) = copy(log = (log + line).takeLast(80))
}

/**
 * Compara campeona y retadora por pares: cada partida de la retadora con la de la campeona
 * que se jugó justo antes. Así lo que cambie entre medias (tus compras en el Taller, la
 * hora) afecta a las dos por igual.
 *
 * Mira la ganancia de cada par en escala logarítmica (×2 y ÷2 pesan lo mismo) y su margen
 * de error. Decide en cuanto los datos son claros, sin esperar a un número fijo de partidas,
 * y como mucho tras [maxPairs] pares.
 */
object Duel {
    enum class Verdict { CONTINUE, ACCEPT, REJECT }

    /** [gain]: cuánto mejor es la retadora de media (0.10 = +10 %), con su margen [low]..[high]. */
    data class Result(val verdict: Verdict, val pairs: Int, val gain: Double, val low: Double, val high: Double)

    fun judge(
        champion: List<Double>,
        challenger: List<Double>,
        minPairs: Int,
        maxPairs: Int,
        minGain: Double,
        z: Double = 1.0,
    ): Result {
        val n = min(champion.size, challenger.size)
        if (n == 0) return Result(Verdict.CONTINUE, 0, 0.0, -1.0, Double.POSITIVE_INFINITY)
        val d = (0 until n).map { ln(max(challenger[it], 1.0) / max(champion[it], 1.0)) }
        val mean = d.average()
        val se = if (n >= 2) sqrt(d.sumOf { (it - mean) * (it - mean) } / (n - 1) / n) else Double.POSITIVE_INFINITY
        val threshold = ln(1 + minGain)
        val low = mean - z * se
        val high = mean + z * se
        val verdict = when {
            n < minPairs -> Verdict.CONTINUE
            low > threshold -> Verdict.ACCEPT
            high < threshold -> Verdict.REJECT
            n >= maxPairs -> if (mean > threshold) Verdict.ACCEPT else Verdict.REJECT
            else -> Verdict.CONTINUE
        }
        return Result(verdict, n, exp(mean) - 1, exp(low) - 1, if (high.isInfinite()) high else exp(high) - 1)
    }
}

/**
 * Aprendizaje sin IA: campeona contra retadora, en cada nivel por separado.
 *
 * La retadora es la campeona con un cambio pequeño. Si hay datos de dónde muere la torre,
 * la mayoría de las veces el cambio va dirigido a eso (ver [Mutator]). Se alternan partida a
 * partida y [Duel] decide cuándo hay datos suficientes.
 */
class Learner(
    private val minPairs: Int = 2,
    private val maxPairs: Int = 4,
    private val minGain: Double = 0.03,
    private val random: Random = Random.Default,
) {
    /**
     * La estrategia de la próxima partida. [deathWaves]: dónde murió la campeona en sus
     * últimas partidas de este nivel; orienta el cambio de la próxima retadora.
     */
    fun next(state: LearnerState, available: Set<Upgrade>, deathWaves: List<Int> = emptyList()): Pair<LearnerState, Strategy> {
        var s = state
        val challenger = s.challenger ?: run {
            val id = "g${s.generation + 1}-${random.nextInt(0x1000, 0x10000).toString(16)}"
            Mutator.mutate(s.champion, available, random, id, deathWaves).also {
                s = s.copy(challenger = it).withLog("🧪 Pruebo ${it.id}: ${it.note}")
            }
        }
        val strategy = if (s.challengerScores.size < s.championScores.size) challenger else s.champion
        return s to strategy
    }

    fun report(state: LearnerState, strategyId: String, coinsPerMinute: Double): LearnerState {
        var s = when (strategyId) {
            state.champion.id -> state.copy(championScores = state.championScores + coinsPerMinute)
            state.challenger?.id -> state.copy(challengerScores = state.challengerScores + coinsPerMinute)
            else -> return state
        }
        val challenger = s.challenger ?: return s
        if (s.championScores.size != s.challengerScores.size) return s

        val r = Duel.judge(s.championScores, s.challengerScores, minPairs, maxPairs, minGain)
        val detail = "${pct(r.gain)} de media en ${r.pairs} ${if (r.pairs == 1) "par" else "pares"}" +
            if (r.pairs >= 2) " (entre ${pct(r.low)} y ${pct(r.high)})" else ""
        s = when (r.verdict) {
            Duel.Verdict.CONTINUE -> return s
            Duel.Verdict.ACCEPT -> s.copy(champion = challenger, generation = s.generation + 1)
                .withLog("✅ ${challenger.id} gana: $detail. ${challenger.note}")
            Duel.Verdict.REJECT -> s.withLog("❌ ${challenger.id} descartada: $detail")
        }
        return s.copy(challenger = null, championScores = emptyList(), challengerScores = emptyList())
    }

    private fun pct(g: Double) =
        if (g.isInfinite()) "∞" else (if (g >= 0) "+" else "") + (g * 100).roundToInt() + " %"
}

object Mutator {
    /** Mejoras que hacen aguantar más: vida, defensa y matar antes de que lleguen. */
    val SURVIVAL = listOf(
        Upgrade.SALUD, Upgrade.DEF_PCT, Upgrade.DEF_ABS, Upgrade.REGEN, Upgrade.VEL_ATAQUE, Upgrade.DANO,
        Upgrade.ESPINAS, Upgrade.ROBO_VIDA, Upgrade.KNOCKBACK_PROB, Upgrade.MULTI_PROB,
        Upgrade.PROB_CRITICO, Upgrade.FACTOR_CRITICO,
    )

    /** Qué parte de las retadoras sale de los datos (dónde muere) en vez de al azar. */
    const val DIRECTED_SHARE = 0.6

    /**
     * Un cambio pequeño (uno o dos retoques) sobre [base]. Sólo toca mejoras calibradas.
     * Con datos de [deathWaves], casi siempre uno de los retoques ataca el punto donde muere.
     */
    fun mutate(
        base: Strategy,
        available: Set<Upgrade>,
        random: Random,
        id: String,
        deathWaves: List<Int> = emptyList(),
    ): Strategy {
        val phases = base.phases.toMutableList()
        val notes = mutableListOf<String>()
        val directed = deathWaves.size >= 2 && random.nextDouble() < DIRECTED_SHARE
        // La mediana baja: si unas veces muere en la 60 y otras en la 185, primero hay que pasar la 60.
        if (directed) towardSurvival(phases, available, random, deathWaves.sorted()[(deathWaves.size - 1) / 2])?.let(notes::add)
        repeat(if (directed) random.nextInt(2) else 1 + random.nextInt(2)) {
            when (random.nextInt(10)) {
                in 0..3 -> scaleWeight(phases, available, random)
                in 4..6 -> transferWeight(phases, available, random)
                else -> shiftBoundary(phases, random)
            }?.let(notes::add)
        }
        return Strategy(
            id = id,
            phases = phases,
            parentId = base.id,
            note = notes.ifEmpty { listOf("sin cambios") }.joinToString("; "),
        )
    }

    /**
     * La torre suele morir hacia la oleada [wave]. Si muere en una fase que no es la última,
     * esa fase está gastando en economía mientras la torre se cae: o se acorta, para que la
     * defensa de la fase siguiente llegue antes, o se le mete defensa. Si muere en la última,
     * se refuerza la defensa ahí.
     */
    private fun towardSurvival(phases: MutableList<Phase>, available: Set<Upgrade>, random: Random, wave: Int): String? {
        val k = phases.indexOfFirst { wave <= it.untilWave }.let { if (it < 0) phases.lastIndex else it }
        val where = "muere hacia la oleada $wave (${phaseName(phases, k)})"
        if (k < phases.lastIndex && random.nextBoolean()) {
            val old = phases[k].untilWave
            val lo = (if (k == 0) 0 else phases[k - 1].untilWave) + 1
            if (lo < old) {
                val new = (wave * (0.55 + random.nextDouble() * 0.3)).roundToInt().coerceIn(lo, old - 1)
                phases[k] = phases[k].copy(untilWave = new)
                return "datos: $where → la fase ${k + 1} acaba en la oleada $new (antes $old) para que la defensa llegue antes"
            }
        }
        val pool = SURVIVAL.filter { available.isEmpty() || it in available }
        if (pool.isEmpty()) return null
        // Las prioridades de defensa que ya tiene el plan, empezando por la fase siguiente.
        val reference = phases.getOrNull(k + 1) ?: phases[k]
        val weighted = pool.filter { (reference.weights[it] ?: 0.0) > 0 }
        val u = if (weighted.isEmpty()) pool[random.nextInt(pool.size)] else pickWeighted(weighted, reference.weights, random)
        val p = phases[k]
        val add = p.weights.values.sum().coerceAtLeast(1.0) * (0.2 + random.nextDouble() * 0.4)
        phases[k] = p.copy(weights = p.weights + (u to (p.weights[u] ?: 0.0) + add))
        return "datos: $where → más ${u.label} en esa fase"
    }

    private fun pickWeighted(options: List<Upgrade>, weights: Map<Upgrade, Double>, random: Random): Upgrade {
        var r = random.nextDouble() * options.sumOf { weights[it] ?: 0.0 }
        for (u in options) {
            r -= weights[u] ?: 0.0
            if (r <= 0) return u
        }
        return options.last()
    }

    private fun phaseName(phases: List<Phase>, i: Int): String {
        val from = if (i == 0) 1 else phases[i - 1].untilWave + 1
        val to = phases[i].untilWave
        return if (to == Int.MAX_VALUE) "oleada $from+" else "oleadas $from-$to"
    }

    private fun pool(phase: Phase, available: Set<Upgrade>) =
        available.ifEmpty { phase.weights.keys }.toList()

    private fun scaleWeight(phases: MutableList<Phase>, available: Set<Upgrade>, random: Random): String? {
        val i = random.nextInt(phases.size)
        val p = phases[i]
        val options = pool(p, available)
        if (options.isEmpty()) return null
        val u = options[random.nextInt(options.size)]
        val old = p.weights[u] ?: 0.0
        val new = if (old <= 0) p.weights.values.sum().coerceAtLeast(1.0) * 0.1
        else old * exp(gaussian(random) * 0.35)
        phases[i] = p.copy(weights = p.weights + (u to new))
        val factor = if (old <= 0) "nueva" else "×%.2f".format(new / old)
        return "${phaseName(phases, i)}: ${u.label} $factor"
    }

    private fun transferWeight(phases: MutableList<Phase>, available: Set<Upgrade>, random: Random): String? {
        val i = random.nextInt(phases.size)
        val p = phases[i]
        val givers = p.weights.filter { (u, w) -> w > 0 && (available.isEmpty() || u in available) }.keys.toList()
        if (givers.isEmpty()) return null
        val from = givers[random.nextInt(givers.size)]
        val takers = pool(p, available).filter { it != from }
        if (takers.isEmpty()) return null
        val to = takers[random.nextInt(takers.size)]
        val amount = p.weights.getValue(from) * (0.1 + random.nextDouble() * 0.2)
        phases[i] = p.copy(
            weights = p.weights + (from to p.weights.getValue(from) - amount) + (to to (p.weights[to] ?: 0.0) + amount),
        )
        return "${phaseName(phases, i)}: parte de ${from.label} pasa a ${to.label}"
    }

    private fun shiftBoundary(phases: MutableList<Phase>, random: Random): String? {
        if (phases.size < 2) return null
        val i = random.nextInt(phases.size - 1)
        val current = phases[i].untilWave
        val lo = (if (i == 0) 0 else phases[i - 1].untilWave) + 1
        val nextLimit = phases[i + 1].untilWave
        val hi = if (nextLimit == Int.MAX_VALUE) current * 2 else nextLimit - 1
        if (lo > hi) return null
        val new = (current * (0.8 + random.nextDouble() * 0.45)).roundToInt().coerceIn(lo, hi)
        if (new == current) return null
        phases[i] = phases[i].copy(untilWave = new)
        return "fase ${i + 1} termina en la oleada $new (antes $current)"
    }

    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)
    }
}
