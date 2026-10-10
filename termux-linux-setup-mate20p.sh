#!/data/data/com.termux/files/usr/bin/bash
#######################################################
#  Termux Linux Setup Script - Huawei Mate 20 Pro edition
#
#  Adapted from https://github.com/orailnoor/termux-linux-setup
#  (upstream commit of 2026-03-02). Changes for the Kirin 980 / Mali-G76:
#  - Software rendering (llvmpipe) instead of Zink/Turnip, which target Adreno
#  - Wine/Hangover is optional (off by default)
#  - Install output goes to ~/linux-setup.log and failures are shown
#  - The community TUR repository is only enabled if a package needs it
#  - KDE Plasma and the Flask demo are dropped
#  - The launcher holds a wake lock so EMUI does not kill the session
#######################################################

# ============== CONFIGURATION ==============
TOTAL_STEPS=9
CURRENT_STEP=0
DE_CHOICE="1"
DE_NAME="XFCE4"
INSTALL_WINE="n"
LOG="$HOME/linux-setup.log"
FAILED=()

# ============== COLORS ==============
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
NC='\033[0m'

# ============== PROGRESS FUNCTIONS ==============
update_progress() {
    CURRENT_STEP=$((CURRENT_STEP + 1))
    PERCENT=$((CURRENT_STEP * 100 / TOTAL_STEPS))

    FILLED=$((PERCENT / 5))
    EMPTY=$((20 - FILLED))

    BAR="${GREEN}"
    for ((i=0; i<FILLED; i++)); do BAR+="*"; done
    BAR+="${GRAY}"
    for ((i=0; i<EMPTY; i++)); do BAR+="-"; done
    BAR+="${NC}"

    echo ""
    echo -e "${WHITE}------------------------------------------------------------${NC}"
    echo -e "${CYAN}  OVERALL PROGRESS: ${WHITE}Step ${CURRENT_STEP}/${TOTAL_STEPS}${NC} ${BAR} ${WHITE}${PERCENT}%${NC}"
    echo -e "${WHITE}------------------------------------------------------------${NC}"
    echo ""
}

spinner() {
    local pid=$1
    local message=$2
    local spin='-\|/'
    local i=0

    while kill -0 $pid 2>/dev/null; do
        i=$(( (i+1) % 4 ))
        printf "\r  [*] ${message} ${CYAN}${spin:$i:1}${NC}  "
        sleep 0.1
    done

    wait $pid
    local exit_code=$?

    if [ $exit_code -eq 0 ]; then
        printf "\r  [+] ${message}                    \n"
    else
        printf "\r  [-] ${message} ${RED}(failed)${NC}     \n"
    fi

    return $exit_code
}

# Runs a command in the background with a spinner, logging its output.
# On failure the end of the log is shown and the step is remembered.
run_logged() {
    local message=$1
    shift
    echo "===== $* =====" >> "$LOG"
    ("$@" >> "$LOG" 2>&1) &
    if ! spinner $! "$message"; then
        FAILED+=("$message")
        tail -n 5 "$LOG" | sed 's/^/        /'
    fi
}

install_pkg() {
    local pkg=$1
    local name=${2:-$pkg}
    DEBIAN_FRONTEND=noninteractive run_logged "Installing ${name}..." \
        apt-get install -y -o Dpkg::Options::="--force-confold" "$pkg"
}

# ============== BANNER ==============
show_banner() {
    clear
    echo -e "${CYAN}"
    cat << 'BANNER'
    -------------------------------------------

       Termux Linux Setup - Mate 20 Pro edition

    -------------------------------------------
BANNER
    echo -e "${NC}"
    echo ""
}

