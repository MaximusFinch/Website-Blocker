package app.siteblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Calendar

/**
 * Ad blocker: a local "VPN" that only receives DNS lookups.
 *
 * Android sends the apps' DNS questions ("where is ads.example.com?") to a made-up
 * DNS server address inside this VPN. Questions for ad and tracker domains are
 * answered with 0.0.0.0 (nowhere) right away, all others are passed on to the
 * normal DNS server of the network. All other traffic does not go through the VPN
 * at all, and nothing is sent anywhere else.
 *
 * Built to add as little delay as possible:
 *  - one shared socket to the DNS server instead of a new socket and thread per lookup
 *  - answers are cached for as long as the DNS server allows (repeated lookups: ~0 ms)
 *  - the network's DNS server is updated the moment Wi-Fi / mobile data changes
 *  - when the DNS server doesn't answer within 1 s, the lookup is sent to a second one
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

        private const val RETRY_AFTER = 1_000L      // ask the second DNS server after 1 s
        private const val GIVE_UP_AFTER = 5_000L
        private const val CACHE_SIZE = 2_000
        private const val MAX_CACHE_TTL = 3_600     // seconds

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
    private var output: FileOutputStream? = null
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
            builder.setMetered(false)   // metered only when the real network is
        }
        val fd = try { builder.establish() } catch (_: Exception) { null }
        if (fd == null) { stopSelf(); return }

        tun = fd
        output = FileOutputStream(fd.fileDescriptor)
        stopping = false
        running = true
        countDay = today()
        count = blockedToday(this)
        watchNetwork()

        Thread({
            // List: load the stored one, download it when missing or older than a week
            if (!AdBlockList.isLoaded) AdBlockList.load(this)
            if (AdBlockList.needsUpdate(this)) try { AdBlockList.download(this) } catch (_: Exception) {}
        }, "adblock-list").start()
        Thread({ readLoop(fd) }, "adblock-in").start()
        Thread({ upstreamLoop() }, "adblock-out").start()
    }

    // ---------- Questions from the apps ----------

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!stopping) {
            val n = try { input.read(buf) } catch (_: IOException) { break }
            if (n < 0) break
            if (n == 0) {
                // Android 9 and older: the interface doesn't wait for packets
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            try { handle(buf.copyOf(n)) } catch (_: Exception) {}
        }
    }

    private fun handle(pkt: ByteArray) {
        if (pkt.size < 28 || ((pkt[0].toInt() shr 4) and 0xF) != 4) return   // IPv4 only
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17 || pkt.size < ihl + 8) return               // UDP only
        if (u16(pkt, ihl + 2) != 53) return                                  // DNS only
        val dns = pkt.copyOfRange(ihl + 8, pkt.size)
        val q = Dns.question(dns) ?: return

        // 1. Ad or tracker: answer "nowhere" right away
        if (AdBlockList.isBlocked(q.name)) {
            reply(pkt, ihl, Dns.blocked(dns, q))
            countBlocked()
            return
        }
        // 2. Asked recently: answer from the cache
        val key = q.key
        val cached = cacheGet(key, dns)
        if (cached != null) {
            reply(pkt, ihl, cached)
            return
        }
        // 3. Pass on to the network's DNS server
        send(Pending(pkt, ihl, u16(dns, 0), dns, key, SystemClock.elapsedRealtime()))
    }

    // ---------- Passing questions on ----------

    private class Pending(
        val pkt: ByteArray, val ihl: Int, val clientId: Int,
        val dns: ByteArray, val key: String, val sentAt: Long,
    ) { var retried = false }

    private val pending = HashMap<Int, Pending>()
    private var nextId = (System.nanoTime() and 0xFFFF).toInt()
    @Volatile private var socket: DatagramSocket? = null
    private val socketLock = Any()

    private fun openSocket(): DatagramSocket = synchronized(socketLock) {
        socket ?: DatagramSocket().also {
            protect(it)
            it.soTimeout = 250
            socket = it
        }
    }

    private fun resetSocket() = synchronized(socketLock) {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Sends the question with an own ID (apps' IDs can collide) to the DNS server. */
    private fun send(p: Pending) {
        val id: Int
        synchronized(pending) {
            if (pending.size > 4000) return   // something is badly wrong upstream; the app asks again
            var i = nextId
            do { i = (i + 1) and 0xFFFF } while (pending.containsKey(i))
            nextId = i
            id = i
            pending[id] = p
        }
        transmit(p, id, primaryDns())
    }

    private fun transmit(p: Pending, id: Int, server: InetAddress) {
        val out = p.dns.copyOf()
        out[0] = (id shr 8).toByte()
        out[1] = id.toByte()
        try { openSocket().send(DatagramPacket(out, out.size, server, 53)) } catch (_: Exception) {}
    }

    /** Receives the DNS server's answers and hands them back to the right app. */
    private fun upstreamLoop() {
        val buf = ByteArray(8192)
        var lastCheck = 0L
        while (!stopping) {
            val s = try { openSocket() } catch (_: Exception) { null }
            if (s == null) { try { Thread.sleep(200) } catch (_: InterruptedException) { break }; continue }
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                if (packet.length >= 12) {
                    val answer = buf.copyOf(packet.length)
                    val p = synchronized(pending) { pending.remove(u16(answer, 0)) }
                    if (p != null) {
                        answer[0] = (p.clientId shr 8).toByte()
                        answer[1] = p.clientId.toByte()
                        reply(p.pkt, p.ihl, answer)
                        cachePut(p.key, answer)
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                // Socket closed (network change): a new one is opened in the next round
                if (stopping) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck >= 250) { lastCheck = now; checkTimeouts(now) }
        }
    }

    /** No answer after 1 s: ask the second DNS server. After 5 s: give up (the app asks again). */
    private fun checkTimeouts(now: Long) {
        val retry = ArrayList<Pair<Int, Pending>>()
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (id, p) = it.next()
                val age = now - p.sentAt
                if (age > GIVE_UP_AFTER) it.remove()
                else if (age > RETRY_AFTER && !p.retried) { p.retried = true; retry += id to p }
            }
        }
        if (retry.isNotEmpty()) {
            val server = secondaryDns()
            for ((id, p) in retry) transmit(p, id, server)
        }
    }

    // ---------- DNS servers of the current network ----------

    @Volatile private var dnsServers: List<InetAddress> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cloudflare = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    private val quad9 = InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9))

    private fun primaryDns() = dnsServers.firstOrNull() ?: cloudflare

    private fun secondaryDns(): InetAddress {
        val list = dnsServers
        return list.getOrNull(1) ?: if (list.firstOrNull() == cloudflare) quad9 else cloudflare
    }

    private fun useLink(lp: LinkProperties?) {
        val servers = lp?.dnsServers.orEmpty()
            .filter { !it.isLoopbackAddress && it.hostAddress != DNS_ADDRESS }
        if (servers == dnsServers) return
        dnsServers = servers
        // New network: other DNS server, old answers may not fit (e.g. home network names)
        synchronized(cache) { cache.clear() }
        resetSocket()
    }

    /** Follows Wi-Fi / mobile data changes right away (WebBlock itself is outside the VPN). */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try { useLink(cm.activeNetwork?.let { cm.getLinkProperties(it) }) } catch (_: Exception) {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = useLink(lp)
            override fun onLost(network: Network) = useLink(null)
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (_: Exception) {}
    }

    // ---------- Answer cache ----------

    private class Cached(val data: ByteArray, val ttlPos: IntArray, val ttls: IntArray, val storedAt: Long, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, Cached>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_SIZE
    }

    private fun cachePut(key: String, answer: ByteArray) {
        val info = Dns.ttlInfo(answer) ?: return
        val minTtl = info.second.minOrNull() ?: return
        if (minTtl <= 0) return
        val now = SystemClock.elapsedRealtime()
        val life = minOf(minTtl, MAX_CACHE_TTL) * 1000L
        synchronized(cache) { cache[key] = Cached(answer, info.first, info.second, now, now + life) }
    }

    /** Cached answer with the app's ID and the remaining times, or null. */
    private fun cacheGet(key: String, question: ByteArray): ByteArray? {
        val now = SystemClock.elapsedRealtime()
        val c = synchronized(cache) {
            val c = cache[key] ?: return null
            if (now >= c.expiresAt) { cache.remove(key); return null }
            c
        }
        // Answers over 512 bytes only for apps that said they can take them (EDNS)
        if (c.data.size > 512 && u16(question, 10) == 0) return null
        val out = c.data.copyOf()
        out[0] = question[0]
        out[1] = question[1]
        val age = ((now - c.storedAt) / 1000).toInt()
        for (i in c.ttlPos.indices) put32(out, c.ttlPos[i], maxOf(1, c.ttls[i] - age))
        return out
    }

    // ---------- Answers to the apps ----------

    /** Wraps a DNS answer in an IPv4/UDP packet back to the asking app. */
    private fun reply(req: ByteArray, ihl: Int, dns: ByteArray) {
        if (dns.size > 65_000) return
        val out = ByteArray(28 + dns.size)
        out[0] = 0x45
        put16(out, 2, out.size)
        put16(out, 6, 0x4000)                   // don't fragment
        out[8] = 64                             // TTL
        out[9] = 17                             // UDP
        System.arraycopy(req, 16, out, 12, 4)   // from: the DNS address
        System.arraycopy(req, 12, out, 16, 4)   // to: the app
        put16(out, 10, checksum(out, 20))
        put16(out, 20, u16(req, ihl + 2))
        put16(out, 22, u16(req, ihl))
        put16(out, 24, 8 + dns.size)            // UDP checksum 0 = none (allowed for IPv4)
        System.arraycopy(dns, 0, out, 28, dns.size)
        val o = output ?: return
        try { synchronized(o) { o.write(out) } } catch (_: IOException) {}
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
        netCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        netCallback = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        output = null
        resetSocket()
        synchronized(pending) { pending.clear() }
        synchronized(cache) { cache.clear() }
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

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 24).toByte()
        b[i + 1] = (v shr 16).toByte()
        b[i + 2] = (v shr 8).toByte()
        b[i + 3] = v.toByte()
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) { sum += u16(b, i); i += 2 }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}

