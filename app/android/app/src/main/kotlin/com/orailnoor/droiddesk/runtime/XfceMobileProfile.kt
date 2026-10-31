package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/** Installs DroidDesk's touch-friendly Ubuntu-inspired XFCE defaults once per home. */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v15"
    private const val MONOSPACE_FONT = "DejaVu Sans Mono"
    private const val WALLPAPER_ASSET = "droiddesk/sunset-wave-wallpaper.jpg"

    /** Height of the bottom launcher dock in X11 pixels; Android overlays sit above it. */
    const val DOCK_SIZE_PX = 88

    /** True when the current profile still needs to be (re)installed in [homeDir]. */
    fun needsInstall(homeDir: File): Boolean = !File(homeDir, PROFILE_MARKER).exists()

    fun install(
        context: Context,
        homeDir: File,
        wallpaperFile: File,
        wallpaperPathInSession: String = wallpaperFile.absolutePath,
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            wallpaperFile.parentFile?.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                wallpaperFile.outputStream().use(input::copyTo)
            }

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig())
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(wallpaperPathInSession)),
            )
            File(xfconfDir, "xfwm4.xml").writeText(windowManagerConfig())
            File(xfconfDir, "xsettings.xml").writeText(xsettingsConfig())
            File(xfconfDir, "xfce4-terminal.xml").writeText(terminalConfig())
            installMonospaceFontRule(homeDir)
            installPanelCss(homeDir)
            installAutoMaximize(homeDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "org.xfce.terminalemulator",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "thunar %u",
                icon = "org.xfce.filemanager",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "exo-open --launch WebBrowser %u",
                icon = "org.xfce.webbrowser",
            )

            marker.writeText("1\n")
            Log.i(TAG, "Installed Ubuntu-inspired XFCE mobile profile in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile", error)
            false
        }
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            StartupNotify=true
            Terminal=false
            """.trimIndent() + "\n",
        )
    }

    private fun installPanelCss(homeDir: File) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* DroidDesk mobile panel start */"
        val endMarker = "/* DroidDesk mobile panel end */"
        val managedBlock = """
            $startMarker
            .xfce4-panel button {
              padding: 4px 8px;
            }
            $endMarker
        """.trimIndent()
        val existing = if (cssFile.exists()) cssFile.readText() else ""
        val withoutOldBlock = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        cssFile.writeText(
            if (withoutOldBlock.isEmpty()) "$managedBlock\n"
            else "$withoutOldBlock\n\n$managedBlock\n",
        )
    }

    /** Maximizes every normal window so apps never open wider than a portrait phone screen. */
    private fun installAutoMaximize(homeDir: File) {
        File(homeDir, ".config/devilspie2/droiddesk-maximize.lua").apply {
            parentFile?.mkdirs()
            writeText(
                """
                -- Managed by DroidDesk: dialogs, menus and splash screens keep their own size.
                if get_window_type() == "WINDOW_TYPE_NORMAL" then
                  -- Fit the window to the screen first so "restore" returns to a size
                  -- that is still usable on a phone, then open it maximized.
                  if get_screen_geometry then
                    local screen_w, screen_h = get_screen_geometry()
                    local w = math.floor(screen_w * 0.9)
                    local h = math.floor(screen_h * 0.8)
                    set_window_geometry(math.floor((screen_w - w) / 2), math.floor((screen_h - h) / 2), w, h)
                  end
                  maximize()
                end
                """.trimIndent() + "\n",
            )
        }
        File(homeDir, ".config/autostart/droiddesk-automax.desktop").apply {
            parentFile?.mkdirs()
            writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=DroidDesk window fitting
                Comment=Maximize new windows on small screens
                Exec=sh -c 'command -v devilspie2 >/dev/null 2>&1 && exec devilspie2'
                OnlyShowIn=XFCE;
                NoDisplay=true
                """.trimIndent() + "\n",
            )
        }
    }

    private fun windowManagerConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="theme" type="string" value="Default-hdpi"/>
            <property name="title_font" type="string" value="Sans Bold 11"/>
            <property name="button_layout" type="string" value="O|HMC"/>
            <property name="placement_ratio" type="int" value="100"/>
            <property name="tile_on_move" type="bool" value="true"/>
            <property name="snap_to_border" type="bool" value="true"/>
            <property name="wrap_windows" type="bool" value="false"/>
            <property name="easy_click" type="string" value="Alt"/>
            <property name="show_dock_shadow" type="bool" value="false"/>
            <property name="use_compositing" type="bool" value="false"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xsettingsConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xsettings" version="1.0">
          <property name="Xft" type="empty">
            <property name="DPI" type="int" value="140"/>
            <property name="Antialias" type="int" value="1"/>
            <property name="HintStyle" type="string" value="hintslight"/>
            <property name="RGBA" type="string" value="rgb"/>
          </property>
          <property name="Net" type="empty">
            <property name="ThemeName" type="string" value="Adwaita"/>
            <property name="IconThemeName" type="string" value="Papirus"/>
            <property name="CursorSize" type="int" value="32"/>
          </property>
          <property name="Gtk" type="empty">
            <property name="CursorThemeSize" type="int" value="32"/>
            <property name="FontName" type="string" value="Sans 12"/>
            <property name="ButtonImages" type="bool" value="false"/>
            <property name="DialogsUseHeader" type="bool" value="true"/>
            <property name="MonospaceFontName" type="string" value="$MONOSPACE_FONT 10"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun terminalConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-terminal" version="1.0">
          <property name="font-use-system" type="bool" value="false"/>
          <property name="font-name" type="string" value="$MONOSPACE_FONT 10"/>
        </channel>
    """.trimIndent() + "\n"

    /**
     * The native runtime's fontconfig also scans Android's /system/fonts, so "monospace" can
     * resolve to a proportional Android font and terminals draw every letter in a wide cell.
     */
    private fun installMonospaceFontRule(homeDir: File) {
        File(homeDir, ".config/fontconfig/conf.d/60-droiddesk-monospace.conf").apply {
            parentFile?.mkdirs()
            writeText(
                """
                <?xml version="1.0"?>
                <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                <!-- Managed by DroidDesk -->
                <fontconfig>
                  <alias binding="strong">
                    <family>monospace</family>
                    <prefer><family>$MONOSPACE_FONT</family></prefer>
                  </alias>
                </fontconfig>
                """.trimIndent() + "\n",
            )
        }
    }

    private fun panelConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <value type="int" value="2"/>
            <property name="dark-mode" type="bool" value="false"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=6;x=0;y=0"/>
              <property name="length" type="uint" value="1"/>
              <property name="length-adjust" type="bool" value="true"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="1"/>
              <property name="size" type="uint" value="45"/>
              <property name="icon-size" type="uint" value="30"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.965000"/>
                <value type="double" value="0.961000"/>
                <value type="double" value="0.957000"/>
                <value type="double" value="0.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="2"/>
              </property>
            </property>
            <property name="panel-2" type="empty">
              <property name="position" type="string" value="p=10;x=0;y=0"/>
              <property name="mode" type="uint" value="0"/>
              <property name="length" type="uint" value="1"/>
              <property name="length-adjust" type="bool" value="true"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="1"/>
              <property name="size" type="uint" value="$DOCK_SIZE_PX"/>
              <property name="icon-size" type="uint" value="60"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.965000"/>
                <value type="double" value="0.961000"/>
                <value type="double" value="0.957000"/>
                <value type="double" value="0.000000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="20"/>
                <value type="int" value="21"/>
                <value type="int" value="22"/>
                <value type="int" value="23"/>
                <value type="int" value="24"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-2" type="string" value="tasklist">
              <property name="show-labels" type="bool" value="false"/>
              <property name="grouping" type="uint" value="1"/>
              <property name="flat-buttons" type="bool" value="true"/>
            </property>
            <property name="plugin-20" type="string" value="applicationsmenu">
              <property name="show-button-title" type="bool" value="false"/>
            </property>
            <property name="plugin-21" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-terminal.desktop"/>
              </property>
            </property>
            <property name="plugin-22" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-files.desktop"/>
              </property>
            </property>
            <property name="plugin-23" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-browser.desktop"/>
              </property>
            </property>
            <property name="plugin-24" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun desktopConfig(wallpaperPath: String): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-desktop" version="1.0">
          <property name="last-settings-migration-version" type="uint" value="1"/>
          <property name="backdrop" type="empty">
            <property name="screen0" type="empty">
              <property name="monitorbuiltin" type="empty">
                <property name="workspace0" type="empty">
                  <property name="color-style" type="int" value="0"/>
                  <property name="image-style" type="int" value="5"/>
                  <property name="last-image" type="string" value="$wallpaperPath"/>
                </property>
              </property>
            </property>
          </property>
          <property name="desktop-icons" type="empty">
            <property name="style" type="int" value="0"/>
            <property name="icon-size" type="uint" value="72"/>
            <property name="file-icons" type="empty">
              <property name="show-home" type="bool" value="false"/>
              <property name="show-filesystem" type="bool" value="false"/>
              <property name="show-trash" type="bool" value="false"/>
              <property name="show-removable" type="bool" value="false"/>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
