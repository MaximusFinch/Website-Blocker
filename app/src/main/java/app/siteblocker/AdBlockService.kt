package app.siteblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ad blocker: a local "VPN" that only receives DNS lookups.
 *
 * Android sends the apps' DNS questions ("where is ads.example.com?") to a made-up
 * DNS server address inside this VPN. Questions for ad and tracker domains are
 * answered with 0.0.0.0 (nowhere), all others are passed on unchanged to the
 * normal DNS server of the network. All other traffic does not go through the VPN
 * at all, and nothing is sent anywhere else.
 *
 * Only one VPN can run at a time. When another VPN app (e.g. NordVPN) starts,
 * Android stops this one; a notification then offers to turn it back on.
 */
class AdBlockService : VpnService() {

    companion object {
        const val ACTION_START = "app.siteblocker.adblock.START"
        const val ACTION_STOP = "app.siteblocker.adblock.STOP"
        /** Opens WebBlock and turns the ad blocker back on (from the notification). */
        const val ACTION_ENABLE = "app.siteblocker.ENABLE_ADBLOCK"

        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private const val CHANNEL = "adblock"
        private const val NOTE_OFF = 7

        @Volatile
        var running = false
            private set

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("adblock_on", false)
        fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("adblock_on", on).apply()

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, AdBlockService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AdBlockService::class.java).setAction(ACTION_STOP))
        }

        private fun today() = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

        /** Ads and trackers blocked today. */
        fun blockedToday(ctx: Context): Int {
            val p = prefs(ctx)
            return if (p.getInt("adblock_day", 0) == today()) p.getInt("adblock_count", 0) else 0
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    @Volatile private var stopping = false

    // Counter of blocked lookups, saved every few blocks
    private var count = 0
    private var countDay = 0
    private var unsaved = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !isEnabled(this)) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tun != null) return
        // Permission missing (e.g. another VPN app was allowed in the meantime)
        if (prepare(this) != null) {
            setEnabled(this, false)
            notifyOff()
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTE_OFF)

        val builder = Builder()
            .setSession("WebBlock ad blocker")
            .addAddress(VPN_ADDRESS, 24)
            .addDnsServer(DNS_ADDRESS)
            // Only the made-up DNS address is routed into the VPN; everything else goes past it
            .addRoute(DNS_ADDRESS, 32)
        // WebBlock itself (list download, forwarded lookups) doesn't go through its own VPN
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setBlocking(true)
            builder.setMetered(false)
        }
        val fd = try { builder.establish() } catch (_: Exception) { null }
        if (fd == null) { stopSelf(); return }

        tun = fd
        stopping = false
        running = true
        pool = Executors.newFixedThreadPool(8)
        countDay = today()
        count = blockedToday(this)

        Thread({
            // List: load the stored one, download it when missing or older than a week
            if (!AdBlockList.isLoaded) AdBlockList.load(this)
            if (AdBlockList.needsUpdate(this)) try { AdBlockList.download(this) } catch (_: Exception) {}
        }, "adblock-list").start()
        Thread({ loop(fd) }, "adblock-dns").start()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!stopping) {
            val n = try { input.read(buf) } catch (_: IOException) { break }
            if (n < 0) break
            if (n == 0) {
                // Android 9 and older: the interface doesn't wait for packets
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }
            try { handle(buf.copyOf(n), output) } catch (_: Exception) {}
        }
    }

    private fun handle(pkt: ByteArray, output: FileOutputStream) {
        if (pkt.size < 28 || ((pkt[0].toInt() shr 4) and 0xF) != 4) return   // IPv4 only
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17 || pkt.size < ihl + 8) return             // UDP only
        if (u16(pkt, ihl + 2) != 53) return                                // DNS only
        val dns = pkt.copyOfRange(ihl + 8, pkt.size)
        val q = Dns.question(dns) ?: return

        if (AdBlockList.isBlocked(q.name)) {
            reply(output, pkt, ihl, Dns.blocked(dns, q))
            countBlocked()
            return
        }
        pool?.execute { forward(output, pkt, ihl, dns) }
    }

    /** Passes the question on to the network's normal DNS server and returns its answer. */
    private fun forward(output: FileOutputStream, pkt: ByteArray, ihl: Int, dns: ByteArray) {
        try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 5000
                s.send(DatagramPacket(dns, dns.size, upstream(), 53))
                val buf = ByteArray(4096)
                val answer = DatagramPacket(buf, buf.size)
                s.receive(answer)
                reply(output, pkt, ihl, buf.copyOf(answer.length))
            }
        } catch (_: Exception) {
            // No answer: the app simply asks again
        }
    }

    @Volatile private var upstreamAddr: InetAddress? = null
    @Volatile private var upstreamAt = 0L

    /** DNS server of the current Wi-Fi / mobile network (checked every 30 s), or Cloudflare. */
    private fun upstream(): InetAddress {
        val now = System.currentTimeMillis()
        upstreamAddr?.let { if (now - upstreamAt < 30_000) return it }
        val cm = getSystemService(ConnectivityManager::class.java)
        val found = try {
            cm?.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress != DNS_ADDRESS }
        } catch (_: Exception) { null }
        val addr = found ?: InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        upstreamAddr = addr
        upstreamAt = now
        return addr
    }

    /** Wraps a DNS answer in an IPv4/UDP packet back to the asking app. */
    private fun reply(output: FileOutputStream, req: ByteArray, ihl: Int, dns: ByteArray) {
        if (dns.size > 65_000) return
        val total = 28 + dns.size
        val out = ByteArray(total)
        out[0] = 0x45
        put16(out, 2, total)
        put16(out, 6, 0x4000)       // don't fragment
        out[8] = 64                 // TTL
        out[9] = 17                 // UDP
        System.arraycopy(req, 16, out, 12, 4)   // from: the DNS address
        System.arraycopy(req, 12, out, 16, 4)   // to: the app
        put16(out, 10, checksum(out, 20))
        put16(out, 20, u16(req, ihl + 2))
        put16(out, 22, u16(req, ihl))
        put16(out, 24, 8 + dns.size)            // UDP checksum 0 = none (allowed for IPv4)
        System.arraycopy(dns, 0, out, 28, dns.size)
        synchronized(output) { output.write(out) }
    }

    private fun countBlocked() {
        val day = today()
        if (day != countDay) { countDay = day; count = 0 }
        count++
        if (++unsaved >= 20) saveCount()
    }

    private fun saveCount() {
        unsaved = 0
        prefs(this).edit().putInt("adblock_day", countDay).putInt("adblock_count", count).apply()
    }

    private fun shutdown() {
        stopping = true
        running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        pool?.shutdownNow()
        pool = null
        if (countDay != 0) saveCount()
    }

    /** Another VPN app was started (or the VPN was turned off in the settings). */
    override fun onRevoke() {
        shutdown()
        setEnabled(this, false)
        notifyOff()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** "Ad blocker turned off – tap to turn it back on". */
    private fun notifyOff() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ad blocker", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_ENABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val text = "Another VPN was started, so Android turned the ad blocker off. Tap to turn it back on."
        builder.setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Ad blocker turned off")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        try { nm.notify(NOTE_OFF, builder.build()) } catch (_: SecurityException) {}
    }

    // ---------- Packet helpers ----------

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) { sum += u16(b, i); i += 2 }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}

