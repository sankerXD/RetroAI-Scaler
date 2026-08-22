package com.retroai.scaler.shim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.retroai.scaler.MainActivity
import com.retroai.scaler.R
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate 1 of the libretro shim route (NewSolution.md §7): what channel can carry
 * frames from inside RetroArch's process to this app?
 *
 * THIS WHOLE FILE IS A PROBE AND IS MEANT TO BE DELETED once the gate is
 * answered. It ships no production behaviour; it exists to turn one assumption
 * into one measurement.
 *
 * The assumption under test
 * -------------------------
 * The shim runs inside RetroArch, which the SELinux audit in §3 puts in
 * `untrusted_app_27` with per-app MLS categories; this app is `untrusted_app`
 * with different ones. That pair kills abstract and filesystem unix sockets,
 * which is why the design book reached for Binder. But Binder needs a JavaVM
 * from native code, and `JNI_GetCreatedJavaVMs` only entered the NDK at API 31
 * - on the Android 11 target it lives in libnativehelper.so inside the ART
 * APEX, which an app's linker namespace will not open. That makes the entire
 * Binder plan rest on something no gate has ever tested.
 *
 * Loopback TCP has no such problem: SELinux checks `name_connect` against the
 * PORT's type, not the peer's domain, and never consults MLS categories. If
 * that reading is right, a plain C `connect()` to 127.0.0.1 works with zero
 * JNI, zero reflection and zero AIDL. RetroArch's own AI Service feature
 * (`ai_service_url`, a localhost URL by default) is the same call from the same
 * process, which is good evidence but not a measurement on this device.
 *
 * How the measurement is taken
 * ----------------------------
 * The client has to be the real RetroArch process - a toy APK would only
 * reproduce a domain we guessed at. So this service listens on RetroArch's own
 * default netplay port and the tester points RetroArch's "Connect to Netplay
 * Host" at 127.0.0.1. RetroArch then performs exactly the syscall the shim
 * would: socket() + connect() to this app's listener, from the production
 * process, under the production label. The netplay handshake failing afterwards
 * is irrelevant and expected - accept() returning is the whole answer.
 *
 * Two cheap things ride along, because they need the same tester and the same
 * five minutes (they are the residual items on gate 0):
 *  - whether this app can create/rename/delete inside the directories holding
 *    Pegasus' metadata.pegasus.txt, on internal storage AND on the SD card;
 *  - a marker file under /storage/emulated/0/RetroAIScaler/shim/, so the tester can
 *    confirm from RetroArch's own file browser that RetroArch can read the
 *    directory the production build would use to publish its port and token.
 *
 * The directory probe never touches metadata.pegasus.txt itself. It writes a
 * temp file beside it, renames it, and deletes it - which is precisely what
 * §5.4's atomic-replace discipline needs to be able to do, and what a read-only
 * mount would refuse. Writing to the real file to see whether writing works
 * would risk the one failure the user actually feels: Pegasus not launching
 * games any more.
 */
class ShimProbeService : Service() {

    companion object {
        private const val TAG = "RetroAI_ShimProbe"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "retro_ai_shim_probe"

        /**
         * RetroArch's default `netplay_ip_port`. Listening here rather than on
         * a port of our own choosing means the tester only has to type an
         * address into RetroArch, not an address and a port.
         */
        const val PORT = 55435

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Connections accepted since the service started. The verdict. */
        val acceptedCount = AtomicInteger(0)

        /**
         * Probe transcript, newest last. Deliberately English and technical:
         * this is a diagnostic log that ends up in a bug report, not product
         * copy, and §16.3 says not to make localised text carry meaning.
         */
        val transcript = CopyOnWriteArrayList<String>()
    }

    @Volatile private var stopping = false
    private var server: ServerSocket? = null
    private var listenThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        transcript.clear()
        acceptedCount.set(0)
        stopping = false
        isRunning = true

        say("probe started, build=${Build.MODEL} api=${Build.VERSION.SDK_INT}")

        listenThread = Thread({ listenLoop() }, "ShimProbe-listen").apply {
            isDaemon = false
            start()
        }
        Thread({ storageProbe() }, "ShimProbe-storage").apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopping = true
        isRunning = false
        // Close the socket rather than interrupt the thread: accept() does not
        // respond to interrupt, and the SO_TIMEOUT below only bounds how long
        // shutdown waits, it does not cause it.
        try {
            server?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing server socket", t)
        }
        listenThread?.join(2000)
        say("probe stopped, accepted=${acceptedCount.get()}")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- socket

