#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  P-noroot linux — updater
#  Pulls the latest setup script and re-applies it WITHOUT
#  wiping your config: it keeps your XFCE theme, wallpaper,
#  VNC setup, Proot data, username and SD-card storage choice.
#  It refreshes packages and regenerates the helper scripts
#  (start-x11.sh, chromium.sh, ram-manager.sh, lock-screen.sh,
#  power-menu.sh, device-info.sh, fix-proot.sh, proot-menu-sync.sh,
#  stop-linux.sh, update.sh).
# ============================================================
set -u

RAW_BASE="https://raw.githubusercontent.com/Juanoto2012/Proot/main"
STATE_DIR="$HOME/.p-noroot"
BACKUP_DIR="$STATE_DIR/backups/$(date +%Y%m%d-%H%M%S)"
SETUP_LOCAL="$STATE_DIR/termux-linux-setup.sh"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN}  P-noroot linux — updater${NC}"
echo -e "${CYAN}============================================================${NC}"

mkdir -p "$STATE_DIR" "$BACKUP_DIR"

# 1) Back up the user's config and current helper scripts, just in case.
echo -e "${GREEN}[*] Backing up your config to:${NC}"
echo -e "    $BACKUP_DIR"
for item in .config/xfce4 .config/linux-wallpaper.jpg .config/linux-gpu.sh \
             .vnc start-x11.sh start-vnc.sh start-proot.sh \
             chromium.sh helium.sh \
             ram-manager.sh lock-screen.sh power-menu.sh device-info.sh \
             fix-proot.sh stop-proot.sh \
             proot-menu-sync.sh stop-linux.sh update.sh Desktop; do
    if [ -e "$HOME/$item" ]; then
        mkdir -p "$BACKUP_DIR/$(dirname "$item")"
        cp -a "$HOME/$item" "$BACKUP_DIR/$item" 2>/dev/null || true
    fi
done

# 2) Fetch the latest setup script (fail safe: change nothing on error).
echo -e "${GREEN}[*] Downloading the latest setup script...${NC}"
if ! curl -fsSL "$RAW_BASE/termux-linux-setup.sh" -o "$SETUP_LOCAL"; then
    echo -e "${YELLOW}[!] Download failed. Check your connection. Nothing was changed.${NC}"
    exit 1
fi
chmod +x "$SETUP_LOCAL"

# 3) Re-apply in update mode: PNOROOT_UPDATE=1 makes the setup script skip the
#    theme/wallpaper and VNC steps, keep the existing storage choice, and skip
#    re-downloading the Proot rootfs if it is already installed.
echo -e "${GREEN}[*] Applying update (your config is preserved)...${NC}"
PNOROOT_UPDATE=1 bash "$SETUP_LOCAL"

echo ""
echo -e "${GREEN}[+] Update complete.${NC}"
echo -e "    Previous config backed up at: $BACKUP_DIR"
echo -e "    Start the desktop with: ${CYAN}bash ~/start-x11.sh${NC}"
