package com.orailnoor.droiddesk.runtime

import android.app.ActivityManager
import android.content.Context

/**
 * Coarse hardware tiering used to keep DroidDesk usable on low-RAM phones
 * (e.g. Samsung Galaxy A10s, 2 GB). Low-RAM devices get a lighter desktop
 * default and a lower X server render resolution.
 */
object DeviceProfile {
    /** Devices at or below this total RAM are treated as low-end. */
    const val LOW_RAM_THRESHOLD_MB = 3072L

    // Always resolve the ActivityManager through the application context.
    // Activities cannot serve getSystemService before onCreate() completes, and
    // configureDisplayScale() may run from an Activity in that window.
    private fun activityManager(context: Context): ActivityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun totalRamMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager(context).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun isLowRam(context: Context): Boolean {
        val am = activityManager(context)
        if (am.isLowRamDevice) return true
        val ram = totalRamMb(context)
        return ram in 1..LOW_RAM_THRESHOLD_MB
    }
}
