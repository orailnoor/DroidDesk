package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Core Linux runtime engine.
 *
 * Runs native Termux Linux on Android without PRoot.
 * Uses a C hook (libsocket_hook.so) via LD_PRELOAD to redirect file operations
 * and Unix socket connections from /data/data/com.termux/files/usr to DroidDesk's prefix.
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".bootstrap_extracted"
        private const val SHEBANG_MARKER = ".relocated_text_paths_v3"
        private const val ELF_PATCH_MARKER = ".elf_runpaths_patched"
        private const val DE_MARKER = ".de_installed"

        // ELF64 constants
        private const val ELFMAG0: Byte = 0x7f
        private const val ELFMAG1: Byte = 'E'.code.toByte()
        private const val ELFMAG2: Byte = 'L'.code.toByte()
        private const val ELFMAG3: Byte = 'F'.code.toByte()
        private const val ELFCLASS64: Byte = 2
        private const val ELFDATA2LSB: Byte = 1

        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2

        private const val DT_NULL = 0L
        private const val DT_STRTAB = 5L
        private const val DT_STRSZ = 10L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L

        private const val EI_MAG0 = 0
        private const val EI_MAG1 = 1
        private const val EI_MAG2 = 2
        private const val EI_MAG3 = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5

        private const val E_PHOFF_OFFSET = 32
        private const val E_PHENTSIZE_OFFSET = 54
        private const val E_PHNUM_OFFSET = 56

        private const val P_TYPE_OFFSET = 0
        private const val P_OFFSET_OFFSET = 8
        private const val P_VADDR_OFFSET = 16
        private const val P_FILESZ_OFFSET = 32
        private const val PH_SIZE = 56

        private const val D_TAG_OFFSET = 0
        private const val D_VAL_OFFSET = 8
        private const val DYN_SIZE = 16

        // The desktop is launched from DesktopActivity but queried/stopped from
        // MainActivity. Keep process ownership application-wide so both runtime
        // wrappers operate on the same session.
        @Volatile private var sessionProcess: Process? = null
        @Volatile private var dbusProcess: Process? = null
    }

    @Volatile private var activeCommandProcess: Process? = null
    @Volatile private var installLogSink: ((String) -> Unit)? = null

    fun setInstallLogSink(sink: ((String) -> Unit)?) {
        installLogSink = sink
    }

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val libDir: File get() = File(prefixDir, "lib")
    // Shared with X11ServerService. X clients resolve /tmp/.X11-unix/X0 here
    // through libsocket_hook, so this must not be $PREFIX/tmp.
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(baseDir, "home")

    /** Qualcomm exposes the Adreno render device through KGSL on Android. */
    private fun hasAdrenoGpu(): Boolean = File("/dev/kgsl-3d0").exists()

    private fun normalizedDesktop(desktopEnv: String): String = when (desktopEnv.lowercase()) {
        "lxqt", "mate", "kde", "xfce4" -> desktopEnv.lowercase()
        else -> "xfce4"
    }

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, BOOTSTRAP_MARKER).exists() && File(prefixDir, "bin/bash").exists()
    }

    fun isRunning(): Boolean {
        return sessionProcess?.isAlive == true
    }

    fun getInstalledDE(): String {
        val marker = File(prefixDir, DE_MARKER)
        if (marker.exists()) return marker.readText().trim().ifEmpty { "xfce4" }
        // Individual desktop binaries may already exist after a partially failed
        // package transaction. Only the marker written at the end of the complete
        // setup flow means the runtime is ready to launch.
        return ""
    }

    fun getGraphicsMode(): String {
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            "Turnip + Zink"
        } else {
            "Software (llvmpipe)"
        }
    }

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(binDir, "firefox").exists(),
        "code_oss" to (File(binDir, "code-oss").exists() || File(binDir, "code").exists()),
        "nodejs" to (File(binDir, "node").exists() && File(binDir, "npm").exists()),
        "imagemagick" to (File(binDir, "magick").exists() || File(binDir, "convert").exists()),
        "proot_debian" to isMinimalDebianInstalled(),
    )

    private fun isMinimalDebianInstalled(): Boolean {
        val installed = debianRootfsMarkers().any(File::exists) &&
            File(binDir, "start-debian").exists()
        if (installed) {
            relocateProotExecutable()
            writeDebianLauncher()
            clearProotDownloadCache()
        }
        return installed
    }

    private fun relocateProotExecutable() {
        val proot = File(binDir, "proot")
        if (!proot.isFile) return
        patchElfFile(
            proot,
            "/data/data/com.termux/files/usr/lib",
            File(prefixDir, "lib").absolutePath,
            "/data/data/com.termux/files/usr/lib/dri",
        )
    }

    private fun writeDebianLauncher() {
        val launcher = File(binDir, "start-debian")
        launcher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                env DISPLAY="${'$'}DISPLAY" TERM="${'$'}{TERM:-xterm-256color}" bash -l
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val appsLauncher = File(binDir, "debian-apps")
        appsLauncher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            mode="${'$'}{1:-gui}"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                bash -s -- "${'$'}mode" <<'DROIDDESK_DEBIAN_APPS'
            mode="${'$'}1"
            case "${'$'}mode" in
                --all)
                    dpkg-query -W -f='${'$'}{binary:Package}\t${'$'}{Version}\n' | sort
                    ;;
                --manual)
                    apt-mark showmanual | sort
                    ;;
                gui)
                    for desktop in \
                        /usr/share/applications/*.desktop \
                        /usr/local/share/applications/*.desktop
                    do
                        [ -f "${'$'}desktop" ] || continue
                        no_display=${'$'}(sed -n 's/^NoDisplay=//p' "${'$'}desktop" | head -n 1)
                        [ "${'$'}no_display" = "true" ] && continue
                        name=${'$'}(sed -n 's/^Name=//p' "${'$'}desktop" | head -n 1)
                        command=${'$'}(sed -n 's/^Exec=//p' "${'$'}desktop" | head -n 1)
                        [ -n "${'$'}name" ] && [ -n "${'$'}command" ] &&
                            printf '%-32s %s\n' "${'$'}name" "${'$'}command"
                    done | sort -f
                    ;;
                *)
                    echo "Usage: debian-apps [--manual|--all]" >&2
                    exit 2
                    ;;
            esac
            DROIDDESK_DEBIAN_APPS
            """.trimIndent() + "\n",
        )
        appsLauncher.setExecutable(true, false)
    }

    private fun clearProotDownloadCache() {
        File(prefixDir, "var/lib/proot-distro/dlcache").deleteRecursively()
        File(prefixDir, "var/lib/proot-distro/cache").deleteRecursively()
    }

    private fun debianRootfsMarkers(): List<File> = listOf(
        // proot-distro 5.4+
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/usr/lib/os-release"),
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release"),
        // Legacy proot-distro releases
        File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian/etc/os-release"),
    )

    // ── Bootstrap ──

    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")
        listOf(prefixDir, binDir, libDir, tmpDir, homeDir).forEach { it.mkdirs() }
        Log.i(TAG, "Bootstrap directories ready. Base: ${baseDir.absolutePath}")
    }

    fun extractBootstrapIfNeeded(context: Context) {
        val bashBin = File(prefixDir, "bin/bash")
        if (bashBin.exists()) {
            Log.i(TAG, "Bootstrap already extracted at ${prefixDir.absolutePath}")
            // Refresh wrapper/config in case the app was updated
            createAptConfigOverride()
            ensureAptDirectories()
            wrapDpkgForPath()
            wrapUpdateAlternatives()
            ensureSocketHookPrebuilt()
            return
        }

        val marker = File(baseDir, BOOTSTRAP_MARKER)
        if (marker.exists()) {
            marker.delete()
        }

        Log.i(TAG, "Extracting bootstrap from assets to ${prefixDir.absolutePath}...")

        // Remove any partial extraction to ensure a clean bootstrap
        if (prefixDir.exists()) {
            prefixDir.deleteRecursively()
        }
        prefixDir.mkdirs()

        // Flutter assets are located under "flutter_assets/" in the APK asset tree
        val assetName = "flutter_assets/assets/bootstrap-aarch64.zip"
        val tmpZip = File(tmpDir, "bootstrap-aarch64.zip")
        tmpZip.parentFile?.mkdirs()

        try {
            context.assets.open(assetName).use { input ->
                tmpZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bootstrap asset: ${e.message}", e)
            return
        }

        // Extract the bootstrap so the top-level bin/lib/etc directories land under prefixDir/usr
        extractZip(tmpZip, prefixDir)

        // Restore symlinks from SYMLINKS.txt
        restoreSymlinks(prefixDir)

        // Create apt config override so apt uses our prefix
        createAptConfigOverride()

        // Ensure apt cache/state directories exist (bootstrap zip may omit empty dirs)
        ensureAptDirectories()

        // Set executable permissions on binaries
        setExecutableRecursively(binDir)
        setExecutableRecursively(File(prefixDir, "libexec"))

        // Wrap dpkg so it and its children (e.g. dpkg-split) always see the right PATH/env
        wrapDpkgForPath()

        // Wrap update-alternatives so its postinst invocations don't fail under
        // our relocated root (it otherwise double-prefixes absolute paths).
        wrapUpdateAlternatives()

        // Copy prebuilt socket hook from jniLibs to prefix/lib
        ensureSocketHookPrebuilt()

        marker.writeText("DroidDesk native bootstrap\n")
        tmpZip.delete()
        Log.i(TAG, "Bootstrap extraction complete")
    }

    private fun ensureSocketHookPrebuilt() {
        try {
            val libDir = File(prefixDir, "lib")
            libDir.mkdirs()
            val destHook = File(libDir, "libsocket_hook.so")
            if (destHook.exists()) return

            // Find the hook in jniLibs
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val jniDir = File(context.applicationInfo.nativeLibraryDir)
            val srcHook = File(jniDir, "libsocket_hook.so")
            if (srcHook.exists()) {
                srcHook.inputStream().use { input ->
                    destHook.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destHook.setExecutable(true, false)
                Log.i(TAG, "Copied prebuilt socket hook to ${destHook.absolutePath}")
            } else {
                Log.w(TAG, "Prebuilt socket hook not found in jniLibs at ${srcHook.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy prebuilt socket hook: ${e.message}")
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val destRoot = destDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Zip-slip guard: reject any entry that resolves outside destDir.
                if (!outFile.canonicalPath.startsWith(destRoot)) {
                    throw SecurityException("Blocked path traversal in zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun createAptConfigOverride() {
        try {
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val confFile = File(aptConfDir, "99-droiddesk-paths.conf")
            confFile.writeText(
                """
                Dir "${prefixDir.absolutePath}";
                Dir::Etc "${prefixDir.absolutePath}/etc/apt";
                Dir::State "${prefixDir.absolutePath}/var/lib/apt";
                Dir::State::dpkg "${prefixDir.absolutePath}/var/lib/dpkg";
                Dir::Cache "${prefixDir.absolutePath}/var/cache/apt";
                Dir::Log "${prefixDir.absolutePath}/var/log/apt";
                Dir::Bin::Methods "${prefixDir.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefixDir.absolutePath}/bin/dpkg";
                Dir::Bin::apt-key "${prefixDir.absolutePath}/bin/apt-key";
                Acquire::gpgv::Options:: "--homedir=${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                Acquire::Retries "3";
                Acquire::http::Timeout "30";
                Acquire::https::Timeout "30";
                DPkg::Lock::Timeout "60";
                """.trimIndent()
            )
            Log.i(TAG, "Created apt config override at ${confFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create apt config override: ${e.message}")
        }
    }

    private fun wrapDpkgForPath() {
        try {
            val dpkgBin = File(prefixDir, "bin/dpkg")
            val dpkgReal = File(prefixDir, "bin/dpkg.real")
            val relocateShebangs = File(prefixDir, "bin/droiddesk-relocate-shebangs")

            // Build a mini root tree so dpkg can use Termux-style paths internally
            // while the actual files land in our private prefix.
            val dpkgRoot = File(baseDir, "dpkgroot")
            mapOf(
                File(dpkgRoot, "data/data/com.termux/files/usr") to prefixDir.absolutePath,
                File(dpkgRoot, "var/lib/dpkg") to File(prefixDir, "var/lib/dpkg").absolutePath,
                File(dpkgRoot, "tmp") to tmpDir.absolutePath
            ).forEach { (linkDir, target) ->
                linkDir.parentFile?.mkdirs()
                // Remove an existing symlink so we can update its target on app upgrades.
                if (linkDir.exists()) {
                    linkDir.delete()
                }
                if (!linkDir.exists()) {
                    android.system.Os.symlink(target, linkDir.absolutePath)
                }
            }

            // Make sure the real dpkg binary is saved as dpkg.real, then always
            // rewrite the wrapper so updates take effect on app upgrade/reinstall.
            if (!dpkgReal.exists() && dpkgBin.exists()) {
                dpkgBin.renameTo(dpkgReal)
            }

            // dpkg enumerates its compile-time configuration directory through
            // libc APIs that are not consistently intercepted by the path hook
            // on every Android build. Point that one embedded path at the
            // wrapper's working directory instead.
            patchEmbeddedCommand(
                dpkgReal,
                "/data/data/com.termux/files/usr/etc/dpkg",
                "/proc/self/cwd/etc/dpkg",
            )

            // Package installations started inside the XFCE terminal bypass the
            // Kotlin installation flow. Run this after every dpkg transaction so
            // newly unpacked commands and maintainer scripts never retain
            // Termux's original, inaccessible interpreter prefix.
            relocateShebangs.writeText(
                """
                #!/system/bin/sh
                old_prefix="/data/data/com.termux/files/usr"
                new_prefix="${prefixDir.absolutePath}"
                scan_marker="${'$'}1"
                [ -f "${'$'}scan_marker" ] || exit 0
                for root in \
                    "${prefixDir.absolutePath}/bin" \
                    "${prefixDir.absolutePath}/libexec" \
                    "${prefixDir.absolutePath}/var/lib/dpkg/info"
                do
                    [ -d "${'$'}root" ] || continue
                    "${prefixDir.absolutePath}/bin/find" "${'$'}root" \
                        -type f -cnewer "${'$'}scan_marker" 2>/dev/null |
                    while IFS= read -r file; do
                        first_line=$("${prefixDir.absolutePath}/bin/head" -n 1 "${'$'}file" 2>/dev/null)
                        case "${'$'}first_line" in
                            "#!${'$'}old_prefix"*)
                                "${prefixDir.absolutePath}/bin/sed" -i \
                                    "1s|${'$'}old_prefix|${'$'}new_prefix|" "${'$'}file"
                                ;;
                        esac
                    done
                done
                exit 0
                """.trimIndent() + "\n",
            )
            relocateShebangs.setExecutable(true, false)

            val wrapper = """
                #!/system/bin/sh
                export PATH="${prefixDir.absolutePath}/bin:${'$'}PATH"
                export LD_LIBRARY_PATH="${prefixDir.absolutePath}/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
                export LD_PRELOAD="${prefixDir.absolutePath}/lib/libsocket_hook.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
                # dpkg requires admindir to be inside root. Strip any caller-provided
                # --root/--admindir (and their values) and prepend our own before any
                # trailing filenames/apt separators so dpkg parses them as options.
                caller_dir="${'$'}PWD"
                args=""
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --admindir=*|--root=*)
                            ;;
                        --admindir|--root)
                            shift
                            ;;
                        *)
                            arg="${'$'}1"
                            case "${'$'}arg" in
                                /*)
                                    ;;
                                *)
                                    if [ -e "${'$'}caller_dir/${'$'}arg" ]; then
                                        arg="${'$'}caller_dir/${'$'}arg"
                                    fi
                                    ;;
                            esac
                            args="${'$'}args ${'$'}arg"
                            ;;
                    esac
                    shift
                done
                cd "${prefixDir.absolutePath}" || exit 1
                scan_marker="${tmpDir.absolutePath}/dpkg-shebang-scan-${'$'}${'$'}"
                : > "${'$'}scan_marker"
                "${dpkgReal.absolutePath}" --force-not-root --force-script-chrootless --root="${dpkgRoot.absolutePath}" --admindir="${dpkgRoot.absolutePath}/var/lib/dpkg" ${'$'}args
                status=${'$'}?
                "${relocateShebangs.absolutePath}" "${'$'}scan_marker"
                rm -f "${'$'}scan_marker"
                exit ${'$'}status
            """.trimIndent()

            dpkgBin.writeText(wrapper)
            dpkgBin.setExecutable(true, false)
            Log.i(TAG, "Installed dpkg wrapper at ${dpkgBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dpkg wrapper: ${e.message}")
        }
    }

    private fun wrapUpdateAlternatives() {
        try {
            val uaBin = File(prefixDir, "bin/update-alternatives")
            val uaReal = File(prefixDir, "bin/update-alternatives.real")

            if (!uaBin.exists() || uaReal.exists()) return

            uaBin.renameTo(uaReal)
            uaBin.writeText(
                """
                #!/system/bin/sh
                # update-alternatives is not needed for DroidDesk's single-prefix
                # environment and fails when dpkg is run with a relocated root.
                exit 0
                """.trimIndent()
            )
            uaBin.setExecutable(true, false)
            Log.i(TAG, "Installed update-alternatives wrapper at ${uaBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update-alternatives wrapper: ${e.message}")
        }
    }

    private fun ensureAptDirectories() {
        listOf(
            "etc/dpkg/dpkg.cfg.d",
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { relativePath ->
            File(prefixDir, relativePath).mkdirs()
        }
        // dpkg requires these files to exist even if empty
        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions"
        ).forEach { relativePath ->
            val f = File(prefixDir, relativePath)
            if (!f.exists()) f.createNewFile()
        }
        Log.i(TAG, "Ensured apt/dpkg cache/state directories exist")
    }

    private fun setExecutableRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun restoreSymlinks(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping symlink restoration")
            return
        }

        val termuxPrefix = "/data/data/com.termux/files/usr"
        var created = 0
        var failed = 0
        symlinksFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Format: /data/data/com.termux/files/usr/.../target←./linkpath
            val arrow = "\u2190"
            val parts = trimmed.split(arrow)
            if (parts.size != 2) return@forEach

            val targetPath = parts[0].trim()
            val linkPath = parts[1].trim()

            // linkPath is relative to prefix root (starts with ./)
            val cleanLinkPath = if (linkPath.startsWith("./")) linkPath.substring(2) else linkPath
            val linkFile = File(prefixDir, cleanLinkPath)

            // Rewrite target from Termux prefix to app prefix;
            // bare filenames are kept relative so the symlink resolves correctly.
            val newTarget = when {
                targetPath.startsWith(termuxPrefix) -> {
                    prefixDir.absolutePath + targetPath.substring(termuxPrefix.length)
                }
                targetPath.startsWith("/") -> targetPath
                else -> targetPath
            }

            try {
                if (linkFile.exists()) {
                    linkFile.deleteRecursively()
                }
                linkFile.parentFile?.mkdirs()
                android.system.Os.symlink(newTarget, linkFile.absolutePath)
                created++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $newTarget: ${e.message}")
            }
        }

        Log.i(TAG, "Restored $created symlinks, $failed failed")
    }

    // ── Shebang Patching ──
    fun patchShebangs(force: Boolean = false) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        if (oldPrefix == newPrefix) return

        val markerFile = File(prefixDir, SHEBANG_MARKER)
        if (!force && markerFile.exists()) {
            Log.i(TAG, "Shebangs already patched, skipping")
            return
        }

        Log.i(TAG, "Patching shebangs: $oldPrefix -> $newPrefix")
        var patchCount = 0

        val dirsToScan = listOf("bin", "libexec", "share", "etc", "var/lib/dpkg/info")
        for (dirName in dirsToScan) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists()) continue

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val bytes = file.inputStream().use {
                            val buf = ByteArray(256)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }

                        val isScript = bytes.size >= 2 &&
                            bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
                        val isPathConfig = file.extension.lowercase() in setOf(
                            "service", "desktop", "conf", "xml", "pc", "cmake",
                            "la", "prl", "sh", "pl", "py", "rb", "json", "ini",
                        )
                        if (isScript || isPathConfig) {
                            val content = file.readText()
                            if (content.contains(oldPrefix)) {
                                val updated = content.replace(oldPrefix, newPrefix)
                                file.writeText(updated)
                                patchCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore binary or read-only files
                    }
                }
            }
        }
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    /** Relocate commands that Xfce compiled as absolute Termux paths. */
    private fun patchEmbeddedXfcePaths() {
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-session"),
            "/data/data/com.termux/files/usr/bin/iceauth",
            "iceauth",
        )
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-panel"),
            "/data/data/com.termux/files/usr/lib/xfce4/panel/migrate",
            "migrate",
        )
    }

    private fun patchEmbeddedCommand(file: File, oldCommand: String, newCommand: String) {
        if (!file.exists() || newCommand.length > oldCommand.length) return
        val oldValue = oldCommand.toByteArray()
        val newValue = newCommand.toByteArray()
        val bytes = file.readBytes()
        var patched = false
        var offset = 0
        while (offset <= bytes.size - oldValue.size) {
            var matches = true
            for (index in oldValue.indices) {
                if (bytes[offset + index] != oldValue[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                bytes.fill(0, offset, offset + oldValue.size)
                newValue.copyInto(bytes, offset)
                patched = true
                offset += oldValue.size
            } else {
                offset++
            }
        }
        if (patched) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            Log.i(TAG, "Relocated embedded command in ${file.name}: $newCommand")
        }
    }

    // ── ELF RUNPATH/RPATH Patching ──

    fun patchElfRunpaths(prefixDir: File) {
        val marker = File(prefixDir, ELF_PATCH_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "ELF runpaths already patched, skipping")
            return
        }

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) {
            Log.w(TAG, "lib directory not found, skipping ELF patch")
            return
        }

        val oldPath = "/data/data/com.termux/files/usr/lib"
        val newPath = libDir.absolutePath
        val driOldPath = "/data/data/com.termux/files/usr/lib/dri"

        var patched = 0
        libDir.walkTopDown()
            .filter { it.isFile && it.canRead() && isElf64(it) }
            .forEach { file ->
                try {
                    if (patchElfFile(file, oldPath, newPath, driOldPath)) {
                        patched++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch ${file.absolutePath}: ${e.message}")
                }
            }

        marker.writeText("patched $patched files")
        Log.i(TAG, "Patched ELF runpaths in $patched files")
    }

    private fun isElf64(file: File): Boolean {
        if (file.length() < 6) return false
        return file.inputStream().use { input ->
            val buf = ByteArray(6)
            val n = input.read(buf)
            if (n < 6) return@use false
            buf[EI_MAG0] == ELFMAG0 &&
                    buf[EI_MAG1] == ELFMAG1 &&
                    buf[EI_MAG2] == ELFMAG2 &&
                    buf[EI_MAG3] == ELFMAG3 &&
                    buf[EI_CLASS] == ELFCLASS64 &&
                    buf[EI_DATA] == ELFDATA2LSB
        }
    }

    private fun patchElfFile(file: File, oldPath: String, newPath: String, driOldPath: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 64) return false

        val ePhoff = getLongLe(bytes, E_PHOFF_OFFSET)
        val ePhentsize = getShortLe(bytes, E_PHENTSIZE_OFFSET).toInt() and 0xFFFF
        val ePhnum = getShortLe(bytes, E_PHNUM_OFFSET).toInt() and 0xFFFF

        if (ePhoff < 0 || ePhoff + ePhnum * ePhentsize.toLong() > bytes.size) return false

        var strTabAddr: Long? = null
        var strTabSize: Long? = null
        val runpathOffsets = mutableListOf<Long>()
        val rpathOffsets = mutableListOf<Long>()

        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_DYNAMIC) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (pOffset < 0 || pOffset + pFilesz > bytes.size) continue

                val dynCount = pFilesz / DYN_SIZE
                for (j in 0 until dynCount) {
                    val dynOffset = (pOffset + j * DYN_SIZE).toInt()
                    val dTag = getLongLe(bytes, dynOffset + D_TAG_OFFSET)
                    val dVal = getLongLe(bytes, dynOffset + D_VAL_OFFSET)
                    when (dTag) {
                        DT_STRTAB -> strTabAddr = dVal
                        DT_STRSZ -> strTabSize = dVal
                        DT_RPATH -> rpathOffsets.add(dVal)
                        DT_RUNPATH -> runpathOffsets.add(dVal)
                        DT_NULL -> break
                    }
                }
            }
        }

        if (strTabAddr == null || strTabSize == null || strTabSize <= 0) return false

        var strTabFileOffset: Long? = null
        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_LOAD) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pVaddr = getLongLe(bytes, phOffset + P_VADDR_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (strTabAddr >= pVaddr && strTabAddr < pVaddr + pFilesz) {
                    strTabFileOffset = pOffset + (strTabAddr - pVaddr)
                    break
                }
            }
        }

        if (strTabFileOffset == null) return false

        val origin = "\${ORIGIN}"
        val relativeLib = "\${ORIGIN}/../lib"
        val replacement = when {
            newPath.length <= oldPath.length -> newPath
            file.parentFile?.absolutePath == newPath -> origin
            else -> relativeLib
        }
        var modified = false

        for (offset in runpathOffsets + rpathOffsets) {
            val fileOffset = (strTabFileOffset + offset).toInt()
            if (fileOffset < 0 || fileOffset >= bytes.size) continue
            val endOffset = minOf((strTabFileOffset + strTabSize).toInt(), bytes.size)
            val current = readNullTerminatedString(bytes, fileOffset, endOffset)

            when {
                current == driOldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, origin, current.length)
                    modified = true
                }
                current == oldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, replacement, current.length)
                    modified = true
                }
                current.contains(driOldPath) -> {
                    val replaced = current.replace(driOldPath, origin)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
                current.contains(oldPath) -> {
                    val replaced = current.replace(oldPath, replacement)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            return true
        }
        return false
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, end: Int): String {
        var i = start
        while (i < end && bytes[i] != 0.toByte()) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun writeNullTerminatedString(bytes: ByteArray, offset: Int, value: String, padTo: Int) {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        for (i in valueBytes.indices) {
            bytes[offset + i] = valueBytes[i]
        }
        for (i in valueBytes.size until padTo) {
            bytes[offset + i] = 0
        }
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun getIntLe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun getShortLe(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() and 0xFF) shl 8 or (bytes[offset].toInt() and 0xFF)).toShort()
    }

    // ── C Socket Hook Native Compilation ──
    fun compileSocketHook() {
        val hookC = File(tmpDir, "socket_hook.c")
        val hookBuildC = File(tmpDir, "socket_hook_build.c")
        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        File(prefixDir, "lib").mkdirs()

        try {
            context.assets.open("flutter_assets/assets/socket_hook.c").use { input ->
                hookC.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket_hook.c asset: ${e.message}")
            return
        }

        hookBuildC.writeText(
            """
            #define _GNU_SOURCE
            #define NEW_PREFIX "${prefixDir.absolutePath}"
            #include "${hookC.absolutePath}"
            """.trimIndent()
        )

        val clang = File(prefixDir, "bin/clang")
        if (clang.exists()) {
            Log.i(TAG, "Compiling socket_hook.c natively using clang...")
            val compileCmd = listOf(
                clang.absolutePath,
                "-shared", "-fPIC",
                hookBuildC.absolutePath,
                "-I", tmpDir.absolutePath,
                "-o", hookSo.absolutePath,
                "-ldl", "-llog"
            )
            try {
                val pb = ProcessBuilder(compileCmd)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        it.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                        it.environment()["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
                        it.environment()["TMPDIR"] = tmpDir.absolutePath
                    }
                val process = pb.start()
                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "Native compilation of libsocket_hook.so successful!")
                } else {
                    Log.e(TAG, "Native compilation failed (code $exitCode): $log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compiling natively: ${e.message}")
            }
        } else {
            Log.w(TAG, "clang binary not found yet. Cannot compile socket_hook.c.")
        }
    }

    // ── Environment Configuration ──

    private fun getTermuxEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["EXTERNAL_STORAGE"] = System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"

        env["PREFIX"] = prefixDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        // proot-distro 5.x derives all container paths from these variables.
        // Without them it falls back to Termux's original com.termux sandbox.
        env["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        env["TERMUX__PREFIX"] = prefixDir.absolutePath
        env["TERMUX__HOME"] = homeDir.absolutePath
        env["TERMUX_VERSION"] = "DroidDesk"
        env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
        env["PATH"] = listOf(
            "${prefixDir.absolutePath}/bin",
            "${prefixDir.absolutePath}/lib/xfce4/panel",
            System.getenv("PATH") ?: "/system/bin",
        ).joinToString(":")
        env["HOME"] = homeDir.absolutePath
        // VTE-based terminals use SHELL to spawn their child process. Android's
        // account database points at /system/bin/sh, which is not the relocated
        // Termux userspace expected by XFCE Terminal.
        env["SHELL"] = File(binDir, "bash").absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"

        env["DISPLAY"] = ":0"
        env["XDG_RUNTIME_DIR"] = tmpDir.absolutePath

        env["PERL5LIB"] = "${prefixDir.absolutePath}/lib/perl5/core_perl:${prefixDir.absolutePath}/lib/perl5/site_perl:${prefixDir.absolutePath}/lib/perl5/vendor_perl:${prefixDir.absolutePath}/lib/perl5"
        env["PYTHONHOME"] = prefixDir.absolutePath
        env["PIP_CONFIG_FILE"] = "${prefixDir.absolutePath}/etc/pip.conf"
        env["XDG_DATA_DIRS"] = "${prefixDir.absolutePath}/share"
        // Xfce validates that its compile-time SYSCONFDIR is present literally,
        // even though actual file access is relocated to this app's prefix.
        env["XDG_CONFIG_DIRS"] = listOf(
            "${prefixDir.absolutePath}/etc/xdg",
            "${prefixDir.absolutePath}/etc",
            "/data/data/com.termux/files/usr/etc",
        ).joinToString(":")
        env["GDK_PIXBUF_MODULEDIR"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders"
        env["GDK_PIXBUF_MODULE_FILE"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"

        // Mesa is always available. Adreno devices use Turnip + Zink for hardware
        // rendering; other GPUs use Mesa's software renderer instead of being
        // forced through an incompatible Freedreno Vulkan ICD.
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            env["VK_ICD_FILENAMES"] = freedrenoIcd.absolutePath
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "true"
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
            env["GALLIUM_DRIVER"] = "llvmpipe"
        }

        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${tmpDir.absolutePath}/dbus-session"

        env["DPKG_ADMINDIR"] = "${prefixDir.absolutePath}/var/lib/dpkg"
        env["APT_CONFIG"] = "${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf"

        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        if (hookSo.exists()) {
            env["LD_PRELOAD"] = hookSo.absolutePath
        }

        return env
    }

    // ── Native Package Installation ──

    private fun installRepoPackages(): Boolean {
        val pkgs = listOf("x11-repo", "tur-repo")

        // Ensure main package list is up to date before downloading the repo packages.
        if (executeCommand("apt-get update").startsWith("Error:")) {
            Log.e(TAG, "apt-get update failed before installing repo packages")
            return false
        }

        // Download the .debs to the prefix root.
        val downloadCmd = "cd \"${prefixDir.absolutePath}\" && apt-get download ${pkgs.joinToString(" ")}"
        if (executeCommand(downloadCmd).startsWith("Error:")) {
            Log.e(TAG, "Failed to download x11-repo/tur-repo .debs")
            return false
        }

        // Unpack without configuring so we can edit the maintainer scripts first.
        val debs = pkgs.joinToString(" ") { "${it}_*.deb" }
        if (executeCommand("dpkg --unpack $debs").startsWith("Error:")) {
            Log.e(TAG, "Failed to unpack x11-repo/tur-repo .debs")
            return false
        }

        // Replace the postinst scripts with no-ops. The originals just run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter.
        for (pkg in pkgs) {
            val postinst = File(prefixDir, "var/lib/dpkg/info/$pkg.postinst")
            postinst.writeText("#!/system/bin/sh\nexit 0\n")
            postinst.setExecutable(true, false)
        }

        // Now configure the repo packages and refresh apt's package lists.
        if (executeCommand("dpkg --configure ${pkgs.joinToString(" ")}").startsWith("Error:")) {
            Log.e(TAG, "Failed to configure x11-repo/tur-repo")
            return false
        }
        if (executeCommand("pkg update -y").startsWith("Error:")) {
            Log.e(TAG, "Failed to update package lists after installing repo packages")
            return false
        }

        return true
    }

    private fun installPackageGroup(cmd: String): Boolean {
        // pkg install downloads, unpacks and configures in one go. Newly unpacked
        // maintainer scripts still contain the original Termux shebang, so after
        // the command finishes (successfully or not) we patch them and run a
        // configuration pass to finish any half-configured packages. The first
        // command is therefore allowed to fail specifically when that recovery
        // pass succeeds and all requested packages are present.
        Log.i(TAG, "Running: $cmd")
        val installOutput = executeCommand(cmd)
        patchShebangs(force = true)
        val configureOutput = executeCommand("dpkg --configure -a")
        if (configureOutput.startsWith("Error:")) {
            return false
        }
        if (!installOutput.startsWith("Error:")) {
            return true
        }

        // A configure command is itself the recovery operation. If the patched
        // second pass completed, dpkg has no remaining unconfigured packages.
        if (cmd.startsWith("dpkg --configure")) {
            Log.i(TAG, "dpkg configuration recovered after shebang patching")
            return true
        }

        val installPrefix = "pkg install -y "
        if (!cmd.startsWith(installPrefix)) {
            return false
        }

        val requestedPackages = cmd.removePrefix(installPrefix).trim()
        if (requestedPackages.isEmpty()) {
            return false
        }

        // Do not mistake an unrelated successful `dpkg --configure -a` for a
        // recovered install: every package requested by this group must now be
        // registered in dpkg's database.
        val queryOutput = executeCommand("dpkg-query -W $requestedPackages")
        val recovered = !queryOutput.startsWith("Error:")
        if (recovered) {
            Log.i(TAG, "Package group recovered after shebang patching: $requestedPackages")
        }
        return recovered
    }

    /**
     * Installs optional packages without `pkg`'s implicit repository refresh.
     *
     * Desktop setup already creates the package indexes. Refreshing every repo for
     * every optional app made an unrelated, temporarily syncing TUR mirror block
     * packages from the healthy main/X11 repositories. apt-get uses the last
     * verified indexes and only refreshes as a recovery step if installation fails.
     */
    private fun installOptionalPackages(
        packages: List<String>,
        onProgress: ((Double, String) -> Unit)? = null,
        retryProgress: Double = 0.5,
    ): Boolean {
        if (packages.isEmpty()) return true
        val packageNames = packages.joinToString(" ")

        fun installAndRecover(): Boolean {
            val installOutput = executeCommand("apt-get install -y $packageNames")
            patchShebangs(force = true)
            // A relocated package may already be unpacked while dependencies are
            // still missing (notably Node.js -> c-ares). Let apt complete that
            // dependency transaction after its maintainer scripts are patched.
            executeCommand("apt-get --fix-broken install -y")
            patchShebangs(force = true)
            val configureOutput = executeCommand("dpkg --configure -a")
            val installed = packages.all(::isDpkgPackageInstalled)
            return installed && !configureOutput.startsWith("Error:") &&
                (!installOutput.startsWith("Error:") || installed)
        }

        if (installAndRecover()) return true

        onProgress?.invoke(retryProgress, "Refreshing package repositories and retrying...")
        // apt may update main/X11 successfully while a third-party repository is
        // temporarily inconsistent. Retrying is still useful with those newly
        // refreshed lists and apt's last verified TUR index.
        executeCommand("apt-get update")
        return installAndRecover()
    }

    private fun isDpkgPackageInstalled(packageName: String): Boolean {
        val result = executeCommand(
            "dpkg-query -W -f='${'$'}{Status}' $packageName",
        )
        return !result.startsWith("Error:") && result.trim() == "install ok installed"
    }

    /**
     * Node.js currently ships a preinst script with Termux's absolute shebang.
     * Preinst runs before dpkg exposes the script in its admin directory, so it
     * must be relocated inside the deb before installation.
     */
    private fun installRelocatedNodejs(): Boolean {
        // Install Node's native dependency before unpacking the relocated deb.
        // Running apt --fix-broken with an unpacked local Node package can make
        // apt replace it with the repository deb, whose absolute preinst shebang
        // is exactly what this relocation path must avoid.
        if (!isDpkgPackageInstalled("c-ares") &&
            !installOptionalPackages(listOf("c-ares"))) return false

        val workDir = File(tmpDir, "nodejs-relocated-deb")
        workDir.deleteRecursively()
        workDir.mkdirs()

        if (executeCommand("cd \"${workDir.absolutePath}\" && apt-get download nodejs")
                .startsWith("Error:")) return false
        val sourceDeb = workDir.listFiles()
            ?.firstOrNull { it.name.startsWith("nodejs_") && it.extension == "deb" }
            ?: return false
        val unpacked = File(workDir, "unpacked")
        if (executeCommand(
                "dpkg-deb -R \"${sourceDeb.absolutePath}\" \"${unpacked.absolutePath}\""
            ).startsWith("Error:")) return false

        val oldPrefix = "/data/data/com.termux/files/usr"
        val controlDir = File(unpacked, "DEBIAN")
        controlDir.listFiles()?.filter { it.isFile }?.forEach { script ->
            val content = script.readText()
            if (content.contains(oldPrefix)) {
                script.writeText(content.replace(oldPrefix, prefixDir.absolutePath))
                script.setExecutable(true, false)
            }
        }

        val rebuiltDeb = File(workDir, "nodejs-relocated.deb")
        if (executeCommand(
                "dpkg-deb -b \"${unpacked.absolutePath}\" \"${rebuiltDeb.absolutePath}\""
            ).startsWith("Error:")) return false
        if (executeCommand("dpkg --unpack \"${rebuiltDeb.absolutePath}\"")
                .startsWith("Error:")) return false

        patchShebangs(force = true)
        if (executeCommand("dpkg --configure nodejs").startsWith("Error:")) return false
        return isDpkgPackageInstalled("nodejs")
    }

    /** Installs only PRoot, proot-distro and Debian's base rootfs. */
    private fun installMinimalDebian(
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        onProgress?.invoke(0.12, "Installing lightweight PRoot runtime...")
        if (!installOptionalPackages(listOf("proot", "proot-distro"), onProgress, 0.28)) {
            return false
        }

        patchShebangs(force = true)
        relocateProotExecutable()
        if (executeCommand("proot --version").startsWith("Error:")) {
            Log.e(TAG, "Installed PRoot executable could not start")
            return false
        }

        if (debianRootfsMarkers().none(File::exists)) {
            // Remove an interrupted extraction so proot-distro can safely retry.
            File(prefixDir, "var/lib/proot-distro/containers/debian").deleteRecursively()
            File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian").deleteRecursively()
            onProgress?.invoke(0.38, "Downloading minimal Debian base system...")
            if (executeCommand("proot-distro install debian").startsWith("Error:")) {
                Log.e(TAG, "Minimal Debian rootfs installation failed")
                return false
            }
        }

        onProgress?.invoke(0.9, "Creating Debian shell shortcut...")
        writeDebianLauncher()

        // The downloaded archive is not needed after extraction.
        clearProotDownloadCache()
        onProgress?.invoke(1.0, "Minimal Debian compatibility is ready")
        return isMinimalDebianInstalled()
    }

    fun installDesktopEnvironmentNative(
        desktopEnv: String = "xfce4",
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        val marker = File(prefixDir, DE_MARKER)

        if (getInstalledDE() == selectedDesktop) {
            onProgress?.invoke(1.0, "$selectedDesktop is already installed")
            Log.i(TAG, "$selectedDesktop desktop environment already installed")
            return true
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot install DE — bootstrap not extracted")
            return false
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        onProgress?.invoke(0.12, "Configuring X11 and TUR repositories...")

        // Install the x11/tur repository packages. Their postinst scripts run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter, so we
        // unpack the .debs, neutralise the postinst scripts, configure them, and
        // then update the package lists ourselves.
        if (!installRepoPackages()) {
            Log.e(TAG, "Failed to install x11-repo/tur-repo")
            return false
        }
        onProgress?.invoke(0.24, "Updating native package database...")

        // Finish configuring anything left over from a previous run, then install
        // the desktop, GPU drivers, and build tools. Each install is followed by a
        // shebang patch + configure pass so postinst scripts find our prefix.
        if (!installPackageGroup("dpkg --configure -a")) {
            Log.e(TAG, "Initial dpkg --configure -a failed")
            return false
        }
        if (!installPackageGroup("pkg update -y")) {
            Log.e(TAG, "pkg update failed")
            return false
        }
        onProgress?.invoke(0.34, "Installing X11 and audio packages...")
        // DroidDesk embeds the X server, so termux-x11-nightly is deliberately
        // not installed. All desktops connect to the service's DISPLAY=:0.
        if (!installPackageGroup("pkg install -y xorg-xrandr pulseaudio")) {
            Log.e(TAG, "Native X11 runtime package install failed")
            return false
        }
        onProgress?.invoke(0.46, "Installing $selectedDesktop desktop packages...")

        val desktopPackages = when (selectedDesktop) {
            "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
            "mate" -> "mate mate-terminal"
            "kde" -> "plasma-desktop konsole dolphin"
            else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd thunar mousepad"
        }
        if (!installPackageGroup("pkg install -y $desktopPackages")) {
            Log.e(TAG, "$selectedDesktop package install failed")
            return false
        }
        onProgress?.invoke(0.70, "Installing Mesa graphics packages...")

        // mesa-zink pulls the Vulkan loader selected by the active Termux repo.
        // Current repositories use vulkan-loader-generic, which provides and
        // conflicts with the older vulkan-loader-android package name.
        if (!installPackageGroup("pkg install -y mesa-zink")) {
            // Graphics acceleration is optional. A desktop with llvmpipe is much
            // better UX than failing setup because a vendor Vulkan stack is not
            // compatible with the current Mesa package set.
            Log.w(TAG, "Mesa/Zink install unavailable; continuing with software rendering")
            installPackageGroup("dpkg --configure -a")
        }

        // Turnip/Freedreno is the hardware path for Qualcomm Adreno. Do not
        // install or force that ICD on Mali/PowerVR devices.
        if (hasAdrenoGpu()) {
            onProgress?.invoke(0.78, "Installing Adreno hardware acceleration...")
            installPackageGroup("pkg install -y mesa-vulkan-icd-freedreno")
        }

        val nativeTools = "git wget curl openssh htop python clang"
        onProgress?.invoke(
            0.84,
            "Installing Desktop Essentials tools...",
        )
        if (!installPackageGroup("pkg install -y $nativeTools")) {
            Log.e(TAG, "Native Termux utility package install failed")
            return false
        }
        onProgress?.invoke(0.94, "Finalizing native Linux environment...")

        // Rebuild the hook with the installed clang, then persist the selected DE.
        compileSocketHook()
        patchEmbeddedXfcePaths()
        marker.writeText(selectedDesktop)
        onProgress?.invoke(1.0, "Native Linux setup complete")
        Log.i(TAG, "Native Termux $selectedDesktop installation complete")
        return true
    }

    fun installOptionalApp(
        appId: String,
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        if (getInstalledDE().isEmpty()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress?.invoke(1.0, "Already installed")
            return true
        }

        // Code OSS can pull npm, and npm can be left unpacked while Node's
        // original Termux preinst path is invalid in our relocated prefix.
        // Repair/install Node before the generic dpkg configure pass.
        if (appId == "nodejs" || appId == "code_oss") {
            onProgress?.invoke(0.08, "Preparing relocated Node.js dependency...")
            if (!isDpkgPackageInstalled("nodejs") && !installRelocatedNodejs()) return false
        }

        onProgress?.invoke(0.18, "Repairing interrupted packages...")
        if (!installPackageGroup("dpkg --configure -a")) return false

        val ok = when (appId) {
            "firefox" -> {
                onProgress?.invoke(0.25, "Installing Firefox...")
                installOptionalPackages(listOf("firefox"), onProgress, 0.55)
            }
            "code_oss" -> {
                onProgress?.invoke(0.45, "Installing npm dependency...")
                installOptionalPackages(listOf("npm"), onProgress, 0.55) && run {
                    onProgress?.invoke(0.65, "Installing Code OSS...")
                    installOptionalPackages(listOf("code-oss"), onProgress, 0.78)
                }
            }
            "nodejs" -> {
                onProgress?.invoke(0.65, "Installing npm...")
                installOptionalPackages(listOf("npm"), onProgress, 0.78)
            }
            "imagemagick" -> {
                onProgress?.invoke(0.25, "Installing ImageMagick...")
                installOptionalPackages(listOf("imagemagick"), onProgress, 0.55)
            }
            "proot_debian" -> installMinimalDebian(onProgress)
            else -> false
        }

        val verified = ok && getOptionalAppsStatus()[appId] == true
        if (verified) {
            patchShebangs(force = true)
            onProgress?.invoke(1.0, "Installation complete")
        } else {
            onProgress?.invoke(-1.0, "Installation failed. Review the package log and retry.")
        }
        return verified
    }

    // ── Session Management ──

    fun startSession(desktopEnv: String = "xfce4", mode: String = "x11", width: Int = 1920, height: Int = 1080) {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        extractBootstrapIfNeeded(context)

        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        patchEmbeddedXfcePaths()

        if (selectedDesktop == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = homeDir.apply { mkdirs() },
                wallpaperFile = File(
                    homeDir,
                    ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
                ),
            )
        }

        // X11ServerService owns this socket. Never delete it from the client runtime.
        File(tmpDir, ".X11-unix").mkdirs()

        // Remove stale Xfce ICE listeners left by a killed/restarted activity.
        tmpDir.listFiles { file -> file.name.startsWith(".xfsm-ICE-") }
            ?.forEach { it.delete() }

        // Start a session dbus-daemon and keep it as a child process. Do not use
        // --fork: a forked daemon can outlive the Android activity and leave an
        // orphaned bus behind after its socket is replaced.
        val dbusSocket = File(tmpDir, "dbus-session")
        try {
            dbusProcess?.destroyForcibly()
            if (dbusSocket.exists()) dbusSocket.delete()
            val dbusConfig = File(tmpDir, "dbus-session.conf")
            dbusConfig.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <keep_umask/>
                  <listen>unix:path=${dbusSocket.absolutePath}</listen>
                  <auth>EXTERNAL</auth>
                  <servicedir>${File(prefixDir, "share/dbus-1/services").absolutePath}</servicedir>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n",
            )
            val dbusCmd = listOf(
                File(prefixDir, "bin/dbus-daemon").absolutePath,
                "--config-file=${dbusConfig.absolutePath}",
                "--nofork",
                "--nopidfile"
            )
            val startedDbus = ProcessBuilder(dbusCmd)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            dbusProcess = startedDbus

            Thread {
                try {
                    startedDbus.inputStream.bufferedReader().forEachLine {
                        Log.d(TAG, "DBUS: $it")
                    }
                } catch (error: java.io.IOException) {
                    // Closing the pipe is expected when Stop Server terminates dbus.
                    Log.d(TAG, "D-Bus output stream closed")
                }
            }.start()

            val readyDeadline = System.currentTimeMillis() + 2_000
            while (!dbusSocket.exists() && startedDbus.isAlive &&
                System.currentTimeMillis() < readyDeadline) {
                Thread.sleep(20)
            }
            check(dbusSocket.exists() && startedDbus.isAlive) {
                "dbus-daemon did not create its session socket"
            }
            Log.i(TAG, "Session dbus-daemon ready")
        } catch (e: Exception) {
            Log.e(TAG, "dbus-daemon start failed: ${e.message}")
            dbusProcess?.destroyForcibly()
            dbusProcess = null
            return
        }

        val desktopCommand = when (selectedDesktop) {
            "lxqt" -> "startlxqt"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            else -> "startxfce4"
        }

        val runScript = """
            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export DISPLAY=:0

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # Native Android audio. AAudio is reliable on modern Android while
            # OpenSL ES remains the compatibility fallback for older devices.
            pulseaudio -k >/dev/null 2>&1 || true
            if [ "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "1" else "0"}" = "1" ]; then
                pulseaudio --start --exit-idle-time=-1 \
                    --load=module-aaudio-sink >/dev/null 2>&1 || true
                audio_ready=0
                for attempt in 1 2 3 4 5 6 7 8 9 10; do
                    if pactl list short sinks 2>/dev/null | grep -q AAudio_sink; then
                        audio_ready=1
                        break
                    fi
                    sleep 0.1
                done
                if [ "${'$'}audio_ready" != "1" ]; then
                    pulseaudio -k >/dev/null 2>&1 || true
                    pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
                fi
            else
                pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
            fi
            echo "DIAG: PulseAudio sinks: ${'$'}(pactl list short sinks 2>/dev/null | cut -f2 | tr '\n' ' ')"

            echo "DIAG: Launching $desktopCommand natively on DISPLAY=:0 ..."
            exec $desktopCommand
        """.trimIndent()

        Log.i(TAG, "Starting native Termux session for $selectedDesktop")

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val command = listOf(bashBin, "-c", runScript)

        val startedSession = ProcessBuilder(command)
            .directory(homeDir.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(getTermuxEnv())
            }
            .start()
        sessionProcess = startedSession

        Thread {
            try {
                val reader = java.io.InputStreamReader(startedSession.inputStream)
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                // destroyForcibly() closes the pipe during a normal shutdown.
                Log.d(TAG, "Desktop output stream closed")
            }
        }.start()

        Log.i(TAG, "Termux session started")
    }

    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")
        sessionProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        sessionProcess = null
        dbusProcess?.destroyForcibly()
        dbusProcess = null
        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        activeCommandProcess?.let { process ->
            try {
                Log.d(TAG, "Routing input to active command: $command")
                val os = process.outputStream
                os.write((command + "\n").toByteArray())
                os.flush()
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "Active process closed or failed to receive input: ${e.message}")
            }
        }

        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        compileSocketHook()

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val fullCommand = listOf(bashBin, "-c", command)

        return try {
            Log.d(TAG, "Executing command natively: $command")

            val process = ProcessBuilder(fullCommand)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()

            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                installLogSink?.invoke(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")

            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }

            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    fun interruptCommand() {
        activeCommandProcess?.let {
            Log.d(TAG, "Interrupting active command...")
            it.destroy()
        }
        activeCommandProcess = null
    }
}