# ============== DEVICE & USER SELECTION ==============
setup_environment() {
    if [ -z "$TERMUX_VERSION" ]; then
        echo -e "${RED}[-] This script must be run inside the Termux app.${NC}"
        exit 1
    fi

    DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null || echo "Unknown")
    DEVICE_BRAND=$(getprop ro.product.brand 2>/dev/null || echo "Unknown")
    ANDROID_VERSION=$(getprop ro.build.version.release 2>/dev/null || echo "Unknown")

    echo -e "  [*] Device: ${WHITE}${DEVICE_BRAND} ${DEVICE_MODEL}${NC}"
    echo -e "  [*] Android: ${WHITE}${ANDROID_VERSION}${NC}"
    echo -e "  [*] Graphics: ${WHITE}Software rendering (llvmpipe)${NC}"
    echo ""

    echo -e "${CYAN}Please choose your Desktop Environment:${NC}"
    echo -e "  ${WHITE}1) XFCE4${NC}       (Recommended - Fast, Customizable, macOS style dock)"
    echo -e "  ${WHITE}2) LXQt${NC}        (Ultra lightweight)"
    echo -e "  ${WHITE}3) MATE${NC}        (Classic UI, moderately heavy)"
    echo ""
    while true; do
        read -p "Enter number (1-3) [default: 1]: " DE_INPUT
        DE_INPUT=${DE_INPUT:-1}
        if [[ "$DE_INPUT" =~ ^[1-3]$ ]]; then
            DE_CHOICE="$DE_INPUT"
            break
        else
            echo "Invalid input. Please enter 1, 2, or 3."
        fi
    done

    # "package|display name" pairs for the chosen desktop
    case $DE_CHOICE in
        1)
            DE_NAME="XFCE4"
            DE_PKGS=("xfce4|XFCE4 Desktop" "xfce4-terminal|XFCE4 Terminal"
                     "xfce4-whiskermenu-plugin|Whisker Menu" "plank-reloaded|Plank Dock"
                     "thunar|Thunar File Manager" "mousepad|Mousepad Editor")
            ;;
        2)
            DE_NAME="LXQt"
            DE_PKGS=("lxqt|LXQt Desktop" "qterminal|QTerminal"
                     "pcmanfm-qt|PCManFM-Qt" "featherpad|FeatherPad")
            ;;
        3)
            DE_NAME="MATE"
            DE_PKGS=("mate|MATE Desktop" "mate-tweak|MATE Tweak"
                     "plank-reloaded|Plank Dock" "mate-terminal|MATE Terminal")
            ;;
    esac

    echo ""
    echo -e "${CYAN}Windows app support (Wine/Hangover) is large and slow without GPU acceleration.${NC}"
    read -p "Install it? (y/N): " WINE_INPUT
    if [[ "$WINE_INPUT" =~ ^[Yy]$ ]]; then
        INSTALL_WINE="y"
        TOTAL_STEPS=$((TOTAL_STEPS + 1))
    fi

    echo -e "\n${GREEN}[+] Selected: ${DE_NAME}.${NC}"
    echo -e "${GRAY}    Full install log: ${LOG}${NC}"
    : > "$LOG"
    sleep 2
}

# ============== STEP 1: UPDATE SYSTEM ==============
step_update() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Updating system packages...${NC}"
    echo ""
    DEBIAN_FRONTEND=noninteractive run_logged "Updating package lists..." apt-get update -y
    DEBIAN_FRONTEND=noninteractive run_logged "Upgrading installed packages..." \
        apt-get upgrade -y -q -o Dpkg::Options::="--force-confold"
}

# ============== STEP 2: INSTALL REPOSITORIES ==============
step_repos() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Adding package repositories...${NC}"
    echo ""
    install_pkg "x11-repo" "X11 Repository"
    DEBIAN_FRONTEND=noninteractive run_logged "Updating package lists..." apt-get update -y

    # Only pull in the community TUR repository if the official ones lack something.
    local wanted=("firefox" "vlc")
    local entry pkg missing=""
    for entry in "${DE_PKGS[@]}"; do wanted+=("${entry%%|*}"); done
    if [ "$INSTALL_WINE" == "y" ]; then wanted+=("hangover-wine" "hangover-wowbox64"); fi
    for pkg in "${wanted[@]}"; do
        apt-cache show "$pkg" > /dev/null 2>&1 || missing+=" $pkg"
    done

    if [ -n "$missing" ]; then
        echo -e "  [*] Not in the official repositories:${WHITE}${missing}${NC}"
        install_pkg "tur-repo" "TUR Repository (community)"
        DEBIAN_FRONTEND=noninteractive run_logged "Updating package lists..." apt-get update -y
    else
        echo -e "  [+] Everything is in the official repositories, TUR not needed"
    fi
}

# ============== STEP 3: INSTALL TERMUX-X11 ==============
step_x11() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Termux-X11...${NC}"
    echo ""
    install_pkg "termux-x11-nightly" "Termux-X11 Display Server"
    install_pkg "xorg-xrandr" "XRandR (Display Settings)"
}

