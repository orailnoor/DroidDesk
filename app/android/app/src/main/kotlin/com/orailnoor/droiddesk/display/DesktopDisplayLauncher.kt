package com.orailnoor.droiddesk.display

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.service.DroidDeskService

class DesktopDisplayLauncher {
    companion object {
        fun intent(context: Context, displayId: Int, displayKey: String, requestId: Long): Intent =
            Intent(context, DesktopActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                data = Uri.parse("droiddesk://desktop/$requestId")
                putExtra("displayId", displayId)
                putExtra("displayKey", displayKey)
                putExtra("displayRequestId", requestId)
            }
    }
}

internal class AndroidDesktopDisplayHost(
    private val context: Context,
    private val repository: DisplayRepository,
) : DesktopDisplayHost {
    private val handler = Handler(Looper.getMainLooper())
    private val activities = context.getSystemService(ActivityManager::class.java)

    override fun displays() = repository.snapshot()

    override fun defaultProfileKind(): ProfileKind {
        val configuration = context.resources.configuration
        return DisplayProfilePolicy.classify(
            true, configuration.smallestScreenWidthDp,
            configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK,
        )
    }

    override fun sessionRunning() = ChrootRuntime(context).isRunning() || LinuxRuntime(context).isRunning()

    override fun launch(request: DesktopLaunchRequest) {
        val current = repository.requireDisplay(request.display.key)
        if (current.id != request.display.id || !current.eligible) {
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display is no longer launchable")
        }
        val intent = DesktopDisplayLauncher.intent(context, current.id, current.key, request.requestId).apply {
            putExtra("sessionGeneration", request.sessionGeneration)
            putExtra("mode", request.mode)
            putExtra("de", request.desktopEnv)
        }
        try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(current.id).toBundle())
            Log.i("DesktopDisplay", "Launch request=${request.requestId} display=${current.id}")
        } catch (error: SecurityException) {
            Log.w("DesktopDisplay", "Android denied desktop placement", error)
            throw DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android denied activity placement on this display")
        } catch (error: IllegalArgumentException) {
            Log.w("DesktopDisplay", "Display disappeared during launch", error)
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The display disconnected during launch")
        } catch (error: ActivityNotFoundException) {
            Log.e("DesktopDisplay", "Native desktop activity is unavailable", error)
            throw DesktopDisplayException("DISPLAY_NOT_ALLOWED", "The native desktop activity could not be opened")
        }
    }

    override fun focus(requestId: Long): Boolean {
        val task = activities.appTasks.firstOrNull {
            it.taskInfo.baseIntent?.data == Uri.parse("droiddesk://desktop/$requestId")
        } ?: return false
        task.moveToFront()
        return true
    }

    override fun cancelLaunch(requestId: Long) {
        activities.appTasks.filter {
            it.taskInfo.baseIntent?.data == Uri.parse("droiddesk://desktop/$requestId")
        }.forEach { it.finishAndRemoveTask() }
    }

    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
        val timeout = Runnable(action)
        handler.postDelayed(timeout, delayMillis)
        return { handler.removeCallbacks(timeout) }
    }

    override fun notifyReturnRequired(error: DesktopDisplayException) {
        Log.e("DesktopDisplay", "Phone return requires user action: ${error.code}: ${error.message}")
        DroidDeskService.notifyReturnRequired(context, error.message ?: "Phone placement was not confirmed")
    }
}
