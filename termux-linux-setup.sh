#!/data/data/com.termux/files/usr/bin/bash
#######################################################
#  Termux Linux Setup Script
#
#  Features:
#  - XFCE4 / LXQt / MATE / KDE Desktop
#  - Smart GPU acceleration (Turnip/Zink)
#  - Termux-X11 display + optional VNC
#  - Modern dark XFCE theme + auto wallpaper
#  - Proot Linux container (Ubuntu/Debian/Kali)
#  - Proot App Bridge (apt installs appear in XFCE menu)
#  - Python & Web Dev environment
#######################################################

# ============== CONFIGURATION ==============
TOTAL_STEPS=12
CURRENT_STEP=0
DE_CHOICE="1"
DE_NAME="XFCE4"
VNC_ENABLED=false
SETUP_USERNAME="user"

# Wallpaper URL — Ubuntu 4K wallpaper (set by user)
WALLPAPER_URL="https://wallpapercave.com/download/ubuntu-4k-wallpapers-wp8303186"

# ============== COLORS ==============
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
NC='\033[0m'
BOLD='\033[1m'

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
    echo -e "${CYAN}  PROGRESS: ${WHITE}Step ${CURRENT_STEP}/${TOTAL_STEPS}${NC} ${BAR} ${WHITE}${PERCENT}%${NC}"
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

install_pkg() {
    local pkg=$1
    local name=${2:-$pkg}
    (DEBIAN_FRONTEND=noninteractive apt-get install -y \
        -o Dpkg::Options::="--force-confold" $pkg > /dev/null 2>&1) &
    spinner $! "Installing ${name}..."
}

# ============== BANNER ==============
show_banner() {
    clear
    echo -e "${CYAN}"
    cat << 'BANNER'
    ╔══════════════════════════════════════════╗
    ║                                          ║
    ║       Termux Linux Setup Script          ║
    ║       X11 + Proot + Modern XFCE          ║
    ║                                          ║
    ╚══════════════════════════════════════════╝
BANNER
    echo -e "${NC}"
    echo ""
}

# ============== DEVICE & DE SELECTION ==============
setup_environment() {
    echo -e "${PURPLE}[*] Detecting your device...${NC}"
    echo ""

    DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null || echo "Unknown")
    DEVICE_BRAND=$(getprop ro.product.brand 2>/dev/null || echo "Unknown")
    ANDROID_VERSION=$(getprop ro.build.version.release 2>/dev/null || echo "Unknown")
    CPU_ABI=$(getprop ro.product.cpu.abi 2>/dev/null || echo "arm64-v8a")
    GPU_VENDOR=$(getprop ro.hardware.egl 2>/dev/null || echo "")

    echo -e "  [*] Device : ${WHITE}${DEVICE_BRAND} ${DEVICE_MODEL}${NC}"
    echo -e "  [*] Android: ${WHITE}${ANDROID_VERSION}${NC}"

    if [[ "$GPU_VENDOR" == *"adreno"* ]] || \
       [[ "$DEVICE_BRAND" =~ [Ss]amsung|[Oo]ne[Pp]lus|[Xx]iaomi|[Rr]edmi|[Pp]oco|[Mm]oto|motorola ]]; then
        GPU_DRIVER="freedreno"
        echo -e "  [*] GPU    : ${WHITE}Adreno — Hardware Acceleration Enabled${NC}"
    else
        GPU_DRIVER="zink_native"
        echo -e "  [*] GPU    : ${WHITE}Non-Adreno — Zink/LLVMpipe fallback${NC}"
        echo -e "${YELLOW}      [!] Recommend XFCE or LXQt for best performance.${NC}"
    fi
    echo ""

    echo -e "${CYAN}Choose your Desktop Environment:${NC}"
    echo -e "  ${WHITE}1) XFCE4${NC}      — Fast, customizable (Recommended)"
    echo -e "  ${WHITE}2) LXQt${NC}       — Ultra lightweight"
    echo -e "  ${WHITE}3) MATE${NC}       — Classic, moderate weight"
    echo -e "  ${WHITE}4) KDE Plasma${NC} — Heavy, modern (needs strong GPU/RAM)"
    echo ""
    while true; do
        read -p "Enter number (1-4) [default: 1]: " DE_INPUT
        DE_INPUT=${DE_INPUT:-1}
        if [[ "$DE_INPUT" =~ ^[1-4]$ ]]; then
            DE_CHOICE="$DE_INPUT"; break
        else
            echo "Please enter 1, 2, 3, or 4."
        fi
    done

    case $DE_CHOICE in
        1) DE_NAME="XFCE4";;
        2) DE_NAME="LXQt";;
        3) DE_NAME="MATE";;
        4) DE_NAME="KDE Plasma";;
    esac

    echo -e "\n${GREEN}[+] Selected: ${DE_NAME}${NC}"

    # ---- Username ----
    SETUP_USERNAME="root"
    echo -e "  ${GREEN}[+] Proot User set to: ${SETUP_USERNAME} (Default)${NC}"
    sleep 1
}

# ============== STEP 1: UPDATE ==============
step_update() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Updating system packages...${NC}"
    echo ""
    (DEBIAN_FRONTEND=noninteractive apt-get update -y > /dev/null 2>&1) &
    spinner $! "Updating package lists..."
    (DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -q \
        -o Dpkg::Options::="--force-confold" > /dev/null 2>&1) &
    spinner $! "Upgrading installed packages..."
}

# ============== STEP 2: REPOSITORIES ==============
step_repos() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Adding repositories...${NC}"
    echo ""
    install_pkg "x11-repo" "X11 Repository"
    install_pkg "tur-repo" "TUR Repository"
}

# ============== STEP 3: TERMUX-X11 ==============
step_x11() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Termux-X11...${NC}"
    echo ""
    install_pkg "termux-x11-nightly" "Termux-X11 Display Server"
    install_pkg "xorg-xrandr" "XRandR"
}

