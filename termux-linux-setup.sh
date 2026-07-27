#!/data/data/com.termux/files/usr/bin/bash
#######################################################
#  P-noroot linux  —  Setup Script
#
#  A light, usable, no-root Linux desktop for Android.
#
#  Features:
#  - XFCE4 desktop (lightweight, fast)
#  - Smart GPU acceleration (Turnip/Zink)
#  - Termux-X11 display + optional VNC
#  - Modern dark XFCE theme + auto wallpaper
#  - Native Chromium browser preinstalled with uBlock Origin
#  - Visual .deb / AppImage installer (hidden Proot glibc backend)
#  - Proot App Bridge (installed apps appear in XFCE menu)
#  - Optional: store the Linux container on an SD card
#  - Python & Web Dev environment
#  - Everything lives inside Termux's private folder by default
#######################################################

# ============== CONFIGURATION ==============
TOTAL_STEPS=12
CURRENT_STEP=0
DE_CHOICE="1"
DE_NAME="XFCE4"
VNC_ENABLED=false
SETUP_USERNAME="user"

# ---- Storage ----
# By default everything lives inside Termux's private folder ($PREFIX).
# The user may optionally relocate the (large) Proot container to an SD card.
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
STORAGE_MODE="internal"     # internal | sd
SD_CARD_ID=""               # e.g. 1A2B-3C4D (only when STORAGE_MODE=sd)
PROOT_SD_BASE=""            # e.g. /storage/1A2B-3C4D/p-noroot-linux

# ---- Update mode ----
# When run by update.sh (PNOROOT_UPDATE=1) the script refreshes packages and
# regenerates helper scripts but PRESERVES the user's config: it skips the
# XFCE theme/wallpaper and VNC steps, doesn't re-prompt for storage, and won't
# re-download the Proot rootfs if it's already installed.
UPDATE_MODE="${PNOROOT_UPDATE:-0}"

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
    ║            P-noroot  linux               ║
    ║       X11 + Proot + Modern XFCE          ║
    ║          light · usable · chingon        ║
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

    # ── Hardcoded to XFCE4 (DroidDesk default) ──
    DE_CHOICE="1"
    DE_NAME="XFCE4"
    echo -e "${GREEN}[+] Desktop: ${DE_NAME} (default)${NC}"

    # --- Multi-DE selection (commented out for now) ---
    # echo -e "${CYAN}Choose your Desktop Environment:${NC}"
    # echo -e "  ${WHITE}1) XFCE4${NC}      — Fast, customizable (Recommended)"
    # echo -e "  ${WHITE}2) LXQt${NC}       — Ultra lightweight"
    # echo -e "  ${WHITE}3) MATE${NC}       — Classic, moderate weight"
    # echo -e "  ${WHITE}4) KDE Plasma${NC} — Heavy, modern (needs strong GPU/RAM)"
    # echo ""
    # while true; do
    #     read -p "Enter number (1-4) [default: 1]: " DE_INPUT
    #     DE_INPUT=${DE_INPUT:-1}
    #     if [[ "$DE_INPUT" =~ ^[1-4]$ ]]; then
    #         DE_CHOICE="$DE_INPUT"; break
    #     else
    #         echo "Please enter 1, 2, 3, or 4."
    #     fi
    # done
    # case $DE_CHOICE in
    #     1) DE_NAME="XFCE4";;
    #     2) DE_NAME="LXQt";;
    #     3) DE_NAME="MATE";;
    #     4) DE_NAME="KDE Plasma";;
    # esac
    # echo -e "\n${GREEN}[+] Selected: ${DE_NAME}${NC}"

    # ---- Username ----
    SETUP_USERNAME="root"
    echo -e "  ${GREEN}[+] Proot User set to: ${SETUP_USERNAME} (Default)${NC}"
    sleep 1
}

