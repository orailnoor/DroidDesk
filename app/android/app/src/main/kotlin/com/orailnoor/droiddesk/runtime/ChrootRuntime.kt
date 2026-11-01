package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread
import java.util.UUID
import java.util.concurrent.Executors
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplayProfilePolicy

/**
 * Real chroot-based Linux runtime for rooted Android devices.
 *
 * This runtime downloads a standard ARM64 Ubuntu rootfs, mounts the necessary
 * kernel filesystems via root access, and runs the desktop environment inside a
 * real chroot. X11 output is sent to the app's embedded LorieView X server
 * through a bind-mounted Unix socket.
 */
class ChrootRuntime(context: Context) {
    private val context = context.applicationContext

    companion object {
        private const val TAG = "ChrootRuntime"
        private const val CHROOT_DE_MARKER = ".chroot_de_installed"

        // Ubuntu 24.04 ARM64 minimal rootfs
        const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"

        // DesktopActivity starts the chroot while MainActivity owns status and
        // stop controls, so the process handle must be shared app-wide.
        @Volatile private var sessionProcess: Process? = null
        @Volatile private var sessionEnvironmentFile: String? = null
        private var displayProfiles: DesktopProfileExecutor? = null
    }

    private val rootShell = RootShell(context)
    private val rootfsManager = RootfsManager(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    fun isRootfsReady(): Boolean = rootfsManager.isRootfsReady()

    fun isDesktopInstalled(): Boolean {
        return File(rootfsDir, CHROOT_DE_MARKER).exists() ||
                File(rootfsDir, "usr/bin/startxfce4").exists()
    }

    fun isRunning(): Boolean = sessionProcess?.isAlive == true

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getRootfsSizeMB(): Long = rootfsManager.getRootfsSizeMB()

    fun readLinuxClipboard(): String? = runCatching {
        val encoded = rootShell.exec(
            "chroot ${rootfsDir.absolutePath} /bin/bash -c " +
                shellQuote("DISPLAY=:0 xclip -selection clipboard -o 2>/dev/null | base64 -w0"),
        ).trim()
        if (encoded.isEmpty()) "" else String(Base64.decode(encoded, Base64.DEFAULT))
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val encoded = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        rootShell.exec(
            "chroot ${rootfsDir.absolutePath} /bin/bash -c " +
                shellQuote("printf %s ${shellQuote(encoded)} | base64 -d | DISPLAY=:0 xclip -selection clipboard -in"),
        )
        true
    }.getOrDefault(false)

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(rootfsDir, "usr/bin/firefox").exists(),
        "code_oss" to (File(rootfsDir, "usr/bin/code").exists() || File(rootfsDir, "usr/bin/code-oss").exists()),
        "nodejs" to (File(rootfsDir, "usr/bin/node").exists() && File(rootfsDir, "usr/bin/npm").exists()),
        "imagemagick" to (File(rootfsDir, "usr/bin/convert").exists() || File(rootfsDir, "usr/bin/magick").exists()),
    )

    // ── Rootfs setup ──

