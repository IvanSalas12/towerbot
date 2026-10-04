package com.arisa.towerbot.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * La burbuja flotante sobre el juego: arrancar/parar, capturar para calibrar y
 * preguntar qué pantalla ve el bot. Se arrastra desde cualquier botón.
 * Mientras el bot juega, mantiene la pantalla encendida.
 */
class Overlay(
    private val context: Context,
    private val onToggle: () -> Unit,
    private val onCapture: () -> Unit,
    private val onIdentify: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private lateinit var root: LinearLayout
    private lateinit var toggle: TextView
    private lateinit var note: TextView
    private val hideNote = Runnable { note.visibility = View.GONE }
    private val extras = mutableListOf<View>()
    private var shown = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = (160 * density).toInt()
    }

    fun show() {
        if (shown) return
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 20, 20, 28))
                cornerRadius = 18 * density
            }
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        toggle = button("▶", onToggle)
        root.addView(toggle)
        extras += button("📷", onCapture)
        extras += button("🔍", onIdentify)
        extras.forEach(root::addView)
        note = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = (240 * density).toInt()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(note)
        wm.addView(root, params)
        shown = true
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(root) }
        extras.clear()
        shown = false
    }

    /** Un mensaje corto bajo los botones durante unos segundos (los toasts los bloquea Android). */
    fun showMessage(text: String) {
        if (!shown) return
        note.text = text
        note.visibility = View.VISIBLE
        note.removeCallbacks(hideNote)
        note.postDelayed(hideNote, 6000)
    }

    fun setVisible(visible: Boolean) {
        if (shown) root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /** Mientras juega: sólo el botón de parar (menos estorbo) y la pantalla siempre encendida. */
    fun setRunning(running: Boolean) {
        if (!shown) return
        toggle.text = if (running) "■" else "▶"
        extras.forEach { it.visibility = if (running) View.GONE else View.VISIBLE }
        params.flags = if (running) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(root, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, action: () -> Unit): TextView {
        val size = (40 * density).toInt()
        return TextView(context).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(size, size)
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) + abs(dy) > 12 * density) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            wm.updateViewLayout(root, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragging) action()
                }
                true
            }
        }
    }
}
