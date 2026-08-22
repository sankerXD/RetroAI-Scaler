package com.retroai.scaler.detector

/**
 * "The core we just met draws on the GPU, and which one it was."
 *
 * A core that renders on the GPU hands libretro a sentinel instead of pixels,
 * so the direct frame source has nothing to take and the service that found
 * that out stops immediately. This is the note it leaves behind: it is what
 * puts the screen-recording card on the main screen, and - through the core
 * name - what tells the capture route which console to configure RetroArch
 * for.
 *
 * ## In memory, deliberately
 *
 * This used to be a SharedPreferences flag, which meant that once a
 * PlayStation had been met the card was on the main screen for good, offering
 * screen recording to someone about to play a Game Boy game.
 *
 * The note is now exactly as durable as the fact behind it. It is set when
 * such a core is met, dropped when direct frames prove the current core needs
 * none of this, dropped when a capture session ends, and gone with the process
 * - so restarting the app clears the card. The recovery is the same action
 * that raised it in the first place: launch that game again and the shim says
 * `hw_render=1` within seconds.
 *
 * The service and the activity share a process, so a note left by the service
 * stopping is still there when the player opens the app to act on it. Only a
 * real process death loses it, and that also loses the running service, which
 * has to be started again anyway.
 */
object HardwareCoreNotice {

    @Volatile private var pending = false

    /** Core filename as the shim reported it, e.g. `swanstation_libretro_android.so`. */
    @Volatile private var core: String? = null

    /**
     * Never clears a known name with an unknown one. The name is the more
     * important half - it is the only honest answer to "which console is this
     * capture session for", and getting that wrong writes a viewport into some
     * other platform's config, where it surfaces days later.
     */
    fun remember(coreFile: String?) {
        pending = true
        if (coreFile != null) core = coreFile
    }

    fun forget() {
        pending = false
        core = null
    }

    /** True while screen recording is the answer to something. */
    fun pending(): Boolean = pending

    /** The GPU-rendering core that raised this notice, if it is still known. */
    fun coreFile(): String? = core
}