# ============== STORAGE SELECTION (SD card) ==============
# By default the whole setup lives in Termux's private folder ($PREFIX).
# The Proot container is by far the biggest part (several GB once you install
# apps). On phones with little internal storage the user can move it to an SD
# card. We ask; if yes, we list the detected SD IDs AND let the user type their
# own ID. The data is stored in Termux's private (app-specific) folder on that
# SD card: /storage/<ID>/Android/data/com.termux/files/... — this path is
# writable by Termux without any extra storage permission on modern Android.
TERMUX_PKG="com.termux"
setup_storage() {
    # In update mode, keep whatever storage the user already chose (detected
    # from the installed-rootfs symlink) — never re-prompt.
    if [ "$UPDATE_MODE" = "1" ]; then
        local pd_rootfs="${TERMUX_PREFIX}/var/lib/proot-distro/installed-rootfs"
        if [ -L "$pd_rootfs" ]; then
            STORAGE_MODE="sd"
            SD_CARD_ID=$(readlink "$pd_rootfs" | sed -n 's#^/storage/\([^/]*\)/.*#\1#p')
            echo -e "  ${GREEN}[+] Update mode: keeping SD storage (${SD_CARD_ID}).${NC}"
        else
            STORAGE_MODE="internal"
            echo -e "  ${GREEN}[+] Update mode: keeping internal storage.${NC}"
        fi
        return 0
    fi

    echo ""
    echo -e "${YELLOW}============================================================${NC}"
    echo -e "${WHITE}  STORAGE: where should the Linux container live?${NC}"
    echo -e "${YELLOW}============================================================${NC}"
    echo ""
    echo -e "  By default everything is stored in Termux's private folder"
    echo -e "  (internal storage). The Proot Linux container can grow to several"
    echo -e "  GB, so you may prefer to keep it on an SD card instead."
    echo ""
    read -p "  Store the Linux container on an SD card? (y/N): " SD_ANSWER
    SD_ANSWER=${SD_ANSWER:-N}

    if [[ ! "$SD_ANSWER" =~ ^[Yy]$ ]]; then
        STORAGE_MODE="internal"
        echo -e "  ${GREEN}[+] Using internal storage (Termux private folder).${NC}"
        return 0
    fi

    # Make sure Termux can see external storage.
    if [ ! -d /storage ]; then
        echo -e "  ${YELLOW}[!] /storage not accessible. Run 'termux-setup-storage' and grant${NC}"
        echo -e "  ${YELLOW}    permission, then re-run. Falling back to internal storage.${NC}"
        STORAGE_MODE="internal"
        return 0
    fi

    # SD cards mount as /storage/XXXX-XXXX. Exclude the internal emulated ones.
    local ids=()
    local entry
    for entry in /storage/*; do
        [ -d "$entry" ] || continue
        local id
        id=$(basename "$entry")
        case "$id" in
            emulated|self|sdcard0|"") continue;;
        esac
        # Must be readable/writable to be useful.
        [ -r "$entry" ] || continue
        ids+=("$id")
    done

    if [ ${#ids[@]} -eq 0 ]; then
        echo -e "  ${YELLOW}[!] No SD card auto-detected under /storage.${NC}"
        echo -e "  ${YELLOW}    You can still type your SD ID manually below, or press${NC}"
        echo -e "  ${YELLOW}    Enter to use internal storage.${NC}"
    else
        echo ""
        echo -e "  ${CYAN}Detected SD card ID(s):${NC}"
        local i=1
        for id in "${ids[@]}"; do
            echo -e "    ${WHITE}${i}) ${id}${NC}"
            i=$((i + 1))
        done
    fi
    echo ""
    echo -e "  ${GRAY}You can pick a number from the list, or type your SD ID directly${NC}"
    echo -e "  ${GRAY}(the ID looks like 1A2B-3C4D and is the folder name under /storage).${NC}"
    echo ""

    local sel
    while true; do
        read -p "  Select or type your SD card ID [Enter = internal]: " sel
        if [ -z "$sel" ]; then
            STORAGE_MODE="internal"
            echo -e "  ${GREEN}[+] Using internal storage (Termux private folder).${NC}"
            return 0
        fi
        # A plain number always refers to the detected list; reject it (and
        # re-prompt) if it is out of range instead of treating it as an SD ID.
        if [[ "$sel" =~ ^[0-9]+$ ]]; then
            if [ ${#ids[@]} -gt 0 ] && [ "$sel" -ge 1 ] && [ "$sel" -le ${#ids[@]} ]; then
                SD_CARD_ID="${ids[$((sel - 1))]}"
                break
            fi
            echo -e "  ${YELLOW}Enter a list number between 1 and ${#ids[@]}, or an SD ID like 1A2B-3C4D.${NC}"
            continue
        fi
        # ...otherwise treat the input as a typed SD ID (e.g. 1A2B-3C4D).
        if [[ "$sel" =~ ^[A-Za-z0-9._-]+$ ]]; then
            SD_CARD_ID="$sel"
            break
        fi
        echo -e "  ${YELLOW}Enter a list number, or an SD ID like 1A2B-3C4D.${NC}"
    done

    # Store data in Termux's private (app-specific) folder on the SD card.
    # This is writable without extra storage permissions on modern Android.
    local sd_termux_priv="/storage/${SD_CARD_ID}/Android/data/${TERMUX_PKG}/files"
    PROOT_SD_BASE="${sd_termux_priv}/p-noroot-linux"

    # Verify we can actually write there before committing.
    if ! mkdir -p "$PROOT_SD_BASE/installed-rootfs" 2>/dev/null; then
        echo -e "  ${YELLOW}[!] Cannot write to ${PROOT_SD_BASE}.${NC}"
        echo -e "  ${YELLOW}    Check the SD ID is correct, the card is inserted, and that${NC}"
        echo -e "  ${YELLOW}    storage permission is granted (termux-setup-storage).${NC}"
        echo -e "  ${YELLOW}    Falling back to internal storage.${NC}"
        STORAGE_MODE="internal"
        SD_CARD_ID=""
        PROOT_SD_BASE=""
        return 0
    fi

    # Point proot-distro's installed-rootfs dir at the SD card via a symlink.
    # proot-distro already logs in with --link2symlink, so it tolerates the
    # symlink/permission limitations of FAT/exFAT SD cards.
    local pd_rootfs="${TERMUX_PREFIX}/var/lib/proot-distro/installed-rootfs"
    mkdir -p "$(dirname "$pd_rootfs")"
    if [ -e "$pd_rootfs" ] && [ ! -L "$pd_rootfs" ]; then
        # Nothing important yet (install runs later) — clear it so we can link.
        rm -rf "$pd_rootfs" 2>/dev/null || true
    fi
    ln -sfn "$PROOT_SD_BASE/installed-rootfs" "$pd_rootfs"

    STORAGE_MODE="sd"
    echo -e "  ${GREEN}[+] Linux container will be stored in Termux's private folder${NC}"
    echo -e "  ${GREEN}    on SD card ${SD_CARD_ID}:${NC}"
    echo -e "      ${WHITE}${PROOT_SD_BASE}/installed-rootfs${NC}"
    echo -e "  ${GRAY}    (scripts, config and menu still live in Termux's private folder)${NC}"
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
        # Modern flat Windows 11-style look (native desktop runs in Termux).
        install_pkg "fluent-gtk-theme" "Fluent GTK theme (Windows 11 style)"
        install_pkg "fluent-icon-theme" "Fluent icon theme"
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
    install_pkg "virglrenderer-android" "VirGL Renderer for Android"
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
    # Keep this list lean so the base install stays light.
    install_pkg "git" "Git"
    install_pkg "wget" "Wget"
    install_pkg "curl" "cURL"
    install_pkg "imagemagick" "ImageMagick (wallpaper)"
    install_pkg "openssh" "OpenSSH"
    install_pkg "neofetch" "Neofetch"
    install_pkg "htop" "htop"
    install_pkg "xdotool"

    # Native Firefox (runs in Termux, no proot)
    install_pkg "firefox" "Firefox"
    install_pkg "xfce4-goodies" "XFCE4 Goodies (Basic apps)"
    install_pkg "libnotify" "libnotify (desktop notifications)"
    install_pkg "procps" "procps (ps/pkill/kill for RAM mgr)"
    install_pkg "zenity" "Zenity (power/info dialogs)"

    # Configure Firefox for 1GB RAM + Adreno GPU + Zink/VA-API offload
    FIREFOX_DIR="${TERMUX_PREFIX}/lib/firefox"
    FIREFOX_PREFS="${HOME}/.mozilla/firefox/*.default-release/prefs.js"
    mkdir -p "$(dirname "$FIREFOX_PREFS")" 2>/dev/null || true

    cat > ~/.mozilla/firefox/pnoroot-gpu-prefs.js << 'FFEOF'
// P-noroot linux: Firefox tuned for 1GB RAM + Adreno GPU + Zink/VA-API
user_pref("browser.sessionstore.restore_pinned_tabs_on_demand", true);
user_pref("browser.sessionstore.restore_on_demand", true);
user_pref("browser.sessionstore.max_tabs_undo", 2);
user_pref("browser.sessionstore.interval", 30000);
user_pref("browser.discovery.enabled", false);
user_pref("browser.newtabpage.enabled", false);
user_pref("browser.onboarding.enabled", false);
user_pref("browser.aboutConfig.showWarning", false);
user_pref("browser.tabs.remote.autostart", true);
user_pref("browser.tabs.unloadOnLowMemory", true);
user_pref("browser.tabs.min_inactive_duration_before_unload", 30000);
user_pref("browser.memory.low_pri_space_threshold_percent", 70);
user_pref("browser.memory.high_pri_space_threshold_percent", 60);
user_pref("browser.memory.low_commit_space_threshold_percent", 80);
user_pref("browser.memory.high_commit_space_threshold_percent", 70);
user_pref("browser.tabs.load_delay_limit", 2);
user_pref("browser.tabs.load_in_background", false);
user_pref("browser.cache.disk.enable", false);
user_pref("browser.cache.memory.enable", true);
user_pref("browser.cache.memory.capacity", 32768);
user_pref("media.cache_size", 51200);
user_pref("browser.display.use_document_fonts", 0);
user_pref("gfx.webrender.all", true);
user_pref("gfx.webrender.compositor", true);
user_pref("layers.acceleration.force-enabled", true);
user_pref("layers.acceleration.disabled", false);
user_pref("gfx.canvas.accelerated.cache-size", 512);
user_pref("gfx.content.opaque-background-color", true);
user_pref("gfx.color-management.enabled", false);
user_pref("gfx.font-rendering.cleartype_params.rendering_mode", 2);
user_pref("network.dns.disablePrefetch", true);
user_pref("network.dns.disablePrefetchHTTPS", true);
user_pref("network.http.max-connections", 32);
user_pref("network.http.max-persistent-connections-per-server", 4);
user_pref("network.http.max-persistent-connections-per-proxy", 2);
user_pref("network.http.spdy.enabled.v3-1", false);
FFEOF
    echo -e "  [+] Firefox configured: GPU HW accel + 1GB RAM tuned"

    # Force-install uBlock Origin (Lite, MV3 — required by modern Chromium)
    # via Chromium's managed policy so it ships preinstalled and enabled.
    local pol_dir="${TERMUX_PREFIX}/etc/chromium/policies/managed"
    mkdir -p "$pol_dir"
    cat > "$pol_dir/ublock-origin.json" << 'POLEOF'
{
  "ExtensionInstallForcelist": [
    "ddkjiahejlhfcafbddmgiahcphecmpfh;https://clients2.google.com/service/update2/crx"
  ]
}
POLEOF
    echo -e "  [+] Chromium + uBlock Origin (policy) configured"
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
    # Alpine is the DroidDesk default: tiny rootfs and much lower idle RAM.
    PROOT_DISTRO="alpine"
    PROOT_LABEL="Alpine Linux"
    # The container hosts the lightweight XFCE session and the app bridge. If it
    # fails to install we keep going anyway so the native scripts (Chromium,
    # Click n run, launchers) are still generated; the user can repair the
    # backend later with ~/fix-proot.sh.
    PROOT_READY=1
    echo -e "${GREEN}[+] Proot distro: ${PROOT_LABEL} (default)${NC}"

    # --- Multi-distro selection (commented out for now) ---
    # echo -e "${CYAN}Choose a Linux distro for Proot:${NC}"
    # echo -e "  ${WHITE}1) Ubuntu 22.04 LTS${NC}  (Recommended)"
    # echo -e "  ${WHITE}2) Debian 12${NC}          (Minimal)"
    # echo -e "  ${WHITE}3) Kali Linux${NC}         (Security/Pentesting)"
    # echo ""
    # while true; do
    #     read -p "Enter number (1-3) [default: 1]: " PROOT_INPUT
    #     PROOT_INPUT=${PROOT_INPUT:-1}
    #     if [[ "$PROOT_INPUT" =~ ^[1-3]$ ]]; then break; fi
    #     echo "Please enter 1, 2, or 3."
    # done
    # case $PROOT_INPUT in
    #     1) PROOT_DISTRO="alpine";         PROOT_LABEL="Ubuntu 22.04";;
    #     2) PROOT_DISTRO="debian";         PROOT_LABEL="Debian 12";;
    #     3) PROOT_DISTRO="kali-nethunter"; PROOT_LABEL="Kali Linux";;
    # esac

    # Don't re-download the rootfs if it's already installed (update mode).
    local rootfs_dir="${TERMUX_PREFIX}/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"
    if [ -d "$rootfs_dir/bin" ]; then
        echo -e "${GREEN}[+] ${PROOT_LABEL} already installed — skipping download.${NC}"
    else
        # Install with retries. On old Android (e.g. Android 10 "ginkgo") the
        # post-extraction step runs proot and dies unless seccomp is disabled,
        # which used to leave a half-written rootfs and the classic
        # "<distro> is not installed" error later on. We disable seccomp,
        # retry a few times, and clean up partial state between attempts.
        local install_log="${TMPDIR:-$TERMUX_PREFIX/tmp}/pnoroot-proot-install.log"
        mkdir -p "$(dirname "$install_log")"
        local attempt=1 max_attempts=3 installed=0
        while [ "$attempt" -le "$max_attempts" ]; do
            echo -e "\n${GREEN}[+] Installing ${PROOT_LABEL} (attempt ${attempt}/${max_attempts})...${NC}"
            (PROOT_NO_SECCOMP=1 proot-distro install "$PROOT_DISTRO" > "$install_log" 2>&1) &
            spinner $! "Downloading ${PROOT_LABEL} rootfs (may take a while)..."
            if [ -d "$rootfs_dir/bin" ]; then
                installed=1
                break
            fi
            echo -e "  ${YELLOW}[!] Attempt ${attempt} failed. Cleaning up partial install...${NC}"
            PROOT_NO_SECCOMP=1 proot-distro remove "$PROOT_DISTRO" > /dev/null 2>&1 || true
            attempt=$((attempt + 1))
            sleep 2
        done

        if [ "$installed" -ne 1 ]; then
            PROOT_READY=0
            echo -e "\n${YELLOW}[!] Could not install ${PROOT_LABEL} right now.${NC}"
            echo -e "  ${YELLOW}Last lines of the install log (${install_log}):${NC}"
            tail -n 15 "$install_log" 2>/dev/null | sed 's/^/    /'
            echo -e "  ${YELLOW}Setup will CONTINUE — the browser, Click n run and the${NC}"
            echo -e "  ${YELLOW}desktop still work. The .deb/AppImage installer needs the${NC}"
            echo -e "  ${YELLOW}backend, so fix it later with:${NC} ${WHITE}bash ~/fix-proot.sh${NC}"
            echo -e "  ${YELLOW}Common fixes on old Android (Android 10 / ginkgo):${NC}"
            echo -e "    • Check internet + free storage (rootfs needs ~1.5 GB)."
            echo -e "    • Re-run: ${WHITE}PROOT_NO_SECCOMP=1 proot-distro install ${PROOT_DISTRO}${NC}"
        fi
    fi

    # termux-api gives the sensor bridge access to accelerometer, gyro, light,
    # GPS, battery, etc. (requires the Termux:API companion app).
    install_pkg "termux-api" "Termux API (device sensors)"

    # ---- alpine-bootstrap.sh ----
    # Shared bootstrap: installs/repairs the whole Alpine desktop. Used by the
    # setup here AND by start-x11.sh (auto-installs Alpine if it's missing).
    cat > ~/alpine-bootstrap.sh << 'BOOTEOF'
#!/data/data/com.termux/files/usr/bin/bash
# DroidDesk — Alpine bootstrap / repair
export PROOT_NO_SECCOMP=1
PROOT_DISTRO="alpine"
ROOTFS="/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"

if [ ! -d "$ROOTFS/bin" ]; then
    echo "[*] Alpine container not found — installing it now..."
    proot-distro install "$PROOT_DISTRO" || {
        echo "[!] Alpine install failed. Check internet/storage and retry."
        exit 1
    }
fi

echo "[*] Bootstrapping Alpine desktop (XFCE + Firefox + sensors + su)..."
proot-distro login "$PROOT_DISTRO" -- /bin/sh -lc '
    # 1) Enable the community repo — XFCE, Firefox and Papirus live there.
    #    Without this apk finds almost nothing and the desktop has no apps.
    sed -i "s|^#\(.*/community\)$|\1|" /etc/apk/repositories 2>/dev/null || true
    grep -q "/community" /etc/apk/repositories || \
        echo "https://dl-cdn.alpinelinux.org/alpine/latest-stable/community" >> /etc/apk/repositories
    apk update

    # 2) Full desktop stack. font-dejavu is REQUIRED: without fonts XFCE
    #    renders a black/blank screen. desktop-file-utils + shared-mime-info
    #    make app menu entries actually appear.
    apk add --no-cache \
        bash dbus dbus-x11 sudo shadow busybox-suid \
        xfce4 xfce4-session xfce4-terminal xfce4-appfinder thunar \
        xfce4-whiskermenu-plugin desktop-file-utils shared-mime-info \
        mesa-dri-gallium mesa-egl mesa-gl mesa-utils \
        font-dejavu adwaita-icon-theme papirus-icon-theme \
        firefox-esr \
        curl wget git htop nano

    # 3) D-Bus needs a machine id or dbus-launch dies silently (black screen).
    [ -s /etc/machine-id ] || dbus-uuidgen > /etc/machine-id
    mkdir -p /run/dbus

    # 4) Root support: sessions already run as root inside proot, and
    #    busybox-suid + shadow give a working "su". Set a known password
    #    and add a passwordless sudo policy for convenience.
    printf "root:root\n" | chpasswd 2>/dev/null || true
    mkdir -p /etc/sudoers.d
    echo "root ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/droiddesk
    chmod 0440 /etc/sudoers.d/droiddesk

    # 5) Sensor client: talks to the Termux-side bridge over shared /tmp.
    cat > /usr/local/bin/termux-sensor <<"SENSOREOF"
