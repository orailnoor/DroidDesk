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
        private const val SHEBANG_MARKER = ".relocated_text_paths_v4"
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
    @Volatile private var packageOperationCancelled = false

    fun setInstallLogSink(sink: ((String) -> Unit)?) {
        installLogSink = sink
    }

    fun beginPackageOperation() {
        // A previous App Store screen may have been closed while apt was still
        // running. Stop that process before accepting a new transaction.
        if (activeCommandProcess?.isAlive == true) interruptCommand()
        packageOperationCancelled = false
        clearStalePackageLocks()
    }

    fun finishPackageOperation() {
        packageOperationCancelled = false
    }

    fun cancelPackageOperation(): Boolean {
        packageOperationCancelled = true
        val wasRunning = activeCommandProcess?.isAlive == true
        interruptCommand()
        return wasRunning
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

    // The non-root product ships one supported, touch-tuned desktop.
    // Ignore legacy callers that may still request an older DE option.
    private fun normalizedDesktop(@Suppress("UNUSED_PARAMETER") desktopEnv: String): String = "xfce4"

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
            EXTRA_STORAGE_BINDS=""
            if [ -d "/storage/emulated/0" ] && [ -r "/storage/emulated/0" ]; then
                EXTRA_STORAGE_BINDS="--bind /storage/emulated/0:/sdcard --bind /storage/emulated/0:/storage/emulated/0"
            elif [ -d "/sdcard" ] && [ -r "/sdcard" ]; then
                EXTRA_STORAGE_BINDS="--bind /sdcard:/sdcard"
            fi
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" ${'$'}EXTRA_STORAGE_BINDS \
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
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
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
            // newly unpacked scripts never keep Termux's inaccessible prefix.
            // apt unpacks and configures in separate dpkg runs, so maintainer
            // scripts are fixed before they execute. The whole script is
            // rewritten, not just the #! line: bodies such as cups' postinst
            // ("mkdir -p /data/data/com.termux/...") or the code-oss and
            // hangover-wine launchers otherwise fail with "Permission denied".
            relocateShebangs.writeText(
                """
                #!/system/bin/sh
                old_prefix="/data/data/com.termux/files/usr"
                new_prefix="${prefixDir.absolutePath}"
                old_home="/data/data/com.termux/files/home"
                new_home="${homeDir.absolutePath}"
                # "--all" rescans everything once (repairs older installs);
                # otherwise only files changed since the dpkg run began.
                if [ "${'$'}1" = "--all" ]; then
                    changed=""
                else
                    [ -f "${'$'}1" ] || exit 0
                    changed="-cnewer ${'$'}1"
                fi
                for root in \
                    "${prefixDir.absolutePath}/bin" \
                    "${prefixDir.absolutePath}/libexec" \
                    "${prefixDir.absolutePath}/lib" \
                    "${prefixDir.absolutePath}/opt" \
                    "${prefixDir.absolutePath}/share" \
                    "${prefixDir.absolutePath}/var/lib/dpkg/info"
                do
                    [ -d "${'$'}root" ] || continue
                    "${prefixDir.absolutePath}/bin/find" "${'$'}root" \
                        -type f -perm -100 -size -2M ${'$'}changed \
                        ! -name droiddesk-relocate-shebangs \
                        -exec "${prefixDir.absolutePath}/bin/grep" -lIF "/data/data/com.termux/files" {} + 2>/dev/null |
                    while IFS= read -r file; do
                        first_line=$("${prefixDir.absolutePath}/bin/head" -n 1 "${'$'}file" 2>/dev/null)
                        # dpkg's *.list/*.md5sums must keep Termux paths; only
                        # maintainer scripts and other #! scripts are rewritten.
                        case "${'$'}file:${'$'}first_line" in
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*.list:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.md5sums:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.conffiles:*)
                                ;;
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*inst:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*rm:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.config:*|*":#!"*)
                                "${prefixDir.absolutePath}/bin/sed" -i \
                                    -e "s|${'$'}old_prefix|${'$'}new_prefix|g" \
                                    -e "s|${'$'}old_home|${'$'}new_home|g" "${'$'}file"
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
        val migrating = !markerFile.exists()
        if (!force && !migrating) {
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
        // New dpkg runs relocate their own files; the full scan is a one-time migration.
        if (migrating) relocateAllScripts()
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    private fun hasUnfinishedPackages(): Boolean = runCatching {
        File(prefixDir, "var/lib/dpkg/status").useLines { lines ->
            lines.any { it.startsWith("Status: ") && !it.endsWith(" installed") && !it.endsWith(" not-installed") && !it.endsWith(" config-files") }
        }
    }.getOrDefault(false)

    /**
     * Older releases only relocated the #! line of new scripts, so script bodies (and launchers
     * under lib/ and opt/) could still point at Termux and fail with "Permission denied".
     */
    private fun relocateAllScripts() {
        val relocate = File(prefixDir, "bin/droiddesk-relocate-shebangs")
        if (!relocate.canExecute()) return
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", relocate.absolutePath, "--all")
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getTermuxEnv())
                }
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }.onFailure { Log.w(TAG, "Full script relocation failed: ${it.message}") }
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
        patchEmbeddedCommand(
            File(prefixDir, "libexec/upowerd"),
            "/data/data/com.termux/files/usr/bin/termux-battery-status",
            "termux-battery-status",
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

    fun updateBatteryStatus() {
        try {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val bIntent = context.registerReceiver(null, ifilter)
            val level = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100
            val status = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pluggedInt = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val plugged = when (pluggedInt) {
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
                else -> if (isCharging) "PLUGGED_AC" else "UNPLUGGED"
            }
            val statusStr = when {
                status == android.os.BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                isCharging -> "CHARGING"
                else -> "DISCHARGING"
            }
            val tempRaw = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val temp = String.format(java.util.Locale.US, "%.1f", tempRaw / 10.0)

            val json = """{"health": "GOOD", "percentage": $pct, "plugged": "$plugged", "status": "$statusStr", "temperature": $temp, "current": 0}"""
            tmpDir.mkdirs()
            File(tmpDir, "battery.json").writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update battery status: ${e.message}")
        }
    }

    private fun ensureBatteryHelper() {
        try {
            updateBatteryStatus()
            val batteryScript = File(binDir, "termux-battery-status")
            val shPath = File(binDir, "sh").takeIf { it.exists() }?.absolutePath ?: "/bin/sh"
            val scriptContent = """
                #!$shPath
                BATTERY_FILE="${tmpDir.absolutePath}/battery.json"
                if [ -f "${'$'}BATTERY_FILE" ]; then
                    cat "${'$'}BATTERY_FILE"
                    exit 0
                fi
                echo '{"health": "GOOD", "percentage": 100, "plugged": "UNPLUGGED", "status": "DISCHARGING", "temperature": 25.0, "current": 0}'
            """.trimIndent() + "\n"
            batteryScript.writeText(scriptContent)
            batteryScript.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write battery helper: ${e.message}")
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
        val buildMarker = File(prefixDir, "lib/.socket_hook_build")
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

        val buildSignature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((hookC.readText() + "\n" + prefixDir.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hookSo.isFile && buildMarker.readTextOrNull() == buildSignature) {
            Log.d(TAG, "Native socket hook is current; skipping compilation")
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
                    buildMarker.writeText(buildSignature)
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

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

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

        // The official Mesa package contains both Zink and llvmpipe. Qualcomm
        // devices pair Zink with the Freedreno/Turnip Vulkan ICD; every other
        // GPU uses llvmpipe and must not inherit a vendor Vulkan selection.
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            env["VK_ICD_FILENAMES"] = freedrenoIcd.absolutePath
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "true"
            // Mesa exposes llvmpipe through swrast_dri.so. "llvmpipe" is the
            // Gallium driver name, not a DRI loader module name.
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "swrast"
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

    /**
     * Keep the graphics stack aligned with the device GPU.
     *
     * The old TUR mesa-zink package conflicts with and removes the current Mesa
     * package. That left non-Adreno devices configured for llvmpipe without the
     * llvmpipe driver. Installing official Mesa on every device provides both
     * Zink and llvmpipe; only Adreno devices additionally need Turnip/Freedreno.
     */
    private fun ensureGraphicsPackages(
        onProgress: ((Double, String) -> Unit)? = null,
        progress: Double = 0.72,
    ): Boolean {
        if (!isDpkgPackageInstalled("mesa")) {
            onProgress?.invoke(progress, "Installing Mesa graphics runtime...")
            // libc++ too: apt keeps an older installed copy, which Mesa can't load against.
            if (!installPackageGroup("pkg install -y mesa libc++")) {
                Log.e(TAG, "Mesa graphics runtime installation failed")
                return false
            }
        }
        if (mesaNeedsNewerLibcxx()) {
            onProgress?.invoke(progress, "Updating the C++ runtime for graphics...")
            Log.w(TAG, "Mesa needs a newer libc++ than installed; upgrading it")
            if (!installPackageGroup("pkg install -y libc++") || mesaNeedsNewerLibcxx()) {
                Log.e(TAG, "libc++ is still too old for Mesa; OpenGL will be unavailable")
            }
        }

        if (hasAdrenoGpu()) {
            val freedrenoIcd = File(
                prefixDir,
                "share/vulkan/icd.d/freedreno_icd.aarch64.json",
            )
            if (!isDpkgPackageInstalled("mesa-vulkan-icd-freedreno") || !freedrenoIcd.exists()) {
                onProgress?.invoke(progress, "Installing Adreno Turnip acceleration...")
                if (!installPackageGroup("pkg install -y mesa-vulkan-icd-freedreno")) {
                    Log.e(TAG, "Adreno Turnip/Freedreno installation failed")
                    return false
                }
            }
            Log.i(TAG, "Graphics mode: Mesa Zink with Turnip/Freedreno")
        } else {
            Log.i(TAG, "Graphics mode: Mesa llvmpipe software rendering")
        }
        return true
    }

    /**
     * Mesa from the current repo links against libc++ symbols that older installed
     * libc++_shared.so builds don't export (seen: std::__ndk1::__hash_memory). Then
     * libGLX_mesa fails to load and every OpenGL client gets no context at all.
     */
    private fun mesaNeedsNewerLibcxx(): Boolean {
        val lib = File(prefixDir, "lib")
        val libcxx = File(lib, "libc++_shared.so")
        if (!libcxx.isFile) return false
        val mesaLibs = (lib.listFiles { f -> f.name.startsWith("libgallium-") && f.name.endsWith(".so") }
            ?.toList().orEmpty()) + listOf(File(lib, "libvulkan_freedreno.so")).filter { it.isFile }
        if (mesaLibs.isEmpty()) return false
        val symbol = "_ZNSt6__ndk113__hash_memoryEPKvm"
        val quoted = mesaLibs.joinToString(" ") { "'${it.absolutePath}'" }
        val needed = runQuickCommand("grep -lqa '$symbol' $quoted && echo yes").contains("yes")
        if (!needed) return false
        return !runQuickCommand("grep -qa '$symbol' '${libcxx.absolutePath}' && echo yes").contains("yes")
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
        val boundedCommand = if (cmd.startsWith("dpkg --configure")) {
            "timeout --signal=TERM --kill-after=5s 90s $cmd"
        } else {
            cmd
        }
        val installOutput = executeCommand(boundedCommand)
        patchShebangs(force = true)
        if (packageOperationCancelled) return false
        val configureOutput = if (cmd.startsWith("dpkg --configure")) {
            installOutput
        } else {
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
        }
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
            val installOutput = executeCommand(
                "DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold install -y $packageNames",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            // A relocated package may already be unpacked while dependencies are
            // still missing (notably Node.js -> c-ares). Let apt complete that
            // dependency transaction after its maintainer scripts are patched.
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 120s " +
                    "env DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold --fix-broken install -y",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            val configureOutput = executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
            val installed = packages.all(::isDpkgPackageInstalled)
            return installed && !configureOutput.startsWith("Error:") &&
                (!installOutput.startsWith("Error:") || installed)
        }

        if (installAndRecover()) return true
        if (packageOperationCancelled) return false

        onProgress?.invoke(retryProgress, "Refreshing package repositories and retrying...")
        // apt may update main/X11 successfully while a third-party repository is
        // temporarily inconsistent. Retrying is still useful with those newly
        // refreshed lists and apt's last verified TUR index.
        executeCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update")
        if (packageOperationCancelled) return false
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
        if (!installPackageGroup("pkg install -y xorg-xrandr pulseaudio xclip")) {
            Log.e(TAG, "Native X11 runtime package install failed")
            return false
        }
        onProgress?.invoke(0.46, "Installing $selectedDesktop desktop packages...")

        val desktopPackages =
            "xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd thunar mousepad devilspie2"
        if (!installPackageGroup("pkg install -y $desktopPackages")) {
            Log.e(TAG, "$selectedDesktop package install failed")
            return false
        }
        onProgress?.invoke(0.70, "Configuring graphics for this device...")
        if (!ensureGraphicsPackages(onProgress, 0.74)) return false

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

    // ── Native package store ──

    fun searchNativePackages(query: String, limit: Int = 60): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        val cleanQuery = query.trim().take(60)
        if (!cleanQuery.matches(Regex("[A-Za-z0-9+._ -]*"))) return emptyList()
        val output = runQuickCommand("apt-cache search --names-only '${cleanQuery.ifEmpty { "." }}'")
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        return output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).substringBefore(':').trim()
            if (!isSafePackageName(name)) return@mapNotNull null
            val description = line.substring(separator + 3).trim()
            val record = installed[name]
            mapOf(
                "name" to name,
                "description" to description,
                "version" to (record?.get("version") ?: ""),
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to (record != null),
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.take(limit.coerceIn(1, 100)).toList()
    }

    fun getInstalledNativePackages(limit: Int = 500): List<Map<String, Any>> {
        val visible = storeInstalledPackages() + setOf(
            "firefox", "code-oss", "libreoffice", "gimp", "blender", "vlc",
            "nodejs", "python", "imagemagick",
        )
        return installedPackageRecords()
            .filter { record ->
                val name = record["name"] as? String ?: return@filter false
                visible.contains(name)
            }
            .take(limit.coerceIn(1, 1000))
    }

    fun installStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName)) {
            onProgress(-1.0, "Invalid package name")
            return false
        }
        onProgress(0.08, "Repairing interrupted package operations...")
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) {
            onProgress(-1.0, "Installation cancelled")
            return false
        }
        onProgress(0.22, "Installing $packageName and dependencies...")
        val optionalId = when (packageName) {
            "firefox" -> "firefox"
            "code-oss", "code" -> "code_oss"
            "nodejs" -> "nodejs"
            "imagemagick" -> "imagemagick"
            else -> null
        }
        val ok = if (optionalId != null) {
            installOptionalApp(optionalId) { progress, status ->
                onProgress(0.22 + progress.coerceIn(0.0, 1.0) * 0.68, status)
            }
        } else {
            installOptionalPackages(listOf(packageName), onProgress, 0.5)
        }
        if (packageOperationCancelled) {
            onProgress(-1.0, "Installation cancelled")
            return false
        }
        patchShebangs(force = true)
        refreshDesktopMenus()
        val installed = ok && isDpkgPackageInstalled(packageName)
        if (installed) setStorePackageInstalled(packageName, true)
        onProgress(if (installed) 1.0 else -1.0, if (installed) "$packageName installed" else "$packageName installation failed")
        return installed
    }

    fun removeStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName) || isProtectedPackage(packageName)) {
            onProgress(-1.0, "This package is required by DroidDesk")
            return false
        }
        onProgress(0.15, "Removing $packageName...")
        val output = executeCommand("apt-get remove -y $packageName")
        if (packageOperationCancelled) {
            onProgress(-1.0, "Removal cancelled")
            return false
        }
        patchShebangs(force = true)
        executeCommand("dpkg --configure -a")
        refreshDesktopMenus()
        val removed = !isDpkgPackageInstalled(packageName)
        if (removed) setStorePackageInstalled(packageName, false)
        onProgress(if (removed) 1.0 else -1.0, if (removed) "$packageName removed" else "$packageName removal failed")
        return removed && !output.startsWith("Error:")
    }

    /**
     * Installed packages with a newer version available. Core packages DroidDesk depends on
     * (see [isProtectedPackage]) are left out: upgrading them can break the relocated runtime.
     */
    fun getUpgradablePackages(refresh: Boolean): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        if (refresh) {
            runQuickCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update >/dev/null 2>&1; true")
        }
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        // Lines look like: name/stable 1.2-1 aarch64 [upgradable from: 1.1-1]
        return runQuickCommand("apt list --upgradable 2>/dev/null").lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val name = line.substringBefore('/').trim()
            if (!isSafePackageName(name) || isProtectedPackage(name)) return@mapNotNull null
            val newVersion = line.substringAfter('/').split(' ').getOrNull(1).orEmpty()
            val current = line.substringAfter("upgradable from: ").substringBefore(']').trim()
            val record = installed[name]
            val description = record?.get("description") as? String ?: ""
            mapOf(
                "name" to name,
                "version" to newVersion,
                "currentVersion" to current,
                "description" to description,
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to true,
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.toList()
    }

    /** Upgrades every package from [getUpgradablePackages]; core packages are never touched. */
    fun upgradeAllPackages(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(0.06, "Repairing interrupted package operations...")
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.14, "Checking for updates...")
        val packages = getUpgradablePackages(refresh = true).map { it["name"] as String }
        if (packageOperationCancelled) return cancelled(onProgress)
        if (packages.isEmpty()) {
            onProgress(1.0, "Everything is up to date")
            return true
        }
        onProgress(0.3, "Updating ${packages.size} package${if (packages.size == 1) "" else "s"}...")
        val output = executeCommand(
            "DEBIAN_FRONTEND=noninteractive apt-get " +
                "-o Dpkg::Options::=--force-confdef " +
                "-o Dpkg::Options::=--force-confold " +
                "install --only-upgrade -y ${packages.joinToString(" ")}",
        )
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.88, "Finishing up...")
        patchShebangs(force = true)
        executeCommand("timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a")
        refreshDesktopMenus()
        val remaining = getUpgradablePackages(refresh = false).size
        val ok = !output.startsWith("Error:") && remaining < packages.size
        onProgress(
            if (ok) 1.0 else -1.0,
            if (!ok) "Update failed" else if (remaining == 0) "All packages updated" else "$remaining package(s) could not be updated",
        )
        return ok
    }

    private fun cancelled(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(-1.0, "Update cancelled")
        return false
    }

    /** Disk usage of the Linux runtime and the device, in bytes. */
    fun getStorageInfo(): Map<String, Any> {
        val stat = android.os.StatFs(baseDir.absolutePath)
        val info = mutableMapOf<String, Any>(
            "deviceFree" to stat.availableBytes,
            "deviceTotal" to stat.totalBytes,
            "available" to isBootstrapped(),
        )
        if (!isBootstrapped()) return info
        // Separate du runs: one du call counts each inode once, so a nested path listed after
        // its parent would report 0.
        val output = runQuickCommand(
            "cd '${baseDir.absolutePath}' && for d in usr home usr/var/cache/apt usr/var/lib/apt/lists; " +
                "do du -sk \"\$d\" 2>/dev/null; done; true",
        )
        val sizes = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val kb = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            parts.getOrNull(1)?.let { it to kb * 1024 }
        }.toMap()
        val cache = (sizes["usr/var/cache/apt"] ?: 0L) + (sizes["usr/var/lib/apt/lists"] ?: 0L)
        info["system"] = ((sizes["usr"] ?: 0L) - cache).coerceAtLeast(0L)
        info["home"] = sizes["home"] ?: 0L
        info["cache"] = cache
        info["packages"] = installedPackageRecords().size
        return info
    }

    /** Deletes downloaded package files (apt-get clean). Returns bytes freed, or -1 on failure. */
    fun cleanPackageCache(): Long {
        val before = (getStorageInfo()["cache"] as? Long) ?: 0L
        val output = executeCommand("apt-get clean")
        if (output.startsWith("Error:")) return -1L
        val after = (getStorageInfo()["cache"] as? Long) ?: 0L
        return (before - after).coerceAtLeast(0L)
    }

    private fun installedPackageRecords(): List<Map<String, Any>> {
        val statusFile = File(prefixDir, "var/lib/dpkg/status")
        if (!statusFile.isFile) return emptyList()
        return statusFile.readText().split(Regex("\\n\\s*\\n")).mapNotNull { paragraph ->
            val fields = mutableMapOf<String, String>()
            var currentKey: String? = null
            paragraph.lineSequence().forEach { line ->
                if (line.startsWith(" ") && currentKey != null) {
                    fields[currentKey!!] = fields[currentKey!!].orEmpty() + " " + line.trim()
                } else {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        currentKey = line.substring(0, colon)
                        fields[currentKey!!] = line.substring(colon + 1).trim()
                    }
                }
            }
            if (fields["Status"] != "install ok installed") return@mapNotNull null
            val name = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            val description = fields["Description"].orEmpty().substringBefore(" . ").trim()
            mapOf(
                "name" to name,
                "description" to description,
                "version" to fields["Version"].orEmpty(),
                "section" to fields["Section"].orEmpty().ifEmpty { categoryForPackage(name, description) },
                "installed" to true,
                "gui" to providesDesktopEntry(name),
                "removable" to !isProtectedPackage(name),
            )
        }.sortedBy { (it["name"] as String).lowercase() }
    }

    private fun providesDesktopEntry(packageName: String): Boolean {
        val fileList = File(prefixDir, "var/lib/dpkg/info/$packageName.list")
        return fileList.isFile && fileList.useLines { lines ->
            lines.any { it.contains("/share/applications/") && it.endsWith(".desktop") }
        }
    }

    private fun categoryForPackage(name: String, description: String): String {
        val text = "$name $description".lowercase()
        return when {
            listOf("browser", "firefox", "chromium").any(text::contains) -> "Internet"
            listOf("editor", "compiler", "git", "python", "nodejs", "ide").any(text::contains) -> "Development"
            listOf("image", "photo", "graphics", "gimp", "blender").any(text::contains) -> "Graphics"
            listOf("audio", "video", "media", "music", "vlc").any(text::contains) -> "Multimedia"
            listOf("office", "document", "spreadsheet", "pdf").any(text::contains) -> "Office"
            else -> "System"
        }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[a-z0-9][a-z0-9+.-]{0,127}"))

    private fun isProtectedPackage(packageName: String): Boolean =
        packageName in setOf(
            "apt", "bash", "coreutils", "dpkg", "termux-tools", "termux-am", "glibc-repo",
            "x11-repo", "tur-repo", "pulseaudio", "dbus", "python", "xfce4", "xfce4-session",
            "xfce4-panel", "xfdesktop", "xfwm4", "xfconf", "thunar", "xorg-xrandr",
        )

    private fun storeInstalledPackages(): Set<String> =
        context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
            .getStringSet("installed", emptySet())
            ?.toSet()
            .orEmpty()

    private fun setStorePackageInstalled(packageName: String, installed: Boolean) {
        val preferences = context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
        val packages = preferences.getStringSet("installed", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (installed) packages.add(packageName) else packages.remove(packageName)
        preferences.edit().putStringSet("installed", packages).commit()
    }

    private fun runQuickCommand(command: String): String = runCatching {
        ProcessBuilder(File(binDir, "bash").absolutePath, "-c", command)
            .directory(prefixDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
            }
            .start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (process.exitValue() == 0) output else ""
            }
    }.getOrDefault("")

    private fun refreshDesktopMenus() {
        executeCommand(
            "update-desktop-database ${prefixDir.absolutePath}/share/applications >/dev/null 2>&1 || true",
        )
    }

    fun readLinuxClipboard(): String? = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return null
        ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-o")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }
            .start().let { process ->
                val value = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) value else ""
            }
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return false
        val process = ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-in")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }.start()
        process.outputStream.use { it.write(value.toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

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
        ensureBatteryHelper()

        // Packages whose maintainer scripts failed on older releases stay half-configured and
        // block every later apt install (and with it the Adreno driver). Finish them once
        // their scripts have been relocated.
        if (hasUnfinishedPackages()) {
            Log.i(TAG, "Finishing packages left half-configured by an earlier install")
            installPackageGroup("dpkg --configure -a")
        }

        // Repair installations made by older releases, where mesa-zink could
        // remove Mesa and leave the configured llvmpipe fallback unavailable.
        if (!ensureGraphicsPackages()) {
            Log.e(TAG, "Cannot start desktop without a working Mesa runtime")
            return
        }

        val clipboardTool = File(binDir, "xclip")
        if (!clipboardTool.canExecute()) {
            Log.i(TAG, "Installing the desktop clipboard compatibility helper")
            if (installPackageGroup("pkg install -y xclip")) {
                patchShebangs(force = true)
            } else {
                Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
            }
        }

        if (selectedDesktop == "xfce4") {
            // Older installs predate the auto-maximize helper; try once while upgrading the profile.
            if (XfceMobileProfile.needsInstall(homeDir) && !File(binDir, "devilspie2").canExecute()) {
                Log.i(TAG, "Installing the window fitting helper")
                if (installPackageGroup("pkg install -y devilspie2")) {
                    patchShebangs(force = true)
                } else {
                    Log.w(TAG, "Window fitting helper unavailable; windows will keep their default size")
                }
            }
            XfceMobileProfile.install(
                context = context,
                homeDir = homeDir.apply { mkdirs() },
                wallpaperFile = File(
                    homeDir,
                    ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
                ),
            )
            AndroidAppBridge.syncLaunchers(
                context = context,
                homeDir = homeDir,
                python = File(prefixDir, "bin/python3"),
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

        // The Termux startxfce4 wrapper falls back to Android's /bin/sh when an
        // X server already exists, which corrupts DISPLAY on recent Android
        // releases. The session binary starts the same XFCE components and
        // preserves DroidDesk's prepared environment.
        val desktopCommand = "xfce4-session"

        val runScript = """
            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export DISPLAY=:0

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # PulseAudio daemonizes, so it outlives a stopped session. A wedged
            # one makes every pactl/pulseaudio call (and XFCE's event sounds)
            # block forever, leaving a black desktop. Never wait on audio
            # without a time limit, and always start from a fresh daemon.
            limit() {
                if command -v timeout >/dev/null 2>&1; then timeout "${'$'}@"; else shift; "${'$'}@"; fi
            }
            pkill -9 -x pulseaudio >/dev/null 2>&1 || true

            # Native Android audio. AAudio is reliable on modern Android while
            # OpenSL ES remains the compatibility fallback for older devices.
            if [ "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "1" else "0"}" = "1" ]; then
                limit 10 pulseaudio --start --exit-idle-time=-1 \
                    --load=module-aaudio-sink >/dev/null 2>&1 || true
                audio_ready=0
                for attempt in 1 2 3 4 5 6 7 8 9 10; do
                    if limit 2 pactl list short sinks 2>/dev/null | grep -q AAudio_sink; then
                        audio_ready=1
                        break
                    fi
                    sleep 0.1
                done
                if [ "${'$'}audio_ready" != "1" ]; then
                    pkill -9 -x pulseaudio >/dev/null 2>&1 || true
                    limit 10 pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
                fi
            else
                limit 10 pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
            fi
            echo "DIAG: PulseAudio sinks: ${'$'}(limit 2 pactl list short sinks 2>/dev/null | cut -f2 | tr '\n' ' ')"

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

    /** Wait until the desktop shell processes that paint the first frame exist. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val processes = when (normalizedDesktop(desktopEnv)) {
            "lxqt" -> listOf("lxqt-session", "lxqt-panel")
            "mate" -> listOf("mate-session", "mate-panel")
            "kde" -> listOf("plasmashell")
            else -> listOf("xfdesktop", "xfce4-panel")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val check = processes.joinToString(" && ") { "pgrep -x '$it' >/dev/null" }
            val ready = runCatching {
                ProcessBuilder(File(binDir, "bash").absolutePath, "-c", check)
                    .directory(homeDir)
                    .redirectErrorStream(true)
                    .also { builder ->
                        builder.environment().clear()
                        builder.environment().putAll(getTermuxEnv())
                    }
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (ready) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(150)
        }
        Log.w(TAG, "Timed out waiting for $desktopEnv to paint its first desktop frame")
        return false
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
        if (packageOperationCancelled) return "Error: Package operation cancelled"
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
        activeCommandProcess?.let { process ->
            Log.d(TAG, "Interrupting active command...")
            val rootPid = processPid(process)
            val descendants = rootPid?.let(::descendantPids).orEmpty()
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 15) }
            rootPid?.let { android.os.Process.sendSignal(it, 15) } ?: process.destroy()
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 9) }
            if (process.isAlive) process.destroyForcibly()
        }
        activeCommandProcess = null
    }

    private fun processPid(process: Process): Int? = runCatching {
        var type: Class<*>? = process.javaClass
        var pidField: java.lang.reflect.Field? = null
        while (type != null && pidField == null) {
            pidField = runCatching { type.getDeclaredField("pid") }.getOrNull()
            type = type.superclass
        }
        requireNotNull(pidField).apply { isAccessible = true }.getInt(process)
    }.onFailure { Log.w(TAG, "Could not inspect package process PID", it) }
        .getOrNull()

    private fun descendantPids(parentPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(pid: Int) {
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach { child ->
                result += child
                collect(child)
            }
        }
        collect(parentPid)
        return result
    }

    private fun clearStalePackageLocks() {
        listOf(
            File(prefixDir, "var/lib/dpkg/lock"),
            File(prefixDir, "var/lib/dpkg/lock-frontend"),
            File(prefixDir, "var/cache/apt/archives/lock"),
            File(prefixDir, "var/lib/apt/lists/lock"),
        ).forEach { lock ->
            if (lock.exists() && !lock.delete()) {
                Log.w(TAG, "Could not remove stale package lock: ${lock.absolutePath}")
            }
        }
    }
}
