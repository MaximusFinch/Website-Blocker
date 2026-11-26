package app.siteblocker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.siteblocker.MainActivity
import app.siteblocker.R
import app.siteblocker.adblock.AdBlockList
import app.siteblocker.adblock.AdBlockService
import app.siteblocker.core.Stats
import java.util.Locale

/**
 * Page 2: the ad blocker – on/off, how much it blocked (today, week, total),
 * a chart per day, the most blocked addresses and the filter list.
 */
class AdBlockPage(private val a: MainActivity) {

    private val hero: View = a.findViewById(R.id.hero_ads)
    private val ring: RingView = a.findViewById(R.id.ring_ads)
    private val adsToday: TextView = a.findViewById(R.id.ads_today)
    private val status: TextView = a.findViewById(R.id.adblock_status)
    private val btnToggle: Button = a.findViewById(R.id.btn_adblock)
    private val statWeek: TextView = a.findViewById(R.id.ads_week)
    private val statTotal: TextView = a.findViewById(R.id.ads_total)
    private val statShare: TextView = a.findViewById(R.id.ads_share)
    private val chip7: TextView = a.findViewById(R.id.chip_7)
    private val chip30: TextView = a.findViewById(R.id.chip_30)
    private val chart: BarChartView = a.findViewById(R.id.chart_ads)
    private val chartSub: TextView = a.findViewById(R.id.chart_ads_sub)
    private val topList: LinearLayout = a.findViewById(R.id.top_list)
    private val listInfo: TextView = a.findViewById(R.id.adblock_list_info)
    private val btnUpdate: Button = a.findViewById(R.id.btn_adblock_update)

    /** Days shown in the chart: 7 or 30. */
    private var range = 7
    private var downloading = false

    init {
        ring.setIcon(R.drawable.ic_ads)
        // Can always be switched, also in Alpha mode
        btnToggle.setOnClickListener { if (AdBlockService.isEnabled(a)) turnOff() else enable() }
        ring.setOnClickListener { if (!AdBlockService.isEnabled(a)) enable() }
        btnUpdate.setOnClickListener { updateList() }
        chip7.setOnClickListener { range = 7; refresh() }
        chip30.setOnClickListener { range = 30; refresh() }
    }

    /** Ad blocker should be on but isn't running (e.g. Android stopped it): start it again. */
    fun restartIfNeeded() {
        if (!AdBlockService.isEnabled(a) || AdBlockService.running) return
        if (VpnService.prepare(a) == null) {
            try { AdBlockService.start(a) } catch (_: Exception) {}
        } else {
            AdBlockService.setEnabled(a, false)   // another VPN app has the slot now
            AdBlockService.setWaiting(a, true)
        }
    }

    /** Asks Android for the VPN permission (only the first time), then starts the ad blocker. */
    fun enable() {
        val consent = try { VpnService.prepare(a) } catch (_: Exception) { null }
        if (consent != null) {
            refresh()   // stays off until the permission is given
            @Suppress("DEPRECATION")
            a.startActivityForResult(consent, MainActivity.REQ_VPN)
            return
        }
        onVpnAllowed()
    }

    /** Answer of the VPN permission dialog. */
    fun onVpnResult(ok: Boolean) {
        if (ok) onVpnAllowed()
        else { a.toast("The ad blocker needs the VPN permission to work.", long = true); refresh() }
    }

