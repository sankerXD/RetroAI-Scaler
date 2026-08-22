package com.retroai.scaler.shim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.retroai.scaler.MainActivity
import com.retroai.scaler.R
import com.retroai.scaler.jni.NativeBridge
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Gate 3 (NewSolution.md §7): receive the shim's frames and prove they are the
 * same picture RetroArch draws.
 *
 * This deliberately does NOT render. Gate 3 answers three questions - is the
 * geometry, rate and format right, is the picture pixel-identical to the
 * existing capture path, and is retro_run any slower - and wiring the renderer
 * in first would let a mistake in any of them hide behind a plausible-looking
 * image. Rendering is gate 4.
 *
 * The listener binds an ephemeral port on 127.0.0.1 and publishes it, with a
 * random token, to a file on shared storage that RetroArch can read because it
 * holds legacy storage. Loopback is reachable by any app holding INTERNET, so
 * the token is what stops another app from reading someone's game frames by
 * guessing a port; and binding the loopback address specifically, never the
 * wildcard, is what keeps them off the local network entirely.
 */
class ShimFrameService : Service() {

    /**
     * Where frames go once received. Null until a frame source registers,
     * which is what makes this service useful both as gate 3's measuring
     * instrument on its own and as the socket end of the real pipeline.
     *
     * Called on the socket thread with the receiver's own buffer, which is
     * valid only for the duration of the call.
     */
    fun interface FrameListener {
        fun onFrame(data: ByteArray, width: Int, height: Int, pitch: Int, format: Int)
    }

    companion object {
        private const val TAG = "RetroAI_ShimLink"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "retro_ai_shim_probe"

        private const val HEADER_BYTES = 64
        private const val FRAME_MAGIC = 0x31494152  // "RAI1"
        private const val HELLO_MAGIC = 0x48494152  // "RAIH"
        private const val NOTICE_MAGIC = 0x43494152 // "RAIC"
        private const val WIRE_VERSION = 1

        /** Cap on a single frame, so a corrupt length cannot make us allocate
         *  wildly. 1024x768x4 is far above anything a 2D core emits. */
        private const val MAX_PAYLOAD = 1024 * 768 * 4

        @Volatile
        var isRunning: Boolean = false
            private set

        val framesReceived = AtomicLong(0)
        val bytesReceived = AtomicLong(0)

        @Volatile var lastWidth = 0
        @Volatile var lastHeight = 0
        @Volatile var lastPitch = 0
        @Volatile var lastFormat = -1
        @Volatile var lastRotation = 0
        @Volatile var measuredFps = 0.0
        @Volatile var averageFps = 0.0
        @Volatile var connected = false

        /**
         * The loaded core renders on the GPU, so no CPU frames exist to take.
         *
         * The shim knows this the instant the core asks for hardware rendering
         * and says so over the link, because the alternative is the worst kind
         * of failure: the link connects, nothing ever arrives, and four seconds
         * later the watchdog blames screen capture for producing nothing - which
         * is not what happened and sends the reader somewhere else entirely.
         */
        @Volatile var hardwareRenderedCore = false

        /**
         * The real core behind the shim, e.g. "fceumm_libretro_android.so".
         * Identifies the console where the frame size cannot.
         */
        @Volatile var coreFile: String? = null

        /** When a frame last arrived, from any connection. */
        @Volatile private var lastFrameOwnerAtMs = 0L

        /** Set by the UI; the next frame to arrive is written out as a PNG. */
        val dumpRequested = AtomicBoolean(false)

        @Volatile var lastDumpPath: String? = null

        val transcript = CopyOnWriteArrayList<String>()

        @Volatile var frameListener: FrameListener? = null

        fun linkDir(): File =
            File(Environment.getExternalStorageDirectory(), "RetroAIScaler/shim")
    }

