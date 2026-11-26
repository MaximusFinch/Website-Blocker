package app.siteblocker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Simple bar chart for daily numbers (oldest left, today right).
 * Today's bar is highlighted; the numbers of today and the best day are shown
 * above their bars. Drawn by hand, no chart library.
 */
class BarChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var values = IntArray(0)
    private var labels = emptyList<String>()

    var barColor = 0xFFFF7A1A.toInt()
        set(value) { field = value; invalidate() }
    var todayColor = 0xFFFFA24D.toInt()
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF858585.toInt()
        textSize = 11 * scaled
        textAlign = Paint.Align.CENTER
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        textSize = 11 * scaled
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setData(values: IntArray, labels: List<String>) {
        this.values = values
        this.labels = labels
        contentDescription = values.joinToString(", ")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0 || width == 0) return
        val labelH = 20 * density
        val topPad = 18 * density
        val chartH = height - labelH - topPad
        if (chartH <= 0) return
        val slot = width / n.toFloat()
        val barW = slot * if (n <= 7) 0.46f else 0.6f
        val radius = minOf(barW / 2, 7 * density)
        val max = values.max()
        val best = if (max > 0) values.indexOfLast { it == max } else -1
        val bottom = topPad + chartH

        for (i in 0 until n) {
            val left = i * slot + (slot - barW) / 2
            // Background track: the full height, so empty days are still visible
            rect.set(left, topPad, left + barW, bottom)
            canvas.drawRoundRect(rect, radius, radius, track)

            val v = values[i]
            if (v > 0 && max > 0) {
                val h = maxOf(chartH * v / max, radius * 2)
                rect.set(left, bottom - h, left + barW, bottom)
                val c = if (i == n - 1) todayColor else barColor
                bar.shader = LinearGradient(
                    0f, rect.top, 0f, rect.bottom,
                    c, (c and 0x00FFFFFF) or (if (i == n - 1) 0x99000000.toInt() else 0x55000000),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRoundRect(rect, radius, radius, bar)
                if (i == n - 1 || i == best) {
                    canvas.drawText(short(v), left + barW / 2, rect.top - 5 * density, valueText)
                }
            }

            // Label: every one for a week, every 5th (counted from today) for longer
            val label = labels.getOrNull(i) ?: continue
            if (n <= 7 || (n - 1 - i) % 5 == 0) {
                text.color = if (i == n - 1) 0xFFF5F5F5.toInt() else 0xFF858585.toInt()
                canvas.drawText(label, i * slot + slot / 2, height - 5 * density, text)
            }
        }
        if (max == 0) {
            valueText.isFakeBoldText = false
            valueText.color = 0xFFBDBDBD.toInt()
            canvas.drawText("No data yet", width / 2f, topPad + chartH / 2, valueText)
            valueText.color = 0xFFF5F5F5.toInt()
            valueText.isFakeBoldText = true
        }
    }

    /** 1234 -> "1.2k" so the number fits above a thin bar. */
    private fun short(v: Int) = when {
        v >= 1_000_000 -> String.format(java.util.Locale.ENGLISH, "%.1fM", v / 1_000_000f)
        v >= 10_000 -> "${v / 1000}k"
        v >= 1_000 -> String.format(java.util.Locale.ENGLISH, "%.1fk", v / 1000f)
        else -> v.toString()
    }
}
