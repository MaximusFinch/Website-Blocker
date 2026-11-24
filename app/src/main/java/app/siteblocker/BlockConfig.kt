package app.siteblocker

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/** All app settings plus the logic that decides whether a URL is blocked. */
object BlockConfig {
    private const val PREFS = "config"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Block list ----
    fun getSites(ctx: Context): List<String> =
        (prefs(ctx).getString("sites", "") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun setSites(ctx: Context, sites: List<String>) =
        prefs(ctx).edit().putString("sites", sites.joinToString("\n")).apply()

    // ---- Switches ----
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    // ---- Strict mode: locked until a point in time (milliseconds, 0 = off) ----
    fun getStrictUntil(ctx: Context) = prefs(ctx).getLong("strict_until", 0L)
    fun setStrictUntil(ctx: Context, v: Long) = prefs(ctx).edit().putLong("strict_until", v).apply()
    fun isStrictLocked(ctx: Context) = getStrictUntil(ctx) > System.currentTimeMillis()

    // ---- Schedule ----
    fun isScheduleEnabled(ctx: Context) = prefs(ctx).getBoolean("schedule", false)
    fun setScheduleEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("schedule", v).apply()

    /** Minutes since midnight */
    fun getStart(ctx: Context) = prefs(ctx).getInt("start", 9 * 60)
    fun setStart(ctx: Context, v: Int) = prefs(ctx).edit().putInt("start", v).apply()
    fun getEnd(ctx: Context) = prefs(ctx).getInt("end", 17 * 60)
    fun setEnd(ctx: Context, v: Int) = prefs(ctx).edit().putInt("end", v).apply()

    /** Bitmask: bit (Calendar.DAY_OF_WEEK - 1). Default: Mon–Fri. */
    fun getDays(ctx: Context) = prefs(ctx).getInt("days", 0b0111110)
    fun setDays(ctx: Context, v: Int) = prefs(ctx).edit().putInt("days", v).apply()

    /** Is blocking active right now? */
    fun isActiveNow(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        if (!isScheduleEnabled(ctx)) return true
        val cal = Calendar.getInstance()
        val dayBit = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
        if (getDays(ctx) and dayBit == 0) return false
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = getStart(ctx)
        val end = getEnd(ctx)
        return when {
            start == end -> true                 // whole day
            start < end -> now in start until end
            else -> now >= start || now < end    // across midnight
        }
    }

    // ---- URL matching ----
    fun normalize(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.removePrefix("www.")
        return s.trimEnd('/')
    }

    /**
     * Rules:
     *  - "youtube.com"        -> youtube.com and all subdomains (m.youtube.com ...)
     *  - "reddit.com/r/funny" -> only this path (and below)
     *  - "casino"             -> keyword (no dot): blocks every URL containing it
     */
    fun matchesRule(url: String, rule: String): Boolean {
        val u = normalize(url)
        val r = normalize(rule)
        if (r.isEmpty() || u.isEmpty()) return false
        if (!r.contains('.') && !r.contains('/')) return u.contains(r)

        val hostPart = u.substringBefore('/')
        val host = hostPart.substringBefore(':').substringBefore('?')
        val rest = u.substring(hostPart.length)
        val rHost = r.substringBefore('/')
        val rPath = r.substring(rHost.length)

        val hostOk = host == rHost || host.endsWith(".$rHost")
        return hostOk && (rPath.isEmpty() || rest.startsWith(rPath))
    }

    fun findMatch(url: String, sites: List<String>): String? =
        sites.firstOrNull { matchesRule(url, it) }
}
