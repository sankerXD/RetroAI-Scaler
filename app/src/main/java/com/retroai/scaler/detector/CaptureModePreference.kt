package com.retroai.scaler.detector

import android.content.Context

/**
 * Remembers that the last core we met draws on the GPU.
 *
 * The service that learns this stops immediately afterwards - there is nothing
 * for it to do - so by the time anyone opens the app to act on it, the fact is
 * gone with the process. Persisting it is what lets the screen-recording button
 * be there when it is the answer, and absent the rest of the time.
 *
 * Cleared as soon as frames do arrive, because that is proof the current core
 * needs none of this.
 */
object CaptureModePreference {
    private const val PREFS = "capture_mode"
    private const val KEY_SAW_HW = "saw_hardware_core"

    fun rememberHardwareCore(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAW_HW, true).apply()
    }

    fun forgetHardwareCore(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAW_HW, false).apply()
    }

    fun sawHardwareCore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAW_HW, false)
}