# ============== STEP 4: INSTALL DESKTOP ==============
step_desktop() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing ${DE_NAME} Desktop...${NC}"
    echo ""
    local entry
    for entry in "${DE_PKGS[@]}"; do
        install_pkg "${entry%%|*}" "${entry##*|}"
    done
}

# ============== STEP 5: INSTALL GRAPHICS ==============
step_graphics() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Software Rendering...${NC}"
    echo ""
    install_pkg "mesa" "Mesa (llvmpipe)"
    install_pkg "mesa-demos" "Mesa Demos (glxinfo, glxgears)"
}

# ============== STEP 6: INSTALL AUDIO ==============
step_audio() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Audio...${NC}"
    echo ""
    install_pkg "pulseaudio" "PulseAudio Server"
}

# ============== STEP 7: INSTALL APPS ==============
step_apps() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Media & Dev Apps...${NC}"
    echo ""
    install_pkg "firefox" "Firefox Browser"
    install_pkg "vlc" "VLC Media Player"
    install_pkg "git" "Git Version Control"
    install_pkg "wget" "Wget Downloader"
    install_pkg "curl" "cURL"
    install_pkg "python" "Python 3"
}

# ============== OPTIONAL STEP: INSTALL WINE ==============
step_wine() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Windows Support (Wine/Box64)...${NC}"
    echo ""
    run_logged "Removing old Wine versions..." pkg remove wine-stable -y

    install_pkg "hangover-wine" "Wine Compatibility Layer"
    install_pkg "hangover-wowbox64" "Box64 Wrapper"

    ln -sf "$PREFIX/opt/hangover-wine/bin/wine" "$PREFIX/bin/wine"
    ln -sf "$PREFIX/opt/hangover-wine/bin/winecfg" "$PREFIX/bin/winecfg"
}

# ============== STEP 8: CREATE LAUNCHERS ==============
step_launchers() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Configuring Startup Scripts...${NC}"
    echo ""

    mkdir -p ~/.config

    # Rendering & Environment Config (Mali-G76: no Zink, use the CPU rasterizer)
    cat > ~/.config/linux-env.sh << 'EOF'
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:${XDG_CONFIG_DIRS}
EOF

    # Create Plank autostart if XFCE or MATE
    if [ "$DE_CHOICE" == "1" ] || [ "$DE_CHOICE" == "3" ]; then
        mkdir -p ~/.config/autostart
        cat > ~/.config/autostart/plank.desktop << 'PLANKEOF'
[Desktop Entry]
Type=Application
Exec=plank
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
Name=Plank
PLANKEOF
    else
        rm -f ~/.config/autostart/plank.desktop 2>/dev/null
    fi

    # Determine execution commands and kill commands based on DE
    case $DE_CHOICE in
        1)
            EXEC_CMD="exec startxfce4"
            KILL_CMD="pkill -9 xfce4-session; pkill -9 plank"
            ;;
        2)
            EXEC_CMD="exec startlxqt"
            KILL_CMD="pkill -9 lxqt-session"
            ;;
        3)
            EXEC_CMD="exec mate-session"
            KILL_CMD="pkill -9 mate-session; pkill -9 plank"
            ;;
    esac

    # Main Launcher
    cat > ~/start-linux.sh << LAUNCHEREOF
#!/data/data/com.termux/files/usr/bin/bash
echo ""
echo "[*] Starting ${DE_NAME}..."
echo ""
source ~/.config/linux-env.sh 2>/dev/null

# Keep EMUI from suspending Termux while the desktop runs
termux-wake-lock 2>/dev/null

echo "[*] Cleaning up old sessions..."
pkill -9 -f "termux.x11" 2>/dev/null
${KILL_CMD} 2>/dev/null
pkill -9 -f "dbus" 2>/dev/null

unset PULSE_SERVER
pulseaudio --kill 2>/dev/null
sleep 0.5
echo "[*] Starting audio server..."
pulseaudio --start --exit-idle-time=-1
sleep 1
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null
export PULSE_SERVER=127.0.0.1

echo "[*] Starting X11 server..."
termux-x11 :0 -ac &
sleep 3
export DISPLAY=:0

