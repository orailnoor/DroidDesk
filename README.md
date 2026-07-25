# P-noroot linux

A light, usable, no-root Linux desktop for any Android phone. Not a terminal. Not an emulator. A complete desktop environment with direct kernel access -- native Chromium browser, Blender, Metasploit, local AI, all of it.

Designed to stay lightweight: it preinstalls Firefox (no proot needed to browse), skips heavy defaults like VS Code, includes a **visual `.deb` / AppImage installer** (a hidden Proot glibc backend runs the apps and adds them to your menu), and lets you keep the Linux backend on an **SD card** if internal storage is tight. By default everything lives inside Termux's private folder.

Connect your phone to a monitor and it becomes a Linux PC. Unplug it and your entire setup comes with you.

## Video

[![Watch the video](https://img.youtube.com/vi/QCr4WWsfVv8/maxresdefault.jpg)](https://youtu.be/QCr4WWsfVv8)

## What This Actually Runs

Everything below has been tested and confirmed working:

- **Firefox** -- Native Termux browser with **uBlock Origin** (runs without proot).
- **LibreOffice** -- Word processing, spreadsheets, presentations. Fully functional.
- **VS Code** -- Full version (installed inside the container with `apt`). Python, PIP, extensions, everything.
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
curl -sL https://raw.githubusercontent.com/Juanoto2012/Proot/main/termux-linux-setup.sh -o setup.sh
bash setup.sh
```

The script will:
0. Ask where to store the Linux container: **internal** (Termux private folder, default) or an **SD card** -- if you pick SD, it lists the detected SD card IDs and also lets you **type your own SD ID** (e.g. `1A2B-3C4D`). The data is kept in Termux's private (app-specific) folder on that card (`/storage/<ID>/Android/data/com.termux/files/p-noroot-linux`), which Termux can write to without extra storage permissions
1. Update Termux packages
2. Add X11 and TUR repositories
3. Install your chosen desktop environment (XFCE4/LXQt/MATE/KDE)
4. Set up GPU acceleration (Turnip for Adreno, Zink fallback for others)
5. Install Git, Python, and core tools
6. Set up a Proot Linux backend (Ubuntu) for the visual `.deb` / AppImage installer
7. Create the App Bridge for automatic menu syncing
8. Apply a modern flat Windows 11-style dark theme (Fluent + Fluent icons), with the compositor off to keep RAM/CPU usage low
9. Optionally set up VNC for remote access

### Step 4: Start the Desktop

After installation completes:

```bash
bash ~/start-x11.sh
```

Then open the Termux-X11 app on your phone. Your desktop is ready.

### Step 5: Install Desktop Apps

Use `pkg search <name>` or `pkg install <name>` in Termux to install native apps.
For Linux GUI apps in Proot, open a terminal and run `proot-distro login ubuntu -- apt install <pkg>`.

### Memory & stability

Non-rooted Android **cannot enable swap or zram** (both require root), so this
project doesn't create swap. Instead a light **RAM Manager** (`~/ram-manager.sh`,
auto-started by `start-x11.sh`) watches free RAM and, when it gets low, drops
caches and silences idle non-critical background processes — without killing
foreground apps, the browser, or the desktop session.

If the Proot backend ever reports `container 'ubuntu' is not installed` (common
when it lives on an SD card that got unmounted), run `bash ~/fix-proot.sh` — it
prints diagnostics, repairs a dangling SD link, and reinstalls the rootfs if
needed.

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
| `bash ~/chromium.sh` | Launch Chromium (with uBlock Origin) |
| `bash ~/ram-manager.sh` | Start RAM manager manually |
| `bash ~/device-info.sh` | Show device info (battery, wifi, gpu, ram) |
| `bash ~/power-menu.sh` | Lock / sleep / reboot / power off |
| `bash ~/lock-screen.sh` | Lock / turn off screen |
| `bash ~/fix-proot.sh` | Diagnose / repair the Linux backend |
| `bash ~/proot-menu-sync.sh` | Sync installed apps to desktop menu |
| `bash ~/stop-linux.sh` | Stop all sessions |
| `bash ~/update.sh` | Update to the latest scripts (keeps your config) |

## Updating

When the scripts get new features or fixes, run:

```bash
bash ~/update.sh
```

It pulls the latest version and re-applies it **without touching your config**:
your XFCE theme, wallpaper, VNC setup, username, installed Proot apps and your
SD-card storage choice are all preserved. It refreshes packages and regenerates
the helper scripts. A backup of your previous config is saved under
`~/.p-noroot/backups/<timestamp>/` before anything changes.

uBlock Origin is force-installed in native Chromium via a managed policy, so it
stays enabled across updates.

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