# ============== STEP 4: DESKTOP ==============
step_desktop() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing ${DE_NAME}...${NC}"
    echo ""

    if [ "$DE_CHOICE" == "1" ]; then
        install_pkg "xfce4" "XFCE4 Desktop"
        install_pkg "xfce4-terminal" "XFCE4 Terminal"
        install_pkg "xfce4-whiskermenu-plugin" "Whisker Menu"
        install_pkg "xfce4-notifyd" "XFCE Notifications"
        install_pkg "thunar" "Thunar File Manager"
        install_pkg "mousepad" "Mousepad Editor"
    elif [ "$DE_CHOICE" == "2" ]; then
        install_pkg "lxqt" "LXQt Desktop"
        install_pkg "qterminal" "QTerminal"
        install_pkg "pcmanfm-qt" "PCManFM-Qt"
        install_pkg "featherpad" "FeatherPad"
    elif [ "$DE_CHOICE" == "3" ]; then
        install_pkg "mate" "MATE Desktop"
        install_pkg "mate-tweak" "MATE Tweak"
        install_pkg "mate-terminal" "MATE Terminal"
    elif [ "$DE_CHOICE" == "4" ]; then
        install_pkg "plasma-desktop" "KDE Plasma"
        install_pkg "konsole" "Konsole"
        install_pkg "dolphin" "Dolphin"
    fi
}

# ============== STEP 5: GPU DRIVERS ==============
step_gpu() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing GPU Acceleration...${NC}"
    echo ""
    install_pkg "mesa-zink" "Mesa Zink Core"
    if [ "$GPU_DRIVER" == "freedreno" ]; then
        install_pkg "mesa-vulkan-icd-freedreno" "Turnip Adreno Driver"
    fi
    install_pkg "vulkan-loader-android" "Vulkan Loader"
}

# ============== STEP 6: AUDIO ==============
step_audio() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Audio...${NC}"
    echo ""
    install_pkg "pulseaudio" "PulseAudio"
}

# ============== STEP 7: APPS ==============
step_apps() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Apps...${NC}"
    echo ""
    install_pkg "firefox" "Firefox Browser"
    install_pkg "git" "Git"
    install_pkg "wget" "Wget"
    install_pkg "curl" "cURL"
    install_pkg "imagemagick" "ImageMagick (wallpaper)"
}

# ============== STEP 8: PYTHON ==============
step_python() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Installing Python...${NC}"
    echo ""
    install_pkg "python" "Python 3"
    install_pkg "python-pip" "pip"
    echo -e "  [+] Python 3 installed"
}

# ============== STEP 9: PROOT ==============
step_proot() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Setting up Proot Container...${NC}"
    echo ""

    install_pkg "proot-distro" "Proot-Distro Manager"
    install_pkg "proot" "PRoot"

    echo ""
    echo -e "${CYAN}Choose a Linux distro for Proot:${NC}"
    echo -e "  ${WHITE}1) Ubuntu 22.04 LTS${NC}  (Recommended)"
    echo -e "  ${WHITE}2) Debian 12${NC}          (Minimal)"
    echo -e "  ${WHITE}3) Kali Linux${NC}         (Security/Pentesting)"
    echo ""
    while true; do
        read -p "Enter number (1-3) [default: 1]: " PROOT_INPUT
        PROOT_INPUT=${PROOT_INPUT:-1}
        if [[ "$PROOT_INPUT" =~ ^[1-3]$ ]]; then break; fi
        echo "Please enter 1, 2, or 3."
    done

    case $PROOT_INPUT in
        1) PROOT_DISTRO="ubuntu";         PROOT_LABEL="Ubuntu 22.04";;
        2) PROOT_DISTRO="debian";         PROOT_LABEL="Debian 12";;
        3) PROOT_DISTRO="kali-nethunter"; PROOT_LABEL="Kali Linux";;
    esac

    echo -e "\n${GREEN}[+] Installing ${PROOT_LABEL}...${NC}"
    (proot-distro install "$PROOT_DISTRO" > /dev/null 2>&1) &
    spinner $! "Downloading ${PROOT_LABEL} rootfs (may take a while)..."

    echo -e "  [*] Bootstrapping ${PROOT_LABEL}..."
    proot-distro login "$PROOT_DISTRO" -- bash -c "
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -y -q > /dev/null 2>&1
        apt-get install -y -q --no-install-recommends \
            mesa-utils vulkan-tools \
            libgl1-mesa-glx libvulkan1 libgles2 \
            xfce4 xfce4-terminal dbus-x11 \
            sudo curl wget git htop nano > /dev/null 2>&1
    " 2>/dev/null || true

    # ---- Global --no-sandbox wrapper for Electron/Chromium apps in proot ----
    proot-distro login "$PROOT_DISTRO" -- bash -c "
        cat > /usr/local/bin/proot-sandbox-fix << 'SANDBOXFIX'