echo "-----------------------------------------------"
echo "  [*] Open Termux-X11 app to view desktop!"
echo "-----------------------------------------------"
echo ""
${EXEC_CMD}
LAUNCHEREOF
    chmod +x ~/start-linux.sh
    echo -e "  [+] Created ~/start-linux.sh"

    # Stopper
    cat > ~/stop-linux.sh << STOPEOF
#!/data/data/com.termux/files/usr/bin/bash
echo "Stopping ${DE_NAME}..."
pkill -9 -f "termux.x11" 2>/dev/null
pkill -9 -f "pulseaudio" 2>/dev/null
${KILL_CMD} 2>/dev/null
pkill -9 -f "dbus" 2>/dev/null
termux-wake-unlock 2>/dev/null
echo "Desktop stopped."
STOPEOF
    chmod +x ~/stop-linux.sh
    echo -e "  [+] Created ~/stop-linux.sh"
}

# ============== STEP 9: CREATE SHORTCUTS ==============
step_shortcuts() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Creating Desktop Shortcuts...${NC}"
    echo ""
    mkdir -p ~/Desktop

    # App shortcuts
    cat > ~/Desktop/Firefox.desktop << 'EOF'
[Desktop Entry]
Name=Firefox
Exec=firefox
Icon=firefox
Type=Application
EOF

    cat > ~/Desktop/VLC.desktop << 'EOF'
[Desktop Entry]
Name=VLC Media Player
Exec=vlc
Icon=vlc
Type=Application
EOF

    if [ "$INSTALL_WINE" == "y" ]; then
        cat > ~/Desktop/Wine_Config.desktop << 'EOF'
[Desktop Entry]
Name=Wine Config (Windows)
Exec=wine winecfg
Icon=wine
Type=Application
EOF
    fi

    # Dynamic terminal shortcut
    local term_cmd="xfce4-terminal"
    local term_icon="utilities-terminal"
    if [ "$DE_CHOICE" == "2" ]; then term_cmd="qterminal"; fi
    if [ "$DE_CHOICE" == "3" ]; then term_cmd="mate-terminal"; fi

    cat > ~/Desktop/Terminal.desktop << EOF
[Desktop Entry]
Name=Terminal
Exec=${term_cmd}
Icon=${term_icon}
Type=Application
EOF

    chmod +x ~/Desktop/*.desktop 2>/dev/null
    echo -e "  [+] Added Firefox, VLC, and Terminal shortcuts."
}

# ============== COMPLETION ==============
show_completion() {
    echo ""
    if [ ${#FAILED[@]} -eq 0 ]; then
        echo -e "${GREEN}"
        cat << 'COMPLETE'
    ---------------------------------------------------------------
             [*]  INSTALLATION COMPLETE!  [*]
    ---------------------------------------------------------------
COMPLETE
        echo -e "${NC}"
    else
        echo -e "${YELLOW}[!] Finished with ${#FAILED[@]} failed step(s):${NC}"
        local item
        for item in "${FAILED[@]}"; do echo -e "    ${RED}-${NC} ${item}"; done
        echo -e "${YELLOW}    Details: ${LOG}${NC}"
        echo ""
    fi

    echo -e "${WHITE}[*] Your ${DE_NAME} environment is ready.${NC}"
    echo -e "${CYAN}[*] Installed Software:${NC}"
    echo "    - Firefox Browser & VLC Media Player"
    echo "    - Python 3, Git"
    if [ "$INSTALL_WINE" == "y" ]; then
        echo "    - Wine & Hangover (Windows PC App compatibility)"
    fi
    echo "    - Software rendering (check with: glxinfo -B)"
    echo ""
    echo -e "${YELLOW}------------------------------------------------------------${NC}"
    echo -e "${WHITE}[*] TO START THE DESKTOP:${NC}  ${GREEN}./start-linux.sh${NC}"
    echo -e "${WHITE}[*] TO STOP THE DESKTOP:${NC}   ${GREEN}./stop-linux.sh${NC}"
    echo -e "${YELLOW}------------------------------------------------------------${NC}"
    echo ""
}

# ============== MAIN ==============
main() {
    show_banner
    setup_environment

    step_update
    step_repos
    step_x11
    step_desktop
    step_graphics
    step_audio
    step_apps
    if [ "$INSTALL_WINE" == "y" ]; then step_wine; fi
    step_launchers
    step_shortcuts

    show_completion
}

main
