package com.orailnoor.droiddesk.x11

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.termux.x11.CmdEntryPoint
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Owns the native X server in the dedicated :x11 process.
 *
 * CmdEntryPoint must never be referenced by an Activity or Flutter platform view. The only
 * objects crossing back to the UI process are ParcelFileDescriptors marshalled by Binder.
 */
class X11ServerService : Service() {
    private lateinit var serverThread: HandlerThread
    private lateinit var serverHandler: Handler

    private val stateLock = Any()
    private var startLatch: CountDownLatch? = null
    @Volatile private var started = false
    @Volatile private var startSucceeded = false
    private val cmdEntryPoint = CmdEntryPoint()

    private val binder = object : IX11Service.Stub() {
        override fun startServer(): Boolean = ensureServerStarted()

        override fun getServerPid(): Int = android.os.Process.myPid()

        override fun getXConnection(): ParcelFileDescriptor? {
            if (!ensureServerStarted()) return null
            return cmdEntryPoint.xConnection
        }

        override fun getLogcatOutput(): ParcelFileDescriptor? {
            if (!ensureServerStarted()) return null
            return cmdEntryPoint.logcatOutput
        }
    }

    override fun onCreate() {
        super.onCreate()
        serverThread = HandlerThread("X11Server")
        serverThread.start()
        serverHandler = Handler(serverThread.looper)
        Log.i(TAG, "X11 service created in pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The Linux foreground service owns user-visible session persistence.
        // Do not resurrect X11 by itself after Stop Server or an app shutdown.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping dedicated X11 service process")
        serverThread.quitSafely()
        super.onDestroy()
        // libXlorie owns native threads that are not stopped by destroying the
        // Android Service object. This process hosts X11 only, so terminate it
        // to guarantee the next launch receives a clean server and socket.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun ensureServerStarted(): Boolean {
        if (started) return startSucceeded

        val latch: CountDownLatch
        synchronized(stateLock) {
            if (started) return startSucceeded
            val existingLatch = startLatch
            if (existingLatch != null) {
                latch = existingLatch
            } else {
                latch = CountDownLatch(1)
                startLatch = latch
                serverHandler.post {
                    startSucceeded = try {
                        configureEnvironment()
                        Log.i(TAG, "Starting native X server in pid=${android.os.Process.myPid()}")
                        CmdEntryPoint.start(arrayOf(":0", "-nolock"))
                    } catch (error: Throwable) {
                        Log.e(TAG, "Native X server failed to start", error)
                        false
                    }
                    started = true
                    latch.countDown()
                    Log.i(TAG, "Native X server start result=$startSucceeded")
                }
            }
        }

        try {
            latch.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return startSucceeded
    }

    private fun configureEnvironment() {
        val appTmpDir = File(filesDir, "tmp").apply { mkdirs() }
        File(appTmpDir, ".X11-unix").mkdirs()

        Os.setenv("TMPDIR", appTmpDir.absolutePath, true)
        Os.setenv("XDG_RUNTIME_DIR", appTmpDir.absolutePath, true)
        Os.setenv("PREFIX", File(filesDir, "usr").absolutePath, true)
        Os.setenv("HOME", filesDir.absolutePath, true)

        val installedXkbRoot = File(filesDir, "usr/share/X11/xkb")
        val rootfsXkbRoot = File(filesDir, "rootfs/usr/share/X11/xkb")
        val xkbRoot = if (installedXkbRoot.exists()) installedXkbRoot else rootfsXkbRoot
        if (xkbRoot.exists()) {
            Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
        } else {
            Log.w(TAG, "XKB config root not found")
        }

        val staleSocket = File(appTmpDir, ".X11-unix/X0")
        if (staleSocket.exists() && !staleSocket.delete()) {
            Log.w(TAG, "Could not remove stale X11 socket ${staleSocket.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "X11ServerService"
    }
}
