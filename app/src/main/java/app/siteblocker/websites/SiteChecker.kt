package app.siteblocker.websites

import java.net.InetAddress

/**
 * Checks newly entered sites before they are added to the block list:
 * - does the domain exist at all (DNS lookup)?
 * - is it probably a typo of a well-known site (youtub.com -> youtube.com)?
 *
 * Network calls - only run this on a background thread.
 */
object SiteChecker {

    enum class Status { OK, NOT_FOUND, TYPO }

    data class Result(val entry: String, val status: Status, val suggestion: String? = null)

    /** Well-known sites used for spelling suggestions. */
    private val popular = listOf(
        "youtube.com", "facebook.com", "instagram.com", "x.com", "twitter.com", "tiktok.com",
        "reddit.com", "netflix.com", "twitch.tv", "amazon.com", "amazon.de", "ebay.com", "ebay.de",
        "google.com", "wikipedia.org", "linkedin.com", "pinterest.com", "snapchat.com",
        "tumblr.com", "discord.com", "whatsapp.com", "telegram.org", "spotify.com",
        "primevideo.com", "disneyplus.com", "hulu.com", "9gag.com", "imgur.com", "quora.com",
        "threads.net", "bsky.app", "vk.com", "roblox.com", "epicgames.com", "steampowered.com",
        "minecraft.net", "chess.com", "lichess.org", "crunchyroll.com", "dazn.com", "vimeo.com",
        "dailymotion.com", "soundcloud.com", "medium.com", "substack.com", "yahoo.com", "msn.com",
        "bing.com", "chatgpt.com", "cnn.com", "bbc.com", "bbc.co.uk", "nytimes.com",
        "theguardian.com", "foxnews.com", "espn.com", "bild.de", "spiegel.de", "zeit.de",
        "welt.de", "focus.de", "t-online.de", "web.de", "gmx.net", "chip.de", "kicker.de",
        "sport1.de", "transfermarkt.de", "chefkoch.de", "otto.de", "zalando.de", "kleinanzeigen.de",
        "mobile.de", "aliexpress.com", "temu.com", "shein.com", "wish.com", "etsy.com",
        "booking.com", "airbnb.com", "tinder.com", "bumble.com", "joyn.de", "rtl.de", "zdf.de",
        "ardmediathek.de", "bet365.com", "tipico.de", "bwin.com", "pokerstars.com", "stake.com",
        "github.com", "stackoverflow.com",
    )

    private val commonTlds = listOf("com", "de", "net", "org", "io", "tv")

    /** [offline] = no internet connection, some entries could not be checked. */
    class Report(val results: List<Result>, val offline: Boolean)

    /** Checks all entries. Keywords (no dot) are not checked. */
    fun check(entries: List<String>): Report {
        var online: Boolean? = null
        val results = entries.map { entry ->
            val host = hostOf(entry) ?: return@map Result(entry, Status.OK)

            if (resolves(host)) {
                val typo = likelyTypo(host)
                return@map if (typo != null) Result(entry, Status.TYPO, replaceHost(entry, typo))
                else Result(entry, Status.OK)
            }

            // Lookup failed: no internet, or the site really doesn't exist?
            if (online == null) online = resolves("google.com")
            if (online == false) return@map Result(entry, Status.OK)

            Result(entry, Status.NOT_FOUND, suggest(host)?.let { replaceHost(entry, it) })
        }
        return Report(results, online == false)
    }

    enum class Exists { YES, NO, UNKNOWN }

    /**
     * Does each entry on the block list still exist?
     * NO is only returned when the internet clearly works (well-known sites resolve
     * before and after the lookup), so a bad connection never marks a site as dead.
     */
    fun exists(entries: List<String>): Map<String, Exists> {
        val result = mutableMapOf<String, Exists>()
        var online: Boolean? = null
        for (entry in entries) {
            val host = hostOf(entry)
            result[entry] = when {
                host == null -> Exists.NO
                resolves(host) -> Exists.YES
                else -> {
                    if (online == null) online = isOnline()
                    if (online == true && isOnline()) Exists.NO else Exists.UNKNOWN
                }
            }
        }
        return result
    }

    private fun isOnline() = resolves("google.com") && resolves("cloudflare.com")

    // ---------- Suggestions ----------

    /** Suggestion for a domain that doesn't exist. */
    private fun suggest(host: String): String? {
        val label = host.substringBeforeLast('.')
        val tld = host.substringAfterLast('.')

        // 1. Close to a well-known site (youtub.com -> youtube.com)
        popular.map { it to distance(host, it) }
            .filter { it.second in 1..maxDistance(host) }
            .minByOrNull { it.second }
            ?.let { return it.first }

        // 2. Typo in the ending (youtube.cmo -> youtube.com)
        for (t in commonTlds) {
            val candidate = "$label.$t"
            if (candidate != host && resolves(candidate)) return candidate
        }

        // 3. Name close to a well-known site, keeping the ending if possible
        //    (youtub.de -> youtube.de)
        val best = popular.map { it to distance(label, it.substringBeforeLast('.')) }
            .filter { it.second in 1..maxDistance(label) }
            .minByOrNull { it.second }
            ?.first ?: return null
        val sameEnding = best.substringBeforeLast('.') + "." + tld
        return if (resolves(sameEnding)) sameEnding else best
    }

    /**
     * For a domain that exists but is one letter away from a well-known site
     * with the same ending (typo domains are often registered by someone else).
     */
    private fun likelyTypo(host: String): String? {
        if (host in popular) return null
        val label = host.substringBeforeLast('.')
        val tld = host.substringAfterLast('.')
        if (label.length < 5) return null
        return popular.firstOrNull {
            it.substringAfterLast('.') == tld && distance(label, it.substringBeforeLast('.')) == 1
        }
    }

    private fun maxDistance(s: String) = if (s.length <= 5) 1 else 2

    /** Edit distance where swapping two neighbouring letters counts as one mistake. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    // ---------- Helpers ----------

    /** Domain part of an entry, or null for a keyword. */
    private fun hostOf(entry: String): String? {
        val host = entry.substringBefore('/').substringBefore(':').lowercase()
        return if (host.contains('.') && !host.endsWith('.')) host else null
    }

    /** Keeps a path like /r/all when the domain is corrected. */
    private fun replaceHost(entry: String, newHost: String): String {
        val slash = entry.indexOf('/')
        return if (slash < 0) newHost else newHost + entry.substring(slash)
    }

    private fun resolves(host: String): Boolean = try {
        InetAddress.getAllByName(host).isNotEmpty()
    } catch (e: Exception) {
        false
    }
}