#!/bin/sh
# Reads Android sensors through the DroidDesk sensor bridge (shared /tmp).
# Usage: termux-sensor            -> list sensors
#        termux-sensor all        -> one reading of every sensor
#        termux-sensor <name>     -> one reading of a specific sensor
REQ=/tmp/droiddesk-sensor.req
OUT=/tmp/droiddesk-sensor.out
if [ ! -e /tmp/droiddesk-sensor.bridge ]; then
    echo "Sensor bridge not running. Start the desktop with start-x11.sh" >&2
    exit 1
fi
rm -f "$OUT"
echo "${*:-list}" > "$REQ"
i=0
while [ $i -lt 40 ]; do
    if [ -s "$OUT" ]; then cat "$OUT"; rm -f "$OUT"; exit 0; fi
    sleep 0.25; i=$((i+1))
done
echo "Timed out waiting for sensor data." >&2
exit 1
SENSOREOF
    chmod +x /usr/local/bin/termux-sensor
    ln -sf /usr/local/bin/termux-sensor /usr/local/bin/device-sensors

    # 6) Theme: Papirus icons now, Fluent window theme config prepared.
    mkdir -p /root/.config/xfce4/xfconf/xfce-perchannel-xml
    cat > /root/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml <<"ALPINE_XFCE"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Fluent-Dark"/>
    <property name="IconThemeName" type="string" value="Papirus-Dark"/>
  </property>
