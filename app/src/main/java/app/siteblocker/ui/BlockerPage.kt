package app.siteblocker.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.siteblocker.BlockerService
import app.siteblocker.MainActivity
import app.siteblocker.R
import app.siteblocker.core.Stats
import app.siteblocker.websites.BlockConfig
import app.siteblocker.websites.SiteChecker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Page 1: blocking websites and apps – status, block lists, schedule, Alpha mode
 * and the chart of block screens.
 */
class BlockerPage(private val a: MainActivity) {

    private val scroll: ScrollView = a.findViewById(R.id.page_blocker)
    private val hero: View = a.findViewById(R.id.hero_blocker)
    private val ring: RingView = a.findViewById(R.id.ring_blocker)
    private val status: TextView = a.findViewById(R.id.status)
    private val headerSub: TextView = a.findViewById(R.id.header_sub)
    private val btnProtect: Button = a.findViewById(R.id.btn_protect)
    private val statSites: TextView = a.findViewById(R.id.stat_sites)
    private val statApps: TextView = a.findViewById(R.id.stat_apps)
    private val statBlocked: TextView = a.findViewById(R.id.stat_blocked)
    private val alphaStatus: TextView = a.findViewById(R.id.alpha_status)
    private val serviceCard: View = a.findViewById(R.id.service_card)
    private val chart: BarChartView = a.findViewById(R.id.chart_blocks)
    private val chartSub: TextView = a.findViewById(R.id.chart_blocks_sub)
    private val alwaysToggleText: TextView = a.findViewById(R.id.always_toggle_text)
    private val alwaysChevron: TextView = a.findViewById(R.id.always_chevron)
    private val alwaysSub: TextView = a.findViewById(R.id.always_sub)
    private val alwaysList: LinearLayout = a.findViewById(R.id.always_list)
    private val flexToggleText: TextView = a.findViewById(R.id.flex_toggle_text)
    private val flexChevron: TextView = a.findViewById(R.id.flex_chevron)
    private val flexSub: TextView = a.findViewById(R.id.flex_sub)
    private val flexList: LinearLayout = a.findViewById(R.id.flex_list)
    private val btnAddAlways: Button = a.findViewById(R.id.btn_add_always)
    private val btnAddFlex: Button = a.findViewById(R.id.btn_add_flex)
    private val input: EditText = a.findViewById(R.id.input_site)
    private val swSchedule: Switch = a.findViewById(R.id.sw_schedule)
    private val scheduleBox: LinearLayout = a.findViewById(R.id.schedule_box)
    private val btnStart: Button = a.findViewById(R.id.btn_start)
    private val btnEnd: Button = a.findViewById(R.id.btn_end)
    private val daysBox: LinearLayout = a.findViewById(R.id.days)
    private val strictStatus: TextView = a.findViewById(R.id.strict_status)
    private val btnStrict: Button = a.findViewById(R.id.btn_strict)

    private var alwaysExpanded = false
    private var flexExpanded = false
    /** List the sites being checked right now will be added to. */
    private var pendingAlways = true
    /** True after a successful fingerprint / PIN check, until the app goes to the background. */
    private var listUnlocked = false
    /** Result of the last existence check per site (green / red dot). */
    private val existence = mutableMapOf<String, SiteChecker.Exists>()
    private var checkingSites = false

    private val dayOrder = listOf(
        Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Tu", Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th", Calendar.FRIDAY to "Fr", Calendar.SATURDAY to "Sa",
        Calendar.SUNDAY to "Su",
    )
    private val dayChecks = mutableListOf<CheckBox>()
    private var binding = false
    private val dateFmt = SimpleDateFormat("EEEE, MMM d, yyyy 'at' HH:mm", Locale.ENGLISH)

