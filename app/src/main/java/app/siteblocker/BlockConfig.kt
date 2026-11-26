package app.siteblocker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar

/** All app settings plus the logic that decides whether a URL is blocked. */
object BlockConfig {
    private const val PREFS = "config"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Block lists ----
    //
    // Two lists, each with websites and apps:
    //  - "Always":      blocked around the clock, protected by Alpha mode
    //  - "On schedule": blocked only at the scheduled times, can always be changed
    // The Always websites keep the key "sites" used by older versions.

    private fun key(kind: String, always: Boolean) = when (kind) {
        "sites" -> if (always) "sites" else "flex_sites"
        else -> if (always) "apps" else "flex_apps"
    }

    private fun readList(ctx: Context, key: String): List<String> {
        migrate(ctx)
        return (prefs(ctx).getString(key, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getSites(ctx: Context, always: Boolean = true): List<String> =
        readList(ctx, key("sites", always)).filter { isWebsite(it) }

    fun setSites(ctx: Context, sites: List<String>, always: Boolean = true) =
        prefs(ctx).edit().putString(key("sites", always), sites.joinToString("\n")).apply()

    /** Blocked apps (package names). */
    fun getApps(ctx: Context, always: Boolean = true): List<String> = readList(ctx, key("apps", always))

    fun setApps(ctx: Context, apps: List<String>, always: Boolean = true) =
        prefs(ctx).edit().putString(key("apps", always), apps.joinToString("\n")).apply()

    /**
     * Apps that can never be blocked, so you can't lock yourself out:
     * WebBlock itself, phone, settings, system UI and the home screen.
     */
    fun canBlockApp(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg == "com.android.phone") return false
        if (listOf("settings", "dialer", "packageinstaller", "permissioncontroller", "emergency", "inputmethod", "keyboard")
                .any { pkg.contains(it) }
        ) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(home, 0).none { it.activityInfo?.packageName == pkg }
    }

    fun hasEntries(ctx: Context) = listOf(true, false).any { getSites(ctx, it).isNotEmpty() || getApps(ctx, it).isNotEmpty() }

    /** Websites to block right now (Always list plus, inside the schedule, the On schedule list). */
    fun activeSites(ctx: Context): List<String> {
        if (!isEnabled(ctx)) return emptyList()
        return if (isActiveNow(ctx)) getSites(ctx, true) + getSites(ctx, false) else getSites(ctx, true)
    }

    /** Apps to block right now. */
    fun activeApps(ctx: Context): Set<String> {
        if (!isEnabled(ctx)) return emptySet()
        return (if (isActiveNow(ctx)) getApps(ctx, true) + getApps(ctx, false) else getApps(ctx, true)).toSet()
    }

    /**
     * Version 1.21 split the list in two. Before, all sites followed the schedule:
     * with a schedule turned on (and no Alpha mode running) they move to the
     * On schedule list so nothing changes; otherwise they stay on the Always list.
     */
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("lists_v2", false)) return
        val e = p.edit().putBoolean("lists_v2", true)
        if (p.getBoolean("schedule", false) && p.getLong("strict_until", 0L) <= System.currentTimeMillis()) {
            e.putString("flex_sites", p.getString("sites", "")).putString("sites", "")
        }
        e.commit()
    }

    /** Only real websites (with a dot in the domain) can be blocked; keywords are not supported. */
    fun isWebsite(entry: String): Boolean {
        val host = entry.substringBefore('/').substringBefore(':')
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    fun setSites(ctx: Context, sites: List<String>) =
        prefs(ctx).edit().putString("sites", sites.joinToString("\n")).apply()

    // ---- Switches ----
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    // ---- Alpha mode (stored as "strict"): locked for a chosen time ----
    //
    // The remaining time is counted down with the phone's uptime clock
    // (SystemClock.elapsedRealtime), not with the date/time setting, so moving the
    // clock forward can't end Alpha mode early. After a restart the uptime clock
    // starts again at 0; the time the phone was off then only counts when Android
    // takes the time from the network ("Set time automatically").

    /** End of Alpha mode in wall-clock time (for display), 0 = never started. */
    fun getStrictUntil(ctx: Context): Long {
        tick(ctx)
        return prefs(ctx).getLong("strict_until", 0L)
    }

    fun setStrictUntil(ctx: Context, v: Long) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong("strict_until", v)
            .putLong("strict_remaining", (v - now).coerceAtLeast(0L))
            .putLong("strict_elapsed", SystemClock.elapsedRealtime())
            .putLong("strict_wall", now)
            .putInt("strict_boot", bootCount(ctx))
            .commit()
    }

    fun isStrictLocked(ctx: Context): Boolean {
        tick(ctx)
        return prefs(ctx).getLong("strict_remaining", 0L) > 0L
    }

    @Synchronized
    private fun tick(ctx: Context) {
        val p = prefs(ctx)
        val until = p.getLong("strict_until", 0L)
        if (until == 0L) return
        if (!p.contains("strict_remaining")) { setStrictUntil(ctx, until); return } // set by version ≤ 1.18
        var remaining = p.getLong("strict_remaining", 0L)
        if (remaining <= 0L) return

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(ctx)
        val lastElapsed = p.getLong("strict_elapsed", elapsed)
        val passed = when {
            boot == p.getInt("strict_boot", boot) && elapsed >= lastElapsed -> elapsed - lastElapsed
            autoTime(ctx) -> (now - p.getLong("strict_wall", now)).coerceAtLeast(0L)
            else -> elapsed // restarted and clock set by hand: only count the time since the restart
        }
        remaining = (remaining - passed).coerceAtLeast(0L)
        p.edit()
            .putLong("strict_remaining", remaining)
            .putLong("strict_until", now + remaining)
            .putLong("strict_elapsed", elapsed)
            .putLong("strict_wall", now)
            .putInt("strict_boot", boot)
            .apply()
    }

    private fun bootCount(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun autoTime(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 0) == 1

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

    /** Is the On schedule list active right now? (No schedule set = always.) */
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
     */
    fun matchesRule(url: String, rule: String): Boolean {
        val u = normalize(url)
        val r = normalize(rule)
        if (r.isEmpty() || u.isEmpty()) return false
        if (!isWebsite(r)) return false

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
