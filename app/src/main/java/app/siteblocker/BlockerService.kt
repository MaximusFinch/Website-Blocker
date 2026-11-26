package app.siteblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

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
    /** Browser that showed the blocked site; its tab is closed when "Go to home screen" is tapped. */
    private var blockedPkg: String? = null
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

        if (guardSettings(pkg, event.className?.toString())) return
        if (blockApp(pkg)) return

        if (pkg !in urlBarIds) {
            // Another app came to the front (e.g. home screen) -> remove the overlay
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")
            ) hideOverlay()
            return
        }

        if (sticky) return
        val sites = BlockConfig.activeSites(this)
        if (sites.isEmpty()) { hideOverlay(); return }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val url = readUrl(root, pkg) ?: return
        val rule = BlockConfig.findMatch(url, sites)
        if (rule == null) { hideOverlay(); return }
        val isNew = overlay == null
        showOverlay(url, rule)
        if (isNew && overlay != null) {
            // Keep the block screen until the button is tapped; the tab is closed then
            sticky = true
            blockedPkg = pkg
        }
    }

    // ---------- Blocking apps ----------

    /** App whose block screen is showing right now. */
    private var blockedApp: String? = null

    /**
     * Covers a blocked app with the block screen as soon as it is in front.
     * Returns true when the event belongs to a blocked app.
     */
    private fun blockApp(pkg: String): Boolean {
        if (pkg !in BlockConfig.activeApps(this)) return false
        if (overlay != null && blockedApp == pkg) return true
        // Only when the app's own window is in front (not e.g. a toast it shows)
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        if (!BlockConfig.canBlockApp(this, pkg)) return false

        val name = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val list = if (pkg in BlockConfig.getApps(this, true)) "Always list" else "On schedule list"
        val view = overlay ?: createOverlay() ?: return false
        view.findViewById<TextView>(R.id.blocked_title).setText(R.string.blocked_app_title)
        view.findViewById<TextView>(R.id.blocked_url).text = name
        view.findViewById<TextView>(R.id.blocked_rule).text = getString(R.string.rule_label, list)
        setClosingUi(false)
        sticky = true
        blockedPkg = null   // "Go to home screen" just leaves, there is no tab to close
        blockedApp = pkg
        return true
    }

    // ---------- Alpha mode protection ----------

    private var lastGuardToast = 0L

    /** System screens that could turn WebBlock off: settings, app info, uninstall dialogs. */
    private fun isGuardedApp(pkg: String) =
        pkg != "com.android.systemui" && (
            pkg.contains("settings") || pkg.contains("packageinstaller") ||
                pkg.contains("permissioncontroller") || pkg == "com.miui.securitycenter"
            )

    /**
     * While Alpha mode is on, a settings or uninstall screen that shows WebBlock
     * (accessibility switch, app info with force stop / clear data / uninstall,
     * uninstall dialog) is closed right away by going to the home screen.
     * Returns true when it did that.
     */
    private fun guardSettings(pkg: String, className: String?): Boolean {
        if (!isGuardedApp(pkg)) return false
        if (!BlockConfig.isStrictLocked(this)) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val name = getString(R.string.app_name)
        val hits = root.findAccessibilityNodeInfosByText(name)
        if (hits.isNullOrEmpty()) return false
        // Package installer: installing an update is fine, only the uninstall dialog is closed
        if (pkg.contains("packageinstaller") && !isUninstallScreen(root, className)) return false

        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.uptimeMillis()
        if (now - lastGuardToast > 3000) {
            lastGuardToast = now
            Toast.makeText(this, R.string.guard_hint, Toast.LENGTH_LONG).show()
        }
        return true
    }

    /** Words in uninstall dialogs ("Do you want to uninstall this app?", "deinstallieren", …). */
    private val uninstallWords = listOf("uninstall", "deinstall", "désinstall", "desinstal", "disinstall", "verwijder")

    /** Is the package installer showing the uninstall dialog (not the install / update one)? */
    private fun isUninstallScreen(root: AccessibilityNodeInfo, className: String?): Boolean {
        if (className?.contains("uninstall", true) == true) return true
        val text = StringBuilder()
        fun collect(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12 || text.length > 4000) return
            n.text?.let { text.append(it).append(' ') }
            for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1)
        }
        collect(root, 0)
        val t = text.toString().lowercase()
        return uninstallWords.any { it in t }
    }

    // ---------- Closing the tab ----------

    /** Buttons that open the tab overview (long press opens a "Close tab" menu). */
    private val tabButtonIds = listOf(
        "tab_switcher_button",                // Chrome, Brave, Vivaldi, Kiwi, Ecosia
        "mozac_browser_toolbar_tab_counter",  // Firefox
        "counter_root", "tab_counter",        // Firefox (older/newer versions)
        "tab_button", "tabs_button",          // Samsung Internet, others
    )

    /** Built-in menu entry texts (German Chrome calls it just "Schließen"). */
    private val builtInCloseTexts = setOf(
        "close tab", "close", "close this tab",
        "tab schließen", "tab schliessen", "schließen", "schliessen",
    )

    /** Names of the browsers' own text resources for "Close tab" / "Close". */
    private val closeResourceNames = listOf(
        "close_tab", "close", "menu_close_tab", "tab_close", "close_current_tab", "action_close_tab",
    )

    private val closeTextCache = mutableMapOf<String, Set<String>>()

    /**
     * "Close tab" texts for this browser. Besides the built-in ones, the texts are
     * read from the browser app itself, in the language the phone is set to -
     * so this works in French, Spanish, Turkish ... without translations here.
     */
    private fun closeTexts(pkg: String): Set<String> = closeTextCache.getOrPut(pkg) {
        val texts = builtInCloseTexts.toMutableSet()
        try {
            val res = packageManager.getResourcesForApplication(pkg)
            for (name in closeResourceNames) {
                val id = res.getIdentifier(name, "string", pkg)
                if (id != 0) texts += res.getString(id).trim().lowercase()
            }
        } catch (_: Exception) {}
        texts
    }

    /** Window IDs that existed before the long press: the menu is a new window. */
    private var windowsBefore: Set<Int> = emptySet()

    private fun rememberWindows() {
        windowsBefore = windows.map { it.id }.toSet()
    }

    /** What happened during the last attempt (written to logcat for troubleshooting). */
    private val closeLog = StringBuilder()

    private fun log(step: String) {
        if (closeLog.isNotEmpty()) closeLog.append("  ·  ")
        closeLog.append(step)
        android.util.Log.d("WebBlock", "tab close: $closeLog")
    }

    /**
     * Number of the current closing run. Hiding the block screen increases it,
     * so steps still waiting from an older run notice it and do nothing.
     */
    private var closeRun = 0

    private fun isCurrent(run: Int) = run == closeRun && overlay != null

    /**
     * Closes the browser tab that shows the blocked site, then goes to the
     * home screen by itself:
     * 1. Link opened from another app (Custom Tab): tap its X button.
     * 2. Normal tab: long press on the tab button and tap "Close tab" / "Schließen".
     *    First as an accessibility action, then as a real long-press gesture.
     * 3. If nothing works: go back so the blocked page is at least left.
     */
    private fun closeTab(root: AccessibilityNodeInfo, pkg: String) {
        val run = ++closeRun
        closeLog.setLength(0)
        log(pkg)
        setClosingUi(true)

        root.findAccessibilityNodeInfosByViewId("$pkg:id/close_button")?.firstOrNull()?.let {
            val ok = clickNodeOrParent(it)
            log("custom tab X: $ok")
            if (ok) { finish(run); return }
        }

        val tabButton = findTabButton(root, pkg)
        if (tabButton == null) {
            log("no tab button")
            fallback(run)
            return
        }
        log("tab button: " + (tabButton.viewIdResourceName?.substringAfter('/') ?: tabButton.contentDescription))
        val chromium = tabButton.viewIdResourceName?.endsWith("/tab_switcher_button") == true
        rememberWindows()
        log("long click: " + tabButton.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))

        waitForCloseItem(run, pkg, chromium, 20) { found ->
            if (found) { finish(run); return@waitForCloseItem }
            // Second try: a real long press on the button
            val bounds = Rect()
            tabButton.refresh()
            tabButton.getBoundsInScreen(bounds)
            log("gesture")
            rememberWindows()
            pressAt(bounds, 600) {
                waitForCloseItem(run, pkg, chromium, 20) { found2 ->
                    if (found2) finish(run)
                    else {
                        log("menu not found, seen: " + visibleTexts())
                        fallback(run)
                    }
                }
            }
        }
    }

    /** Looks for the "Close tab" entry for up to about a second and taps it. */
    private fun waitForCloseItem(
        run: Int, pkg: String, chromium: Boolean, tries: Int, onDone: (Boolean) -> Unit,
    ) {
        if (!isCurrent(run)) return
        val item = findCloseItem(pkg, chromium)
        if (item != null) {
            val ok = clickNodeOrParent(item)
            if (!ok) {
                val r = Rect()
                item.getBoundsInScreen(r)
                pressAt(r, 60) {}
            }
            log("close tab tapped (" + (if (ok) "action" else "gesture") + ")")
            onDone(true)
            return
        }
        if (tries <= 0) { onDone(false); return }
        handler.postDelayed({ waitForCloseItem(run, pkg, chromium, tries - 1, onDone) }, 50)
    }

    /** Closing didn't work: close a possibly open menu and leave the page. */
    private fun fallback(run: Int) {
        if (!isCurrent(run)) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            if (isCurrent(run)) performGlobalAction(GLOBAL_ACTION_BACK)
            finish(run)
        }, 200)
    }

    /** Tab is closed: go to the home screen and remove the block screen. */
    private fun finish(run: Int) {
        handler.postDelayed({
            if (!isCurrent(run)) return@postDelayed
            log("done")
            performGlobalAction(GLOBAL_ACTION_HOME)
            handler.postDelayed({ if (run == closeRun) hideOverlay() }, 200)
        }, 100)
    }

    /** While closing, the button shows what is happening and can't be tapped. */
    private fun setClosingUi(closing: Boolean) {
        val btn = overlay?.findViewById<Button>(R.id.btn_home) ?: return
        btn.isEnabled = !closing
        btn.setText(if (closing) R.string.closing_tab else R.string.go_home)
    }

    private fun findTabButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in tabButtonIds) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()?.let { return it }
        }
        // Fallback: a button whose description mentions tabs ("Switch or close tabs")
        return findNode(root) { n ->
            val d = n.contentDescription?.toString()?.lowercase()
            d != null && (n.isLongClickable || n.isClickable) && d.contains("tab")
        }
    }

    /**
     * Finds "Close tab" in the menu that opened after the long press. Only new
     * windows of the browser are searched, so a "Close" button on the web page
     * itself is never tapped by mistake.
     */
    private fun findCloseItem(pkg: String, chromium: Boolean): AccessibilityNodeInfo? {
        val texts = closeTexts(pkg)
        val menus = (windows.filter { it.id !in windowsBefore }.mapNotNull { it.root } +
            listOfNotNull(rootInActiveWindow?.takeIf { it.windowId !in windowsBefore }))
            .filter { it.packageName?.toString() == pkg }
        for (menu in menus) {
            findNode(menu) { n ->
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                t != null && t in texts
            }?.let { return it }
        }
        // Unknown language in a Chrome-based browser: "Close tab" is the first entry
        if (chromium) {
            for (menu in menus) {
                findNode(menu) { n ->
                    n.isClickable && (!n.text.isNullOrBlank() ||
                        (n.childCount > 0 && n.getChild(0)?.text?.isNotBlank() == true))
                }?.let { log("first menu entry"); return it }
            }
        }
        // Last resort (menu window not recognised): search all browser windows by text
        if (menus.isEmpty()) {
            for (r in browserRoots()) {
                findNode(r) { n ->
                    val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                    t != null && t in texts
                }?.let { return it }
            }
        }
        return null
    }

    /** Root nodes of all visible windows except our own block screen. */
    private fun browserRoots(): List<AccessibilityNodeInfo> =
        (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName?.toString() != packageName }

    /** A few texts on screen, to see in the log what the menu looked like. */
    private fun visibleTexts(): String {
        val texts = mutableListOf<String>()
        for (r in browserRoots()) {
            findNode(r) { n ->
                val t = n.text?.toString()
                if (t != null && t.isNotBlank() && t.length < 40) texts += t
                texts.size >= 10
            }
        }
        return texts.distinct().joinToString(", ").ifEmpty { "nothing" }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = findNode(node, 0, intArrayOf(0), match)

    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        count: IntArray,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (depth > 40 || ++count[0] > 800) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, depth + 1, count, match)?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    /**
     * Real touch at the middle of [bounds] (tap or long press). The block screen
     * lets touches through for that moment so they reach the browser.
     */
    private fun pressAt(bounds: Rect, durationMs: Long, then: () -> Unit) {
        if (bounds.isEmpty) { then(); return }
        setOverlayTouchable(false)
        val path = Path()
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val done = Runnable {
            handler.postDelayed({ setOverlayTouchable(true); then() }, 150)
        }
        val started = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.run() }
            override fun onCancelled(g: GestureDescription?) { log("gesture cancelled"); done.run() }
        }, handler)
        if (!started) { log("gesture not started"); done.run() }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val view = overlay ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    // ---------- Overlay ----------

    private fun showOverlay(url: String, rule: String) {
        val view = overlay ?: createOverlay() ?: return
        view.findViewById<TextView>(R.id.blocked_title).setText(R.string.blocked_title)
        view.findViewById<TextView>(R.id.blocked_url).text = BlockConfig.normalize(url)
        view.findViewById<TextView>(R.id.blocked_rule).text = getString(R.string.rule_label, rule)
    }

    private fun createOverlay(): View? {
        val view = LayoutInflater.from(ContextThemeWrapper(this, R.style.BlockedTheme))
            .inflate(R.layout.activity_blocked, null)
        view.findViewById<Button>(R.id.btn_home).setOnClickListener { onHomeTapped() }

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
        blockedApp = null
        closeRun++   // stops any closing steps that are still waiting
        val view = overlay ?: return
        overlay = null
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    /** "Go to home screen" (the tab has already been closed). */
    /** "Go to home screen": close the tab of the blocked site first, then go home. */
    private fun onHomeTapped() {
        val pkg = blockedPkg
        val root = pkg?.let { p -> browserRoots().firstOrNull { it.packageName?.toString() == p } }
        if (pkg == null || root == null) { leave(); return }
        closeTab(root, pkg)
    }

    private fun leave() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val run = closeRun
        handler.postDelayed({ if (run == closeRun) hideOverlay() }, 250)
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
