package app.siteblocker.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/** Small helpers shared by the pages. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun num(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)
fun num(n: Int): String = num(n.toLong())

/** "today", "yesterday", "3 days ago". */
fun ago(time: Long): String {
    val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
}

/** "4 min", "2 h 15 min", "3 days 4 h". */
fun duration(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    val d = min / (60 * 24)
    val h = (min / 60) % 24
    val m = min % 60
    return when {
        min < 1 -> "less than a minute"
        d > 0 -> "$d ${if (d == 1L) "day" else "days"}" + if (h > 0) " $h h" else ""
        h > 0 -> "$h h" + if (m > 0) " $m min" else ""
        else -> "$m min"
    }
}

/** Labels below the bars: weekdays for a week, day of the month for longer; the last one is "Today". */
fun dayLabels(count: Int): List<String> {
    val names = arrayOf("", "Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    return List(count) { i ->
        val back = count - 1 - i
        if (back == 0) "Today"
        else {
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
            if (count <= 7) names[c.get(Calendar.DAY_OF_WEEK)] else c.get(Calendar.DAY_OF_MONTH).toString()
        }
    }
}

/** Dark glass pill: only the dot and the outline carry the state color. */
fun TextView.pill(text: CharSequence, color: Int) {
    this.text = SpannableStringBuilder(text).apply {
        setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    background = GradientDrawable().apply {
        cornerRadius = context.dp(100).toFloat()
        setColor(0x59000000)
        setStroke(context.dp(1), (color and 0x00FFFFFF) or 0xFF000000.toInt())
    }
}

/** Big status card: dark, with a soft glow of the state color at the top (like VPN apps). */
fun heroBackground(ctx: Context, color: Int) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf((color and 0x00FFFFFF) or 0x38000000, 0xFF161616.toInt(), 0xFF161616.toInt()),
).apply {
    cornerRadius = ctx.dp(28).toFloat()
    setStroke(ctx.dp(1), (color and 0x00FFFFFF) or 0x66000000)
}
