package app.siteblocker

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The list of ad and tracker domains for the ad blocker.
 *
 * It is downloaded from the StevenBlack hosts project (a plain text file with lines
 * like "0.0.0.0 ads.example.com"). Only the domain names are kept – nothing from
 * the download is ever run. The list is stored in the app's private folder and
 * refreshed once a week.
 */
object AdBlockList {
    const val SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    private const val FILE = "adblock_hosts.txt"
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    private val domainRegex = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
    private val notDomains = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "0.0.0.0",
    )

    @Volatile
    private var domains: Set<String> = emptySet()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Number of domains in the stored list (also when the list isn't loaded). */
    fun count(ctx: Context) = prefs(ctx).getInt("adblock_domains", 0)

    /** When the list was last downloaded (0 = never). */
    fun lastUpdate(ctx: Context) = prefs(ctx).getLong("adblock_updated", 0)

    fun needsUpdate(ctx: Context) =
        !file(ctx).exists() || System.currentTimeMillis() - lastUpdate(ctx) > WEEK

    val isLoaded get() = domains.isNotEmpty()

    /** Loads the stored list into memory. */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        val set = HashSet<String>(150_000)
        f.forEachLine { if (it.isNotBlank()) set.add(it.trim()) }
        domains = set
    }

    /** Is this host (or one of its parent domains) an ad or tracker domain? */
    fun isBlocked(host: String): Boolean {
        val d = domains
        if (d.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in d) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            // Never check a bare top-level domain like "com"
            if (h.indexOf('.') < 0) return false
        }
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

            val target = file(ctx)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w -> for (d in found) { w.write(d); w.write("\n") } }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Couldn't save the list")
            }
            prefs(ctx).edit()
                .putLong("adblock_updated", System.currentTimeMillis())
                .putInt("adblock_domains", found.size)
                .apply()
            domains = found
            return found.size
        } finally {
            conn.disconnect()
        }
    }
}
