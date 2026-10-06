package com.droidperf.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.res.ResourcesCompat
import com.droidperf.R
import com.droidperf.settings.OsdStyle
import kotlin.math.roundToInt

/**
 * The floating overlay surface. It is deliberately a single, small [View] rather than a
 * full-screen transparent window, so it never intercepts touches outside its own bounds.
 *
 * Supports render styles:
 * - MODERN: rounded horizontal pill HUD with styled badge chips
 * - CLASSIC: single-color vertical list
 * - RTSS: Authentic RivaTuner Statistics Server multi-column OSD
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
) : View(context) {

    private var osdStyle: OsdStyle = OsdStyle.MODERN
    private var lines: List<OsdLine> = emptyList()
    private var modernItems: List<ModernItem> = emptyList()
    private var rtssRows: List<RtssRow> = emptyList()
    private var classicLines: List<String>? = null

    private var textSizePx: Float = sp(11f)
    private var textColor: Int = Color.GREEN
    private var bgColor: Int = 0xDE0B0F15.toInt()
    private var textOpacity: Float = 1.0f
    private var bgOpacity: Float = 0.85f
    private var locked: Boolean = false

    private val unispaceTypeface: Typeface by lazy {
        try {
            ResourcesCompat.getFont(context, R.font.unispace_bold) ?: Typeface.MONOSPACE
        } catch (_: Throwable) {
            Typeface.MONOSPACE
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#33FFFFFF")
    }

    private val modernLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val modernValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    // RTSS Paints
    private val rtssFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val rtssStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.LEFT
        color = Color.BLACK
    }
    private val rtssUnitFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val rtssUnitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.LEFT
        color = Color.BLACK
    }

    private val bgRect = RectF()
    private val padH = dp(8f)
    private val padV = dp(6f)
    private var lineHeight = 0f
    private var rtssLineHeight = 0f
    private var boxWidth = 0
    private var boxHeight = 0

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    fun setOsdStyle(style: OsdStyle) {
        if (this.osdStyle != style) {
            this.osdStyle = style
            requestLayout()
            invalidate()
        }
    }

    fun setStyle(textSizeSp: Float, textColor: Int, bgColor: Int, textOpacity: Float, bgOpacity: Float) {
        this.textSizePx = sp(textSizeSp)
        this.textColor = textColor
        this.bgColor = bgColor
        this.textOpacity = textOpacity.coerceIn(0.1f, 1f)
        this.bgOpacity = bgOpacity.coerceIn(0f, 1f)
        applyTextSize()
        classicLines?.let { classic -> lines = classic.map { OsdLine(it, textColor) } }
        requestLayout()
        invalidate()
    }

    fun setLocked(value: Boolean) {
        locked = value
        params.flags = if (value) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        safeUpdate()
    }

    fun setLines(newLines: List<String>) {
        classicLines = newLines
        setStyledLines(newLines.map { OsdLine(it, textColor) })
    }

    fun setStyledLines(newLines: List<OsdLine>) {
        if (newLines == lines && osdStyle != OsdStyle.MODERN && rtssRows.isEmpty()) return
        lines = newLines
        requestLayout()
        invalidate()
    }

    fun setRtss(newRows: List<RtssRow>) {
        if (newRows == rtssRows && osdStyle == OsdStyle.RTSS) return
        this.rtssRows = newRows
        this.lines = newRows.map { OsdLine(it.toFormattedText(), it.labelColor) }
        this.osdStyle = OsdStyle.RTSS
        requestLayout()
        invalidate()
    }

    fun setModern(items: List<ModernItem>) {
        this.modernItems = items
        this.osdStyle = OsdStyle.MODERN
        requestLayout()
        invalidate()
    }

    private fun applyTextSize() {
        textPaint.textSize = textSizePx
        lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent + dp(1f) }
        modernLabelPaint.textSize = (textSizePx * 0.75f).coerceAtLeast(sp(8f))
        modernValuePaint.textSize = textSizePx.coerceAtLeast(sp(10f))

        rtssFillPaint.typeface = unispaceTypeface
        rtssFillPaint.textSize = textSizePx
        rtssStrokePaint.typeface = unispaceTypeface
        rtssStrokePaint.textSize = textSizePx
        rtssStrokePaint.strokeWidth = (textSizePx * 0.16f).coerceIn(dp(1.2f), dp(3.5f))

        val unitSize = (textSizePx * 0.68f).coerceAtLeast(sp(7f))
        rtssUnitFillPaint.typeface = unispaceTypeface
        rtssUnitFillPaint.textSize = unitSize
        rtssUnitStrokePaint.typeface = unispaceTypeface
        rtssUnitStrokePaint.textSize = unitSize
        rtssUnitStrokePaint.strokeWidth = (unitSize * 0.16f).coerceIn(dp(1.0f), dp(2.5f))

        rtssLineHeight = rtssFillPaint.fontMetrics.let { it.descent - it.ascent + dp(1.5f) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        applyTextSize()
        when (osdStyle) {
            OsdStyle.MODERN -> {
                var totalW = padH * 2
                val itemSpacing = dp(10f)
                for (item in modernItems) {
                    val lW = modernLabelPaint.measureText(item.label)
                    val vW = modernValuePaint.measureText(item.value)
                    totalW += lW + dp(4f) + vW + itemSpacing
                }
                if (modernItems.isNotEmpty()) totalW -= itemSpacing
                val valMetrics = modernValuePaint.fontMetrics
                val h = (valMetrics.descent - valMetrics.ascent + padV * 2).roundToInt()
                boxWidth = totalW.roundToInt().coerceAtLeast(dp(60f).roundToInt())
                boxHeight = h.coerceAtLeast(dp(26f).roundToInt())
            }
            OsdStyle.RTSS -> {
                if (rtssRows.isNotEmpty()) {
                    val charW = rtssFillPaint.measureText("0")
                    val unitCharW = rtssUnitFillPaint.measureText("0")
                    val labelColWidth = charW * 5.8f
                    val numSlotWidth = charW * 4.0f
                    val gapUnit = charW * 0.35f
                    val colGap = charW * 1.5f

                    val col1Width = numSlotWidth + gapUnit + (unitCharW * 3.2f) + colGap
                    val col2Width = numSlotWidth + gapUnit + (unitCharW * 2.2f) + colGap
                    val col3Width = numSlotWidth + gapUnit + (unitCharW * 3.2f)

                    val totalW = padH * 2 + labelColWidth + col1Width + col2Width + col3Width
                    val totalH = padV * 2 + (rtssLineHeight * rtssRows.size)

                    boxWidth = totalW.roundToInt().coerceAtLeast(dp(60f).roundToInt())
                    boxHeight = totalH.roundToInt().coerceAtLeast(1)
                } else {
                    val maxText = lines.maxOfOrNull { rtssFillPaint.measureText(it.text) } ?: 0f
                    boxWidth = (maxText + padH * 2).roundToInt().coerceAtLeast(1)
                    boxHeight = (rtssLineHeight * lines.size + padV * 2).roundToInt().coerceAtLeast(1)
                }
            }
            OsdStyle.CLASSIC -> {
                val maxText = lines.maxOfOrNull { textPaint.measureText(it.text) } ?: 0f
                val pH = padH * 2
                val pV = padV * 2
                boxWidth = (maxText + pH).roundToInt()
                boxHeight = (lineHeight * lines.size + pV).roundToInt()
            }
        }
        setMeasuredDimension(boxWidth.coerceAtLeast(1), boxHeight.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bgRect.set(0f, 0f, w, h)

        val bgAlpha = (bgOpacity * 255).roundToInt().coerceIn(0, 255)
        val strokeAlpha = (bgOpacity * 0x33).roundToInt().coerceIn(0, 255)

        val radius = when (osdStyle) {
            OsdStyle.MODERN -> dp(12f)
            OsdStyle.RTSS -> dp(2f)
            OsdStyle.CLASSIC -> dp(4f)
        }

        if (bgAlpha > 0) {
            bgPaint.color = (bgColor and 0x00FFFFFF) or (bgAlpha shl 24)
            canvas.drawRoundRect(bgRect, radius, radius, bgPaint)
        }
        if (strokeAlpha > 0 && osdStyle != OsdStyle.RTSS) {
            strokePaint.color = (0x00FFFFFF) or (strokeAlpha shl 24)
            canvas.drawRoundRect(bgRect, radius, radius, strokePaint)
        }

        when (osdStyle) {
            OsdStyle.MODERN -> {
                var curX = padH
                val cy = h / 2f
                val itemSpacing = dp(10f)
                val valFm = modernValuePaint.fontMetrics
                val valBaseline = cy - (valFm.ascent + valFm.descent) / 2f

                for (item in modernItems) {
                    modernLabelPaint.color = applyAlpha(MODERN_LABEL_COLOR, textOpacity)
                    val lblFm = modernLabelPaint.fontMetrics
                    val lblBaseline = cy - (lblFm.ascent + lblFm.descent) / 2f
                    canvas.drawText(item.label, curX, lblBaseline, modernLabelPaint)
                    curX += modernLabelPaint.measureText(item.label) + dp(4f)

                    modernValuePaint.color = applyAlpha(item.color, textOpacity)
                    canvas.drawText(item.value, curX, valBaseline, modernValuePaint)
                    curX += modernValuePaint.measureText(item.value) + itemSpacing
                }
            }
            OsdStyle.RTSS -> {
                if (rtssRows.isNotEmpty()) {
                    val charW = rtssFillPaint.measureText("0")
                    val unitCharW = rtssUnitFillPaint.measureText("0")
                    val labelColWidth = charW * 5.8f
                    val numSlotWidth = charW * 4.0f
                    val gapUnit = charW * 0.35f
                    val colGap = charW * 1.5f

                    val col1Width = numSlotWidth + gapUnit + (unitCharW * 3.2f) + colGap
                    val col2Width = numSlotWidth + gapUnit + (unitCharW * 2.2f) + colGap

                    var y = padV - rtssFillPaint.fontMetrics.ascent
                    for (row in rtssRows) {
                        drawOutlinedText(canvas, row.label, padH, y, row.labelColor, rtssFillPaint, rtssStrokePaint)

                        for (stat in row.stats) {
                            val colStartX = when (stat.slot) {
                                0 -> padH + labelColWidth
                                1 -> padH + labelColWidth + col1Width
                                2 -> padH + labelColWidth + col1Width + col2Width
                                else -> padH + labelColWidth + col1Width + col2Width + (stat.slot - 2) * col2Width
                            }

                            // Right-align number inside slot
                            val numW = rtssFillPaint.measureText(stat.value)
                            val numX = colStartX + (numSlotWidth - numW)
                            drawOutlinedText(canvas, stat.value, numX, y, stat.color, rtssFillPaint, rtssStrokePaint)

                            // Draw unit in smaller font right after number slot
                            if (stat.unit.isNotEmpty()) {
                                val unitX = colStartX + numSlotWidth + gapUnit
                                drawOutlinedText(canvas, stat.unit, unitX, y, stat.color, rtssUnitFillPaint, rtssUnitStrokePaint)
                            }
                        }
                        y += rtssLineHeight
                    }
                } else {
                    var y = padV - rtssFillPaint.fontMetrics.ascent
                    for (line in lines) {
                        drawOutlinedText(canvas, line.text, padH, y, line.color, rtssFillPaint, rtssStrokePaint)
                        y += rtssLineHeight
                    }
                }
            }
            OsdStyle.CLASSIC -> {
                var y = padV - textPaint.fontMetrics.ascent
                for (line in lines) {
                    textPaint.color = applyAlpha(line.color, textOpacity)
                    canvas.drawText(line.text, padH, y, textPaint)
                    y += lineHeight
                }
            }
        }
    }

    private fun drawOutlinedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        fillColor: Int,
        fillPaint: Paint,
        strokePaint: Paint,
    ) {
        val strokeCol = applyAlpha(Color.BLACK, textOpacity)
        strokePaint.color = strokeCol
        canvas.drawText(text, x, y, strokePaint)

        val fillCol = applyAlpha(fillColor, textOpacity)
        fillPaint.color = fillCol
        canvas.drawText(text, x, y, fillPaint)
    }

    private fun applyAlpha(color: Int, factor: Float): Int {
        val origAlpha = Color.alpha(color)
        val newAlpha = (origAlpha * factor.coerceIn(0.1f, 1f)).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (newAlpha shl 24)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                initialX = params.x
                initialY = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaX = (event.rawX - touchStartX).roundToInt()
                    val deltaY = (event.rawY - touchStartY).roundToInt()
                    val (clampedX, clampedY) = clampToScreen(initialX + deltaX, initialY + deltaY)
                    params.x = clampedX
                    params.y = clampedY
                    safeUpdate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    val deltaX = (event.rawX - touchStartX).roundToInt()
                    val deltaY = (event.rawY - touchStartY).roundToInt()
                    val (finalX, finalY) = clampToScreen(initialX + deltaX, initialY + deltaY)
                    params.x = finalX
                    params.y = finalY
                    safeUpdate()
                    onPositionChanged(finalX, finalY)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampToScreen(rawX: Int, rawY: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - height).coerceAtLeast(0)
        return Pair(rawX.coerceIn(0, maxX), rawY.coerceIn(0, maxY))
    }

    private fun safeUpdate() {
        try {
            windowManager.updateViewLayout(this, params)
        } catch (_: Throwable) {}
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private companion object {
        const val MODERN_LABEL_COLOR = 0xFF8295A8.toInt()
    }
}