/** Minimal DNS message handling: read the question, build a "blocked" answer. */
object Dns {
    class Question(val name: String, val type: Int, val end: Int)

    fun question(d: ByteArray): Question? {
        if (d.size < 12) return null
        if ((d[2].toInt() and 0x80) != 0) return null                  // not a question
        if (((d[4].toInt() and 0xFF) shl 8 or (d[5].toInt() and 0xFF)) < 1) return null
        val name = StringBuilder()
        var i = 12
        while (true) {
            if (i >= d.size) return null
            val len = d[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if ((len and 0xC0) != 0 || i + 1 + len > d.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (k in 1..len) name.append((d[i + k].toInt() and 0xFF).toChar())
            if (name.length > 253) return null
            i += 1 + len
        }
        if (i + 4 > d.size) return null
        val type = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
        return Question(name.toString().lowercase(), type, i + 4)
    }

    /** Answer: 0.0.0.0 for IPv4, :: for IPv6, "no records" for anything else. */
    fun blocked(d: ByteArray, q: Question): ByteArray {
        val size = when (q.type) { 1 -> 4; 28 -> 16; else -> 0 }
        val head = d.copyOf(q.end)
        head[2] = (0x80 or (d[2].toInt() and 0x79)).toByte()    // answer; keep opcode and "recursion desired"
        head[3] = 0x80.toByte()                                  // "recursion available", no error
        head[4] = 0; head[5] = 1                                 // 1 question
        head[6] = 0; head[7] = if (size > 0) 1 else 0            // 0 or 1 answer
        head[8] = 0; head[9] = 0; head[10] = 0; head[11] = 0
        if (size == 0) return head
        val ans = ByteArray(12 + size)
        ans[0] = 0xC0.toByte(); ans[1] = 0x0C                    // name: the one from the question
        ans[2] = 0; ans[3] = q.type.toByte()                     // type A / AAAA
        ans[4] = 0; ans[5] = 1                                   // class IN
        ans[8] = 0x01; ans[9] = 0x2C                             // TTL 300 s
        ans[10] = 0; ans[11] = size.toByte()                     // address is all zeros
        return head + ans
    }
}
