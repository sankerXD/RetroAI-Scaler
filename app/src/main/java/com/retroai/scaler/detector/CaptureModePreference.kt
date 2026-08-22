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
    private const val KEY_CORE = "hardware_core_file"

    /**
     * [coreFile] is stored with the flag, and it is the more important half.
     *
     * The capture route has to write RetroArch's viewport for a specific
     * console, and the ONLY honest source for which console that is, is the
     * core the shim reported. Deriving it from a stored "current console"
     * instead is what wrote a PlayStation session's viewport into mGBA's
     * override and left a Game Boy Advance drawing into a corner days later.
     *
     * The name lives in a static while the link service runs and dies with the
     * process; the button that acts on it can be pressed much later.
     */
    fun rememberHardwareCore(context: Context, coreFile: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAW_HW, true)
            .apply {
                // Never overwrite a known name with nothing: the flag and the
                // name are what the whole capture route is configured from.
                if (coreFile != null) putString(KEY_CORE, coreFile)
            }
            .apply()
    }

    fun forgetHardwareCore(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAW_HW, false).remove(KEY_CORE).apply()
    }

    fun sawHardwareCore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAW_HW, false)

    /** The GPU-rendering core that sent us here, if one is still remembered. */
    fun hardwareCoreFile(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CORE, null)
}
