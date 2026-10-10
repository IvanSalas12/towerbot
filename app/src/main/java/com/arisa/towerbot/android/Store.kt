package com.arisa.towerbot.android

import com.arisa.towerbot.core.BotMemory
import com.arisa.towerbot.core.BotSettings
import com.arisa.towerbot.core.BrainState
import com.arisa.towerbot.core.Calibration
import com.arisa.towerbot.core.RunRecord
import com.arisa.towerbot.core.CardLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

/** Todo lo que el bot recuerda, en archivos JSON dentro de la app. */
class Store(private val dir: File) : BotMemory {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private val _calibration = MutableStateFlow(load("calibration.json", Calibration.serializer()) ?: Calibration())
    private val _settings = MutableStateFlow(load("settings.json", BotSettings.serializer()) ?: BotSettings())
    // learner.json era el aprendizaje de antes, sin niveles: se deja de usar.
    private val _brain = MutableStateFlow(load("brain.json", BrainState.serializer()) ?: BrainState())
    private val _runs = MutableStateFlow(load("runs.json", ListSerializer(RunRecord.serializer())) ?: emptyList())

    val calibrationFlow: StateFlow<Calibration> = _calibration
    val settingsFlow: StateFlow<BotSettings> = _settings
    val brainFlow: StateFlow<BrainState> = _brain
    val runsFlow: StateFlow<List<RunRecord>> = _runs

    override val calibration get() = _calibration.value
    override val settings get() = _settings.value
    override var brain: BrainState
        get() = _brain.value
        set(value) {
            _brain.value = value
            save("brain.json", BrainState.serializer(), value)
        }

    override val runs: List<RunRecord> get() = _runs.value

    override fun addRun(run: RunRecord) {
        _runs.update { (it + run).takeLast(3000) }
        save("runs.json", ListSerializer(RunRecord.serializer()), _runs.value)
    }

    fun updateCalibration(change: (Calibration) -> Calibration) {
        _calibration.update(change)
        save("calibration.json", Calibration.serializer(), _calibration.value)
    }
    override fun updateCards(cards: CardLayout) = updateCalibration { it.copy(cards = cards) }
    override fun raiseMaxTier(tier: Int) = updateSettings { if (tier > it.maxTier) it.copy(maxTier = tier) else it }

    fun updateSettings(change: (BotSettings) -> BotSettings) {
        _settings.update(change)
        save("settings.json", BotSettings.serializer(), _settings.value)
    }

    /** Olvida lo aprendido de las estrategias (el historial de partidas se queda). */
    fun resetLearner() {
        brain = BrainState().withLog("↺ Vuelvo al plan de los PDF en todos los niveles")
    }

    /** El próximo momento en que toca la hora de parar, o null si no hay hora de parar. */
    fun nextStopAt(nowMs: Long = System.currentTimeMillis()): Long? {
        val s = settings
        if (!s.stopAtEnabled) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, s.stopHour)
            set(Calendar.MINUTE, s.stopMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun <T> load(name: String, serializer: KSerializer<T>): T? = runCatching {
        File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) }
    }.getOrNull()

    @Synchronized
    private fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(File(dir, name))
    }
}
