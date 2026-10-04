package com.arisa.towerbot.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.arisa.towerbot.core.ArrayFrame
import com.arisa.towerbot.core.Device
import com.arisa.towerbot.core.Frame
import com.arisa.towerbot.core.Pt
import com.arisa.towerbot.core.Shot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class BitmapShot(val bitmap: Bitmap) : Shot {
    init {
        synchronized(live) { live[this] = true }
    }

    companion object {
        /** Capturas que siguen vivas (diagnóstico de memoria). */
        val live = java.util.WeakHashMap<BitmapShot, Boolean>()
    }

    override val frame: Frame by lazy {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ArrayFrame(bitmap.width, bitmap.height, pixels)
    }
}

/** El teléfono de verdad, manejado a través del servicio de accesibilidad. */
class AndroidDevice(private val service: AccessibilityService) : Device {
    /** Android limita las capturas del servicio: una cada 1 s en Android 11, cada 1/3 s después. */
    private val minGapMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 350L else 1050L
    private var lastShotAt = 0L

    @Volatile
    var lastEventPackage: String? = null

    override suspend fun capture(): Shot? {
        repeat(3) {
            val wait = lastShotAt + minGapMs - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            lastShotAt = SystemClock.elapsedRealtime()
            val result = suspendCancellableCoroutine<Result<AccessibilityService.ScreenshotResult>> { cont ->
                // Android retiene el callback un buen rato después de usarlo. Si guardara la
                // continuación, retendría con ella todas las capturas del bucle: fuga de memoria.
                val pending = AtomicReference<CancellableContinuation<Result<AccessibilityService.ScreenshotResult>>?>(cont)
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val c = pending.getAndSet(null)
                            if (c != null) c.resume(Result.success(screenshot)) else screenshot.hardwareBuffer.close()
                        }

                        override fun onFailure(errorCode: Int) {
                            pending.getAndSet(null)?.resume(Result.failure(ScreenshotError(errorCode)))
                        }
                    },
                )
                cont.invokeOnCancellation { pending.set(null) }
            }
            val screenshot = result.getOrNull()
            if (screenshot != null) {
                val buffer = screenshot.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                buffer.close()
                return bitmap?.let(::BitmapShot)
            }
            val code = (result.exceptionOrNull() as? ScreenshotError)?.code
            Log.w(BotService.TAG, "Captura fallida, código $code")
            if (code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return null
        }
        return null
    }

    override suspend fun tap(p: Pt) {
        val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
        dispatch(GestureDescription.StrokeDescription(path, 0, 60))
    }

    override suspend fun swipe(from: Pt, to: Pt, durationMs: Long) {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    override suspend fun drag(from: Pt, to: Pt, durationMs: Long) {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        val move = GestureDescription.StrokeDescription(path, 0, durationMs, true)
        dispatch(move)
        // El dedo se queda quieto un rato: sin velocidad al soltar, la lista no sigue sola.
        val still = Path().apply { moveTo(to.x.toFloat(), to.y.toFloat()) }
        dispatch(move.continueStroke(still, 0, 250, false))
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        suspendCancellableCoroutine { cont ->
            val pending = AtomicReference<CancellableContinuation<Unit>?>(cont)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    pending.getAndSet(null)?.resume(Unit)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    pending.getAndSet(null)?.resume(Unit)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null)) pending.getAndSet(null)?.resume(Unit)
            cont.invokeOnCancellation { pending.set(null) }
        }
    }

    override fun back() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun launchGame(packageName: String) {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var lastForeground: String? = null

    override fun foregroundPackage(): String? {
        val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: lastEventPackage
        if (pkg != lastForeground) Log.i(BotService.TAG, "En primer plano: $pkg")
        lastForeground = pkg
        return pkg
    }

    override fun ownPackage(): String = service.packageName

    override fun batteryTempC(): Float? {
        val battery = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private class ScreenshotError(val code: Int) : Exception("takeScreenshot falló: $code")
}
