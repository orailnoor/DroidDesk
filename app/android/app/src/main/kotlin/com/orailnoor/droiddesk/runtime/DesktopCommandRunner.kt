package com.orailnoor.droiddesk.runtime

import java.io.IOException
import java.util.concurrent.TimeUnit

class DesktopCommandRunner(
    private val buildProcess: (String) -> ProcessBuilder,
    private val diagnostic: (String) -> Unit,
) {
    fun run(script: String): Result<Unit> {
        var process: Process? = null
        return try {
            val quoted = "'" + script.replace("'", "'\"'\"'") + "'"
            val bounded = """exec timeout --kill-after=1 7 "${'$'}BASH" -c $quoted"""
            val command = buildProcess(bounded).redirectErrorStream(true).start()
            process = command
            command.outputStream.close()
            val output = StringBuilder()
            val reader = Thread({
                try {
                    command.inputStream.bufferedReader().use { stream ->
                        stream.forEachLine { line ->
                            synchronized(output) {
                                if (output.length < 8192) output.appendLine(line)
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (command.isAlive) diagnostic("Desktop command output failed: ${error.message}")
                }
            }, "DesktopProfileOutput").apply { isDaemon = true; start() }
            if (!command.waitFor(8, TimeUnit.SECONDS)) {
                command.destroyForcibly()
                command.waitFor(1, TimeUnit.SECONDS)
                reader.join(1000)
                error("Desktop profile command exceeded its bounded execution budget")
            }
            reader.join(1000)
            val details = synchronized(output) { output.toString().trim() }
            if (command.exitValue() != 0) {
                error("Desktop command exited ${command.exitValue()}: $details")
            }
            Result.success(Unit)
        } catch (error: IOException) {
            diagnostic("Desktop command could not start: ${error.message}")
            Result.failure(error)
        } catch (error: SecurityException) {
            diagnostic("Desktop command permission denied: ${error.message}")
            Result.failure(error)
        } catch (error: IllegalStateException) {
            diagnostic(error.message ?: "Desktop command failed")
            Result.failure(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(error)
        } finally {
            process?.let {
                try {
                    it.inputStream.close()
                } catch (error: IOException) {
                    diagnostic("Desktop command stream cleanup failed: ${error.message}")
                }
                if (it.isAlive) it.destroy()
            }
        }
    }
}