</channel>
ALPINE_XFCE
'
echo "[+] Alpine desktop ready (XFCE + Firefox ESR + Papirus + su/sudo + sensors)."
BOOTEOF
    chmod +x ~/alpine-bootstrap.sh
    echo -e "  [+] Created ~/alpine-bootstrap.sh"

    if [ "$PROOT_READY" = "1" ]; then
        echo -e "  [*] Bootstrapping ${PROOT_LABEL}..."
        bash ~/alpine-bootstrap.sh > /dev/null 2>&1 || true
        echo -e "  [+] ${PROOT_LABEL} ready (XFCE + Firefox ESR + Papirus + sensors + su)."
    fi

    # ---- sensor-bridge.sh (Termux side) ----
    # Serves Android sensor data to Alpine through the shared /tmp directory.
    cat > ~/sensor-bridge.sh << 'SBEOF'
#!/data/data/com.termux/files/usr/bin/bash
# DroidDesk sensor bridge — answers requests from Alpine's termux-sensor client.
TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
REQ="$TMP/droiddesk-sensor.req"
OUT="$TMP/droiddesk-sensor.out"
FLAG="$TMP/droiddesk-sensor.bridge"

if ! command -v termux-sensor > /dev/null 2>&1; then
    echo "[!] termux-api not installed (pkg install termux-api + Termux:API app)."
    exit 1
fi

touch "$FLAG"
trap 'rm -f "$FLAG" "$REQ" "$OUT"' EXIT INT TERM

while :; do
    if [ -s "$REQ" ]; then
        args=$(cat "$REQ" 2>/dev/null); rm -f "$REQ"
        case "$args" in
            list|"") termux-sensor -l > "$OUT".tmp 2>&1 ;;
            all)     termux-sensor -a -n 1 > "$OUT".tmp 2>&1 ;;
            *)       termux-sensor -s "$args" -n 1 > "$OUT".tmp 2>&1 ;;
        esac
        mv -f "$OUT".tmp "$OUT" 2>/dev/null
    fi
    sleep 0.25
done
SBEOF
    chmod +x ~/sensor-bridge.sh
    echo -e "  [+] Created ~/sensor-bridge.sh (Android sensor bridge)"

    # The browser is native Chromium in Termux (installed in step_apps); the
    # Proot container is used only for proot-menu-sync (app menu bridge).

    PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
    TERMUX_VK_ICD="/data/data/com.termux/files/usr/share/vulkan/icd.d"
    TERMUX_LIB="/data/data/com.termux/files/usr/lib"

    # ---- start-proot.sh ----
    cat > ~/start-proot.sh << PROOTEOF
#!/data/data/com.termux/files/usr/bin/bash
PROOT_DISTRO="$PROOT_DISTRO"
PROOT_LABEL="$PROOT_LABEL"
TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
# Keep proot happy on old Android kernels (seccomp emulation issues).
export PROOT_NO_SECCOMP=1

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
export MESA_GL_VERSION_OVERRIDE=4.0
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=virpipe
export TU_DEBUG=noconform
export ZINK_DESCRIPTORS=lazy
export MESA_VK_WSI_PRESENT_MODE=immediate
[ -f /usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json ] && \
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json
export XDG_DATA_DIRS=/usr/share:/usr/local/share:\${XDG_DATA_DIRS}
export PS1="\[\033[01;32m\]$SETUP_USERNAME@linux\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "
echo ""
echo " User: $SETUP_USERNAME | GPU: GALLIUM=\${GALLIUM_DRIVER}"
echo " Type 'exit' to leave proot."
echo ""
RCEOF

proot-distro login "\$PROOT_DISTRO" \$BINDS --user root -- bash --rcfile "\$_RC"
rm -f "\$_RC"
PROOTEOF
    chmod +x ~/start-proot.sh
    echo -e "  [+] Created ~/start-proot.sh"

    # ---- chromium.sh (native browser launcher) ----
    # Chromium runs natively in Termux (no proot). uBlock Origin is force-
    # installed through the managed policy written in step_apps.
    cat > ~/chromium.sh << 'CHROMEEOF'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:0
# --no-sandbox: Android has no unprivileged user namespaces for the sandbox.
exec chromium --no-sandbox --ozone-platform-hint=auto "$@"
CHROMEEOF
    chmod +x ~/chromium.sh
    echo -e "  [+] Created ~/chromium.sh (native Chromium launcher)"

    # ---- ram-manager.sh (lightweight RAM cleaner for low-memory devices) ----
    # Frees background/cache/idle processes WITHOUT killing foreground apps,
    # browser, desktop, audio, or proot. This is the new light RAM Manager.
    cat > ~/ram-manager.sh << 'RMEOF'
#!/data/data/com.termux/files/usr/bin/bash
# P-noroot linux — RAM Manager
# Designed for 1GB RAM devices. Runs every 30s, frees idle/cache only.
# Never kills: XFCE, Firefox, Chromium, Termux-X11, PulseAudio, foreground apps.

INTERVAL="${RNGR_INTERVAL:-30}"
LOW_PCT="${RNGR_LOW_PCT:-20}"
CRIT_PCT="${RNGR_CRIT_PCT:-10}"

SAFE_FOREGROUND='thunar|xfce4-terminal|xfce4-session|xfwm4|xfce4-panel|xfdesktop|pulseaudio|dbus-daemon|termux-x11|Xwayland|startxfce4|plasmashell|kwin_x11|mate-session|marco|startlxqt|xfce4-keyboard|xfce4-notifyd'

notify(){ command -v notify-send >/dev/null 2>&1 && notify-send -u normal "P-noroot linux" "$1" 2>/dev/null; }

is_foreground(){
    local pid="$1"
    local fg=""
    fg=$(cat /proc/$pid/status 2>/dev/null | grep -E '^State:' | awk '{print $2}')
    [ "$fg" = "R" ] || [ "$fg" = "D" ] || [ "$fg" = "S" ] && return 0
    return 1
}

