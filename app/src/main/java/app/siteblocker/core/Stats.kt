package app.siteblocker.core

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Daily numbers for the charts: blocked ads, DNS lookups, block screens for
 * websites and apps. Kept in memory and written to the "stats" preferences at
 * most every 10 seconds (the ad blocker counts many lookups per second).
 * The last [KEEP_DAYS] days are kept; nothing ever leaves the phone.
 */
object Stats {
    const val ADS = "ads"        // ad & tracker lookups blocked
    const val LOOKUPS = "dns"    // all lookups the ad blocker answered
    const val SITES = "sites"    // website block screens shown
    const val APPS = "apps"      // app block screens shown

    private const val KEEP_DAYS = 60
    private const val SAVE_EVERY = 10_000L
    private const val TOP_KEEP = 30
    private const val TOP_MAX = 2_000

    private val lock = Any()
    private var loaded = false
    private var day = 0
    private val today = HashMap<String, Int>()
    private val totals = HashMap<String, Long>()
    private val top = HashMap<String, Int>()
    private var dirty = false
    private var lastSave = 0L
    private var checkedAt = 0L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)

    /** yyyymmdd of the day [back] days before today. */
    private fun dayKey(back: Int = 0): Int {
        val c = Calendar.getInstance()
        if (back != 0) c.add(Calendar.DAY_OF_YEAR, -back)
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun key(day: Int, metric: String) = "d${day}_$metric"

    /** Loads today's numbers and starts a new day when the date changed. Call with [lock] held. */
    private fun ensure(ctx: Context) {
        // The date is checked at most once a second (lookups can come many times a second)
        val tick = System.nanoTime()
        if (loaded && tick - checkedAt < 1_000_000_000L) return
        checkedAt = tick
        val now = dayKey()
        if (loaded && now == day) return
        val p = prefs(ctx)
        if (loaded) save(ctx, force = true)   // close the old day
        if (!p.contains("since")) migrate(ctx, p)
        day = now
        today.clear()
        top.clear()
        totals.clear()
        for (m in listOf(ADS, LOOKUPS, SITES, APPS)) {
            today[m] = p.getInt(key(day, m), 0)
            totals[m] = p.getLong("total_$m", 0)
        }
        p.getString("top_$day", null)?.lineSequence()?.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { top[line.substring(0, tab)] = it }
        }
        if (loaded) prune(p)
        loaded = true
    }

    /** Version 1.27 and older only counted today's blocked ads. */
    private fun migrate(ctx: Context, p: SharedPreferences) {
        val old = ctx.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)
        val c = Calendar.getInstance()
        val oldDay = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val count = if (old.getInt("adblock_day", 0) == oldDay) old.getInt("adblock_count", 0) else 0
        p.edit()
            .putLong("since", System.currentTimeMillis())
            .putInt(key(dayKey(), ADS), count)
            .putLong("total_$ADS", count.toLong())
            .apply()
    }

    /** Removes days older than [KEEP_DAYS]. */
    private fun prune(p: SharedPreferences) {
        val oldest = dayKey(KEEP_DAYS)
        val e = p.edit()
        for (k in p.all.keys) {
            val d = when {
                k.startsWith("d") && k.contains('_') -> k.substring(1, k.indexOf('_')).toIntOrNull()
                k.startsWith("top_") -> k.substring(4).toIntOrNull()
                else -> null
            } ?: continue
            if (d < oldest) e.remove(k)
        }
        e.apply()
    }

    private fun save(ctx: Context, force: Boolean = false) {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < SAVE_EVERY) return
        lastSave = now
        dirty = false
        val e = prefs(ctx).edit()
        for ((m, v) in today) e.putInt(key(day, m), v)
        for ((m, v) in totals) e.putLong("total_$m", v)
        if (top.isNotEmpty()) {
            e.putString("top_$day", top.entries.sortedByDescending { it.value }.take(TOP_KEEP)
                .joinToString("\n") { "${it.key}\t${it.value}" })
        }
        e.apply()
    }

    /** Counts [n] events of [metric] today; [domain] is remembered for the "most blocked" list. */
    fun add(ctx: Context, metric: String, n: Int = 1, domain: String? = null) = synchronized(lock) {
        ensure(ctx)
        today[metric] = (today[metric] ?: 0) + n
        totals[metric] = (totals[metric] ?: 0L) + n
        if (domain != null) {
            val d = domain.trimEnd('.').lowercase()
            val c = top[d]
            if (c != null) top[d] = c + n else if (top.size < TOP_MAX) top[d] = n
        }
        dirty = true
        save(ctx)
    }

    /** Writes everything right away (when the ad blocker stops). */
    fun flush(ctx: Context) = synchronized(lock) { if (loaded) save(ctx, force = true) }

    fun today(ctx: Context, metric: String): Int = synchronized(lock) { ensure(ctx); today[metric] ?: 0 }

    fun total(ctx: Context, metric: String): Long = synchronized(lock) { ensure(ctx); totals[metric] ?: 0L }

    /** When counting started. */
    fun since(ctx: Context): Long = synchronized(lock) { ensure(ctx); prefs(ctx).getLong("since", 0) }

    /** The last [count] days, oldest first; the last value is today. */
    fun days(ctx: Context, metric: String, count: Int): IntArray = synchronized(lock) {
        ensure(ctx)
        val p = prefs(ctx)
        IntArray(count) { i ->
            val back = count - 1 - i
            if (back == 0) today[metric] ?: 0 else p.getInt(key(dayKey(back), metric), 0)
        }
    }

    /** Most blocked domains today, most first. */
    fun topToday(ctx: Context, count: Int): List<Pair<String, Int>> = synchronized(lock) {
        ensure(ctx)
        top.entries.sortedByDescending { it.value }.take(count).map { it.key to it.value }
    }
}