    /**
     * Download the Ubuntu rootfs with progress callbacks.
     */
    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    /**
     * Extract the downloaded rootfs and configure it for chroot.
     */
    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            onProgress(progress, status)
            if (progress == 1.0) {
                // Additional chroot-specific configuration
                configureChrootRootfs()
            }
        }
    }

    private fun configureChrootRootfs() {
        Log.i(TAG, "Applying chroot-specific rootfs configuration")

        // Ensure critical mount points exist
        listOf(
            "dev", "dev/pts", "dev/shm",
            "proc", "sys", "run",
            "tmp", "tmp/.X11-unix", "tmp/runtime-root",
            "root", "mnt/android", "mnt/sdcard"
        ).forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Portable software-rendering profile. Android vendor GPU libraries do
        // not automatically become usable inside an Ubuntu chroot.
        File(rootfsDir, "etc/profile.d/droiddesk-ha.sh").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #!/bin/bash
                # DroidDesk portable graphics environment
                export DISPLAY=:0
                export XDG_RUNTIME_DIR=/tmp/runtime-root
                export XDG_SESSION_TYPE=x11
                export XDG_DATA_DIRS=/usr/share:/usr/local/share
                export XDG_CONFIG_DIRS=/etc/xdg

                # Conservative Mesa fallback that works across GPU vendors
                export LIBGL_ALWAYS_SOFTWARE=true
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe

                # Disable accessibility bus spam
                export NO_AT_BRIDGE=1
                export GTK_A11Y=none

                # Locale
                export LANG=C.UTF-8
                export LC_ALL=C.UTF-8
                export LANGUAGE=C.UTF-8

                # Prompt
                export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                """.trimIndent()
            )
        }




        // Make sure apt works without _apt sandbox user
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/apt/apt.conf.d/99-droiddesk-reliability").writeText(
            "Acquire::Retries \"3\";\n" +
                    "Acquire::http::Timeout \"30\";\n" +
                    "Acquire::https::Timeout \"30\";\n" +
                    "DPkg::Lock::Timeout \"60\";\n"
        )

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    /**
     * Install the desktop environment and GPU drivers inside the chroot.
     */
    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot()) {
            onProgress(-1.0, "Root access required for chroot mode")
            return
        }
        if (!isRootfsReady()) {
            onProgress(-1.0, "Rootfs not ready. Download and extract first.")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, "Mounting rootfs...")
                ensureMounts()

                onProgress(0.05, "Fixing Android network permissions...")
                execChroot("groupadd -g 3003 inet 2>/dev/null || true; usermod -a -G inet _apt 2>/dev/null || true; usermod -a -G inet root 2>/dev/null || true", onLog)

                onProgress(0.05, "Repairing interrupted packages...")
                execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)

                onProgress(0.05, "Updating package index...")
                if (execChroot("DEBIAN_FRONTEND=noninteractive apt-get update", onLog) != 0) {
                    throw IllegalStateException("Package index update failed")
                }

                onProgress(0.1, "Installing Android kernel compatibility...")
                if (!ensureCloseRangeCompatibility(onLog)) {
                    throw IllegalStateException("Android kernel compatibility setup failed")
                }

                onProgress(0.15, "Installing core tools...")
                if (!isPackageInstalled("x11-utils")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "locales ca-certificates wget curl dbus-x11 xclip x11-utils",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled("x11-utils")) throw IllegalStateException("Core package installation failed")
                    }
                }

                onProgress(0.2, "Installing Mesa GPU drivers...")
                if (!isPackageInstalled("mesa-vulkan-drivers")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 libgl1 vulkan-tools",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        Log.w(TAG, "Mesa packages unavailable; desktop will use available software rendering")
                    }
                }

                onProgress(0.4, "Installing desktop environment...")
                val checkPkg = when (desktopEnv) {
                    "lxqt" -> "lxqt"
                    "mate" -> "mate-desktop-environment"
                    "kde" -> "plasma-desktop"
                    else -> "xfce4"
                }
                if (!isPackageInstalled(checkPkg)) {
                    val dePackages = when (desktopEnv) {
                        "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                        "mate" -> "mate-desktop-environment mate-terminal"
                        "kde" -> "plasma-desktop konsole dolphin"
                        else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad devilspie2"
                    }
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $dePackages",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled(checkPkg)) throw IllegalStateException("Desktop package installation failed")
                    }
                }

                onProgress(0.6, "Installing HiDPI icon theme...")
                if (!isPackageInstalled("papirus-icon-theme")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "papirus-icon-theme adwaita-icon-theme-full fonts-noto-core",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        Log.w(TAG, "HiDPI icon theme install failed; will use default icons")
                    }
                }

                onProgress(0.8, "Installing Desktop Essentials tools...")
                if (!isPackageInstalled("htop")) {
                    val essentialsExit = execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "git nano htop wget curl python3 python3-pip openssh-client",
                        onLog
                    )
                    if (essentialsExit != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled("htop")) throw IllegalStateException("Desktop Essentials package installation failed")
                    }
                }

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                File(rootfsDir, CHROOT_DE_MARKER).writeText("$desktopEnv\n")
                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot() || !isDesktopInstalled()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress(1.0, "Already installed")
            return true
        }

        return try {
            ensureMounts()
            onProgress(0.05, "Repairing interrupted packages...")
            execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)

            val command = when (appId) {
                "firefox" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg
                    install -d -m 0755 /etc/apt/keyrings
                    wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O /etc/apt/keyrings/packages.mozilla.org.asc
                    echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list
                    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
                    apt-get update -y
                    apt-get install -y --no-install-recommends firefox
                """.trimIndent()
                "code_oss" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg apt-transport-https
                    install -d -m 0755 /etc/apt/keyrings
                    wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/packages.microsoft.gpg
                    echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main' > /etc/apt/sources.list.d/vscode.list
                    apt-get update -y
                    apt-get install -y --no-install-recommends code
                """.trimIndent()
                "nodejs" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends nodejs npm"
                "imagemagick" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends imagemagick"
                else -> return false
            }

            onProgress(0.25, "Installing optional application...")
            val exitCode = execChroot(command, onLog)
            if (exitCode != 0) throw IllegalStateException("Package manager exited with code $exitCode")
            onProgress(1.0, "Installation complete")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Optional app installation failed: $appId", error)
            onProgress(-1.0, "Installation failed: ${error.message}")
            false
        }
    }

    // ── Session management ──

    /**
     * Start the chrooted desktop session.
     * The caller should ensure the X11 socket directory is mounted before this.
     */
    fun startSession(desktopEnv: String = "xfce4", displayProfile: DisplayProfile? = null) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start chroot session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) {
            Log.w(TAG, "Chroot session already running")
            return
        }

        // Existing rootfs installations predate the compatibility library.
        // Build it once before starting XFCE so they do not need a reinstall.
        if (!ensureCloseRangeCompatibility()) {
            Log.e(TAG, "Cannot start chroot desktop: close_range compatibility setup failed")
            return
        }

        // The profile is staged in app storage (see installChrootXfceMobileProfile), so its
        // version marker lives there rather than in the root-owned chroot home.
        val upgradingXfceProfile = desktopEnv == "xfce4" &&
            XfceMobileProfile.needsInstall(File(context.cacheDir, "droiddesk-xfce-profile/root"))
        if (desktopEnv == "xfce4") installChrootXfceMobileProfile()
        if (desktopEnv == "xfce4") {
            runCatching {
                AndroidAppBridge.syncLaunchers(
                    context = context,
                    homeDir = File(rootfsDir, "root"),
                    python = File(rootfsDir, "usr/bin/python3"),
                    sessionRoot = rootfsDir,
                )
            }.onFailure { Log.w(TAG, "Android desktop launchers could not be refreshed", it) }
        }

        ensureMounts()
        bindX11Socket()

        if (!File(rootfsDir, "usr/bin/xclip").canExecute()) {
            Log.i(TAG, "Installing the chroot clipboard compatibility helper")
            val installed = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xclip",
                {},
            ) == 0
            if (!installed) Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
        }

        // Older installs predate the auto-maximize helper; try once while upgrading the profile.
        if (upgradingXfceProfile && !File(rootfsDir, "usr/bin/devilspie2").canExecute()) {
            Log.i(TAG, "Installing the window fitting helper")
            val installed = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends devilspie2",
                {},
            ) == 0
            if (!installed) Log.w(TAG, "Window fitting helper unavailable; windows will keep their default size")
        }

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }
        val sessionId = UUID.randomUUID().toString()
        val environmentFile = "/tmp/droiddesk-session-$sessionId.env"
        val wrapperFile = "/tmp/droiddesk-session-$sessionId.sh"
        val stagedProfile = XfceDisplayProfile.buildStageScript(displayProfile ?: DisplayProfilePolicy.phoneDefaultProfile())
        val wrapper = """
            #!/bin/bash
            set -eu
            export DISPLAY=:0
            export XDG_CONFIG_HOME="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}"
            if [ -z "${'$'}{XDG_RUNTIME_DIR:-}" ]; then
                export XDG_RUNTIME_DIR="/tmp/droiddesk-runtime-$sessionId"
                mkdir -p "${'$'}XDG_RUNTIME_DIR"
                chmod 700 "${'$'}XDG_RUNTIME_DIR"
            fi
            export DROIDDESK_SESSION_ID='$sessionId'
            export DROIDDESK_SESSION_PID="${'$'}${'$'}"
            previous_umask=${'$'}(umask)
            umask 077
            {
                printf 'export HOME=%q\n' "${'$'}HOME"
                printf 'export DISPLAY=%q\n' "${'$'}DISPLAY"
                printf 'export DBUS_SESSION_BUS_ADDRESS=%q\n' "${'$'}DBUS_SESSION_BUS_ADDRESS"
                printf 'export XDG_CONFIG_HOME=%q\n' "${'$'}XDG_CONFIG_HOME"
                printf 'export XDG_RUNTIME_DIR=%q\n' "${'$'}XDG_RUNTIME_DIR"
                printf 'export DROIDDESK_SESSION_ID=%q\n' "${'$'}DROIDDESK_SESSION_ID"
                printf 'export DROIDDESK_SESSION_PID=%q\n' "${'$'}DROIDDESK_SESSION_PID"
            } > '$environmentFile'
            umask "${'$'}previous_umask"
            exec ${shellQuote(deBin)}
        """.trimIndent()

        val runScript = """
            # Standard FHS PATH (inherited Android PATH lacks /usr/bin)
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            # Reset environment variables leaked from Android app
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            # This is a normal Ubuntu userspace, not the relocated native
            # Termux runtime.  Load only the glibc compatibility shim built
            # for this rooted Ubuntu filesystem.
            export LD_PRELOAD=/usr/local/lib/libdroiddesk-close-range.so

            # Source DroidDesk environment
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true

            # Make sure X11 socket dir exists in case bind mount was late
            mkdir -p /tmp/.X11-unix

            # ── Wait for X server to be ready with the correct resolution ──
            # The LorieView X server may still be initializing when the chroot
            # process starts. Poll until the socket exists and xdpyinfo reports
            # a usable geometry. 1280x1024 can be a legitimate measured window.
            echo "DIAG: Waiting for X server on DISPLAY=:0 ..."
            for attempt in ${'$'}(seq 1 50); do
                if [ -e /tmp/.X11-unix/X0 ]; then
                    current_res=${'$'}(xdpyinfo 2>/dev/null | grep 'dimensions:' | awk '{print ${'$'}2}')
                    if [ -n "${'$'}current_res" ]; then
                        echo "DIAG: X server ready at ${'$'}current_res"
                        break
                    fi
                fi
                sleep 0.1
            done

            echo "DIAG: Starting $desktopEnv in chroot on DISPLAY=:0 ..."

            (
$stagedProfile
            )
            if command -v xrdb >/dev/null 2>&1; then
                if [ -f "${'$'}HOME/.Xresources" ]; then xrdb -merge "${'$'}HOME/.Xresources"; fi
                xrdb -merge "${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/droiddesk/display-profile.Xresources"
            else
                echo "DIAG: xrdb unavailable; live profile application will report the missing helper"
            fi

            # Disable GTK client-side decorations so all apps use xfwm4's
            # title bar (whose maximize button actually works on Xwayland)
            export GTK_CSD=0

            cat > '$wrapperFile' <<'DROIDDESK_SESSION'
$wrapper
DROIDDESK_SESSION
            chmod +x '$wrapperFile'

            # dbus-run-session owns the one session bus for Xfce
            exec dbus-run-session -- '$wrapperFile'
        """.trimIndent()

        Log.i(TAG, "Starting chroot session for $desktopEnv")

        // Launch via ProcessBuilder through su so we get a Process handle we can monitor.
        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        val startedSession = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()
        sessionProcess = startedSession
        sessionEnvironmentFile = environmentFile

        Thread {
            try {
                val reader = startedSession.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "CHROOT DESKTOP: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                Log.d(TAG, "Chroot desktop output stream closed")
            }
        }.start()
    }

    /** Wait until the chroot desktop shell has created its visible components. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val processes = when (desktopEnv) {
            "lxqt" -> listOf("lxqt-session", "lxqt-panel")
            "mate" -> listOf("mate-session", "mate-panel")
            "kde" -> listOf("plasmashell")
            else -> listOf("xfdesktop", "xfce4-panel")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val check = processes.joinToString(" && ") { "pgrep -x '$it' >/dev/null" }
            val output = runCatching {
                rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(check)}")
            }.getOrDefault("")
            if (!output.contains("not found", ignoreCase = true) &&
                processes.all { process ->
                    runCatching {
                        rootShell.exec(
                            "chroot ${rootfsDir.absolutePath} /usr/bin/pgrep -x ${shellQuote(process)}",
                        ).trim().isNotEmpty()
                    }.getOrDefault(false)
                }) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(150)
        }
        Log.w(TAG, "Timed out waiting for chroot $desktopEnv to paint its first desktop frame")
        return false
    }

    /**
     * Stage the profile as the app user, then copy it into the root-owned
     * Ubuntu filesystem through su.  apt makes /root and /usr root-owned, so
     * writing the profile directly from Kotlin fails on real rooted devices.
     */
    private fun installChrootXfceMobileProfile() {
        val stagingDir = File(context.cacheDir, "droiddesk-xfce-profile")
        val stagingHome = File(stagingDir, "root")
        val stagingWallpaper = File(stagingDir, "ubuntu-touch.jpg")
        val sessionWallpaper = "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"

        val staged = XfceMobileProfile.install(
            context = context,
            homeDir = stagingHome,
            wallpaperFile = stagingWallpaper,
            wallpaperPathInSession = sessionWallpaper,
        )
        if (!staged) {
            Log.w(TAG, "Could not stage the XFCE mobile profile; starting with stock XFCE")
            return
        }

        val chrootHome = File(rootfsDir, "root")
        val chrootWallpaper = File(rootfsDir, sessionWallpaper.removePrefix("/"))
        val command = """
            set -e
            mkdir -p ${shellQuote(chrootHome.absolutePath)}
            mkdir -p ${shellQuote(chrootWallpaper.parentFile!!.absolutePath)}
            cp -f ${shellQuote(stagingWallpaper.absolutePath)} ${shellQuote(chrootWallpaper.absolutePath)}
            chmod 0644 ${shellQuote(chrootWallpaper.absolutePath)}
            cp -R ${shellQuote(File(stagingHome, ".config").absolutePath)} ${shellQuote(chrootHome.absolutePath)}
            chown -R 0:0 ${shellQuote(File(chrootHome, ".config").absolutePath)}
        """.trimIndent()

        try {
            rootShell.exec(command)
            if (!chrootWallpaper.exists()) {
                Log.w(TAG, "XFCE wallpaper was not copied into the chroot")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile into chroot", error)
        }
    }

    /**
     * Android 5.4-derived kernels reject Ubuntu glibc's close_range() call
     * with EINVAL. GLib then cannot spawn any XFCE process. This tiny
     * glibc-side preload returns ENOSYS instead, so GLib uses its own safe
     * file-descriptor-walk fallback. It is only used inside the rooted Ubuntu
     * chroot; Termux's Android-native runtime is unaffected.
     */
    private fun ensureCloseRangeCompatibility(onLog: (String) -> Unit = {}): Boolean {
        val target = File(rootfsDir, "usr/local/lib/libdroiddesk-close-range.so")
        if (target.exists()) return true

        return try {
            val stagingDir = File(context.cacheDir, "droiddesk-close-range").apply { mkdirs() }
            val stagedSource = File(stagingDir, "close_range_compat.c")
            context.assets.open("droiddesk/close_range_compat.c").use { input ->
                stagedSource.outputStream().use(input::copyTo)
            }

            val sourceInRootfs = File(rootfsDir, "usr/local/src/droiddesk/close_range_compat.c")
            rootShell.exec(
                "mkdir -p ${shellQuote(sourceInRootfs.parentFile!!.absolutePath)} && " +
                        "cp -f ${shellQuote(stagedSource.absolutePath)} ${shellQuote(sourceInRootfs.absolutePath)}"
            )

            onLog("Installing rooted-desktop compatibility support...\n")
            if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc libc6-dev",
                    onLog,
                ) != 0
            ) return false

            val buildCommand = """
                set -e
                mkdir -p /usr/local/lib /tmp/droiddesk-close-range-build
                printf 'GLIBC_2.34 { global: close_range; };\n' > /tmp/droiddesk-close-range-build/version.map
                gcc -shared -fPIC -O2 -Wl,--version-script=/tmp/droiddesk-close-range-build/version.map -o /usr/local/lib/libdroiddesk-close-range.so /usr/local/src/droiddesk/close_range_compat.c
                chmod 0644 /usr/local/lib/libdroiddesk-close-range.so
                rm -f /tmp/droiddesk-close-range-build/version.map
                rmdir /tmp/droiddesk-close-range-build
            """.trimIndent()
            val built = execChroot(buildCommand, onLog) == 0
            if (built) Log.i(TAG, "Installed rooted close_range compatibility library")
            built
        } catch (error: Exception) {
            Log.e(TAG, "Failed to build rooted close_range compatibility library", error)
            false
        }
    }

    /**
     * Stop the chroot session and unmount bind mounts.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping chroot session...")
        cancelDisplayProfile()
        sessionProcess?.let {
            try {
                it.destroyForcibly()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session: ${e.message}")
            }
        }
        sessionProcess = null
        val environmentFile = sessionEnvironmentFile
        sessionEnvironmentFile = null
        if (environmentFile != null) {
            val wrapperFile = environmentFile.removeSuffix(".env") + ".sh"
            val code = rootShell.exec(
                "chroot ${shellQuote(rootfsDir.absolutePath)} /bin/bash -c " +
                    shellQuote("rm -f ${shellQuote(environmentFile)} ${shellQuote(wrapperFile)}"),
            ) { Log.d("DesktopDisplay", it) }
            if (code != 0) Log.w("DesktopDisplay", "Could not remove retired chroot session metadata")
        }
        unmountAll()
        Log.i(TAG, "Chroot session stopped")
    }

    fun applyDisplayProfile(profile: DisplayProfile, onComplete: (Result<Unit>) -> Unit) {
        try {
            val runner = captureDesktopRunner()
            val executor = synchronized(Companion) {
                displayProfiles ?: DesktopProfileExecutor(
                    Executors.newSingleThreadExecutor { task -> Thread(task, "ChrootDesktopProfiles") },
                    { task -> Handler(Looper.getMainLooper()).post(task) },
                ).also { displayProfiles = it }
            }
            val script = XfceDisplayProfile.buildApplyScript(profile)
            executor.apply({ runner.run(script) }, onComplete)
        } catch (error: IllegalStateException) {
            Log.e("DesktopDisplay", "Chroot profile has no live session environment", error)
            onComplete(Result.failure(error))
        }
    }

    fun cancelDisplayProfile() {
        displayProfiles?.cancel()
    }

    fun executeDesktopCommand(command: String): Result<Unit> = try {
        captureDesktopRunner().run(command)
    } catch (error: IllegalStateException) {
        Log.e("DesktopDisplay", "Chroot desktop command has no live session", error)
        Result.failure(error)
    }

    private fun captureDesktopRunner(): DesktopCommandRunner {
        val session = checkNotNull(sessionProcess) { "The chroot desktop session is not running" }
        check(session.isAlive) { "The chroot desktop session ended" }
        val environmentFile = checkNotNull(sessionEnvironmentFile) { "The chroot desktop bus environment is unavailable" }
        val su = checkNotNull(rootShell.findSuPath()) { "Root access is unavailable for desktop settings" }
        return DesktopCommandRunner({ script ->
            check(sessionProcess === session && session.isAlive) { "The chroot desktop session changed" }
            val command = getPreloadEnv() +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "test -r ${shellQuote(environmentFile)} || { echo 'Desktop session environment is missing' >&2; exit 1; }; " +
                ". ${shellQuote(environmentFile)}; exec /bin/bash -c ${shellQuote(script)}"
            ProcessBuilder(su, "-c", "chroot ${shellQuote(rootfsDir.absolutePath)} /bin/bash -c ${shellQuote(command)}")
        }) { Log.e("DesktopDisplay", it) }
    }

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     */
    fun ensureMounts() {
        if (!hasRoot()) return

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs") { isMounted("dev/shm") }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Fix missing symlinks in Android's /dev for chroot compatibility
        val devPath = File(rootfsDir, "dev").absolutePath
        rootShell.exec("ln -snf /proc/self/fd $devPath/fd")
        rootShell.exec("ln -snf /proc/self/fd/0 $devPath/stdin")
        rootShell.exec("ln -snf /proc/self/fd/1 $devPath/stdout")
        rootShell.exec("ln -snf /proc/self/fd/2 $devPath/stderr")

        // Create runtime dirs after tmpfs is mounted
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")

        // Refresh DNS — the phone's network may have changed since the
        // rootfs was first extracted, and package installs can clobber resolv.conf
        // Ubuntu makes resolv.conf a symlink to systemd; delete it first so writeText succeeds.
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.delete()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        Log.i(TAG, "Refreshed chroot resolv.conf")
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    /**
     * Bind-mount the host X11 socket directory into the chroot.
     */
    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath

        // If already mounted, leave it
        val mounts = rootShell.exec("mount").lines()
        if (mounts.any { it.contains(" on $chrootX11 ") }) return

        rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        Log.i(TAG, "Bound X11 socket: $hostX11 -> $chrootX11")
    }

    /**
     * Unmount all DroidDesk-related mounts.
     */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        val targets = listOf(
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath
        )
        // Unmount in reverse order, be tolerant of busy mounts
        targets.reversed().forEach { target ->
            if (mounts.any { it.contains(" on $target ") }) {
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
        }
    }

    // ── Command execution inside chroot ──

    private fun getPreloadEnv(): String {
        return if (File(rootfsDir, "usr/local/lib/libdroiddesk-close-range.so").exists()) {
            "export LD_PRELOAD=/usr/local/lib/libdroiddesk-close-range.so; "
        } else {
            ""
        }
    }

    /**
     * Execute a command inside the chroot as root.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!hasRoot()) return "Error: root access required"
        val wrapped = "${getPreloadEnv()}export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return if (onOutput != null) {
            val code = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
                onOutput(chunk)
            }
            "Exit code: $code"
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "${getPreloadEnv()}export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            Log.d(TAG, "execChroot: ${chunk.trimEnd()}")
            onLog(chunk)
        }
        Log.d(TAG, "chroot command exit code: $output")
        return output
    }

    private fun shellQuote(input: String): String {
        // Use a single-quoted string that handles embedded single quotes safely
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        // -f='${Status}' returns something like 'install ok installed' if successfully installed
        return execChroot("dpkg-query -W -f='\${Status}' $pkg 2>/dev/null | grep -q 'install ok installed'") == 0
    }
}