free_cache(){
    command -v logcat >/dev/null 2>&1 && logcat -c >/dev/null 2>&1 || true
}

kill_idle_heavy(){
    local best_pid="" best_rss=0
    for d in /proc/[0-9]*; do
        pid="${d#/proc/}"
        [ -r "$d/statm" ] || continue
        rss=$(awk '{print $2}' "$d/statm" 2>/dev/null)
        [ -n "$rss" ] || continue
        comm=$(tr -d '\0' < "$d/cmdline" 2>/dev/null); [ -n "$comm" ] || comm=$(cat "$d/comm" 2>/dev/null)
        printf '%s' "$comm" | grep -qiE "$SAFE_FOREGROUND" && continue
        printf '%s' "$comm" | grep -qiE 'firefox|chromium|chrome|brave|chrome-sandbox' && continue
        printf '%s' "$comm" | grep -qiE 'Termux|termux|applets' && continue
        is_foreground "$pid" && continue
        if [ "$rss" -gt "$best_rss" ]; then best_rss="$rss"; best_pid="$pid"; fi
    done
    if [ -n "$best_pid" ] && [ "$best_rss" -gt 4096 ]; then
        name=$(cat "/proc/$best_pid/comm" 2>/dev/null)
        kill "$best_pid" 2>/dev/null || kill -9 "$best_pid" 2>/dev/null || true
        sleep 1
        notify "RAM Manager: freed ${name:-a process} (${best_rss} KB)"
    fi
}

while :; do
    total=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    avail=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
    if [ -n "$total" ] && [ -n "$avail" ] && [ "$total" -gt 0 ]; then
        pct=$(( avail * 100 / total ))
        if [ "$pct" -le "$CRIT_PCT" ]; then
            free_cache
            kill_idle_heavy
        fi
    fi
    sleep "$INTERVAL"
done
RMEOF
    chmod +x ~/ram-manager.sh
    echo -e "  [+] Created ~/ram-manager.sh (lightweight RAM Manager)"

    # ---- fix-proot.sh (diagnose & repair the hidden backend) ----
    # Prints the diagnostics we used to ask for by hand, repairs a dangling SD
    # symlink, and reinstalls the rootfs if it went missing.
    cat > ~/fix-proot.sh << 'FIXEOF'
#!/data/data/com.termux/files/usr/bin/bash
export PROOT_NO_SECCOMP=1
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
RF="$PREFIX_DIR/var/lib/proot-distro/installed-rootfs"
DISTRO="alpine"

echo "== P-noroot linux — Proot diagnostic & repair =="
echo "PREFIX=$PREFIX_DIR"
echo "--- installed-rootfs ---"; ls -la "$RF" 2>&1
echo "--- $DISTRO ---"; ls -la "$RF/$DISTRO" 2>&1 | head
echo "--- proot-distro list ---"; proot-distro list 2>&1
echo "------------------------------------------------"

if [ -L "$RF" ]; then
    target=$(readlink "$RF")
    echo "[*] installed-rootfs -> $target"
    if [ ! -d "$target" ]; then
        echo "[!] SD target missing. Trying to recreate it..."
        if mkdir -p "$target" 2>/dev/null; then
            echo "[+] Recreated $target"
        else
            echo "[!] Could not create $target. Is the SD card inserted and is"
            echo "    storage permission granted (run termux-setup-storage)?"
        fi
    fi
fi

if [ ! -d "$RF/$DISTRO/bin" ]; then
    echo "[*] $DISTRO rootfs missing — installing (this can take a while)..."
    proot-distro install "$DISTRO"
fi

if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
    echo "[+] OK: '$DISTRO' is installed and working."
else
    echo "[!] '$DISTRO' still not working. If it's on an SD card, the card's"
    echo "    filesystem may not support a Linux rootfs — try internal storage."
fi
FIXEOF
    chmod +x ~/fix-proot.sh
    echo -e "  [+] Created ~/fix-proot.sh (diagnose & repair backend)"

    # ---- proot-menu-sync.sh (v5 — embedded) ----
    cat > ~/proot-menu-sync.sh << 'SYNCEOF'
#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  Proot App Menu Bridge v5
#  Syncs proot .desktop files into native XFCE menu.
#  Fixes: $TMPDIR log path, runtime X11 bind, dbus-run-session,
#         Blender libvulkan auto-detect, LibreOffice --norestore
#  v4: old-Android compatibility
#      - PROOT_NO_SECCOMP=1 (works on old kernels)
#      - busybox-safe grep (-E instead of \|) and pgrep guards
#      - Chromium/Helium apps get --no-sandbox automatically
#  v5: use $PREFIX (not a hardcoded path) and ask proot-distro itself
#      whether the distro is installed, so an SD-card rootfs symlink no
#      longer causes a false "'ubuntu' not installed" every run.
# ============================================================

# Keep proot working on old Android kernels whose seccomp filters
# break syscall emulation. Must be exported before any proot login.
export PROOT_NO_SECCOMP=1

PROOT_DISTRO="${1:-alpine}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PROOT_BIN="$PREFIX_DIR/bin/proot-distro"
PROOT_ROOTFS="$PREFIX_DIR/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"
PROOT_APPS="$PROOT_ROOTFS/usr/share/applications"
BRIDGE_DIR="$HOME/.local/share/applications/proot-bridge"
WRAPPER_DIR="$HOME/.local/share/proot-wrappers"
TERMUX_TMP="${TMPDIR:-$PREFIX_DIR/tmp}"

if [ ! -f "$PROOT_BIN" ]; then
    echo "[!] proot-distro not found. pkg install proot-distro"
    exit 1
fi
# Ask proot-distro itself instead of trusting a fixed directory path: when the
# rootfs lives on an SD card it is a symlink, and a plain "-d" test can fail
# (unmounted at boot, scoped-storage timing) even though proot can reach it.
if [ ! -d "$PROOT_ROOTFS" ] && \
   ! "$PROOT_BIN" login "$PROOT_DISTRO" -- true > /dev/null 2>&1; then
    echo "[!] Proot distro '$PROOT_DISTRO' not installed."
    echo "    Install it with: proot-distro install $PROOT_DISTRO"
    echo "    (or re-run the setup / bash ~/update.sh). If it's on an SD card,"
    echo "    make sure the card is mounted and storage permission is granted."
    exit 1
fi
if [ ! -d "$PROOT_APPS" ]; then
    echo "[!] No proot apps yet. proot-distro login $PROOT_DISTRO -- apk add <pkg>"
    exit 0
fi

mkdir -p "$BRIDGE_DIR" "$WRAPPER_DIR"

HAS_GPU="software"
[ -d "/dev/dri" ] && HAS_GPU="zink"

# Ensure D-Bus is available inside Alpine.
if ! "$PROOT_BIN" login "$PROOT_DISTRO" -- /bin/sh -lc 'command -v dbus-launch' > /dev/null 2>&1; then
    echo "[*] Installing D-Bus in Alpine..."
    "$PROOT_BIN" login "$PROOT_DISTRO" -- apk add --no-cache dbus dbus-x11 > /dev/null 2>&1
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

    echo "$appname" | grep -qiE 'libreoffice|soffice' && \
        APP_CMD="$CLEAN_EXEC --norestore --nofirststartwizard"

    # Chromium-based browsers run as root inside proot: --no-sandbox is needed,
    # and the zygote/namespace helpers hit "Operation not permitted" under
    # proot on Android, so disable them too.
    echo "$appname" | grep -qiE 'helium|chrome|chromium|brave|electron|vivaldi|opera' && \
        APP_CMD="$CLEAN_EXEC --no-sandbox --no-zygote --disable-dev-shm-usage --disable-gpu-sandbox"

    if echo "$appname" | grep -qi "blender"; then
        APP_CMD="$CLEAN_EXEC"
        if "$PROOT_BIN" login "$PROOT_DISTRO" -- \
                ldconfig -p 2>/dev/null | grep -q "libvulkan.so.1"; then
            EXTRA_ENV="export GALLIUM_DRIVER=virpipe; export MESA_GL_VERSION_OVERRIDE=4.0;"
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
export PROOT_NO_SECCOMP=1
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
export MESA_GL_VERSION_OVERRIDE=4.0
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=virpipe
export TU_DEBUG=noconform
export MESA_VK_WSI_PRESENT_MODE=immediate
export ZINK_DESCRIPTORS=lazy
export XDG_DATA_DIRS=/usr/share:/usr/local/share:\${XDG_DATA_DIRS}
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

