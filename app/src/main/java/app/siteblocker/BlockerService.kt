package app.siteblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView

/**
 * Accessibility service: reads the address bar of known browsers and covers
 * the screen with a block overlay as soon as a blocked site is opened.
 *
 * The overlay is drawn directly by the service (TYPE_ACCESSIBILITY_OVERLAY),
 * so it appears instantly and can't be swallowed by activity-start limits.
 */
class BlockerService : AccessibilityService() {

    private var overlay: View? = null
    /**
     * Set while the block screen is showing after a tab was closed. The screen
     * then stays until "Go to home screen" is tapped or another app is opened,
     * even though the browser no longer shows the blocked address.
     */
    private var sticky = false
    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    /** Known address bar view IDs per browser. */
    private val urlBarIds = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "org.chromium.chrome" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.kiwibrowser.browser" to listOf("url_bar"),
        "com.ecosia.android" to listOf("url_bar"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view", "display_url"),
        "org.mozilla.klar" to listOf("mozac_browser_toolbar_url_view", "display_url"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "custom_tab_toolbar_url_bar_text"),
        "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.gx" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.mi.globalbrowser" to listOf("url"),
        "com.android.browser" to listOf("url"),
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg !in urlBarIds) {
            // Another app came to the front (e.g. home screen) -> remove the overlay
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")
            ) hideOverlay()
            return
        }

        if (sticky) return
        if (!BlockConfig.isActiveNow(this)) { hideOverlay(); return }
        val sites = BlockConfig.getSites(this)
        if (sites.isEmpty()) { hideOverlay(); return }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val url = readUrl(root, pkg) ?: return
        val rule = BlockConfig.findMatch(url, sites)
        if (rule == null) { hideOverlay(); return }
        val isNew = overlay == null
        showOverlay(url, rule)
        if (isNew && overlay != null) {
            sticky = true
            closeTab(root, pkg)
        }
    }

    // ---------- Closing the tab ----------

    /** Buttons that open the tab overview (long press opens a "Close tab" menu). */
    private val tabButtonIds = listOf(
        "tab_switcher_button",                // Chrome, Brave, Vivaldi, Kiwi, Ecosia
        "mozac_browser_toolbar_tab_counter",  // Firefox
        "counter_root", "tab_counter",        // Firefox (older/newer versions)
        "tab_button", "tabs_button",          // Samsung Internet, others
    )

    /** Menu entry texts in English and German. */
    private val closeTabTexts = listOf("close tab", "tab schließen", "tab schliessen", "close this tab")

    /**
     * Closes the browser tab that shows the blocked site: long press on the
     * tab button and tap "Close tab" in the menu that opens. If that doesn't
     * work in this browser, go back instead so the blocked page is left.
     */
    private fun closeTab(root: AccessibilityNodeInfo, pkg: String) {
        val tabButton = tabButtonIds.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()
        }
        if (tabButton == null || !tabButton.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        handler.postDelayed({
            val item = findCloseItem()
            if (item != null) {
                clickNodeOrParent(item)
            } else {
                // Menu didn't contain "Close tab": close it and leave the page
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 250)
            }
        }, 600)
    }

    private fun findCloseItem(): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
        for (r in roots) {
            if (r.packageName?.toString() == packageName) continue
            for (text in closeTabTexts) {
                r.findAccessibilityNodeInfosByText(text)
                    ?.firstOrNull { it.text?.toString()?.trim()?.equals(text, true) == true }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            depth++
        }
    }

    // ---------- Overlay ----------

    private fun showOverlay(url: String, rule: String) {
        val view = overlay ?: createOverlay() ?: return
        view.findViewById<TextView>(R.id.blocked_url).text = BlockConfig.normalize(url)
        view.findViewById<TextView>(R.id.blocked_rule).text = getString(R.string.rule_label, rule)
    }

    private fun createOverlay(): View? {
        val view = LayoutInflater.from(ContextThemeWrapper(this, R.style.BlockedTheme))
            .inflate(R.layout.activity_blocked, null)
        view.findViewById<Button>(R.id.btn_home).setOnClickListener { leave() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        return try {
            wm.addView(view, lp)
            overlay = view
            view
        } catch (e: Exception) {
            null
        }
    }

    private fun hideOverlay() {
        sticky = false
        val view = overlay ?: return
        overlay = null
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    /** "Go to home screen" (the tab has already been closed). */
    private fun leave() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ hideOverlay() }, 250)
    }

    // ---------- Reading the URL ----------

    private fun readUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        for (id in urlBarIds[pkg].orEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") ?: continue
            for (n in nodes) {
                // Don't block while you are typing in the address bar
                if (n.isFocused) return null
                val t = n.text?.toString()
                if (!t.isNullOrBlank()) return t
            }
        }
        return searchTree(root, 0, intArrayOf(0))
    }

    /** Fallback: look for a field whose ID looks like an address bar. */
    private fun searchTree(node: AccessibilityNodeInfo, depth: Int, count: IntArray): String? {
        if (depth > 30 || ++count[0] > 500) return null
        val id = node.viewIdResourceName?.lowercase()
        if (id != null && (id.contains("url") || id.contains("omnibox") ||
                    id.contains("address") || id.contains("location_bar"))
        ) {
            if (node.isFocused) return null
            val t = node.text?.toString()
            if (!t.isNullOrBlank() && (t.contains('.') || t.contains('/'))) return t
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchTree(child, depth + 1, count)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
