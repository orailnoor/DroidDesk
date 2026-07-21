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

class AndroidSurfaceView(
    val context: Context,
    id: Int,
    creationParams: Map<String?, Any?>?
) : PlatformView {

    private val lorieView: LorieView
    private val x11ServiceClient: X11ServiceClient
    private var inputController: X11InputController? = null

    companion object {
        private const val TAG = "AndroidSurfaceView"
    }

    init {
        X11InputController.configureDisplayScale(context)
        MainActivity.getInstance().initLorieView(context)
        lorieView = MainActivity.getInstance().lorieView
        lorieView.setBackgroundColor(Color.TRANSPARENT)
        lorieView.setZOrderMediaOverlay(true)
        x11ServiceClient = X11ServiceClient(
            context = context,
            onConnected = { connectionFd, logcatFd ->
                try {
                    LorieView.connect(connectionFd.detachFd())
                    logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                    inputController = X11InputController(lorieView)
                    Log.i(TAG, "LorieView connected to the :x11 service process")
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    Log.e(TAG, "Failed to attach LorieView", error)
                }
            },
            onError = { message, error -> Log.e(TAG, message, error) },
        )
        if (!LorieView.connected()) x11ServiceClient.connect()
    }

    override fun getView(): View {
        return lorieView
    }

    override fun dispose() {
        inputController?.dispose()
        inputController = null
        x11ServiceClient.disconnect()
    }
}