    init {
        ring.setIcon(R.drawable.ic_shield)
        a.findViewById<View>(R.id.always_toggle).setOnClickListener { toggleList(true) }
        a.findViewById<View>(R.id.flex_toggle).setOnClickListener { toggleList(false) }
        a.findViewById<Button>(R.id.btn_service).setOnClickListener { openAccessibility() }
        btnAddAlways.setOnClickListener { addSites(true) }
        btnAddFlex.setOnClickListener { addSites(false) }
        a.findViewById<Button>(R.id.btn_add_app).setOnClickListener { pickApp() }
        btnProtect.setOnClickListener { onProtectTapped() }

        swSchedule.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            BlockConfig.setScheduleEnabled(a, checked); a.refresh()
        }
        btnStart.setOnClickListener {
            pickTime(BlockConfig.getStart(a)) { BlockConfig.setStart(a, it); a.refresh() }
        }
        btnEnd.setOnClickListener {
            pickTime(BlockConfig.getEnd(a)) { BlockConfig.setEnd(a, it); a.refresh() }
        }

        dayOrder.forEachIndexed { index, (day, label) ->
            val cb = CheckBox(a).apply {
                text = label
                setButtonDrawable(null as Drawable?)
                background = a.getDrawable(R.drawable.bg_day)
                setTextColor(a.getColorStateList(R.color.day_text))
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, a.dp(42), 1f).apply {
                    if (index > 0) marginStart = a.dp(6)
                }
                setOnCheckedChangeListener { _, checked ->
                    if (binding) return@setOnCheckedChangeListener
                    val bit = 1 shl (day - 1)
                    val d = BlockConfig.getDays(a)
                    BlockConfig.setDays(a, if (checked) d or bit else d and bit.inv())
                    a.refresh()
                }
            }
            dayChecks += cb
            daysBox.addView(cb)
        }

        btnStrict.setOnClickListener { pickStrictEnd() }

        // Alpha mode status: explains the lock while active, otherwise jumps to its card
        alphaStatus.setOnClickListener {
            if (a.isLocked()) a.denied()
            else scroll.smoothScrollTo(0, a.findViewById<View>(R.id.card_alpha).top)
        }
    }

    /** Leaving the app locks the lists again. */
    fun lock() {
        listUnlocked = false
        alwaysExpanded = false
        flexExpanded = false
    }

    private fun openAccessibility() {
        a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "${a.packageName}/${BlockerService::class.java.name}"
        val meShort = "${a.packageName}/.${BlockerService::class.java.simpleName}"
        return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    /** The big button: turn on, pause, or explain the Alpha mode lock. */
    private fun onProtectTapped() {
        when {
            !isServiceEnabled() -> openAccessibility()
            !BlockConfig.isEnabled(a) -> { BlockConfig.setEnabled(a, true); a.refresh() }
            a.isLocked() -> a.denied()
            else -> AlertDialog.Builder(a)
                .setTitle("Pause blocking?")
                .setMessage("Nothing will be blocked until you turn it back on.")
                .setPositiveButton("Pause") { _, _ -> BlockConfig.setEnabled(a, false); a.refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun refresh() {
        binding = true

        val serviceOn = isServiceEnabled()
        val enabled = BlockConfig.isEnabled(a)
        val lock = a.isLocked()
        // Green only while sites are really being blocked right now.
        // The line below the status says why the protection is not active.
        val reason = when {
            !serviceOn -> R.string.sub_service_off
            !enabled -> R.string.sub_disabled
            !BlockConfig.hasEntries(a) -> R.string.sub_no_sites
            BlockConfig.activeSites(a).isEmpty() && BlockConfig.activeApps(a).isEmpty() -> R.string.sub_schedule
            else -> null
        }
        val protectedNow = reason == null
        // Red when something must be done, orange when it's just nothing to block right now
        val color = a.getColor(
            when {
                protectedNow -> R.color.success
                reason == R.string.sub_service_off || reason == R.string.sub_disabled -> R.color.danger
                else -> R.color.warn
            }
        )
        ring.color = color
        ring.active = protectedNow
        hero.background = heroBackground(a, color)
        status.text = when {
            protectedNow -> "Protected"
            !enabled -> "Paused"
            else -> "Not protected"
        }
        status.setTextColor(color)
        headerSub.setText(reason ?: R.string.sub_active)

        when {
            !serviceOn -> button(btnProtect, "Turn on accessibility", primary = true)
            !enabled -> button(btnProtect, "Turn on blocking", primary = true)
            lock -> button(btnProtect, "🔒  Locked by Alpha mode", primary = false)
            else -> button(btnProtect, "❚❚  Pause blocking", primary = false)
        }
        serviceCard.visibility = if (serviceOn) View.GONE else View.VISIBLE

        // Alpha mode pill: orange dot and outline while on, grey when off
        val alphaColor = if (lock) a.getColor(R.color.accent2) else 0xFF8A8A8A.toInt()
        alphaStatus.pill(a.getString(if (lock) R.string.alpha_on else R.string.alpha_off), alphaColor)
        alphaStatus.alpha = if (lock) 1f else 0.75f

        // Numbers and chart
        statSites.text = (BlockConfig.getSites(a, true).size + BlockConfig.getSites(a, false).size).toString()
        statApps.text = (BlockConfig.getApps(a, true).size + BlockConfig.getApps(a, false).size).toString()
        statBlocked.text = num(Stats.today(a, Stats.SITES) + Stats.today(a, Stats.APPS))
        val sites = Stats.days(a, Stats.SITES, 7)
        val apps = Stats.days(a, Stats.APPS, 7)
        val week = IntArray(7) { sites[it] + apps[it] }
        chart.setData(week, dayLabels(7))
        val sum = week.sum()
        chartSub.text = if (sum == 0) "How often a site or app was blocked. Nothing in the last 7 days."
        else "Block screens shown: ${num(sum)} in the last 7 days " +
            "(${num(sites.sum())} sites, ${num(apps.sum())} apps)"

        // Schedule
        swSchedule.isChecked = BlockConfig.isScheduleEnabled(a)
        scheduleBox.visibility = if (swSchedule.isChecked) View.VISIBLE else View.GONE
        btnStart.text = fmt(BlockConfig.getStart(a))
        btnEnd.text = fmt(BlockConfig.getEnd(a))
        val days = BlockConfig.getDays(a)
        dayOrder.forEachIndexed { i, (day, _) ->
            dayChecks[i].isChecked = (days and (1 shl (day - 1))) != 0
        }

        // Alpha mode
        if (lock) {
            val until = BlockConfig.getStrictUntil(a)
            strictStatus.text = "🔒  Until ${dateFmt.format(Date(until))}\n      ${remaining(until)} left"
            strictStatus.setTextColor(a.getColor(R.color.warn))
            btnStrict.setText(R.string.strict_extend)
        } else {
            strictStatus.setText(R.string.strict_off)
            strictStatus.setTextColor(a.getColor(R.color.text_dim))
            btnStrict.setText(R.string.strict_start)
        }

        renderLists(lock)
        binding = false
    }

    /** Orange filled button for "turn on", dark outlined one for everything else. */
    private fun button(b: Button, text: String, primary: Boolean) {
        b.text = text
        b.background = a.getDrawable(if (primary) R.drawable.bg_button else R.drawable.bg_button_outline)
        b.setTextColor(if (primary) a.getColorStateList(R.color.button_text) else a.getColorStateList(R.color.text_dim))
    }

    private fun remaining(until: Long) = a.remaining(until)

    // ---------- Block lists ----------
    //
    // Two lists with websites and apps:
    //  - Always:      blocked around the clock; in Alpha mode nothing can be removed or moved out
    //  - On schedule: blocked at the scheduled times; can always be changed

    /** One entry on a block list: a website or an app (package name). */
    private data class Entry(val value: String, val isApp: Boolean)

    private data class AppItem(val pkg: String, val label: String, val icon: Drawable?)

    private fun entries(always: Boolean) =
        BlockConfig.getSites(a, always).map { Entry(it, false) } +
            BlockConfig.getApps(a, always).map { Entry(it, true) }

    /** App name, or null when the app is not installed (any more). */
    private fun appLabel(pkg: String): String? = try {
        a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { null }

    private fun appIcon(pkg: String): Drawable? = try {
        a.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) { null }

    private fun nameOf(e: Entry) = if (e.isApp) appLabel(e.value) ?: e.value else e.value

    /** Has no effect any more: a site that doesn't exist or an app that isn't installed. */
    private fun isDead(e: Entry) =
        if (e.isApp) appLabel(e.value) == null else existence[e.value] == SiteChecker.Exists.NO

    /** Opens / closes a list; the first time after opening the app it asks for fingerprint or PIN. */
    private fun toggleList(always: Boolean) {
        val expanded = if (always) alwaysExpanded else flexExpanded
        val open = {
            if (always) alwaysExpanded = true else flexExpanded = true
            a.refresh()
            checkExistence()
        }
        when {
            expanded -> { if (always) alwaysExpanded = false else flexExpanded = false; a.refresh() }
            listUnlocked -> open()
            else -> a.authenticate { listUnlocked = true; open() }
        }
    }

    private fun renderLists(lock: Boolean) {
        renderList(true, lock)
        renderList(false, lock)
    }

    private fun renderList(always: Boolean, lock: Boolean) {
        val expanded = if (always) alwaysExpanded else flexExpanded
        val box = if (always) alwaysList else flexList
        (if (always) alwaysToggleText else flexToggleText)
            .setText(if (always) R.string.list_always else R.string.list_flex)
        // Second line: what the list does right now and how many entries it has
        val count = BlockConfig.getSites(a, always).size + BlockConfig.getApps(a, always).size
        val entries = if (count == 1) "1 entry" else "$count entries"
        (if (always) alwaysSub else flexSub).text = when {
            always -> "Around the clock · $entries"
            !BlockConfig.isScheduleEnabled(a) -> "Schedule off – blocked all day · $entries"
            BlockConfig.isActiveNow(a) -> "Blocked right now · $entries"
            else -> "Not blocked right now · $entries"
        }
        (if (always) alwaysChevron else flexChevron).text = when {
            expanded -> "▴"
            listUnlocked -> "▾"
            else -> "🔒"
        }
        box.removeAllViews()
        box.visibility = if (expanded) View.VISIBLE else View.GONE
        if (!expanded) return

        box.addView(TextView(a).apply {
            setText(if (always) R.string.list_always_desc else R.string.list_flex_desc)
            textSize = 12f
            setTextColor(a.getColor(R.color.text_dim))
            setPadding(a.dp(4), a.dp(8), a.dp(4), 0)
        })
        val items = entries(always)
        if (items.isEmpty()) {
            box.addView(TextView(a).apply {
                setText(R.string.no_sites)
                gravity = Gravity.CENTER
                setPadding(0, a.dp(14), 0, a.dp(6))
                setTextColor(a.getColor(R.color.text_hint))
            })
            return
        }
        for (e in items) {
            val dead = isDead(e)
            // Always list in Alpha mode: can't be removed or moved (except entries without effect)
            val locked = always && lock && !dead
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = a.getDrawable(R.drawable.bg_chip)
                setPadding(a.dp(14), a.dp(4), a.dp(4), a.dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = a.dp(8) }
            }
            val icon = if (e.isApp) appIcon(e.value) else null
            if (icon != null) {
                // App: its icon
                row.addView(ImageView(a).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(a.dp(24), a.dp(24)).apply { marginEnd = a.dp(10) }
                })
            } else {
                // Website: green = exists, red = doesn't exist / app not installed, grey = not checked yet
                val color = when {
                    dead -> a.getColor(R.color.danger)
                    !e.isApp && existence[e.value] == SiteChecker.Exists.YES -> a.getColor(R.color.success)
                    else -> a.getColor(R.color.text_hint)
                }
                row.addView(View(a).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    layoutParams = LinearLayout.LayoutParams(a.dp(8), a.dp(8)).apply { marginEnd = a.dp(12) }
                })
            }
            row.addView(TextView(a).apply {
                text = if (e.isApp && dead) "${e.value} (not installed)" else nameOf(e)
                textSize = 15f
                setTextColor(a.getColor(R.color.text))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Move to the other list
            row.addView(smallButton("⇄", locked) { moveEntry(e, always) })
            row.addView(smallButton(if (locked) "🔒" else "✕", locked) { removeEntry(e, always) })
            box.addView(row)
        }
    }

    private fun smallButton(label: String, dimmed: Boolean, onClick: () -> Unit) =
        Button(a, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 16f
            setTextColor(a.getColor(R.color.text_dim))
            minWidth = a.dp(40)
            minimumWidth = a.dp(40)
            setPadding(0, 0, 0, 0)
            alpha = if (dimmed) 0.5f else 1f
            setOnClickListener { onClick() }
        }

    private fun addTo(e: Entry, always: Boolean) {
        if (e.isApp) {
            val apps = BlockConfig.getApps(a, always)
            if (e.value !in apps) BlockConfig.setApps(a, apps + e.value, always)
        } else {
            val sites = BlockConfig.getSites(a, always)
            if (sites.none { it.equals(e.value, true) }) BlockConfig.setSites(a, sites + e.value, always)
        }
    }

    private fun removeFrom(e: Entry, always: Boolean) {
        if (e.isApp) BlockConfig.setApps(a, BlockConfig.getApps(a, always).filter { it != e.value }, always)
        else BlockConfig.setSites(a, BlockConfig.getSites(a, always).filter { it != e.value }, always)
    }

    /** Always → On schedule is not possible in Alpha mode; the other way round always is. */
    private fun moveEntry(e: Entry, fromAlways: Boolean) {
        if (fromAlways && a.isLocked() && !isDead(e)) { a.denied(); return }
        val name = nameOf(e)
        val dialog = AlertDialog.Builder(a)
        if (fromAlways) {
            dialog.setTitle("Move to the On schedule list?")
                .setMessage("\"$name\" will only be blocked at the scheduled times.")
        } else {
            dialog.setTitle("Move to the Always list?")
                .setMessage(
                    "\"$name\" will be blocked around the clock." +
                        if (a.isLocked()) "\n\nAlpha mode is on: it can't be moved back until Alpha mode ends." else ""
                )
        }
        dialog.setPositiveButton("Move") { _, _ ->
            removeFrom(e, fromAlways)
            addTo(e, !fromAlways)
            a.refresh()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun removeEntry(e: Entry, always: Boolean) {
        val name = nameOf(e)
        if (always && a.isLocked()) {
            // In Alpha mode only entries without effect can be removed from the Always list
            if (e.isApp) {
                if (appLabel(e.value) != null) { a.denied(); return }
                confirmRemove(e, true, "\"${e.value}\" is no longer installed.\n\nRemove it from the block list?")
                return
            }
            if (existence[e.value] != SiteChecker.Exists.NO) { a.denied(); return }
            // Check again online first
            Thread {
                val dead = SiteChecker.exists(listOf(e.value))[e.value] == SiteChecker.Exists.NO
                a.runOnUiThread {
                    if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                    if (!dead) {
                        existence.remove(e.value); a.refresh(); a.denied()
                        return@runOnUiThread
                    }
                    confirmRemove(e, true, "\"$name\" doesn't exist, so blocking it has no effect.\n\nRemove it from the block list?")
                }
            }.start()
            return
        }
        confirmRemove(
            e, always,
            when {
                !e.isApp && existence[e.value] == SiteChecker.Exists.NO -> "\"$name\" doesn't exist. Remove it from the block list?"
                else -> "Remove \"$name\" from the block list?"
            }
        )
    }

    private fun confirmRemove(e: Entry, always: Boolean, message: String) {
        AlertDialog.Builder(a)
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ -> removeFrom(e, always); a.refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Adding websites ----------

    /**
     * Adds all entered sites (separated by new lines, commas, semicolons or spaces)
     * to the Always list or the On schedule list. Each domain is checked first; for
     * unknown or misspelled ones the user can pick the correction, keep it or skip it.
     */
    private fun addSites(always: Boolean) {
        val entries = input.text.toString()
            .split(Regex("[\\s,;]+"))
            .map { BlockConfig.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (entries.isEmpty()) {
            a.toast("Enter a website first, e.g. youtube.com")
            return
        }
        // Keywords are not supported: only real websites like youtube.com
        val notSites = entries.filterNot { BlockConfig.isWebsite(it) }
        if (notSites.isNotEmpty()) {
            a.toast("Not a website: ${notSites.joinToString(", ")} – enter e.g. ${notSites.first()}.com")
            return
        }
        val existing = BlockConfig.getSites(a, always)
        val newOnes = entries.filter { e -> existing.none { it.equals(e, true) } }
        if (newOnes.isEmpty()) { a.toast("Already on this list"); return }

        pendingAlways = always
        val btn = if (always) btnAddAlways else btnAddFlex
        btnAddAlways.isEnabled = false
        btnAddFlex.isEnabled = false
        btn.text = "Checking…"
        Thread {
            val report = SiteChecker.check(newOnes)
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                btnAddAlways.isEnabled = true
                btnAddFlex.isEnabled = true
                btnAddAlways.setText(R.string.add_always)
                btnAddFlex.setText(R.string.add_flex)
                input.setText("")
                if (report.offline) a.toast("No internet – added without checking")
                reviewNext(ArrayDeque(report.results), mutableListOf())
            }
        }.start()
    }

    /** Goes through the checked entries one by one and asks about the doubtful ones. */
    private fun reviewNext(queue: ArrayDeque<SiteChecker.Result>, accepted: MutableList<String>) {
        val r = queue.removeFirstOrNull() ?: return saveAdded(accepted)
        if (r.status == SiteChecker.Status.OK) { accepted += r.entry; reviewNext(queue, accepted); return }

        val next = { site: String? -> site?.let { accepted += it }; reviewNext(queue, accepted) }
        val dialog = AlertDialog.Builder(a).setCancelable(false)
        val fix = r.suggestion
        if (fix != null) {
            dialog.setTitle("Did you mean $fix?")
                .setMessage(
                    if (r.status == SiteChecker.Status.TYPO)
                        "\"${r.entry}\" looks like a typo of \"$fix\"."
                    else
                        "\"${r.entry}\" couldn't be found."
                )
                .setPositiveButton("Use $fix") { _, _ -> next(fix) }
                .setNeutralButton("Keep as typed") { _, _ -> next(r.entry) }
                .setNegativeButton("Skip") { _, _ -> next(null) }
        } else {
            dialog.setTitle("Site not found")
                .setMessage("\"${r.entry}\" couldn't be found. Please check the spelling.")
                .setPositiveButton("Add anyway") { _, _ -> next(r.entry) }
                .setNegativeButton("Skip") { _, _ -> next(null) }
        }
        dialog.show()
    }

    /**
     * Saves the accepted sites. A site on the other list is moved when that makes
     * blocking stricter (On schedule → Always); an Always site is never weakened here.
     */
    private fun saveAdded(accepted: List<String>) {
        if (accepted.isEmpty()) return
        val always = pendingAlways
        val target = BlockConfig.getSites(a, always).toMutableList()
        val other = BlockConfig.getSites(a, !always).toMutableList()
        var added = 0
        var onAlways = 0
        for (e in accepted) {
            if (target.any { it.equals(e, true) }) continue
            val inOther = other.firstOrNull { it.equals(e, true) }
            if (inOther != null) {
                if (!always) { onAlways++; continue }
                other.remove(inOther)
            }
            target.add(e)
            added++
        }
        BlockConfig.setSites(a, target, always)
        BlockConfig.setSites(a, other, !always)
        if (alwaysExpanded || flexExpanded) checkExistence()
        a.toast(
            when {
                onAlways > 0 && added == 0 -> "Already on the Always list"
                added == 0 -> "Already on this list"
                always -> if (added == 1) "Blocked always" else "$added sites blocked always"
                else -> if (added == 1) "Blocked on schedule" else "$added sites blocked on schedule"
            }
        )
        a.refresh()
    }

    // ---------- Adding apps ----------

    /** Shows the installed apps; the chosen one goes to the Always or the On schedule list. */
    private fun pickApp() {
        a.toast("Loading apps…")
        Thread {
            val blocked = (BlockConfig.getApps(a, true) + BlockConfig.getApps(a, false)).toSet()
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = a.packageManager.queryIntentActivities(launcher, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .filter { it !in blocked && BlockConfig.canBlockApp(a, it) }
                .mapNotNull { pkg -> appLabel(pkg)?.let { AppItem(pkg, it, appIcon(pkg)) } }
                .sortedBy { it.label.lowercase() }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (apps.isEmpty()) { a.toast("No apps to block"); return@runOnUiThread }
                val adapter = object : ArrayAdapter<AppItem>(a, 0, apps) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val item = getItem(position)!!
                        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(a.dp(22), a.dp(10), a.dp(22), a.dp(10))
                            addView(ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { marginEnd = a.dp(16) }
                            })
                            addView(TextView(context).apply {
                                textSize = 16f
                                setTextColor(a.getColor(R.color.text))
                            })
                        }
                        (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
                        (row.getChildAt(1) as TextView).text = item.label
                        return row
                    }
                }
                AlertDialog.Builder(a)
                    .setTitle("Which app?")
                    .setAdapter(adapter) { _, which -> chooseAppList(apps[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun chooseAppList(app: AppItem) {
        AlertDialog.Builder(a)
            .setTitle("Block ${app.label}")
            .setMessage(
                "Always: blocked around the clock, protected by Alpha mode.\n\n" +
                    "On schedule: blocked at the scheduled times, can always be changed."
            )
            .setPositiveButton("Always") { _, _ -> addApp(app, true) }
            .setNeutralButton("On schedule") { _, _ -> addApp(app, false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addApp(app: AppItem, always: Boolean) {
        addTo(Entry(app.pkg, true), always)
        a.toast("${app.label} " + if (always) "blocked always" else "blocked on schedule")
        a.refresh()
    }

    /** Checks in the background whether the sites on both lists still exist. */
    private fun checkExistence() {
        if (checkingSites) return
        val sites = (BlockConfig.getSites(a, true) + BlockConfig.getSites(a, false)).distinct()
        if (sites.isEmpty()) return
        checkingSites = true
        Thread {
            val result = SiteChecker.exists(sites)
            a.runOnUiThread {
                checkingSites = false
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                existence.clear()
                existence.putAll(result)
                a.refresh()
            }
        }.start()
    }

    // ---------- Alpha mode ----------

    /** Pick the date in a calendar first, then the time. */
    private fun pickStrictEnd() {
        val now = System.currentTimeMillis()
        val current = BlockConfig.getStrictUntil(a)
        val minimum = if (current > now) current else now
        val proposal = Calendar.getInstance().apply {
            timeInMillis = minimum
            add(Calendar.HOUR_OF_DAY, 1)
        }

        val dp = DatePickerDialog(a, { _, y, m, d ->
            TimePickerDialog(a, { _, h, min ->
                val end = Calendar.getInstance().apply {
                    set(y, m, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                confirmStrict(end)
            }, proposal.get(Calendar.HOUR_OF_DAY), proposal.get(Calendar.MINUTE), true).apply {
                setTitle("Until what time?")
            }.show()
        }, proposal.get(Calendar.YEAR), proposal.get(Calendar.MONTH), proposal.get(Calendar.DAY_OF_MONTH))

        // Earlier days can't be selected
        dp.datePicker.minDate = Calendar.getInstance().apply {
            timeInMillis = minimum
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        dp.setTitle("Alpha mode until which day?")
        dp.show()
    }

    private fun confirmStrict(end: Long) {
        val now = System.currentTimeMillis()
        val current = BlockConfig.getStrictUntil(a)
        if (end <= now) {
            a.toast("That time is in the past.", long = true)
            return
        }
        if (current > now && end <= current) {
            a.toast("Alpha mode can only be extended, not shortened.", long = true)
            return
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.strict_header)
            .setMessage(
                "Alpha mode until ${dateFmt.format(Date(end))}\n(${remaining(end)})\n\n" +
                    "Until then you can't remove or move anything on the Always list, turn blocking off, " +
                    "or turn off or uninstall WebBlock in the settings. The On schedule list and the " +
                    "schedule stay changeable. This cannot be undone."
            )
            .setPositiveButton("Start") { _, _ ->
                BlockConfig.setEnabled(a, true)
                BlockConfig.setStrictUntil(a, end)
                a.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Helpers ----------

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(a, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)
}
