package com.orailnoor.droiddesk.display

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

interface DisplayPreferences {
    fun read(key: String): Any?
    fun write(changes: Map<String, Any?>)
}

class DisplaySettingsStore(
    private val preferences: DisplayPreferences,
    private val legacyInputMode: () -> Int?,
    private val diagnostic: (String) -> Unit,
) {
    private constructor(preferences: AndroidDisplayPreferences) : this(
        preferences,
        preferences::legacyInputMode,
        { Log.w("DesktopDisplay", it) },
    )

    constructor(context: Context) : this(AndroidDisplayPreferences(context))

    fun loadAutoOpenOnMonitor(): Boolean {
        ensureSchema()
        val value = preferences.read("autoOpenOnMonitor")
        if (value == null) return true
        if (value is Boolean) return value
        diagnostic("Invalid autoOpenOnMonitor; restoring Enabled")
        preferences.write(mapOf("autoOpenOnMonitor" to true))
        return true
    }

    fun saveAutoOpenOnMonitor(enabled: Boolean) {
        ensureSchema()
        val previous = preferences.read("autoOpenOnMonitor")
        try {
            preferences.write(mapOf("autoOpenOnMonitor" to enabled))
        } catch (failure: IllegalStateException) {
            try {
                preferences.write(mapOf("autoOpenOnMonitor" to previous))
            } catch (rollbackFailure: IllegalStateException) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun load(kind: ProfileKind): DisplaySettings? {
        ensureSchema()
        if (kind == ProfileKind.PHONE && preferences.read("phone.inputMigrated") != true) {
            val changes = mutableMapOf<String, Any?>("phone.inputMigrated" to true)
            if (preferences.read("phone.inputMode") == null) {
                legacyInputMode()?.let { mode ->
                    if (mode !in 1..3) diagnostic("Invalid legacy phone inputMode; restoring Trackpad")
                    changes["phone.inputMode"] = mode.takeIf { it in 1..3 } ?: 1
                }
            }
            preferences.write(changes)
        }
        val prefix = kind.wireName
        val renderValue = preferences.read("$prefix.renderPolicy")
        val scaleValue = preferences.read("$prefix.uiScalePercent")
        val inputValue = preferences.read("$prefix.inputMode")
        if (renderValue == null && scaleValue == null && inputValue == null) return null

        val defaults = DisplayProfilePolicy.defaults(kind)
        val repairs = mutableMapOf<String, Any?>()
        val render = RenderPolicy.fromWire(renderValue as? String) ?: defaults.renderPolicy.also {
            if (renderValue != null) {
                diagnostic("Invalid $prefix.renderPolicy; restoring ${it.wireName}")
                repairs["$prefix.renderPolicy"] = it.wireName
            }
        }
        val scale = (scaleValue as? Int)?.takeIf { it in DisplaySettings.UI_SCALES }
        if (scaleValue != null && scale == null) {
            diagnostic("Invalid $prefix.uiScalePercent; restoring Automatic")
            repairs["$prefix.uiScalePercent"] = null
        }
        val input = (inputValue as? Int)?.takeIf { it in 1..3 } ?: defaults.inputMode.also {
            if (inputValue != null) {
                diagnostic("Invalid $prefix.inputMode; restoring Trackpad")
                repairs["$prefix.inputMode"] = it
            }
        }
        if (repairs.isNotEmpty()) preferences.write(repairs)
        return DisplaySettings(render, scale, input)
    }

    fun save(kind: ProfileKind, settings: DisplaySettings) {
        ensureSchema()
        val prefix = kind.wireName
        val changes = mutableMapOf<String, Any?>(
            "$prefix.renderPolicy" to settings.renderPolicy.wireName,
            "$prefix.uiScalePercent" to settings.uiScalePercent,
            "$prefix.inputMode" to settings.inputMode,
        )
        if (kind == ProfileKind.PHONE) changes["phone.inputMigrated"] = true
        preferences.write(changes)
    }

    private fun ensureSchema() {
        val version = preferences.read("schemaVersion")
        if (version == 1) return
        if (version != null) diagnostic("Invalid display schemaVersion; restoring version 1")
        preferences.write(mapOf("schemaVersion" to 1))
    }
}

private class AndroidDisplayPreferences(context: Context) : DisplayPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        "droiddesk_display_v1", Context.MODE_PRIVATE,
    )
    private val legacy = context.applicationContext.getSharedPreferences("desktop_input", Context.MODE_PRIVATE)

    fun legacyInputMode(): Int? {
        val value = legacy.all["mode"]
        if (value != null && value !is Int) {
            Log.w("DesktopDisplay", "Invalid legacy phone inputMode; restoring Trackpad")
        }
        return value as? Int
    }

    override fun read(key: String): Any? = preferences.all[key]

    override fun write(changes: Map<String, Any?>) {
        val editor: SharedPreferences.Editor = preferences.edit()
        changes.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported display preference type for $key")
            }
        }
        check(editor.commit()) { "Could not persist display preferences" }
    }
}