    private fun listenLoop() {
        try {
            // Loopback only. Binding the wildcard address would put game frames
            // on the local network in the production version of this, and there
            // is no reason to ever practise that here.
            val socket = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 1000
            server = socket
            say("listening on 127.0.0.1:$PORT")
        } catch (t: Throwable) {
            say("FAILED to bind 127.0.0.1:$PORT - ${t.javaClass.simpleName}: ${t.message}")
            say("if this says EACCES the app is missing android.permission.INTERNET")
            return
        }

        while (!stopping) {
            val client = try {
                server?.accept() ?: break
            } catch (e: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (!stopping) say("accept failed - ${t.javaClass.simpleName}: ${t.message}")
                break
            }

            val n = acceptedCount.incrementAndGet()
            say("ACCEPTED #$n from ${client.inetAddress?.hostAddress}:${client.port}")

            try {
                client.soTimeout = 1500
                val buf = ByteArray(64)
                val read = try {
                    client.getInputStream().read(buf)
                } catch (e: SocketTimeoutException) {
                    -2
                }
                when {
                    read > 0 -> say("  read $read bytes: ${hex(buf, read)}")
                    read == -2 -> say("  peer sent nothing within 1500ms (connect still proves the channel)")
                    else -> say("  peer closed without sending")
                }
                // Write back too: the shim only ever needs app<-shim for frames,
                // but a handshake in production goes the other way, so measure
                // both directions while a real peer is on the line.
                client.getOutputStream().write("RETROAI_SHIM_PROBE_OK\n".toByteArray())
                client.getOutputStream().flush()
                say("  wrote 22 bytes back OK")
            } catch (t: Throwable) {
                say("  exchange failed - ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try {
                    client.close()
                } catch (ignored: Throwable) {
                }
            }
        }
        say("listener exited")
    }

    private fun hex(buf: ByteArray, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(len, 24)) sb.append(String.format("%02x ", buf[i]))
        if (len > 24) sb.append("…")
        return sb.toString().trim()
    }

    // --------------------------------------------------------------- storage

    private fun storageProbe() {
        val manager = Environment.isExternalStorageManager()
        say("MANAGE_EXTERNAL_STORAGE granted=$manager")
        if (!manager) {
            say("grant All files access first, storage results below are meaningless")
        }

        // Marker file, so RetroArch's own file browser can be pointed at it.
        try {
            // RetroAIScaler, no hyphen: that is what backups/ and dataset/
            // already use. A second near-identical directory in the root of a
            // user's storage is litter, and the two would drift.
            val dir = File(Environment.getExternalStorageDirectory(), "RetroAIScaler/shim")
            dir.mkdirs()
            val marker = File(dir, "shim-probe.txt")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            marker.writeText("port=$PORT\nwritten=$stamp\n")
            say("marker written: ${marker.absolutePath}")
        } catch (t: Throwable) {
            say("marker write FAILED - ${t.javaClass.simpleName}: ${t.message}")
        }

        val volumes = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach { v ->
            // /storage holds emulated/, self/ and the mounted volumes. Volume
            // ids are what we want and they are neither of the first two.
            if (v.isDirectory && v.name != "emulated" && v.name != "self") volumes.add(v)
        }
        say("volumes: ${volumes.joinToString { it.absolutePath }}")

        var found = 0
        for (vol in volumes) {
            val roms = File(vol, "Roms")
            val systems = roms.listFiles() ?: continue
            for (system in systems) {
                val meta = File(system, "metadata.pegasus.txt")
                if (!meta.isFile) continue
                found++
                val hasLibretro = try {
                    meta.readText().contains("-e LIBRETRO")
                } catch (t: Throwable) {
                    say("  ${meta.absolutePath} unreadable - ${t.message}")
                    false
                }
                val writable = probeDirWritable(system)
                say("pegasus: ${meta.absolutePath}")
                say("  has '-e LIBRETRO'=$hasLibretro  dir create+rename+delete=$writable")
            }
        }
        if (found == 0) say("no metadata.pegasus.txt found under any <volume>/Roms/*/")
    }

    /**
     * Create, write, read back, rename and delete a temp file in [dir]. That is
     * the exact sequence §5.4's atomic replacement needs; a volume that allows
     * create but refuses rename would otherwise only be discovered the first
     * time a user's launch file was half-written.
     */
    private fun probeDirWritable(dir: File): String {
        val a = File(dir, ".retroai-writetest")
        val b = File(dir, ".retroai-writetest2")
        try {
            a.writeText("probe")
            if (a.readText() != "probe") return "NO (read-back mismatch)"
            if (!a.renameTo(b)) {
                a.delete()
                return "NO (rename refused)"
            }
            if (!b.delete()) return "PARTIAL (rename ok, delete refused - ${b.absolutePath} left behind)"
            return "YES"
        } catch (t: Throwable) {
            return "NO (${t.javaClass.simpleName}: ${t.message})"
        } finally {
            // Belt and braces: never leave litter in a user's ROM folder.
            if (a.exists()) a.delete()
            if (b.exists()) b.delete()
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
            "Shim IPC probe",
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
            .setContentTitle(getString(R.string.shim_probe_title))
            .setContentText("127.0.0.1:$PORT")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
