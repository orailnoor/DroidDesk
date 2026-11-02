package com.orailnoor.droiddesk.display

import android.os.Looper
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

data class RendererLease(val generation: Long, val displayId: Int)

data class DesktopLaunchRequest(
    val display: DesktopDisplay,
    val requestId: Long,
    val sessionGeneration: Long,
    val mode: String,
    val desktopEnv: String,
)

interface DesktopDisplayHost {
    fun displays(): List<DesktopDisplay>
    fun defaultProfileKind(): ProfileKind
    fun sessionRunning(): Boolean
    fun launch(request: DesktopLaunchRequest)
    fun focus(requestId: Long): Boolean
    fun cancelLaunch(requestId: Long)
    fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit
    fun notifyReturnRequired(error: DesktopDisplayException)
}

class DesktopDisplayCoordinator(
    private val assertMainThread: () -> Unit = {},
    private var host: DesktopDisplayHost? = null,
    private var settingsStore: DisplaySettingsStore? = null,
) {
    private data class Owner(
        val lease: RendererLease,
        val detach: () -> Unit,
        var requestId: Long? = null,
        var finish: (() -> Unit)? = null,
        var actualDisplay: () -> Int = { lease.displayId },
    )
    private data class Move(
        val request: DesktopLaunchRequest,
        val completion: (Result<Unit>) -> Unit,
        val previous: Owner?,
        val previousTarget: DesktopDisplay?,
        val recovery: Boolean,
        var cancelTimeout: () -> Unit = {},
    )
    private var owner: Owner? = null
    private var generation = 0L
    private var repository: DisplayRepository? = null
    private val listeners = mutableSetOf<(Map<String, Any?>) -> Unit>()
    private val sessionStartListeners = mutableSetOf<(Result<Unit>) -> Unit>()
    private var appliedKind: ProfileKind? = null
    private var renderSize: PixelSize? = null
    private var profileRefresher: (((Result<Unit>) -> Unit) -> Unit)? = null
    private var move: Move? = null
    private var requestSequence = 0L
    private var lastTarget: DesktopDisplay? = null
    private var acceptedRequest: Long? = null
    private var phase = "hidden"
    private var error: DesktopDisplayException? = null
    private var pendingStops = 0
    private var needsSessionStart = false
    private var sessionStarting = false
    private var cancelAutomaticTimer: (() -> Unit)? = null
    private var automaticTargetKey: String? = null
    private var automaticSequence = 0L
    private var automaticEpoch = 0L
    private val automaticAttempts = mutableSetOf<String>()
    private val automaticSuppressed = mutableSetOf<String>()
    val isStarting: Boolean get() = sessionStarting
    var sessionMode = "termux"
        private set
    var desktopEnv = "xfce4"
        private set
    @Volatile var isStopped = false
        private set
    @Volatile var sessionGeneration = 1L
        private set

    fun beginSession(): Long {
        assertMainThread()
        if (pendingStops > 0) throw DesktopDisplayException("SESSION_STOPPED", "Desktop teardown is still finishing")
        if (isStopped) {
            isStopped = false
            sessionGeneration++
            phase = "hidden"
            error = null
            publish()
        }
        return sessionGeneration
    }

    fun isSessionCurrent(value: Long): Boolean = !isStopped && sessionGeneration == value

    fun prepareSession(mode: String, desktop: String, start: Boolean, expectedEpoch: Long? = null): Long {
        assertMainThread()
        if (expectedEpoch != null && !isSessionCurrent(expectedEpoch)) {
            throw DesktopDisplayException("SESSION_STOPPED", "The deferred desktop action belongs to a stopped session")
        }
        require(mode == "termux" || mode == "chroot") { "Invalid Linux runtime" }
        val epoch = beginSession()
        sessionMode = mode
        desktopEnv = desktop
        if (start) {
            needsSessionStart = true
            sessionStarting = true
            profileRefresher?.invoke {}
        }
        return epoch
    }

    fun consumeSessionStart(lease: RendererLease): Boolean {
        assertMainThread()
        if (!isCurrent(lease) || !needsSessionStart) return false
        needsSessionStart = false
        return true
    }

    fun sessionStarted(epoch: Long, result: Result<Unit>) {
        assertMainThread()
        if (!isSessionCurrent(epoch)) return
        sessionStarting = false
        result.exceptionOrNull()?.let {
            reportError(DesktopDisplayException("SESSION_STOPPED", it.message ?: "Linux session failed to start"))
        }
        if (result.isSuccess) publish()
        sessionStartListeners.toList().forEach { if (isSessionCurrent(epoch)) it(result) }
        if (result.isSuccess && isSessionCurrent(epoch)) refreshAutomaticOpening()
    }

    fun startStopping() {
        assertMainThread()
        pendingStops++
        stop()
    }

    fun finishStopping() {
        assertMainThread()
        check(pendingStops > 0)
        pendingStops--
        publish()
    }

    // The cleanup belongs to this lease and runs before the next owner attaches.
    fun claim(displayId: Int, detachPrevious: () -> Unit): RendererLease {
        assertMainThread()
        check(!isStopped) { "SESSION_STOPPED" }
        val previous = owner
        owner = null
        profileRefresher = null
        previous?.detach?.invoke()
        if (move == null) previous?.finish?.invoke()
        check(!isStopped) { "SESSION_STOPPED" }
        appliedKind = null
        renderSize = null
        return RendererLease(++generation, displayId).also {
            owner = Owner(it, detachPrevious)
            publish()
        }
    }

    fun isCurrent(lease: RendererLease): Boolean = owner?.lease == lease && !isStopped

    fun registerActivity(
        lease: RendererLease,
        requestId: Long,
        actualDisplay: () -> Int = { lease.displayId },
        finish: () -> Unit,
    ) {
        assertMainThread()
        owner?.takeIf { it.lease == lease }?.let {
            it.requestId = requestId
            it.actualDisplay = actualDisplay
            it.finish = finish
        }
    }

    fun release(lease: RendererLease) {
        assertMainThread()
        val current = owner ?: return
        if (current.lease != lease) return
        owner = null
        profileRefresher = null
        current.detach()
        appliedKind = null
        renderSize = null
        if (move == null && !isStopped) phase = "hidden"
        publish()
    }

    fun stop() {
        assertMainThread()
        if (isStopped) return
        cancelAutomaticOpening()
        isStopped = true
        sessionGeneration++
        generation++
        needsSessionStart = false
        sessionStarting = false
        val pending = move
        move = null
        pending?.cancelTimeout?.invoke()
        pending?.let { host?.cancelLaunch(it.request.requestId) }
        val previous = owner
        owner = null
        profileRefresher = null
        previous?.detach?.invoke()
        previous?.finish?.invoke()
        if (pending?.previous?.lease != previous?.lease) pending?.previous?.finish?.invoke()
        appliedKind = null
        renderSize = null
        lastTarget = null
        acceptedRequest = null
        phase = "stopped"
        error = null
        publish()
        pending?.completion?.invoke(Result.failure(DesktopDisplayException("SESSION_STOPPED", "The Linux session was stopped")))
    }

    fun initialize(context: Context) {
        assertMainThread()
        if (host != null) return
        settingsStore = DisplaySettingsStore(context)
        val discovery = DisplayRepository(context)
        repository = discovery
        host = AndroidDesktopDisplayHost(context.applicationContext, discovery)
        discovery.start { onDisplaysChanged() }
        refreshAutomaticOpening()
    }

    fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        assertMainThread()
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun subscribeSessionStart(listener: (Result<Unit>) -> Unit): () -> Unit {
        assertMainThread()
        sessionStartListeners.add(listener)
        return { sessionStartListeners.remove(listener) }
    }

    fun displays(): List<DesktopDisplay> = checkNotNull(host) { "Display discovery is not initialized" }.displays()

    fun snapshot(): Map<String, Any?> {
        assertMainThread()
        val displays = displays()
        val active = owner?.actualDisplay?.invoke()?.let { id -> displays.firstOrNull { it.id == id } }
        return mapOf(
            "schemaVersion" to 1,
            "autoOpenOnMonitor" to automaticOpeningEnabled(),
            "phase" to phase,
            "activeDisplayId" to active?.id, "activeDisplayKey" to active?.key,
            "profileKind" to active?.let { kindFor(it).wireName },
            "renderWidth" to renderSize?.width, "renderHeight" to renderSize?.height,
            "displays" to displays.map(DesktopDisplay::toMap),
            "error" to error?.let { mapOf("code" to it.code, "message" to it.message) },
        )
    }

    fun settingsFor(displayKey: String): Map<String, Any?> {
        assertMainThread()
        val display = displays().firstOrNull { it.key == displayKey }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected connection is no longer available")
        val settings = loadSettings(kindFor(display)) ?: DisplayProfilePolicy.defaults(kindFor(display))
        return mapOf(
            "displayKey" to display.key, "renderPolicy" to settings.renderPolicy.wireName,
            "uiScalePercent" to settings.uiScalePercent, "inputMode" to settings.inputMode,
        )
    }

    fun loadSettings(kind: ProfileKind): DisplaySettings? = checkNotNull(settingsStore).load(kind)

    fun setAutoOpenOnMonitor(enabled: Boolean) {
        assertMainThread()
        checkNotNull(settingsStore).saveAutoOpenOnMonitor(enabled)
        cancelAutomaticOpening()
        if (enabled) {
            automaticAttempts.clear()
            automaticSuppressed.clear()
        }
        publish()
        refreshAutomaticOpening()
    }

    fun setProfileRefresher(lease: RendererLease, refresh: ((Result<Unit>) -> Unit) -> Unit) {
        assertMainThread()
        if (isCurrent(lease)) profileRefresher = refresh
    }

    fun saveProfileSettings(
        kind: ProfileKind,
        settings: DisplaySettings,
        completion: (Result<Unit>) -> Unit = {},
    ) {
        assertMainThread()
        checkNotNull(settingsStore).save(kind, settings)
        if (appliedKind == kind && owner != null) {
            val refresh = profileRefresher
            if (refresh == null) completion(Result.failure(DesktopDisplayException(
                "PROFILE_APPLY_FAILED", "Display settings were saved but the active view cannot apply them",
            ))) else {
                val completed = AtomicBoolean(false)
                val lease = owner?.lease
                val cancelTimeout = checkNotNull(host).scheduleTimeout(10_000L) {
                    if (completed.compareAndSet(false, true)) {
                        val failure = DesktopDisplayException(
                            "PROFILE_APPLY_FAILED", "Display settings were saved but application was not confirmed; retry the profile",
                        )
                        if (lease != null && isCurrent(lease)) reportError(failure)
                        completion(Result.failure(failure))
                    }
                }
                refresh { result ->
                    if (completed.compareAndSet(false, true)) {
                        cancelTimeout()
                        completion(result)
                    }
                }
            }
        } else {
            completion(Result.success(Unit))
        }
        publish()
    }

    fun saveSettingsFor(displayKey: String, settings: DisplaySettings, completion: (Result<Unit>) -> Unit) {
        val display = displays().firstOrNull { it.key == displayKey }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display connection is unavailable")
        saveProfileSettings(kindFor(display), settings, completion)
    }

    fun profileApplied(lease: RendererLease, outcome: Result<Unit>) {
        assertMainThread()
        if (!isCurrent(lease)) return
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            reportError(DesktopDisplayException(
                "PROFILE_APPLY_FAILED", failure.message ?: "XFCE settings could not be applied",
            ))
        } else if (error?.code == "PROFILE_APPLY_FAILED") {
            error = null
            phase = if (move != null) "moving" else "attached"
            publish()
        }
    }

    fun reportGeometry(lease: RendererLease, kind: ProfileKind, size: PixelSize) {
        assertMainThread()
        if (!isCurrent(lease)) return
        if (appliedKind == kind && renderSize == size) return
        appliedKind = kind
        renderSize = size
        publish()
    }

    private fun kindFor(display: DesktopDisplay): ProfileKind {
        val currentKind = appliedKind
        if (owner?.lease?.displayId == display.id && currentKind != null) return currentKind
        return if (display.isDefault) checkNotNull(host).defaultProfileKind() else ProfileKind.MONITOR
    }

    fun open(displayId: Int, displayKey: String, completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        val target = displays().firstOrNull { it.id == displayId && it.key == displayKey }
        if (target == null) {
            val failure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display connection changed")
            reportError(failure)
            completion(Result.failure(failure))
            return
        }
        cancelAutomaticOpening()
        if (target.isDefault) suppressAutomaticOpening()
        openTarget(target, completion, recovery = false)
    }

    fun openActive(completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        val current = displays()
        val actual = owner?.actualDisplay?.invoke()
        val target = current.firstOrNull { it.id == actual }
            ?: current.firstOrNull { it.key == lastTarget?.key }
            ?: current.firstOrNull { it.isDefault }
        if (target == null) {
            completion(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "No phone display is available")))
        } else {
            openTarget(target, completion, recovery = false)
        }
    }

    fun moveToPhone(completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        suppressAutomaticOpening()
        val phone = displays().firstOrNull { it.isDefault }
        if (phone == null) {
            completion(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The phone display is unavailable")))
        } else {
            openTarget(phone, completion, recovery = false)
        }
    }

    private fun openTarget(target: DesktopDisplay, completion: (Result<Unit>) -> Unit, recovery: Boolean) {
        val platform = checkNotNull(host)
        val failure = when {
            isStopped || (!sessionStarting && !platform.sessionRunning()) ->
                DesktopDisplayException("SESSION_STOPPED", "Start the Linux desktop before moving its view")
            move != null -> DesktopDisplayException("INVALID_ARGUMENT", "A desktop move is already in progress")
            !target.eligible -> DesktopDisplayException("DISPLAY_NOT_ALLOWED", target.reason ?: "Android denied this display")
            else -> null
        }
        if (failure != null) {
            if (move == null) reportError(failure)
            completion(Result.failure(failure))
            return
        }
        val currentOwner = owner
        val requestId = currentOwner?.requestId
        if (currentOwner?.lease?.displayId == target.id && currentOwner.actualDisplay() == target.id &&
            requestId != null && platform.focus(requestId)) {
            completion(Result.success(Unit))
            return
        }
        val request = DesktopLaunchRequest(target, ++requestSequence, sessionGeneration, sessionMode, desktopEnv)
        val pending = Move(request, completion, currentOwner, lastTarget, recovery)
        move = pending
        phase = "moving"
        if (!recovery) error = null
        pending.cancelTimeout = platform.scheduleTimeout(10_000L) {
            if (move?.request?.requestId == request.requestId) {
                failMove(DesktopDisplayException("PLACEMENT_TIMEOUT", "Android did not confirm desktop placement"), !recovery)
            }
        }
        publish()
        if (move !== pending || !isSessionCurrent(request.sessionGeneration)) return
        try {
            platform.launch(request)
        } catch (failure: DesktopDisplayException) {
            failMove(failure, !recovery)
        }
    }

    fun canAttach(requestId: Long, displayId: Int, displayKey: String, epoch: Long): Boolean {
        assertMainThread()
        if (!isSessionCurrent(epoch)) return false
        val target = displays().firstOrNull { it.id == displayId && it.key == displayKey } ?: return false
        if (!target.eligible) return false
        val pending = move
        return if (pending != null) {
            pending.request.requestId == requestId && pending.request.display.key == target.key
        } else {
            acceptedRequest == requestId && lastTarget?.key == target.key
        }
    }

    fun rejectAttachment(requestId: Long) {
        assertMainThread()
        if (move?.request?.requestId == requestId) {
            failMove(DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android opened the activity on a different display"), true)
        }
    }

    fun onAttached(lease: RendererLease, requestId: Long, measured: PixelSize) {
        assertMainThread()
        if (!isCurrent(lease) || measured.width <= 0 || measured.height <= 0) return
        val pending = move
        if (pending == null) {
            if (requestId == acceptedRequest) {
                phase = "attached"
                publish()
                refreshAutomaticOpening()
            }
            return
        }
        val target = pending.request.display
        if (pending.request.requestId != requestId || lease.displayId != target.id || owner?.actualDisplay?.invoke() != target.id ||
            displays().none { it.id == target.id && it.key == target.key && it.eligible }) return
        move = null
        pending.cancelTimeout()
        lastTarget = target
        acceptedRequest = requestId
        phase = "attached"
        if (!pending.recovery) error = null
        if (pending.previous?.lease != lease) pending.previous?.finish?.invoke()
        publish()
        pending.completion(Result.success(Unit))
        refreshAutomaticOpening()
    }

    fun onDisplayRemoved(displayId: Int) {
        assertMainThread()
        if (move?.request?.display?.id == displayId) {
            failMove(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected monitor disconnected"), true)
        } else if (!isStopped && move == null && lastTarget?.id == displayId) {
            val phone = displays().firstOrNull { it.isDefault }
            if (phone != null) openTarget(phone, {}, recovery = true)
        }
    }

    fun onDisplaysChanged() {
        assertMainThread()
        val current = displays()
        val pending = move
        if (pending != null && current.none { it.key == pending.request.display.key }) {
            onDisplayRemoved(pending.request.display.id)
        } else if (pending != null && current.any { it.key == pending.request.display.key && !it.eligible }) {
            failMove(DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Display placement permission changed"), true)
        } else if (pending == null) {
            lastTarget?.takeIf { target -> current.none { it.key == target.key } }?.let { onDisplayRemoved(it.id) }
        }
        profileRefresher?.invoke {}
        publish()
        refreshAutomaticOpening()
    }

    private fun automaticOpeningEnabled(): Boolean = settingsStore?.loadAutoOpenOnMonitor() ?: true

    private fun cancelAutomaticOpening() {
        automaticSequence++
        cancelAutomaticTimer?.invoke()
        cancelAutomaticTimer = null
        automaticTargetKey = null
    }

    private fun suppressAutomaticOpening() {
        cancelAutomaticOpening()
        automaticSuppressed += displays().filterNot { it.isDefault }.map { it.key }
    }

    private fun automaticTarget(current: List<DesktopDisplay>): DesktopDisplay? {
        val platform = host ?: return null
        if (isStopped || sessionStarting || move != null || !automaticOpeningEnabled() || !platform.sessionRunning()) {
            return null
        }
        val actual = owner?.actualDisplay?.invoke()
        if (current.any { it.id == actual && !it.isDefault }) return null
        return current.filter { !it.isDefault && it.eligible }.singleOrNull()?.takeIf {
            it.key !in automaticAttempts && it.key !in automaticSuppressed
        }
    }

    private fun refreshAutomaticOpening() {
        val platform = host ?: return
        val current = platform.displays()
        val connections = current.filterNot { it.isDefault }.map { it.key }.toSet()
        automaticAttempts.retainAll(connections)
        automaticSuppressed.retainAll(connections)
        if (automaticEpoch != sessionGeneration) {
            automaticEpoch = sessionGeneration
            automaticAttempts.clear()
        }
        val target = automaticTarget(current)
        if (target == null) {
            cancelAutomaticOpening()
            return
        }
        if (automaticTargetKey == target.key) return
        cancelAutomaticOpening()
        automaticTargetKey = target.key
        val sequence = automaticSequence
        val epoch = sessionGeneration
        cancelAutomaticTimer = platform.scheduleTimeout(500L) {
            if (sequence != automaticSequence || !isSessionCurrent(epoch)) return@scheduleTimeout
            cancelAutomaticTimer = null
            automaticTargetKey = null
            val latest = automaticTarget(platform.displays())
            if (latest?.key != target.key) {
                refreshAutomaticOpening()
                return@scheduleTimeout
            }
            automaticAttempts += latest.key
            openTarget(latest, {}, recovery = false)
        }
    }

    fun onActivityMoved(lease: RendererLease, displayId: Int) {
        assertMainThread()
        if (!isCurrent(lease) || lease.displayId == displayId || move != null) return
        val target = displays().firstOrNull { it.id == displayId && it.eligible } ?: return
        openTarget(target, {}, recovery = true)
    }

    private fun failMove(failure: DesktopDisplayException, recover: Boolean) {
        val pending = move ?: return
        move = null
        pending.cancelTimeout()
        checkNotNull(host).cancelLaunch(pending.request.requestId)
        val incoming = owner?.takeIf { it.requestId == pending.request.requestId }
        if (incoming != null) {
            release(incoming.lease)
            incoming.finish?.invoke()
            pending.previous?.finish?.invoke()
        }
        error = failure
        phase = "error"
        publish()
        pending.completion(Result.failure(failure))
        if (isStopped || move != null) return
        val available = displays()
        val remainingOwner = owner
        if (remainingOwner != null) {
            val actual = remainingOwner.actualDisplay()
            if (actual == remainingOwner.lease.displayId && available.any { it.id == actual && it.eligible }) return
            release(remainingOwner.lease)
            remainingOwner.finish?.invoke()
            phase = "error"
            publish()
        }
        val fallback = available.firstOrNull { it.key == pending.previousTarget?.key && it.eligible }
            ?: available.firstOrNull { it.isDefault && it.eligible }
        if (recover && !pending.recovery && fallback != null && fallback.key != pending.request.display.key) {
            openTarget(fallback, {}, recovery = true)
        } else {
            checkNotNull(host).notifyReturnRequired(failure)
        }
    }

    fun reportError(failure: DesktopDisplayException) {
        assertMainThread()
        error = failure
        if (!isStopped) phase = "error"
        publish()
    }

    private fun publish() {
        if (host == null || listeners.isEmpty()) return
        val state = snapshot()
        listeners.toList().forEach { it(state) }
    }

    companion object {
        val shared: DesktopDisplayCoordinator by lazy {
            DesktopDisplayCoordinator(assertMainThread = {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Display transitions require the main thread" }
            })
        }
    }
}
