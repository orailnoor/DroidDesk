package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Manages Linux rootfs downloads, extraction, and lifecycle.
 *
 * Rootfs images are standard arm64 Linux distributions downloaded from
 * official or community mirrors. They are used only by DroidDesk's rooted
 * chroot path; non-root devices use the native Termux/TUR runtime instead.
 *
 * Download flow:
 *   1. User selects distro in Flutter wizard
 *   2. Download tarball (~300-500 MB compressed) with progress
 *   3. Extract to app's private storage (2-4 GB uncompressed)
 *   4. Configure user, sudo, apt sources
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val BUFFER_SIZE = 8192

        // Official rootfs download URLs (arm64)
        // Minimal rootfs tarballs reused as rooted chroot filesystems.
        val DISTRO_URLS = mapOf(
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz",
            "kali" to "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
        )

        val DISTRO_NAMES = mapOf(
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20",
            "kali" to "Kali Linux (NetHunter Minimal)"
        )

        // Pinned SHA-256 of each rootfs tarball. The extracted rootfs is executed
        // as root inside a chroot, so the download MUST be integrity-verified
        // before extraction. A mismatch means the artifact was tampered with
        // (MITM, hijacked/compromised mirror) and extraction is aborted.
        //
        // ubuntu/alpine point at fixed, versioned files, so their pins are stable.
        // kali points at a rolling "current" image; its pin is verified primarily
        // against the upstream SHA256SUMS (fetched over HTTPS), with the value
        // below as the last-known-good fallback.
        private val SHA256_PINS = mapOf(
            "ubuntu" to "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            "alpine" to "83a79199bf3112c65e62ddf1732b051daf62ed2ddf5a8413c440dc25956116f0",
            "kali" to "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e"
        )

        // Upstream SHA256SUMS manifests (HTTPS) used as the source of truth for
        // rolling releases when the hardcoded pin is stale.
        private val SHA256SUMS_URLS = mapOf(
            "ubuntu" to ("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS"
                to "ubuntu-base-24.04.4-base-arm64.tar.gz"),
            "alpine" to ("https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz.sha256"
                to "alpine-minirootfs-3.20.0-aarch64.tar.gz"),
            "kali" to ("https://kali.download/nethunter-images/current/rootfs/SHA256SUMS"
                to "kali-nethunter-rootfs-minimal-arm64.tar.xz")
        )
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val deConfigFile: File get() = File(baseDir, "de.conf")

    // ── Status ──

    fun getInstalledDistro(): String {
        return if (configFile.exists()) configFile.readText().trim() else ""
    }

    fun getInstalledDE(): String {
        return if (deConfigFile.exists()) deConfigFile.readText().trim() else ""
    }

    fun getMissingPackages(): List<String> {
        if (!isRootfsReady()) return listOf("rootfs")
        val de = getInstalledDE().ifEmpty { "xfce4" }
        val deBin = when (de) {
            "lxqt" -> "usr/bin/lxqt-session"
            "mate" -> "usr/bin/mate-session"
            "kde" -> "usr/bin/startplasma-x11"
            else -> "usr/bin/xfce4-session"
        }
        return if (File(rootfsDir, deBin).exists()) {
            emptyList()
        } else {
            listOf(de)
        }
    }

    fun getRootfsPath(): String {
        return rootfsDir.absolutePath
    }

    fun getRootfsSizeMB(): Long {
        return if (rootfsDir.exists()) {
            calculateDirSize(rootfsDir) / (1024 * 1024)
        } else 0
    }

    fun isRootfsReady(): Boolean {
        // Check for critical directories that indicate a valid rootfs
        return rootfsDir.exists() &&
                File(rootfsDir, "bin").exists() &&
                File(rootfsDir, "usr").exists() &&
                File(rootfsDir, "etc").exists()
    }

    // ── Download ──

    /**
     * Download a distro rootfs tarball with progress callbacks.
     * Runs on a background thread.
     */
    fun downloadRootfs(
        distro: String,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        thread(name = "rootfs-download") {
            try {
                val url = DISTRO_URLS[distro]
                    ?: throw IllegalArgumentException("Unknown distro: $distro")
                val distroName = DISTRO_NAMES[distro] ?: distro

                downloadDir.mkdirs()
                val targetFile = File(downloadDir, "${distro}-rootfs.tar." + (if (distro == "kali") "xz" else "gz"))

                onProgress(0.0, "Connecting to download server...")
                Log.i(TAG, "Downloading $distroName from $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                var downloadedBytes = 0L
                if (targetFile.exists()) {
                    downloadedBytes = targetFile.length()
                    connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                
                connection.requestMethod = "GET"

                // Handle redirects or Already Satisfied (416)
                if (connection.responseCode == 416) {
                    // Already fully downloaded — still verify integrity before trusting it.
                    onProgress(0.99, "Verifying $distroName...")
                    verifyChecksumOrThrow(distro, targetFile)
                    onProgress(1.0, "$distroName already downloaded")
                    configFile.writeText(distro)
                    return@thread
                }

                if (connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    connection.responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    val redirectUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    // Never follow a redirect that downgrades to cleartext HTTP; the
                    // rootfs runs as root, so an http:// target would allow trivial MITM.
                    if (redirectUrl == null || !redirectUrl.startsWith("https://")) {
                        throw SecurityException("Refusing insecure redirect to: $redirectUrl")
                    }
                    val newConnection = URL(redirectUrl).openConnection() as HttpURLConnection
                    if (downloadedBytes > 0) {
                        newConnection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                    }
                    downloadFromConnection(newConnection, targetFile, distroName, downloadedBytes, onProgress)
                } else {
                    // If server ignored Range header (returned 200 instead of 206), we must restart
                    if (connection.responseCode == 200 && downloadedBytes > 0) {
                        targetFile.delete()
                        downloadedBytes = 0L
                    }
                    downloadFromConnection(connection, targetFile, distroName, downloadedBytes, onProgress)
                }

                // Integrity gate: the tarball is about to be extracted and run as
                // root, so verify it before trusting it. A failure deletes the file
                // so the next attempt re-downloads from scratch.
                onProgress(0.99, "Verifying $distroName...")
                verifyChecksumOrThrow(distro, targetFile)

                // Save distro selection
                configFile.writeText(distro)

                onProgress(1.0, "$distroName downloaded successfully")
                Log.i(TAG, "Download complete and verified: ${targetFile.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                onProgress(-1.0, "Download failed: ${e.message}")
            }
        }
    }

    private fun downloadFromConnection(
        connection: HttpURLConnection,
        targetFile: File,
        distroName: String,
        initialDownloadedBytes: Long,
        onProgress: (Double, String) -> Unit
    ) {
        var downloadedBytes = initialDownloadedBytes
        val totalBytes = connection.contentLengthLong
        val expectedTotal = downloadedBytes + totalBytes

        if (totalBytes == 0L) {
            onProgress(1.0, "$distroName downloaded successfully")
            return
        }

        val buffer = ByteArray(BUFFER_SIZE)

        connection.inputStream.use { input ->
            FileOutputStream(targetFile, true).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (expectedTotal > 0) {
                        val progress = downloadedBytes.toDouble() / expectedTotal
                        val downloadedMB = downloadedBytes / (1024 * 1024)
                        val totalMB = expectedTotal / (1024 * 1024)
                        onProgress(
                            progress,
                            "Downloading $distroName: ${downloadedMB}MB / ${totalMB}MB"
                        )
                    }
                }
            }
        }
        connection.disconnect()
    }

    // ── Extraction ──

    /**
     * Extract the downloaded rootfs tarball.
     * Uses the system's tar command (available on Android via toybox).
     */
    fun extractRootfs(
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        thread(name = "rootfs-extract") {
            try {
                val distro = getInstalledDistro()
                val tarball = File(downloadDir, "${distro}-rootfs.tar." + (if (distro == "kali") "xz" else "gz"))

                if (!tarball.exists()) {
                    onProgress(-1.0, "Rootfs tarball not found. Download first.")
                    return@thread
                }

                // Clean previous rootfs
                if (rootfsDir.exists()) {
                    rootfsDir.deleteRecursively()
                }
                rootfsDir.mkdirs()

                onProgress(0.1, "Extracting ${DISTRO_NAMES[distro]}...")
                Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

                // Use ProcessBuilder to run tar extraction
                // Android's toybox includes tar, and we can use xz if available
                val ext = if (distro == "kali") "xz" else "gz"
                val tarFlags = if (ext == "xz") "Jxf" else "zxf"
                // --no-same-owner: do not honor archived uid/gid.
                // -P is intentionally NOT passed, so tar strips leading "/" and
                // refuses "../" members, preventing path-traversal outside rootfs.
                val process = ProcessBuilder(
                    "tar", tarFlags, tarball.absolutePath,
                    "--no-same-owner",
                    "-C", rootfsDir.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()

                // Read output for progress indication
                val reader = process.inputStream.bufferedReader()
                var line: String?
                var lineCount = 0
                var lastLine = ""
                while (reader.readLine().also { line = it } != null) {
                    lastLine = line!!
                    if (lineCount % 500 == 0) {
                        onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting: $line")
                    }
                    lineCount++
                }

                val exitCode = process.waitFor()
                val binDir = File(rootfsDir, "bin")
                
                // Android's tar will fail (code 1 or 2) when trying to chown files to root or create /dev nodes.
                // We ignore this as long as the core filesystem extracted successfully.
                if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true)) {
                    throw RuntimeException("tar failed (code $exitCode): $lastLine")
                }

                onProgress(0.7, "Configuring Linux environment...")

                // Post-extraction configuration
                configureRootfs()

                // Mark setup as completely successful
                File(context.filesDir, "SETUP_COMPLETE").writeText("done")

                // Clean up tarball to save space
                tarball.delete()

                onProgress(1.0, "${DISTRO_NAMES[distro] ?: distro} setup complete")
                Log.i(TAG, "Rootfs extraction complete. Size: ${getRootfsSizeMB()} MB")

            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed: ${e.message}", e)
                onProgress(-1.0, "Extraction failed: ${e.message}")
            }
        }
    }

    // ── Post-install Configuration ──
    /**
     * Configure the extracted rootfs with essential settings.
     * Sets up user, sudo, apt sources, DNS, and environment.
     */
    private fun configureRootfs() {
        Log.i(TAG, "Configuring rootfs...")

        // DNS resolution
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("""
                nameserver 8.8.8.8
                nameserver 8.8.4.4
                nameserver 1.1.1.1
            """.trimIndent())
        }

        // Disable apt sandbox (CRITICAL for Android)
        // If apt drops to the _apt user, it loses the Android 'inet' group and hangs indefinitely without network access.
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")

        // Hostname
        File(rootfsDir, "etc/hostname").writeText("droiddesk\n")

        // Hosts file
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("""
                127.0.0.1   localhost
                127.0.0.1   droiddesk
                ::1         localhost ip6-localhost ip6-loopback
            """.trimIndent())
        }

        // Environment variables for the Linux session
        File(rootfsDir, "etc/profile.d/droiddesk.sh").apply {
            parentFile?.mkdirs()
            writeText("""
                #!/bin/bash
                # DroidDesk environment configuration
                export DISPLAY=:0
                export PULSE_SERVER=127.0.0.1
                export MESA_NO_ERROR=1
                export MESA_GL_VERSION_OVERRIDE=4.6
                export MESA_GLES_VERSION_OVERRIDE=3.2
                export GALLIUM_DRIVER=zink
                export MESA_LOADER_DRIVER_OVERRIDE=zink
                export TU_DEBUG=noconform
                export ZINK_DESCRIPTORS=lazy
                export MESA_VK_WSI_PRESENT_MODE=immediate
                
                # XDG directories
                export XDG_RUNTIME_DIR=/tmp/runtime-root
                mkdir -p "${'$'}XDG_RUNTIME_DIR" 2>/dev/null
                
                # Color prompt
                export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                
                alias ll='ls -la --color=auto'
                alias update='apt update && apt upgrade -y'
            """.trimIndent())
        }

        // Sudo configuration (passwordless for root)
        File(rootfsDir, "etc/sudoers.d/droiddesk").apply {
            parentFile?.mkdirs()
            writeText("""
                Defaults !requiretty
                root ALL=(ALL) NOPASSWD: ALL
            """.trimIndent())
        }

        // Create required directories
        listOf(
            "tmp",
            "tmp/runtime-root",
            "run",
            "var/run",
            "dev/shm"
        ).forEach {
            File(rootfsDir, it).mkdirs()
        }

        Log.i(TAG, "Rootfs configuration complete")
    }

    // ── Desktop Environment Installation ──

    /**
     * Install a desktop environment inside the rootfs using apt.
     * Called after rootfs extraction.
     */
    fun installDesktopEnvironment(
        de: String,
        runtime: LinuxRuntime,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit
    ) {
        thread(name = "de-install") {
            try {
                // Forcefully release any stuck apt locks before starting
                onProgress(0.0, "Clearing package manager locks...")
                try {
                    runtime.executeCommand("""
                        killall -9 apt apt-get dpkg 2>/dev/null || true
                        rm -f /var/lib/apt/lists/lock
                        rm -f /var/cache/apt/archives/lock
                        rm -f /var/lib/dpkg/lock*
                        dpkg --configure -a
                    """.trimIndent(), onLog)
                } catch (e: Exception) {
                    Log.w(TAG, "Lock clearing failed, continuing anyway: ${e.message}")
                }
                
                onProgress(0.0, "Updating package lists...")
                runtime.executeCommand("apt-get update -y", onLog)
                
                // Pre-configure Firefox PPA to fix Ubuntu snap issue
                onProgress(0.1, "Configuring repositories...")
                runtime.executeCommand("""
                    DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y software-properties-common wget gpg
                    add-apt-repository ppa:mozillateam/ppa -y
                    echo "Package: *" > /etc/apt/preferences.d/mozilla-firefox
                    echo "Pin: release o=LP-PPA-mozillateam" >> /etc/apt/preferences.d/mozilla-firefox
                    echo "Pin-Priority: 1001" >> /etc/apt/preferences.d/mozilla-firefox
                    
                    # Add Microsoft repository for VS Code
                    mkdir -p /etc/apt/keyrings
                    wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > packages.microsoft.gpg
                    install -D -o root -g root -m 644 packages.microsoft.gpg /etc/apt/keyrings/packages.microsoft.gpg
                    echo "deb [arch=amd64,arm64,armhf signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main" > /etc/apt/sources.list.d/vscode.list
                    rm -f packages.microsoft.gpg
                    
                    apt-get update -y
                """.trimIndent(), onLog)

                var packages = when (de) {
                    "xfce4" -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad dbus-x11"
                    "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad dbus-x11"
                    "mate" -> "mate-desktop-environment mate-terminal dbus-x11"
                    "kde" -> "plasma-desktop konsole dolphin dbus-x11"
                    else -> "xfce4 xfce4-terminal dbus-x11"
                }
                
                // Fix broken dependencies from interrupted apt installs
                runtime.executeCommand("DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -f -y", onLog)

                onProgress(0.2, "Installing $de packages...")
                runtime.executeCommand(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $packages", onLog
                )

                onProgress(0.8, "Installing core utilities...")
                val result = runtime.executeCommand(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                    "git wget curl python3 python3-pip htop nano sudo libgl1 x11-xserver-utils", onLog
                )

                onProgress(0.9, "Configuring embedded X11 display...")
                
                if (result.contains("E: ")) {
                    throw Exception("Apt-get failed: Check terminal output.")
                }

                deConfigFile.writeText(de)
                onProgress(1.0, "$de installation complete!")

            } catch (e: Exception) {
                Log.e(TAG, "DE installation failed: ${e.message}", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    // ── Integrity verification ──

    /**
     * Verify the downloaded tarball against a pinned SHA-256 (and, for rolling
     * releases, the upstream SHA256SUMS manifest). Throws SecurityException and
     * deletes the file on any mismatch — the artifact must never be extracted
     * unverified because it is executed as root.
     */
    private fun verifyChecksumOrThrow(distro: String, file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SecurityException("Downloaded rootfs is missing or empty")
        }

        val actual = sha256Of(file)
        val pinned = SHA256_PINS[distro]

        if (pinned != null && actual.equals(pinned, ignoreCase = true)) {
            Log.i(TAG, "Checksum OK (pinned) for $distro")
            return
        }

        // Pin missing or stale (e.g. rolling "current" image) — fall back to the
        // upstream HTTPS manifest as the source of truth.
        val upstream = fetchUpstreamSha256(distro)
        if (upstream != null && actual.equals(upstream, ignoreCase = true)) {
            Log.w(TAG, "Checksum OK (upstream manifest) for $distro; hardcoded pin is stale")
            return
        }

        file.delete()
        throw SecurityException(
            "Rootfs checksum verification FAILED for $distro. " +
                "Expected ${pinned ?: upstream ?: "<unknown>"} but got $actual. " +
                "The download was corrupted or tampered with and has been discarded."
        )
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Fetch the expected hash for [distro] from its upstream SHA256SUMS manifest
     * over HTTPS. Returns null if unavailable. HTTPS-only; never follows a
     * cleartext redirect.
     */
    private fun fetchUpstreamSha256(distro: String): String? {
        val (sumsUrl, fileName) = SHA256SUMS_URLS[distro] ?: return null
        return runCatching {
            if (!sumsUrl.startsWith("https://")) return null
            val conn = URL(sumsUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // Manifest lines look like: "<hash>  <filename>" (or a bare hash for
            // single-file .sha256 manifests).
            body.lineSequence()
                .map { it.trim() }
                .firstNotNullOfOrNull { line ->
                    val hash = line.substringBefore(' ').substringBefore('\t').removePrefix("*")
                    when {
                        line.contains(fileName) -> line.substringBefore(' ').trim().removePrefix("*")
                        line.isNotEmpty() && !line.contains(' ') && hash.length == 64 -> hash
                        else -> null
                    }
                }
        }.getOrNull()
    }

    // ── Utility ──

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
