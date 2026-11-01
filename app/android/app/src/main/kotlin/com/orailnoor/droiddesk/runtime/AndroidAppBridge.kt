package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

private const val LEGACY_LAUNCHER_SOCKET = "droiddesk.android-app-launcher"

internal fun androidLauncherSocketName(packageName: String): String =
    if (packageName == "com.orailnoor.droiddesk") LEGACY_LAUNCHER_SOCKET else "$LEGACY_LAUNCHER_SOCKET.$packageName"

/**
 * Makes Android launcher apps available to the Linux desktop without granting
 * the Linux processes any extra Android permissions.  XFCE entries connect to
 * this app-private Unix socket; the Android service then launches the chosen
 * activity through the normal Android API.
 */
object AndroidAppBridge {
    private const val TAG = "AndroidAppBridge"
    @Volatile private var server: LocalServerSocket? = null

    fun listApps(context: Context): List<Map<String, String>> = launcherActivities(context).map {
        mapOf(
            "label" to it.loadLabel(context.packageManager).toString(),
            "package" to it.activityInfo.packageName,
            "source" to "Android",
        )
    }

    fun launchAndroidPackage(context: Context, packageName: String): Boolean =
        launchPackage(context, packageName)

    fun getDockPackages(context: Context): List<String> {
        val preferences = context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
        if (preferences.contains("dock_packages")) {
            return preferences.getString("dock_packages", "").orEmpty()
                .split(',').filter(String::isNotBlank)
        }
        return defaultDockPackages(launcherActivities(context))
    }

    fun setDockPackages(context: Context, packages: List<String>) {
        val installed = launcherActivities(context).map { it.activityInfo.packageName }.toSet()
        val safe = packages.filter { it in installed }.distinct().take(8)
        context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
            .edit().putString("dock_packages", safe.joinToString(",")).commit()
    }

    fun start(context: Context) {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            try {
                val socket = LocalServerSocket(androidLauncherSocketName(context.packageName))
                server = socket
                thread(name = "android-app-bridge", isDaemon = true) {
                    serve(context.applicationContext, socket)
                }
                Log.i(TAG, "Android app launcher bridge started")
            } catch (error: Exception) {
                Log.e(TAG, "Could not start Android app launcher bridge", error)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            runCatching { server?.close() }
            server = null
        }
    }

