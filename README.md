# DroidDesk

Run a full Linux desktop on any Android phone. Not a terminal. Not an emulator. A complete desktop environment with direct kernel access -- VS Code, Blender, Metasploit, local AI, all of it.

Connect your phone to a monitor and it becomes a Linux PC. Unplug it and your entire setup comes with you.

## What's New

| Change | Details | Reason |
|---|---|---|
| **Proot Sandbox Fix** | Global `--no-sandbox` wrapper script installed inside proot at `/usr/local/bin/proot-sandbox-fix`, plus symlinks for `code`, `chromium`, `brave`, `discord`, `slack`, `teams`, and other Electron/Chromium apps | Proot lacks user namespaces, so Electron/Chromium sandbox crashes with `--no-sandbox` error — the fix auto-appends `--no-sandbox --disable-gpu-sandbox` to all affected binaries |
| **App Bridge Sandbox Detection** | `proot-menu-sync.sh` auto-detects Electron/Chromium apps by name and injects `--no-sandbox` flags and sandbox-disabling env vars into the desktop launcher wrappers | Ensures apps launched from the XFCE menu (not just terminal) also bypass the sandbox correctly |
| **Environment Variable Protection** | Proot shell rcfile exports `ELECTRON_NO_SANDBOX=1`, `ELECTRON_DISABLE_SANDBOX=1`, and `CHROME_DEVEL_SANDBOX=` globally; `/etc/proot-sandbox/env.sh` sources on login | Catches any Electron app that respects env vars instead of CLI flags, even if it's not in the name-based detection list |
| **Debian Repo for Ubuntu Proot** | When Ubuntu is selected, Debian Bookworm's repo is added as the default package source (priority 500) and Ubuntu's own packages are pinned low (priority 99). This means `apt install` prefers Debian packages when they exist in both repos. | Ubuntu ships `chromium`, `firefox`, `thunderbird`, and other packages as snap-only transitional deps that silently fail in proot (no systemd/snapd). Debian still ships them as proper `.deb` packages that work normally. |
| **GPG Key Refresh** | Proot bootstrap now installs `gnupg` and `ca-certificates` first, reinstalls `debian-archive-keyring`, and fetches current archive signing keys from `keyserver.ubuntu.com` before `apt-get update` | Stale rootfs tarballs have expired GPG keys — `apt-get update` silently fails, leaving the proot container with no packages installed |
| **Reliable App Menu Sync** | `proot-menu-sync.sh` now force-shows known apps (Chromium, Firefox, VS Code, LibreOffice) even if their `.desktop` file has `NoDisplay=true`; tracks synced/removed app names instead of just counters; clears garcon menu cache so entries appear on next XFCE start | Chromium and other apps were silently skipped by `NoDisplay=true` and never appeared in the XFCE menu |
| **Fixed Desktop Shortcut Path** | Proot.desktop `Exec=` line now uses `${HOME}/start-proot.sh` instead of the nonexistent `/root/start-proot.sh`; same fix for the first-run theme autostart at `~/.config/autostart/xfce-first-run.desktop` | Termux has no `/root` directory — the shortcut crashed instantly on click |

## Video