#!/bin/bash
# Proot Sandbox Fix — auto-append --no-sandbox for Electron/Chromium apps
# Proot lacks user namespaces, so Chrome/Electron sandbox fails without this.
REAL_BIN=\$(which \"\$1\" 2>/dev/null | head -1)
[ -z \"\$REAL_BIN\" ] && REAL_BIN=\"\$(command -v \"\$1\" 2>/dev/null)\"
if [ -z \"\$REAL_BIN\" ]; then
    echo \"[!] '\$1' not found. Install it first.\"
    exit 1
fi
shift
export CHROME_DEVEL_SANDBOX=\"\"
exec \"\$REAL_BIN\" --no-sandbox --disable-gpu-sandbox \"\$@\"
SANDBOXFIX
        chmod +x /usr/local/bin/proot-sandbox-fix

        # Known Electron/Chromium apps: create convenience wrappers
        for app in code code-insiders chromium chromium-browser google-chrome \
                   brave-browser microsoft-edge discord slack teams skype \
                   obsidian notion atom github-desktop figma-linux; do
            [ -f \"/usr/local/bin/\$app\" ] && continue
            [ ! \"\$(command -v \$app 2>/dev/null)\" ] && continue
            ln -sf /usr/local/bin/proot-sandbox-fix \"/usr/local/bin/\$app\" 2>/dev/null
        done

        # Also create a global electron-flags config
        mkdir -p /etc/proot-sandbox
        cat > /etc/proot-sandbox/env.sh << 'ENVEOF'
# Global Electron/Chromium sandbox flags for proot
export ELECTRON_NO_SANDBOX=1
export CHROME_DEVEL_SANDBOX=''
export ELECTRON_DISABLE_SANDBOX=1
ENVEOF
    " 2>/dev/null || true
    echo -e "  [+] Proot sandbox fix applied (Electron/Chromium --no-sandbox wrappers)"

    # ---- Create named user with working sudo ----
    echo -e "  [*] Creating proot user: ${SETUP_USERNAME} (with sudo)..."
    proot-distro login "$PROOT_DISTRO" -- bash -c "
        # Create user if not exists
        id '$SETUP_USERNAME' > /dev/null 2>&1 || \
            useradd -m -s /bin/bash '$SETUP_USERNAME'

        # Add to sudo group
        usermod -aG sudo '$SETUP_USERNAME' 2>/dev/null || true

        # Drop-in sudoers file (cleaner than editing /etc/sudoers directly)
        # Defaults !requiretty  — allows sudo without a real terminal (proot)
        # NOPASSWD            — no password prompt
        mkdir -p /etc/sudoers.d
        echo 'Defaults !requiretty' > /etc/sudoers.d/proot-compat
        echo '$SETUP_USERNAME ALL=(ALL) NOPASSWD: ALL' >> /etc/sudoers.d/proot-compat
        chmod 0440 /etc/sudoers.d/proot-compat

        # Ensure sudo binary has correct permissions (SETUID)
        chmod u+s /usr/bin/sudo 2>/dev/null || true

        # Nice coloured shell prompt
        echo 'export PS1="\[\033[01;32m\]${SETUP_USERNAME}@linux\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "' \
            >> /home/'$SETUP_USERNAME'/.bashrc
        # Useful aliases
        echo 'alias ll="ls -la"' >> /home/'$SETUP_USERNAME'/.bashrc
        echo 'alias update="sudo apt update && sudo apt upgrade -y"' >> /home/'$SETUP_USERNAME'/.bashrc
    " 2>/dev/null || true
    echo -e "  [+] Proot user '${SETUP_USERNAME}' created with passwordless sudo"

    PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
    TERMUX_VK_ICD="/data/data/com.termux/files/usr/share/vulkan/icd.d"
    TERMUX_LIB="/data/data/com.termux/files/usr/lib"

    # ---- start-proot.sh ----
    cat > ~/start-proot.sh << PROOTEOF
#!/data/data/com.termux/files/usr/bin/bash
PROOT_DISTRO="$PROOT_DISTRO"
PROOT_LABEL="$PROOT_LABEL"
TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"

echo ""
echo "============================================="
echo "  [*] Starting \$PROOT_LABEL"
echo "============================================="
echo ""

BINDS=""
[ -d "\$TERMUX_TMP/.X11-unix" ] && BINDS="\$BINDS --bind \$TERMUX_TMP/.X11-unix:/tmp/.X11-unix"
[ -d "/dev/dri" ]               && BINDS="\$BINDS --bind /dev/dri:/dev/dri"
[ -e "/dev/kgsl-3d0" ]          && BINDS="\$BINDS --bind /dev/kgsl-3d0:/dev/kgsl-3d0"
[ -d "${TERMUX_VK_ICD}" ]       && BINDS="\$BINDS --bind ${TERMUX_VK_ICD}:/usr/share/vulkan/icd.d.termux"
[ -f "${TERMUX_LIB}/libvulkan.so" ] && \
    BINDS="\$BINDS --bind ${TERMUX_LIB}/libvulkan.so:/usr/lib/aarch64-linux-gnu/libvulkan_termux.so"

_RC=\$(mktemp /data/data/com.termux/files/usr/tmp/proot_rc.XXXX)
cat > "\$_RC" << 'RCEOF'
export DISPLAY=:0
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export ZINK_DESCRIPTORS=lazy
export MESA_VK_WSI_PRESENT_MODE=immediate
[ -f /usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json ] && \
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json
export XDG_DATA_DIRS=/usr/share:/usr/local/share:\${XDG_DATA_DIRS}
# Sandbox fix for Electron/Chromium apps in proot
export CHROME_DEVEL_SANDBOX=
export ELECTRON_NO_SANDBOX=1
export ELECTRON_DISABLE_SANDBOX=1
[ -f /etc/proot-sandbox/env.sh ] && source /etc/proot-sandbox/env.sh

export PS1="\[\033[01;32m\]$SETUP_USERNAME@linux\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "
echo ""
echo " User: $SETUP_USERNAME | GPU: GALLIUM=\${GALLIUM_DRIVER}"
echo " Tip: Use /usr/local/bin/<app> for sandbox-free Electron apps"
echo " Type 'exit' to leave proot."
echo ""
RCEOF

proot-distro login "\$PROOT_DISTRO" \$BINDS --user root -- bash --rcfile "\$_RC"
rm -f "\$_RC"
PROOTEOF
    chmod +x ~/start-proot.sh
    echo -e "  [+] Created ~/start-proot.sh"

    # ---- proot-menu-sync.sh (v3 — embedded) ----
    cat > ~/proot-menu-sync.sh << 'SYNCEOF'
#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  Proot App Menu Bridge v3
#  Syncs proot .desktop files into native XFCE menu.
#  Fixes: $TMPDIR log path, runtime X11 bind, dbus-run-session,
#         Blender libvulkan auto-detect, LibreOffice --norestore
# ============================================================

PROOT_DISTRO="${1:-ubuntu}"
PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
PROOT_ROOTFS="/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"
PROOT_APPS="$PROOT_ROOTFS/usr/share/applications"
BRIDGE_DIR="$HOME/.local/share/applications/proot-bridge"
WRAPPER_DIR="$HOME/.local/share/proot-wrappers"
TERMUX_TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"

if [ ! -f "$PROOT_BIN" ]; then
    echo "[!] proot-distro not found. pkg install proot-distro"
    exit 1
fi
if [ ! -d "$PROOT_ROOTFS" ]; then
    echo "[!] Proot distro '$PROOT_DISTRO' not installed."
    exit 1
fi
if [ ! -d "$PROOT_APPS" ]; then
    echo "[!] No proot apps yet. proot-distro login $PROOT_DISTRO -- apt install <pkg>"
    exit 0
fi

mkdir -p "$BRIDGE_DIR" "$WRAPPER_DIR"

HAS_GPU="software"
[ -d "/dev/dri" ] && HAS_GPU="zink"

# Ensure dbus-x11 in proot
if ! "$PROOT_BIN" login "$PROOT_DISTRO" -- which dbus-run-session > /dev/null 2>&1; then
    echo "[*] Installing dbus-x11 in proot..."
    "$PROOT_BIN" login "$PROOT_DISTRO" -- apt-get install -y -q dbus-x11 > /dev/null 2>&1
fi

SYNCED=0
REMOVED=0

for bridge_file in "$BRIDGE_DIR"/proot-*.desktop; do
    [ -f "$bridge_file" ] || continue
    original_name=$(basename "$bridge_file" | sed 's/^proot-//')
    if [ ! -f "$PROOT_APPS/$original_name" ]; then
        rm -f "$bridge_file" "$WRAPPER_DIR/proot-${original_name%.desktop}.sh"
        REMOVED=$((REMOVED + 1))
    fi
done

for desktop_file in "$PROOT_APPS"/*.desktop; do
    [ -f "$desktop_file" ] || continue

    filename=$(basename "$desktop_file")
    appname="${filename%.desktop}"
    output="$BRIDGE_DIR/proot-$filename"
    wrapper="$WRAPPER_DIR/proot-${appname}.sh"

    grep -q "^NoDisplay=true" "$desktop_file" 2>/dev/null && continue
    grep -q "^Hidden=true"    "$desktop_file" 2>/dev/null && continue

    ORIGINAL_EXEC=$(grep "^Exec=" "$desktop_file" | head -1 | sed 's/^Exec=//')
    [ -z "$ORIGINAL_EXEC" ] && continue
    CLEAN_EXEC=$(echo "$ORIGINAL_EXEC" | sed 's/ %[a-zA-Z]//g; s/%[a-zA-Z]//g')

    APP_CMD="$CLEAN_EXEC"
    EXTRA_ENV=""

    # Auto-append --no-sandbox for Electron/Chromium apps (proot lacks user namespaces)
    if echo "$appname" | grep -qiE "code|vscode|code-insiders|chromium|chrome|brave|edge|discord|slack|teams|skype|obsidian|notion|atom|github-desktop|figma|electron|msedge|microsoft-edge"; then
        if ! echo "$APP_CMD" | grep -q -- "--no-sandbox"; then
            APP_CMD="$APP_CMD --no-sandbox --disable-gpu-sandbox"
        fi
        EXTRA_ENV="${EXTRA_ENV} export CHROME_DEVEL_SANDBOX=; export ELECTRON_NO_SANDBOX=1; export ELECTRON_DISABLE_SANDBOX=1;"
    fi

    echo "$appname" | grep -qi "libreoffice\|soffice" && \
        APP_CMD="$CLEAN_EXEC --norestore --nofirststartwizard"

    if echo "$appname" | grep -qi "blender"; then
        APP_CMD="$CLEAN_EXEC"
        if "$PROOT_BIN" login "$PROOT_DISTRO" -- \
                ldconfig -p 2>/dev/null | grep -q "libvulkan.so.1"; then
            EXTRA_ENV="export GALLIUM_DRIVER=zink; export MESA_GL_VERSION_OVERRIDE=4.6;"
            echo "  [+] Blender: Zink GPU mode"
        else
            EXTRA_ENV="export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; export MESA_GL_VERSION_OVERRIDE=4.5;"
            echo "  [!] Blender: Software mode (install libvulkan1 in proot for GPU)"
        fi
    fi

    cat > "$wrapper" << WRAPEOF
#!/data/data/com.termux/files/usr/bin/bash
PROOT_BIN="$PROOT_BIN"
PROOT_DISTRO="$PROOT_DISTRO"
TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
LOG="\$TERMUX_TMP/proot-${appname}.log"

BINDS=""
X11_DIR="\$TERMUX_TMP/.X11-unix"
[ -d "\$X11_DIR" ]     && BINDS="\$BINDS --bind \$X11_DIR:/tmp/.X11-unix"
[ -d "/dev/dri" ]      && BINDS="\$BINDS --bind /dev/dri:/dev/dri"
[ -e "/dev/kgsl-3d0" ] && BINDS="\$BINDS --bind /dev/kgsl-3d0:/dev/kgsl-3d0"

{
echo "[+] Launching $appname at \$(date)"
echo "    X11=\$X11_DIR  BINDS=\$BINDS"
\$PROOT_BIN login "\$PROOT_DISTRO" \$BINDS -- /bin/bash -c "
export DISPLAY=:0
export XDG_RUNTIME_DIR=/tmp
export MESA_NO_ERROR=1
$EXTRA_ENV
dbus-run-session $APP_CMD
"
EXIT_CODE=\$?
echo "Exit: \$EXIT_CODE at \$(date)"
} > "\$LOG" 2>&1

[ \$EXIT_CODE -ne 0 ] && \
    xfce4-terminal --title="$appname error" \
        -e "bash -c 'cat \$LOG; echo; read -p \"Press Enter\"'" &
WRAPEOF
    chmod +x "$wrapper"

    cp "$desktop_file" "$output"
    sed -i \
        -e "s|^Exec=.*|Exec=$wrapper|" \
        -e "s|^TryExec=.*|TryExec=$wrapper|" \
        -e '/^NoDisplay=/d' -e '/^Hidden=/d' \
        "$output"
    echo "NoDisplay=false" >> "$output"

    APP_NAME=$(grep "^Name=" "$output" | head -1 | sed 's/^Name=//')
    [[ "$APP_NAME" != \[P\]* ]] && sed -i "s|^Name=.*|Name=[P] $APP_NAME|" "$output"
    SYNCED=$((SYNCED + 1))
done

echo "[+] Bridge: $SYNCED synced, $REMOVED removed."
echo "    Logs: \$TERMUX_TMP/proot-<appname>.log"
echo "    Re-run after new installs: bash ~/proot-menu-sync.sh"

pgrep -x "xfce4-panel" > /dev/null 2>&1 && xfce4-panel --restart > /dev/null 2>&1 &
pgrep -x "xfdesktop"   > /dev/null 2>&1 && { sleep 1; xfdesktop --reload > /dev/null 2>&1 & }
SYNCEOF
    chmod +x ~/proot-menu-sync.sh
    echo -e "  [+] Created ~/proot-menu-sync.sh"

    # Run once during install
    bash ~/proot-menu-sync.sh "$PROOT_DISTRO" 2>/dev/null || true
}

# ============== STEP 10: LAUNCHERS ==============
step_launchers() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Creating Startup Scripts...${NC}"
    echo ""

    mkdir -p ~/.config ~/.vnc

    # GPU env config
    cat > ~/.config/linux-gpu.sh << EOF
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export MESA_VK_WSI_PRESENT_MODE=immediate
export ZINK_DESCRIPTORS=lazy
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:\${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:\${XDG_CONFIG_DIRS}
EOF

    if [ "$DE_CHOICE" == "4" ]; then
        echo "export KWIN_COMPOSE=O2ES" >> ~/.config/linux-gpu.sh
        mkdir -p ~/.config/plasma-workspace/env
        cat > ~/.config/plasma-workspace/env/xdg_fix.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:${XDG_CONFIG_DIRS}
EOF
        chmod +x ~/.config/plasma-workspace/env/xdg_fix.sh
    fi

    case $DE_CHOICE in
        1) EXEC_CMD="exec startxfce4"
           KILL_CMD="pkill -9 xfce4-session 2>/dev/null";;
        2) EXEC_CMD="exec startlxqt"
           KILL_CMD="pkill -9 lxqt-session 2>/dev/null";;
        3) EXEC_CMD="exec mate-session"
           KILL_CMD="pkill -9 mate-session 2>/dev/null";;
        4) EXEC_CMD="(sleep 5 && pkill -9 plasmashell && plasmashell) > /dev/null 2>&1 &
exec startplasma-x11"
           KILL_CMD="pkill -9 startplasma-x11 2>/dev/null; pkill -9 kwin_x11 2>/dev/null";;
    esac

    # ---- start-x11.sh ----
    cat > ~/start-x11.sh << LAUNCHEREOF
#!/data/data/com.termux/files/usr/bin/bash
echo ""
echo "=============================================="
echo "  [*] Starting ${DE_NAME} via Termux-X11..."
echo "=============================================="
echo ""
source ~/.config/linux-gpu.sh 2>/dev/null

# Override Android's u0_a281 with the custom username
# XFCE panel reads USER/LOGNAME for all user-facing displays
export USER="$SETUP_USERNAME"
export LOGNAME="$SETUP_USERNAME"
export HOSTNAME="android-linux"
export HOST="android-linux"

pkill -9 -f "termux.x11" 2>/dev/null
pkill -9 -f "Xvnc" 2>/dev/null
${KILL_CMD}
pkill -9 -f "dbus" 2>/dev/null

unset PULSE_SERVER
pulseaudio --kill 2>/dev/null
sleep 0.5
echo "[*] Starting audio..."
pulseaudio --start --exit-idle-time=-1
sleep 1
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null
export PULSE_SERVER=127.0.0.1

echo "[*] Starting Termux-X11 on :0..."
termux-x11 :0 -ac &
sleep 3
export DISPLAY=:0

# Sync proot apps into menu (background, non-blocking)
[ -f ~/proot-menu-sync.sh ] && bash ~/proot-menu-sync.sh > /dev/null 2>&1 &

echo "----------------------------------------------"
echo "  [*] Open the Termux-X11 app to see desktop"
echo "----------------------------------------------"
echo ""
${EXEC_CMD}
LAUNCHEREOF
    chmod +x ~/start-x11.sh
    echo -e "  [+] Created ~/start-x11.sh"

    # ---- stop-linux.sh ----
    cat > ~/stop-linux.sh << STOPEOF
#!/data/data/com.termux/files/usr/bin/bash
echo "Stopping all sessions..."
pkill -9 -f "termux.x11" 2>/dev/null
vncserver -kill :1 2>/dev/null
pkill -9 -f "Xvnc" 2>/dev/null
pkill -9 -f "pulseaudio" 2>/dev/null
${KILL_CMD}
pkill -9 -f "dbus" 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
echo "Done."
STOPEOF
    chmod +x ~/stop-linux.sh
    echo -e "  [+] Created ~/stop-linux.sh"
}

# ============== STEP 11: XFCE MODERN THEME ==============
step_theme_xfce() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Configuring Modern XFCE Theme...${NC}"
    echo ""

    mkdir -p ~/.config/xfce4/xfconf/xfce-perchannel-xml \
             ~/.config/autostart \
             ~/.local/share/themes

    # ---- GTK + Font settings (xsettings.xml) ----
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml << 'XSEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Adwaita-dark"/>
    <property name="IconThemeName" type="string" value="Adwaita"/>
  </property>
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="96"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="Hinting" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Sans 11"/>
    <property name="MonospaceFontName" type="string" value="Monospace 10"/>
    <property name="DecorationLayout" type="string" value="menu:minimize,maximize,close"/>
    <property name="MenuImages" type="bool" value="true"/>
    <property name="ButtonImages" type="bool" value="true"/>
  </property>
</channel>
XSEOF

    # ---- Window manager (xfwm4.xml) ----
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml << 'XWEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Default-xhdpi"/>
    <property name="title_font" type="string" value="Sans Bold 10"/>
    <property name="use_compositing" type="bool" value="true"/>
    <property name="frame_opacity" type="int" value="95"/>
    <property name="inactive_opacity" type="int" value="90"/>
    <property name="popup_opacity" type="int" value="95"/>
    <property name="show_frame_shadow" type="bool" value="true"/>
    <property name="show_popup_shadow" type="bool" value="true"/>
    <property name="shadow_opacity" type="int" value="50"/>
    <property name="button_layout" type="string" value="O|SHMC"/>
    <property name="snap_to_windows" type="bool" value="true"/>
    <property name="snap_to_border" type="bool" value="true"/>
    <property name="tile_on_move" type="bool" value="true"/>
    <property name="wrap_workspaces" type="bool" value="false"/>
  </property>
</channel>
XWEOF

    # ---- Terminal dark theme (Dracula) ----
    mkdir -p ~/.config/xfce4
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml << 'TERMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-terminal" version="1.0">
  <property name="color-foreground" type="string" value="#f8f8f2"/>
  <property name="color-background" type="string" value="#282a36"/>
  <property name="color-cursor" type="string" value="#f8f8f2"/>
  <property name="color-selection" type="string" value="#44475a"/>
  <property name="color-palette" type="string" value="#21222c;#ff5555;#50fa7b;#f1fa8c;#bd93f9;#ff79c6;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff"/>
  <property name="font-name" type="string" value="Monospace 11"/>
  <property name="misc-use-padding" type="bool" value="true"/>
  <property name="misc-cursor-blinks" type="bool" value="true"/>
  <property name="misc-cursor-shape" type="uint" value="1"/>
  <property name="scrolling-bar" type="uint" value="0"/>
  <property name="tab-activity-color" type="string" value="#bd93f9"/>
  <property name="title-mode" type="uint" value="0"/>
</channel>
TERMEOF

    # ---- Keyboard shortcuts ----
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml << 'KBEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-keyboard-shortcuts" version="1.0">
  <property name="commands" type="empty">
    <property name="custom" type="empty">
      <property name="&lt;Super&gt;e" type="string" value="thunar"/>
      <property name="&lt;Super&gt;t" type="string" value="xfce4-terminal"/>
      <property name="&lt;Super&gt;r" type="string" value="xfce4-appfinder --collapsed"/>
      <property name="&lt;Alt&gt;F2" type="string" value="xfce4-appfinder --collapsed"/>
      <property name="Print" type="string" value="xfce4-screenshooter"/>
    </property>
  </property>
  <property name="xfwm4" type="empty">
    <property name="custom" type="empty">
      <property name="&lt;Alt&gt;F4" type="string" value="close_window_key"/>
      <property name="&lt;Alt&gt;F10" type="string" value="maximize_window_key"/>
      <property name="&lt;Super&gt;d" type="string" value="show_desktop_key"/>
      <property name="&lt;Super&gt;Left" type="string" value="tile_left_key"/>
      <property name="&lt;Super&gt;Right" type="string" value="tile_right_key"/>
      <property name="&lt;Super&gt;Up" type="string" value="maximize_window_key"/>
    </property>
  </property>
</channel>
KBEOF

    # ---- Desktop settings (icon layout, hide useless icons) ----
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml << 'DESKEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="desktop-icons" type="empty">
    <property name="file-icons" type="empty">
      <property name="show-filesystem" type="bool" value="false"/>
      <property name="show-home" type="bool" value="true"/>
      <property name="show-trash" type="bool" value="true"/>
      <property name="show-removable" type="bool" value="true"/>
    </property>
    <property name="icon-size" type="uint" value="48"/>
    <property name="tooltip-size" type="double" value="64"/>
  </property>
</channel>
DESKEOF

    # ---- First-run script (panel + wallpaper via xfconf-query) ----
    # Runs once when XFCE starts for the first time
    cat > ~/.config/xfce-first-run.sh << 'FREOF'
#!/data/data/com.termux/files/usr/bin/bash
# XFCE First Run: configure panel + wallpaper
WALLPAPER="$HOME/.config/linux-wallpaper.jpg"

sleep 4  # Wait for xfconfd + panel to be ready

# ---- Dark Adwaita theme ----
xfconf-query -c xsettings -p /Net/ThemeName -s "Adwaita-dark"
xfconf-query -c xfwm4 -p /general/theme -s "Default-xhdpi"

# ---- Panel: Move panel-1 to bottom, resize ----
# Position: p=8 = bottom-center
xfconf-query -c xfce4-panel -p /panels/panel-1/position -s "p=8;x=0;y=0" 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-1/size -t int -s 44 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-1/position-locked -s true 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-1/background-style -t int -s 1 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-1/background-rgba \
    -t double -s 0.12 -t double -s 0.12 -t double -s 0.18 -t double -s 0.90 2>/dev/null || true

# ---- Panel-2: reduce top panel size if it exists ----
xfconf-query -c xfce4-panel -p /panels/panel-2/size -t int -s 28 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-2/background-style -t int -s 1 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-2/background-rgba \
    -t double -s 0.10 -t double -s 0.10 -t double -s 0.14 -t double -s 0.95 2>/dev/null || true

# ---- Wallpaper ----
if [ -f "$WALLPAPER" ]; then
    for prop in $(xfconf-query -c xfce4-desktop -lv 2>/dev/null | \
                  grep "last-image" | awk '{print $1}'); do
        xfconf-query -c xfce4-desktop -p "$prop" -s "$WALLPAPER" 2>/dev/null
    done
    # Set image style: 5 = zoomed/scaled
    for prop in $(xfconf-query -c xfce4-desktop -lv 2>/dev/null | \
                  grep "image-style" | awk '{print $1}'); do
        xfconf-query -c xfce4-desktop -p "$prop" -t int -s 5 2>/dev/null
    done
    xfdesktop --reload 2>/dev/null &
fi

# ---- Compositing tuning ----
xfconf-query -c xfwm4 -p /general/use_compositing -s true 2>/dev/null || true
xfconf-query -c xfwm4 -p /general/frame_opacity -t int -s 95 2>/dev/null || true

# ---- Remove this autostart so it never runs again ----
rm -f "$HOME/.config/autostart/xfce-first-run.desktop"
FREOF
    chmod +x ~/.config/xfce-first-run.sh

    # Register as XFCE autostart (one-shot)
    cat > ~/.config/autostart/xfce-first-run.desktop << 'AREOF'
[Desktop Entry]
Type=Application
Name=XFCE First Run Setup
Exec=bash /root/.config/xfce-first-run.sh
Hidden=false
NoDisplay=true
X-GNOME-Autostart-enabled=true
AREOF

    # ---- Wallpaper: URL → gradient fallback → skip ----
    WALLPAPER_FILE="$HOME/.config/linux-wallpaper.jpg"
    WALLPAPER_OK=false

    if [ -n "$WALLPAPER_URL" ]; then
        echo -e "  [*] Downloading wallpaper..."
        # -L = follow redirects, --timeout = don't hang forever, -q = silent
        (wget -L -q --timeout=30 --tries=2 \
            -O "$WALLPAPER_FILE" "$WALLPAPER_URL" > /dev/null 2>&1) &
        spinner $! "Downloading wallpaper (timeout: 30s)..."

        # Validate: must exist AND be >10KB (not an error HTML page)
        if [ -f "$WALLPAPER_FILE" ] && \
           [ "$(wc -c < "$WALLPAPER_FILE" 2>/dev/null)" -gt 10240 ]; then
            echo -e "  [+] Wallpaper downloaded OK"
            WALLPAPER_OK=true
        else
            rm -f "$WALLPAPER_FILE"
            echo -e "  [!] Wallpaper URL failed or returned invalid data — trying gradient..."
        fi
    fi

    if [ "$WALLPAPER_OK" = false ]; then
        # Fallback: generate dark gradient with ImageMagick
        if command -v convert > /dev/null 2>&1; then
            (convert -size 1920x1080 \
                gradient:"#0f0c29"-"#302b63" \
                "$WALLPAPER_FILE" > /dev/null 2>&1) &
            spinner $! "Generating gradient wallpaper..."
            [ -f "$WALLPAPER_FILE" ] && WALLPAPER_OK=true && \
                echo -e "  [+] Gradient wallpaper generated"
        fi
    fi

    if [ "$WALLPAPER_OK" = false ]; then
        echo -e "  [!] Wallpaper skipped (URL failed + ImageMagick unavailable)"
        echo -e "      Desktop will use XFCE default background."
    fi

    echo -e "  [+] XFCE dark theme configured (Adwaita-dark + Dracula terminal)"
    echo -e "  [+] First-run script will configure panels on first launch"
}

# ============== STEP 12: SHORTCUTS ==============
step_shortcuts() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Creating Desktop Shortcuts...${NC}"
    echo ""
    mkdir -p ~/Desktop

    cat > ~/Desktop/Firefox.desktop << 'EOF'
[Desktop Entry]
Name=Firefox
Exec=firefox
Icon=firefox
Type=Application
EOF

    cat > ~/Desktop/Files.desktop << 'EOF'
[Desktop Entry]
Name=Files
Exec=thunar
Icon=folder
Type=Application
EOF

    local term_cmd="xfce4-terminal"
    [ "$DE_CHOICE" == "2" ] && term_cmd="qterminal"
    [ "$DE_CHOICE" == "3" ] && term_cmd="mate-terminal"
    [ "$DE_CHOICE" == "4" ] && term_cmd="konsole"

    cat > ~/Desktop/Terminal.desktop << EOF
[Desktop Entry]
Name=Terminal
Exec=${term_cmd}
Icon=utilities-terminal
Type=Application
EOF

    cat > ~/Desktop/Proot.desktop << EOF
[Desktop Entry]
Name=Linux Container
Comment=Open Proot Shell with GPU support
Exec=${term_cmd} -e "bash /root/start-proot.sh"
Icon=system-run
Type=Application
Terminal=false
EOF

    chmod +x ~/Desktop/*.desktop 2>/dev/null
    echo -e "  [+] Shortcuts: Firefox, Files, Terminal, Proot"
}

# ============== VNC (OPTIONAL — asked at end) ==============
step_vnc_optional() {
    echo ""
    echo -e "${YELLOW}============================================================${NC}"
    echo -e "${WHITE}  OPTIONAL: VNC Remote Desktop${NC}"
    echo -e "${YELLOW}============================================================${NC}"
    echo ""
    echo -e "  VNC lets you connect from another device (phone, PC, tablet)"
    echo -e "  using any VNC Viewer app over WiFi or USB."
    echo ""
    read -p "  Install VNC support? (y/N): " VNC_ANSWER
    VNC_ANSWER=${VNC_ANSWER:-N}

    if [[ "$VNC_ANSWER" =~ ^[Yy]$ ]]; then
        VNC_ENABLED=true

        read -p "  VNC password [default: 123456]: " VNC_PASS_IN
        VNC_PASS="${VNC_PASS_IN:-123456}"
        read -p "  Resolution [default: 1280x720]: " VNC_GEO_IN
        VNC_GEOMETRY="${VNC_GEO_IN:-1280x720}"
        VNC_DISPLAY=":1"

        echo ""
        echo -e "  [*] Installing TigerVNC..."
        install_pkg "tigervnc" "TigerVNC Server"

        mkdir -p ~/.vnc
        echo "$VNC_PASS" | vncpasswd -f > ~/.vnc/passwd
        chmod 600 ~/.vnc/passwd

        case $DE_CHOICE in
            1) VNC_EXEC="exec startxfce4";;
            2) VNC_EXEC="exec startlxqt";;
            3) VNC_EXEC="exec mate-session";;
            4) VNC_EXEC="exec startplasma-x11";;
        esac

        cat > ~/.vnc/xstartup << VNCSTARTUP
#!/data/data/com.termux/files/usr/bin/bash
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export ZINK_DESCRIPTORS=lazy
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:\${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:\${XDG_CONFIG_DIRS}
$VNC_EXEC
VNCSTARTUP
        chmod +x ~/.vnc/xstartup

        cat > ~/start-vnc.sh << VNCEOF
#!/data/data/com.termux/files/usr/bin/bash
echo ""
echo "=============================================="
echo "  [*] Starting ${DE_NAME} via TigerVNC..."
echo "=============================================="
echo ""

pkill -9 -f "termux.x11" 2>/dev/null
vncserver -kill ${VNC_DISPLAY} 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null

unset PULSE_SERVER
pulseaudio --kill 2>/dev/null
sleep 0.5
pulseaudio --start --exit-idle-time=-1
sleep 1
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null
export PULSE_SERVER=127.0.0.1

vncserver -localhost no -geometry ${VNC_GEOMETRY} -depth 24 ${VNC_DISPLAY}

DEVICE_IP=\$(ip -4 addr show wlan0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | head -1)
echo ""
echo "=============================================="
echo "  VNC Ready! Connect with any VNC Viewer:"
echo "    Local   : 127.0.0.1:5901"
[ -n "\$DEVICE_IP" ] && echo "    Network : \${DEVICE_IP}:5901"
echo "    Password: ${VNC_PASS}"
echo "=============================================="
VNCEOF
        chmod +x ~/start-vnc.sh
        echo -e "  [+] Created ~/start-vnc.sh"
    else
        echo -e "  [*] Skipping VNC. You can add it later with:"
        echo -e "      pkg install tigervnc"
    fi
}

# ============== COMPLETION ==============
show_completion() {
    echo ""
    echo -e "${GREEN}"
    cat << 'COMPLETE'
    ╔══════════════════════════════════════════╗
    ║       INSTALLATION COMPLETE!             ║
    ╚══════════════════════════════════════════╝
COMPLETE
    echo -e "${NC}"

    echo -e "${WHITE}[*] ${DE_NAME} desktop is ready.${NC}"
    echo ""
    echo -e "${CYAN}[*] Installed:${NC}"
    echo "    - Firefox, Git, Python 3"
    echo "    - GPU Acceleration (Turnip/Zink)"
    echo "    - Proot Linux Container + App Bridge"
    echo "    - Modern Dark XFCE Theme (Adwaita + Dracula terminal)"
    echo ""
    echo -e "${YELLOW}============================================================${NC}"
    echo -e "${WHITE}  HOW TO START:${NC}"
    echo -e "${YELLOW}============================================================${NC}"
    echo ""
    echo -e "  ${GREEN}Native X11 (recommended):${NC}"
    echo -e "    ${WHITE}bash ~/start-x11.sh${NC}"
    echo -e "    Then open the ${WHITE}Termux-X11${NC} app"
    echo ""
    if [ "$VNC_ENABLED" = "true" ]; then
        echo -e "  ${GREEN}VNC (connect via any VNC Viewer):${NC}"
        echo -e "    ${WHITE}bash ~/start-vnc.sh${NC}  → 127.0.0.1:5901"
        echo ""
    fi
    echo -e "  ${GREEN}Proot Linux shell:${NC}"
    echo -e "    ${WHITE}bash ~/start-proot.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Install proot app → sync to XFCE menu:${NC}"
    echo -e "    ${WHITE}bash ~/proot-menu-sync.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Stop everything:${NC}"
    echo -e "    ${WHITE}bash ~/stop-linux.sh${NC}"
    echo ""
    echo -e "${YELLOW}============================================================${NC}"
    echo ""
    echo -e "${CYAN}  👤 Your username : ${WHITE}${SETUP_USERNAME}${NC}"
    echo ""
    echo -e "${YELLOW}  ★━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━★${NC}"
    echo -e "${WHITE}     If you found this helpful, please subscribe to:${NC}"
    echo -e "${RED}           ▶  orailnoor  on YouTube  ◀${NC}"
    echo -e "${YELLOW}  ★━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━★${NC}"
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
    step_gpu
    step_audio
    step_apps
    step_python
    step_proot
    step_launchers
    step_theme_xfce
    step_shortcuts

    # Optional VNC — asked after all main steps
    step_vnc_optional

    # Apply username to native Termux shell prompt
    BASHRC="$HOME/.bashrc"
    grep -q "SETUP_USERNAME_PROMPT" "$BASHRC" 2>/dev/null || \
        echo "# SETUP_USERNAME_PROMPT\nexport PS1='\[\033[01;32m\]${SETUP_USERNAME}@android\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '" >> "$BASHRC"
    source "$BASHRC" 2>/dev/null || true

    show_completion
}

main