    fun syncLaunchers(context: Context, homeDir: File, python: File, sessionRoot: File? = null) {
        if (!python.canExecute()) {
            Log.w(TAG, "Python is unavailable; Android app launchers were not synced")
            return
        }
        val appsDir = File(homeDir, ".local/share/applications/droiddesk-android").apply { mkdirs() }
        appsDir.listFiles()?.forEach { it.delete() }
        val iconsDir = File(homeDir, ".local/share/icons/droiddesk-android").apply { mkdirs() }
        iconsDir.listFiles()?.forEach { it.delete() }

        val launcher = File(homeDir, ".local/bin/droiddesk-launch-android-app.py")
        fun sessionPath(file: File): String = if (sessionRoot == null) {
            file.absolutePath
        } else {
            "/" + file.relativeTo(sessionRoot).invariantSeparatorsPath
        }
        val pythonPath = sessionPath(python)
        val launcherPath = sessionPath(launcher)
        launcher.parentFile?.mkdirs()
        launcher.writeText(
            """
            #!$pythonPath
            import socket
            import sys

            if len(sys.argv) != 2:
                raise SystemExit("Expected an Android package name")
            client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            client.connect("\0${androidLauncherSocketName(context.packageName)}")
            client.sendall((sys.argv[1] + "\n").encode())
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val activities = launcherActivities(context)

        activities.forEach { activity ->
            val packageName = activity.activityInfo.packageName
            val label = desktopEscape(activity.loadLabel(context.packageManager).toString())
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val iconFile = File(iconsDir, "$safeName.png")
            val icon = if (writeIconPng(activity.loadIcon(context.packageManager), iconFile)) {
                sessionPath(iconFile)
            } else {
                "applications-other"
            }
            val filename = "$safeName.desktop"
            File(appsDir, filename).writeText(
                """
                [Desktop Entry]
                Version=1.0
                Type=Application
                Name=$label
                Comment=Open Android app: $packageName
                Exec=$pythonPath $launcherPath $packageName
                Icon=$icon
                Terminal=false
                Categories=Utility;
                StartupNotify=false
                """.trimIndent() + "\n",
            )
        }
        syncUtilityLaunchers(homeDir, pythonPath, launcherPath, sessionPath(homeDir))
        syncDockLaunchers(context, homeDir, activities, appsDir)
        Log.i(TAG, "Synced ${activities.size} Android app launchers into ${appsDir.absolutePath}")
    }

    private fun syncUtilityLaunchers(
        homeDir: File,
        pythonPath: String,
        launcherPath: String,
        homePath: String,
    ) {
        val appsDir = File(homeDir, ".local/share/applications/droiddesk-tools").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val folders = listOf(
            Triple("home", "Home folder", homePath),
            Triple("downloads", "Downloads", "$homePath/Downloads"),
            Triple("documents", "Documents", "$homePath/Documents"),
            Triple("pictures", "Pictures", "$homePath/Pictures"),
        )
        folders.forEach { (id, label, path) ->
            File(homeDir, id.replaceFirstChar { it.uppercase() }).takeIf { id != "home" }?.mkdirs()
            File(appsDir, "droiddesk-folder-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open $label in Linux Files
                Exec=thunar ${desktopEscape(path)}
                Icon=folder
                Terminal=false
                Categories=Utility;FileManager;
                Keywords=files;folder;search;$id;
                """.trimIndent() + "\n",
            )
        }
        listOf(
            Triple("wifi", "Wi-Fi settings", "network-wireless"),
            Triple("bluetooth", "Bluetooth settings", "bluetooth"),
            Triple("display", "Display and brightness", "video-display"),
            Triple("sound", "Sound and volume", "audio-volume-high"),
            Triple("hotspot", "Mobile hotspot", "network-transmit-receive"),
            Triple("battery", "Battery saver", "battery"),
        ).forEach { (id, label, icon) ->
            File(appsDir, "droiddesk-setting-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open Android $label
                Exec=$pythonPath $launcherPath action:$id
                Icon=$icon
                Terminal=false
                Categories=Settings;System;
                Keywords=phone;android;control center;$id;
                """.trimIndent() + "\n",
            )
        }

    }

    private fun syncDockLaunchers(
        context: Context,
        homeDir: File,
        activities: List<android.content.pm.ResolveInfo>,
        appsDir: File,
    ) {
        val byPackage = activities.associateBy { it.activityInfo.packageName }
        val dockEntries = getDockPackages(context).mapIndexedNotNull { index, packageName ->
            if (!byPackage.containsKey(packageName)) return@mapIndexedNotNull null
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val source = File(appsDir, "$safeName.desktop")
            if (!source.isFile) return@mapIndexedNotNull null
            val id = 30 + index
            val dockDir = File(homeDir, ".config/xfce4/panel/launcher-$id").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val dockFile = File(dockDir, "droiddesk-android-$safeName.desktop")
            source.copyTo(dockFile, overwrite = true)
            id to dockFile.name
        }

        val panelFile = File(homeDir, ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml")
        if (!panelFile.isFile) return
        val idStart = "<!-- DroidDesk Android dock ids start -->"
        val idEnd = "<!-- DroidDesk Android dock ids end -->"
        val pluginStart = "<!-- DroidDesk Android dock plugins start -->"
        val pluginEnd = "<!-- DroidDesk Android dock plugins end -->"
        // xfconfd rewrites this file without our comment markers, so also drop
        // any dock ids/launchers left over from earlier sessions; otherwise
        // every restart appends another (iconless) copy of the dock apps.
        var xml = panelFile.readText()
            .replace(managedXmlBlock(idStart, idEnd), "")
            .replace(managedXmlBlock(pluginStart, pluginEnd), "")
            .replace(staleDockIdValue, "")
            .replace(staleDockPluginBlock, "")

        val idNeedle = Regex("<value type=\\\"int\\\" value=\\\"24\\\"/>")
        idNeedle.find(xml)?.let { match ->
            val indent = "        "
            val replacement = buildString {
                append('\n').append(indent).append(idStart).append('\n')
                dockEntries.forEach { (id, _) ->
                    append(indent).append("<value type=\"int\" value=\"$id\"/>").append('\n')
                }
                append(indent).append(idEnd).append('\n').append(match.value)
            }
            xml = xml.replaceRange(match.range, replacement)
        }

        val pluginNeedle = Regex("<property name=\\\"plugin-24\\\"")
        pluginNeedle.find(xml)?.let { match ->
            val indent = "    "
            val pluginBlock = buildString {
                append('\n').append(indent).append(pluginStart).append('\n')
                dockEntries.forEach { (id, filename) ->
                    append(
                        """
                        <property name="plugin-$id" type="string" value="launcher">
                          <property name="items" type="array">
                            <value type="string" value="$filename"/>
                          </property>
                        </property>
                        """.trimIndent().prependIndent(indent),
                    )
                    append('\n')
                }
                append(indent).append(pluginEnd).append('\n')
            }
            xml = xml.replaceRange(match.range, pluginBlock + match.value)
        }
        panelFile.writeText(xml)
        Log.i(TAG, "Added ${dockEntries.size} installed Android apps to the XFCE dock")
    }

    /** Updates the active xfconf session; editing its XML file alone is not enough while XFCE is running. */
    fun xfceDockCommand(context: Context): String {
        val dock = getDockPackages(context).mapIndexed { index, packageName ->
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            (30 + index) to "droiddesk-android-$safeName.desktop"
        }
        val pluginIds = listOf(20, 21, 22, 23) + dock.map { it.first } + listOf(24)
        return buildString {
            for (id in 30..37) {
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id -r >/dev/null 2>&1 || true; ")
            }
            append("xfconf-query -c xfce4-panel -p /panels/panel-2/plugin-ids -a ")
            pluginIds.forEach { id -> append("-t int -s $id ") }
            append(">/dev/null 2>&1; ")
            dock.forEach { (id, filename) ->
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id -n -t string -s launcher >/dev/null 2>&1; ")
                append("xfconf-query -c xfce4-panel -p /plugins/plugin-$id/items -n -a -t string -s '$filename' >/dev/null 2>&1; ")
            }
        }
    }

    /** Dock launchers use plugin ids 30..37 (see [xfceDockCommand]). */
    private val staleDockIdValue = Regex("""[ \t]*<value type="int" value="3[0-7]"/>\r?\n?""")
    private val staleDockPluginBlock = Regex(
        """[ \t]*<property name="plugin-3[0-7]" type="string" value="launcher">\s*<property name="items" type="array">.*?</property>\s*</property>\r?\n?""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun managedXmlBlock(start: String, end: String): Regex = Regex(
        "${Regex.escape(start)}.*?${Regex.escape(end)}",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun serve(context: Context, socket: LocalServerSocket) {
        while (server === socket) {
            var client: LocalSocket? = null
            try {
                client = socket.accept()
                val command = client.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                if (command.startsWith("action:")) {
                    launchSystemAction(context, command.removePrefix("action:"))
                } else {
                    launchPackage(context, command)
                }
            } catch (error: Exception) {
                if (server === socket) Log.w(TAG, "Android app launcher request failed", error)
            } finally {
                runCatching { client?.close() }
            }
        }
    }

    private fun launchPackage(context: Context, packageName: String): Boolean {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "Could not launch Android package $packageName", it) }
            .getOrDefault(false)
    }

    fun launchSystemAction(context: Context, action: String) {
        val settingsAction = when (action) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Could not open Android setting $action", it) }
    }

    private fun launcherActivities(context: Context): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
    }

    private fun defaultDockPackages(
        activities: List<android.content.pm.ResolveInfo>,
    ): List<String> {
        val installed = activities.map { it.activityInfo.packageName }.toSet()
        // Keep the default dock short; more apps can be pinned from Desktop Tools.
        return listOf("com.android.chrome").filter { it in installed }
    }

    private fun desktopEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", " ")

    private fun writeIconPng(drawable: android.graphics.drawable.Drawable, output: File): Boolean {
        return runCatching {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            bitmap.recycle()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not export Android app icon to ${output.name}", error)
            false
        }
    }
}