[![Watch the video](https://img.youtube.com/vi/QCr4WWsfVv8/maxresdefault.jpg)](https://youtu.be/QCr4WWsfVv8)

## What This Actually Runs

Everything below has been tested and confirmed working:

- **LibreOffice** -- Word processing, spreadsheets, presentations. Fully functional.
- **VS Code** -- Full version. Python, PIP, extensions, everything.
- **Claude Code** -- AI coding agent running directly in terminal.
- **Blender** -- Installs and opens. Laggy on mobile hardware, but it runs.
- **Wireshark** -- Full network analysis, every packet and protocol.
- **Metasploit** -- Pentesting framework, runs fine.
- **Local AI** -- Offline LLM inference, 5+ tokens/second, no API needed.

If it runs on Ubuntu, it runs here.

## How It Works

The Linux environment runs through Termux with direct access to the phone's kernel. No emulation, no translation -- native performance.

The setup script installs a full desktop (XFCE4/LXQt/MATE/KDE) inside Termux using the Termux User Repository (TUR) for GUI apps. For tools not available in TUR (Wireshark, Metasploit, etc.), a Proot container provides a standard Ubuntu/Debian/Kali environment where you install anything with `apt`.

The automatic menu sync scans what you install inside Proot and adds it directly to your desktop app menu. No need to enter the container every time.

## Requirements

- Any Android phone (ARM64)
- [Termux](https://f-droid.org/en/packages/com.termux/) (install from F-Droid, not Play Store)
- [Termux-X11](https://github.com/termux/termux-x11/releases/tag/nightly) (for on-phone display)

### For Monitor Output ( Optional )

**Option A: USB-C Display Output**
If your phone supports display output over USB-C, just use a USB-C to HDMI adapter. Done.

**Option B: Raspberry Pi Bridge**
For phones without display output (most mid-range phones with USB 2.0), use a Raspberry Pi Zero 2W as a bridge:
- Raspberry Pi Zero 2W with Raspberry Pi OS
- Micro USB to USB-C cable
- USB-C hub
- Micro HDMI to HDMI adapter
- SD card with Pi firmware
- Wireless keyboard and mouse

The Pi connects to the phone via USB tethering, detects the phone's IP automatically, and opens a VNC viewer to display the phone's desktop on the monitor.

## Installation

### Step 1: Install Termux

Download and install Termux from F-Droid:
https://f-droid.org/en/packages/com.termux/

Do NOT use the Play Store version. It is outdated and will not work.

### Step 2: Install Termux-X11

Download the latest APK from:
https://github.com/termux/termux-x11/releases/tag/nightly

Install it on your phone. This is the display server that renders the desktop.

### Step 3: Run the Setup Script

Open Termux and run:

```bash
curl -sL https://raw.githubusercontent.com/orailnoor/DroidDesk/main/termux-linux-setup.sh -o setup.sh
bash setup.sh
```

The script will:
1. Update Termux packages
2. Add X11 and TUR repositories
3. Install your chosen desktop environment (XFCE4/LXQt/MATE/KDE)
4. Set up GPU acceleration (Turnip for Adreno, Zink fallback for others)
5. Install Firefox, Git, Python, and core tools
6. Set up a Proot Linux container (Ubuntu/Debian/Kali)
7. Create the App Bridge for automatic menu syncing
8. Apply a modern dark theme
9. Optionally set up VNC for remote access

### Step 4: Start the Desktop

After installation completes:

```bash
bash ~/start-x11.sh
```

Then open the Termux-X11 app on your phone. Your desktop is ready.

### Step 5: Install Apps Inside Proot

To install tools that are not in TUR:

```bash
bash ~/start-proot.sh
apt install wireshark    # or any other package
exit
bash ~/proot-menu-sync.sh
```

The app will appear in your desktop menu automatically.

## Raspberry Pi Monitor Bridge Setup

If you are using a Raspberry Pi Zero 2W to output to a monitor:

### Step 1: Flash Raspberry Pi OS

Flash standard Raspberry Pi OS to an SD card and boot the Pi.

### Step 2: Install VNC Viewer on the Pi

```bash
sudo apt update
sudo apt install realvnc-vnc-viewer
```

### Step 3: Copy the Launcher Script

Copy `pi-launch_phone.sh` to your Pi:

```bash
curl -sL https://raw.githubusercontent.com/orailnoor/DroidDesk/main/pi-launch_phone.sh -o ~/pi-launch_phone.sh
chmod +x ~/pi-launch_phone.sh
```

### Step 4: Connect and Launch

1. Connect the phone to the Pi via USB cable
2. Enable USB Tethering on the phone
3. Start VNC on the phone: `bash ~/start-vnc.sh` (in Termux)
4. Run the bridge script on the Pi:

```bash
bash ~/pi-launch_phone.sh
```

The script auto-detects the phone's IP and opens a fullscreen VNC session on the monitor.

### Optional: Auto-Launch on Boot

To make the Pi automatically connect when powered on, add to crontab:

```bash
crontab -e
```

Add this line:

```
@reboot sleep 15 && /home/pi/pi-launch_phone.sh
```

## Commands Reference

| Command | What It Does |
|---|---|
| `bash ~/start-x11.sh` | Start desktop via Termux-X11 |
| `bash ~/start-vnc.sh` | Start desktop via VNC (if installed) |
| `bash ~/start-proot.sh` | Open Proot Linux shell |
| `bash ~/proot-menu-sync.sh` | Sync Proot apps to desktop menu |
| `bash ~/stop-linux.sh` | Stop all sessions |

## Notes

> [!WARNING]
> **Disable Child Process in Developer Options**
> On some Android versions (MIUI, One UI, stock Android 13+), the system may kill Termux background processes and drop your desktop session. To prevent this:
> 1. Go to **Settings → Developer Options**
> 2. Find **"Child process"** (may be labeled differently depending on your ROM)
> 3. Disable child process restrictions for Termux
>
> Without this, long-running sessions (VNC, Termux-X11) may be killed by the OS without warning.

- Termux-X11 directly on the phone is faster than VNC. Use VNC only when you need monitor output through the Pi bridge or remote access from another device.
- For standalone phone use without a monitor, Termux-X11 is the recommended option.
- The Proot container shares the display with the native Termux desktop. Apps installed in Proot render on the same screen.
- GPU acceleration works best on Adreno GPUs (Qualcomm Snapdragon phones). Other GPUs fall back to software rendering.

## Credits

Created by [orailnoor](https://youtube.com/@orailnoor)
