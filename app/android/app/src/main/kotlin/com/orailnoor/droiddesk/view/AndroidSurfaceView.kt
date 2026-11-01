package com.orailnoor.droiddesk.view

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import io.flutter.plugin.platform.PlatformView
import com.termux.x11.MainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.RendererLease
import android.view.ViewGroup
import com.orailnoor.droiddesk.display.DisplaySettingsStore
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.PixelSize

class AndroidSurfaceView(
    val context: Context,
    id: Int,
    creationParams: Map<String?, Any?>?
) : PlatformView {

    private lateinit var lorieView: LorieView
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private val coordinator = DesktopDisplayCoordinator.shared
    private val lease: RendererLease
    private var released = false

    companion object {
        private const val TAG = "AndroidSurfaceView"
    }

    init {
        coordinator.initialize(context.applicationContext)
        lease = coordinator.claim(creationParams?.get("displayId") as? Int ?: 0, ::releaseRenderer)
        X11InputController.configureDisplayScale()
        lorieView = MainActivity.getInstance().attachLorieView(context, lease.generation)
        lorieView.setBackgroundColor(Color.TRANSPARENT)
        lorieView.setZOrderMediaOverlay(true)
        x11ServiceClient = X11ServiceClient(
            context = context,
            onConnected = { serverPid, connectionFd, logcatFd ->
                if (!coordinator.isCurrent(lease)) {
                    connectionFd.close()
                    logcatFd?.close()
                    return@X11ServiceClient
                }
                try {
                    if (!LorieView.connected() || X11ServiceClient.connectedServerPid != serverPid) {
                        LorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                        X11ServiceClient.connectedServerPid = serverPid
                        lorieView.onXConnectionChanged()
                    } else {
                        connectionFd.close()
                        logcatFd?.close()
                    }
                    val store = DisplaySettingsStore(context)
                    val settings = store.load(ProfileKind.PHONE) ?: DisplayProfilePolicy.defaults(ProfileKind.PHONE)
                    inputController = X11InputController(
                        lorieView, lease.generation, settings.inputMode,
                        onModeChanged = { mode ->
                            store.save(ProfileKind.PHONE, settings.copy(inputMode = mode))
                        },
                        onViewportChanged = { width, height, _ ->
                            if (width > 0 && height > 0) {
                                coordinator.reportGeometry(lease, ProfileKind.PHONE, PixelSize(width, height))
                            }
                        },
                    )
                    Log.i(TAG, "LorieView attached to X11 service pid=$serverPid")
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    Log.e(TAG, "Failed to attach LorieView", error)
                }
            },
            onError = { message, error -> Log.e(TAG, message, error) },
        )
        // Always bind so a stale native connected flag cannot hide a restarted
        // X11 service process.
        x11ServiceClient?.connect()
    }

    override fun getView(): View {
        return lorieView
    }

    override fun dispose() {
        coordinator.release(lease)
        releaseRenderer()
    }

    private fun releaseRenderer() {
        if (released) return
        released = true
        inputController?.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        MainActivity.getInstance().releaseLorieView(lease.generation)
        if (::lorieView.isInitialized) (lorieView.parent as? ViewGroup)?.removeView(lorieView)
    }
}
