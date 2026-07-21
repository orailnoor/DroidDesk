package com.orailnoor.droiddesk.runtime

import android.util.Log
import java.io.File

/**
 * Installs a minimal, touch-usable Openbox profile for low-RAM devices.
 *
 * Openbox itself ships no panel or background, so this writes an [autostart]
 * that launches the tint2 panel and paints a solid desktop, plus a small
 * right-click [menu.xml] with a terminal and file manager. Everything is kept
 * deliberately light — no compositor, no notification daemon, no search
 * indexer — so a 2 GB device (e.g. Samsung Galaxy A10s) has headroom.
 */
object OpenboxMobileProfile {
    private const val TAG = "OpenboxMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-openbox-mobile-v1"

    fun install(homeDir: File): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            val configDir = File(homeDir, ".config/openbox").apply { mkdirs() }

            File(configDir, "autostart").writeText(
                """
                #!/bin/sh
                # Lightweight panel; solid background instead of a wallpaper image.
                tint2 &
                (feh --bg-color '#1e293b' 2>/dev/null || xsetroot -solid '#1e293b') &
                pcmanfm --daemon-mode &
                """.trimIndent() + "\n",
            )

            File(configDir, "menu.xml").writeText(menuXml())

            marker.writeText("1\n")
            Log.i(TAG, "Installed Openbox mobile profile in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install Openbox mobile profile", error)
            false
        }
    }

    private fun menuXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <openbox_menu xmlns="http://openbox.org/3.4/menu">
          <menu id="root-menu" label="Openbox">
            <item label="Terminal">
              <action name="Execute"><command>xterm</command></action>
            </item>
            <item label="Files">
              <action name="Execute"><command>pcmanfm</command></action>
            </item>
            <separator/>
            <item label="Reconfigure">
              <action name="Reconfigure"/>
            </item>
            <item label="Exit">
              <action name="Exit"/>
            </item>
          </menu>
        </openbox_menu>
    """.trimIndent() + "\n"
}
