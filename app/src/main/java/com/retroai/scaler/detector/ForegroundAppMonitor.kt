package com.retroai.scaler.detector

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Tracks which app is in the foreground so the overlay only paints while the
 * target emulator is actually on screen.
 *
 * Without this the enhanced image covers the whole screen in every app - the
 * launcher, Recents, Settings - and the user is left staring at a magnified
 * corner of whatever they were looking at, unable to see anything else.
 *
 * Uses UsageStatsManager (needs the "usage access" special permission, granted
 * from Settings). No accessibility service, no root.
 */
class ForegroundAppMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ForegroundAppMonitor"

        /** Package names RetroArch ships under. */
        val RETROARCH_PACKAGES = listOf(
            "com.retroarch.aarch64",
            "com.retroarch",
            "com.retroarch.ra32"
        )

        fun hasUsageAccess(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        fun usageAccessSettingsIntent(): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Foreground package as of the last poll. Events only arrive when the
     * foreground app CHANGES, so the last observed value has to be remembered -
     * querying a short window and finding nothing means "unchanged", not
     * "nothing is running".
     */
    private var lastKnownPackage: String? = null
    private var lastQueryEndMs: Long = 0L

    fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        // First call looks back far enough to find the current app; later calls
        // only need the slice since the previous poll.
        val begin = if (lastQueryEndMs == 0L) now - 60_000L else lastQueryEndMs - 1_000L
        lastQueryEndMs = now

        try {
            val events = usageStatsManager.queryEvents(begin, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> lastKnownPackage = event.packageName

                    // Recents, the notification shade and the power menu are
                    // system UI: they never emit ACTIVITY_RESUMED for a new
                    // package, so tracking resumes alone leaves the emulator
                    // looking like it is still in front. The overlay then keeps
                    // painting a full-screen picture of a game that has stopped
                    // producing frames - a frozen image covering everything,
                    // which reads to the user as the device having hung.
                    //
                    // The target pausing is the signal that we are no longer in
                    // the game, whatever took its place.
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (event.packageName == lastKnownPackage) lastKnownPackage = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed", e)
        }
        return lastKnownPackage
    }

    /** Launchable apps, for the target picker. */
    fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                AppEntry(pkg, info.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** First installed RetroArch build, or null. */
    fun detectRetroArch(): String? {
        val pm = context.packageManager
        for (pkg in RETROARCH_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // keep looking
            }
        }
        // Fall back to anything launchable that looks like RetroArch.
        return launchableApps().firstOrNull {
            it.packageName.contains("retroarch", ignoreCase = true)
        }?.packageName
    }

    fun labelFor(packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    data class AppEntry(val packageName: String, val label: String)
}

/** Which app the overlay should follow. Shared by the Activity and the Service. */
object TargetAppPreference {
    private const val PREFS = "retro_ai_prefs"
    private const val KEY_TARGET = "target_package"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TARGET, null)

    fun set(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET, packageName)
            .apply()
    }
}