# Refresh the panel/desktop so new entries show up (best-effort; old busybox
# pgrep may lack -x, so fall back to a plain match).
if command -v pgrep > /dev/null 2>&1; then
    pgrep xfce4-panel > /dev/null 2>&1 && xfce4-panel --restart > /dev/null 2>&1 &
    pgrep xfdesktop   > /dev/null 2>&1 && { sleep 1; xfdesktop --reload > /dev/null 2>&1 & }
else
    xfce4-panel --restart > /dev/null 2>&1 &
    { sleep 1; xfdesktop --reload > /dev/null 2>&1 & }
fi
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
export DISPLAY=:0
export GALLIUM_DRIVER=virpipe
export MESA_GL_VERSION_OVERRIDE=4.0
export MESA_GLES_VERSION_OVERRIDE=3.2
export MESA_NO_ERROR=1
export XDG_RUNTIME_DIR=\${TMPDIR:-/data/data/com.termux/files/usr/tmp}
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:\${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:\${XDG_CONFIG_DIRS}
EOF

    # Firefox RAM/GPU wrapper: launches Firefox with forced GPU + low-memory prefs
    mkdir -p ~/.mozilla/firefox
    cat > ~/.mozilla/firefox/pnoroot-gpu-prefs.js << 'FFEOF'
user_pref("browser.sessionstore.restore_pinned_tabs_on_demand", true);
user_pref("browser.sessionstore.restore_on_demand", true);
user_pref("browser.sessionstore.max_tabs_undo", 2);
user_pref("browser.sessionstore.interval", 30000);
user_pref("browser.discovery.enabled", false);
user_pref("browser.newtabpage.enabled", false);
user_pref("browser.onboarding.enabled", false);
user_pref("browser.aboutConfig.showWarning", false);
user_pref("browser.tabs.remote.autostart", true);
user_pref("browser.tabs.unloadOnLowMemory", true);
user_pref("browser.tabs.min_inactive_duration_before_unload", 30000);
user_pref("browser.memory.low_pri_space_threshold_percent", 70);
user_pref("browser.memory.high_pri_space_threshold_percent", 60);
user_pref("browser.memory.low_commit_space_threshold_percent", 80);
user_pref("browser.memory.high_commit_space_threshold_percent", 70);
user_pref("browser.tabs.load_delay_limit", 2);
user_pref("browser.tabs.load_in_background", false);
user_pref("browser.cache.disk.enable", false);
user_pref("browser.cache.memory.enable", true);
user_pref("browser.cache.memory.capacity", 32768);
user_pref("media.cache_size", 51200);
user_pref("browser.display.use_document_fonts", 0);
user_pref("gfx.webrender.all", true);
user_pref("gfx.webrender.compositor", true);
user_pref("layers.acceleration.force-enabled", true);
user_pref("layers.acceleration.disabled", false);
user_pref("gfx.canvas.accelerated.cache-size", 512);
user_pref("gfx.content.opaque-background-color", true);
user_pref("gfx.color-management.enabled", false);
user_pref("gfx.font-rendering.cleartype_params.rendering_mode", 2);
user_pref("network.dns.disablePrefetch", true);
user_pref("network.dns.disablePrefetchHTTPS", true);
user_pref("network.http.max-connections", 32);
user_pref("network.http.max-persistent-connections-per-server", 4);
user_pref("network.http.max-persistent-connections-per-proxy", 2);
user_pref("network.http.spdy.enabled.v3-1", false);
FFEOF

    cat > ~/firefox.sh << 'FIREOF'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:0
source ~/.config/linux-gpu.sh 2>/dev/null || true
export MOZ_USE_OPENGL=1
export MOZ_WEBRENDER=1
export MOZ_ACCELERATED=1
export LIBGL_ALWAYS_SOFTWARE=0
export LD_LIBRARY_PATH="/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}"

PROFILE_DIR=""
if [ -f "$HOME/.mozilla/firefox/profiles.ini" ]; then
    PROFILE_DIR=$(sed -n 's/^Path=//p' "$HOME/.mozilla/firefox/profiles.ini" 2>/dev/null | head -1)
fi
if [ -z "$PROFILE_DIR" ]; then
    PROFILE_DIR=$(find "$HOME/.mozilla/firefox" -maxdepth 2 -type f -name prefs.js 2>/dev/null | head -1 | xargs dirname 2>/dev/null | xargs basename 2>/dev/null)
fi
if [ -n "$PROFILE_DIR" ] && [ -f "$HOME/.mozilla/firefox/pnoroot-gpu-prefs.js" ]; then
    PROFILE_PATH="$HOME/.mozilla/firefox/$PROFILE_DIR"
    mkdir -p "$PROFILE_PATH"
    cp -f "$HOME/.mozilla/firefox/pnoroot-gpu-prefs.js" "$PROFILE_PATH/user.js"
fi

exec firefox "$@"
FIREOF
    chmod +x ~/firefox.sh
    echo -e "  [+] Created ~/firefox.sh (GPU + 1GB RAM tuned)"

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

ensure_alpine() {
    # Auto-install Alpine if the proot container is missing (first run,
    # wiped storage, failed setup, etc.) so the desktop always comes up.
    local rootfs="/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/alpine"
    if [ ! -d "\$rootfs/bin" ]; then
        echo "[!] Alpine container not found — installing it now (one time)..."
        if [ -f ~/alpine-bootstrap.sh ]; then
            bash ~/alpine-bootstrap.sh || { echo "[!] Alpine install failed."; exit 1; }
        else
            PROOT_NO_SECCOMP=1 proot-distro install alpine || exit 1
        fi
    fi
    # Make sure startxfce4 actually exists inside the container (repairs
    # half-installed rootfs where the GUI packages never got installed).
    if ! PROOT_NO_SECCOMP=1 proot-distro login alpine -- /bin/sh -lc 'command -v startxfce4' >/dev/null 2>&1; then
        echo "[*] Alpine found but XFCE missing — bootstrapping desktop..."
        [ -f ~/alpine-bootstrap.sh ] && bash ~/alpine-bootstrap.sh
    fi
}

start_desktop() {
    ensure_alpine

    # Clean only stale display/render processes so repeated starts stay reliable.
    pkill -f "virgl_test_server_android" 2>/dev/null || true
    pkill -f "termux.x11" 2>/dev/null || true
    pkill -f "Xvnc" 2>/dev/null || true
    sleep 1

    unset PULSE_SERVER
    pulseaudio --kill 2>/dev/null || true
    pulseaudio --start --exit-idle-time=-1
    export PULSE_SERVER=127.0.0.1

    echo "[*] Starting VirGL renderer (GPU)..."
    virgl_test_server_android &
    VIRGL_PID=\$!

    # -ac disables X access control: REQUIRED so the Alpine container can
    # connect to the display. Without it the GUI never shows up.
    echo "[*] Starting Termux-X11 on :0..."
    termux-x11 :0 -ac &
    X11_PID=\$!

    # Wait for the X socket instead of a blind sleep (slow devices need more).
    TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
    for i in \$(seq 1 20); do
        [ -e "\$TERMUX_TMP/.X11-unix/X0" ] && break
        sleep 0.5
    done

    export DISPLAY=:0
    export GALLIUM_DRIVER=virpipe
    export MESA_GL_VERSION_OVERRIDE=4.0

    # Android sensor bridge (accelerometer, gyro, light, battery...).
    if [ -f ~/sensor-bridge.sh ] && ! pgrep -f "sensor-bridge.sh" >/dev/null 2>&1; then
        nohup bash ~/sensor-bridge.sh >/dev/null 2>&1 &
    fi

    trap 'kill \$VIRGL_PID \$X11_PID 2>/dev/null || true; pkill -f "sensor-bridge.sh" 2>/dev/null || true' EXIT INT TERM

    if [ -f ~/ram-manager.sh ] && ! pgrep -f "ram-manager.sh" >/dev/null 2>&1; then
        nohup bash ~/ram-manager.sh >/dev/null 2>&1 &
    fi

    echo "----------------------------------------------"
    echo "  [*] Open the Termux-X11 app to see desktop"
    echo "  [*] Root: session runs as root ('su' works)"
    echo "  [*] Sensors: run 'termux-sensor' inside Alpine"
    echo "----------------------------------------------"
    # --shared-tmp shares Termux /tmp (X11 + VirGL sockets + sensor bridge)
    # with Alpine. Session runs as root, so 'su' and 'sudo' both work.
    PROOT_NO_SECCOMP=1 proot-distro login alpine --shared-tmp --user root -- \
        /bin/sh -lc 'export DISPLAY=:0 GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0 XDG_RUNTIME_DIR=/tmp PULSE_SERVER=127.0.0.1; dbus-launch --exit-with-session startxfce4'
}

start_desktop
LAUNCHEREOF
    chmod +x ~/start-x11.sh
    echo -e "  [+] Created ~/start-x11.sh"

    # ---- stop-linux.sh ----
    cat > ~/stop-linux.sh << STOPEOF
#!/data/data/com.termux/files/usr/bin/bash
echo "Stopping all sessions..."
pkill -9 -f "termux.x11" 2>/dev/null
pkill -f "virgl_test_server_android" 2>/dev/null
pkill -f "sensor-bridge.sh" 2>/dev/null
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

    # ---- power-menu.sh (shutdown / reboot / lockscreen) ----
    cat > ~/power-menu.sh << 'POWEREOF'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:0
command -v zenity >/dev/null 2>&1 || { echo "[!] zenity not installed."; exit 1; }

ACTION=$(zenity --list --width=360 --height=260 \
    --title="Power Menu" \
    --column="Action" \
    Lock Screen \
    Sleep \
    Reboot desktop \
    Power off desktop 2>/dev/null) || exit 0

case "$ACTION" in
    "Lock Screen")
        [ -f ~/lock-screen.sh ] && bash ~/lock-screen.sh || xset dpms force off 2>/dev/null || true
        ;;
    "Sleep")
        command -v xset >/dev/null 2>&1 && xset dpms force off 2>/dev/null || true
        ;;
    "Reboot desktop")
        zenity --question --width=360 --text="Restart the desktop session?" 2>/dev/null || exit 0
        [ -f ~/stop-linux.sh ] && bash ~/stop-linux.sh >/dev/null 2>&1 || true
        bash ~/start-x11.sh >/dev/null 2>&1 &
        ;;
    "Power off desktop")
        zenity --question --width=360 --text="Shut down the desktop session?" 2>/dev/null || exit 0
        [ -f ~/stop-linux.sh ] && bash ~/stop-linux.sh >/dev/null 2>&1 || true
        ;;
