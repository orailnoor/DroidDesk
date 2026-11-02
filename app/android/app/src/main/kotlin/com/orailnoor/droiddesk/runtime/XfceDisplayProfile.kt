package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.ProfileKind
import kotlin.math.roundToInt

object XfceDisplayProfile {
    fun maximizeScript(): String = """
        -- Managed by DroidDesk; read the active profile for every new window.
        local config = os.getenv("XDG_CONFIG_HOME") or (os.getenv("HOME") .. "/.config")
        local file = io.open(config .. "/droiddesk/display-profile", "r")
        local mode = file and file:read("*l") or "phone"
        if file then file:close() end
        if mode == "phone" and get_window_type() == "WINDOW_TYPE_NORMAL" then
          if get_screen_geometry then
            local screen_w, screen_h = get_screen_geometry()
            local w = math.floor(screen_w * 0.9)
            local h = math.floor(screen_h * 0.8)
            set_window_geometry(math.floor((screen_w - w) / 2), math.floor((screen_h - h) / 2), w, h)
          end
          maximize()
        end
    """.trimIndent()

    fun buildStageScript(profile: DisplayProfile): String {
        require(profile.dpi > 0 && profile.cursorPx > 0 && profile.dockPx > 0)
        return buildString {
            appendLine("""
                set -eu
                : "${'$'}{HOME:?Desktop home is unavailable}"
                config="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
                managed="${'$'}config/droiddesk"
                mkdir -p "${'$'}managed" "${'$'}config/devilspie2" "${'$'}config/autostart"
                printf '%s\n' '${profile.kind.wireName}' > "${'$'}managed/.display-profile.${'$'}${'$'}"
                mv "${'$'}managed/.display-profile.${'$'}${'$'}" "${'$'}managed/display-profile"
                cat > "${'$'}config/devilspie2/.droiddesk-maximize.${'$'}${'$'}.tmp" <<'DROIDDESK_LUA'
            """.trimIndent())
            appendLine(maximizeScript())
            appendLine("DROIDDESK_LUA")
            appendLine("""
                mv "${'$'}config/devilspie2/.droiddesk-maximize.${'$'}${'$'}.tmp" "${'$'}config/devilspie2/droiddesk-maximize.lua"
                printf 'Xft.dpi: ${profile.dpi}\nXcursor.size: ${profile.cursorPx}\n' > "${'$'}managed/.Xresources.${'$'}${'$'}"
                mv "${'$'}managed/.Xresources.${'$'}${'$'}" "${'$'}managed/display-profile.Xresources"
                cat > "${'$'}config/autostart/.droiddesk-automax.${'$'}${'$'}.tmp" <<'DROIDDESK_AUTOSTART'
            """.trimIndent())
            appendLine("""
                [Desktop Entry]
                Type=Application
                Name=DroidDesk window fitting
                Comment=Fit new phone windows without changing monitor windows
                Exec=sh -c 'command -v devilspie2 >/dev/null 2>&1 && exec devilspie2'
                OnlyShowIn=XFCE;
                NoDisplay=true
            """.trimIndent())
            appendLine("DROIDDESK_AUTOSTART")
            appendLine("""mv "${'$'}config/autostart/.droiddesk-automax.${'$'}${'$'}.tmp" "${'$'}config/autostart/droiddesk-automax.desktop" """.trim())
        }
    }

    fun buildApplyScript(profile: DisplayProfile): String {
        val iconSize = if (profile.kind == ProfileKind.PHONE && profile.settings.uiScalePercent == null) {
            60
        } else {
            (profile.dockPx * 2.0 / 3.0).roundToInt()
        }
        return buildStageScript(profile) + """
            : "${'$'}{DBUS_SESSION_BUS_ADDRESS:?Desktop session bus is unavailable}"
            command -v xfconf-query >/dev/null
            command -v xrdb >/dev/null
            command -v timeout >/dev/null
            if ! timeout --foreground --kill-after=1 5 "${'$'}BASH" -c \
              'until timeout --foreground --kill-after=1 1 xfconf-query -c xsettings -l >/dev/null 2>&1; do sleep 0.1; done'; then
              echo "XFCE settings were not ready within five seconds on the desktop session bus" >&2
              exit 1
            fi
            set_property() {
              if timeout --foreground --kill-after=1 1 xfconf-query -c "${'$'}1" -p "${'$'}2" >/dev/null 2>&1; then
                timeout --foreground --kill-after=1 2 xfconf-query -c "${'$'}1" -p "${'$'}2" -s "${'$'}4"
              else
                timeout --foreground --kill-after=1 2 xfconf-query -c "${'$'}1" -p "${'$'}2" -n -t "${'$'}3" -s "${'$'}4"
              fi
            }
            set_property xsettings /Xft/DPI int ${profile.dpi}
            set_property xsettings /Net/CursorSize int ${profile.cursorPx}
            set_property xsettings /Gtk/CursorThemeSize int ${profile.cursorPx}
            set_property xfce4-panel /panels/panel-2/size uint ${profile.dockPx}
            set_property xfce4-panel /panels/panel-2/icon-size uint $iconSize
            timeout --foreground --kill-after=1 2 xrdb -merge "${'$'}managed/display-profile.Xresources"
            if command -v devilspie2 >/dev/null 2>&1; then
              if ! pgrep -x devilspie2 >/dev/null; then
                devilspie2 >> "${'$'}managed/window-fitting.log" 2>&1 &
                helper_pid=${'$'}!
                sleep 0.1
                if ! kill -0 "${'$'}helper_pid" 2>/dev/null; then
                  echo "DroidDesk window-fitting helper did not stay running" >&2
                  exit 1
                fi
              fi
            elif [ '${profile.kind.wireName}' = 'phone' ]; then
              echo "The phone window-fitting helper devilspie2 is unavailable" >&2
              exit 1
            fi
        """.trimIndent() + "\n"
    }

    fun sameUi(first: DisplayProfile?, second: DisplayProfile): Boolean =
        first != null && first.kind == second.kind && first.dpi == second.dpi &&
            first.cursorPx == second.cursorPx && first.dockPx == second.dockPx &&
            first.autoMaximize == second.autoMaximize
}
