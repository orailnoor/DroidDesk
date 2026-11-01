package com.orailnoor.droiddesk.display

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopLaunchReply(
    private val delegate: MethodChannel.Result,
    coordinator: DesktopDisplayCoordinator,
) : MethodChannel.Result {
    private val completed = AtomicBoolean(false)
    private var unsubscribe: () -> Unit = {}

    init {
        unsubscribe = coordinator.subscribe { state ->
            if (state["phase"] == "stopped") {
                error("SESSION_STOPPED", "The session stopped before launch completed", null)
            }
        }
    }

    override fun success(result: Any?) {
        if (!completed.compareAndSet(false, true)) return
        unsubscribe()
        delegate.success(result)
    }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        if (!completed.compareAndSet(false, true)) return
        unsubscribe()
        delegate.error(errorCode, errorMessage, errorDetails)
    }

    override fun notImplemented() {
        if (!completed.compareAndSet(false, true)) return
        unsubscribe()
        delegate.notImplemented()
    }
}