esac
POWEREOF
    chmod +x ~/power-menu.sh
    echo -e "  [+] Created ~/power-menu.sh"

    # ---- lock-screen.sh ----
    cat > ~/lock-screen.sh << 'LOCKEOF'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:0
command -v xset >/dev/null 2>&1 && xset dpms force off 2>/dev/null || true
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock 2>/dev/null || true
command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock 2>/dev/null || true
LOCKEOF
    chmod +x ~/lock-screen.sh
    echo -e "  [+] Created ~/lock-screen.sh"

    # ---- device-info.sh (battery, wifi, gpu, ram, device) ----
    cat > ~/device-info.sh << 'INFOEOF'
#!/data/data/com.termux/files/usr/bin/bash
export DISPLAY=:0
command -v zenity >/dev/null 2>&1 || { echo "[!] zenity not installed."; exit 1; }

MODEL=$(getprop ro.product.model 2>/dev/null || echo "Unknown")
BRAND=$(getprop ro.product.brand 2>/dev/null || echo "Unknown")
ANDROID_VER=$(getprop ro.build.version.release 2>/dev/null || echo "Unknown")
DEVICE=$(getprop ro.product.device 2>/dev/null || echo "Unknown")

BATTERY=$(termux-battery-status 2>/dev/null)
if [ -n "$BATTERY" ]; then
    BAT_PCT=$(echo "$BATTERY" | grep -o '"percentage":[0-9]*' | head -1 | cut -d: -f2)
    BAT_STAT=$(echo "$BATTERY" | grep -o '"status":"[^"]*"' | head -1 | cut -d: -f2 | tr -d '"')
else
    BAT_PCT="N/A"; BAT_STAT="N/A"
fi

WIFI_SSID=$(termux-wifi-scaninfo 2>/dev/null | grep -oP '"ssid":"\K[^"]+' | head -1)
[ -z "$WIFI_SSID" ] && WIFI_SSID="N/A"

TOTAL_RAM=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
AVAIL_RAM=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
[ -n "$TOTAL_RAM" ] && TOTAL_RAM_MB=$(( TOTAL_RAM / 1024 )) || TOTAL_RAM_MB="N/A"
[ -n "$AVAIL_RAM" ] && AVAIL_RAM_MB=$(( AVAIL_RAM / 1024 )) || AVAIL_RAM_MB="N/A"

GPU=$(getprop ro.hardware.egl 2>/dev/null || echo "N/A")
if [ "$GPU" = *"adreno"* ] || [ "$GPU" = *"freedreno"* ]; then GPU="Adreno (freedreno/Zink)"; fi

INFO=$(cat <<EOF
<b>Device Info</b>

<b>Device:</b> $BRAND $MODEL ($DEVICE)
<b>Android:</b> $ANDROID_VER

<b>Battery:</b> ${BAT_PCT}% ($BAT_STAT)
<b>WiFi:</b> $WIFI_SSID

<b>GPU:</b> $GPU
<b>RAM:</b> $AVAIL_RAM_MB MB free / $TOTAL_RAM_MB MB total
EOF
)

zenity --info --width=400 --height=320 --title="Device Info" --text="$INFO" 2>/dev/null
INFOEOF
    chmod +x ~/device-info.sh
    echo -e "  [+] Created ~/device-info.sh"

    # ~/update.sh: thin wrapper that fetches & runs the latest updater. Keeps
    # the user's config (theme, wallpaper, proot data, storage choice).
    cat > ~/update.sh << 'UPDEOF'
