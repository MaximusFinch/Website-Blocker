package app.siteblocker.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The big round status sign at the top of a page: a glowing ring in the state
 * color with an icon in the middle. While protection is on, a soft wave pulses
 * outwards. Optionally a part of the ring is filled ([progress], 0..1).
 */
class RingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var color = 0xFF3DDC97.toInt()
        set(value) { if (field != value) { field = value; shader = null; invalidate() } }

    /** Pulse while protection is on. */
    var active = false
        set(value) { if (field != value) { field = value; updateAnimation(); invalidate() } }

    /** Filled part of the ring (0..1), or -1 for a full ring. */
    var progress = -1f
        set(value) { if (field != value) { field = value; invalidate() } }

    private var icon: Drawable? = null
    private var shader: RadialGradient? = null
    private var shaderSize = 0f
    private var pulse = 0f
    private var shownNow = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_600
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    fun setIcon(resId: Int) {
        icon = context.getDrawable(resId)?.mutate()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shownNow = isVisible
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (active && shownNow) { if (!animator.isStarted) animator.start() }
        else if (animator.isStarted) { animator.cancel(); pulse = 0f }
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (r <= 0f) return

        // Pulse wave
        if (active) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = withAlpha(color, 0.22f * (1f - pulse))
            canvas.drawCircle(cx, cy, r * (0.6f + 0.4f * pulse), paint)
        }

        // Thin outer ring
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = r * 0.018f
        paint.color = withAlpha(color, 0.28f)
        canvas.drawCircle(cx, cy, r * 0.9f, paint)

        // Main ring: track and (part) in the state color
        val ringR = r * 0.74f
        paint.strokeWidth = r * 0.075f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF2A2A2A.toInt()
        canvas.drawCircle(cx, cy, ringR, paint)
        paint.color = color
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        if (progress < 0f) canvas.drawCircle(cx, cy, ringR, paint)
        else if (progress > 0f) canvas.drawArc(oval, -90f, 360f * progress.coerceAtMost(1f), false, paint)

        // Inner disc with a soft glow
        val discR = r * 0.6f
        if (shader == null || shaderSize != discR) {
            shaderSize = discR
            shader = RadialGradient(
                cx, cy, discR,
                intArrayOf(withAlpha(color, 0.38f), withAlpha(color, 0.08f)), null, Shader.TileMode.CLAMP,
            )
        }
        paint.style = Paint.Style.FILL
        paint.shader = shader
        canvas.drawCircle(cx, cy, discR, paint)
        paint.shader = null

        // Icon
        icon?.let {
            val half = (r * 0.27f).toInt()
            it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            it.setTint(color)
            it.draw(canvas)
        }
    }
}
