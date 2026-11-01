package com.orailnoor.droiddesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.runtime.AndroidAppSync
import com.orailnoor.droiddesk.runtime.RootShell
import java.util.concurrent.Executors
import java.lang.ref.WeakReference
import java.io.IOException

/**
 * Foreground service that keeps the Linux runtime alive.
 *
 * Android aggressively kills background processes (especially Android 12+'s
 * Phantom Process Killer). This service ensures our native Termux/chroot session, desktop
 * environment, and Wayland compositor survive when the user switches apps.
 */
class DroidDeskService : Service() {

    companion object {
        const val CHANNEL_ID = "droiddesk_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "DroidDeskService"
        private const val ACTION_STOP = "com.orailnoor.droiddesk.action.STOP_DESKTOP"
        private val sessionExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "DesktopSessionOperations") }
        private val mainHandler = Handler(Looper.getMainLooper())
        private var activeService: WeakReference<DroidDeskService>? = null
        @Volatile private var legacyIntentProcess: Process? = null
        @Volatile private var legacyIntentActive = false

        fun executeSession(operation: () -> Unit) {
            sessionExecutor.execute(operation)
        }

        fun stopDesktop(context: Context, completion: (Result<Unit>) -> Unit) {
            val app = context.applicationContext
            val coordinator = DesktopDisplayCoordinator.shared
            coordinator.startStopping()
            executeSession {
                var failure: Exception? = null
                val operations: List<() -> Unit> = listOf(
                    { stopLegacyIntentBridge() },
                    { ChrootRuntime(app).let { if (it.isRunning()) it.stopSession() } },
                    { LinuxRuntime(app).stopSession() },
                    { app.stopService(Intent(app, X11ServerService::class.java)); Unit },
                )
                for (operation in operations) {
                    try {
                        operation()
                    } catch (error: Exception) {
                        Log.e(TAG, "Desktop teardown operation failed", error)
                        if (failure == null) failure = error
                    }
                }
                mainHandler.post {
                    coordinator.finishStopping()
                    val error = failure
                    if (error == null) {
                        activeService?.get()?.let {
                            ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        }
                        app.stopService(Intent(app, DroidDeskService::class.java))
                        completion(Result.success(Unit))
                    } else {
                        activeService?.get()?.updateNotification("Desktop stop failed. Try Stop again.")
                        completion(Result.failure(error))
                    }
                }
            }
        }

        fun ensureLegacyIntentBridge(context: Context, runtime: ChrootRuntime) {
            if (legacyIntentActive) return
            val pipe = runtime.getRootfsPath() + "/tmp/android_intent_pipe"
            val prepared = runtime.executeCommand(
                "mkdir -p /tmp; if [ -e /tmp/android_intent_pipe ]; then " +
                    "test -p /tmp/android_intent_pipe || exit 1; else mkfifo /tmp/android_intent_pipe; fi; " +
                    "chmod 666 /tmp/android_intent_pipe",
            ) { Log.d(TAG, it) }
            check(prepared == "Exit code: 0") { "Could not prepare the Android launcher pipe: $prepared" }
            AndroidAppSync.syncApps(context.applicationContext, runtime.getRootfsPath())
            val su = RootShell(context).findSuPath() ?: error("Root launcher bridge has no su binary")
            val process = ProcessBuilder(su, "-c", "tail -f '$pipe'").start()
            legacyIntentProcess = process
            legacyIntentActive = true
            thread(name = "DesktopAndroidIntentBridge") {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (legacyIntentActive) {
                            val packageName = reader.readLine()?.trim() ?: break
                            if (packageName.isEmpty()) continue
                            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (intent == null) {
                                Log.w(TAG, "No Android launcher for $packageName")
                            } else {
                                mainHandler.post {
                                    if (legacyIntentActive && legacyIntentProcess === process) {
                                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (legacyIntentActive && legacyIntentProcess === process) {
                        Log.e(TAG, "Android launcher pipe disconnected", error)
                    }
                } finally {
                    if (legacyIntentProcess === process) {
                        legacyIntentProcess = null
                        legacyIntentActive = false
                    }
                    process.destroy()
                }
            }
        }

        private fun stopLegacyIntentBridge() {
            legacyIntentActive = false
            legacyIntentProcess?.destroy()
            legacyIntentProcess = null
        }

        fun notifyReturnRequired(context: Context, message: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "DroidDesk Linux Service", NotificationManager.IMPORTANCE_LOW,
            ))
            val returnIntent = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_RETURN_TO_PHONE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopIntent = PendingIntent.getService(
                context, 1, Intent(context, DroidDeskService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val running = ChrootRuntime(context).isRunning() || LinuxRuntime(context).isRunning()
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(if (running) "Linux session preserved" else "Desktop placement needs attention")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(returnIntent)
                .addAction(0, "Return to phone", returnIntent)
                .addAction(0, "Stop", stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build())
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var startupNotification: DesktopStartupNotification? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        DesktopDisplayCoordinator.shared.initialize(applicationContext)
        AndroidAppBridge.start(this)
        createNotificationChannel()
        startupNotification = DesktopStartupNotification(DesktopDisplayCoordinator.shared, ::updateNotification)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDesktop()
            return START_NOT_STICKY
        }
        val notification = buildNotification(checkNotNull(startupNotification).text)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        startupNotification?.close()
        startupNotification = null
        AndroidAppBridge.stop()
        releaseWakeLock()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    /** Same teardown as the Flutter "stopLinux" call, triggered from the notification. */
    private fun stopDesktop() {
        if (stopping) return
        stopping = true
        stopDesktop(applicationContext) { outcome ->
            if (outcome.isFailure) stopping = false
        }
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidDesk Linux Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux desktop environment running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Brings the existing task forward (showing the desktop if it is open); otherwise
        // MainActivity reopens the running session without restarting it.
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DESKTOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DroidDeskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidDesk")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── Wake Lock ──

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DroidDesk::LinuxRuntime"
        ).apply {
            acquire(Long.MAX_VALUE)  // Keep CPU alive
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