#!/data/data/com.termux/files/usr/bin/bash
# P-noroot linux — pull the latest scripts and re-apply them, keeping your config.
mkdir -p "$HOME/.p-noroot"
echo "[*] Fetching latest updater..."
if curl -fsSL https://raw.githubusercontent.com/Juanoto2012/Proot/main/update.sh \
        -o "$HOME/.p-noroot/update.sh"; then
    exec bash "$HOME/.p-noroot/update.sh"
fi
echo "[!] Could not download the updater. Check your internet connection."
exit 1
UPDEOF
    chmod +x ~/update.sh
    echo -e "  [+] Created ~/update.sh"
}

# ============== STEP 11: XFCE MODERN THEME ==============
step_theme_xfce() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Configuring Modern XFCE Theme...${NC}"
    echo ""

    mkdir -p ~/.config/xfce4/xfconf/xfce-perchannel-xml \
             ~/.config/autostart \
             ~/.config/gtk-3.0 \
             ~/.local/share/themes

    # ---- GTK defaults so Fluent applies to every GTK app, not just XFCE ----
    cat > ~/.config/gtk-3.0/settings.ini << 'GTK3EOF'
[Settings]
gtk-theme-name=Fluent-Dark
gtk-icon-theme-name=Fluent-dark
gtk-font-name=Sans 11
gtk-application-prefer-dark-theme=true
GTK3EOF
    cat > ~/.gtkrc-2.0 << 'GTK2EOF'
gtk-theme-name="Fluent-Dark"
gtk-icon-theme-name="Fluent-dark"
gtk-font-name="Sans 11"
GTK2EOF

    # ---- GTK + Font settings (xsettings.xml) ----
    cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml << 'XSEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Fluent-Dark"/>
    <property name="IconThemeName" type="string" value="Fluent-dark"/>
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
    <property name="theme" type="string" value="Fluent-Dark"/>
    <property name="title_font" type="string" value="Sans Bold 10"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="frame_opacity" type="int" value="100"/>
    <property name="inactive_opacity" type="int" value="100"/>
    <property name="popup_opacity" type="int" value="100"/>
    <property name="show_frame_shadow" type="bool" value="false"/>
    <property name="show_popup_shadow" type="bool" value="false"/>
    <property name="shadow_opacity" type="int" value="0"/>
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

# ---- Modern flat Windows 11-style theme (Fluent) ----
xfconf-query -c xsettings -p /Net/ThemeName -s "Fluent-Dark"
xfconf-query -c xsettings -p /Net/IconThemeName -s "Fluent-dark"
xfconf-query -c xfwm4 -p /general/theme -s "Fluent-Dark"

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

# ---- Compositing OFF (saves RAM/CPU on low-end and old devices) ----
xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null || true
xfconf-query -c xfwm4 -p /general/frame_opacity -t int -s 100 2>/dev/null || true

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

    echo -e "  [+] XFCE theme: Fluent-Dark (Windows 11 style) + Fluent icons + Dracula terminal"
    echo -e "  [+] Compositing OFF for lower RAM/CPU usage (better on old devices)"
    echo -e "  [+] First-run script will configure panels on first launch"
}

# ============== STEP 12: SHORTCUTS ==============
step_shortcuts() {
    update_progress
    echo -e "${PURPLE}[Step ${CURRENT_STEP}/${TOTAL_STEPS}] Creating Desktop Shortcuts...${NC}"
    echo ""
    mkdir -p ~/Desktop

    cat > ~/Desktop/Device-Info.desktop << EOF
[Desktop Entry]
Name=Device Info
Comment=Battery, WiFi, GPU, RAM info
Exec=bash /data/data/com.termux/files/home/device-info.sh
Icon=computer
Type=Application
Terminal=false
EOF

    cat > ~/Desktop/Power.desktop << EOF
[Desktop Entry]
Name=Power
Comment=Lock screen, sleep, shutdown
Exec=bash /data/data/com.termux/files/home/power-menu.sh
Icon=system-shutdown
Type=Application
Terminal=false
EOF

    cat > ~/Desktop/Lock.desktop << EOF
[Desktop Entry]
Name=Lock Screen
Comment=Lock / turn off screen
Exec=bash /data/data/com.termux/files/home/lock-screen.sh
Icon=system-lock-screen
Type=Application
Terminal=false
EOF

    cat > ~/Desktop/Chromium.desktop << 'EOF'
[Desktop Entry]
Name=Chromium
Comment=Chromium browser (native) with uBlock Origin
Exec=chromium --no-sandbox --ozone-platform-hint=auto %U
Icon=chromium
Type=Application
Terminal=false
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

    chmod +x ~/Desktop/*.desktop 2>/dev/null
    echo -e "  [+] Shortcuts: Chromium, Files, Terminal, Device Info, Power, Lock"
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
export MESA_GL_VERSION_OVERRIDE=4.0
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=virpipe
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

    echo -e "${WHITE}[*] P-noroot linux (${DE_NAME}) is ready.${NC}"
    echo ""
    echo -e "${CYAN}[*] Installed:${NC}"
    echo "    - Chromium (native) + uBlock Origin, Git, Python 3"
    echo "    - GPU Acceleration (Turnip/Zink) for Adreno + OpenGL"
    echo "    - XFCE4 (low RAM compositing disabled)"
    echo "    - Firefox tuned for 1GB RAM + GPU HW accel"
    echo "    - RAM Manager (~/ram-manager.sh) auto-starts with desktop"
    echo "    - Device Info (~/device-info.sh), Power Menu (~/power-menu.sh), Lock Screen"
    if [ "$STORAGE_MODE" = "sd" ]; then
        echo "    - Storage: Linux container on SD card ($SD_CARD_ID)"
    else
        echo "    - Storage: Termux private folder (internal)"
    fi
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
    echo -e "  ${GREEN}Chromium browser (with uBlock Origin):${NC}"
    echo -e "    ${WHITE}bash ~/chromium.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Device Info (battery, wifi, gpu, ram):${NC}"
    echo -e "    ${WHITE}bash ~/device-info.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Power Menu (lock, sleep, shutdown):${NC}"
    echo -e "    ${WHITE}bash ~/power-menu.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Lock Screen:${NC}"
    echo -e "    ${WHITE}bash ~/lock-screen.sh${NC}"
    echo ""
    echo -e "  ${GREEN}RAM Manager:${NC}"
    echo -e "    ${WHITE}bash ~/ram-manager.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Re-sync installed apps → XFCE menu:${NC}"
    echo -e "    ${WHITE}bash ~/proot-menu-sync.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Diagnose / repair the Linux backend:${NC}"
    echo -e "    ${WHITE}bash ~/fix-proot.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Stop everything:${NC}"
    echo -e "    ${WHITE}bash ~/stop-linux.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Update (keeps your config):${NC}"
    echo -e "    ${WHITE}bash ~/update.sh${NC}"
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
    setup_storage   # ask about SD-card storage before installing the container

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
    if [ "$UPDATE_MODE" = "1" ]; then
        echo -e "${GREEN}[+] Update mode: keeping your XFCE theme, wallpaper and VNC config.${NC}"
    else
        step_theme_xfce
    fi
    step_shortcuts

    # Optional VNC — asked after all main steps (skipped in update mode)
    [ "$UPDATE_MODE" = "1" ] || step_vnc_optional

    # Apply username to native Termux shell prompt
    BASHRC="$HOME/.bashrc"
    grep -q "SETUP_USERNAME_PROMPT" "$BASHRC" 2>/dev/null || \
        echo "# SETUP_USERNAME_PROMPT\nexport PS1='\[\033[01;32m\]${SETUP_USERNAME}@android\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '" >> "$BASHRC"
    source "$BASHRC" 2>/dev/null || true

    show_completion
}

main
