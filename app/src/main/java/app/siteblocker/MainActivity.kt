package app.siteblocker

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import app.siteblocker.adblock.AdBlockService
import app.siteblocker.ui.AdBlockPage
import app.siteblocker.ui.BlockerPage
import app.siteblocker.ui.dp
import app.siteblocker.websites.BlockConfig
import java.util.Locale
import kotlin.math.abs

/**
 * The app's only screen: two pages (Blocker, Ad blocker) with a tab bar at the
 * bottom. The pages live in the ui package; this class only holds what they share
 * (tabs, fingerprint check, messages, results of system dialogs).
 *
 * Stays in the app.siteblocker package: launcher shortcuts point to this name.
 */
class MainActivity : Activity() {

    private lateinit var blocker: BlockerPage
    private lateinit var ads: AdBlockPage
    private lateinit var pages: List<View>
    private lateinit var tabPills: List<View>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>
    private var tab = 0
    private var onAuthResult: (() -> Unit)? = null

    val handler = Handler(Looper.getMainLooper())

    // Ad numbers every 5 s, everything every 30 s (Alpha mode countdown, schedule)
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (ticks++ % 6 == 0) refresh() else ads.refresh()
            handler.postDelayed(this, 5_000)
        }
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

        blocker = BlockerPage(this)
        ads = AdBlockPage(this)
        pages = listOf(findViewById(R.id.page_blocker), findViewById(R.id.page_ads))
        tabPills = listOf(findViewById(R.id.tab_blocker_pill), findViewById(R.id.tab_ads_pill))
        tabIcons = listOf(findViewById(R.id.tab_blocker_icon), findViewById(R.id.tab_ads_icon))
        tabLabels = listOf(findViewById(R.id.tab_blocker_label), findViewById(R.id.tab_ads_label))
        findViewById<View>(R.id.tab_blocker).setOnClickListener { showTab(0, true) }
        findViewById<View>(R.id.tab_ads).setOnClickListener { showTab(1, true) }

        showTab(savedInstanceState?.getInt("tab", 0) ?: 0, false)

        // Opened from the ad blocker notification
        if (savedInstanceState == null) handleIntent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        if (intent?.action != AdBlockService.ACTION_ENABLE) return
        setIntent(Intent(this, MainActivity::class.java))
        showTab(1, false)
        ads.enable()
    }

    override fun onResume() {
        super.onResume()
        ads.restartIfNeeded()
        ticks = 0
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        blocker.lock()
    }

    fun refresh() {
        blocker.refresh()
        ads.refresh()
    }

    // ---------- Tabs ----------

    private fun showTab(index: Int, animate: Boolean) {
        val next = index.coerceIn(0, pages.size - 1)
        val dir = if (next > tab) 1 else -1
        val changed = next != tab
        tab = next
        pages.forEachIndexed { i, p -> if (i != next) p.visibility = View.GONE }
        val page = pages[next]
        page.visibility = View.VISIBLE
        if (animate && changed) {
            page.alpha = 0f
            page.translationX = dir * dp(48).toFloat()
            page.animate().alpha(1f).translationX(0f).setDuration(220).start()
            // Keyboard of the website field doesn't belong on the other page
            currentFocus?.let {
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
                it.clearFocus()
            }
        }
        for (i in pages.indices) {
            val on = i == next
            tabPills[i].background = if (on) getDrawable(R.drawable.bg_nav_pill) else null
            tabIcons[i].imageTintList = getColorStateList(if (on) R.color.accent2 else R.color.text_hint)
            tabLabels[i].setTextColor(getColor(if (on) R.color.text else R.color.text_hint))
        }
    }

    /** Swipe left / right switches the page. */
    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(80) || abs(dx) < abs(dy) * 2 || abs(velocityX) < dp(350)) return false
                showTab(if (dx < 0) tab + 1 else tab - 1, true)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- Fingerprint / PIN ----------

    /**
     * Asks for fingerprint, face or the screen lock (PIN, pattern, password),
     * like Google Pay does. Without any screen lock it simply continues.
     */
    fun authenticate(onSuccess: () -> Unit) {
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
            ads.onVpnResult(resultCode == RESULT_OK)
            return
        }
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) onAuthResult?.invoke()
        onAuthResult = null
    }

    // ---------- Shared helpers ----------

    fun isLocked() = BlockConfig.isStrictLocked(this)

    /** The Alpha mode message: only shown when a locked control (or the Alpha status) is tapped. */
    fun denied() {
        val until = BlockConfig.getStrictUntil(this)
        toast(getString(R.string.locked_hint) + "\n(${remaining(until)} left)", long = true, key = "denied")
    }

    /** "2 days 3 h", "5 h 10 min" until [until]. */
    fun remaining(until: Long): String {
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

    private var shownToast: Toast? = null
    private var shownToastKey: String? = null
    private var shownToastUntil = 0L

    /**
     * Shows a short message. Tapping again while the same message is still on screen
     * does nothing (Android would otherwise queue it again and again); a different
     * message replaces the current one.
     */
    fun toast(msg: String, long: Boolean = false, key: String = msg) {
        val now = android.os.SystemClock.uptimeMillis()
        if (key == shownToastKey && now < shownToastUntil) return
        shownToast?.cancel()
        shownToast = Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
        shownToastKey = key
        // Android shows LENGTH_LONG for 3.5 s and LENGTH_SHORT for 2 s
        shownToastUntil = now + if (long) 3600 else 2100
    }

    companion object {
        const val REQ_AUTH = 1
        const val REQ_VPN = 2
        const val REQ_NOTIF = 3
    }
}
