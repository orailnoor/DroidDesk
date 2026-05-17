# DroidDesk

Run a full Linux desktop on an Android phone. Not a terminal. Not an emulator. A real desktop environment with GPU acceleration, a proper app menu, and a workflow that now reaches into both the Linux side and the Android side of the phone.

Plug the phone into a monitor and it behaves like a compact desktop machine. Unplug it and the full setup stays in your pocket.

## Video

[![Watch the video](https://img.youtube.com/vi/QCr4WWsfVv8/maxresdefault.jpg)](https://youtu.be/QCr4WWsfVv8)

## What This Actually Runs

Everything below has been tested and confirmed working on the Linux side:

- **LibreOffice** for documents, spreadsheets, and presentations.
- **VS Code** with Python, PIP, extensions, and normal desktop workflows.
- **Claude Code** running directly in terminal inside DroidDesk.
- **Blender** on mobile hardware. Heavy, but functional.
- **Wireshark** for packet inspection and network analysis.
- **Metasploit** for security and research workflows.
- **Local AI** for offline LLM inference without an API.

The new Android integration layer also lets DroidDesk index installed Android apps and expose launchers for them inside the Linux desktop menu. On phones and ROMs that support freeform windows or strong external-display multitasking, that gets very close to a true mixed Android + Linux desk setup. On standard Android builds, it still removes the friction of bouncing back through the launcher every time you need a phone app.

## What Changed

- **Android App Bridge** scans installed Android apps and adds launchers directly into DroidDesk.
- **APK handoff** lets you trigger Android APK installation from Termux with a single command.
- **Automatic refresh** runs when the desktop starts so new Android apps can appear in the menu without manual cleanup.
- **Waydroid helper hook** is included for advanced users running a compatible rooted/kernel-capable environment where Waydroid is already installed separately.

## How It Works

The Linux environment runs through Termux with direct access to the phone's kernel. No emulation, no VM, and no translation layer for the Linux desktop itself.

The setup script installs a full desktop environment inside Termux using the X11 and TUR repositories. For heavier packages that are not available natively in Termux, DroidDesk provisions a Proot container and then mirrors those Linux applications back into the desktop menu with the built-in App Bridge.

The new Android App Bridge does the same kind of thing for the phone side. It scans installed Android packages, resolves their launcher activities, and creates desktop entries so they can be started from inside DroidDesk.

For advanced users experimenting beyond stock rootless Termux, a `start-waydroid.sh` helper is also generated. That helper is intentionally separate from the main install path because Waydroid needs a compatible Linux base plus binder/memfd-capable kernel support and is not something DroidDesk can honestly promise on every standard Android phone.

## Requirements

- Any Android phone with ARM64 support
- [Termux](https://f-droid.org/en/packages/com.termux/) from F-Droid
- [Termux-X11](https://github.com/termux/termux-x11/releases/tag/nightly) for on-phone display

### For Monitor Output (Optional)

**Option A: USB-C display output**

If your phone supports display output over USB-C, use a USB-C to HDMI adapter.

**Option B: Raspberry Pi bridge**

For phones without display output, you can use a Raspberry Pi Zero 2W as a bridge:

- Raspberry Pi Zero 2W with Raspberry Pi OS
- Micro USB to USB-C cable
- USB-C hub
- Micro HDMI to HDMI adapter
- SD card with Pi firmware
- Wireless keyboard and mouse

The Pi connects through USB tethering, detects the phone automatically, and opens a fullscreen VNC session on the monitor.

## Installation

### Step 1: Install Termux

Install Termux from F-Droid:

https://f-droid.org/en/packages/com.termux/

Do not use the Play Store build.

### Step 2: Install Termux-X11

Download the latest APK from:

https://github.com/termux/termux-x11/releases/tag/nightly

Install it on the phone. This is the display server used by DroidDesk.

### Step 3: Run the Setup Script

Open Termux and run:

```bash
curl -sL https://raw.githubusercontent.com/orailnoor/DroidDesk/main/termux-linux-setup.sh -o setup.sh
bash setup.sh
```

The script will:

1. Update Termux packages
2. Add the X11 and TUR repositories
3. Install your desktop environment
4. Configure GPU acceleration
5. Install Firefox, Git, Python, and core tools
6. Set up a Proot Linux container
7. Sync Proot Linux apps into the desktop menu
8. Build the Android App Bridge and scan installed Android apps
9. Apply the desktop theme and shortcuts
10. Optionally set up VNC for remote or bridge use

### Step 4: Start the Desktop

After installation:

```bash
bash ~/start-x11.sh
```

Then open the Termux-X11 app on the phone.

### Step 5: Install Linux Apps Inside Proot

For packages that are not in TUR:

```bash
bash ~/start-proot.sh
apt install wireshark
exit
bash ~/proot-menu-sync.sh
```

The Linux app will appear in the desktop menu.

### Step 6: Refresh Android App Launchers

If you install a new Android app and want to refresh the DroidDesk menu immediately:

```bash
bash ~/android-app-sync.sh
```

## Raspberry Pi Monitor Bridge Setup

If you are using a Raspberry Pi Zero 2W for monitor output:

### Step 1: Flash Raspberry Pi OS

Flash a standard Raspberry Pi OS image and boot the Pi.

### Step 2: Install VNC Viewer on the Pi

```bash
sudo apt update
sudo apt install realvnc-vnc-viewer
```

### Step 3: Copy the Launcher Script

```bash
curl -sL https://raw.githubusercontent.com/orailnoor/DroidDesk/main/pi-launch_phone.sh -o ~/pi-launch_phone.sh
chmod +x ~/pi-launch_phone.sh
```

### Step 4: Connect and Launch

1. Connect the phone to the Pi by USB
2. Enable USB tethering on the phone
3. Start VNC on the phone with `bash ~/start-vnc.sh`
4. Run the bridge script on the Pi:

```bash
bash ~/pi-launch_phone.sh
```

The Pi will detect the phone IP and open the VNC desktop in fullscreen.

## Commands Reference

| Command | What It Does |
|---|---|
| `bash ~/start-x11.sh` | Start DroidDesk through Termux-X11 |
| `bash ~/start-vnc.sh` | Start DroidDesk through VNC |
| `bash ~/start-proot.sh` | Open the Proot Linux shell |
| `bash ~/proot-menu-sync.sh` | Refresh Linux app launchers from Proot |
| `bash ~/android-app-sync.sh` | Refresh Android app launchers from the phone side |
| `bash ~/install-android-apk.sh /path/to/app.apk` | Hand an APK to Android's package installer |
| `bash ~/start-waydroid.sh` | Launch the advanced Waydroid helper if Waydroid is already installed separately |
| `bash ~/stop-linux.sh` | Stop active desktop services |

## Notes

> [!WARNING]
> **Disable aggressive child-process restrictions**
> On some Android builds, the OS may kill Termux background processes and break long-running desktop sessions. If that happens, check Developer Options and disable the child-process restriction affecting Termux.

- Termux-X11 on the phone is faster than VNC. Use VNC mainly for Pi bridge output or remote access.
- The Proot container shares the same display as the native Termux desktop, so Linux apps launched through the bridge appear in the same environment.
- The Android App Bridge launches the phone's native Android apps. On standard Android builds, those apps may come forward outside the Termux-X11 window. On devices with better desktop-mode or freeform-window support, the mixed workflow feels much more seamless.
- GPU acceleration is strongest on Adreno-based phones. Other GPUs fall back to slower rendering paths.
- The included `start-waydroid.sh` helper is for advanced compatible environments only. Stock rootless Termux users should treat the Android App Bridge as the supported Android integration path.

## Credits

Created by [orailnoor](https://youtube.com/@orailnoor)

Android integration update by [Samin Yeasar](https://github.com/solez-ai), who pushed the mixed Android + Linux workflow forward with the Android app bridge direction, launcher sync improvements, and the advanced Waydroid-ready helper entry point.

- Instagram: [solez_ai](https://www.instagram.com/solez_ai/)
- GitHub: [solez-ai](https://github.com/solez-ai)
- X: [Solez_None](https://x.com/Solez_None)
