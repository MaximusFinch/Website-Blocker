package app.siteblocker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
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
    private lateinit var statSites: TextView
    private lateinit var statApps: TextView
    private lateinit var statAds: TextView
    private lateinit var swAdblock: Switch
    private lateinit var adblockStatus: TextView
    private lateinit var btnAdblockUpdate: Button
    private lateinit var headerIcon: View
    private lateinit var cardEnabled: View
    private lateinit var cardSchedule: View
    private lateinit var alwaysToggleText: TextView
    private lateinit var alwaysChevron: TextView
    private lateinit var alwaysSub: TextView
    private lateinit var alwaysList: LinearLayout
    private lateinit var flexToggleText: TextView
    private lateinit var flexChevron: TextView
    private lateinit var flexSub: TextView
    private lateinit var flexList: LinearLayout
    private var alwaysExpanded = false
    private var flexExpanded = false
    /** List the sites being checked right now will be added to. */
    private var pendingAlways = true
    /** True after a successful fingerprint / PIN check, until the app goes to the background. */
    private var listUnlocked = false
    /** Result of the last existence check per site (green / red dot). */
    private val existence = mutableMapOf<String, SiteChecker.Exists>()
    private var checkingSites = false
    private var onAuthResult: (() -> Unit)? = null
    private lateinit var btnAddAlways: Button
    private lateinit var btnAddFlex: Button
    private lateinit var serviceCard: View
    private lateinit var btnService: Button
    private lateinit var alphaStatus: TextView
    private lateinit var input: EditText
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

    // Refresh every 30 s so the Alpha mode countdown stays current
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
        statSites = findViewById(R.id.stat_sites)
        statApps = findViewById(R.id.stat_apps)
        statAds = findViewById(R.id.stat_ads)
        swAdblock = findViewById(R.id.sw_adblock)
        adblockStatus = findViewById(R.id.adblock_status)
        btnAdblockUpdate = findViewById(R.id.btn_adblock_update)
        headerIcon = findViewById(R.id.header_icon)
        cardEnabled = findViewById(R.id.card_enabled)
        cardSchedule = findViewById(R.id.card_schedule)
        alwaysToggleText = findViewById(R.id.always_toggle_text)
        alwaysChevron = findViewById(R.id.always_chevron)
        alwaysSub = findViewById(R.id.always_sub)
        alwaysList = findViewById(R.id.always_list)
        flexToggleText = findViewById(R.id.flex_toggle_text)
        flexChevron = findViewById(R.id.flex_chevron)
        flexSub = findViewById(R.id.flex_sub)
        flexList = findViewById(R.id.flex_list)
        findViewById<View>(R.id.always_toggle).setOnClickListener { toggleList(true) }
        findViewById<View>(R.id.flex_toggle).setOnClickListener { toggleList(false) }
        serviceCard = findViewById(R.id.service_card)
        btnService = findViewById(R.id.btn_service)
        alphaStatus = findViewById(R.id.alpha_status)
        input = findViewById(R.id.input_site)
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

        btnAddAlways = findViewById(R.id.btn_add_always)
        btnAddAlways.setOnClickListener { addSites(true) }
        btnAddFlex = findViewById(R.id.btn_add_flex)
        btnAddFlex.setOnClickListener { addSites(false) }
        findViewById<Button>(R.id.btn_add_app).setOnClickListener { pickApp() }

        // Paused-notice: "Turn on" closes it (it slides away upwards)
        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            BlockConfig.setEnabled(this, true); refresh()
        }

        // Tapping the status pauses blocking (or turns it back on)
        status.setOnClickListener {
            when {
                !BlockConfig.isEnabled(this) -> { BlockConfig.setEnabled(this, true); refresh() }
                isLocked() -> denied()
                else -> AlertDialog.Builder(this)
                    .setTitle("Pause blocking?")
                    .setMessage("Nothing will be blocked until you turn it back on.")
                    .setPositiveButton("Pause") { _, _ -> BlockConfig.setEnabled(this, false); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        swSchedule.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            BlockConfig.setScheduleEnabled(this, checked); refresh()
        }

        btnStart.setOnClickListener {
            pickTime(BlockConfig.getStart(this)) { BlockConfig.setStart(this, it); refresh() }
        }
        btnEnd.setOnClickListener {
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

        // Ad blocker: can always be switched, also in Alpha mode
        swAdblock.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            if (on) enableAdBlock()
            else {
                AdBlockService.setEnabled(this, false)
                AdBlockService.setWaiting(this, false)   // turned off on purpose: no reminder
                try { AdBlockService.stop(this) } catch (_: Exception) {}
                refresh()
            }
        }
        btnAdblockUpdate.setOnClickListener { updateAdList() }
        findViewById<View>(R.id.stat_ads).setOnClickListener {
            findViewById<android.widget.ScrollView>(R.id.scroll)
                .smoothScrollTo(0, findViewById<View>(R.id.card_adblock).top)
        }

        // Alpha mode status: explains the lock while active, otherwise jumps to its card
        alphaStatus.setOnClickListener {
            if (isLocked()) denied()
            else findViewById<android.widget.ScrollView>(R.id.scroll)
                .smoothScrollTo(0, findViewById<View>(R.id.card_alpha).top)
        }

        // Opened from the "Ad blocker turned off" notification
        if (savedInstanceState == null) handleIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        if (intent?.action != AdBlockService.ACTION_ENABLE) return
        setIntent(Intent(this, MainActivity::class.java))
        enableAdBlock()
    }

    override fun onResume() {
        super.onResume()
        // Ad blocker should be on but isn't running (e.g. Android stopped it): start it again
        if (AdBlockService.isEnabled(this) && !AdBlockService.running) {
            if (VpnService.prepare(this) == null) {
                try { AdBlockService.start(this) } catch (_: Exception) {}
            } else {
                AdBlockService.setEnabled(this, false)   // another VPN app has the slot now
                AdBlockService.setWaiting(this, true)
            }
        }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks the list again
        listUnlocked = false
        alwaysExpanded = false
        flexExpanded = false
    }

    // ---------- Fingerprint / PIN ----------

    /**
     * Asks for fingerprint, face or the screen lock (PIN, pattern, password),
     * like Google Pay does. Without any screen lock the list simply opens.
     */
    private fun authenticate(onSuccess: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) { onSuccess(); return }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onSuccess()
            }
        }
        when {
            Build.VERSION.SDK_INT >= 30 -> BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.auth_title))
                .setSubtitle(getString(R.string.auth_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
                .authenticate(CancellationSignal(), mainExecutor, callback)

            Build.VERSION.SDK_INT == 29 -> {
                @Suppress("DEPRECATION")
                val prompt = BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.auth_title))
                    .setSubtitle(getString(R.string.auth_subtitle))
                    .setDeviceCredentialAllowed(true)
                    .build()
                prompt.authenticate(CancellationSignal(), mainExecutor, callback)
            }

            else -> {
                // Older Android: the system screen lock prompt
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(
                    getString(R.string.auth_title), getString(R.string.auth_subtitle)
                )
                if (intent == null) { onSuccess(); return }
                onAuthResult = onSuccess
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_AUTH)
            }
        }
    }

    @Deprecated("Screen lock on Android 9 and older, VPN permission for the ad blocker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) onVpnAllowed()
            else { toast("The ad blocker needs the VPN permission to work.", long = true); refresh() }
            return
        }
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) onAuthResult?.invoke()
        onAuthResult = null
    }

    // ---------- Ad blocker ----------

    /** Asks Android for the VPN permission (only the first time), then starts the ad blocker. */
    private fun enableAdBlock() {
        val consent = try { VpnService.prepare(this) } catch (_: Exception) { null }
        if (consent != null) {
            refresh()   // switch stays off until the permission is given
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN)
            return
        }
        onVpnAllowed()
    }

    private fun onVpnAllowed() {
        AdBlockService.setEnabled(this, true)
        try { AdBlockService.start(this) } catch (_: Exception) {}
        // For the "turned off by another VPN" notification
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        refresh()
        // The list is downloaded the first time: show the result once it's there
        if (AdBlockList.count(this) == 0) {
            for (delay in listOf(3_000L, 8_000L, 15_000L, 30_000L)) handler.postDelayed({ refresh() }, delay)
        }
    }

    private fun updateAdList() {
        btnAdblockUpdate.isEnabled = false
        btnAdblockUpdate.text = "Downloading…"
        Thread {
            var unsafe: String? = null
            val n = try { AdBlockList.download(this) }
            catch (e: AdBlockList.UnsafeListException) { unsafe = e.domain; -1 }
            catch (_: Exception) { -1 }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnAdblockUpdate.isEnabled = true
                btnAdblockUpdate.setText(R.string.adblock_update)
                toast(
                    if (unsafe != null) "The new list would block $unsafe, so it was ignored. The old list stays active."
                    else if (n < 0) "Couldn't download the list – check your internet connection."
                    else "List updated: ${num(n)} ad & tracker domains",
                    long = true,
                )
                refresh()
            }
        }.start()
    }

    private fun num(n: Int) = String.format(Locale.ENGLISH, "%,d", n)

    /** "today", "yesterday", "3 days ago". */
    private fun ago(time: Long): String {
        val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            else -> "$days days ago"
        }
    }

    private fun isLocked() = BlockConfig.isStrictLocked(this)

    /** The Alpha mode message: only shown when a locked control (or the Alpha status) is tapped. */
    private fun denied() {
        val until = BlockConfig.getStrictUntil(this)
        toast(getString(R.string.locked_hint) + "\n(${remaining(until)} left)", long = true, key = "denied")
    }

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
        // Green only while sites are really being blocked right now, otherwise red.
        // The line below the status says why the protection is not active.
        val reason = when {
            !serviceOn -> R.string.sub_service_off
            !BlockConfig.isEnabled(this) -> R.string.sub_disabled
            !BlockConfig.hasEntries(this) -> R.string.sub_no_sites
            BlockConfig.activeSites(this).isEmpty() && BlockConfig.activeApps(this).isEmpty() -> R.string.sub_schedule
            else -> null
        }
        val protectedNow = reason == null
        val statusColor = getColor(if (protectedNow) R.color.success else R.color.danger)
        // Dark glass pill on the orange header: only the dot and the outline carry
        // the green/red state. The pause bars show that it can be tapped to pause.
        val canPause = BlockConfig.isEnabled(this) && !isLocked()
        val label = getString(if (protectedNow) R.string.status_ok else R.string.status_off)
        status.text = SpannableStringBuilder(label).apply {
            setSpan(ForegroundColorSpan(statusColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (canPause) {
                val start = length
                append("   ❚❚")
                setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.75f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        status.setTextColor(0xFFFFFFFF.toInt())
        headerSub.setText(reason ?: R.string.sub_active)
        status.background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(0x59000000)                                           // black, 35 %
            setStroke(dp(1), (statusColor and 0x00FFFFFF) or 0xFF000000.toInt()) // state color
        }
        headerIcon.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0x40000000)                                           // black, 25 %
            setStroke(dp(1), 0x33FFFFFF)
        }
        serviceCard.visibility = if (serviceOn) View.GONE else View.VISIBLE

        val lock = isLocked()
        // Second pill: Alpha mode on (orange dot and outline) or off (grey)
        val alphaColor = if (lock) getColor(R.color.accent2) else 0xFF8A8A8A.toInt()
        alphaStatus.text = SpannableStringBuilder(getString(if (lock) R.string.alpha_on else R.string.alpha_off)).apply {
            setSpan(ForegroundColorSpan(alphaColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        alphaStatus.alpha = if (lock) 1f else 0.75f
        alphaStatus.background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(0x59000000)
            setStroke(dp(1), (alphaColor and 0x00FFFFFF) or 0xFF000000.toInt())
        }
        // Only shown while blocking is paused (never in Alpha mode)
        cardEnabled.visibility = if (!lock && !BlockConfig.isEnabled(this)) View.VISIBLE else View.GONE

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
            strictStatus.text = "🔒  Until ${dateFmt.format(Date(until))}\n      ${remaining(until)} left"
            strictStatus.setTextColor(getColor(R.color.warn))
            btnStrict.setText(R.string.strict_extend)
        } else {
            strictStatus.setText(R.string.strict_off)
            strictStatus.setTextColor(getColor(R.color.text_dim))
            btnStrict.setText(R.string.strict_start)
        }

        renderLists(lock)

        // Header numbers
        statSites.text = (BlockConfig.getSites(this, true).size + BlockConfig.getSites(this, false).size).toString()
        statApps.text = (BlockConfig.getApps(this, true).size + BlockConfig.getApps(this, false).size).toString()
        val adOn = AdBlockService.isEnabled(this)
        statAds.text = if (adOn) num(AdBlockService.blockedToday(this)) else "Off"

        // Ad blocker card
        swAdblock.isChecked = adOn
        val domains = AdBlockList.count(this)
        adblockStatus.text = when {
            !adOn -> "●  Off"
            domains == 0 -> "●  On – downloading the filter list…"
            else -> "●  On · ${num(domains)} ad & tracker domains\n" +
                "      ${num(AdBlockService.blockedToday(this))} blocked today · list from ${ago(AdBlockList.lastUpdate(this))}"
        }
        adblockStatus.setTextColor(getColor(if (adOn) R.color.success else R.color.text_dim))
        btnAdblockUpdate.visibility = if (adOn) View.VISIBLE else View.GONE

        binding = false
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

    // ---------- Block lists ----------
    //
    // Two lists with websites and apps:
    //  - Always:      blocked around the clock; in Alpha mode nothing can be removed or moved out
    //  - On schedule: blocked at the scheduled times; can always be changed

    /** One entry on a block list: a website or an app (package name). */
    private data class Entry(val value: String, val isApp: Boolean)

    private data class AppItem(val pkg: String, val label: String, val icon: Drawable?)

    private fun entries(always: Boolean) =
        BlockConfig.getSites(this, always).map { Entry(it, false) } +
            BlockConfig.getApps(this, always).map { Entry(it, true) }

    /** App name, or null when the app is not installed (any more). */
    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { null }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
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
            refresh()
            checkExistence()
        }
        when {
            expanded -> { if (always) alwaysExpanded = false else flexExpanded = false; refresh() }
            listUnlocked -> open()
            else -> authenticate { listUnlocked = true; open() }
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
        val count = BlockConfig.getSites(this, always).size + BlockConfig.getApps(this, always).size
        val entries = if (count == 1) "1 entry" else "$count entries"
        (if (always) alwaysSub else flexSub).text = when {
            always -> "Around the clock · $entries"
            !BlockConfig.isScheduleEnabled(this) -> "Schedule off – blocked all day · $entries"
            BlockConfig.isActiveNow(this) -> "Blocked right now · $entries"
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

        box.addView(TextView(this).apply {
            setText(if (always) R.string.list_always_desc else R.string.list_flex_desc)
            textSize = 12f
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(4), dp(8), dp(4), 0)
        })
        val items = entries(always)
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                setText(R.string.no_sites)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
                setTextColor(getColor(R.color.text_hint))
            })
            return
        }
        for (e in items) {
            val dead = isDead(e)
            // Always list in Alpha mode: can't be removed or moved (except entries without effect)
            val locked = always && lock && !dead
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_chip)
                setPadding(dp(14), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            val icon = if (e.isApp) appIcon(e.value) else null
            if (icon != null) {
                // App: its icon
                row.addView(ImageView(this).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
                })
            } else {
                // Website: green = exists, red = doesn't exist / app not installed, grey = not checked yet
                val color = when {
                    dead -> getColor(R.color.danger)
                    !e.isApp && existence[e.value] == SiteChecker.Exists.YES -> getColor(R.color.success)
                    else -> getColor(R.color.text_hint)
                }
                row.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) }
                })
            }
            row.addView(TextView(this).apply {
                text = if (e.isApp && dead) "${e.value} (not installed)" else nameOf(e)
                textSize = 15f
                setTextColor(getColor(R.color.text))
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
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 16f
            setTextColor(getColor(R.color.text_dim))
            minWidth = dp(40)
            minimumWidth = dp(40)
            setPadding(0, 0, 0, 0)
            alpha = if (dimmed) 0.5f else 1f
            setOnClickListener { onClick() }
        }

    private fun addTo(e: Entry, always: Boolean) {
        if (e.isApp) {
            val apps = BlockConfig.getApps(this, always)
            if (e.value !in apps) BlockConfig.setApps(this, apps + e.value, always)
        } else {
            val sites = BlockConfig.getSites(this, always)
            if (sites.none { it.equals(e.value, true) }) BlockConfig.setSites(this, sites + e.value, always)
        }
    }

    private fun removeFrom(e: Entry, always: Boolean) {
        if (e.isApp) BlockConfig.setApps(this, BlockConfig.getApps(this, always).filter { it != e.value }, always)
        else BlockConfig.setSites(this, BlockConfig.getSites(this, always).filter { it != e.value }, always)
    }

    /** Always → On schedule is not possible in Alpha mode; the other way round always is. */
    private fun moveEntry(e: Entry, fromAlways: Boolean) {
        if (fromAlways && isLocked() && !isDead(e)) { denied(); return }
        val name = nameOf(e)
        val dialog = AlertDialog.Builder(this)
        if (fromAlways) {
            dialog.setTitle("Move to the On schedule list?")
                .setMessage("\"$name\" will only be blocked at the scheduled times.")
        } else {
            dialog.setTitle("Move to the Always list?")
                .setMessage(
                    "\"$name\" will be blocked around the clock." +
                        if (isLocked()) "\n\nAlpha mode is on: it can't be moved back until Alpha mode ends." else ""
                )
        }
        dialog.setPositiveButton("Move") { _, _ ->
            removeFrom(e, fromAlways)
            addTo(e, !fromAlways)
            refresh()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun removeEntry(e: Entry, always: Boolean) {
        val name = nameOf(e)
        if (always && isLocked()) {
            // In Alpha mode only entries without effect can be removed from the Always list
            if (e.isApp) {
                if (appLabel(e.value) != null) { denied(); return }
                confirmRemove(e, true, "\"${e.value}\" is no longer installed.\n\nRemove it from the block list?")
                return
            }
            if (existence[e.value] != SiteChecker.Exists.NO) { denied(); return }
            // Check again online first
            Thread {
                val dead = SiteChecker.exists(listOf(e.value))[e.value] == SiteChecker.Exists.NO
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (!dead) {
                        existence.remove(e.value); refresh(); denied()
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
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ -> removeFrom(e, always); refresh() }
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
            toast("Enter a website first, e.g. youtube.com")
            return
        }
        // Keywords are not supported: only real websites like youtube.com
        val notSites = entries.filterNot { BlockConfig.isWebsite(it) }
        if (notSites.isNotEmpty()) {
            toast("Not a website: ${notSites.joinToString(", ")} – enter e.g. ${notSites.first()}.com")
            return
        }
        val existing = BlockConfig.getSites(this, always)
        val newOnes = entries.filter { e -> existing.none { it.equals(e, true) } }
        if (newOnes.isEmpty()) { toast("Already on this list"); return }

        pendingAlways = always
        val btn = if (always) btnAddAlways else btnAddFlex
        btnAddAlways.isEnabled = false
        btnAddFlex.isEnabled = false
        btn.text = "Checking…"
        Thread {
            val report = SiteChecker.check(newOnes)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnAddAlways.isEnabled = true
                btnAddFlex.isEnabled = true
                btnAddAlways.setText(R.string.add_always)
                btnAddFlex.setText(R.string.add_flex)
                input.setText("")
                if (report.offline) toast("No internet – added without checking")
                reviewNext(ArrayDeque(report.results), mutableListOf())
            }
        }.start()
    }

    /** Goes through the checked entries one by one and asks about the doubtful ones. */
    private fun reviewNext(queue: ArrayDeque<SiteChecker.Result>, accepted: MutableList<String>) {
        val r = queue.removeFirstOrNull() ?: return saveAdded(accepted)
        if (r.status == SiteChecker.Status.OK) { accepted += r.entry; reviewNext(queue, accepted); return }

        val next = { site: String? -> site?.let { accepted += it }; reviewNext(queue, accepted) }
        val dialog = AlertDialog.Builder(this).setCancelable(false)
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
        val target = BlockConfig.getSites(this, always).toMutableList()
        val other = BlockConfig.getSites(this, !always).toMutableList()
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
        BlockConfig.setSites(this, target, always)
        BlockConfig.setSites(this, other, !always)
        if (alwaysExpanded || flexExpanded) checkExistence()
        toast(
            when {
                onAlways > 0 && added == 0 -> "Already on the Always list"
                added == 0 -> "Already on this list"
                always -> if (added == 1) "Blocked always" else "$added sites blocked always"
                else -> if (added == 1) "Blocked on schedule" else "$added sites blocked on schedule"
            }
        )
        refresh()
    }

    // ---------- Adding apps ----------

    /** Shows the installed apps; the chosen one goes to the Always or the On schedule list. */
    private fun pickApp() {
        toast("Loading apps…")
        Thread {
            val blocked = (BlockConfig.getApps(this, true) + BlockConfig.getApps(this, false)).toSet()
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .filter { it !in blocked && BlockConfig.canBlockApp(this, it) }
                .mapNotNull { pkg -> appLabel(pkg)?.let { AppItem(pkg, it, appIcon(pkg)) } }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (apps.isEmpty()) { toast("No apps to block"); return@runOnUiThread }
                val adapter = object : ArrayAdapter<AppItem>(this, 0, apps) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val item = getItem(position)!!
                        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(22), dp(10), dp(22), dp(10))
                            addView(ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
                            })
                            addView(TextView(context).apply {
                                textSize = 16f
                                setTextColor(getColor(R.color.text))
                            })
                        }
                        (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
                        (row.getChildAt(1) as TextView).text = item.label
                        return row
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Which app?")
                    .setAdapter(adapter) { _, which -> chooseAppList(apps[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun chooseAppList(app: AppItem) {
        AlertDialog.Builder(this)
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
        toast("${app.label} " + if (always) "blocked always" else "blocked on schedule")
        refresh()
    }

    private var shownToast: Toast? = null
    private var shownToastKey: String? = null
    private var shownToastUntil = 0L

    /**
     * Shows a short message. Tapping again while the same message is still on screen
     * does nothing (Android would otherwise queue it again and again); a different
     * message replaces the current one.
     */
    private fun toast(msg: String, long: Boolean = false, key: String = msg) {
        val now = android.os.SystemClock.uptimeMillis()
        if (key == shownToastKey && now < shownToastUntil) return
        shownToast?.cancel()
        shownToast = Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
        shownToastKey = key
        // Android shows LENGTH_LONG for 3.5 s and LENGTH_SHORT for 2 s
        shownToastUntil = now + if (long) 3600 else 2100
    }

    /** Checks in the background whether the sites on both lists still exist. */
    private fun checkExistence() {
        if (checkingSites) return
        val sites = (BlockConfig.getSites(this, true) + BlockConfig.getSites(this, false)).distinct()
        if (sites.isEmpty()) return
        checkingSites = true
        Thread {
            val result = SiteChecker.exists(sites)
            runOnUiThread {
                checkingSites = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                existence.clear()
                existence.putAll(result)
                refresh()
            }
        }.start()
    }

    // ---------- Alpha mode ----------

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
        dp.setTitle("Alpha mode until which day?")
        dp.show()
    }

    private fun confirmStrict(end: Long) {
        val now = System.currentTimeMillis()
        val current = BlockConfig.getStrictUntil(this)
        if (end <= now) {
            toast("That time is in the past.", long = true)
            return
        }
        if (current > now && end <= current) {
            toast("Alpha mode can only be extended, not shortened.", long = true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.strict_header)
            .setMessage(
                "Alpha mode until ${dateFmt.format(Date(end))}\n(${remaining(end)})\n\n" +
                    "Until then you can't remove or move anything on the Always list, turn blocking off, " +
                    "or turn off or uninstall WebBlock in the settings. The On schedule list and the " +
                    "schedule stay changeable. This cannot be undone."
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

    private companion object {
        const val REQ_AUTH = 1
        const val REQ_VPN = 2
        const val REQ_NOTIF = 3
    }
}
