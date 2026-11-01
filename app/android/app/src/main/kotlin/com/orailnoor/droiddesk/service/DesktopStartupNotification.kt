package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator

internal class DesktopStartupNotification(
    private val coordinator: DesktopDisplayCoordinator,
    notify: (String) -> Unit,
) {
    private var lastMessage: String? = null
    private var messageEpoch = 0L
    val text: String
        get() = when {
            coordinator.isStarting -> "Linux desktop is starting"
            coordinator.isStopped -> "Linux desktop is stopped"
            messageEpoch == coordinator.sessionGeneration -> checkNotNull(lastMessage)
            else -> "Linux desktop is running"
        }

    private val unsubscribe = coordinator.subscribeSessionStart { result ->
        lastMessage = result.fold(
            onSuccess = { "Linux desktop is running" },
            onFailure = { "Linux desktop failed to start: ${it.message ?: "Check the session log"}" },
        )
        messageEpoch = coordinator.sessionGeneration
        notify(text)
    }

    fun close() {
        unsubscribe()
    }
}
