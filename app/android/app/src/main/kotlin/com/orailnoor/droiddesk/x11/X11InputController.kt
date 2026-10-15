package com.orailnoor.droiddesk.x11

import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(
    private val lorieView: LorieView,
    onThreeFingerTap: () -> Unit = {},
) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    /** Input mode the user picked last time; survives app restarts. */
    private val savedModes = lorieView.context.getSharedPreferences("desktop_input", android.content.Context.MODE_PRIVATE)

    var mode: Int = savedModes.getInt(KEY_MODE, TouchInputHandler.InputMode.SIMULATED_TOUCH)
        private set

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)
        TouchInputHandler.setThreeFingerTapListener { onThreeFingerTap() }
        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    fun nextMode(): Int {
        val next = when (mode) {
            TouchInputHandler.InputMode.SIMULATED_TOUCH -> TouchInputHandler.InputMode.TRACKPAD
            TouchInputHandler.InputMode.TRACKPAD -> TouchInputHandler.InputMode.TOUCH
            else -> TouchInputHandler.InputMode.SIMULATED_TOUCH
        }
        setMode(next)
        return next
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun reacquireFocus() {
        lorieView.requestFocus()
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(mode.toString())
        inputHandler.reloadPreferences(prefs)
        if (prefs.pointerCapture.get() && !lorieView.hasPointerCapture()) {
            lorieView.requestPointerCapture()
        }
    }

    fun dispose() {
        MainActivity.getInstance().setKeyHandler(null)
        TouchInputHandler.setThreeFingerTapListener(null)
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        savedModes.edit().putInt(KEY_MODE, newMode).apply()
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)

    companion object {
        private const val KEY_MODE = "mode"
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
