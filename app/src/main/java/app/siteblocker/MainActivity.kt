package app.siteblocker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var headerSub: TextView
    private lateinit var headerIcon: View
    private lateinit var cardEnabled: View
    private lateinit var cardSchedule: View
    private lateinit var sitesToggleText: TextView
    private lateinit var sitesChevron: TextView
    private var listExpanded = false
    private lateinit var serviceCard: View
    private lateinit var btnService: Button
    private lateinit var locked: TextView
    private lateinit var swEnabled: Switch
    private lateinit var input: EditText
    private lateinit var siteList: LinearLayout
    private lateinit var swSchedule: Switch
    private lateinit var scheduleBox: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var daysBox: LinearLayout
    private lateinit var strictStatus: TextView
    private lateinit var btnStrict: Button

    private val dayOrder = listOf(
        Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Tu", Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th", Calendar.FRIDAY to "Fr", Calendar.SATURDAY to "Sa",
        Calendar.SUNDAY to "Su",
    )
    private val dayChecks = mutableListOf<CheckBox>()
    private var binding = false
    private val dateFmt = SimpleDateFormat("EEEE, MMM d, yyyy 'at' HH:mm", Locale.ENGLISH)

    // Refresh every 30 s so the strict mode countdown stays current
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 30_000) }
    }


    /** Always show the app in English, including system date/time pickers. */
    override fun attachBaseContext(base: Context) {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.ENGLISH)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        headerSub = findViewById(R.id.header_sub)
        headerIcon = findViewById(R.id.header_icon)
        cardEnabled = findViewById(R.id.card_enabled)
        cardSchedule = findViewById(R.id.card_schedule)
        sitesToggleText = findViewById(R.id.sites_toggle_text)
        sitesChevron = findViewById(R.id.sites_chevron)
        findViewById<View>(R.id.sites_toggle).setOnClickListener {
            listExpanded = !listExpanded
            refresh()
        }
        serviceCard = findViewById(R.id.service_card)
        btnService = findViewById(R.id.btn_service)
        locked = findViewById(R.id.locked)
        swEnabled = findViewById(R.id.sw_enabled)
        input = findViewById(R.id.input_site)
        siteList = findViewById(R.id.site_list)
        swSchedule = findViewById(R.id.sw_schedule)
        scheduleBox = findViewById(R.id.schedule_box)
        btnStart = findViewById(R.id.btn_start)
        btnEnd = findViewById(R.id.btn_end)
        daysBox = findViewById(R.id.days)
        strictStatus = findViewById(R.id.strict_status)
        btnStrict = findViewById(R.id.btn_strict)

        btnService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { addSites() }

        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (!checked && isLocked()) { denied(); refresh(); return@setOnCheckedChangeListener }
            BlockConfig.setEnabled(this, checked); refresh()
        }

        swSchedule.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (isLocked()) { denied(); refresh(); return@setOnCheckedChangeListener }
            BlockConfig.setScheduleEnabled(this, checked); refresh()
        }

        btnStart.setOnClickListener {
            if (isLocked()) { denied(); return@setOnClickListener }
            pickTime(BlockConfig.getStart(this)) { BlockConfig.setStart(this, it); refresh() }
        }
        btnEnd.setOnClickListener {
            if (isLocked()) { denied(); return@setOnClickListener }
            pickTime(BlockConfig.getEnd(this)) { BlockConfig.setEnd(this, it); refresh() }
        }

        dayOrder.forEachIndexed { index, (day, label) ->
            val cb = CheckBox(this).apply {
                text = label
                setButtonDrawable(null as Drawable?)
                background = getDrawable(R.drawable.bg_day)
                setTextColor(getColorStateList(R.color.day_text))
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
                setOnCheckedChangeListener { _, checked ->
                    if (binding) return@setOnCheckedChangeListener
                    if (isLocked()) { denied(); refresh(); return@setOnCheckedChangeListener }
                    val bit = 1 shl (day - 1)
                    val d = BlockConfig.getDays(this@MainActivity)
                    BlockConfig.setDays(this@MainActivity, if (checked) d or bit else d and bit.inv())
                    refresh()
                }
            }
            dayChecks += cb
            daysBox.addView(cb)
        }

        btnStrict.setOnClickListener { pickStrictEnd() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun isLocked() = BlockConfig.isStrictLocked(this)

    private fun denied() =
        Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${BlockerService::class.java.name}"
        val meShort = "$packageName/.${BlockerService::class.java.simpleName}"
        return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    private fun refresh() {
        binding = true

        val serviceOn = isServiceEnabled()
        // Green = blocking now, amber = paused (off / outside schedule), red = accessibility off
        val (statusText, statusColor, statusTextColor) = when {
            !serviceOn -> Triple(R.string.status_off, getColor(R.color.danger), 0xFFFFFFFF.toInt())
            !BlockConfig.isActiveNow(this) -> Triple(R.string.status_paused, getColor(R.color.warn), 0xFF2B1C00.toInt())
            else -> Triple(R.string.status_ok, getColor(R.color.success), 0xFF06281A.toInt())
        }
        status.setText(statusText)
        status.setTextColor(statusTextColor)
        status.background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(statusColor)
        }
        headerIcon.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor((statusColor and 0x00FFFFFF) or 0x59000000) // same color, 35 % opacity
        }
        serviceCard.visibility = if (serviceOn) View.GONE else View.VISIBLE

        val count = BlockConfig.getSites(this).size
        headerSub.text = when (count) {
            0 -> "No sites blocked yet"
            1 -> "1 site blocked"
            else -> "$count sites blocked"
        }

        val lock = isLocked()
        locked.visibility = if (lock) View.VISIBLE else View.GONE
        // Can't be changed in strict mode -> don't show at all
        cardEnabled.visibility = if (lock) View.GONE else View.VISIBLE
        cardSchedule.visibility = if (lock) View.GONE else View.VISIBLE

        swEnabled.isChecked = BlockConfig.isEnabled(this)
        swSchedule.isChecked = BlockConfig.isScheduleEnabled(this)
        scheduleBox.visibility = if (swSchedule.isChecked) View.VISIBLE else View.GONE
        btnStart.text = fmt(BlockConfig.getStart(this))
        btnEnd.text = fmt(BlockConfig.getEnd(this))
        val days = BlockConfig.getDays(this)
        dayOrder.forEachIndexed { i, (day, _) ->
            dayChecks[i].isChecked = days and (1 shl (day - 1)) != 0
        }

        if (lock) {
            val until = BlockConfig.getStrictUntil(this)
            strictStatus.text = "🔒  Until ${dateFmt.format(Date(until))}\n      ${remaining(until)} left" +
                scheduleSummary()
            strictStatus.setTextColor(getColor(R.color.warn))
            btnStrict.setText(R.string.strict_extend)
        } else {
            strictStatus.setText(R.string.strict_off)
            strictStatus.setTextColor(getColor(R.color.text_dim))
            btnStrict.setText(R.string.strict_start)
        }

        renderSites(lock)
        binding = false
    }

    /** Schedule as a short line so it is still visible in strict mode. */
    private fun scheduleSummary(): String {
        if (!BlockConfig.isScheduleEnabled(this)) return "\n      Blocking around the clock"
        val days = BlockConfig.getDays(this)
        val names = dayOrder.filter { (day, _) -> days and (1 shl (day - 1)) != 0 }.map { it.second }
        return "\n      Schedule: ${names.joinToString(", ")}  " +
            "${fmt(BlockConfig.getStart(this))}–${fmt(BlockConfig.getEnd(this))}"
    }

    private fun remaining(until: Long): String {
        val min = ((until - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        val d = min / (60 * 24)
        val h = (min / 60) % 24
        val m = min % 60
        return buildList {
            if (d > 0) add("$d ${if (d == 1L) "day" else "days"}")
            if (h > 0) add("$h h")
            if (m > 0 && d == 0L) add("$m min")
        }.joinToString(" ")
    }

    // ---------- Block list ----------

    private fun renderSites(lock: Boolean) {
        siteList.removeAllViews()
        val sites = BlockConfig.getSites(this)
        sitesToggleText.setText(R.string.sites_header)
        sitesChevron.text = if (listExpanded) "▴" else "▾"
        siteList.visibility = if (listExpanded) View.VISIBLE else View.GONE
        if (!listExpanded) return
        if (sites.isEmpty()) {
            siteList.addView(TextView(this).apply {
                setText(R.string.no_sites)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
                setTextColor(getColor(R.color.text_hint))
            })
            return
        }
        for (site in sites) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_chip)
                setPadding(dp(14), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            // small colored dot
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.accent))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) }
            })
            row.addView(TextView(this).apply {
                text = site
                textSize = 15f
                setTextColor(getColor(R.color.text))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (lock) "🔒" else "✕"
                textSize = 16f
                setTextColor(getColor(R.color.text_dim))
                minWidth = dp(44)
                minimumWidth = dp(44)
                isEnabled = !lock
                setOnClickListener { removeSite(site) }
            })
            siteList.addView(row)
        }
    }

    /** Adds all entered sites (separated by new lines, commas, semicolons or spaces). */
    private fun addSites() {
        val entries = input.text.toString()
            .split(Regex("[\\s,;]+"))
            .map { BlockConfig.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (entries.isEmpty()) return

        val sites = BlockConfig.getSites(this).toMutableList()
        var added = 0
        for (e in entries) {
            if (sites.none { it.equals(e, true) }) { sites.add(e); added++ }
        }
        BlockConfig.setSites(this, sites)
        input.setText("")
        val skipped = entries.size - added
        val msg = when {
            added == 0 -> "Already on the list"
            skipped > 0 -> "$added added ($skipped already on the list)"
            added == 1 -> "1 site added"
            else -> "$added sites added"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun removeSite(site: String) {
        if (isLocked()) { denied(); return }
        AlertDialog.Builder(this)
            .setMessage("Remove \"$site\" from the block list?")
            .setPositiveButton("Remove") { _, _ ->
                BlockConfig.setSites(this, BlockConfig.getSites(this).filter { it != site })
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Strict mode ----------

    /** Pick the date in a calendar first, then the time. */
    private fun pickStrictEnd() {
        val now = System.currentTimeMillis()
        val current = BlockConfig.getStrictUntil(this)
        val minimum = if (current > now) current else now
        val proposal = Calendar.getInstance().apply {
            timeInMillis = minimum
            add(Calendar.HOUR_OF_DAY, 1)
        }

        val dp = DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
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
        dp.setTitle("Strict mode until which day?")
        dp.show()
    }

    private fun confirmStrict(end: Long) {
        val now = System.currentTimeMillis()
        val current = BlockConfig.getStrictUntil(this)
        if (end <= now) {
            Toast.makeText(this, "That time is in the past.", Toast.LENGTH_LONG).show()
            return
        }
        if (current > now && end <= current) {
            Toast.makeText(this, "Strict mode can only be extended, not shortened.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.strict_header)
            .setMessage(
                "Strict mode until ${dateFmt.format(Date(end))}\n(${remaining(end)})\n\n" +
                    "Until then you can't remove sites, change the schedule " +
                    "or turn blocking off. This cannot be undone."
            )
            .setPositiveButton("Start") { _, _ ->
                BlockConfig.setEnabled(this, true)
                BlockConfig.setStrictUntil(this, end)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Helpers ----------

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
