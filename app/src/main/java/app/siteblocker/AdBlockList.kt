package app.siteblocker

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The list of ad and tracker domains for the ad blocker.
 *
 * It is downloaded from the StevenBlack hosts project (a plain text file with lines
 * like "0.0.0.0 ads.example.com"). Only the domain names are kept – nothing from
 * the download is ever run. The list is refreshed once a week.
 *
 * To be fast and small, each domain is stored as a 64-bit hash in a sorted array
 * (about 1 MB instead of ~15 MB of text in memory); a lookup is a binary search
 * without creating any new strings.
 */
object AdBlockList {
    const val SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    private const val FILE = "adblock_hashes.bin"
    private const val OLD_FILE = "adblock_hosts.txt"   // text format of 1.24
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    private val domainRegex = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
    private val notDomains = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "0.0.0.0",
    )

    /**
     * Important sites that are never blocked, whatever the list says: Android's
     * connection check and push messages, big everyday sites, and the list's own
     * download address (so a bad list can always be replaced).
     * A downloaded list that blocks one of them (or a parent domain like
     * gstatic.com) is treated as broken or tampered with and thrown away.
     */
    private val neverBlock = setOf(
        "google.com", "www.google.com", "accounts.google.com", "play.google.com",
        "mtalk.google.com", "clients3.google.com",
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com",
        "www.gstatic.com", "fonts.gstatic.com",
        "android.googleapis.com", "play.googleapis.com", "fcm.googleapis.com",
        "www.googleapis.com", "firebaseinstallations.googleapis.com",
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com", "i.ytimg.com",
        "whatsapp.com", "www.whatsapp.com", "web.whatsapp.com", "g.whatsapp.net", "mmg.whatsapp.net",
        "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
        "wikipedia.org", "en.wikipedia.org", "de.wikipedia.org",
        "microsoft.com", "login.microsoftonline.com", "outlook.office.com", "teams.microsoft.com",
        "apple.com", "icloud.com", "amazon.com", "amazon.de", "paypal.com",
        "instagram.com", "www.instagram.com", "facebook.com", "www.facebook.com",
        "cloudflare.com", "one.one.one.one", "dns.google",
    )

    /** The list would block an important site: it is broken or was tampered with. */
    class UnsafeListException(val domain: String) :
        IOException("The downloaded list would block $domain")

    /** First important site the list would block (directly or through a parent domain), or null. */
    private fun unsafeEntry(domains: Set<String>): String? {
        for (n in neverBlock) {
            var from = 0
            while (true) {
                val part = n.substring(from)
                if (part in domains) return part
                val dot = n.indexOf('.', from)
                if (dot < 0 || n.indexOf('.', dot + 1) < 0) break
                from = dot + 1
            }
        }
        return null
    }

    @Volatile
    private var hashes = LongArray(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Number of domains in the stored list (also when the list isn't loaded). */
    fun count(ctx: Context) = prefs(ctx).getInt("adblock_domains", 0)

    /** When the list was last downloaded (0 = never). */
    fun lastUpdate(ctx: Context) = prefs(ctx).getLong("adblock_updated", 0)

    fun needsUpdate(ctx: Context) =
        !file(ctx).exists() || System.currentTimeMillis() - lastUpdate(ctx) > WEEK

    val isLoaded get() = hashes.isNotEmpty()

    /** 64-bit FNV-1a hash of host[from until end]. */
    private fun hash(host: String, from: Int): Long {
        var h = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325
        for (i in from until host.length) {
            h = h xor host[i].code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /** Loads the stored list into memory. */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) {
            // List from version 1.24 (text): convert it once
            val old = File(ctx.filesDir, OLD_FILE)
            if (old.exists()) {
                try {
                    val set = HashSet<String>(150_000)
                    old.forEachLine { if (it.isNotBlank()) set.add(it.trim()) }
                    save(ctx, set)
                } catch (_: Exception) {}
                old.delete()
            }
            return
        }
        try {
            DataInputStream(BufferedInputStream(f.inputStream(), 65536)).use { input ->
                val n = input.readInt()
                if (n < 0 || n > 5_000_000) throw IOException("Broken list")
                hashes = LongArray(n) { input.readLong() }
            }
        } catch (_: Exception) {
            f.delete()   // downloaded again next time
        }
    }

    /** Is this host (or one of its parent domains) an ad or tracker domain? */
    fun isBlocked(host: String): Boolean {
        val h = hashes
        if (h.isEmpty()) return false
        val end = if (host.endsWith('.')) host.length - 1 else host.length
        val name = if (end == host.length) host else host.substring(0, end)
        if (name in neverBlock) return false
        var from = 0
        while (true) {
            if (java.util.Arrays.binarySearch(h, hash(name, from)) >= 0) return true
            val dot = name.indexOf('.', from)
            if (dot < 0) return false
            from = dot + 1
            // Never check a bare top-level domain like "com"
            if (name.indexOf('.', from) < 0) return false
        }
    }

    private fun save(ctx: Context, domains: Collection<String>) {
        val sorted = LongArray(domains.size)
        var i = 0
        for (d in domains) sorted[i++] = hash(d, 0)
        sorted.sort()
        // Remove duplicates
        var n = 0
        for (k in sorted.indices) if (n == 0 || sorted[k] != sorted[n - 1]) sorted[n++] = sorted[k]
        val list = sorted.copyOf(n)

        val target = file(ctx)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 65536)).use { out ->
            out.writeInt(list.size)
            for (v in list) out.writeLong(v)
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't save the list")
        }
        hashes = list
        prefs(ctx).edit().putInt("adblock_domains", domains.size).apply()
    }

    /** Downloads the list and replaces the stored one. Returns the number of domains. */
    fun download(ctx: Context): Int {
        val conn = URL(SOURCE).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "WebBlock")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val found = HashSet<String>(150_000)
            var bytes = 0L
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    bytes += line.length + 1
                    if (bytes > MAX_BYTES) throw IOException("List too large")
                    val clean = line.substringBefore('#').trim()
                    if (clean.isEmpty()) continue
                    val parts = clean.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size < 2 || (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1")) continue
                    for (p in parts.drop(1)) {
                        val d = p.lowercase()
                        if (d !in notDomains && d.length <= 253 && domainRegex.matches(d)) found.add(d)
                    }
                }
            }
            if (found.size < 1000) throw IOException("The list looks broken")
            // Keep the old list rather than one that would cut off important sites
            unsafeEntry(found)?.let { throw UnsafeListException(it) }
            save(ctx, found)
            prefs(ctx).edit().putLong("adblock_updated", System.currentTimeMillis()).apply()
            File(ctx.filesDir, OLD_FILE).delete()
            return found.size
        } finally {
            conn.disconnect()
        }
    }
}
