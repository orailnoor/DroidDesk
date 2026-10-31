package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import com.orailnoor.droiddesk.R
import com.orailnoor.droiddesk.service.DroidDeskService
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.ClipboardSync
import com.orailnoor.droiddesk.runtime.XfceMobileProfile
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.runtime.AndroidAppSync
import java.io.File
import java.io.BufferedReader
import java.io.FileReader

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var connectionRequested = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private var clipboardSync: ClipboardSync? = null
    private lateinit var placeholder: FrameLayout
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private var inputModeButton: Button? = null
    private var controlOverlay: LinearLayout? = null
    private var collapsedControl: View? = null
    private val collapsedFadeHandler = Handler(Looper.getMainLooper())
    private val fadeCollapsedControl = Runnable {
        collapsedControl?.animate()?.alpha(COLLAPSED_IDLE_ALPHA)?.setDuration(400)?.start()
    }
    private var extraKeysBar: ExtraKeysBar? = null
    private var logoAnimator: FloatingLogoAnimator? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var intentListenerThread: Thread? = null
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            linuxRuntime.updateBatteryStatus()
        }
    }
    private var loadingOverlay: FrameLayout? = null
    private var loadingStatus: TextView? = null
    private var loadingEstimate: TextView? = null
    private var desktopRevealed = false
    private val loadingMessageHandler = Handler(Looper.getMainLooper())
    private var loadingMessageIndex = 0
    private var loadingStartedAt = 0L
    private var estimatedLoadingSeconds = 30
    private val loadingMessages = listOf(
        "Waking up your portable Linux workspace",
        "Connecting Android to your Linux desktop",
        "Starting the desktop engine and services",
        "Loading your apps, icons, and shortcuts",
        "Polishing your panels, wallpaper, and workspace",
        "Almost ready for your next big idea",
    )
    private val loadingMessageTicker = object : Runnable {
        override fun run() {
            val status = loadingStatus ?: return
            val ticker = this
            status.animate().alpha(0f).setDuration(180).withEndAction {
                loadingMessageIndex = (loadingMessageIndex + 1) % loadingMessages.size
                status.text = loadingMessages[loadingMessageIndex]
                status.animate().alpha(1f).setDuration(260).withEndAction {
                    if (!desktopRevealed) {
                        loadingMessageHandler.postDelayed(ticker, 5_000)
                    }
                }.start()
            }.start()
        }
    }
    private val loadingEstimateTicker = object : Runnable {
        override fun run() {
            val estimate = loadingEstimate ?: return
            val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - loadingStartedAt) / 1_000).toInt()
            val remaining = (estimatedLoadingSeconds - elapsedSeconds).coerceAtLeast(0)
            estimate.text = if (remaining > 0) {
                "About $remaining seconds remaining"
            } else {
                "Finishing up…"
            }
            estimate.contentDescription = estimate.text
            if (!desktopRevealed) loadingMessageHandler.postDelayed(this, 1_000)
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"
        private const val COLLAPSED_IDLE_ALPHA = 0.4f
        private const val COLLAPSED_FADE_DELAY_MS = 3_000L
        private var current: java.lang.ref.WeakReference<DesktopActivity>? = null

        fun isOpen(): Boolean = current?.get()?.isFinishing == false

        /** Closes the desktop screen, e.g. when the session is stopped from the notification. */
        fun finishIfOpen() {
            current?.get()?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        shouldStartSession = intent.getBooleanExtra("startSession", false)
        sessionMode = intent.getStringExtra("mode") ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: "xfce4"
        estimatedLoadingSeconds = if (shouldStartSession) 30 else 10

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        setContentView(placeholder)
        showLoadingOverlay()
        // Some Android/LineageOS builds throw from PhoneWindow.getInsetsController
        // until a decor view has been created by setContentView().
        enableImmersiveMode()

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
            startClipboardSync()
            lorieView?.post {
                lorieView?.requestFocus()
                lorieView?.reload()
            }
        } else {
            clipboardSync?.stop()
        }
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — setting up LorieView")
            setupLorieView()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (TermuxMainActivity.getInstance().handleKey(event)) {
            return true
        }
        if (lorieView?.dispatchKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (lorieView?.dispatchGenericMotionEvent(event) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val collapsed = collapsedControl
        if (collapsed != null && collapsed.visibility == View.VISIBLE) {
            val rect = android.graphics.Rect()
            collapsed.getGlobalVisibleRect(rect)
            if (rect.contains(event.x.toInt(), event.y.toInt())) {
                return super.dispatchTouchEvent(event)
            }
        }
        val control = controlOverlay
        if (control != null && control.visibility == View.VISIBLE) {
            val rect = android.graphics.Rect()
            control.getGlobalVisibleRect(rect)
            if (rect.contains(event.x.toInt(), event.y.toInt())) {
                return super.dispatchTouchEvent(event)
            }
        }
        if (event.action == MotionEvent.ACTION_DOWN && lorieView?.isFocused == false) {
            lorieView?.requestFocus()
        }
        val lv = lorieView
        if (lv != null) {
            return lv.dispatchTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        restartSessionIfStopped()
        try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(batteryReceiver, filter)
            }
        } catch (e: Exception) {}
        lorieView?.post {
            lorieView?.requestFocus()
            lorieView?.reload()
            inputController?.reacquireFocus()
        }
    }

    private fun isSessionRunning(): Boolean =
        if (sessionMode == "chroot") chrootRuntime.isRunning() else linuxRuntime.isRunning()

    /**
     * The session can be stopped while this screen stays in the back stack (notification Stop,
     * in-app Stop). Coming back to it would only show the bare X server, so start Linux again.
     */
    private fun restartSessionIfStopped() {
        if (!desktopRevealed || isSessionRunning()) return
        Log.i(TAG, "Desktop session is no longer running; starting it again")
        desktopRevealed = false
        controlOverlay?.visibility = View.INVISIBLE
        collapsedControl?.visibility = View.INVISIBLE
        loadingOverlay?.apply {
            alpha = 1f
            visibility = View.VISIBLE
            bringToFront()
        }
        logoAnimator?.start()
        estimatedLoadingSeconds = 30
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
        // Stop also ended the keep-alive service.
        startForegroundService(Intent(this, DroidDeskService::class.java))
        shouldStartSession = true
        startDesktopSessionIfRequested()
    }

    private fun startClipboardSync() {
        if (clipboardSync == null) {
            clipboardSync = if (sessionMode == "chroot") {
                ClipboardSync(this, chrootRuntime::readLinuxClipboard, chrootRuntime::writeLinuxClipboard)
            } else {
                ClipboardSync(this, linuxRuntime::readLinuxClipboard, linuxRuntime::writeLinuxClipboard)
            }
        }
        clipboardSync?.start()
    }

    override fun onPause() {
        clipboardSync?.stop()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        X11InputController.configureDisplayScale()
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView

        // Keep Android overlay controls above the X11 SurfaceView while preventing
        // the window background from covering the surface.
        lorieView!!.setZOrderMediaOverlay(true)
        placeholder.setBackgroundColor(Color.TRANSPARENT)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // TermuxMainActivity retains its LorieView singleton across activity
        // recreation. Detach it from the previous activity's container before
        // attaching it here, otherwise Android throws "child already has a parent".
        (lorieView!!.parent as? ViewGroup)?.removeView(lorieView)
        placeholder.addView(lorieView, params)
        loadingOverlay?.bringToFront()
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                synchronized(this@DesktopActivity) {
                    if (!connectionRequested) {
                        connectionRequested = true
                        connectToX11Service()
                    } else {
                        lorieView?.reload()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
            }
        }.also { lorieView!!.holder.addCallback(it) }
    }

    private fun connectToX11Service() {
        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { serverPid, connectionFd, logcatFd ->
                try {
                    if (!LorieView.connected() || X11ServiceClient.connectedServerPid != serverPid) {
                        LorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                        X11ServiceClient.connectedServerPid = serverPid
                        Log.i(TAG, "LorieView connected to X11 service pid=$serverPid")
                    } else {
                        connectionFd.close()
                        logcatFd?.close()
                        Log.i(TAG, "LorieView reused live X11 service pid=$serverPid")
                    }

                    attachDesktopInput()
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = ::showX11Error,
        ).also { it.connect() }
    }

    private fun attachDesktopInput() {
        if (inputController == null) {
            inputController = X11InputController(lorieView!!, onThreeFingerTap = ::toggleKeyboard)
        }
        addDesktopControls()
        lorieView?.requestFocus()
        if (shouldStartSession) {
            startDesktopSessionIfRequested()
        } else {
            waitForDesktopAndReveal()
        }
    }

    private fun addDesktopControls() {
        if (controlOverlay != null) return
        val density = resources.displayMetrics.density

        // Frosted, translucent controls: one rounded pill when open, an AssistiveTouch-style
        // square when collapsed.
        fun frosted(radius: Float) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(200, 22, 26, 34))
            setStroke((1 * density).toInt(), Color.argb(40, 255, 255, 255))
        }
        fun pressRipple() = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null,
            GradientDrawable().apply { cornerRadius = 20 * density; setColor(Color.WHITE) },
        )
        fun iconButton(icon: Int, label: String) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            background = pressRipple()
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }

        val buttonHeight = (44 * density).toInt()
        val keyboardButton = iconButton(R.drawable.ic_dd_keyboard, "Show keyboard").apply {
            setOnClickListener { showKeyboard() }
        }
        inputModeButton = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 13f
            setTextColor(Color.WHITE)
            background = pressRipple()
            stateListAnimator = null
            compoundDrawablePadding = (6 * density).toInt()
            setPadding((12 * density).toInt(), 0, (14 * density).toInt(), 0)
            showInputMode(this)
            setOnClickListener {
                inputController?.nextMode()
                showInputMode(this)
                Toast.makeText(this@DesktopActivity, "Input mode: $text", Toast.LENGTH_SHORT).show()
            }
        }
        val hideButton = iconButton(R.drawable.ic_dd_close, "Hide desktop controls").apply {
            setOnClickListener { setControlsCollapsed(true) }
        }

        controlOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = frosted(26 * density)
            elevation = 10 * density
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(keyboardButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            addView(inputModeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, buttonHeight,
            ))
            addView(hideButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            // Start collapsed so the bar never covers windows until the user asks for it.
            visibility = View.INVISIBLE
        }

        // AssistiveTouch-style handle: fades out when idle and lights up while touched.
        collapsedControl = View(this).apply {
            contentDescription = "Show desktop controls"
            background = assistiveTouchDrawable(density)
            elevation = 10 * density
            alpha = COLLAPSED_IDLE_ALPHA
            if (!desktopRevealed) visibility = View.INVISIBLE
        }

        // Sit just above XFCE's bottom dock, which is scaled up with the X11 display.
        val dockHeight = XfceMobileProfile.DOCK_SIZE_PX * X11InputController.DISPLAY_SCALE_PERCENT / 100
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = (8 * density).toInt()
            bottomMargin = dockHeight + (8 * density).toInt()
        }
        val collapsedSize = (52 * density).toInt()
        val collapsedParams = FrameLayout.LayoutParams(
            collapsedSize,
            collapsedSize,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = overlayParams.rightMargin
            bottomMargin = overlayParams.bottomMargin
        }

        placeholder.addView(controlOverlay, overlayParams)
        placeholder.addView(collapsedControl, collapsedParams)
        collapsedControl?.setOnTouchListener(assistiveTouchListener(collapsedControl!!))
        collapsedControl?.bringToFront()
        addExtraKeysBar()
    }

    /** Esc/Tab/Ctrl/Alt/arrow row that rides on top of the soft keyboard while it is open. */
    private fun addExtraKeysBar() {
        val bar = ExtraKeysBar(this) { lorieView }.apply { visibility = View.GONE }
        extraKeysBar = bar
        placeholder.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        // Typing on the soft keyboard releases one-shot Ctrl/Alt (see LorieView's InputConnection).
        TermuxMainActivity.getInstance().apply {
            mExtraKeys = object : TermuxMainActivity.DummyExtraKeys() {
                override fun unsetSpecialKeys() {
                    runOnUiThread { extraKeysBar?.onKeyTyped() }
                }
            }
            useTermuxEKBarBehaviour = true
        }
        placeholder.setOnApplyWindowInsetsListener { view, insets ->
            updateExtraKeysBar(insets)
            view.onApplyWindowInsets(insets)
        }
        placeholder.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun updateExtraKeysBar(insets: WindowInsets) {
        val bar = extraKeysBar ?: return
        val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            insets.systemWindowInsetBottom
        }
        if (imeBottom > 0) {
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = imeBottom
            bar.visibility = View.VISIBLE
            bar.requestLayout()
            bar.bringToFront()
        } else if (bar.visibility == View.VISIBLE) {
            bar.releaseAll()
            bar.visibility = View.GONE
        }
    }

    private fun dragListener(target: View, onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        val threshold = resources.displayMetrics.density * 6

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                        dragged = true
                    }
                    target.x = (startX + dx).coerceIn(0f, (placeholder.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (placeholder.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    /** iOS AssistiveTouch look: dark translucent rounded square with nested white circles. */
    private fun assistiveTouchDrawable(density: Float): LayerDrawable {
        fun circle(alpha: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 255, 255, 255))
        }
        val square = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(170, 30, 30, 30))
        }
        return LayerDrawable(arrayOf(square, circle(45), circle(90), circle(240))).apply {
            setLayerInset(1, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setLayerInset(2, (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setLayerInset(3, (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
    }

    /** Mouse icon for trackpad mode, finger icon for the touch modes, plus the mode name. */
    private fun showInputMode(button: Button) {
        val label = inputController?.modeLabel() ?: "Trackpad"
        val icon = if (label == "Trackpad") R.drawable.ic_dd_mouse else R.drawable.ic_dd_touch
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    /** Lights the ☰ handle up while touched, snaps it to the nearest side after a drag, then fades it. */
    private fun assistiveTouchListener(handle: View): View.OnTouchListener {
        val drag = dragListener(handle) { setControlsCollapsed(false) }
        var downX = 0f
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = handle.x
                    collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
                    handle.animate().cancel()
                    handle.alpha = 1f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handle.x != downX) {
                        val margin = 8 * resources.displayMetrics.density
                        val maxX = (placeholder.width - handle.width).toFloat()
                        val edgeX = if (handle.x + handle.width / 2f < placeholder.width / 2f) margin else maxX - margin
                        handle.animate().x(edgeX.coerceIn(0f, maxX.coerceAtLeast(0f))).setDuration(180).start()
                    }
                    scheduleCollapsedFade()
                }
            }
            drag.onTouch(view, event)
        }
    }

    private fun scheduleCollapsedFade() {
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        collapsedFadeHandler.postDelayed(fadeCollapsedControl, COLLAPSED_FADE_DELAY_MS)
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        val from = if (collapsed) controlOverlay else collapsedControl
        val to = if (collapsed) collapsedControl else controlOverlay
        to?.x = from?.x ?: 0f
        to?.y = from?.y ?: 0f
        from?.visibility = View.INVISIBLE
        to?.visibility = View.VISIBLE
        to?.bringToFront()
        // The expanded bar is wider than the ☰ handle; once laid out, keep it fully on screen.
        to?.post { keepOnScreen(to) }
        if (collapsed) {
            collapsedControl?.alpha = 1f
            scheduleCollapsedFade()
        } else {
            collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        }
    }

    private fun keepOnScreen(view: View) {
        val maxX = (placeholder.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (placeholder.height - view.height).coerceAtLeast(0).toFloat()
        view.x = view.x.coerceIn(0f, maxX)
        view.y = view.y.coerceIn(0f, maxY)
    }

    /** Three-finger tap: open the keyboard, or close it when it is already showing. */
    private fun toggleKeyboard() {
        val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            extraKeysBar?.visibility == View.VISIBLE
        }
        if (imeVisible) hideKeyboard() else showKeyboard()
    }

    private fun hideKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        inputMethod.restartInput(view)
        view.post {
            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startDesktopSessionIfRequested() {
        if (!shouldStartSession) return
        shouldStartSession = false
        Thread({
            Log.i(TAG, "Starting Linux desktop session after X server connection")
            try {
                if (sessionMode == "chroot") {
                    chrootRuntime.startSession(desktopEnv)

                    // Setup Android App Integration
                    val rootfsPath = chrootRuntime.getRootfsPath()
                    chrootRuntime.executeCommand("mkdir -p /tmp && mkfifo /tmp/android_intent_pipe && chmod 666 /tmp/android_intent_pipe") {}
                    AndroidAppSync.syncApps(this@DesktopActivity, rootfsPath)
                    startIntentListener(rootfsPath)
                } else {
                    linuxRuntime.startSession(desktopEnv, "x11")
                }
                val ready = if (sessionMode == "chroot") {
                    chrootRuntime.waitForDesktopReady(desktopEnv)
                } else {
                    linuxRuntime.waitForDesktopReady(desktopEnv)
                }
                revealDesktop(ready)
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop session failed", error)
                revealDesktop(false)
            }
        }, "LinuxDesktopSession").start()
    }

    private fun waitForDesktopAndReveal() {
        Thread({
            val ready = if (sessionMode == "chroot") {
                chrootRuntime.waitForDesktopReady(desktopEnv)
            } else {
                linuxRuntime.waitForDesktopReady(desktopEnv)
            }
            revealDesktop(ready)
        }, "LinuxDesktopReady").start()
    }

    /** Follows the theme picked in the app (Flutter stores it as flutter.theme_mode). */
    private fun isLightTheme(): Boolean {
        val mode = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .getString("flutter.theme_mode", "dark")
        return when (mode) {
            "light" -> true
            "system" -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }

    private fun showLoadingOverlay() {
        val density = resources.displayMetrics.density
        val light = isLightTheme()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        runCatching {
            assets.open("flutter_assets/assets/icons/logo.png").use { stream ->
                ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    logoAnimator = FloatingLogoAnimator(this).also { it.start() }
                    content.addView(
                        this,
                        LinearLayout.LayoutParams((112 * density).toInt(), (112 * density).toInt()).apply {
                            bottomMargin = (26 * density).toInt()
                        },
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Loading logo unavailable", it) }

        content.addView(TextView(this).apply {
            text = "DroidDesk"
            textSize = 28f
            setTextColor(if (light) Color.rgb(28, 28, 30) else Color.WHITE)
            gravity = Gravity.CENTER
        })
        content.addView(LoadingSpinnerView(
            this,
            arcColor = Color.rgb(10, 132, 255),
            trackColor = if (light) Color.argb(40, 10, 132, 255) else Color.argb(50, 255, 255, 255),
        ), LinearLayout.LayoutParams(
            (44 * density).toInt(),
            (44 * density).toInt(),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (22 * density).toInt()
        })
        loadingStatus = TextView(this).apply {
            text = loadingMessages.first()
            textSize = 14f
            setTextColor(if (light) Color.rgb(58, 58, 60) else Color.rgb(190, 198, 215))
            gravity = Gravity.CENTER
        }.also { status ->
            content.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (14 * density).toInt() })
        }
        loadingEstimate = TextView(this).apply {
            text = "About $estimatedLoadingSeconds seconds remaining"
            textSize = 12f
            setTextColor(if (light) Color.rgb(110, 110, 115) else Color.rgb(130, 143, 164))
            gravity = Gravity.CENTER
            contentDescription = text
        }.also { estimate ->
            content.addView(estimate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() })
        }

        loadingOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (light) {
                    intArrayOf(Color.rgb(242, 242, 247), Color.rgb(237, 237, 242))
                } else {
                    intArrayOf(Color.rgb(12, 18, 32), Color.rgb(4, 7, 14))
                },
            )
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }.also { overlay ->
            placeholder.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
    }

    private fun revealDesktop(ready: Boolean) {
        runOnUiThread {
            if (desktopRevealed || isFinishing || isDestroyed) return@runOnUiThread
            desktopRevealed = true
            loadingMessageHandler.removeCallbacks(loadingMessageTicker)
            loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
            if (!ready) Log.w(TAG, "Revealing desktop after readiness timeout")
            Log.i(TAG, "Desktop revealed ready=$ready")
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(350)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                    logoAnimator?.stop()
                    collapsedControl?.visibility = View.VISIBLE
                    collapsedControl?.bringToFront()
                    lorieView?.requestFocus()
                }
                ?.start()
        }
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        Toast.makeText(this, "X11 Error: $message", Toast.LENGTH_LONG).show()
    }

    private fun startIntentListener(rootfsPath: String) {
        if (intentListenerThread != null) return
        intentListenerThread = Thread({
            val pipePath = "$rootfsPath/tmp/android_intent_pipe"
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "tail -f $pipePath"))
                    BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                        while (!Thread.currentThread().isInterrupted) {
                            val packageName = reader.readLine() ?: break
                            if (packageName.isNotBlank()) {
                                launchAndroidApp(packageName.trim())
                            }
                        }
                    }
                    process.destroy()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Thread.sleep(1000)
                }
            }
        }, "AndroidIntentListener")
        intentListenerThread?.start()
    }

    private fun launchAndroidApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                Log.i(TAG, "Launching Android App: ${packageName}")
                startActivity(launchIntent)
            } else {
                Log.w(TAG, "No launch intent found for package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $packageName", e)
        }
    }

    override fun onDestroy() {
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        clipboardSync?.stop()
        clipboardSync = null
        extraKeysBar?.releaseAll()
        extraKeysBar = null
        TermuxMainActivity.getInstance().apply {
            mExtraKeys = null
            useTermuxEKBarBehaviour = false
        }
        loadingMessageHandler.removeCallbacks(loadingMessageTicker)
        loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
        surfaceCallback?.let { callback -> lorieView?.holder?.removeCallback(callback) }
        surfaceCallback = null
        inputController?.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        intentListenerThread?.interrupt()
        intentListenerThread = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
