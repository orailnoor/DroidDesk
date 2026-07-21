package com.orailnoor.droiddesk.x11

import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(private val lorieView: LorieView) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    var mode: Int = TouchInputHandler.InputMode.TRACKPAD
        private set

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)
        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
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
        setMode(next)
        return next
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun dispose() {
        MainActivity.getInstance().setKeyHandler(null)
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)

    companion object {
        const val DISPLAY_SCALE_PERCENT = 200

        /**
         * In "scaled" mode the render resolution is surface*100/scale, so a
         * larger percent means a smaller (blurrier) backing store. 300 rendered
         * a 720x1520 panel at only 240x506 — far too blocky. Openbox is light
         * enough to render at native resolution (100) even on a 2 GB A10s, so
         * low-RAM devices now get full sharpness.
         */
        const val DISPLAY_SCALE_PERCENT_LOW_RAM = 100

        /** Must run before LorieView is measured so Xwayland starts at the scaled resolution. */
        fun configureDisplayScale(context: android.content.Context) {
            val scale = if (
                com.orailnoor.droiddesk.runtime.DeviceProfile.isLowRam(context)
            ) {
                DISPLAY_SCALE_PERCENT_LOW_RAM
            } else {
                DISPLAY_SCALE_PERCENT
            }
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("scaled")
                displayScale.put(scale)
                displayStretch.put(true)
                scaleTouchpad.put(true)
            }
        }
    }
}
