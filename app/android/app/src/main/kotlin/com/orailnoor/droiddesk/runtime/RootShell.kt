package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Generic root command executor.
 *
 * Works with Magisk, KernelSU, LineageOS su, or any standard Android `su` binary.
 * Detects the `su` path, tests for root access, and runs commands through it.
 */
class RootShell(private val context: Context) {

    companion object {
        private const val TAG = "RootShell"

        // Common su paths, ordered by likelihood
        private val SU_PATHS = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/vendor/xbin/su"
        )

        @Volatile
        private var cachedSuPath: String? = null

        @Volatile
        private var cachedRootState: Boolean? = null
    }

    /**
     * Find the first existing su binary path.
     */
    fun findSuPath(): String? {
        cachedSuPath?.let { return it }

        // Prefer whichever `su` is in PATH
        val pathSu = runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v su")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotEmpty() && File(output).exists()) output else null
        }.getOrNull()

        if (pathSu != null) {
            cachedSuPath = pathSu
            return pathSu
        }

        for (path in SU_PATHS) {
            if (File(path).exists()) {
                cachedSuPath = path
                return path
            }
        }
        return null
    }

    /**
     * Returns true if root access is available and granted.
     */
    fun hasRoot(): Boolean {
        cachedRootState?.let { return it }
        val su = findSuPath() ?: return false
        val result = execSync(su, "id -u")
        val hasRoot = result.trim() == "0"
        cachedRootState = hasRoot
        Log.i(TAG, "Root check: $hasRoot (su=$su)")
        return hasRoot
    }

    /**
     * Execute a shell command as root. Returns stdout+stderr combined.
     */
    fun exec(command: String): String {
        val su = findSuPath() ?: throw RootException("No su binary found. Is this device rooted?")
        return execSync(su, command)
    }

    /**
     * Execute a command as root and stream output chunks.
     */
    fun exec(command: String, onOutput: (String) -> Unit): Int {
        val su = findSuPath() ?: throw RootException("No su binary found. Is this device rooted?")
        return execStream(su, command, onOutput)
    }

    /**
     * Run a command inside the chroot rootfs as root.
     *
     * [command] is passed as a single safely-quoted argument to bash, so a
     * command containing quotes cannot break out of or inject into the wrapper.
     */
    fun execInChroot(rootfs: File, command: String): String {
        return exec("chroot ${rootfs.absolutePath} /bin/bash -c ${shellQuote(command)}")
    }

    /** Wrap [input] as a single-quoted shell token, escaping embedded quotes. */
    private fun shellQuote(input: String): String {
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }

    /**
     * Clear cached root state. Call after granting/denying root in Magisk.
     */
    fun resetCache() {
        cachedSuPath = null
        cachedRootState = null
    }

    private fun execSync(su: String, command: String): String {
        Log.d(TAG, "su exec: $command")
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun execStream(su: String, command: String, onOutput: (String) -> Unit): Int {
        Log.d(TAG, "su stream: $command")
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        val buffer = CharArray(1024)
        var charsRead: Int
        while (reader.read(buffer).also { charsRead = it } != -1) {
            val chunk = String(buffer, 0, charsRead)
            onOutput(chunk)
        }
        process.waitFor()
        return process.exitValue()
    }

    class RootException(message: String) : Exception(message)
}
