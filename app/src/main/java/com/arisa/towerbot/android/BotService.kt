package com.arisa.towerbot.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.arisa.towerbot.core.BotEngine
import com.arisa.towerbot.core.NumberParser
import com.arisa.towerbot.core.ScreenClassifier
import com.arisa.towerbot.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * El servicio de accesibilidad: es lo que le deja al bot ver la pantalla y tocarla.
 * Lo enciendes en Ajustes > Accesibilidad > TowerBot.
 */
class BotService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var device: AndroidDevice
    private lateinit var overlay: Overlay
    private var botJob: Job? = null

    val isRunning get() = botJob?.isActive == true

    override fun onServiceConnected() {
        device = AndroidDevice(this)
        overlay = Overlay(this, onToggle = { toggleBot() }, onCapture = { captureForCalibration() }, onIdentify = { identify() }, onClose = { closeCompletely() })
        overlay.show()
        _instance.value = this
        // Todo lo que hace el bot queda en logcat (adb logcat -s TowerBot) para depurar.
        scope.launch {
            while (true) {
                delay(60_000)
                val rt = Runtime.getRuntime()
                Log.d(TAG, "MEM usada ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB, capturas vivas ${synchronized(BitmapShot.live) { BitmapShot.live.size }}")
            }
        }
        scope.launch {
            TowerBotApp.status.collect {
                Log.i(TAG, "[${it.screen} · oleada ${it.wave} · compras ${it.runPurchases}] ${it.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != packageName) device.lastEventPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (_instance.value !== this) return
        _instance.value = null
        stopBot()
        overlay.hide()
        scope.cancel()
    }

    fun toggleBot() = if (isRunning) stopBot() else startBot()

    /**
     * Para el bot, quita la burbuja y apaga el servicio de accesibilidad. Para volver a
     * tenerla: abre TowerBot y pulsa Activar.
     */
    private fun closeCompletely() {
        Log.i(TAG, "Cerrado desde la burbuja")
        shutdown()
        disableSelf()
    }
    fun setAutomationOverlayVisible(visible: Boolean) {
        mainExecutor.execute { overlay.setVisible(visible) }
    }

    fun startBot() {
        if (isRunning) return
        val store = TowerBotApp.store
        val missing = store.calibration.missing()
        if (missing.isNotEmpty()) {
            notify("Falta calibrar: ${missing.first()}")
            return
        }
        val engine = BotEngine(device, store, TextReader, TowerBotApp.status, trace = { Log.d(TAG, it) })
        val job = scope.launch {
            try {
                engine.runLoop(store.nextStopAt())
            } catch (e: Throwable) {
                Log.e(TAG, "El bucle del bot terminó por un error", e)
                throw e
            }
        }
        botJob = job
        overlay.setRunning(true)
        job.invokeOnCompletion { scope.launch(Dispatchers.Main) { overlay.setRunning(false) } }
        if (device.foregroundPackage() != store.calibration.gamePackage) device.launchGame(store.calibration.gamePackage)
    }

    fun stopBot() {
        if (botJob?.isActive == true) Log.i(TAG, "Parar pedido desde:", Throwable("traza"))
        botJob?.cancel()
        botJob = null
    }

    /** Captura lo que hay debajo de la burbuja y lo abre en el editor de calibración. */
    private fun captureForCalibration() = scope.launch {
        val pkg = device.foregroundPackage()
        withContext(Dispatchers.Main) { overlay.setVisible(false) }
        delay(350)
        val shot = device.capture() as? BitmapShot
        withContext(Dispatchers.Main) { overlay.setVisible(true) }
        if (shot == null) {
            notify("No pude capturar la pantalla")
            return@launch
        }
        val dir = File(filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.png")
        file.outputStream().use { shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        startActivity(
            Intent(this@BotService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CAPTURE, file.absolutePath)
                .putExtra(MainActivity.EXTRA_PACKAGE, pkg),
        )
    }

    /** Dice qué pantalla cree el bot que está viendo, para comprobar la calibración. */
    private fun identify() = scope.launch {
        withContext(Dispatchers.Main) { overlay.setVisible(false) }
        delay(350)
        val shot = device.capture()
        withContext(Dispatchers.Main) { overlay.setVisible(true) }
        if (shot == null) return@launch notify("No pude capturar la pantalla")
        val store = TowerBotApp.store
        val s = store.settings
        val match = ScreenClassifier.classify(shot.frame, store.calibration.screens, s.matchThreshold, s.matchShape)
        val best = ScreenClassifier.rank(shot.frame, store.calibration.screens).firstOrNull()
        // Lo que lee en las zonas de oleada y monedas, para comprobar que lee bien.
        val readings = match?.screen?.let { screen ->
            listOfNotNull(
                screen.waveBox?.let { box ->
                    val text = TextReader.read(shot, box)
                    "oleada: «$text» → ${text?.let(NumberParser::parseInt)}"
                },
                screen.coinsBox?.let { box ->
                    val text = TextReader.read(shot, box)
                    "monedas: «$text» → ${text?.let(NumberParser::parse)?.let(NumberParser::format)}"
                },
                screen.tierBox?.let { box ->
                    val text = TextReader.read(shot, box)
                    "nivel: «$text» → ${text?.let(NumberParser::parseTier)}"
                },
            ).joinToString("") { "\n$it" }
        }.orEmpty()
        Log.i(TAG, "Identificar: ${match?.screen?.name}$readings")
        notify(
            when {
                match != null -> "Veo: ${match.screen.name}\n" + "color %.3f · forma %.2f".format(match.distance, match.shape) + readings
                best == null -> "Aún no hay pantallas calibradas"
                else -> "No la reconozco.\nLa más parecida: ${best.screen.name}\n" + "color %.3f (máx %.2f) · forma %.2f (mín %.2f)"
                    .format(best.distance, s.matchThreshold, best.shape, s.matchShape)
            },
        )
    }

    private fun notify(text: String) {
        scope.launch(Dispatchers.Main) { overlay.showMessage(text) }
    }

    companion object {
        const val TAG = "TowerBot"
        private val _instance = MutableStateFlow<BotService?>(null)

        /** El servicio si está encendido; null si falta activarlo en Accesibilidad. */
        val instance: StateFlow<BotService?> = _instance
    }
}