/** Minimal DNS message handling: read the question, build a "blocked" answer, find TTLs. */
object Dns {
    class Question(val name: String, val type: Int, val end: Int, val key: String)

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    fun question(d: ByteArray): Question? {
        if (d.size < 12) return null
        if ((d[2].toInt() and 0x80) != 0) return null                // not a question
        if (u16(d, 4) < 1) return null
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
        val host = name.toString().lowercase()
        val type = u16(d, i)
        // Cache key: name, type, class and the "DNSSEC OK"-free question flags
        return Question(host, type, i + 4, "$host/$type/${u16(d, i + 2)}/${d[2].toInt() and 0x01}")
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

    /**
     * Positions and values of all TTLs in a cacheable answer (no error or "doesn't
     * exist", not truncated), or null when it shouldn't be cached.
     */
    fun ttlInfo(d: ByteArray): Pair<IntArray, IntArray>? {
        if (d.size < 12) return null
        if ((d[2].toInt() and 0x02) != 0) return null                // truncated
        val rcode = d[3].toInt() and 0x0F
        if (rcode != 0 && rcode != 3) return null
        var i = 12
        repeat(u16(d, 4)) {
            i = skipName(d, i)
            if (i < 0 || i + 4 > d.size) return null
            i += 4
        }
        val records = u16(d, 6) + u16(d, 8) + u16(d, 10)
        val pos = ArrayList<Int>()
        val ttl = ArrayList<Int>()
        repeat(records) {
            i = skipName(d, i)
            if (i < 0 || i + 10 > d.size) return null
            val type = u16(d, i)
            if (type != 41) {                                            // OPT has no real TTL
                pos += i + 4
                ttl += (u16(d, i + 4) shl 16) or u16(d, i + 6)
            }
            i += 10 + u16(d, i + 8)
            if (i > d.size) return null
        }
        if (pos.isEmpty()) return null
        return pos.toIntArray() to ttl.map { if (it < 0) 0 else it }.toIntArray()
    }

    private fun skipName(d: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            if (i >= d.size) return -1
            val len = d[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if ((len and 0xC0) == 0xC0) return i + 2
            if ((len and 0xC0) != 0) return -1
            i += 1 + len
        }
    }
}
