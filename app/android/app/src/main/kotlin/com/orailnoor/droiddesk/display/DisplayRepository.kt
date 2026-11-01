package com.orailnoor.droiddesk.display

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.util.UUID

class DesktopDisplayException(val code: String, message: String) : RuntimeException(message)

data class DesktopDisplay(
    val id: Int,
    val key: String,
    val name: String,
    val isDefault: Boolean,
    val eligible: Boolean,
    val reason: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "key" to key, "name" to name, "isDefault" to isDefault,
        "eligible" to eligible, "reason" to reason,
    )
}

data class DisplayCandidate(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
    val isValid: Boolean = true,
    val isPrivate: Boolean = false,
    val isOn: Boolean = true,
    val allowed: Boolean = true,
    val reason: String? = null,
)

interface DesktopDisplaySource {
    fun snapshot(): List<DisplayCandidate>
    fun start(onChanged: (removedId: Int?) -> Unit)
    fun close()
}

class DisplayRepository(private val source: DesktopDisplaySource) {
    constructor(context: Context) : this(AndroidDisplaySource(context.applicationContext))

    private val keys = mutableMapOf<Int, String>()
    private var listener: ((List<DesktopDisplay>) -> Unit)? = null
    private var last = emptyList<DesktopDisplay>()
    private var started = false

    fun snapshot(): List<DesktopDisplay> {
        val candidates = source.snapshot()
        val liveIds = candidates.map { it.id }.toSet()
        keys.keys.retainAll(liveIds)
        return candidates.filter {
            it.isValid && !it.isPrivate && (it.isDefault || it.isOn)
        }.map {
            val eligible = it.isDefault || it.allowed
            DesktopDisplay(
                it.id, keys.getOrPut(it.id) { UUID.randomUUID().toString() }, it.name,
                it.isDefault, eligible, if (eligible) null else it.reason,
            )
        }
    }

    fun requireDisplay(key: String): DesktopDisplay =
        snapshot().firstOrNull { it.key == key }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display connection is no longer available")

    fun start(onChanged: (List<DesktopDisplay>) -> Unit) {
        listener = onChanged
        if (started) return
        started = true
        last = snapshot()
        source.start { removedId ->
            removedId?.let(keys::remove)
            val current = snapshot()
            if (current != last) {
                last = current
                listener?.invoke(current)
            }
        }
    }

    fun close() {
        source.close()
        started = false
        listener = null
        keys.clear()
        last = emptyList()
    }
}

private class AndroidDisplaySource(private val context: Context) : DesktopDisplaySource {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private var listener: DisplayManager.DisplayListener? = null

    override fun snapshot(): List<DisplayCandidate> {
        val supported = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
        )
        return manager.displays.map { display ->
            val isDefault = display.displayId == Display.DEFAULT_DISPLAY
            val allowed = isDefault || supported && allowed(display)
            DisplayCandidate(
                display.displayId, display.name, isDefault, display.isValid,
                display.flags and Display.FLAG_PRIVATE != 0, display.state == Display.STATE_ON,
                allowed, if (allowed) null else if (!supported) {
                    "Android does not expose secondary-display activity placement"
                } else {
                    "Android does not allow this app to open on this display"
                },
            )
        }
    }

    private fun allowed(display: Display): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(
                context, display.displayId,
                DesktopDisplayLauncher.intent(context, display.displayId, "eligibility", 0L),
            )
        } catch (error: SecurityException) {
            Log.w("DesktopDisplay", "Display launch permission check failed", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.w("DesktopDisplay", "Display changed during permission check", error)
            false
        }
    }

    override fun start(onChanged: (Int?) -> Unit) {
        if (listener != null) return
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChanged(null)
            override fun onDisplayChanged(displayId: Int) = onChanged(null)
            override fun onDisplayRemoved(displayId: Int) = onChanged(displayId)
        }.also { manager.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
    }

    override fun close() {
        listener?.let(manager::unregisterDisplayListener)
        listener = null
    }
}
