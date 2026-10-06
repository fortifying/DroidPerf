package com.droidperf.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Circular gauge ring:
 * Always maintains a true 1:1 circle (never squished/oval) regardless of container dimensions.
 */
class CircularGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var labelText: String = "CPU"
    private var valueText: String = "0%"
    private var progressPercent: Float = 0f

    private var arcColor: Int = 0xFF00D2FF.toInt()
    private val trackColor: Int = 0xFF152230.toInt()
    private val labelColor: Int = 0xFF8295A8.toInt()
    private val valueColor: Int = Color.WHITE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        color = trackColor
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = valueColor
        textSize = sp(13.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val bounds = RectF()
    private var animator: ValueAnimator? = null
    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f

    fun setGaugeColor(color: Int) {
        arcColor = color
        arcPaint.color = color
        invalidate()
    }

    fun setGauge(label: String, value: String, percent: Float) {
        this.labelText = label
        this.valueText = value
        val clamped = percent.coerceIn(0f, 100f)

        if (kotlin.math.abs(progressPercent - clamped) < 0.5f) {
            progressPercent = clamped
            invalidate()
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(progressPercent, clamped).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressPercent = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = trackPaint.strokeWidth
        val diameter = (minOf(w, h) - stroke - dp(6f)).coerceAtLeast(0f)
        radius = diameter / 2f
        centerX = w / 2f
        centerY = h / 2f
        bounds.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.drawCircle(centerX, centerY, radius, trackPaint)

        arcPaint.color = arcColor
        val sweepAngle = (progressPercent / 100f) * 360f
        if (sweepAngle > 0.5f) {
            canvas.drawArc(bounds, -90f, sweepAngle, false, arcPaint)
        }

        val labelY = centerY - dp(5f)
        val valueY = centerY + dp(12f)
        canvas.drawText(labelText, centerX, labelY, labelPaint)
        canvas.drawText(valueText, centerX, valueY, valuePaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
