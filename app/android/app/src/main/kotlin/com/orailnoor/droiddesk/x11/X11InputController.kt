package com.orailnoor.droiddesk.x11

import android.view.MotionEvent
import android.view.View
import android.graphics.Matrix
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler
import com.termux.x11.input.InputStub
import com.orailnoor.droiddesk.display.DisplayProfile

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(
    private val lorieView: LorieView,
    private val generation: Long,
    initialMode: Int,
    private val onModeChanged: (Int) -> Unit,
    onThreeFingerTap: () -> Unit = {},
    private val onViewportChanged: (Int, Int, Matrix) -> Unit = { _, _, _ -> },
) {
    private fun isCurrent() = MainActivity.getInstance().isLorieViewOwner(generation)
    private val sender = InputEventSender(object : InputStub {
        override fun sendMouseEvent(x: Float, y: Float, button: Int, down: Boolean, relative: Boolean) {
            if (isCurrent()) lorieView.sendMouseEvent(x, y, button, down, relative)
        }
        override fun sendMouseWheelEvent(x: Float, y: Float) {
            if (isCurrent()) lorieView.sendMouseWheelEvent(x, y)
        }
        override fun sendKeyEvent(scan: Int, key: Int, down: Boolean) =
            isCurrent() && lorieView.sendKeyEvent(scan, key, down)
        override fun sendTextEvent(text: ByteArray) {
            if (isCurrent()) lorieView.sendTextEvent(text)
        }
        override fun sendTouchEvent(action: Int, pointer: Int, x: Int, y: Int) {
            if (isCurrent()) lorieView.sendTouchEvent(action, pointer, x, y)
        }
        override fun sendStylusEvent(
            x: Float, y: Float, pressure: Int, tiltX: Int, tiltY: Int, orientation: Int,
            buttons: Int, eraser: Boolean, mouseMode: Boolean,
        ) {
            if (isCurrent()) lorieView.sendStylusEvent(x, y, pressure, tiltX, tiltY, orientation, buttons, eraser, mouseMode)
        }
    })
    private val inputHandler = TouchInputHandler(MainActivity.getInstance(), sender)

    var mode: Int = initialMode
        private set

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent, generation)
        TouchInputHandler.setThreeFingerTapListener { if (isCurrent()) onThreeFingerTap() }
        lorieView.setCallback { width, height, transform ->
            if (isCurrent()) {
                inputHandler.handleInputTransformChanged(width, height, transform)
                onViewportChanged(width, height, transform)
            }
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    fun nextMode(): Int {
        val next = when (mode) {
            TouchInputHandler.InputMode.TRACKPAD -> TouchInputHandler.InputMode.SIMULATED_TOUCH
            TouchInputHandler.InputMode.SIMULATED_TOUCH -> TouchInputHandler.InputMode.TOUCH
            else -> TouchInputHandler.InputMode.TRACKPAD
        }
        onModeChanged(next)
        setMode(next)
        return next
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun dispose() {
        if (isCurrent()) {
            if (LorieView.connected()) sender.releaseAll()
            MainActivity.getInstance().setKeyHandler(null, generation)
            TouchInputHandler.setThreeFingerTapListener(null)
        }
        inputHandler.dispose()
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        require(newMode in 1..3)
        mode = newMode
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    fun applyProfile(profile: DisplayProfile) {
        if (isCurrent()) setMode(profile.settings.inputMode)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        isCurrent() && inputHandler.handleTouchEvent(lorieView, view, event)

    fun reacquireFocus() {
        if (!isCurrent()) return
        lorieView.requestFocus()
        inputHandler.reloadPreferences(MainActivity.getPrefs())
        if (MainActivity.getPrefs().pointerCapture.get() && !lorieView.hasPointerCapture()) {
            lorieView.requestPointerCapture()
        }
        lorieView.refreshRenderer()
    }

    companion object {
        const val DISPLAY_SCALE_PERCENT = 150

        /** Must run before LorieView is measured so Xwayland starts at the scaled resolution. */
        fun configureDisplayScale() {
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("scaled")
                displayScale.put(DISPLAY_SCALE_PERCENT)
                displayStretch.put(true)
                scaleTouchpad.put(true)
            }
        }
    }
}