    @Volatile private var stopping = false
    @Volatile private var client: Socket? = null
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var token: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        transcript.clear()
        framesReceived.set(0)
        bytesReceived.set(0)
        measuredFps = 0.0
        averageFps = 0.0
        connected = false
        hardwareRenderedCore = false
        coreFile = null
        stopping = false
        isRunning = true

        say("link service starting, api=${Build.VERSION.SDK_INT}")
        thread = Thread({ serve() }, "ShimLink-accept").apply {
            isDaemon = false
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopping = true
        isRunning = false
        connected = false
        // Close the socket rather than interrupt: accept() ignores interrupts.
        try {
            server?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing server socket", t)
        }
        // And the accepted socket: the frame loop blocks indefinitely on read
        // by design, so closing the listener alone would not wake it.
        try {
            client?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing client socket", t)
        }
        // Remove the rendezvous file so the shim stops trying to reach a port
        // nobody is listening on any more.
        try {
            File(linkDir(), "link.txt").delete()
        } catch (t: Throwable) {
            Log.w(TAG, "removing link file", t)
        }
        thread?.join(2000)
        say("link service stopped, frames=${framesReceived.get()}")
        super.onDestroy()
    }

    // ----------------------------------------------------------------- serve

    private fun serve() {
        val socket = try {
            // Port 0: let the kernel choose. A fixed port is one more thing to
            // collide with, and the shim reads the real one out of link.txt.
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            say("FAILED to bind loopback - ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        server = socket
        socket.soTimeout = 1000

        token = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        if (!publishLinkFile(socket.localPort, token)) return
        say("listening on 127.0.0.1:${socket.localPort}")

        while (!stopping) {
            val client = try {
                socket.accept()
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (!stopping) say("accept failed - ${t.javaClass.simpleName}: ${t.message}")
                break
            }
            /*
             * One thread per connection, because more than one shim can be
             * connected at once.
             *
             * The shim is linked NODELETE, so a RetroArch process that switches
             * core keeps the previous shim mapped with its thread and its
             * socket still alive. Handling connections one at a time meant the
             * app sat reading the OLD core's connection - which had nothing
             * left to send - while the core actually running could not get a
             * word in. The visible symptom was the console being identified
             * from the previous game's core: an NES read as a Game Boy Advance.
             */
            Thread({
                try {
                    handleClient(client)
                } catch (t: Throwable) {
                    say("client ended - ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    if (this.client === client) {
                        connected = false
                        this.client = null
                    }
                    try {
                        client.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }, "ShimLink-client").apply { isDaemon = true }.start()
        }
        say("listener exited")
    }

    /**
     * Short `key=value` lines the shim sends out of band. Unknown keys are
     * logged and ignored on purpose, so an older app and a newer shim keep
     * working rather than failing at each other.
     */
    private fun handleNotice(text: String, onCore: (String?) -> Unit) {
        say("notice from shim: ${text.replace("\n", " ")}")
        for (line in text.lineSequence()) {
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            when (key) {
                "hw_render" -> hardwareRenderedCore = value == "1"
                "core" -> {
                    val name = value.takeIf { it.isNotEmpty() && it != "unknown" }
                    onCore(name)
                    /*
                     * A core that renders on the GPU never sends a frame, so
                     * waiting for one to claim the name means the one case that
                     * needs identifying is the one case that never gets
                     * identified. Measured: a PlayStation fell back to screen
                     * capture and was laid out as the previous console.
                     *
                     * So a notice claims it too, but only while nothing is
                     * delivering pictures - otherwise a stale connection still
                     * re-announcing itself every five seconds would keep taking
                     * the name away from the core actually running.
                     */
                    if (name != null &&
                        SystemClock.elapsedRealtime() - lastFrameOwnerAtMs > 3000
                    ) {
                        coreFile = name
                    }
                }
                // Unknown keys are ignored on purpose, so an older app and a
                // newer shim keep working rather than failing at each other.
            }
        }
    }

    private fun publishLinkFile(port: Int, token: String): Boolean {
        return try {
            val dir = linkDir()
            dir.mkdirs()
            // Temp file + rename, the same discipline as every other file this
            // project writes: the shim may read at any moment, and a truncated
            // link.txt would have it connect nowhere with no way to tell why.
            val tmp = File(dir, "link.txt.tmp")
            tmp.writeText("port=$port\ntoken=$token\n")
            val dst = File(dir, "link.txt")
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                say("could not rename link.txt into place")
                return false
            }
            say("published ${dst.absolutePath}")
            true
        } catch (t: Throwable) {
            say("could not publish link file - ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun handleClient(client: Socket) {
        this.client = client
        client.tcpNoDelay = true
        // Bounded for the handshake: a peer that connects and says nothing must
        // not hold the single accept slot forever.
        client.soTimeout = 5000
        val input = DataInputStream(client.getInputStream().buffered(1 shl 16))

        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        val hello = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (hello.getInt(0) != HELLO_MAGIC) {
            say("rejected peer: bad hello magic 0x%08x".format(hello.getInt(0)))
            return
        }
        if (hello.getInt(4) != WIRE_VERSION) {
            say("rejected peer: wire version ${hello.getInt(4)}, expected $WIRE_VERSION")
            return
        }
        val tokenLen = hello.getInt(20)
        if (tokenLen !in 1..64) {
            say("rejected peer: token length $tokenLen")
            return
        }
        val tokenBytes = ByteArray(tokenLen)
        input.readFully(tokenBytes)
        if (String(tokenBytes) != token) {
            say("rejected peer: token mismatch - something other than our shim "
                + "connected to this port")
            return
        }

        connected = true
        // Per connection, and promoted to the shared field only by a frame
        // actually arriving on it. Whichever shim is really running is the one
        // delivering pictures, so that is the one whose core name counts.
        var thisCore: String? = null
        say("shim connected")

        /*
         * No read timeout past this point, and that is not laziness.
         *
         * A frame stream going quiet is a NORMAL state here, not a fault: when
         * RetroArch's menu opens it pauses the core, retro_run stops, and no
         * frames are produced until the menu closes. §4.7 relies on exactly
         * that silence as the signal for the overlay to step aside. A read
         * timeout turns that normal state into a dropped connection - measured,
         * the first run died with "Read timed out" the moment the menu was
         * opened.
         *
         * Nothing is lost by blocking: if RetroArch exits, the socket closes
         * and readFully throws, and if the service is stopping, onDestroy
         * closes this socket underneath us for the same effect.
         */
        client.soTimeout = 0

        var payload = ByteArray(0)
        var windowStart = SystemClock.elapsedRealtime()
        var windowFrames = 0
        /* A ring of recent one-second windows, not a session average.
         *
         * The session average was polluted the first time it was read: the
         * tester fast-forwarded past an intro, retro_run ran flat out, and the
         * mean came back 263fps for a 59.7275fps core. The rate here changes
         * legitimately - fast-forward, pause, menus - so any statistic that
         * remembers the whole run reports a number that was never true at any
         * moment. Ten windows is long enough to settle the one-frame jitter
         * that made a single window read 60.76, and short enough to forget a
         * mode the player has left. */
        val recent = DoubleArray(10)
        var recentCount = 0

        while (!stopping) {
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (h.getInt(0) == NOTICE_MAGIC) {
                val n = h.getInt(20)
                if (n !in 0..256) {
                    say("refusing notice of $n bytes")
                    return
                }
                val text = ByteArray(n).also { if (n > 0) input.readFully(it) }
                handleNotice(String(text)) { thisCore = it }
                continue
            }
            if (h.getInt(0) != FRAME_MAGIC) {
                say("stream desynchronised: magic 0x%08x".format(h.getInt(0)))
                return
            }
            val bytes = h.getInt(20)
            if (bytes !in 1..MAX_PAYLOAD) {
                say("refusing payload of $bytes bytes")
                return
            }
            if (payload.size < bytes) payload = ByteArray(bytes)
            input.readFully(payload, 0, bytes)

            // Delivering pictures is what makes a connection the live one, so
            // it takes ownership of the core name here. Never cleared by a
            // frame: a picture arriving before this connection's notice must
            // not wipe out a name that is already correct.
            thisCore?.let { coreFile = it }
            lastFrameOwnerAtMs = SystemClock.elapsedRealtime()
            lastWidth = h.getShort(28).toInt() and 0xFFFF
            lastHeight = h.getShort(30).toInt() and 0xFFFF
            lastPitch = h.getInt(24)
            lastFormat = h.getShort(32).toInt() and 0xFFFF
            lastRotation = h.getShort(34).toInt() and 0xFFFF
            framesReceived.incrementAndGet()
            bytesReceived.addAndGet((bytes + HEADER_BYTES).toLong())

            windowFrames++
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - windowStart
            if (elapsed >= 1000) {
                val fps = windowFrames * 1000.0 / elapsed
                measuredFps = fps
                recent[recentCount % recent.size] = fps
                recentCount++
                val n = minOf(recentCount, recent.size)
                averageFps = (0 until n).sumOf { recent[it] } / n
                windowStart = now
                windowFrames = 0
            }

            frameListener?.onFrame(payload, lastWidth, lastHeight, lastPitch, lastFormat)

            if (dumpRequested.get() && readyToDump(payload, bytes, now)) {
                dumpRequested.set(false)
                dumpFrame(payload, lastWidth, lastHeight, lastPitch, lastFormat)
            }
        }
    }

    /**
     * Holds the dump back until the picture stops moving.
     *
     * The pair this produces is only meaningful if both halves show the same
     * thing, and they are not grabbed at the same instant: the shim frame is
     * this one, while the capture-path frame comes from whatever the renderer
     * draws a frame or two later. On a moving picture that gap is a real
     * difference, and it would be indistinguishable from the colour drift the
     * whole comparison exists to measure.
     *
     * Two consecutive byte-identical frames means nothing is moving - on a
     * paused game or an in-game menu that happens immediately. The five second
     * fallback is there so a screen that never settles still yields something
     * rather than silently never dumping.
     */
    private var previousFrame: ByteArray? = null
    private var armedAtMs = 0L

    /**
     * Hand-rolled rather than Arrays.equals(a, from, to, b, from, to): that
     * range overload is Java 9, which on Android means API 33, and minSdk here
     * is 30. It compiles against compileSdk 34 without complaint and throws
     * NoSuchMethodError on the device - which it did, on the first run, once
     * per frame, taking the connection down with it each time.
     */
    private fun sameBytes(a: ByteArray, b: ByteArray, n: Int): Boolean {
        if (a.size < n || b.size < n) return false
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    private fun readyToDump(payload: ByteArray, bytes: Int, now: Long): Boolean {
        if (armedAtMs == 0L) armedAtMs = now
        val prev = previousFrame
        val still = prev != null && sameBytes(prev, payload, bytes)
        if (still || now - armedAtMs > 5000) {
            armedAtMs = 0L
            if (!still) say("picture never settled - dumping a moving frame, "
                + "treat any difference as suspect")
            return true
        }
        var keep = prev
        if (keep == null || keep.size < bytes) keep = ByteArray(bytes)
        System.arraycopy(payload, 0, keep, 0, bytes)
        previousFrame = keep
        return false
    }

    /**
     * Grabs the same picture through the OLD capture path and writes it beside
     * the shim frame.
     *
     * This is the measurement risk #1 has been waiting for. The training corpus
     * was collected through MediaProjection, and its colours do not land on the
     * RGB565 grid (12 of 240) while the shim's land on it exactly - so a
     * transform exists somewhere in the capture chain and the model is bound to
     * it. Whether that matters is a number, and the number needs the same
     * content down both paths at the same moment.
     *
     * Returns quietly when AI enhancement is not running in capture mode:
     * there is simply no second path to sample then.
     */
    private fun dumpCapturePathFrame(stamp: String) {
        Thread({
            try {
                val bridge = NativeBridge()
                bridge.nativeRequestFrameCapture()
                val size = IntArray(2)
                var pixels: ByteArray? = null
                for (attempt in 0 until 40) {
                    pixels = bridge.nativeFetchCapturedFrame(size)
                    if (pixels != null) break
                    Thread.sleep(25)
                }
                val data = pixels
                val w = size[0]
                val h = size[1]
                if (data == null || w <= 0 || h <= 0 || data.size < w * h * 4) {
                    say("no capture-path frame - is AI enhancement running in "
                        + "MediaProjection mode with the window detected?")
                    return@Thread
                }
                val dir = File(linkDir(), "compare")
                dir.mkdirs()
                val out = File(dir, "capture-$stamp-${w}x$h.png")
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                say("dumped ${out.absolutePath}")
            } catch (t: Throwable) {
                say("capture-path dump failed - ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "ShimLink-compare").start()
    }

    // ------------------------------------------------------------------ dump

    /**
     * Write one frame out as a PNG, for the pixel-exact comparison gate 3 turns
     * on. This is also the first place §4.6's colour expansion runs, so getting
     * it wrong here would be visible: 5 and 6 bit channels must be widened by
     * REPLICATING the high bits, not by shifting left. A plain shift maps 31 to
     * 248 rather than 255, so white stops being white and the whole picture
     * sits slightly dark - a systematic shift of the input distribution that is
     * nearly invisible on a still image and would quietly move every frame the
     * model ever sees away from what it was trained on.
     */
    private fun dumpFrame(data: ByteArray, w: Int, h: Int, pitch: Int, format: Int) {
        if (w <= 0 || h <= 0) return
        try {
            val pixels = IntArray(w * h)
            when (format) {
                2 -> for (y in 0 until h) for (x in 0 until w) {
                    val i = y * pitch + x * 2
                    val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                    val r = (v ushr 11) and 0x1F
                    val g = (v ushr 5) and 0x3F
                    val b = v and 0x1F
                    pixels[y * w + x] = (0xFF shl 24) or
                        (((r shl 3) or (r ushr 2)) shl 16) or
                        (((g shl 2) or (g ushr 4)) shl 8) or
                        ((b shl 3) or (b ushr 2))
                }
                0 -> for (y in 0 until h) for (x in 0 until w) {
                    val i = y * pitch + x * 2
                    val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                    val r = (v ushr 10) and 0x1F
                    val g = (v ushr 5) and 0x1F
                    val b = v and 0x1F
                    pixels[y * w + x] = (0xFF shl 24) or
                        (((r shl 3) or (r ushr 2)) shl 16) or
                        (((g shl 3) or (g ushr 2)) shl 8) or
                        ((b shl 3) or (b ushr 2))
                }
                1 -> for (y in 0 until h) for (x in 0 until w) {
                    val i = y * pitch + x * 4
                    pixels[y * w + x] = (0xFF shl 24) or
                        ((data[i + 2].toInt() and 0xFF) shl 16) or
                        ((data[i + 1].toInt() and 0xFF) shl 8) or
                        (data[i].toInt() and 0xFF)
                }
                else -> {
                    say("cannot dump unknown pixel format $format")
                    return
                }
            }

            val dir = File(linkDir(), "compare")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val out = File(dir, "shim-$stamp-${w}x$h.png")
            val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            lastDumpPath = out.absolutePath
            say("dumped ${out.absolutePath}")
            dumpCapturePathFrame(stamp)
        } catch (t: Throwable) {
            say("dump failed - ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ misc

    private fun say(line: String) {
        Log.i(TAG, line)
        transcript.add(line)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shim frame link",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.shim_setup_title))
            .setContentText(getString(R.string.shim_link_notification))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
