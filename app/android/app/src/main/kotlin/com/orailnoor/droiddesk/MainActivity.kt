package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.BuildConfig
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val TAG = "MainActivity"
    }

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)

        // Developer/auto-tester path only. MainActivity is exported (it is the
        // launcher), so honoring this extra in release builds would let any
        // third-party app drive the privileged root-setup pipeline. Restrict it
        // to debug builds.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("autoSetup", false)) {
            runAutoChrootSetup()
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction. Debug builds only.
     */
    private fun runAutoChrootSetup() {
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!chrootRuntime.hasRoot()) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Auto-setup requires root", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                startForegroundService()

                if (!chrootRuntime.isRootfsReady()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    if (!dlOk) throw RuntimeException("Rootfs download failed")

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    if (!exOk) throw RuntimeException("Rootfs extraction failed")
                }

                if (!chrootRuntime.isDesktopInstalled()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, _ ->
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    if (!inOk) throw RuntimeException("Desktop installation failed")
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                runOnUiThread {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", true)
                        putExtra("mode", "chroot")
                        putExtra("de", "xfce4")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup failed", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Auto-setup failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    val rooted = chrootRuntime.hasRoot()
                    result.success(mapOf(
                        "isBootstrapped" to if (rooted) chrootRuntime.isRootfsReady() else linuxRuntime.isBootstrapped(),
                        "isRunning" to if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning(),
                        "hasRoot" to rooted,
                        "distro" to if (rooted) "ubuntu-chroot" else "termux-native",
                        "installedDE" to if (rooted) {
                            if (chrootRuntime.isDesktopInstalled()) "xfce4" else ""
                        } else {
                            linuxRuntime.getInstalledDE()
                        },
                        "rootfsPath" to if (rooted) chrootRuntime.getRootfsPath() else "",
                        "rootfsSizeMB" to if (rooted) chrootRuntime.getRootfsSizeMB() else 0L
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to if (chrootRuntime.hasRoot()) {
                            "Software (llvmpipe)"
                        } else {
                            linuxRuntime.getGraphicsMode()
                        },
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Root checks ──
                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onDownloadProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onExtractProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to status)
                                            )
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onTerminalOutput",
                                                mapOf("text" to logChunk)
                                            )
                                        }
                                    }
                                }
                            )
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        linuxRuntime.setInstallLogSink { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    val status = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.getOptionalAppsStatus()
                    } else {
                        linuxRuntime.getOptionalAppsStatus()
                    }
                    result.success(status)
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    thread {
                        val logSink: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installOptionalApp(appId, progressSink, logSink)
                        } else {
                            linuxRuntime.setInstallLogSink(logSink)
                            try {
                                linuxRuntime.installOptionalApp(appId, progressSink)
                            } finally {
                                linuxRuntime.setInstallLogSink(null)
                            }
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "x11"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080

                    if (height > 720) {
                        val scale = 720.0 / height
                        width = (width * scale).toInt()
                        height = 720
                    }

                    startForegroundService()

                    if (chrootRuntime.hasRoot()) {
                        // Rooted fast path: chroot + LorieView
                        thread {
                            if (!chrootRuntime.isRootfsReady()) {
                                Log.w(TAG, "Chroot rootfs not ready; cannot start session")
                                runOnUiThread { result.success(false) }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "chroot")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    } else {
                        // Non-root fallback: native Termux path
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            val installed = linuxRuntime.getInstalledDE()
                            val ready = installed == desktopEnv ||
                                linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                            if (!ready) {
                                Log.e(TAG, "Native Termux desktop setup failed; session was not launched")
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Native Linux setup failed. Check the setup log.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    result.success(false)
                                }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "termux")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java)
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    thread(name = "stop-linux-session") {
                        if (chrootRuntime.hasRoot() || chrootRuntime.isRunning()) {
                            chrootRuntime.stopSession()
                        }
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        } else {
                            linuxRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "setupBootstrap" -> {
                    if (chrootRuntime.hasRoot()) {
                        // Nothing to bootstrap for chroot; rootfs handles it
                        result.success(true)
                    } else {
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