    private fun onVpnAllowed() {
        AdBlockService.setEnabled(a, true)
        try { AdBlockService.start(a) } catch (_: Exception) {}
        // For the "turned off by another VPN" notification
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIF)
        refresh()
        // The list is downloaded the first time: show the result once it's there
        if (AdBlockList.count(a) == 0) {
            for (delay in listOf(3_000L, 8_000L, 15_000L, 30_000L)) a.handler.postDelayed({ refresh() }, delay)
        } else {
            a.handler.postDelayed({ refresh() }, 1_000)
        }
    }

    private fun turnOff() {
        AdBlockService.setEnabled(a, false)
        AdBlockService.setWaiting(a, false)   // turned off on purpose: no reminder
        try { AdBlockService.stop(a) } catch (_: Exception) {}
        refresh()
        a.handler.postDelayed({ refresh() }, 600)
    }

    private fun updateList() {
        if (downloading) return
        downloading = true
        refresh()
        Thread {
            var unsafe: String? = null
            val n = try { AdBlockList.download(a) }
            catch (e: AdBlockList.UnsafeListException) { unsafe = e.domain; -1 }
            catch (_: Exception) { -1 }
            a.runOnUiThread {
                downloading = false
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                a.toast(
                    if (unsafe != null) "The new list would block $unsafe, so it was ignored. The old list stays active."
                    else if (n < 0) "Couldn't download the list – check your internet connection."
                    else "List updated: ${num(n)} ad & tracker domains",
                    long = true,
                )
                refresh()
            }
        }.start()
    }

    fun refresh() {
        val on = AdBlockService.isEnabled(a)
        val running = AdBlockService.running
        val domains = AdBlockList.count(a)
        val today = Stats.today(a, Stats.ADS)

        // Hero
        val color = a.getColor(if (on) R.color.success else R.color.danger)
        ring.color = color
        ring.active = on && running
        hero.background = heroBackground(a, color)
        adsToday.text = num(today)
        adsToday.setTextColor(if (on) a.getColor(R.color.text) else a.getColor(R.color.text_dim))
        val since = AdBlockService.startedAt
        status.text = when {
            !on -> "●  Off – ads aren't blocked"
            domains == 0 -> "●  On – downloading the filter list…"
            running && since > 0 -> "●  On · protecting for ${duration(System.currentTimeMillis() - since)}"
            else -> "●  On"
        }
        status.setTextColor(color)
        if (on) {
            btnToggle.text = "Turn off"
            btnToggle.background = a.getDrawable(R.drawable.bg_button_outline)
            btnToggle.setTextColor(a.getColorStateList(R.color.text_dim))
        } else {
            btnToggle.text = "Turn on ad blocker"
            btnToggle.background = a.getDrawable(R.drawable.bg_button)
            btnToggle.setTextColor(a.getColorStateList(R.color.button_text))
        }

        // Numbers
        val days = Stats.days(a, Stats.ADS, range)
        val week = if (range >= 7) days.copyOfRange(days.size - 7, days.size).sum() else days.sum()
        statWeek.text = short(week.toLong())
        statTotal.text = short(Stats.total(a, Stats.ADS))
        val lookups = Stats.today(a, Stats.LOOKUPS)
        statShare.text = if (lookups > 0) String.format(Locale.ENGLISH, "%.0f%%", today * 100f / lookups) else "–"

        // Chart
        chip(chip7, range == 7)
        chip(chip30, range == 30)
        chart.setData(days, dayLabels(range))
        val sum = days.sum()
        chartSub.text = if (sum == 0) "Ads and trackers blocked per day. Nothing counted yet."
        else "${num(sum)} in the last $range days · on average ${num(sum / range)} a day"

        renderTop()

        // Filter list
        listInfo.text = if (domains == 0) "Filter list not downloaded yet"
        else "${num(domains)} ad & tracker domains · updated ${ago(AdBlockList.lastUpdate(a))}"
        btnUpdate.isEnabled = !downloading
        if (downloading) btnUpdate.text = "Downloading…" else btnUpdate.setText(R.string.adblock_update)
    }

    private fun chip(v: TextView, selected: Boolean) {
        v.background = a.getDrawable(if (selected) R.drawable.bg_range_on else R.drawable.bg_range_off)
        v.setTextColor(a.getColor(if (selected) R.color.accent2 else R.color.text_dim))
    }

    /** 12,345 → "12.3k", so it fits in a small tile. */
    private fun short(v: Long) = when {
        v >= 1_000_000 -> String.format(Locale.ENGLISH, "%.1fM", v / 1_000_000f)
        v >= 100_000 -> "${v / 1000}k"
        v >= 10_000 -> String.format(Locale.ENGLISH, "%.1fk", v / 1000f)
        else -> num(v)
    }

    /** The most blocked domains today, each with a bar for its share. */
    private fun renderTop() {
        topList.removeAllViews()
        val top = Stats.topToday(a, 6)
        if (top.isEmpty()) {
            topList.addView(TextView(a).apply {
                text = "Nothing blocked yet today."
                gravity = Gravity.CENTER
                setPadding(0, a.dp(14), 0, a.dp(4))
                setTextColor(a.getColor(R.color.text_hint))
            })
            return
        }
        val max = top.first().second.coerceAtLeast(1)
        for ((domain, count) in top) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, a.dp(10), 0, 0)
            }
            row.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(a).apply {
                    text = domain
                    textSize = 14f
                    setTextColor(a.getColor(R.color.text))
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(a).apply {
                    text = num(count)
                    textSize = 14f
                    setTextColor(a.getColor(R.color.text_dim))
                    setPadding(a.dp(10), 0, 0, 0)
                })
            })
            // Bar: share compared to the most blocked one
            val f = count.toFloat() / max
            row.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                background = a.getDrawable(R.drawable.bg_range_off)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(5))
                    .apply { topMargin = a.dp(6) }
                addView(View(a).apply {
                    background = a.getDrawable(R.drawable.bg_bar_fill)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, f)
                })
                addView(View(a).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - f)
                })
            })
            topList.addView(row)
        }
    }
}
